"""spaa.selftest — 纯逻辑自测（不需要窗口/网络/手机）。

覆盖最容易出错的四处：
1. FEC 擦除解码：丢 4 个数据分片还能原样恢复
2. 主控帧 / 雷达帧：CRC 与几何合法性
3. GameStream 组帧：自己按手机端 wire format 造包，喂给接收器，
   必须还原出**逐字节相同**的 Annex-B 帧（这条守住跨语言一致）
4. 相机投影：光轴落点距离、地平线位置、等距环可见性
"""
from __future__ import annotations

import queue
import struct
import sys

from .config import DEFAULTS
from .fec import rs_encode
from .geometry import CameraModel
from .rx_aux import crc16, is_lidar_frame, parse_lidar, parse_master
from .rx_video import VideoReceiver

PASS = 0
FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  OK  {name}")
    else:
        FAIL += 1
        print(f"  XX  {name}  {detail}")


def _cfg() -> dict:
    import copy
    cfg = copy.deepcopy(DEFAULTS)
    cfg["video_port"] = 46990
    cfg["ctrl_port"] = 47990
    cfg["laser_enable"] = False
    cfg["vision_enable"] = False
    return cfg


# ═════════════════ 1. FEC ═════════════════

def test_fec() -> None:
    print("[1] FEC 擦除解码")
    k, m = 8, 4
    data = [bytes([i]) * 64 for i in range(k)]
    parity = rs_encode(data, m)
    check("校验分片数 = m", len(parity) == m)

    from .fec import rs_erasures_decode
    shards = {i: data[i] for i in range(4, k)}          # 只留后 4 个
    for i in range(m):
        shards[k + i] = parity[i]
    rec = rs_erasures_decode(shards, k)
    check("丢 4 个数据分片可恢复", len(rec) == 4, f"got {len(rec)}")
    for i in range(4):
        check(f"分片 {i} 字节一致", rec.get(i) == data[i])

    # 丢 5 个数据分片（校验只有 4 个）→ 必须失败，而不是给出错数据
    shards2 = {i: data[i] for i in range(5, k)}
    for i in range(m):
        shards2[k + i] = parity[i]
    check("丢 5 个 → 解不出（返回空）", rs_erasures_decode(shards2, k) == {})


# ═════════════════ 2. 主控帧 / 雷达帧 ═════════════════

def test_master() -> None:
    print("[2] 主控帧 / 雷达帧")
    fields = list(range(1, 17))
    frame = bytearray(39)
    frame[0:2] = b"\xaa\x55"
    frame[2] = 0x01
    struct.pack_into("<H", frame, 3, 4242)
    for i, v in enumerate(fields):
        struct.pack_into("<h", frame, 5 + i * 2, v)
    struct.pack_into("<H", frame, 37, crc16(bytes(frame[2:37])))
    r = parse_master(bytes(frame))
    check("主控帧解析", r is not None and r[1] == 4242 and r[2] == fields)
    bad = bytearray(frame)
    bad[9] ^= 0xFF
    check("坏 CRC 拒绝", parse_master(bytes(bad)) is None)
    check("长度不对拒绝", parse_master(bytes(frame[:30])) is None)

    lidar = bytearray(60)
    lidar[0:2] = b"\x55\xaa"
    struct.pack_into("<H", lidar, 4, 600)
    struct.pack_into("<H", lidar, 6, 0)
    struct.pack_into("<H", lidar, 56, 600)
    for i in range(16):
        struct.pack_into("<H", lidar, 8 + i * 3, 1000 + i * 10)
        lidar[10 + i * 3] = 80
    check("雷达帧合法", is_lidar_frame(bytes(lidar)))
    check("全零帧拒绝", not is_lidar_frame(bytes(60)))
    pts = parse_lidar(bytes(lidar))
    check("雷达 16 点", len(pts) == 16)
    check("首点角度 0°", abs(pts[0][0]) < 0.01, f"{pts[0][0]}")
    check("点间距 6°/16=0.375°", abs((pts[1][0] - pts[0][0]) - 0.375) < 0.01,
          f"{pts[1][0] - pts[0][0]}")


# ═════════════════ 3. GameStream 组帧 ═════════════════

def _build_gs_frame(annexb: bytes, seq0: int = 0, fec_pct: int = 30,
                    frame_index: int = 7):
    """按手机端 GameStreamSender 的格式造一整帧的 UDP 包。

    短帧头里的 lastPayloadLen 必须先由总长度算出来，再写进头
    （手机端也是这个顺序，写错会让接收端截尾长度不对）。
    frame_index 必须逐帧递增：接收端靠它做幽灵帧防护，同一帧号重复发会被丢掉。
    """
    total = 8 + len(annexb)
    k = (total + 1007) // 1008
    last_len = total - (k - 1) * 1008
    hdr = struct.pack("<BBBBHH", 0x01, 0, 0, 2, last_len, 0)
    stream = hdr + annexb
    shards = [stream[i * 1008:(i + 1) * 1008].ljust(1008, b"\x00") for i in range(k)]
    m = max(4, (k * fec_pct + 99) // 100)
    parity = rs_encode(shards, m)

    pkts = []
    all_shards = shards + parity
    for idx, sh in enumerate(all_shards):
        is_data = idx < k
        flags = 0
        if is_data:
            flags |= 0x01
            if idx == 0:
                flags |= 0x04
            if idx == k - 1:
                flags |= 0x02
        fec_info = ((idx & 0x3FF) << 12) | ((k & 0x3FF) << 22) | ((fec_pct & 0xFF) << 4)
        mfb = (0 << 4) | ((1 - 1) << 6)
        seq = seq0 + idx
        buf = bytearray()
        buf += struct.pack(">BBHII", 0x90, 97, seq & 0xFFFF, 0, 1234)
        buf += b"\x00" * 4
        buf += struct.pack(">IIBBBBi", (seq & 0xFFFFFF) << 8, frame_index,
                           flags, 0, 0x10, mfb, fec_info)
        buf += sh
        pkts.append(bytes(buf))
    return pkts, last_len


def test_framing() -> None:
    print("[3] GameStream 组帧（端到端）")
    annexb = bytes(range(256)) * 12          # 3072B ≈ 3 个分片
    pkts, last_len = _build_gs_frame(annexb)

    cfg = _cfg()
    q: queue.Queue = queue.Queue(maxsize=8)
    rx = VideoReceiver(cfg, q)
    for p in pkts:
        rx._on_packet(p)
    check("还原出 1 帧", not q.empty(), f"qsize={q.qsize()}")
    if q.empty():
        return
    frame, _t = q.get_nowait()
    check("Annex-B 逐字节一致", frame == annexb,
          f"{len(frame)} vs {len(annexb)}")

    # 丢掉 2 个数据分片 → FEC 应能补回来
    q2: queue.Queue = queue.Queue(maxsize=8)
    rx2 = VideoReceiver(cfg, q2)
    for i, p in enumerate(pkts):
        if i in (1, 2):
            continue
        rx2._on_packet(p)
    check("丢 2 片仍出帧", not q2.empty())
    if not q2.empty():
        f2, _ = q2.get_nowait()
        check("FEC 恢复后字节一致", f2 == annexb)

    # 丢掉 5 个分片（>4）→ 不应输出错帧
    q3: queue.Queue = queue.Queue(maxsize=8)
    rx3 = VideoReceiver(cfg, q3)
    for i, p in enumerate(pkts):
        if i in (0, 1, 2, 3, 4):
            continue
        rx3._on_packet(p)
    check("丢 5 片 → 不出帧", q3.empty())

    # 幽灵帧防护：同一帧重复喂，不应出第二帧
    q4: queue.Queue = queue.Queue(maxsize=8)
    rx4 = VideoReceiver(cfg, q4)
    for p in pkts:
        rx4._on_packet(p)
    for p in pkts:
        rx4._on_packet(p)
    check("重复包不产生幽灵帧", q4.qsize() == 1, f"qsize={q4.qsize()}")


# ═════════════════ 4. 相机投影 ═════════════════

def test_camera() -> None:
    print("[4] 相机投影")
    cfg = _cfg()
    cfg["camera"].update({"fx": 1000.0, "fy": 1000.0, "cx": 640.0, "cy": 360.0,
                          "mount_h_mm": 300.0, "mount_pitch_deg": 10.0})
    cam = CameraModel(cfg, 1280, 720)

    rng = cam.optical_axis_range_mm()
    check("光轴落点 = h/sin(theta)", rng is not None and abs(rng - 300 / 0.173648) < 2,
          f"{rng}")
    hv = cam.horizon_v()
    check("地平线在中心上方", hv < cam.cy, f"v={hv:.1f}")
    check("地平线 = cy - fy*tan(theta)", abs(hv - (360 - 1000 * 0.176327)) < 1.0,
          f"{hv:.1f}")

    # 远处地面点应收敛到地平线（Z 要足够大，否则 h 项仍占几个像素）
    p = cam.project_world(0, 1_000_000)
    check("远点收敛到地平线", p is not None and abs(p[1] - hv) < 2.0, f"{p}")

    # 等距环：5m 应落在画面内且有可见段
    segs = cam.ring_segments(5000)
    check("5m 环有可见段", cam.fit(5000, segs))
    # 1m 环在近处，可能落在画面下方之外（正常）
    segs1 = cam.ring_segments(1000)
    check("1m 环投影不崩", isinstance(segs1, list))

    # 左右对称点 u 应关于 cx 对称
    a = cam.project_world(-2000, 5000)
    b = cam.project_world(2000, 5000)
    check("左右对称", a and b and abs((a[0] - cam.cx) + (b[0] - cam.cx)) < 0.01,
          f"{a} {b}")


def run() -> int:
    test_fec()
    test_master()
    test_framing()
    test_camera()
    print(f"\n{PASS}/{PASS + FAIL} 通过" + ("  ALL OK" if FAIL == 0 else "  FAILED"))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(run())
