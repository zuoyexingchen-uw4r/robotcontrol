"""spaa.rx_aux — 雷达点云 / 主控遥测 / 视觉结果 / 设置快照（非图传流）。

v0.7 起不再自己开 8768 / 31001 两个 socket，统一走 `netstack.NetLink`：
本机监听 UDP 48000 + TCP 49000，手机主动连；一条流里按 60B 槽的帧头分流。

- 雷达帧：`55 AA` + 60B，16 点 × (距离 u16 mm + 强度 u8)
- 主控帧：`AA 55 | type | seq(2 LE) | 16×int16 LE | crc16(2 LE)` = 39B（槽里补零到 60B）
- 视觉结果 / 设置快照：长帧 JSON，`{t:"vision"|"settings", ...}`

界面只读 `snapshot()`，所有解析都在收包线程完成。
"""
from __future__ import annotations

import struct
import threading
import time

from netstack.link import NetLink
from netstack.proto import H_LIDAR, H_MCU_UP

MASTER_FRAME_LEN = 39
LIDAR_FRAME_LEN = 60

LIDAR_HDR = b"\x55\xaa"
MASTER_HDR = b"\xaa\x55"

UP_FIELD_NAMES = [
    "seq", "status", "voltage_mv", "current_ma", "temp_0_1c",
    "rpm_l", "rpm_r", "wheel_l_mm", "wheel_r_mm", "imu_yaw_0_1",
    "imu_pitch_0_1", "imu_roll_0_1", "tof_mm", "lidar_health",
    # 14/15 原为 res*，上位机侧启用：档位（PWM 占空比上限）与真实车速
    "gear", "speed_mmps",
]


def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def parse_master(frame: bytes):
    if len(frame) != MASTER_FRAME_LEN or frame[:2] != MASTER_HDR:
        return None
    if crc16(frame[2:37]) != struct.unpack_from("<H", frame, 37)[0]:
        return None
    return (
        frame[2],
        struct.unpack_from("<H", frame, 3)[0],
        list(struct.unpack_from("<16h", frame, 5)),
    )


def is_lidar_frame(frame: bytes) -> bool:
    if len(frame) != LIDAR_FRAME_LEN or frame[:2] != LIDAR_HDR:
        return False
    start = struct.unpack_from("<H", frame, 6)[0]
    end = struct.unpack_from("<H", frame, 56)[0]
    if start >= 36000 or end >= 36000:
        return False
    span = (end - start) % 36000
    if span == 0 or span > 9000:
        return False
    if start == 0 and not any(frame[8:56]):
        return False
    return True


def parse_lidar(frame: bytes) -> list[tuple[float, int, int]]:
    """→ [(角度°, 距离 mm, 强度)]，角度以雷达自身 0° 为基准。"""
    if not is_lidar_frame(frame):
        return []
    start = struct.unpack_from("<H", frame, 6)[0] / 100.0
    end = struct.unpack_from("<H", frame, 56)[0] / 100.0
    diff = end - start
    if diff < 0:
        diff += 360.0
    pts = []
    for i in range(16):
        off = 8 + i * 3
        dist = struct.unpack_from("<H", frame, off)[0]
        inten = frame[off + 2]
        ang = (start + i * (diff / 16.0)) % 360.0
        pts.append((ang, dist, inten))
    return pts


class AuxLink:

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.running = False
        self.net: NetLink | None = None
        self._lock = threading.Lock()

        self.points: list[tuple[float, int, int]] = []
        self.points_t = 0.0
        self.speed_rpm = 0

        self.master: dict | None = None
        self.master_seq = -1
        self.master_t = 0.0
        self.master_lost = 0

        self.vision: dict | None = None
        self.vision_t = 0.0
        self.vision_count = 0

        self.lidar_frames = 0
        self.err = ""
        self.net_stats: dict = {}

    # ─────────── 启停 ───────────

    def start(self) -> None:
        if self.running:
            return
        if not self.cfg.get("net_enable", True):
            return
        self.running = True
        self.net = NetLink(
            self.cfg,
            on_slot=self._on_slot,
            on_json=self._on_json,
            on_stats=self._on_net_stats,
            log=lambda msg: None,
        )
        self.net.start()

    def stop(self) -> None:
        self.running = False
        if self.net:
            self.net.stop()
            self.net = None

    # ─────────── 槽 → 业务 ───────────

    def _on_slot(self, header: int, slot: bytes) -> None:
        if header == H_LIDAR:
            pts = parse_lidar(slot)
            if not pts:
                return
            with self._lock:
                self.points = pts
                self.points_t = time.time()
                self.speed_rpm = struct.unpack_from("<H", slot, 4)[0]
                self.lidar_frames += 1
            return

        if header == H_MCU_UP:
            r = parse_master(slot[:MASTER_FRAME_LEN])
            if r is None:
                return
            _mtype, seq, fields = r
            with self._lock:
                if self.master_seq >= 0:
                    d = (seq - self.master_seq) & 0xFFFF
                    if d > 1:
                        self.master_lost += d - 1
                self.master_seq = seq
                self.master = dict(zip(UP_FIELD_NAMES, fields))
                self.master_t = time.time()

    def _on_json(self, msg: dict) -> None:
        if msg.get("t") == "vision":
            with self._lock:
                self.vision = msg
                self.vision_t = time.time()
                self.vision_count += 1

    def _on_net_stats(self, st: dict) -> None:
        self.net_stats = st
        if st.get("err"):
            self.err = st["err"]

    # ─────────── 下行（留给操控链路） ───────────

    def send_uart(self, data: bytes) -> bool:
        return bool(self.net and self.net.send_uart(data))

    # ─────────── 快照 ───────────

    def snapshot(self) -> dict:
        now = time.time()
        with self._lock:
            return {
                "points": self.points,
                "points_age": now - self.points_t if self.points_t else 1e9,
                "speed_rpm": self.speed_rpm,
                "master": dict(self.master) if self.master else None,
                "master_age": now - self.master_t if self.master_t else 1e9,
                "master_lost": self.master_lost,
                "vision": self.vision,
                "vision_age": now - self.vision_t if self.vision_t else 1e9,
                "vision_count": self.vision_count,
                "lidar_frames": self.lidar_frames,
                "net": dict(self.net_stats),
                "settings": (self.net.settings if self.net else None),
                "err": self.err,
            }
