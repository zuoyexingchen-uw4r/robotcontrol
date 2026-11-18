"""spaa.rx_aux — 雷达点云、主控遥测、手机视觉结果。

三条数据都只读不写，统一由 AuxLink 线程收，界面按快照取：
- 激光/主控：UDP 8768，`[block_id][shard_idx][shard 64B]` + RS-FEC
  shard = `[type][frame][pad]`，0x01 主控上行 / 0x03 雷达
- 主控帧：`AA 55 | type | seq(2 LE) | 16×int16 LE | crc16(2 LE)` = 39B
- 雷达帧：`55 AA` + 60B，16 点 × (距离 u16 mm + 强度 u8)
- 视觉结果：UDP 31001 JSON（`{t:"vision", targets:[], track:{}, ...}`）

下行（TCP 8767）先留接口：主控字段语义还没和电控定稿，发之前不要瞎填。
"""
from __future__ import annotations

import json
import socket
import struct
import threading
import time

from .fec import rs_erasures_decode

T_MASTER_UP = 0x01
T_MASTER_DOWN = 0x02
T_LIDAR = 0x03

LIDAR_FRAME_LEN = 60
MASTER_FRAME_LEN = 39
SHARD_SIZE = 64
FEC_K = 8
FEC_M = 4

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


class _FecBlocks:
    """按 block_id 攒分片，够 k 个就擦除解码。"""

    def __init__(self, k: int = FEC_K):
        self.k = k
        self.blocks: dict[int, dict[int, bytes]] = {}
        self.unrecoverable = 0

    def feed(self, block_id: int, idx: int, shard: bytes):
        b = self.blocks.setdefault(block_id, {})
        b.setdefault(idx, shard)
        if len(b) < self.k:
            return None
        rec = rs_erasures_decode(b, self.k)
        b.update(rec)
        frames = []
        for i in range(self.k):
            sh = b.get(i)
            if sh is None:
                continue
            t = sh[0]
            if t == T_LIDAR:
                frames.append((t, sh[1:1 + LIDAR_FRAME_LEN]))
            elif t == T_MASTER_UP:
                frames.append((t, sh[1:1 + MASTER_FRAME_LEN]))
        self.blocks.pop(block_id, None)
        if len(frames) < self.k:
            self.unrecoverable += 1
        return frames


class AuxLink:

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.running = False
        self._socks: list[socket.socket] = []
        self._threads: list[threading.Thread] = []
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
        self.fec_recovered = 0
        self.fec_failed = 0
        self.err = ""

    # ─────────── 启停 ───────────

    def start(self) -> None:
        if self.running:
            return
        self.running = True
        if self.cfg.get("laser_enable", True):
            self._spawn(self._laser_loop, "aux-laser")
        if self.cfg.get("vision_enable", True):
            self._spawn(self._vision_loop, "aux-vision")

    def _spawn(self, fn, name: str) -> None:
        t = threading.Thread(target=fn, name=name, daemon=True)
        t.start()
        self._threads.append(t)

    def stop(self) -> None:
        self.running = False
        for s in self._socks:
            try:
                s.close()
            except OSError:
                pass

    # ─────────── 激光 + 主控 ───────────

    def _laser_loop(self) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        except OSError:
            pass
        s.settimeout(0.5)
        try:
            s.bind(("0.0.0.0", int(self.cfg["laser_udp"])))
        except OSError as e:
            self.err = f"激光端口 {self.cfg['laser_udp']} 绑定失败：{e}"
            return
        self._socks.append(s)
        fec = _FecBlocks()

        while self.running:
            try:
                data, _ = s.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(data) != SHARD_SIZE + 2:
                continue
            frames = fec.feed(data[0], data[1], data[2:])
            if not frames:
                continue
            for t, frame in frames:
                if t == T_LIDAR:
                    pts = parse_lidar(frame)
                    if pts:
                        with self._lock:
                            self.points = pts
                            self.points_t = time.time()
                            self.speed_rpm = struct.unpack_from("<H", frame, 4)[0]
                            self.lidar_frames += 1
                elif t == T_MASTER_UP:
                    r = parse_master(frame)
                    if r is None:
                        continue
                    _mtype, seq, fields = r
                    with self._lock:
                        if self.master_seq >= 0:
                            d = (seq - self.master_seq) & 0xFFFF
                            if d > 1:
                                self.master_lost += d - 1
                        self.master_seq = seq
                        self.master = dict(zip(UP_FIELD_NAMES, fields))
                        self.master_t = time.time()
        try:
            s.close()
        except OSError:
            pass

    # ─────────── 视觉结果 ───────────

    def _vision_loop(self) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        except OSError:
            pass
        s.settimeout(0.5)
        try:
            s.bind(("0.0.0.0", int(self.cfg["vision_port"])))
        except OSError as e:
            self.err = f"视觉端口 {self.cfg['vision_port']} 绑定失败：{e}"
            return
        self._socks.append(s)

        while self.running:
            try:
                data, _ = s.recvfrom(16384)
            except socket.timeout:
                continue
            except OSError:
                break
            try:
                msg = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if not isinstance(msg, dict) or msg.get("t") != "vision":
                continue
            with self._lock:
                self.vision = msg
                self.vision_t = time.time()
                self.vision_count += 1
        try:
            s.close()
        except OSError:
            pass

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
                "err": self.err,
            }
