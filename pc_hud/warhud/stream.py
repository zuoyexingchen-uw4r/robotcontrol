"""warhud.stream — ffmpeg 子进程解码裸 H.264/HEVC 流为 RGB 帧。

为什么不直接用 ffplay：HUD 叠加层（检测框/准星/提前量）必须和视频帧
画在同一个 PyQt 窗口里，ffplay 独占窗口做不到。所以用 ffmpeg 的
rawvideo 管道输出，每帧 W*H*3 字节，交给 QImage 后由 overlay 绘制。

ffmpeg 命令要点（都是踩过的坑）：
- -flags low_delay：关掉解码器的帧重排缓冲（HEVC 尤其明显，否则 +2~4 帧延迟）
- -f h264 / -f hevc：手机推的是无封装裸流（无 mp4/ts 容器），必须显式指定
- udp://...?fifo_size=8388608：Windows 默认 UDP 接收缓冲只有 64KB，
  一帧 100KB+ 的突发直接溢出丢包——fifo_size 加大循环缓冲
- overrun_nonfatal=1：缓冲溢出时跳过旧数据而不是直接退出
- -s WxH 输出缩放解码：手机可能 1080p60 推流，HUD 1280x720 解码减负
"""

from __future__ import annotations

import subprocess
import threading


class StreamDecoder:
    """后台线程读 ffmpeg stdout，维护最新帧 + 统计。"""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.proc: subprocess.Popen | None = None
        self.thread: threading.Thread | None = None
        self._latest: bytes | None = None   # 最新一帧 rgb24
        self._lock = threading.Lock()
        self._frame_seq = 0
        self.running = False
        self.frames_decoded = 0
        self.last_error = ""

    @property
    def frame_size(self) -> int:
        return self.cfg["decode_w"] * self.cfg["decode_h"] * 3

    def start(self) -> bool:
        if self.running:
            return True
        codec = "hevc" if self.cfg["codec"] == "hevc" else "h264"
        url = (f"udp://{self.cfg['video_ip']}:{self.cfg['video_port']}"
               f"?fifo_size=8388608&overrun_nonfatal=1&reuse=1")
        cmd = [
            self.cfg["ffmpeg_path"],
            "-hide_banner", "-loglevel", "warning",
            "-f", codec,
            "-flags", "low_delay",
            "-i", url,
            "-f", "rawvideo",
            "-pix_fmt", "rgb24",
            "-s", f"{self.cfg['decode_w']}x{self.cfg['decode_h']}",
            "-",
        ]
        try:
            self.proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                bufsize=self.frame_size,
            )
        except FileNotFoundError:
            self.last_error = (f"找不到 ffmpeg：{self.cfg['ffmpeg_path']}。"
                               "请安装 ffmpeg 或在设置里填绝对路径")
            return False
        except OSError as e:
            self.last_error = f"ffmpeg 启动失败：{e}"
            return False

        self.running = True
        self.thread = threading.Thread(target=self._run, name="ffmpeg-reader", daemon=True)
        self.thread.start()
        return True

    def _run(self) -> None:
        proc, n = self.proc, self.frame_size
        try:
            while self.running:
                buf = b""
                while len(buf) < n:
                    chunk = proc.stdout.read(n - len(buf))
                    if not chunk:
                        raise EOFError("ffmpeg 退出")
                    buf += chunk
                with self._lock:
                    self._latest = buf
                    self._frame_seq += 1
                self.frames_decoded += 1
        except (EOFError, OSError) as e:
            self.last_error = str(e)
        finally:
            self.running = False

    def latest_frame(self) -> tuple[bytes | None, int]:
        """返回 (rgb24 帧数据, 序号)。序号用于 HUD 判断是否换了新帧。"""
        with self._lock:
            return self._latest, self._frame_seq

    def stop(self) -> None:
        self.running = False
        proc = self.proc
        if proc:
            try:
                proc.kill()
            except OSError:
                pass
        self.proc = None
