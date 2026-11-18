"""端到端联调：ffmpeg 生成 H.264 → 按 GameStream 封包发给本机 → 窗口截图。

不开手机也能验证：FEC 组帧、ffmpeg 解码（硬解回落）、GL 纹理、HUD 合成。
跑法：python host/spaa/_e2e.py
"""
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PyQt5.QtCore import QTimer
from PyQt5.QtWidgets import QApplication

from spaa import config as cfgmod
from spaa.selftest import _build_gs_frame
from spaa.window import MainWindow

OUT = os.path.join(tempfile.gettempdir(), "spaa_e2e.png")
W, H = 1280, 720


def gen_stream(ffmpeg: str, path: str, seconds: float = 3.0) -> bytes:
    cmd = [ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
           "-f", "lavfi", "-i", f"testsrc2=size={W}x{H}:rate=30",
           "-t", str(seconds), "-c:v", "libx264", "-preset", "ultrafast",
           "-tune", "zerolatency", "-g", "30", "-pix_fmt", "yuv420p",
           "-f", "h264", path]
    subprocess.run(cmd, check=True)
    with open(path, "rb") as f:
        return f.read()


def split_frames(data: bytes):
    """按 startcode 切 NAL，再按 VCL NAL 合成帧（每帧带 SPS/PPS 那一份）。"""
    starts = [m.start() for m in re.finditer(b"\x00\x00\x01", data)]
    nals = []
    for i, s in enumerate(starts):
        e = starts[i + 1] if i + 1 < len(starts) else len(data)
        nals.append(data[s:e])

    frames, cur, has_vcl = [], b"", False
    for nal in nals:
        t = nal[3] & 0x1F
        if t in (1, 5):
            if has_vcl:
                frames.append(cur)
                cur = b""
            cur += nal
            has_vcl = True
        else:
            cur += nal
    if cur:
        frames.append(cur)
    return frames


def main() -> int:
    cfg = cfgmod.load()
    ff = cfg["ffmpeg_path"]
    tmp = os.path.join(tempfile.gettempdir(), "spaa_e2e.h264")
    print("[e2e] 生成测试码流…")
    data = gen_stream(ff, tmp)
    frames = split_frames(data)
    print(f"[e2e] 共 {len(frames)} 帧，{len(data) / 1024:.0f} KB")

    # 发送线程：严格按 1/30 秒节奏发，模拟手机端 pacing
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    target = ("127.0.0.1", cfg["video_port"])
    sending = {"on": True}

    def sender():
        print(f"[e2e] sender start → {target}", flush=True)
        seq = 0
        t0 = time.time()
        for i, fr in enumerate(frames):
            if not sending["on"]:
                break
            pkts, _ = _build_gs_frame(fr, seq0=seq, frame_index=i)
            if i == 0:
                print(f"[e2e] 首帧 {len(fr)}B → {len(pkts)} 包，"
                      f"首包 {len(pkts[0])}B", flush=True)
            for p in pkts:
                try:
                    sock.sendto(p, target)
                except OSError as e:
                    print("[e2e] sendto 失败", e, flush=True)
                    return
            seq = (seq + len(pkts)) & 0xFFFF
            due = t0 + (i + 1) / 30.0
            dt = due - time.time()
            if dt > 0:
                time.sleep(dt)
        print("[e2e] 发送完毕", flush=True)

    app = QApplication(sys.argv)
    win = MainWindow(cfg)
    win.resize(1600, 900)
    win.show()

    th = threading.Thread(target=sender, daemon=True)
    QTimer.singleShot(400, th.start)

    def shot():
        img = win.view.grabFramebuffer()
        img.save(OUT)
        dec = win.dec.stats()
        rx = win.rx.snapshot()
        print(f"[e2e] rx: out={rx['out']} rs={rx['rs']} buf={rx['buf']} "
              f"gap={rx['gap']} kbps={rx['kbps']:.0f} conn={rx['connected']}")
        print(f"[e2e] 队列 {win.rx_q.qsize()}，解码 {dec['decoded']} 帧，"
              f"hw={dec['hw']}，fps={dec['dec_fps']:.1f}，err={dec['err'][:120]}")
        print("[e2e] 截图", OUT)
        sending["on"] = False
        win.close()
        app.quit()

    QTimer.singleShot(3800, shot)
    return app.exec_()


if __name__ == "__main__":
    sys.exit(main())
