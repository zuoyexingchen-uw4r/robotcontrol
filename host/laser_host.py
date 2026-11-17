#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
激光雷达 + 主控板混合流上位机（电脑端，与手机 LaserBridge 对接）。

链路：ESP32 --UART-- CH343 --USB-- 手机(适度解析) --UDP+FEC/TCP-- 本上位机

协议（LASER_PROTOCOL.md v3）：
  上行（雷达 + 主控遥测）走 UDP + Reed-Solomon 前向纠错：
    UDP 报文 = [block_id u8][shard_idx u8][shard 64B]
    shard = [type u8][frame(≤60B)][zero pad]
      0..k-1 为数据分片，k..k+m-1 为校验分片；丢 ≤m 个分片可擦除解码恢复。
  下行（主控指令）走 TCP：[type=0x02][主控帧 39B]，透传下 UART。

  雷达帧：55 AA + 固定 60 字节，小端。
  主控帧：AA 55 | type(1) | seq(2 LE) | payload(32 = 16×int16 LE) | crc16(2 LE)，共 39 字节。

不重传：坏帧靠 CRC/seq 丢弃感知，丢包靠 FEC 恢复。

用法：
  python laser_host.py --selftest
  python laser_host.py --bind 0.0.0.0 --tcp 8767 --udp 8768
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys
import threading

# ── 常量 ──
T_MASTER_UP = 0x01
T_MASTER_DOWN = 0x02
T_LIDAR = 0x03

LIDAR_FRAME_LEN = 60
MASTER_FRAME_LEN = 39
SHARD_SIZE = 64
FEC_K = 8
FEC_M = 4
PAYLOAD_LEN = 32
FIELD_COUNT = 16

LIDAR_HDR = b"\x55\xaa"
MASTER_HDR = b"\xaa\x55"

UP_FIELD_NAMES = [
    "seq", "status", "voltage_mv", "current_ma", "temp_0_1c",
    "rpm_l", "rpm_r", "wheel_l_mm", "wheel_r_mm", "imu_yaw_0_1",
    "imu_pitch_0_1", "imu_roll_0_1", "tof_mm", "lidar_health",
    "res14", "res15",
]
DOWN_FIELD_NAMES = [
    "seq", "cmd", "vx_mmps", "wz_0_01", "yaw_0_01",
    "pitch_0_01", "fire_rpm", "res7", "res8", "res9",
    "res10", "res11", "res12", "res13", "res14", "res15",
]


# ═══════════════ GF(256) + Reed-Solomon（与 Android ReedSolomon.kt 一致） ═══════════════

PRIM = 0x11D
GF_EXP = [0] * 512
GF_LOG = [0] * 256
_x = 1
for _i in range(255):
    GF_EXP[_i] = _x
    GF_LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= PRIM
for _i in range(255, 512):
    GF_EXP[_i] = GF_EXP[_i - 255]


def gf_mul(a, b):
    if a == 0 or b == 0:
        return 0
    return GF_EXP[GF_LOG[a] + GF_LOG[b]]


def gf_inv(a):
    return GF_EXP[255 - GF_LOG[a]]


_GF_TAB = [bytes(gf_mul(c, v) for v in range(256)) for c in range(256)]


def gf_mul_bytes(c, data):
    return data.translate(_GF_TAB[c])


def cauchy_row(k, t):
    """Android ReedSolomon.parityMatrix 的第 t 行：inv((k+1+t) ^ (j+1))"""
    x = k + 1 + t
    return [gf_inv(x ^ (j + 1)) for j in range(k)]


def rs_encode(data_shards, parity):
    """镜像 Android ReedSolomon.encode：返回 parity 个等长校验分片。"""
    k = len(data_shards)
    size = len(data_shards[0])
    out = []
    for t in range(parity):
        row = cauchy_row(k, t)
        p = bytearray(size)
        for d in range(k):
            c = row[d]
            if c:
                tab = _GF_TAB[c]
                src = data_shards[d]
                for i in range(size):
                    p[i] ^= tab[src[i]]
        out.append(bytes(p))
    return out


def rs_erasures_decode(shards, k):
    """shards: {块内分片号: bytes}；0..k-1 数据、≥k 校验。返回补全的数据分片 dict。"""
    missing = [i for i in range(k) if i not in shards]
    n = len(missing)
    if n == 0:
        return {}
    avail = [i - k for i in sorted(shards) if i >= k]
    if len(avail) < n:
        return {}
    rows = avail[:n]
    size = len(next(iter(shards.values())))

    b = []
    for t in rows:
        crow = cauchy_row(k, t)
        acc = int.from_bytes(shards[k + t], "big")
        for d in range(k):
            if d in shards:
                c = crow[d]
                if c:
                    acc ^= int.from_bytes(gf_mul_bytes(c, shards[d]), "big")
        b.append(acc)

    A = [[cauchy_row(k, t)[d] for d in missing] for t in rows]

    def mul_big(c, x):
        if c == 0:
            return 0
        if c == 1:
            return x
        return int.from_bytes(x.to_bytes(size, "big").translate(_GF_TAB[c]), "big")

    M = [A[i][:] + [b[i]] for i in range(n)]
    for col in range(n):
        piv = None
        for r in range(col, n):
            if M[r][col] != 0:
                piv = r
                break
        if piv is None:
            return {}
        M[col], M[piv] = M[piv], M[col]
        inv = gf_inv(M[col][col])
        M[col] = [gf_mul(v, inv) for v in M[col][:n]] + [mul_big(inv, M[col][n])]
        for r in range(n):
            if r != col and M[r][col] != 0:
                f = M[r][col]
                M[r] = [a ^ gf_mul(f, bb) for a, bb in zip(M[r][:n], M[col][:n])] \
                       + [M[r][n] ^ mul_big(f, M[col][n])]
    return {missing[i]: M[i][n].to_bytes(size, "big") for i in range(n)}


# ═══════════════ 主控帧编解码 ═══════════════

def crc16(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def build_master(mtype: int, seq: int, fields: list[int]) -> bytes:
    frame = bytearray(MASTER_FRAME_LEN)
    frame[0:2] = MASTER_HDR
    frame[2] = mtype & 0xFF
    struct.pack_into("<H", frame, 3, seq & 0xFFFF)
    for i in range(FIELD_COUNT):
        v = fields[i] if i < len(fields) else 0
        struct.pack_into("<h", frame, 5 + i * 2, v & 0xFFFF)
    c = crc16(bytes(frame[2:37]))
    struct.pack_into("<H", frame, 37, c)
    return bytes(frame)


def parse_master(frame: bytes):
    if len(frame) != MASTER_FRAME_LEN or frame[:2] != MASTER_HDR:
        return None
    c = crc16(frame[2:37])
    if c != struct.unpack_from("<H", frame, 37)[0]:
        return None
    mtype = frame[2]
    seq = struct.unpack_from("<H", frame, 3)[0]
    fields = list(struct.unpack_from("<%dh" % FIELD_COUNT, frame, 5))
    return mtype, seq, fields


def is_lidar_frame(frame: bytes) -> bool:
    if len(frame) != LIDAR_FRAME_LEN or frame[:2] != LIDAR_HDR:
        return False
    start_raw = struct.unpack_from("<H", frame, 6)[0]
    end_raw = struct.unpack_from("<H", frame, 56)[0]
    if start_raw >= 36000 or end_raw >= 36000:
        return False
    span = (end_raw - start_raw) % 36000
    if span == 0 or span > 9000:
        return False
    if start_raw == 0 and not any(frame[8:56]):
        return False
    return True


def parse_shard(shard: bytes):
    """从 64 字节 FEC 分片还原 (type, frame)；空分片返回 None。"""
    if len(shard) != SHARD_SIZE:
        return None
    t = shard[0]
    if t == 0:
        return None
    if t == T_LIDAR:
        return t, shard[1:1 + LIDAR_FRAME_LEN]
    if t in (T_MASTER_UP, T_MASTER_DOWN):
        return t, shard[1:1 + MASTER_FRAME_LEN]
    return None


# ═══════════════ 雷达解析（与测试上位机一致） ═══════════════

class LidarParser:
    def __init__(self):
        self.points: list[tuple[float, float, int]] = []
        self.last_speed = 0

    def feed(self, frame: bytes) -> list[tuple[float, float, int]]:
        if not is_lidar_frame(frame):
            return []
        self.last_speed = struct.unpack_from("<H", frame, 4)[0]
        start = struct.unpack_from("<H", frame, 6)[0] / 100.0
        end = struct.unpack_from("<H", frame, 56)[0] / 100.0
        data = frame[8:56]
        n = len(data) // 3
        diff = end - start
        if diff < 0:
            diff += 360.0
        step = diff / n if n else 0.0
        out = []
        for i in range(n):
            idx = i * 3
            dist = struct.unpack_from("<H", data, idx)[0]
            intensity = data[idx + 2]
            ang = start + i * step
            if ang >= 360:
                ang -= 360
            out.append((ang, float(dist), intensity))
        self.points = out
        return out


# ═══════════════ FEC 块解码器 ═══════════════

class FecBlockDecoder:
    """按 block_id 缓存分片，凑够 k 个即擦除解码还原数据帧。"""

    def __init__(self, k: int = FEC_K, m: int = FEC_M):
        self.k = k
        self.m = m
        self.blocks: dict[int, dict[int, bytes]] = {}
        self.recovered = 0
        self.unrecoverable = 0

    def feed(self, block_id: int, shard_idx: int, shard: bytes):
        """返回 [(type, frame), ...]（该块能解出时）；否则返回 None。"""
        b = self.blocks.setdefault(block_id, {})
        b[shard_idx] = shard
        if len(b) < self.k:
            return None
        rec = rs_erasures_decode(b, self.k)
        b.update(rec)
        if rec:
            self.recovered += len(rec)
        frames = []
        for i in range(self.k):
            sh = b.get(i)
            if sh is None:
                continue
            parsed = parse_shard(sh)
            if parsed:
                frames.append(parsed)
        del self.blocks[block_id]
        if len(frames) < self.k:
            self.unrecoverable += 1
        return frames


# ═══════════════ 上位机主类 ═══════════════

class LaserHost:
    def __init__(self, bind: str, tcp_port: int, udp_port: int,
                 on_lidar=None, on_master=None):
        self.bind = bind
        self.tcp_port = tcp_port
        self.udp_port = udp_port
        self.on_lidar = on_lidar or (lambda frame: None)
        self.on_master = on_master or (lambda mtype, seq, fields: None)
        self.lidar = LidarParser()
        self.fec = FecBlockDecoder()
        self._tcp_clients: list[socket.socket] = []
        self._lock = threading.Lock()
        self.running = False
        self.last_seq = -1
        self.lost_frames = 0

    def send_master_down(self, seq: int, fields: list[int]) -> None:
        frame = build_master(T_MASTER_DOWN, seq, fields)
        pkt = bytes([T_MASTER_DOWN]) + frame
        with self._lock:
            dead = []
            for s in self._tcp_clients:
                try:
                    s.sendall(pkt)
                except OSError:
                    dead.append(s)
            for s in dead:
                self._tcp_clients.remove(s)

    def run(self) -> None:
        self.running = True
        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.bind((self.bind, self.tcp_port))
        tcp.listen(4)
        tcp.settimeout(0.5)
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.bind((self.bind, self.udp_port))
        udp.settimeout(0.5)

        print(f"[laser-host] TCP {self.bind}:{self.tcp_port} + UDP(FEC) {self.bind}:{self.udp_port} 监听中…",
              file=sys.stderr)

        def handle_tcp(conn: socket.socket) -> None:
            with self._lock:
                self._tcp_clients.append(conn)
            try:
                while self.running:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
            except OSError:
                pass
            finally:
                with self._lock:
                    if conn in self._tcp_clients:
                        self._tcp_clients.remove(conn)
                conn.close()

        try:
            while self.running:
                try:
                    conn, addr = tcp.accept()
                    print(f"[laser-host] 手机连接 {addr[0]}:{addr[1]}", file=sys.stderr)
                    threading.Thread(target=handle_tcp, args=(conn,), daemon=True).start()
                except socket.timeout:
                    pass
                try:
                    data, _addr = udp.recvfrom(65535)
                    self._on_udp(data)
                except socket.timeout:
                    pass
        except KeyboardInterrupt:
            pass
        finally:
            self.running = False
            tcp.close()
            udp.close()

    def _on_udp(self, data: bytes) -> None:
        if len(data) != SHARD_SIZE + 2:
            return
        block_id = data[0]
        shard_idx = data[1]
        shard = data[2:]
        frames = self.fec.feed(block_id, shard_idx, shard)
        if not frames:
            return
        for t, frame in frames:
            if t == T_LIDAR:
                if self.lidar.feed(frame):
                    self.on_lidar(frame)
            elif t == T_MASTER_UP:
                r = parse_master(frame)
                if r:
                    mtype, seq, fields = r
                    if self.last_seq >= 0 and seq != (self.last_seq + 1) & 0xFFFF:
                        self.lost_frames += (seq - self.last_seq - 1) & 0xFFFF
                    self.last_seq = seq
                    self.on_master(mtype, seq, fields)


# ═══════════════ 自测 ═══════════════

def selftest() -> int:
    ok = 0
    total = 0

    def check(name, cond, detail=""):
        nonlocal ok, total
        total += 1
        print(("  ✓ " if cond else "  ✗ ") + name + ("" if cond else f" {detail}"))
        if cond:
            ok += 1

    print("laser_host 自测")
    # 1. 主控帧（39B）编解码往返
    fields = list(range(1, 17))
    f = build_master(T_MASTER_UP, 12345, fields)
    check("主控帧长度=39", len(f) == MASTER_FRAME_LEN, str(len(f)))
    check("主控帧头 AA55", f[:2] == MASTER_HDR)
    r = parse_master(f)
    check("主控帧解码", r is not None and r[0] == T_MASTER_UP and r[1] == 12345 and r[2] == fields)

    # 2. 坏 CRC 拒绝
    bad = bytearray(f); bad[10] ^= 0xFF
    check("坏 CRC 拒绝", parse_master(bytes(bad)) is None)

    # 3. 雷达帧校验
    lidar = bytearray(LIDAR_FRAME_LEN)
    lidar[0:2] = LIDAR_HDR
    struct.pack_into("<H", lidar, 4, 300)
    struct.pack_into("<H", lidar, 6, 0)
    struct.pack_into("<H", lidar, 56, 600)
    for i in range(16):
        struct.pack_into("<H", lidar, 8 + i * 3, 1000 + i)
        lidar[10 + i * 3] = 100
    check("雷达帧合法", is_lidar_frame(bytes(lidar)))
    check("启动空帧拒绝", not is_lidar_frame(bytes(LIDAR_FRAME_LEN)))

    # 4. FEC：编码 K 个分片 → 丢 M 个数据分片 → 擦除解码恢复
    shards = []
    for i in range(FEC_K):
        if i % 4 == 3:
            shards.append(bytearray([T_MASTER_UP]) + build_master(T_MASTER_UP, 100 + i, list(range(i, i + 16))))
        else:
            shards.append(bytes([T_LIDAR]) + bytes(lidar))
        shards[-1] = shards[-1].ljust(SHARD_SIZE, b"\x00")
    parity = rs_encode(shards, FEC_M)
    check("FEC 校验分片数=M", len(parity) == FEC_M)

    dec = FecBlockDecoder(FEC_K, FEC_M)
    result = None
    for i in range(4, FEC_K):            # 保留数据分片 4..7
        result = dec.feed(0, i, shards[i])
    for i in range(FEC_M):               # 喂 4 个校验分片，第 4 个触发解码
        result = dec.feed(0, FEC_K + i, parity[i])
    check("FEC 恢复丢 4 个数据分片", result is not None and len(result) == FEC_K,
          f"got {len(result) if result else 0}")
    if result:
        # 数据分片 0 是 master（i=0 时 0%4!=3 → 实际是 lidar；改判第 3 个）
        # 分片 3 为 master（i=3 时 3%4==3）
        t3, f3 = result[3]
        check("恢复 master 帧一致", t3 == T_MASTER_UP
              and f3 == build_master(T_MASTER_UP, 103, list(range(3, 19))))
        t0, f0 = result[0]
        check("恢复 lidar 帧一致", t0 == T_LIDAR and f0 == bytes(lidar))

    print(f"\n{ok}/{total} 通过" + (" ✅" if ok == total else " ❌"))
    return 0 if ok == total else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="激光雷达+主控混合流上位机")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--tcp", type=int, default=8767)
    ap.add_argument("--udp", type=int, default=8768)
    ap.add_argument("--print-lidar", action="store_true")
    ap.add_argument("--print-master", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        sys.exit(selftest())

    def _lidar(frame):
        if args.print_lidar:
            print(f"[lidar] {len(frame)}B 速度={struct.unpack_from('<H', frame, 4)[0]}", flush=True)

    def _master(t, seq, fields):
        if args.print_master:
            kv = dict(zip(UP_FIELD_NAMES, fields))
            print(f"[master] seq={seq} {kv}", flush=True)

    LaserHost(args.bind, args.tcp, args.udp, on_lidar=_lidar, on_master=_master).run()
