"""smoke_test — 无显示器环境（QT_QPA_PLATFORM=offscreen）的 UI 冒烟测试。

验证：窗口组装、信号桥、HUD 离屏绘制、vision 消息处理、
设置开关下发逻辑（phone 未启动不崩）、ESP32 未连接不崩。
"""

import os
import sys

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from PyQt5.QtGui import QImage, QPainter

ok = 0
total = 0


def check(name, cond, detail=""):
    global ok, total
    total += 1
    print(("  ✓ " if cond else "  ✗ ") + name + ("" if cond else f" {detail}"))
    if cond:
        ok += 1


def main() -> int:
    from PyQt5.QtWidgets import QApplication
    from warhud.main_window import MainWindow
    from warhud.overlay import HudOverlay
    from warhud import config as cfgmod

    app = QApplication(sys.argv)

    # 1) 主窗口组装
    win = MainWindow()
    win.show()  # offscreen 下 isVisible() 依赖窗口已 show
    app.processEvents()
    check("主窗口创建", win is not None)
    check("设置面板默认隐藏", not win.settings_panel.isVisible())
    check("开关数量 = 8", len(win._switches) == 8)

    # 2) Tab 切换设置面板
    win._toggle_settings()
    check("Tab 后面板可见", win.settings_panel.isVisible())
    win._toggle_settings()

    # 3) vision 消息处理（含 fire 指令）
    msg = {
        "t": "vision", "ts": 1, "fps": 12.0, "lat_ms": 42,
        "targets": [
            {"type": "highland", "cx": 300, "cy": 200, "w": 90, "h": 60, "conf": 0.9},
            {"type": "base", "cx": 100, "cy": 400, "w": 50, "h": 50, "conf": 0.8},
        ],
        "track": {"type": "highland", "cx": 301, "cy": 201, "vx": 3.0, "vy": -1.0,
                  "dist_mm": 1344, "yaw_deg": 1.2, "pitch_deg": -0.3},
        "fire": {"should": True, "yaw": 1.2, "pitch": -0.3, "rpm": 12000,
                 "reason": "in_tol"},
        "proc_ms": {"pre": 12, "det": 18, "track": 1, "total": 31},
    }
    ammo_before = win.hud.ammo
    win._on_vision(msg)
    check("vision 消息处理", win.hud.vision is not None and win.hud.vision_lat_ms == 42)
    check("fire 指令扣弹药", win.hud.ammo == ammo_before - 1,
          f"{ammo_before} -> {win.hud.ammo}")

    # 4) settings 快照刷新开关
    win._on_settings({"deblur": True, "tracking": True, "navigation": False})
    check("settings 刷新开关", win._switches["deblur"].isChecked()
          and win._switches["tracking"].isChecked()
          and not win._switches["navigation"].isChecked())

    # 5) 开关下发（phone 未启动，返回 False 但不崩）
    win._switches["deglare"].setChecked(True)
    check("开关下发不崩溃（未连接手机）", True)

    # 6) HUD 离屏绘制（1280×720）
    img = QImage(1280, 720, QImage.Format_RGB888)
    img.fill(0)
    p = QPainter(img)
    hud = HudOverlay(win.cfg)
    hud.vision = msg
    hud.telem = {"voltage_v": 12.3, "current_a": 5.6, "temp_c": 45.2,
                 "rpm_left": 12000, "rpm_right": 11800, "status": 3}
    hud.telem_alive = True
    hud.vision_alive = True
    hud.video_alive = False
    hud.esp32_alive = True
    try:
        hud.paint(p, 1280, 720)
        check("HUD 离屏绘制（无视频）", True)
    except Exception as e:  # noqa: BLE001
        check("HUD 离屏绘制（无视频）", False, str(e))
    # 有视频 + 空 vision 也画一遍
    hud.vision = None
    hud.video_alive = True
    try:
        hud.paint(p, 1280, 720)
        check("HUD 离屏绘制（有视频无目标）", True)
    except Exception as e:  # noqa: BLE001
        check("HUD 离屏绘制（有视频无目标）", False, str(e))
    p.end()

    # 7) ESP32 未连接时 fire 不崩
    win.esp32.queue_fire()
    check("ESP32 未连接 fire 不崩", True)

    # 8) 遥测信号
    win._on_telem({"voltage_v": 11.9, "current_a": 1.0, "temp_c": 30.0,
                   "rpm_left": 0, "rpm_right": 0, "status": 1})
    check("遥测信号处理", win.hud.telem["voltage_v"] == 11.9)

    # 9) 输入状态写入
    win.esp32.current_input["yaw"] = 5.0
    check("输入状态写入", win.esp32.current_input["yaw"] == 5.0)

    win.close()
    print(f"\n{ok}/{total} 通过" + (" ✅" if ok == total else " ❌"))
    return 0 if ok == total else 1


if __name__ == "__main__":
    sys.exit(main())
