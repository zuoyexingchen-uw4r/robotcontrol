"""spaa.decoder — ffmpeg 子进程解码（硬解优先，rawvideo 管道输出）。

为什么不用 ffplay：HUD 的叠加层必须和视频画在同一块 GL 画布上，
ffplay 独占窗口做不到。这里只借 ffmpeg 的解码器，像素从 stdout 拉回来上传纹理。

延迟相关的三处取舍：
- `-flags low_delay`：关掉解码器重排缓冲（HEVC 尤其明显）
- `-vsync 0`：**不补帧不丢帧**，进来一帧解一帧。裸流没有时间戳，
  默认 CFR 会按猜的帧率重复/丢帧，凭空制造抖动。
- `-probesize 32768 -analyzeduration 0`：起播快，又不至于认不出 SPS/PPS。

硬解（d3d11va）只做解码，帧仍需下载回内存才能上传纹理；
真正省的是 CPU 占用与解码耗时。失败自动回退软解，不影响出图。
"""
from __future__ import annotations

import queue
import subprocess
import threading
import time


class VideoDecoder:

    def __init__(self, cfg: dict, frame_q: "queue.Queue[tuple[bytes, float]]"):
        self.cfg = cfg
        self.q = frame_q
        self.w = int(cfg["decode_w"])
        self.h = int(cfg["decode_h"])
        self.frame_bytes = self.w * self.h * 3

        self.proc: subprocess.Popen | None = None
        self.running = False
        self._latest: bytes | None = None
        self._seq = 0
        self._lock = threading.Lock()

        self.decoded = 0
        self.dec_fps = 0.0
        self.decode_ms = 0.0
        self.err = ""
        self.hw = "none"
        self._hw_try = 0
        self._spawn_t = time.time()
        self._t_stat = time.time()
        self._dec_acc = 0

        self._writer: threading.Thread | None = None
        self._reader: threading.Thread | None = None

    # ─────────── 启停 ───────────

    def start(self) -> bool:
        if self.running:
            return True
        self.running = True
        hw = self.cfg.get("hwaccel", "auto")
        self._hw_try = 0
        self.hw = "d3d11va" if hw in ("auto", "d3d11va") else "none"
        if not self._spawn():
            return False
        self._writer = threading.Thread(target=self._write_loop, name="dec-wr", daemon=True)
        self._reader = threading.Thread(target=self._read_loop, name="dec-rd", daemon=True)
        self._writer.start()
        self._reader.start()
        return True

    def stop(self) -> None:
        self.running = False
        self._kill()

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

    def _cmd(self, hw: str) -> list[str]:
        codec = "hevc" if self.cfg.get("codec") == "hevc" else "h264"
        cmd = [
            self.cfg["ffmpeg_path"],
            "-hide_banner", "-loglevel", "error",
            "-flags", "low_delay",
            "-probesize", "32768", "-analyzeduration", "0",
        ]
        if hw != "none":
            cmd += ["-hwaccel", hw]
        cmd += [
            "-f", codec, "-i", "pipe:0",
            # 新版 ffmpeg 已移除 -vsync（报 "Option not found"），改用 fps_mode。
            # passthrough = 不补帧不丢帧，进来一帧解一帧。
            "-fps_mode", "passthrough",
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

    def maybe_fallback(self) -> None:
        """硬解跑了几秒还一帧没出 → 切软解重启。

        为什么不能只靠「进程退出」判断：d3d11va 对 pipe 进来的裸流可能既不报错
        也不出帧，进程一直挂着，读线程就永远阻塞在 read 上。必须由外部定时催一次。
        """
        if (self.hw != "none" and self._hw_try == 0 and self.decoded == 0
                and time.time() - self._spawn_t > 3.0 and self.proc is not None):
            self._hw_try = 1
            self.hw = "none"
            self._kill()          # 读线程随之返回，_on_exit 会用软解重新 spawn

    # ─────────── 写：Annex-B 帧 → stdin ───────────

    def _write_loop(self) -> None:
        while self.running:
            proc = self.proc
            if proc is None:
                time.sleep(0.05)
                continue
            try:
                frame, _t = self.q.get(timeout=0.3)
            except queue.Empty:
                continue
            try:
                proc.stdin.write(frame)
                proc.stdin.flush()
            except (OSError, ValueError):
                # 解码器死了：交给 _read_loop 的重启逻辑处理
                time.sleep(0.05)

    # ─────────── 读：rawvideo → latest ───────────

    def _read_loop(self) -> None:
        n = self.frame_bytes
        while self.running:
            proc = self.proc
            if proc is None:
                time.sleep(0.05)
                continue
            try:
                buf = self._read_exact(proc.stdout, n)
            except (OSError, ValueError):
                buf = b""
            if not buf:
                # ffmpeg 退出：硬解不可用则回退软解，否则重启进程
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

        # 硬解失败 → 软解重启（只回退一次，避免死循环）
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

    # ─────────── 取帧 ───────────

    def latest(self) -> tuple[bytes | None, int]:
        with self._lock:
            return self._latest, self._seq

    def stats(self) -> dict:
        return {
            "decoded": self.decoded,
            "dec_fps": self.dec_fps,
            "hw": self.hw,
            "err": self.err,
            "alive": self.proc is not None and self.proc.poll() is None,
        }
