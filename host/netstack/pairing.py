"""netstack.pairing — 扫码配对（上位机侧）。

二维码内容与 Android `net/Pairing.kt` 约定一致，只多几个**可忽略字段**：

```json
{"v":1,"host":"operator-pc","ip":"192.168.1.13","port":47000,
 "slot":"R1","team":"red","udp":48000,"tcp":49000}
```

- Android `Pairing.parse()` 只读 `ip` / `port` / `slot`，其余键忽略；
- `slot` 决定队伍与角色（R1/R2 红方、B1/B2 蓝方），`team` 只是给人看的；
- `port` 是视频端口基址，非图传端口按 48000/49000 固定推导，另附 `udp`/`tcp` 便于排查。

本模块不依赖 Qt：生成 PIL 图像，由界面转 QPixmap。
"""
from __future__ import annotations

import json
import socket

from .ipcfg import MODES


def payload(cfg: dict, ip: str, port: int | None = None, slot: str | None = None,
            host: str | None = None) -> dict:
    slot = (slot or cfg.get("slot") or "R1").upper()
    # 允许直接传模式名（BR1/RR1），落到 Android 认识的槽位（B1/R1）
    if slot in MODES and MODES[slot].get("slot"):
        slot = MODES[slot]["slot"]
    team = "red" if slot.startswith("R") else "blue"
    return {
        "v": 1,
        "host": host or socket.gethostname(),
        "ip": ip,
        "port": int(port if port is not None else cfg.get("video_port", 47000)),
        "slot": slot,
        "team": team,
        "udp": int(cfg.get("net_udp", 48000)),
        "tcp": int(cfg.get("net_tcp", 49000)),
    }


def to_json(p: dict) -> str:
    return json.dumps(p, ensure_ascii=False, separators=(",", ":"))


def qr_image(text: str, box_size: int = 6, border: int = 2):
    """PIL 图像；没装 `qrcode` 返回 None（界面显示文字兜底）。"""
    try:
        import qrcode
    except ImportError:
        return None
    qr = qrcode.QRCode(
        version=None,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=box_size,
        border=border,
    )
    qr.add_data(text)
    qr.make(fit=True)
    return qr.make_image(fill_color="black", back_color="white").convert("RGB")


def qr_png(text: str, path: str, box_size: int = 8) -> bool:
    img = qr_image(text, box_size=box_size)
    if img is None:
        return False
    img.save(path)
    return True


def describe(p: dict) -> str:
    """二维码下方那行给人看的摘要。"""
    team = {"red": "红方", "blue": "蓝方"}.get(p.get("team", ""), p.get("team", ""))
    return (f"{p.get('host', '')}  {p.get('ip', '')}:{p.get('port', '')}  "
            f"{team} {p.get('slot', '')}")
