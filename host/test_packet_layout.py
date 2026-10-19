#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
包头字节布局自测：验证 video_host.py 的解包偏移与 Android 端
GameStream.buildPacket 逐字节一致。

背景：曾经把 NV 头偏移写成 12（实际是 16，因为 RTP 后面有 4B reserved），
导致 frameIndex 解出来其实是 streamPacketIndex，收到包却 0 帧组装成功。
这个自测就是为防止再犯——按发送端真实字节序构造一个包，
用接收端同款常量解包，逐字段断言。

用法：python test_packet_layout.py
"""
import struct
import sys

# 与 video_host.py 保持一致
RTP_HDR = struct.Struct(">BBHII")            # 12B
NV_HDR = struct.Struct(">IIBBBBi")           # 16B
PAYLOAD_BLOCK = 1008

# 与 GameStream.kt 一致
RTP_HEADER = 0x90
PAYLOAD_TYPE_VIDEO = 97
HEADER_SIZE = 32
MULTI_FEC_FLAGS = 0x10


def build_packet_android(seq, timestamp, ssrc, frame_index, flags, extra_flags,
                         multi_fec_blocks, fec_info, payload):
    """逐字节复刻 GameStream.buildPacket 的写入顺序"""
    buf = bytearray()
    buf += struct.pack(">B", RTP_HEADER)                       # [0]
    buf += struct.pack(">B", PAYLOAD_TYPE_VIDEO)               # [1]
    buf += struct.pack(">H", seq & 0xFFFF)                     # [2:4]
    buf += struct.pack(">I", timestamp & 0xFFFFFFFF)           # [4:8]
    buf += struct.pack(">I", ssrc & 0xFFFFFFFF)                # [8:12]
    buf += b"\x00\x00\x00\x00"                                 # [12:16] reserved
    buf += struct.pack(">I", (seq & 0xFFFFFF) << 8)            # [16:20] streamPacketIndex
    buf += struct.pack(">I", frame_index & 0xFFFFFFFF)         # [20:24]
    buf += struct.pack(">B", flags & 0xFF)                     # [24]
    buf += struct.pack(">B", extra_flags & 0xFF)               # [25]
    buf += struct.pack(">B", MULTI_FEC_FLAGS)                  # [26]
    buf += struct.pack(">B", multi_fec_blocks & 0xFF)          # [27]
    buf += struct.pack(">i", fec_info)                         # [28:32]
    buf += payload                                              # [32:]
    assert len(buf) == HEADER_SIZE + len(payload), (len(buf), HEADER_SIZE, len(payload))
    return bytes(buf)


def pack_fec_info(pkt_idx, k, pct):
    """复刻 GameStream.packFecInfo：bits4-11=pct, bits12-21=idx, bits22-31=k"""
    return ((pkt_idx & 0x3FF) << 12) | ((k & 0x3FF) << 22) | ((pct & 0xFF) << 4)


def main():
    fails = []
    k, m, pct = 100, 30, 30

    # 构造一帧的第 0 个数据分片 和 第 k 个校验分片，验证解包
    fec_data = pack_fec_info(0, k, pct)
    pkt = build_packet_android(
        seq=1234, timestamp=90000, ssrc=0xABCD,
        frame_index=42, flags=0x05, extra_flags=0,
        multi_fec_blocks=(0 << 4) | ((1 - 1) << 6),   # 单块：blockNum=0, nblocks-1=0
        fec_info=fec_data,
        payload=b"\x11" * PAYLOAD_BLOCK,
    )

    # ---- 接收端解包（与 video_host.py 完全相同的偏移）----
    rtp_hdr, rtp_pt, seq_r, ts_r, ssrc_r = RTP_HDR.unpack_from(pkt, 0)
    (spi, fi, fl, ef, mff, mfb, fec_info_r) = NV_HDR.unpack_from(pkt, 16)   # 关键偏移 16
    payload = pkt[32:]

    checks = [
        ("RTP header", rtp_hdr, 0x90),
        ("payloadType", rtp_pt, 97),
        ("sequenceNumber", seq_r, 1234),
        ("timestamp", ts_r, 90000),
        ("ssrc", ssrc_r, 0xABCD),
        ("frameIndex", fi, 42),
        ("flags", fl, 0x05),
        ("multiFecFlags", mff, 0x10),
        ("fecInfo", fec_info_r, fec_data),
        ("payload 长度", len(payload), PAYLOAD_BLOCK),
        ("包总长", len(pkt), HEADER_SIZE + PAYLOAD_BLOCK),
    ]
    for name, got, want in checks:
        ok = got == want
        print(f"  {'✅' if ok else '❌'} {name:18} 得到 {got!r:>12}  期望 {want!r}")
        if not ok:
            fails.append(name)

    # fecInfo 位域还原
    pkt_idx = (fec_info_r >> 12) & 0x3FF
    k_r = (fec_info_r >> 22) & 0x3FF
    pct_r = (fec_info_r >> 4) & 0xFF
    for name, got, want in [("fecInfo→pkt_idx", pkt_idx, 0), ("fecInfo→k", k_r, k), ("fecInfo→pct", pct_r, pct)]:
        ok = got == want
        print(f"  {'✅' if ok else '❌'} {name:18} 得到 {got!r:>12}  期望 {want!r}")
        if not ok:
            fails.append(name)

    # 反向证明：如果误用偏移 12，frameIndex 会解错（这就是曾经的 bug）
    (spi12, fi12, *_rest) = NV_HDR.unpack_from(pkt, 12)
    if fi12 == fi:
        fails.append("偏移12竟也正确（意外，需人工确认布局）")
        print("  ❌ 偏移 12 与 16 解出相同 frameIndex（不符合预期）")
    else:
        print(f"  ✅ 偏移12会解出错误 frameIndex={fi12}（≠{fi}）——旧 bug 已证实")

    print()
    if fails:
        print(f"失败 {len(fails)} 项: {fails}")
        sys.exit(1)
    print("全部通过：解包偏移与 Android 端字节布局一致 ✅")


if __name__ == "__main__":
    main()
