"""spaa.decoder — ffmpeg 子进程解码（先探测码流参数，再按真实分辨率解）。

为什么要探测：
- 手机可能发 H.264 也可能发 HEVC，配置里写死一个，另一个就**完全没有画面**
  （ffmpeg 用错 demuxer 会直接退出，或吐垃圾帧）；
- 分辨率跟着手机设置走（480×640 / 1280×720 / 1920×1080），配置里的
  `decode_w/h` 只是兜底。拿它去强制 `-s` 会白做一次缩放，判断宽高比也会错；
- 帧率在 SPS 的 VUI 里，探测出来就能在 HUD 上显示真实值。

做法：把收到的 Annex-B 帧先攒着，`ffprobe -f h264|hevc -i pipe:0` 探到
`codec_name/width/height/r_frame_rate` 后再启动 ffmpeg；探测失败（比如一直是
P 帧、没有 SPS）4 秒后用配置兜底，绝不卡死不出图。

解码参数三处取舍（原样保留）：
- `-flags low_delay`：关掉重排缓冲；
- `-fps_mode passthrough`（老版本 ffmpeg 自动退回 `-vsync 0`）：不补帧不丢帧；
- `-probesize 32768 -analyzeduration 0`：起播快。
"""
from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import time

PROBE_TIMEOUT = 2.5
PROBE_INTERVAL = 0.4
FALLBACK_AFTER = 4.0
PENDING_MAX_BYTES = 2 << 20
PENDING_MAX_FRAMES = 90


class VideoDecoder:

    def __init__(self, cfg: dict, frame_q: "queue.Queue[tuple[bytes, float]]"):
        self.cfg = cfg
        self.q = frame_q

        # 探测前先用配置兜底；探测成功后覆盖
        self.codec = "hevc" if cfg.get("codec") == "hevc" else "h264"
        self.w = int(cfg["decode_w"])
        self.h = int(cfg["decode_h"])
        self.fps = 0.0
        self.frame_bytes = self.w * self.h * 3
        self.probed = False
        self.probe_desc = "待探测"

        self.proc: subprocess.Popen | None = None
        self.running = False
        self._latest: bytes | None = None
        self._seq = 0
        self._lock = threading.Lock()

        self.decoded = 0
        self.dec_fps = 0.0
        self.err = ""
        self.hw = "none"
        self._hw_try = 0
        self._legacy_sync = False       # 老 ffmpeg 没有 -fps_mode
        self._spawn_t = time.time()
        self._t_stat = time.time()
        self._dec_acc = 0
        self._t_start = time.time()
        self._last_probe = 0.0

        self._pending: list[bytes] = []
        self._pending_bytes = 0

        self._writer: threading.Thread | None = None
        self._reader: threading.Thread | None = None

    # ─────────── 对外 ───────────

    def size(self) -> tuple[int, int]:
        return self.w, self.h

    def start(self) -> bool:
        if self.running:
            return True
        self.running = True
        self._t_start = time.time()
        self.hw = "d3d11va" if self.cfg.get("hwaccel", "auto") in ("auto", "d3d11va") else "none"
        self._hw_try = 0
        # 先不 spawn：等探测到 SPS 再按真实参数起 ffmpeg
        self._writer = threading.Thread(target=self._write_loop, name="dec-wr", daemon=True)
        self._reader = threading.Thread(target=self._read_loop, name="dec-rd", daemon=True)
        self._writer.start()
        self._reader.start()
        return True

    def stop(self) -> None:
        self.running = False
        self._kill()

    def maybe_fallback(self) -> None:
        """硬解跑了几秒还一帧没出 → 切软解。

        d3d11va 对 pipe 进来的裸流可能既不报错也不出帧，进程一直挂着，
        读线程就永远阻塞在 read 上；必须由外部定时催一次。
        """
        if (self.hw != "none" and self._hw_try == 0 and self.decoded == 0
                and self.proc is not None and time.time() - self._spawn_t > 3.0):
            self._hw_try = 1
            self.hw = "none"
            self._kill()

    def latest(self) -> tuple[bytes | None, int]:
        with self._lock:
            return self._latest, self._seq

    def stats(self) -> dict:
        return {
            "decoded": self.decoded,
            "dec_fps": self.dec_fps,
            "hw": self.hw,
            "codec": self.codec,
            "size": f"{self.w}×{self.h}",
            "fps": self.fps,
            "probe": self.probe_desc,
            "err": self.err,
            "alive": self.proc is not None and self.proc.poll() is None,
        }

    # ─────────── 探测 ───────────

    @staticmethod
    def _codec_hint(data: bytes) -> str | None:
        """扫 Annex-B NAL 头，粗判 H.264 / HEVC，避免 ffprobe 用错解析器误判。"""
        h264 = hevc = False
        i = data.find(b"\x00\x00\x01")
        while i >= 0:
            j = i + 3
            if j >= len(data):
                break
            b = data[j]
            if b & 0x80 == 0:
                if b & 0x1F in (7, 8):
                    h264 = True
                if b in (0x40, 0x42, 0x44, 0x4E):
                    hevc = True
            i = data.find(b"\x00\x00\x01", j)
        if h264 and not hevc:
            return "h264"
        if hevc and not h264:
            return "hevc"
        return None

    def _ffprobe_path(self) -> str:
        p = self.cfg.get("ffprobe_path")
        if p:
            return p
        ff = self.cfg.get("ffmpeg_path", "ffmpeg")
        base = os.path.dirname(ff)
        if base:
            for name in ("ffprobe.exe", "ffprobe"):
                cand = os.path.join(base, name)
                if os.path.isfile(cand):
                    return cand
        return "ffprobe"

    def _probe_once(self, data: bytes, fmt: str) -> dict | None:
        cmd = [
            self._ffprobe_path(),
            "-v", "error",
            "-f", fmt,
            "-i", "pipe:0",
            "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height,r_frame_rate",
            "-of", "json",
        ]
        try:
            p = subprocess.run(cmd, input=data, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=PROBE_TIMEOUT)
        except (OSError, subprocess.SubprocessError):
            return None
        if p.returncode != 0:
            return None
        try:
            streams = (json.loads(p.stdout or b"{}") or {}).get("streams") or []
        except (ValueError, json.JSONDecodeError):
            return None
        if not streams:
            return None
        s = streams[0]
        try:
            w = int(s.get("width") or 0)
            h = int(s.get("height") or 0)
        except (TypeError, ValueError):
            return None
        if not (64 <= w <= 4096 and 64 <= h <= 4096):
            return None
        return {
            "codec": s.get("codec_name") or fmt,
            "w": w,
            "h": h,
            "fps": self._parse_rate(s.get("r_frame_rate")),
        }

    @staticmethod
    def _parse_rate(text: str | None) -> float:
        if not text or "/" not in str(text):
            return 0.0
        try:
            num, den = str(text).split("/", 1)
            v = float(num) / float(den or 1)
        except (ValueError, ZeroDivisionError):
            return 0.0
        return v if 1.0 <= v <= 240.0 else 0.0

    def _try_probe(self) -> bool:
        data = b"".join(self._pending)
        if len(data) < 128:
            return False
        hint = self._codec_hint(data)
        order = [hint] if hint else []
        for c in (self.codec, "h264", "hevc"):
            if c not in order:
                order.append(c)

        for fmt in order:
            info = self._probe_once(data, fmt)
            if not info:
                continue
            codec = info["codec"] if info["codec"] in ("h264", "hevc") else fmt
            # 解析器可能"成功"但读错，尺寸/编码要和 NAL 提示对得上才认
            if hint and codec != hint:
                continue
            self.codec = codec
            self.w, self.h = info["w"], info["h"]
            self.fps = info["fps"]
            self.frame_bytes = self.w * self.h * 3
            self.probed = True
            self.probe_desc = f"{codec} {self.w}×{self.h}" + (f"@{self.fps:g}fps" if self.fps else "")
            print(f"[dec] 码流探测：{self.probe_desc}", file=sys.stderr)
            return True
        return False

    # ─────────── 编解码进程 ───────────

    def _cmd(self, hw: str) -> list[str]:
        cmd = [
            self.cfg["ffmpeg_path"],
            "-hide_banner", "-loglevel", "error",
            "-flags", "low_delay",
            "-probesize", "32768", "-analyzeduration", "0",
        ]
        if hw != "none":
            cmd += ["-hwaccel", hw]
        cmd += ["-f", self.codec, "-i", "pipe:0"]
        cmd += ["-vsync", "0"] if self._legacy_sync else ["-fps_mode", "passthrough"]
        cmd += [
            "-f", "rawvideo", "-pix_fmt", "rgb24",
            "-s", f"{self.w}x{self.h}",
            "pipe:1",
        ]
        return cmd

    def _spawn(self) -> bool:
        try:
            self.proc = subprocess.Popen(
                self._cmd(self.hw),
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                bufsize=self.frame_bytes,
            )
            self._spawn_t = time.time()
            return True
        except (OSError, FileNotFoundError) as e:
            self.err = f"ffmpeg 启动失败：{e}"
            return False

    def _kill(self) -> None:
        p = self.proc
        self.proc = None
        if p:
            try:
                if p.stdin:
                    p.stdin.close()
            except OSError:
                pass
            try:
                p.kill()
            except OSError:
                pass

    # ─────────── 写：Annex-B → stdin ───────────

    def _write_loop(self) -> None:
        while self.running:
            try:
                frame, _t = self.q.get(timeout=0.3)
            except queue.Empty:
                frame = None

            if not self.probed:
                if frame:
                    self._push_pending(frame)
                now = time.time()
                if now - self._last_probe >= PROBE_INTERVAL and self._pending:
                    self._last_probe = now
                    if self._try_probe():
                        self._spawn()
                        self._flush_pending()
                    elif now - self._t_start > FALLBACK_AFTER:
                        self._fallback_params()
                        self._spawn()
                        self._flush_pending()
                elif not self._pending and now - self._t_start > FALLBACK_AFTER and self.proc is None:
                    self._fallback_params()
                    self._spawn()
                continue

            if frame is None:
                # 解码器没起来（ffmpeg 路径错/被杀）：定期重试，别让画面永远黑着
                if self.probed and self.proc is None and time.time() - self._spawn_t > 2.0:
                    if self._spawn():
                        self._flush_pending()
                continue
            proc = self.proc
            if proc is None:
                continue          # 重启中，直接丢，别把内存拖爆
            try:
                proc.stdin.write(frame)
                proc.stdin.flush()
            except (OSError, ValueError):
                time.sleep(0.05)

    def _push_pending(self, frame: bytes) -> None:
        self._pending.append(frame)
        self._pending_bytes += len(frame)
        while self._pending and (self._pending_bytes > PENDING_MAX_BYTES
                                 or len(self._pending) > PENDING_MAX_FRAMES):
            old = self._pending.pop(0)
            self._pending_bytes -= len(old)

    def _flush_pending(self) -> None:
        proc = self.proc
        if proc is None:
            return
        try:
            for f in self._pending:
                proc.stdin.write(f)
            proc.stdin.flush()
        except (OSError, ValueError):
            pass
        self._pending.clear()
        self._pending_bytes = 0

    def _fallback_params(self) -> None:
        self.probed = True
        self.probe_desc = f"未探测到 SPS，按配置 {self.codec} {self.w}×{self.h}"
        print(f"[dec] {self.probe_desc}", file=sys.stderr)

    # ─────────── 读：rawvideo → latest ───────────

    def _read_loop(self) -> None:
        while self.running:
            proc = self.proc
            if proc is None:
                time.sleep(0.05)
                continue
            n = self.frame_bytes
            try:
                buf = self._read_exact(proc.stdout, n)
            except (OSError, ValueError):
                buf = b""
            if not buf:
                self._on_exit()
                continue

            with self._lock:
                self._latest = buf
                self._seq += 1
            self.decoded += 1
            self._dec_acc += 1
            now = time.time()
            dt = now - self._t_stat
            if dt >= 1.0:
                self.dec_fps = self._dec_acc / dt
                self._dec_acc = 0
                self._t_stat = now

    def _read_exact(self, stream, n: int) -> bytes:
        chunks = []
        got = 0
        while got < n:
            c = stream.read(n - got)
            if not c:
                return b""
            chunks.append(c)
            got += len(c)
        return b"".join(chunks)

    def _on_exit(self) -> None:
        if not self.running:
            return
        proc = self.proc
        err_txt = ""
        if proc:
            try:
                err_txt = proc.stderr.read(4096).decode("utf-8", "replace").strip()
            except (OSError, ValueError):
                pass
        if err_txt:
            self.err = err_txt.splitlines()[-1][:200]
            # 老 ffmpeg 不认 -fps_mode：换回 -vsync 0 再来一次
            if not self._legacy_sync and ("fps_mode" in err_txt or "Unrecognized option" in err_txt
                                          or "Option not found" in err_txt):
                self._legacy_sync = True

        if self.hw != "none" and self._hw_try == 0 and self.decoded == 0:
            self._hw_try = 1
            self.hw = "none"
            self._kill()
            time.sleep(0.2)
            self._spawn()
            return

        self._kill()
        time.sleep(0.5)
        if self.running:
            self._spawn()
