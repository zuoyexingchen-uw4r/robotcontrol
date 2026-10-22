"""warhud.protocol — 电脑 HUD 与手机/ESP32 的编解码（纯函数，无 UI 依赖）。

三条链路（详见仓库 PROTOCOL.md「电脑 HUD 协议」节）：

1. 视觉结果 + 设置同步（UDP）
   - 手机 → 电脑 31001 广播：vision 帧 + settings 快照（500ms 心跳）
   - 电脑 → 手机 31002 单播：set_req 设置变更请求 / hello 上线宣告
2. 操作输入 + 遥测（WLAN TCP，电脑直连 ESP32-S3）
   - 电脑 → ESP32 31010：输入帧 0xFA 0xAE（10Hz，fire 边沿触发）
   - ESP32 → 电脑：遥测帧 0xFA 0xEA（10Hz）
"""

import json
import struct

# ─────────────────────────── UDP 端口约定 ───────────────────────────
UDP_VISION_PORT = 31001      # 手机广播：视觉结果 + 设置快照
UDP_CMD_PORT = 31002         # 电脑单播：设置变更 / 上线宣告

# ─────────────────────────── ESP32 TCP 端口约定 ───────────────────────────
ESP32_INPUT_PORT = 31010     # 电脑 → ESP32 操作输入
ESP32_TELEM_PORT = 31011     # ESP32 → 电脑 遥测（ESP32 主动连或电脑订阅，二选一，见协议文档）

# ─────────────────────────── 视觉开关键集合（与手机 SharedPreferences 一致 ───────────────────────────
# 顺序即 PROTOCOL 文档顺序；新增键必须两边同步加，否则设置同步会丢字段。
SETTING_KEYS = (
    "deblur",        # 去模糊（维纳反卷积）
    "smoothing",     # 图像平滑（双边滤波）
    "deglare",       # 去辉光
    "highland",      # 高地靶检测
    "base",          # 基地靶检测
    "tracking",      # 卡尔曼追踪
    "autofire",      # 自动开火
    "navigation",    # 导航（占位）
)


# ═════════════════════════════ UDP 视觉结果 / 设置同步 ═══════════════════════════

def encode_hello() -> bytes:
    """电脑上线宣告：手机收到后立即广播一次设置快照（不等 500ms 心跳）。"""
    return json.dumps({"t": "hello", "src": "pc_hud"}).encode("utf-8")


def encode_set_req(key: str, value) -> bytes:
    """设置变更请求（电脑 → 手机）。手机写 SharedPreferences 成功后，
    下一次 settings 广播即全量确认，电脑乐观更新 + 快照校验。"""
    return json.dumps({"t": "set_req", "key": key, "value": value}).encode("utf-8")


def decode_udp(data: bytes) -> dict | None:
    """解析手机广播的 UDP 报文。返回 dict（含 't' 字段）或 None（坏包）。"""
    try:
        msg = json.loads(data.decode("utf-8"))
        return msg if isinstance(msg, dict) and "t" in msg else None
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None


# ═══════════════════════════ ESP32 WLAN 输入帧 ═══════════════════════════
# 0xFA 0xAE | yaw i16(0.01°) | pitch i16(0.01°) | fire u8 | mx i16(mm/s) | my i16 | btn u8 | XOR
INPUT_HEADER = b"\xfa\xae"
INPUT_LEN = 13   # 头2 + body10(<hhBhhB) + XOR1


def encode_input(yaw_deg: float, pitch_deg: float, fire: bool,
                 move_x_mmps: int = 0, move_y_mmps: int = 0, buttons: int = 0) -> bytes:
    """打包操作输入帧（电脑 → ESP32-S3）。

    yaw/pitch 单位 0.01°（云台目标角，右/上为正）；fire 边沿触发——
    ESP32 端做「上升沿开一发」逻辑，电脑长按也只发一帧 True。
    """
    yaw = int(round(max(-180.0, min(180.0, yaw_deg)) * 100))
    pitch = int(round(max(-90.0, min(90.0, pitch_deg)) * 100))
    # 字段序：yaw pitch fire mx my btn（struct <hhBhhB）
    body = struct.pack("<hhBhhB", yaw, pitch, 1 if fire else 0,
                       max(-32768, min(32767, move_x_mmps)),
                       max(-32768, min(32767, move_y_mmps)), buttons & 0xFF)
    return INPUT_HEADER + body + bytes([_xor(body)])


def _xor(bs: bytes) -> int:
    x = 0
    for b in bs:
        x ^= b
    return x


# ═══════════════════════════ ESP32 遥测帧 ═══════════════════════════
# 0xFA 0xEA | voltage u16(mV) | current u16(mA) | temp i16(0.1°C) |
#             rpm_l u16 | rpm_r u16 | status u8 | XOR
TELEM_HEADER = b"\xfa\xea"
TELEM_LEN = 14


def decode_telem(data: bytes) -> dict | None:
    """解析 ESP32 遥测帧。返回 None（长度不足/头错/校验失败）。"""
    if len(data) < TELEM_LEN or data[:2] != TELEM_HEADER:
        return None
    body = data[2:TELEM_LEN - 1]
    if _xor(body) != data[TELEM_LEN - 1]:
        return None
    mv, ma, temp, rl, rr, status = struct.unpack("<HHhHHB", body)
    return {
        "voltage_v": mv / 1000.0,
        "current_a": ma / 1000.0,
        "temp_c": temp / 10.0,
        "rpm_left": rl,
        "rpm_right": rr,
        "status": status,
    }


# ═══════════════════════════ 自测（python protocol.py 直接跑） ═══════════════════════════

if __name__ == "__main__":
    ok = 0
    total = 0

    def check(name, cond, detail=""):
        global ok, total
        total += 1
        if cond:
            ok += 1
            print(f"  ✓ {name}")
        else:
            print(f"  ✗ {name} {detail}")

    print("warhud.protocol 自测")

    # 设置编解码往返
    req = decode_udp(encode_set_req("deglare", False))
    check("set_req 编解码", req is not None and req["t"] == "set_req"
          and req["key"] == "deglare" and req["value"] is False)
    hello = decode_udp(encode_hello())
    check("hello 编解码", hello is not None and hello["src"] == "pc_hud")

    # 坏包
    check("坏 JSON 返回 None", decode_udp(b"\xff\xfe not json") is None)
    check("缺 t 字段返回 None", decode_udp(b'{"foo":1}') is None)

    # 输入帧往返
    pkt = encode_input(1.23, -0.45, True, 100, -50, 0x01)
    check("输入帧长度", len(pkt) == INPUT_LEN, f"got {len(pkt)}")
    check("输入帧头", pkt[:2] == INPUT_HEADER)
    yaw, pitch, fire, mx, my, btn = struct.unpack("<hhBhhB", pkt[2:12])
    check("输入帧字段往返",
          abs(yaw - 123) <= 1 and abs(pitch + 45) <= 1 and fire == 1
          and mx == 100 and my == -50 and btn == 1,
          f"yaw={yaw} pitch={pitch} fire={fire} mx={mx} my={my}")
    check("输入帧 XOR 校验", _xor(pkt[2:12]) == pkt[12])

    # 输入帧限幅
    pkt2 = encode_input(999.0, -999.0, False)
    yaw2, pitch2 = struct.unpack("<hh", pkt2[2:6])
    check("输入帧限幅 ±180°/±90°", yaw2 == 18000 and pitch2 == -9000,
          f"yaw={yaw2} pitch={pitch2}")

    # 遥测帧往返
    telem_body = struct.pack("<HHhHHB", 12340, 5670, 452, 12000, 11800, 0x03)
    telem = TELEM_HEADER + telem_body + bytes([_xor(telem_body)])
    d = decode_telem(telem)
    check("遥测帧解码", d is not None and abs(d["voltage_v"] - 12.34) < 1e-6
          and d["rpm_left"] == 12000 and d["status"] == 3, str(d))
    check("遥测帧坏头拒绝", decode_telem(b"\x00" + telem[1:]) is None)
    check("遥测帧坏校验拒绝", decode_telem(telem[:-1] + b"\x00") is None)
    check("遥测帧短包拒绝", decode_telem(telem[:8]) is None)

    print(f"\n{ok}/{total} 通过" + (" ✅" if ok == total else " ❌"))
    raise SystemExit(0 if ok == total else 1)
