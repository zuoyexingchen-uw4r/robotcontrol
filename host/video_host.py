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
import os
import queue as _queue
import socket
import struct
import subprocess
import sys
import threading
import time
import unicodedata
from collections import deque

# ---------- 终端输出工具 ----------
def _disp_width(s):
    """字符串在等宽终端里的显示列数：CJK 全角字符（次/包等）占 2 列。"""
    return sum(2 if unicodedata.east_asian_width(c) in ("W", "F") else 1 for c in s)


def _term_cols():
    for fd in (sys.stderr.fileno(), sys.stdout.fileno()):
        try:
            return os.get_terminal_size(fd).columns
        except Exception:
            pass
    return 120


def _pad_line(line):
    """把统计行补齐到终端宽度。

    统计行用 \\r 回到行首覆盖刷新，新行比旧行**短**时旧行尾部会残留在屏幕上——
    实测表现就是 `vq=302KBKB` 这种莫名多出一截的乱码（gap= 的次数/包数位数一变，
    行长度就变）。补齐到整行宽度，覆盖才干净。
    """
    pad = _term_cols() - _disp_width(line)
    return line + " " * pad if pad > 0 else line


def _drift_verdict(net, fd, boost):
    """根据「净积压」和 ffplay 丢帧数给出结论，而不是永远说"没问题"。"""
    if net > 0:
        return (f"净积压 {net} 帧且在涨 = 播放端解不过来，延迟在漂；"
                f"先加大 --fps-boost（当前 {boost}，试 3~4），或降码率/分辨率")
    if fd > 0:
        return (f"ffplay 主动丢了 {fd} 帧 = 播放节奏比送入快不少（略有快放感），"
                f"可把 --fps-boost 从 {boost} 调小")
    return "净积压 ≤0 且 ffplay 没丢帧 = 节奏匹配、延迟不会漂"

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
    ap.add_argument("--fps", type=int, default=30, help="发送端帧率档位（15/24/30，需与手机端一致）")
    ap.add_argument("--fps-boost", type=int, default=2,
                    help="播放帧率 = --fps + 该值。留 1~2 的追赶余量，让 ffplay 永远比送入快一点，"
                         "从根上避免内部队列积压导致的延迟漂移")
    ap.add_argument("--show-ffplay-log", action="store_true",
                    help="显示 ffplay 原始日志（默认隐藏，HEVC 解码报错会刷屏）")
    args = ap.parse_args()

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.bind(("0.0.0.0", args.vport))
    # 超时让 recvfrom 周期性返回，Ctrl+C 才能及时打断（阻塞时信号要等系统调用结束）
    udp.settimeout(0.5)

    def _cl(x):
        try:
            x.close()
        except Exception:
            pass

    if args.play:
        # probesize 32 太小，ffplay 会报 "not enough frames to estimate rate"
        # 且起播明显变慢；32768 足够识别 SPS/PPS，又不至于拖延迟
        #
        # stderr 用**管道**而不是 DEVNULL：要从中解析解码统计与内部队列积压。
        # 原始行默认不打印（HEVC 一遇参考帧缺失就刷几千行），
        # 只有 --show-ffplay-log 时才回显；需要排查时再加。
        # 播放帧率 = 档位 + boost：让 ffplay 永远比送入快 1~2fps。
        # 实测对照：30fps 档 ffplay 按 29.8 播（比送入慢）→ 内部队列持续增长 →
        # 延迟从几十 ms 漂到 3~5s；15fps 档它猜 25（比送入快）→ 一直小幅追赶、
        # 完全不积压、延迟 24ms。所以正确做法不是"猜准"，而是**故意猜高一点**。
        # ⚠️ 这里**不能**用 -r：ffplay 的选项表里没有 -r（那是 ffmpeg 专属），
        # 实测会直接报 "Failed to set value '32' for option 'r': Option not found"
        # 然后立刻退出 → 窗口不出来、一帧也没解码，而报错被 stderr 管道吞掉，
        # 表面上只看到 "解码器实际输出 0.00 fps"，极难排查（2026-10-19 踩过）。
        # 裸流（h264/hevc demuxer）的正确输入帧率选项是 **-framerate**，
        # 它是 demuxer 的私有选项，实测生效：不指定时 ffplay 猜 25 fps，
        # 指定 -framerate 32 后流信息就是 "32 fps"。放在 -i 前、对 pipe:0 同样有效。
        #
        # ⚠️⚠️ 怎么拿到"解码器实际按多少帧率解的"（**不能是复述我们送进去的值**）：
        # ffplay 的 -stats 输出是这种格式（实测抓取）：
        #       nan M-V:    nan fd=   0 aq=    0KB vq=    0KB sq=    0B
        #   里面**没有** frame=/fps= ——那是 ffmpeg(命令行)的格式，ffplay 完全不同。
        #   所以真正的解码帧数要用 **-vf showinfo**：它每解码一帧打一行
        #       [Parsed_showinfo_0 @ 0x...] n:   0 pts: NOPTS pts_time:NOPTS ...
        #   n 就是解码器吐出的帧序号 —— 这是解码器自己的输出，不是我们喂了多少。
        #   vq= 同时给出 ffplay **内部视频队列积压量**，是判断延迟会不会漂的直接指标
        #   （持续上涨 = 播放节奏跟不上送入 = 延迟累积）。
        play_fps = args.fps + args.fps_boost
        proc = subprocess.Popen(
            ["ffplay", "-flags", "low_delay", "-fflags", "nobuffer", "-framedrop",
             "-probesize", "32768", "-analyzeduration", "0", "-stats",
             "-framerate", str(play_fps), "-vf", "showinfo",
             "-window_title", f"RobotControl 图传 ({play_fps}fps 播放)", "-i", "pipe:0"],
            stdin=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        sink = proc.stdin
        f = None

        import re as _re
        # frame: showinfo 累计解码帧数（解码器自己的输出，不是我们喂了多少）
        # vq: ffplay 内部**编码包**队列当前 KB（每秒刷新，能看趋势）；vq_max: 峰值
        # fd: ffplay 主动丢帧累计（framedrop 丢的，越多说明播放端越跟不上）
        ff_stats = {"frame": 0, "vq": 0, "vq_max": 0, "fd": 0, "t0": 0.0}
        ff_err_tail = deque(maxlen=30)     # 留最近若干行，ffplay 挂掉时用来报错
        _pat_frame = _re.compile(rb"n:\s*(\d+)\s+pts:")          # showinfo 解码帧序号
        _pat_stat = _re.compile(rb"fd=\s*(\d+).*?vq=\s*(\d+)KB")  # ffplay 状态行

        def read_ff_stderr():
            try:
                for line in iter(proc.stderr.readline, b""):
                    ff_err_tail.append(line.decode("utf-8", "replace").rstrip())
                    if args.show_ffplay_log:
                        sys.stderr.write(line.decode("utf-8", "replace"))
                    m = _pat_frame.search(line)
                    if m:
                        n = int(m.group(1)) + 1
                        if ff_stats["frame"] == 0:
                            ff_stats["t0"] = time.time()   # 从首帧开始计时，避开起播耗时
                        if n > ff_stats["frame"]:
                            ff_stats["frame"] = n
                    m = _pat_stat.search(line)
                    if m:
                        ff_stats["fd"] = int(m.group(1))
                        v = int(m.group(2))
                        ff_stats["vq"] = v              # 当前值，用于看趋势
                        if v > ff_stats["vq_max"]:
                            ff_stats["vq_max"] = v
            except Exception:
                pass

        ff_reader = threading.Thread(target=read_ff_stderr, daemon=True)
        ff_reader.start()

        # 启动自检：ffplay 若因选项/解复用问题秒退，stdin 写入会一直成功但画面永远不来。
        # 这里先等 1.2s，进程已退出就把它最后几行 stderr 打出来，避免又盲查一轮。
        time.sleep(1.2)
        if proc.poll() is not None:
            sys.stderr.write(
                "\n[ffplay] 启动即退出（退出码 %s），播放无法进行。原始报错：\n" % proc.returncode
            )
            for ln in list(ff_err_tail)[-12:]:
                sys.stderr.write("  | " + ln + "\n")
            sys.stderr.write("[ffplay] 常见原因：选项不被 ffplay 支持（如 -r）/ 管道编码格式不匹配\n")
            _cl(sink)
            sys.stderr.write("[stopped]\n")
            return
    else:
        f = open(args.out, "wb")
        sink = f
        proc = None

    stop = threading.Event()
    frames = {"n": 0}        # 成功写出并交给 ffplay 的帧
    rs_frames = {"n": 0}     # 其中靠 RS FEC 恢复出来的帧
    timeouts = {"n": 0}      # 超时未凑齐、被清理掉的帧
    bytes_acc = {"n": 0}
    frames_total = {"n": 0}   # 累计交给 ffplay 的帧（退出时对照解码器实际解了多少）
    bytes_total = {"n": 0}    # 累计字节，用于算平均码率
    run_t0 = time.time()      # 整轮开始时间（t0 每秒被重置，算总时长必须用它）
    t0 = time.time()

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
    last_dec = {"n": 0}        # 上一秒时 ffplay 已解码帧数，用于算 dec= 每秒增量
    max_frame_seen = -1     # 迄今见到的最大 frame_index，用于检测推流重启
    gap_cnt = {"n": 0, "pkts": 0}   # 丢包累计，按秒汇总，避免每个 gap 刷一行

    # 实测帧率：用 RTP timestamp（90kHz）的帧间隔中位数反推手机端真实采集帧率。
    # 用来验证手机是否真的按 --fps 档位在送（VFR/降帧时对不上）。
    ts_hist = deque(maxlen=40)
    last_ts_seen = None
    last_fi_for_ts = None

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
                frames_total["n"] += 1
                bytes_acc["n"] += len(frame)
                bytes_total["n"] += len(frame)
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

            # 帧切换时记一次 RTP ts，用帧间隔中位数反推真实采集帧率
            if frame_index != last_fi_for_ts:
                if last_ts_seen is not None:
                    d = (ts - last_ts_seen) & 0xFFFFFFFF
                    if 0 < d < 90000:        # 合理帧间隔 0~1s，过滤回绕/异常
                        ts_hist.append(d)
                last_ts_seen = ts
                last_fi_for_ts = frame_index

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
                # vq = ffplay 内部视频**包**队列当前积压（KB），实时值能看趋势：
                # 持续上涨 = 解码/播放跟不上送入 = 延迟开始漂；稳定低位 = 节奏对了。
                vq_txt = f" vq={ff_stats['vq']}KB" if proc is not None else ""
                # dec = 解码器最近一秒实际解出的帧数（showinfo 累计数的每秒差值）。
                # dec 持续 < out= 就是解码端在积压，这是"延迟会不会漂"最直接的一个数。
                dec_txt = ""
                if proc is not None:
                    dec_txt = f" dec={ff_stats['frame'] - last_dec['n']}"
                    last_dec["n"] = ff_stats["frame"]
                # real= 手机端真实采集帧率（RTP ts 中位数反推），用来核对 --fps 档位
                real_txt = ""
                real_fps = 0.0
                if len(ts_hist) >= 10:
                    med = sorted(ts_hist)[len(ts_hist) // 2]
                    ts_bug = False
                    # 兼容旧 APK：发送端 rtpTimestamp 误写成 ptsUs×90/1e6（差 1000 倍），
                    # 30fps 的帧间隔只有 3 tick 而不是 3000 → 反推出 30000fps 这种离谱值。
                    # 帧间隔 <450 tick（即 >200fps，物理上不可能）就按 ×1000 修正并标注。
                    if 0 < med < 450:
                        med *= 1000
                        ts_bug = True
                    real_fps = 90000.0 / med if med > 0 else 0.0
                    real_txt = f" real={real_fps:.1f}" + ("(ts修正)" if ts_bug else "")
                line = (
                    f"\r[recv] {frames['n']/dt:5.1f} fps | {kbps:5.0f} kbps | "
                    f"out={frames['n']:3d} rs={rs_frames['n']:3d} to={timeouts['n']:3d} "
                    f"buf={len(frames_buf):3d}{gap_txt}{bp_txt}{real_txt}{vq_txt}{dec_txt}"
                )
                # 必须补齐到终端宽度，否则 \r 覆盖刷新时旧行尾部残留成乱码
                # （实测：vq=302KB 变成 vq=302KBKB）
                sys.stderr.write(_pad_line(line))
                sys.stderr.flush()
                if real_fps > 0 and abs(real_fps - args.fps) > 1.5:
                    sys.stderr.write(
                        f"\n[recv] ⚠️ 实测采集帧率 {real_fps:.1f} 与 --fps {args.fps} 偏差 >1.5，"
                        f"播放按 {args.fps + args.fps_boost}fps 跑；若延迟仍漂，请把 --fps 改成 {round(real_fps)}"
                    )
                frames["n"] = 0
                rs_frames["n"] = 0
                timeouts["n"] = 0
                gap_cnt["n"] = 0
                gap_cnt["pkts"] = 0
                backpressure["n"] = 0
                bytes_acc["n"] = 0
                t0 = time.time()

                # ffplay 若已退出（解码失败/格式不匹配/窗口被关），没必要再空转：
                # 写线程会因 BrokenPipe 退出，但主循环仍在收包并把帧计数入队，
                # 日志会一直刷 out=/bp= 却没有画面。这里发现即停，退出报告会带上原因。
                if proc is not None and proc.poll() is not None:
                    sys.stderr.write(
                        f"\n[ffplay] 已退出（退出码 {proc.returncode}），停止接收。"
                        f"若画面刚出来就黑，看下面 ffplay 的原始报错："
                    )
                    break
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
                dur = (time.time() - ff_stats["t0"]) if ff_stats["t0"] else 0.0
                dec_fps = ff_stats["frame"] / dur if dur > 1.0 else 0.0
                sent = frames_total["n"]
                send_fps = sent / dur if dur > 1.0 else 0.0
                # 净积压 = 我们送进去的帧 − 解码器解出来的帧。
                # >0 且持续变大 = 帧堆在 ffplay 里没解出来 = 延迟在漂；≈0/为负 = 安全。
                # 注意 ffplay 的 KB 是 1024 进制，换算 ms 要 ×1024。
                net = sent - ff_stats["frame"]
                run_dur = time.time() - run_t0
                avg_kbps = bytes_total["n"] * 8 / 1000.0 / run_dur if run_dur > 0.5 else 0.0
                vq_ms = ff_stats["vq_max"] * 8192.0 / avg_kbps if avg_kbps > 0 else 0.0
                sys.stderr.write(
                    f"\n[ffplay] 解码器实际输出 {dec_fps:.2f} fps，累计解码 {ff_stats['frame']} 帧"
                    f"（解码持续 {dur:.0f}s；这是 showinfo 数出来的**解码器输出**，不是我们送了多少）"
                )
                sys.stderr.write(
                    f"\n[ffplay] 同段我们送出 {sent} 帧（{send_fps:.2f} fps）→ 净积压 {net} 帧"
                    f"；净积压 >0 且在变大 = 延迟在漂，≈0 或为负 = 安全"
                )
                sys.stderr.write(
                    f"\n[ffplay] ffplay 内部包队列 峰值 {ff_stats['vq_max']}KB"
                    f"（≈{vq_ms:.0f}ms 数据量；丢包让解码器等数据时会瞬时冲高，不代表播放延迟）"
                    f"/ 当前 {ff_stats['vq']}KB；ffplay 主动丢帧 fd={ff_stats['fd']}"
                )
                sys.stderr.write(
                    f"\n[ffplay] 播放帧率设定 {args.fps + args.fps_boost}"
                    f"（--fps {args.fps} + boost {args.fps_boost}）；"
                    + _drift_verdict(net, ff_stats["fd"], args.fps_boost)
                )
                # 一帧都没解出来 = ffplay 根本没跑起来（或编码格式不匹配），
                # 把它的原始报错打出来，否则只能看到 "0.00 fps" 无从下手。
                if ff_stats["frame"] == 0:
                    sys.stderr.write("\n[ffplay] 未解码任何帧，原始报错：\n")
                    for ln in list(ff_err_tail)[-12:]:
                        sys.stderr.write("  | " + ln + "\n")
            elif f is not None:
                f.close()
        except Exception:
            pass
        sys.stderr.write("\n[stopped]\n")


if __name__ == "__main__":
    main()
