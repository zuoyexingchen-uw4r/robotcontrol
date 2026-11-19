"""spaa.config — 图传上位机运行配置（JSON 持久化到 ~/.spaa/config.json）。

所有可调参数集中在这里，界面里改完就落盘，下次启动沿用。
相机内参是「估值 + 现场标定」：没有标定文件时先用默认值，
实测偏差就在配置里改 fx/fy/cx/cy 与安装高度，测距环会跟着对齐。
"""
from __future__ import annotations

import json
import os
import shutil

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".spaa")
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 配置结构版本：2 = 非图传流（47000/48000/49000）。旧配置读进来会强制迁移端口。
CONFIG_VERSION = 2

DEFAULTS: dict = {
    "config_version": CONFIG_VERSION,
    # ── 图传（手机 CameraStreamer → 本机）──
    "video_port": 47000,          # UDP 视频（与图传控制同号，UDP/TCP 不冲突）
    "ctrl_port": 47000,           # TCP 关键帧请求（本机监听）
    "codec": "h264",              # h264 | hevc
    "decode_w": 1280,             # 解码输出尺寸（与手机推流一致最省）
    "decode_h": 720,
    "hwaccel": "auto",            # auto | d3d11va | none
    "ffmpeg_path": "ffmpeg",      # 可用 ~/.spaa/config.json 覆盖
    "ffprobe_path": "",           # 空 = 用 ffmpeg 同目录的 ffprobe（探测码流参数）

    # ── 非图传流（雷达 / 主控 / 视觉 / 设置 / 保活）──
    "net_enable": True,
    "net_udp": 48000,             # 手机 → 本机
    "net_tcp": 49000,             # TCP 备份（常连，差网切换）
    # 赛事终端地址模式：RR1/RR2/BR1/BR2/DHCP，见 netstack/ipcfg.py
    "ip_mode": "RR1",
    "adapter": "",                # 网卡名，留空 = 不自动切
    "slot": "R1",                 # R1/R2/B1/B2，扫码时告诉手机自己是哪个槽位

    # ── 相机标定（测距环用）──
    "camera": {
        "fx": 1000.0,             # 像素焦距（1280x720 下约 900~1100）
        "fy": 1000.0,
        "cx": 640.0,
        "cy": 360.0,
        "mount_h_mm": 300.0,      # 镜头离地高度
        "mount_pitch_deg": 10.0,  # 光轴相对水平的下倾角（低头为正）
        "ring_step_mm": 1000,     # 等距环间距（标定参考用）
        "ring_max_mm": 8000,      # 最远一圈
    },

    # ── 雷达显示 ──
    "radar": {
        "range_mm": 8000,         # PPI 半径对应距离（最远 8m）
        "min_mm": 120,            # 近处盲区（不画）
        "sweep_deg": 120,         # 扫描扇面角度，90~360；360 = 整圆
        "rings_mm": [2000, 4000, 6000, 8000],
        "zoom": 1.0,              # 显示缩放（点云不重采样，只换半径映射）
    },

    # ── 告警阈值（数值超限 → 黄色/红色）──
    "warn": {
        "temp_warn": 60.0,        # ℃
        "temp_crit": 75.0,
        "volt_warn": 22.0,        # V（低于）
        "volt_crit": 21.0,
        "curr_warn": 8.0,         # A（高于）
        "curr_crit": 12.0,
    },

    # ── 界面 ──
    "ui": {
        "font": "Consolas",
        "font_cjk": "Microsoft YaHei UI",
        "grid": "#00E5C0",        # 主刻度/准星色
        "text": "#E8F0F4",
        "warn": "#FFC53D",        # 黄色警告
        "crit": "#FF4D4F",        # 红色危险
        "dim": "#7A8B94",
        "panel_alpha": 0.55,
        "topbar_alpha": 96,       # 顶栏不透明度 0~255（画面很亮时调高）
        "show_rings": True,
        "show_radar": True,
        "show_attitude": True,
        # 只画这几道距离线：密网格在画面上会糊成一片，操作手只需要参考距离
        "ring_show_mm": [2000, 4000, 6000, 8000],
        "ring_segments": 24,      # 低多边形环的段数（24 → 每 15° 一段）
    },

    # ── 键位（从 3 开始，按战争雷霆武器栏习惯）──
    "keys": {
        "aim_manual": 3,          # 手动瞄准
        "auto_high": 4,           # 自动高地靶
        "auto_base": 5,           # 自动基地靶
        "auto_move": 6,           # 自动移动靶
        "reload": 7,              # 装填弹药
        "gear": 8,                # 起落架开关
        "restart": 9,             # 重启电控（长按）
    },

    # ── 长按动作（毫秒）。断电走 J，不写进武器栏，但要显示进度 ──
    "hold": {
        "restart_ms": 3000,
        "power_off_ms": 3000,
    },

    # ── 比赛规则（弹药计数）──
    "match": {
        "ammo_start": 8,
        "ammo_refill": 8,
        "refill_max": 3,
    },
}


def _local_ffmpeg() -> str:
    """优先用工作区里现成的 ffmpeg 二进制。"""
    for p in (os.path.join(ROOT, "..", "..", "ffmpeg"),
              os.path.join(ROOT, "bin", "ffmpeg.exe")):
        p = os.path.abspath(p)
        if os.path.isfile(p):
            return p
    return ""


def load() -> dict:
    cfg = json.loads(json.dumps(DEFAULTS))       # 深拷贝
    user: dict = {}
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            user = json.load(f)
        for k, v in user.items():
            if isinstance(v, dict) and isinstance(cfg.get(k), dict):
                cfg[k].update(v)
            else:
                cfg[k] = v
    except (OSError, json.JSONDecodeError):
        pass

    # 旧配置迁移：v1 的 46000/8768/31001 那套端口已经不存在了。
    # 只覆盖端口，别动用户的分辨率、标定、键位。
    if int(user.get("config_version", 1) or 1) < CONFIG_VERSION:
        for k in ("video_port", "ctrl_port", "net_udp", "net_tcp"):
            cfg[k] = DEFAULTS[k]
    cfg["config_version"] = CONFIG_VERSION

    ff = cfg.get("ffmpeg_path", "ffmpeg")
    if not (os.path.isabs(ff) and os.path.exists(ff)) and shutil.which(ff) is None:
        local = _local_ffmpeg()
        if local:
            cfg["ffmpeg_path"] = local
    return cfg


def save(cfg: dict) -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
