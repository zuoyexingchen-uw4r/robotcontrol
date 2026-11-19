"""netstack.proto — 非图传流 v1 的字节协议（与 Android `com.robomaster.control.net` 对拍）。

两条信号：
- 图传：视频 UDP 47000 + 控制 TCP 47000（本模块不管）
- 非图传：UDP 48000 / TCP 49000，本模块负责

最小单位是 60B 定长槽，槽就是 UART 帧原样，前两字节是路由键。
网络包 = 16B 包头 + n 个槽（n=1/10/20），校验包单独成包，JSON 走长帧。
"""
from __future__ import annotations

import struct

SLOT = 60
PKT_HDR = 16
FEC_K = 5
FEC_M = 1
MAX_BATCH = 20
MAX_PAYLOAD = 1200

VIDEO_PORT = 47000
UDP_PORT = 48000
TCP_PORT = 49000

MAGIC = b"LV"
VERSION = 1

F_PARITY = 1 << 0
F_LONG = 1 << 1
F_URGENT = 1 << 2

H_LIDAR = 0x55AA      # 雷达点云 → 上位机
H_MCU_UP = 0xAA55     # 主控遥测 → 上位机
H_KA = 0x4B41         # 'K','A' 保活 / 延迟探测
H_FIRE = 0xFAAF       # 开火 → UART
H_NAV = 0xFABF        # 导航 → UART
H_LOCAL = 0xFA5A      # 本机操作
H_UART_DOWN = 0xFA5B  # 原始字节 → UART

LEN_LIDAR = 60
LEN_MCU = 39
LEN_FIRE = 10
LEN_NAV = 7

OP_SWITCH_IP = 0x01
OP_RECONNECT = 0x02
OP_RESET_STATS = 0x03

JSON_VISION = "vision"
JSON_SETTINGS = "settings"
JSON_SET_REQ = "set_req"
JSON_HELLO = "hello"


def crc16(data: bytes, start: int = 0, end: int | None = None) -> int:
    """CRC16-MODBUS（poly 0xA001, init 0xFFFF），与 UART / Kotlin 端一致。"""
    if end is None:
        end = len(data)
    crc = 0xFFFF
    for b in data[start:end]:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


class Packet:
    """16B 包头 + 负载。"""

    __slots__ = ("flags", "seq", "block_id", "shard_start", "count", "ts_ms", "payload")

    def __init__(self, flags: int, seq: int, block_id: int, shard_start: int,
                 count: int, ts_ms: int, payload: bytes):
        self.flags = flags
        self.seq = seq
        self.block_id = block_id
        self.shard_start = shard_start
        self.count = count
        self.ts_ms = ts_ms
        self.payload = payload

    @property
    def is_parity(self) -> bool:
        return bool(self.flags & F_PARITY)

    @property
    def is_long(self) -> bool:
        return bool(self.flags & F_LONG)

    @property
    def is_urgent(self) -> bool:
        return bool(self.flags & F_URGENT)

    def dedupe_key(self) -> int:
        return ((self.seq & 0xFFFF) << 24) | ((self.block_id & 0xFFFF) << 8) | (self.shard_start & 0xFF)

    def encode(self) -> bytes:
        hdr = struct.pack(
            "<2sBBHHBBHHH",
            MAGIC,
            VERSION,
            self.flags & 0xFF,
            self.seq & 0xFFFF,
            self.block_id & 0xFFFF,
            self.shard_start & 0xFF,
            self.count & 0xFF,
            self.ts_ms & 0xFFFF,
            len(self.payload) if self.is_long else 0,
            0,  # crc 占位
        )
        crc = crc16(hdr, 0, 14)
        hdr = hdr[:14] + struct.pack("<H", crc)
        return hdr + self.payload

    @staticmethod
    def decode(buf: bytes) -> "Packet | None":
        if len(buf) < PKT_HDR:
            return None
        if buf[:2] != MAGIC or buf[2] != VERSION:
            return None
        if crc16(buf, 0, 14) != struct.unpack_from("<H", buf, 14)[0]:
            return None

        flags, seq, block_id, shard_start, count, ts_ms, declared = struct.unpack_from("<BHHBBHH", buf, 3)
        body = buf[PKT_HDR:]
        long_frame = bool(flags & F_LONG)

        if long_frame:
            if declared > len(body):
                return None
            payload = body[:declared]
        else:
            if not (1 <= count <= MAX_BATCH) or len(body) != count * SLOT:
                return None
            payload = body

        return Packet(flags, seq, block_id, shard_start, count, ts_ms, payload)

    def slots(self) -> list[bytes]:
        return [self.payload[i * SLOT:(i + 1) * SLOT] for i in range(self.count)]


# ───────────────────────── 槽工具 ─────────────────────────

def slot_header(slot: bytes) -> int:
    return (slot[0] << 8) | slot[1]


def is_keepalive(slot: bytes) -> bool:
    return len(slot) >= 2 and slot_header(slot) == H_KA


def ka_seq(slot: bytes) -> int:
    return struct.unpack_from("<H", slot, 2)[0]


def ka_ts(slot: bytes) -> int:
    return struct.unpack_from("<I", slot, 4)[0]


def keep_alive(seq: int, ts_ms: int) -> bytes:
    s = bytearray(SLOT)
    s[0:2] = b"KA"
    struct.pack_into("<H", s, 2, seq & 0xFFFF)
    struct.pack_into("<I", s, 4, ts_ms & 0xFFFFFFFF)
    s[8] = 1
    struct.pack_into("<H", s, 58, crc16(s, 0, 58))
    return bytes(s)


def local_op(op: int, arg: bytes = b"") -> bytes:
    if len(arg) > 54:
        raise ValueError("本机操作参数过长")
    s = bytearray(SLOT)
    s[0:2] = b"\xfa\x5a"
    s[2] = op & 0xFF
    s[3] = len(arg)
    s[4:4 + len(arg)] = arg
    struct.pack_into("<H", s, 58, crc16(s, 0, 58))
    return bytes(s)


def local_op_code(slot: bytes) -> int:
    return slot[2]


def local_op_arg(slot: bytes) -> bytes:
    n = slot[3]
    return slot[4:4 + n]


def uart_down(data: bytes) -> list[bytes]:
    """原始字节 → 若干 `FA 5B` 槽，每槽最多 55B。"""
    out = []
    for off in range(0, len(data), 55):
        chunk = data[off:off + 55]
        s = bytearray(SLOT)
        s[0:2] = b"\xfa\x5b"
        s[2] = len(chunk)
        s[3:3 + len(chunk)] = chunk
        struct.pack_into("<H", s, 58, crc16(s, 0, 58))
        out.append(bytes(s))
    return out


def uart_payload(slot: bytes) -> bytes | None:
    """下行槽 → 应写进 UART 的字节；不是 UART 目标返回 None。"""
    h = slot_header(slot)
    if h == H_FIRE:
        return slot[:LEN_FIRE]
    if h == H_NAV:
        return slot[:LEN_NAV]
    if h == H_UART_DOWN:
        n = min(slot[2], 55)
        return slot[3:3 + n]
    return None
