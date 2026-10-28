"""warhud.config — HUD 运行配置（JSON 持久化到用户目录）。"""

from __future__ import annotations

import json
import os
import shutil
from dataclasses import dataclass, field, asdict

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".warhud")
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")

DEFAULTS = {
    # 视频流（手机 CameraStreamer 推送目标 = 本机）
    "video_ip": "0.0.0.0",        # 监听地址（0.0.0.0 = 本机所有网卡）
    "video_port": 31000,
    "codec": "h264",              # h264 | hevc
    "decode_w": 1280,
    "decode_h": 720,

    # ffmpeg 可执行文件（PATH 找不到时在此填绝对路径；
    # 也可放到 pc_hud/bin/ffmpeg.exe，load() 会自动优先使用）
    "ffmpeg_path": "ffmpeg",

    # 视觉结果 / 设置同步（手机广播源）
    "vision_port": 31001,         # 收手机广播
    "cmd_port": 31002,            # 发设置变更
    "phone_ip": "",               # 手机 IP（收到广播后自动记住，留空=自动）

    # ESP32-S3 WLAN 直连（操作输入 + 遥测）
    "esp32_ip": "192.168.1.50",
    "esp32_input_port": 31010,
    "esp32_telem_port": 31011,
    "input_hz": 10,               # 输入帧发送频率

    # USB 手柄操控（pygame/SDL；未插手柄自动降级键鼠）
    # 不同手柄 axis/button 序号有差异时，用 gamepad_map 覆盖（见 warhud/gamepad.py）
    "gamepad_deadzone": 0.12,
    "gamepad_aim_exponent": 2.0,
    "gamepad_move_exponent": 1.6,
    "gamepad_move_max_mmps": 800,     # 摇杆满格底盘速度
    "gamepad_aim_max_dps": 90.0,      # 摇杆满格云台角速度
    "gamepad_aim_slow_scale": 0.35,   # LT 精确瞄准倍率
    "gamepad_poll_hz": 100,
    "gamepad_map": {},

    # HUD 行为
    "crosshair_color": "#7CFC00",
    "detect_color": "#FF3B30",
    "track_color": "#FFD60A",
    "panel_opacity": 0.78,
    "show_fps": True,
    "match_minutes": 5,           # 比赛时长（分钟）
}


def _local_ffmpeg() -> str:
    """项目自带 ffmpeg（pc_hud/bin/ffmpeg.exe）：PATH 里没有时用它。
    静态构建放进去即可，换机器不用改配置。"""
    p = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                     "bin", "ffmpeg.exe")
    return p if os.path.exists(p) else ""


def load() -> dict:
    cfg = dict(DEFAULTS)
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            cfg.update(json.load(f))
    except (OSError, json.JSONDecodeError):
        pass
    # ffmpeg 解析顺序：config 绝对路径 → PATH → 项目本地 bin/
    ff = cfg["ffmpeg_path"]
    if not (os.path.isabs(ff) and os.path.exists(ff)) and shutil.which(ff) is None:
        local = _local_ffmpeg()
        if local:
            cfg["ffmpeg_path"] = local
    return cfg


def save(cfg: dict) -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


# ═══════════════════════════ 自测 ═══════════════════════════

if __name__ == "__main__":
    c = load()
    assert "video_port" in c
    c["video_port"] = 31999
    save(c)
    c2 = load()
    assert c2["video_port"] == 31999
    c2["video_port"] = DEFAULTS["video_port"]
    save(c2)
    # 本地 bin/ffmpeg.exe 解析（有则解析为绝对路径）
    local = _local_ffmpeg()
    if local:
        assert os.path.isabs(c2["ffmpeg_path"]) and os.path.exists(c2["ffmpeg_path"])
        print(f"本地 ffmpeg 解析：{c2['ffmpeg_path']}")
    print("warhud.config 自测通过（往返保存/加载）")
