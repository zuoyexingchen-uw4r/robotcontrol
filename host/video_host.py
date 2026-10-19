#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
摄像头图传上位机（Sunshine/Moonlight GameStream 兼容）。

wire format（对照 Sunshine stream.cpp / video.h）：
  每个 UDP 包 = 32B 头 + 净载荷：
    [RTP 12B][reserved 4B][NV_VIDEO_PACKET 16B][payload]
  RTP: 0x90 | PT=97 | seq(u16 BE) | timestamp(u32 BE, 90kHz) | ssrc(u32 BE)
  NV : streamPacketIndex(u32)=seq<<8 | frameIndex(u32) | flags(u8) | extraFlags(u8)
       | multiFecFlags(u8) | multiFecBlocks(u8) | fecInfo(u32)
  flags: 0x01=PIC_DATA 0x02=EOF 0x04=SOF；parity 包 flags=0
  fecInfo: (pktIdx&0x3FF)<<12 | (dataShards&0x3FF)<<22 | (pct&0xFF)<<4
      **pktIdx 是 FEC 块内分片序号**：数据 0..k-1，校验 k..k+m-1
  multiFecBlocks: (blockNum&0x3)<<4 | ((numBlocks-1)&0x3)<<6

丢包恢复：RTP seq 连续，缺段即知缺哪些分片 → GF(256) 擦除解码。
校验矩阵与 Android ReedSolomon.kt 完全一致（Cauchy，MDS）：
  C[t][j] = 1/((k+1+t) ^ (j+1))，只依赖 k，与 m 无关。

帧组装：多 FEC 块按 (frameIndex, blockNum) 分别缓存，全部块到齐后拼接；
短帧头（8B）在重建出的数据流头部解析——SOF 包丢了也能靠 FEC 恢复后读。

控制通道：连上后周期性发 4B 大端 0x0301（SS_RFI_REQUEST）请求关键帧。

用法：
  python video_host.py --host <手机IP> --vport 46000 --cport 47000 --play
  python video_host.py --host <手机IP> --vport 46000 --out stream.h265
"""
import argparse
import queue as _queue
import socket
import struct
import subprocess
import sys
import threading
import time

# ---------- GF(256) Reed-Solomon 擦除解码 ----------
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


# 每个系数一张 256 项翻译表：整段字节乘 GF 标量走 C 速度
_GF_TAB = [bytes(gf_mul(c, v) for v in range(256)) for c in range(256)]


def gf_mul_bytes(c, data):
    return data.translate(_GF_TAB[c])


def cauchy_row(k, t):
    """Android ReedSolomon.parityMatrix 的第 t 行：inv((k+1+t) ^ (j+1))"""
    x = k + 1 + t
    return [gf_inv(x ^ (j + 1)) for j in range(k)]


def rs_erasures_decode(shards, k):
    """shards: {块内分片号: bytes}，0..k-1 为数据、≥k 为 parity（分片号即块内序号）。
    丢失 n 个数据分片时，用任意 n 个已收 parity 行做擦除解码（Cauchy 子阵必非奇异）。
    返回 {分片号: bytes} 补全的数据分片；无法恢复返回 {}。"""
    missing = [i for i in range(k) if i not in shards]
    n = len(missing)
    if n == 0:
        return {}
    avail = [i - k for i in sorted(shards) if i >= k]
    if len(avail) < n:
        return {}
    rows = avail[:n]
    size = len(next(iter(shards.values())))

    # b_t = parity_t XOR Σ_{d 已收到} C[t][d]·shard[d]  （大整数 XOR，C 速度）
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

    # A[i][j] = C[rows[i]][missing[j]]（Cauchy 方形子阵 → 可逆）
    A = [[cauchy_row(k, t)[d] for d in missing] for t in rows]

    def mul_big(c, x):
        if c == 0:
            return 0
        if c == 1:
            return x
        return int.from_bytes(x.to_bytes(size, "big").translate(_GF_TAB[c]), "big")

    # 高斯消元：前 n 列为标量系数，末列为大整数向量
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
                M[r] = [
                    a ^ gf_mul(f, bb) for a, bb in zip(M[r][:n], M[col][:n])
                ] + [M[r][n] ^ mul_big(f, M[col][n])]
    return {missing[i]: M[i][n].to_bytes(size, "big") for i in range(n)}


# ---------- 主流程 ----------
RTP_HDR = struct.Struct(">BBHII")            # 12B
NV_HDR = struct.Struct(">IIBBBBi")           # 16B: streamPacketIndex, frameIndex, flags,
                                             # extraFlags, multiFecFlags, multiFecBlocks, fecInfo
assert RTP_HDR.size == 12 and NV_HDR.size == 16, (RTP_HDR.size, NV_HDR.size)
PAYLOAD_BLOCK = 1008                          # 与 GameStream.PAYLOAD_BLOCK_SIZE 一致


def main():
    ap = argparse.ArgumentParser(description="GameStream-compatible H.264/HEVC receiver")
    # --host 仅用于日志显示；协议已改为电脑监听、手机主动连接，不需要填 IP 也能收
    ap.add_argument("--host", default="", help="（可选，仅日志用）手机 IP")
    ap.add_argument("--vport", type=int, default=46000, help="视频 UDP 端口")
    ap.add_argument("--cport", type=int, default=47000, help="控制 TCP 端口")
    ap.add_argument("--out", default="stream.h265", help="输出裸流文件名")
    ap.add_argument("--play", action="store_true", help="管道给 ffplay 实时播放")
    ap.add_argument("--kf-every", type=float, default=2.0, help="每 N 秒请求一次关键帧")
    ap.add_argument("--show-ffplay-log", action="store_true",
                    help="显示 ffplay 原始日志（默认隐藏，HEVC 解码报错会刷屏）")
    args = ap.parse_args()

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", args.vport))
    # 超时让 recvfrom 周期性返回，Ctrl+C 才能及时打断（阻塞时信号要等系统调用结束）
    udp.settimeout(0.5)

    if args.play:
        # probesize 32 太小，ffplay 会报 "not enough frames to estimate rate"
        # 且起播明显变慢；32768 足够识别 SPS/PPS，又不至于拖延迟
        #
        # stderr 用**管道**而不是 DEVNULL：ffplay 带 -stats 会往 stderr 写
        #   frame=  600 fps= 30.0 q=28.0 size=... time=...
        # 这个 fps/frame 是**解码器实际处理**的数字，不是我们送了多少——
        # 退出时据此报告真实解码帧率（用来判断 ffplay 的播放节奏是否跟不上送入）。
        # 原始行默认不打印（HEVC 一遇参考帧缺失就刷几千行），
        # 只有 --show-ffplay-log 时才回显；需要排查时再加。
        proc = subprocess.Popen(
            ["ffplay", "-flags", "low_delay", "-fflags", "nobuffer", "-framedrop",
             "-probesize", "32768", "-analyzeduration", "0", "-stats",
             "-window_title", "RobotControl 图传", "-i", "pipe:0"],
            stdin=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        sink = proc.stdin
        f = None

        import re as _re
        ff_stats = {"frame": 0, "fps": 0.0}
        _pat = _re.compile(rb"frame=\s*(\d+)\s+fps=\s*([\d.]+)")

        def read_ff_stderr():
            try:
                for line in iter(proc.stderr.readline, b""):
                    if args.show_ffplay_log:
                        sys.stderr.write(line.decode("utf-8", "replace"))
                    m = _pat.search(line)
                    if m:
                        ff_stats["frame"] = int(m.group(1))
                        ff_stats["fps"] = float(m.group(2))
            except Exception:
                pass

        ff_reader = threading.Thread(target=read_ff_stderr, daemon=True)
        ff_reader.start()
    else:
        f = open(args.out, "wb")
        sink = f
        proc = None

    stop = threading.Event()
    frames = {"n": 0}        # 成功写出并交给 ffplay 的帧
    rs_frames = {"n": 0}     # 其中靠 RS FEC 恢复出来的帧
    timeouts = {"n": 0}      # 超时未凑齐、被清理掉的帧
    bytes_acc = {"n": 0}
    t0 = time.time()
    def _cl(x):
        try:
            x.close()
        except Exception:
            pass

    # 写入独立线程：ffplay/磁盘一旦慢下来，不会反过来阻塞 UDP 收包
    # （单线程时 sink.write 卡住 → recvfrom 停 → 内核 UDP 缓冲堆积 → 延迟累积）。
    # 队列很小（8 帧）且满时丢最旧——旧帧到播放端本就过期，保低延迟优先。
    write_q = _queue.Queue(maxsize=8)
    backpressure = {"n": 0}   # 因队列满被丢弃的帧数

    def writer():
        while not stop.is_set():
            try:
                data = write_q.get(timeout=0.5)
            except _queue.Empty:
                continue
            try:
                sink.write(data)
                sink.flush()
            except Exception:
                break

    threading.Thread(target=writer, daemon=True).start()

    def ctrl():
        """关键帧请求通道：**电脑开端口监听，手机连上来**（2026-10 协议方向反转，
        旧版是电脑连手机）。连上后按 --kf-every 周期发 SS_RFI_REQUEST 请求 IDR。"""
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("0.0.0.0", args.cport))
            srv.listen(1)
            while not stop.is_set():
                try:
                    srv.settimeout(1.0)
                    cs, addr = srv.accept()
                except socket.timeout:
                    continue
                cs.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                sys.stderr.write(f"\n[ctrl] 手机已连接 {addr[0]}:{addr[1]}（每 {args.kf_every}s 请求关键帧）")
                try:
                    while not stop.is_set():
                        cs.sendall(struct.pack(">I", 0x0301))  # SS_RFI_REQUEST → 请求 IDR
                        time.sleep(args.kf_every)
                except Exception:
                    pass
                finally:
                    _cl(cs)
        except Exception as e:
            sys.stderr.write(f"\n[ctrl] 监听失败: {e}")
        finally:
            _cl(srv)
    threading.Thread(target=ctrl, daemon=True).start()

    frames_buf = {}   # (frame_index, block_num) -> {"shards": {分片号: bytes}, "k": int, "data": bytes, "t0": float}
    emitted = {}      # frame_index -> 已输出（防迟到包重组出幽灵帧重复输出）
    last_seq = -1
    last_cleanup = time.time()
    max_frame_seen = -1     # 迄今见到的最大 frame_index，用于检测推流重启
    gap_cnt = {"n": 0, "pkts": 0}   # 丢包累计，按秒汇总，避免每个 gap 刷一行

    def block_complete(fb):
        """块内分片到齐（或可 FEC 恢复）→ (该块 1008B×k 字节流, 是否用了RS恢复)；失败返回 None"""
        shards = fb["shards"]
        k = fb["k"]
        if not k or len(shards) < k:
            return None
        used_rs = False
        if any(i not in shards for i in range(k)):
            recovered = rs_erasures_decode(shards, k)
            if not recovered:
                return None
            shards.update(recovered)
            used_rs = True
        return b"".join(shards[i] for i in range(k)), used_rs

    def try_emit(frame_index):
        """所有 FEC 块到齐 → 拼接、剥短帧头、按 lastPayloadLen 截尾、写出"""
        blocks = [b for (fi, b) in frames_buf if fi == frame_index and "data" in frames_buf[(fi, b)]]
        if not blocks:
            return False
        nblocks = max(frames_buf[(frame_index, b)]["nblocks"] for b in blocks)
        if len(blocks) < nblocks:
            return False
        stream = b"".join(frames_buf[(frame_index, b)]["data"] for b in range(nblocks))
        # 短帧头在数据流头部：0x01 | latency(u16 LE) | frameType(u8) | lastPayloadLen(u16 LE) | 2B 0
        if len(stream) < 8:
            return False
        last_len = struct.unpack_from("<H", stream, 4)[0]
        total_k = sum(frames_buf[(frame_index, b)]["k"] for b in range(nblocks))
        end = (total_k - 1) * PAYLOAD_BLOCK + last_len
        frame = stream[8:end] if end > 8 else b""
        if frame:
            # 低延迟：入队交给写入线程；队列满则丢最旧的旧帧
            if write_q.full():
                try:
                    write_q.get_nowait()
                    backpressure["n"] += 1
                except _queue.Empty:
                    pass
            try:
                write_q.put_nowait(frame)
                frames["n"] += 1
                bytes_acc["n"] += len(frame)
            except _queue.Full:
                pass
        for b in range(nblocks):
            frames_buf.pop((frame_index, b), None)
        emitted[frame_index] = True
        # 只保留最近 512 个已输出帧号，防止字典无限增长
        if len(emitted) > 512:
            for old in sorted(emitted)[:256]:
                emitted.pop(old, None)
        return True

    try:
        while not stop.is_set():
            try:
                pkt, _ = udp.recvfrom(65535)
            except socket.timeout:
                continue
            if len(pkt) < 32:
                continue
            rtp_hdr, rtp_pt, seq, ts, ssrc = RTP_HDR.unpack_from(pkt, 0)
            if (rtp_hdr & 0xC0) != 0x80:  # V != 2
                continue
            # 包头布局（与 GameStream.buildPacket 逐字节对齐）：
            #   [0:12]  RTP(header,pt,seq,ts,ssrc)
            #   [12:16] reserved（4B 0）
            #   [16:32] NV_VIDEO_PACKET(streamPacketIndex,frameIndex,flags,extraFlags,
            #                            multiFecFlags,multiFecBlocks,fecInfo)
            #   [32:]   净载荷
            # ⚠️ NV 头从偏移 **16** 读。之前误用 12（把 reserved 当 streamPacketIndex），
            # 导致 frameIndex 解出来其实是 streamPacketIndex（每包都变），
            # 于是每包被当成一个新帧、永远凑不齐 k 个分片 → 收到包但 0 帧组装成功。
            (stream_pkt_idx, frame_index, flags, extra_flags,
             multi_fec_flags, multi_fec_blocks, fec_info) = NV_HDR.unpack_from(pkt, 16)
            payload = pkt[32:]

            # gap 改为累计，按秒汇总（原来每丢一个包刷一行，日志量爆炸）
            if last_seq >= 0 and seq != (last_seq + 1) & 0xFFFF:
                gap_cnt["n"] += 1
                gap_cnt["pkts"] += (seq - last_seq - 1) & 0xFFFF
            last_seq = seq

            pkt_idx = (fec_info >> 12) & 0x3FF      # 块内分片序号
            k = (fec_info >> 22) & 0x3FF            # 块内数据分片数
            block_num = (multi_fec_blocks >> 4) & 0x3
            nblocks = ((multi_fec_blocks >> 6) & 0x3) + 1

            # 幽灵帧防护：已输出过的帧，其迟到包直接丢弃。
            # 否则晚到的分片会和新到的分片在 frames_buf 里重新凑齐一个 k，
            # 被 block_complete 判为完整帧而**再次输出**同一帧——
            # 实测表现为 out(57) 远超 fps_in(30)、画面一卡一卡。
            #
            # 但要注意跨流生命周期：手机端「停止推流 → 改分辨率 → 重新推流」时
            # frameIndex 从 0 重新开始，而 emitted 里还留着上一轮的低序号帧，
            # 新流帧会被误判成幽灵帧全部丢弃 → 电脑端一直加载不出图像。
            # 所以先检测 frame_index 大幅回退，是则重置全部状态。
            # ⚠️ 重置时必须把 max_frame_seen 也降到当前值，否则它会一直记着旧的大值，
            # 后续每个包都满足"回退"条件 → 疯狂重复触发（实测刷屏十几次）。
            if max_frame_seen >= 0 and frame_index < max_frame_seen - 500:
                frames_buf.clear()
                emitted.clear()
                last_seq = -1
                max_frame_seen = frame_index
                sys.stderr.write(f"\n[recv] 检测到推流重启（frameIndex 回退），已重置接收状态")
            max_frame_seen = max(max_frame_seen, frame_index)

            if frame_index in emitted:
                continue

            fb = frames_buf.setdefault((frame_index, block_num), {"shards": {}, "k": k, "nblocks": nblocks, "t0": time.time()})
            fb["k"] = k
            fb["nblocks"] = nblocks
            if pkt_idx not in fb["shards"]:          # 重传/乱序重复包只存首见
                fb["shards"][pkt_idx] = payload

            if "data" not in fb:
                data = block_complete(fb)
                if data is not None:
                    fb["data"] = data
                    rs_frames["n"] += 1 if data[1] else 0
                    fb["data"] = data[0]
                    try_emit(frame_index)

            # 清理超时未凑齐的帧（缺的分片永远等不到，不能一直占内存）
            now_t = time.time()
            if now_t - last_cleanup >= 0.5:
                last_cleanup = now_t
                for key in list(frames_buf.keys()):
                    if now_t - frames_buf[key].get("t0", now_t) > 1.0:
                        frames_buf.pop(key, None)
                        timeouts["n"] += 1
                # 兜底：极端情况内存仍膨胀时，丢最老的
                if len(frames_buf) > 256:
                    for key in list(frames_buf.keys())[:64]:
                        frames_buf.pop(key, None)
                        timeouts["n"] += 1

            dt = time.time() - t0
            if dt >= 1.0:
                kbps = bytes_acc["n"] * 8 / 1000.0 / dt
                # out=成功输出帧  rs=其中RS恢复的  to=超时未凑齐被清理的  buf=当前待组装块数
                # gap=本秒丢包次数/包数（汇总，不逐条刷）
                gap_txt = f" gap={gap_cnt['n']}次/{gap_cnt['pkts']}包" if gap_cnt["n"] else ""
                bp_txt = f" bp={backpressure['n']}" if backpressure["n"] else ""
                sys.stderr.write(
                    f"\r[recv] {frames['n']/dt:5.1f} fps | {kbps:5.0f} kbps | "
                    f"out={frames['n']:3d} rs={rs_frames['n']:3d} to={timeouts['n']:3d} "
                    f"buf={len(frames_buf):3d}{gap_txt}{bp_txt}"
                )
                sys.stderr.flush()
                frames["n"] = 0
                rs_frames["n"] = 0
                timeouts["n"] = 0
                gap_cnt["n"] = 0
                gap_cnt["pkts"] = 0
                backpressure["n"] = 0
                bytes_acc["n"] = 0
                t0 = time.time()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        try:
            if proc is not None:
                # 先关 stdin 让 ffplay 自然退出；3 秒不退就 kill，
                # 否则 Ctrl+C 后 ffplay 窗口/进程会残留，下次起播会抢不到窗口
                _cl(sink)
                try:
                    proc.wait(timeout=3)
                except Exception:
                    proc.kill()
                # 等 stderr 读线程把最后的统计行吃完，再报告**解码器实际**帧率
                try:
                    ff_reader.join(timeout=1.0)
                except Exception:
                    pass
                sys.stderr.write(
                    f"\n[ffplay] 解码器实际输出 {ff_stats['fps']:.2f} fps，累计解码 {ff_stats['frame']} 帧"
                    f"（对照 out= 是接收端送给它的帧数；两者持续拉开就说明播放节奏跟不上，延迟会漂）"
                )
            elif f is not None:
                f.close()
        except Exception:
            pass
        sys.stderr.write("\n[stopped]\n")


if __name__ == "__main__":
    main()
