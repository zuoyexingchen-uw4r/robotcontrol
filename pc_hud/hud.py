#!/usr/bin/env python3
"""狼牙视觉 · 笔记本操控 HUD 启动入口。

用法：
    python hud.py                 # 默认配置启动
    python hud.py --check         # 只检查依赖和配置，不启动 UI

依赖：PyQt5（pip install PyQt5）+ ffmpeg（系统 PATH 或设置里填绝对路径）
"""

from __future__ import annotations

import sys


def check_deps() -> bool:
    ok = True
    try:
        import PyQt5  # noqa: F401
        print("✓ PyQt5")
    except ImportError:
        print("✗ PyQt5 未安装：pip install PyQt5")
        ok = False
    import shutil
    from warhud import config as cfgmod
    cfg = cfgmod.load()
    ff = shutil.which(cfg["ffmpeg_path"]) or (
        cfg["ffmpeg_path"] if __import__("os").path.exists(cfg["ffmpeg_path"]) else None)
    if ff:
        print(f"✓ ffmpeg：{ff}")
    else:
        print(f"✗ ffmpeg 找不到（{cfg['ffmpeg_path']}）：请安装或修改 ~/.warhud/config.json")
        ok = False
    return ok


def main() -> int:
    if "--check" in sys.argv:
        return 0 if check_deps() else 1

    if not check_deps():
        print("\n依赖不满足，退出。修复后重试。")
        return 1

    from PyQt5.QtWidgets import QApplication
    from warhud.main_window import MainWindow

    app = QApplication(sys.argv)
    win = MainWindow()
    win.show()
    return app.exec_()


if __name__ == "__main__":
    sys.exit(main())
