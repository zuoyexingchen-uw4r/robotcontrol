#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""图传上位机入口（狼牙视觉 · SPAA 布局）。

用法：
    python host/spaa_host.py              # 启动界面
    python host/spaa_host.py --selftest   # 纯逻辑自测（不开窗口）
    python host/spaa_host.py --check      # 只检查依赖

快捷键：
    F11 全屏 / Esc 退出        R 录制裸流
    P 截图（含 HUD）           K 请求关键帧
    O 测距环开关               Q 退出
"""
from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))


def check_deps() -> bool:
    ok = True
    try:
        import PyQt5  # noqa: F401
        print("[ok] PyQt5")
    except ImportError:
        print("[--] PyQt5 未安装：pip install PyQt5")
        ok = False
    try:
        import OpenGL  # noqa: F401
        print("[ok] PyOpenGL")
    except ImportError:
        print("[--] PyOpenGL 未安装：pip install PyOpenGL")
        ok = False
    import shutil
    from spaa import config as cfgmod
    cfg = cfgmod.load()
    ff = cfg["ffmpeg_path"]
    if shutil.which(ff) or os.path.exists(ff):
        print(f"[ok] ffmpeg: {ff}")
    else:
        print(f"[--] ffmpeg 找不到（{ff}），装一个或改 ~/.spaa/config.json")
        ok = False
    print(f"     视频端口 {cfg['video_port']}/控制 {cfg['ctrl_port']}，"
          f"雷达 {cfg['laser_udp']}，视觉 {cfg['vision_port']}")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser(description="狼牙视觉图传上位机")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        from spaa.selftest import run
        return run()

    if args.check:
        return 0 if check_deps() else 1

    for s in (sys.stdout, sys.stderr):
        try:
            s.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass

    try:
        from PyQt5.QtCore import Qt
        from PyQt5.QtWidgets import QApplication
        QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
        QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)
    except ImportError as e:
        print(f"PyQt5 缺失：{e}")
        return 1

    from spaa.window import MainWindow

    app = QApplication(sys.argv)
    win = MainWindow()
    win.show()
    return app.exec_()


if __name__ == "__main__":
    sys.exit(main())
