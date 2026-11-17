#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
激光雷达 + 主控板混合流上位机（电脑端，与手机 LaserBridge 对接）。

链路：ESP32 --UART-- CH343 --USB-- 手机(适度解析) --TCP/UDP-- 本上位机

协议（LASER_PROTOCOL.md v2，两类帧均定长 60 字节）：
  网络管道 = [1 字节 type][60 字节帧]
    type 0x01 = 主控上行帧（AA 55 ...）
    type 0x02 = 主控下行帧（上位机→手机，透传下 UART）
    type 0x03 = 雷达点云帧（55 AA ...，原样）

雷达帧：55 AA + 固定 60 字节，小端（与测试上位机_优化版.py 完全一致）。
主控帧：AA 55 | type(1) | seq(2 LE) | payload(52 = 26×int16 LE) | crc16(2 LE)。

不重传：坏帧靠 CRC/seq 丢弃感知。

用法：
  # 自测（不联网）
  python laser_host.py --selftest
  # 监听 TCP 8767 + UDP 8768
  python laser_host.py --bind 0.0.0.0 --tcp 8767 --udp 8768
"""
from __future__ import annotations

import argparse
import socket
import struct
import sys
import threading
import time

# ── 常量 ──
FRAME_LEN = 60
T_MASTER_UP = 0x01
T_MASTER_DOWN = 0x02
T_LIDAR = 0x03

LIDAR_HDR = b"\x55\xaa"
MASTER_HDR = b"\xaa\x55"
PAYLOAD_LEN = 52
FIELD_COUNT = 26

# 主控字段名（占位/建议，与 APP 端 LaserProtocol 对齐）
UP_FIELD_NAMES = [
    "seq", "status", "voltage_mv", "current_ma", "temp_0_1c",
    "rpm_l", "rpm_r", "wheel_l_mm", "wheel_r_mm", "imu_yaw_0_1",
    "imu_pitch_0_1", "imu_roll_0_1", "tof_mm", "lidar_health",
    "res14", "res15", "res16", "res17", "res18", "res19",
    "res20", "res21", "res22", "res23", "res24", "res25",
]
DOWN_FIELD_NAMES = [
    "seq", "cmd", "vx_mmps", "wz_0_01", "yaw_0_01",
    "pitch_0_01", "fire_rpm", "res7", "res8", "res9",
    "res10", "res11", "res12", "res13", "res14", "res15",
    "res16", "res17", "res18", "res19", "res20", "res21",
    "res22", "res23", "res24", "res25",
]


def crc16(data: bytes) -> int:
    """CRC16-MODBUS（poly 0xA001，init 0xFFFF）"""
    crc = 0xFFFF
    for b in data:
        crc ^= b
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return crc & 0xFFFF


def build_master(mtype: int, seq: int, fields: list[int]) -> bytes:
    """组一帧主控帧（60 字节），与 APP 端 LaserProtocol.buildMaster 一致。"""
    frame = bytearray(FRAME_LEN)
    frame[0:2] = MASTER_HDR
    frame[2] = mtype & 0xFF
    struct.pack_into("<H", frame, 3, seq & 0xFFFF)
    for i in range(FIELD_COUNT):
        v = fields[i] if i < len(fields) else 0
        struct.pack_into("<h", frame, 5 + i * 2, v & 0xFFFF)
    c = crc16(bytes(frame[2:58]))
    struct.pack_into("<H", frame, 58, c)
    return bytes(frame)


def parse_master(frame: bytes):
    """解析主控帧，返回 (type, seq, [26×int16])；CRC 错返回 None。"""
    if len(frame) != FRAME_LEN or frame[:2] != MASTER_HDR:
        return None
    c = crc16(frame[2:58])
    if c != struct.unpack_from("<H", frame, 58)[0]:
        return None
    mtype = frame[2]
    seq = struct.unpack_from("<H", frame, 3)[0]
    fields = list(struct.unpack_from("<%dh" % FIELD_COUNT, frame, 5))
    return mtype, seq, fields


def is_lidar_frame(frame: bytes) -> bool:
    """雷达帧有效性校验（与测试上位机 _is_valid_data_frame 一致）。"""
    if len(frame) != FRAME_LEN or frame[:2] != LIDAR_HDR:
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


class LidarParser:
    """雷达帧解析（55AA + 60B，产出 (angle,distance,intensity) 点列表）。"""

    def __init__(self):
        self.points: list[tuple[float, float, int]] = []
        self.frame_buffer = bytearray()
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


class LaserHost:
    """TCP + UDP 双监听，按 type 分流；下行主控帧走 TCP 下发。"""

    def __init__(self, bind: str, tcp_port: int, udp_port: int, on_lidar=None, on_master=None):
        self.bind = bind
        self.tcp_port = tcp_port
        self.udp_port = udp_port
        self.on_lidar = on_lidar or (lambda frame: None)
        self.on_master = on_master or (lambda mtype, seq, fields: None)
        self.lidar = LidarParser()
        self._tcp_clients: list[socket.socket] = []
        self._lock = threading.Lock()
        self.running = False
        self.last_seq = -1
        self.lost_frames = 0

    # ── 发送下行主控帧到手机（TCP 广播到所有已连客户端） ──
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

    # ── 主循环 ──
    def run(self) -> None:
        self.running = True
        # TCP
        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.bind((self.bind, self.tcp_port))
        tcp.listen(4)
        tcp.settimeout(0.5)
        # UDP
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        udp.bind((self.bind, self.udp_port))
        udp.settimeout(0.5)

        print(f"[laser-host] TCP {self.bind}:{self.tcp_port} + UDP {self.bind}:{self.udp_port} 监听中…",
              file=sys.stderr)

        def handle_tcp(conn: socket.socket) -> None:
            with self._lock:
                self._tcp_clients.append(conn)
            buf = bytearray()
            try:
                while self.running:
                    chunk = conn.recv(4096)
                    if not chunk:
                        break
                    buf.extend(chunk)
                    buf = self._demux(buf)
            except OSError:
                pass
            finally:
                with self._lock:
                    if conn in self._tcp_clients:
                        self._tcp_clients.remove(conn)
                conn.close()

        try:
            while self.running:
                # accept TCP
                try:
                    conn, addr = tcp.accept()
                    print(f"[laser-host] 手机连接 {addr[0]}:{addr[1]}", file=sys.stderr)
                    threading.Thread(target=handle_tcp, args=(conn,), daemon=True).start()
                except socket.timeout:
                    pass
                # recv UDP
                try:
                    data, addr = udp.recvfrom(65535)
                    self._demux(bytearray(data))
                except socket.timeout:
                    pass
        except KeyboardInterrupt:
            pass
        finally:
            self.running = False
            tcp.close()
            udp.close()

    def _demux(self, buf: bytearray) -> bytearray:
        """按 [type][60B] 拆包分流，返回未消费的剩余字节。"""
        i = 0
        while i + 1 + FRAME_LEN <= len(buf):
            t = buf[i]
            frame = bytes(buf[i + 1:i + 1 + FRAME_LEN])
            if t == T_LIDAR:
                pts = self.lidar.feed(frame)
                if pts:
                    self.on_lidar(frame)
            elif t == T_MASTER_UP:
                r = parse_master(frame)
                if r:
                    mtype, seq, fields = r
                    # seq 丢帧感知（不重传）
                    if self.last_seq >= 0 and seq != (self.last_seq + 1) & 0xFFFF:
                        self.lost_frames += (seq - self.last_seq - 1) & 0xFFFF
                    self.last_seq = seq
                    self.on_master(mtype, seq, fields)
            else:
                i += 1  # 未知类型，滑 1 字节
                continue
            i += 1 + FRAME_LEN
        return buf[i:]


# ─────────── 自测 ───────────
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
    # 1. 主控帧编解码往返
    fields = list(range(1, 27))
    f = build_master(T_MASTER_UP, 12345, fields)
    check("主控帧长度=60", len(f) == FRAME_LEN, str(len(f)))
    check("主控帧头 AA55", f[:2] == MASTER_HDR)
    r = parse_master(f)
    check("主控帧解码", r is not None and r[0] == T_MASTER_UP and r[1] == 12345 and r[2] == fields)

    # 2. CRC 校验：改 1 字节应失败
    bad = bytearray(f); bad[10] ^= 0xFF
    check("坏 CRC 拒绝", parse_master(bytes(bad)) is None)

    # 3. 雷达帧校验
    lidar = bytearray(FRAME_LEN)
    lidar[0:2] = LIDAR_HDR
    struct.pack_into("<H", lidar, 4, 300)      # 转速
    struct.pack_into("<H", lidar, 6, 0)        # 起始角 0°
    struct.pack_into("<H", lidar, 56, 600)     # 终止角 6°
    for i in range(16):
        struct.pack_into("<H", lidar, 8 + i * 3, 1000 + i)  # 距离
        lidar[10 + i * 3] = 100                          # 强度
    check("雷达帧合法", is_lidar_frame(bytes(lidar)))
    # 全零空帧应拒绝
    check("启动空帧拒绝", not is_lidar_frame(bytes(FRAME_LEN)))

    # 4. 混合流 demux：雷达 + 主控 + 未知字节
    host = LaserHost("127.0.0.1", 1, 2)
    got_lidar = []
    got_master = []
    host.on_lidar = got_lidar.append
    host.on_master = lambda t, s, fl: got_master.append((t, s))
    stream = bytearray()
    stream += bytes([T_LIDAR]) + bytes(lidar)
    stream += bytes([T_MASTER_UP]) + f
    stream += b"\x00"  # 未知类型，应滑过
    stream += bytes([T_LIDAR]) + bytes(lidar)
    rest = host._demux(stream)
    check("雷达分流 2 帧", len(got_lidar) == 2, str(len(got_lidar)))
    check("主控分流 1 帧", len(got_master) == 1 and got_master[0] == (T_MASTER_UP, 12345))
    check("未知字节滑过", len(rest) == 0, str(len(rest)))

    print(f"\n{ok}/{total} 通过" + (" ✅" if ok == total else " ❌"))
    return 0 if ok == total else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="激光雷达+主控混合流上位机")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--tcp", type=int, default=8767)
    ap.add_argument("--udp", type=int, default=8768)
    ap.add_argument("--print-lidar", action="store_true", help="打印雷达点云摘要")
    ap.add_argument("--print-master", action="store_true", help="打印主控帧字段")
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
