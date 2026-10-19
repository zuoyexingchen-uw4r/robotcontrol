#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""RS + GameStream 组包 端到端自测（不依赖 Android）。

test1: RS 互逆 —— 用与 Android ReedSolomon.encode 完全一致的逻辑（同一 Cauchy 行）
       产生 parity，再喂给 video_host.rs_erasures_decode，随机丢 ≤m 个分片须全部恢复。
test2: 端到端 —— 按 GameStreamSender 的组包逻辑造一帧（含多 FEC 块），
       随机丢数据/校验包，走 video_host 的接收逻辑，比对还原出的帧字节。
"""
import os
import random
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import video_host as vh

FAIL = 0


def check(name, ok, extra=""):
    global FAIL
    if not ok:
        FAIL += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {name} {extra}")


# ---------- Android ReedSolomon.encode 的逐行镜像（同一公式） ----------
def kt_encode(data_shards, parity):
    k = len(data_shards)
    size = len(data_shards[0])
    out = []
    for t in range(parity):
        crow = vh.cauchy_row(k, t)
        p = bytearray(size)
        for d in range(k):
            c = crow[d]
            if c == 0:
                continue
            tab = vh._GF_TAB[c]
            src = data_shards[d]
            for i in range(size):
                p[i] ^= tab[src[i]]
        out.append(bytes(p))
    return out


def test1_rs_roundtrip():
    print("=== test1: RS 编解码互逆（镜像 Android 编码逻辑） ===")
    rng = random.Random(20261015)
    cases = [(1, 4), (2, 4), (4, 4), (8, 4), (16, 4), (16, 8), (50, 10),
             (100, 20), (150, 30), (200, 50), (255, 0), (3, 2)]
    for k, m in cases:
        if m == 0:
            continue
        size = 64
        shards = [bytes(rng.randrange(256) for _ in range(size)) for _ in range(k)]
        parity = kt_encode(shards, m)
        # 随机丢 n 个数据分片（n ≤ m），用前 n 个 parity 行恢复
        for n in {1, m // 2, m}:
            if n < 1 or n > k:
                continue
            for trial in range(3):
                missing = rng.sample(range(k), n)
                have = {i: shards[i] for i in range(k) if i not in missing}
                for t in range(n):
                    have[k + t] = parity[t]
                rec = vh.rs_erasures_decode(have, k)
                ok = all(rec.get(i) == shards[i] for i in missing)
                if not ok:
                    check(f"k={k} m={m} 丢{missing}", False)
                    return
        check(f"k={k} m={m} 全丢失组合", True)

    # parity 行本身丢失时只少用一行：丢 1 数据 + 1 parity，仍可恢复
    k, m, size = 16, 8, 64
    shards = [bytes(rng.randrange(256) for _ in range(size)) for _ in range(k)]
    parity = kt_encode(shards, m)
    missing = [3]
    have = {i: shards[i] for i in range(k) if i not in missing}
    have[k + 0] = parity[0]
    have[k + 5] = parity[5]  # parity 1..4,6,7 丢失，用行 0 和 5
    rec = vh.rs_erasures_decode(have, k)
    check("丢1数据+多数parity（用行0,5）", rec.get(3) == shards[3])

    # 丢失数 > parity → 必须失败而不是错数据
    have = {i: shards[i] for i in range(k)}
    for t in range(m):
        have[k + t] = parity[t]
    del have[2]
    del have[7]
    del have[9]
    del have[11]
    del have[13]  # 丢 5 > m=4... m=8 这里改成丢 9 个
    for i in [1, 4, 5, 6]:
        del have[i]
    rec = vh.rs_erasures_decode(have, k)
    check("丢失数>parity → 返回空", rec == {})


# ---------- GameStreamSender 组包逻辑镜像 ----------
HEADER = 32
SHORT_FH = 8
PAYLOAD_BLOCK = 1008
FLAG_PIC, FLAG_EOF, FLAG_SOF = 0x01, 0x02, 0x04


def max_shards_per_block(pct):
    """Android GameStream.maxDataShardsPerBlock 镜像"""
    return min(25500 // (100 + pct), 255 - 4)


def build_frame_packets(annexB, frame_type, frame_index, seq0, ssrc, ts, pct):
    """返回 [(seq, fec_info, multi_fec_blocks, flags, payload), ...] 与总分片数"""
    stream = bytearray(SHORT_FH) + annexB
    data_shards = (len(stream) + PAYLOAD_BLOCK - 1) // PAYLOAD_BLOCK
    last_len = len(stream) - (data_shards - 1) * PAYLOAD_BLOCK
    stream[0:8] = bytes([0x01, 0, 0, frame_type,
                         last_len & 0xFF, (last_len >> 8) & 0xFF, 0, 0])
    shards = []
    off = 0
    for _ in range(data_shards):
        s = bytearray(PAYLOAD_BLOCK)
        n = min(PAYLOAD_BLOCK, len(stream) - off)
        s[:n] = stream[off:off + n]
        shards.append(bytes(s))
        off += n

    per_blk = max_shards_per_block(pct)
    blocks = 1 if data_shards <= per_blk else min(4, (data_shards + per_blk - 1) // per_blk)
    per = []
    rem = data_shards
    for i in range(blocks):
        take = min(255, (rem + blocks - i - 1) // (blocks - i))
        per.append(take)
        rem -= take

    pkts = []
    seq = seq0
    base = 0
    sof = True
    for b, k in enumerate(per):
        parity = max(4, (k * pct + 99) // 100)
        parity = min(parity, 255 - k)
        parity = max(parity, 0)
        bd = shards[base:base + k]
        pd = kt_encode(bd, parity) if parity > 0 else []
        mfb = (b << 4) | ((blocks - 1) << 6)
        last_block = b == blocks - 1
        for i in range(k):
            flags = FLAG_PIC
            if sof:
                flags |= FLAG_SOF
                sof = False
            if last_block and i == k - 1 and parity == 0:
                flags |= FLAG_EOF
            pkts.append((seq, (i << 12) | (k << 22) | (pct << 4), mfb, flags, bd[i]))
            seq += 1
        for i in range(parity):
            flags = FLAG_EOF if (last_block and i == parity - 1) else 0
            pkts.append((seq, ((k + i) << 12) | (k << 22) | (pct << 4), mfb, flags, pd[i]))
            seq += 1
        base += k
    return pkts, data_shards


def rx_deliver(frames_buf, sink, pkt):
    """video_host 主循环接收逻辑镜像"""
    seq, fec_info, mfb, flags, payload = pkt
    frame_index = 7  # 单帧测试
    pkt_idx = (fec_info >> 12) & 0x3FF
    k = (fec_info >> 22) & 0x3FF
    block_num = (mfb >> 4) & 0x3
    nblocks = ((mfb >> 6) & 0x3) + 1
    fb = frames_buf.setdefault((frame_index, block_num), {"shards": {}, "k": k, "nblocks": nblocks})
    fb["k"] = k
    fb["nblocks"] = nblocks
    if pkt_idx not in fb["shards"]:
        fb["shards"][pkt_idx] = payload
    if "data" not in fb:
        shards = fb["shards"]
        if k and len(shards) >= k and all(i in shards for i in range(k)):
            fb["data"] = b"".join(shards[i] for i in range(k))
        elif k and any(i not in shards for i in range(k)):
            rec = vh.rs_erasures_decode(shards, k)
            if rec:
                shards.update(rec)
                fb["data"] = b"".join(shards[i] for i in range(k))
    if "data" in fb:
        blocks = [b for (fi, b) in frames_buf if fi == frame_index and "data" in frames_buf[(fi, b)]]
        if len(blocks) >= fb["nblocks"]:
            stream = b"".join(frames_buf[(frame_index, b)]["data"] for b in range(fb["nblocks"]))
            last_len = struct.unpack_from("<H", stream, 4)[0]
            total_k = sum(frames_buf[(frame_index, b)]["k"] for b in range(fb["nblocks"]))
            end = (total_k - 1) * PAYLOAD_BLOCK + last_len
            sink.append(stream[8:end])
            for b in range(fb["nblocks"]):
                frames_buf.pop((frame_index, b), None)


def test2_end_to_end():
    print("=== test2: 组包 → 随机丢包 → 接收重组 ===")
    rng = random.Random(777)
    # (名称, NAL 字节数, [(丢包率, 期望恢复)])——丢失≤parity 必须逐字节还原；超过则干净丢帧
    cases = [
        ("小帧(1块)", 3000, [(0.0, True), (0.10, True), (0.25, True)]),
        ("中帧(3块)", 600 * 1008 + 500, [(0.0, True), (0.10, True), (0.25, False)]),
        ("大帧(4块)", 700 * 1008 + 123, [(0.0, True), (0.10, True), (0.25, False)]),
    ]
    for name, nbytes, losses in cases:
        annexB = bytes(rng.randrange(256) for _ in range(nbytes))
        pkts, total_k = build_frame_packets(annexB, 2, 7, 1000, 42, 90000, 20)
        for drop_pct, expect in losses:
            frames_buf = {}
            sink = []
            for p in pkts:
                if rng.random() < drop_pct:
                    continue
                rx_deliver(frames_buf, sink, p)
            got = sink[0] if sink else b""
            if expect:
                ok = got == annexB
                note = f"还原 {len(got)}/{len(annexB)} B"
            else:
                ok = got == b""   # 超 FEC 容量：必须丢帧，不能出损坏数据
                note = "干净丢帧" if ok else f"错误输出 {len(got)} B（损坏！）"
            check(f"{name} ({total_k} 分片, {len(pkts)} 包) 丢{int(drop_pct*100)}%", ok, note)


if __name__ == "__main__":
    test1_rs_roundtrip()
    test2_end_to_end()
    print()
    print("全部通过 ✅" if FAIL == 0 else f"{FAIL} 项失败 ❌")
    sys.exit(1 if FAIL else 0)
