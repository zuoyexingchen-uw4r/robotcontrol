"""spaa.rx_video — GameStream 图传接收（UDP + RS-FEC）。

与手机端 `video/GameStreamSender.kt` 的 wire format 逐字节对齐：

    UDP 包 = [RTP 12B][reserved 4B][NV_VIDEO_PACKET 16B][payload <= 1008B]

解析要点（都是踩过的坑，别改）：
- NV 头从偏移 **16** 读；偏移 12 是 reserved，误读会把 streamPacketIndex
  当成 frameIndex，每包都变 → 永远凑不齐分片。
- fecInfo 的 pktIdx 是**块内**分片序号（0..k-1 数据，k..k+m-1 校验）。
- 短帧头在「重建出的数据流」头部，不是 UDP 包头部；SOF 包丢了也能靠 FEC 恢复后读。
- 已输出过的帧，迟到包必须丢弃，否则会重组出幽灵帧重复输出。

本模块只负责「收包 → 组帧 → 得到 Annex-B 帧」，解码交给 decoder.py。
"""
from __future__ import annotations

import queue
import socket
import struct
import sys
import threading
import time

from .fec import rs_erasures_decode

RTP_HDR = struct.Struct(">BBHII")            # 12B
NV_HDR = struct.Struct(">IIBBBBi")           # 16B
PAYLOAD_BLOCK = 1008

FLAG_PIC_DATA = 0x01
FLAG_EOF = 0x02
FLAG_SOF = 0x04


class VideoReceiver:
    """收包线程 + 关键帧请求线程；组好的帧进 out_q。"""

    def __init__(self, cfg: dict, out_q: "queue.Queue[tuple[bytes, float]]"):
        self.cfg = cfg
        self.out_q = out_q
        self.running = False
        self._sock: socket.socket | None = None
        self._ctrl_srv: socket.socket | None = None
        self._need_idr = threading.Event()
        self._thread: threading.Thread | None = None
        self._ctrl_thread: threading.Thread | None = None

        # ── 统计（每秒刷新，界面直接读）──
        self.stat = {
            "rx_fps": 0.0, "kbps": 0.0, "out": 0, "rs": 0, "timeout": 0,
            "gap": 0, "gap_pkts": 0, "buf": 0, "applat_ms": 0.0,
            "idr_periodic": 0, "idr_demand": 0, "real_fps": 0.0,
            "last_frame_t": 0.0, "connected": False, "ctrl_connected": False,
        }
        self._lock = threading.Lock()

        # 组帧状态
        self._frames_buf: dict[tuple[int, int], dict] = {}
        self._emitted: dict[int, bool] = {}
        self._max_frame_seen = -1
        self._last_seq = -1
        self._last_cleanup = time.time()

        # 按秒累计
        self._acc_frames = 0
        self._acc_bytes = 0
        self._acc_applat = 0
        self._acc_applat_n = 0
        self._acc_gap = 0
        self._acc_gap_pkts = 0
        self._acc_rs = 0
        self._acc_timeout = 0
        self._t0 = time.time()

        self._ts_hist: list[int] = []
        self._last_ts = None
        self._last_fi_for_ts = None
        self._first_pkt = False

        # 录制（存原始 Annex-B，比转 RGB 小一个数量级）
        self._rec_fp = None
        self._rec_path = ""
        self._rec_t0 = 0.0
        self.rec_bytes = 0

    # ─────────── 生命周期 ───────────

    def start(self) -> None:
        if self.running:
            return
        self.running = True
        self._thread = threading.Thread(target=self._run, name="video-rx", daemon=True)
        self._thread.start()
        self._ctrl_thread = threading.Thread(target=self._ctrl_loop, name="video-ctrl", daemon=True)
        self._ctrl_thread.start()

    def stop(self) -> None:
        self.running = False

    def request_idr(self) -> None:
        """置位关键帧请求（限流在 ctrl 线程里做）。"""
        self._need_idr.set()

    # ─────────── UDP 收包 ───────────

    def _run(self) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        # 一帧就是 ~100KB 的突发，Windows 默认 64KB 内核缓冲装不下 → 直接丢包。
        # 大缓冲不增加延迟：收到就消费，只是不让内核丢。
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
        except OSError:
            pass
        s.settimeout(0.5)
        try:
            s.bind(("0.0.0.0", self.cfg["video_port"]))
        except OSError as e:
            print(f"[video] UDP {self.cfg['video_port']} 绑定失败：{e}", file=sys.stderr)
            return
        self._sock = s
        print(f"[video] UDP {self.cfg['video_port']} 监听中", file=sys.stderr)

        try:
            while self.running:
                try:
                    pkt, _addr = s.recvfrom(65535)
                except socket.timeout:
                    self._flush_stats()
                    continue
                except OSError as e:
                    print(f"[video] recv 中断：{e}", file=sys.stderr)
                    break
                self._on_packet(pkt)
                self._flush_stats()
        finally:
            try:
                s.close()
            except OSError:
                pass

    def _on_packet(self, pkt: bytes) -> None:
        if len(pkt) < 32:
            return
        if not self._first_pkt:
            self._first_pkt = True
            print(f"[video] 首包到达 {len(pkt)}B", file=sys.stderr)
        hdr, pt, seq, ts, _ssrc = RTP_HDR.unpack_from(pkt, 0)
        if (hdr & 0xC0) != 0x80:
            return

        (pkt_idx_in_frame, frame_index, _flags, _extra,
         _mff, multi_fec_blocks, fec_info) = NV_HDR.unpack_from(pkt, 16)
        payload = pkt[32:]

        if self._last_seq >= 0 and seq != (self._last_seq + 1) & 0xFFFF:
            self._acc_gap += 1
            self._acc_gap_pkts += (seq - self._last_seq - 1) & 0xFFFF
        self._last_seq = seq
        self.stat["connected"] = True

        # 真实采集帧率：用 RTP ts（90kHz）帧间隔中位数反推
        if frame_index != self._last_fi_for_ts:
            if self._last_ts is not None:
                d = (ts - self._last_ts) & 0xFFFFFFFF
                if 0 < d < 90000:
                    self._ts_hist.append(d)
                    if len(self._ts_hist) > 40:
                        self._ts_hist.pop(0)
            self._last_ts = ts
            self._last_fi_for_ts = frame_index

        pkt_idx = (fec_info >> 12) & 0x3FF
        k = (fec_info >> 22) & 0x3FF
        block_num = (multi_fec_blocks >> 4) & 0x3
        nblocks = ((multi_fec_blocks >> 6) & 0x3) + 1
        if k <= 0 or k > 255:
            return

        # 推流重启（frameIndex 回退）→ 全量重置，否则新流会被当成幽灵帧丢掉
        if self._max_frame_seen >= 0 and frame_index < self._max_frame_seen - 500:
            self._frames_buf.clear()
            self._emitted.clear()
            self._last_seq = -1
            self._max_frame_seen = frame_index
            self._need_idr.set()
        self._max_frame_seen = max(self._max_frame_seen, frame_index)

        if frame_index in self._emitted:
            return

        fb = self._frames_buf.setdefault(
            (frame_index, block_num),
            {"shards": {}, "k": k, "nblocks": nblocks, "t0": time.time()},
        )
        fb["k"] = k
        fb["nblocks"] = nblocks
        fb["shards"].setdefault(pkt_idx, payload)

        if "data" not in fb:
            data = self._block_complete(fb)
            if data is not None:
                fb["data"], used_rs = data
                if used_rs:
                    self._acc_rs += 1
                self._try_emit(frame_index)

    def _block_complete(self, fb: dict):
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

    def _try_emit(self, frame_index: int) -> None:
        blocks = [b for (fi, b) in self._frames_buf
                  if fi == frame_index and "data" in self._frames_buf[(fi, b)]]
        if not blocks:
            return
        nblocks = max(self._frames_buf[(frame_index, b)]["nblocks"] for b in blocks)
        if len(blocks) < nblocks:
            return
        stream = b"".join(self._frames_buf[(frame_index, b)]["data"]
                          for b in range(nblocks))
        if len(stream) < 8:
            return
        last_len = struct.unpack_from("<H", stream, 4)[0]
        total_k = sum(self._frames_buf[(frame_index, b)]["k"] for b in range(nblocks))
        end = (total_k - 1) * PAYLOAD_BLOCK + last_len
        frame = stream[8:end] if end > 8 else b""

        if frame:
            self._acc_applat += struct.unpack_from("<H", stream, 1)[0]
            self._acc_applat_n += 1
            self._acc_frames += 1
            self._acc_bytes += len(frame)
            self.stat["last_frame_t"] = time.time()
            self._push_frame(frame)
            self._tap_record(frame)

        for b in range(nblocks):
            self._frames_buf.pop((frame_index, b), None)
        self._emitted[frame_index] = True
        if len(self._emitted) > 512:
            for old in sorted(self._emitted)[:256]:
                self._emitted.pop(old, None)

        # 清理超时未凑齐的帧：缺的分片永远等不到，若它是 IDR 就要立刻补一个
        now = time.time()
        if now - self._last_cleanup >= 0.5:
            self._last_cleanup = now
            for key in list(self._frames_buf.keys()):
                if now - self._frames_buf[key].get("t0", now) > 1.0:
                    self._frames_buf.pop(key, None)
                    self._acc_timeout += 1
                    self._need_idr.set()
            if len(self._frames_buf) > 256:
                for key in list(self._frames_buf.keys())[:64]:
                    self._frames_buf.pop(key, None)
                    self._acc_timeout += 1

    def _push_frame(self, frame: bytes) -> None:
        """只留最新两帧：解码端慢的时候，旧帧到屏幕上也已经过期。"""
        q = self.out_q
        while q.full():
            try:
                q.get_nowait()
            except queue.Empty:
                break
        try:
            q.put_nowait((frame, time.time()))
        except queue.Full:
            pass

    # ─────────── 录制 ───────────

    def start_record(self, path: str) -> str:
        self.stop_record()
        self._rec_fp = open(path, "wb")
        self._rec_path = path
        self._rec_t0 = time.time()
        self.rec_bytes = 0
        return path

    def stop_record(self) -> str:
        fp, self._rec_fp = self._rec_fp, None
        if fp:
            try:
                fp.close()
            except OSError:
                pass
        return self._rec_path

    @property
    def recording(self) -> bool:
        return self._rec_fp is not None

    def record_seconds(self) -> float:
        return time.time() - self._rec_t0 if self._rec_t0 else 0.0

    def _tap_record(self, frame: bytes) -> None:
        fp = self._rec_fp
        if not fp:
            return
        try:
            fp.write(frame)
            self.rec_bytes += len(frame)
        except OSError:
            self.stop_record()

    # ─────────── 统计 ───────────

    def _flush_stats(self) -> None:
        now = time.time()
        dt = now - self._t0
        if dt < 1.0:
            return
        with self._lock:
            self.stat["rx_fps"] = self._acc_frames / dt
            self.stat["kbps"] = self._acc_bytes * 8 / 1000.0 / dt
            self.stat["out"] = self._acc_frames
            self.stat["rs"] = self._acc_rs
            self.stat["timeout"] = self._acc_timeout
            self.stat["gap"] = self._acc_gap
            self.stat["gap_pkts"] = self._acc_gap_pkts
            self.stat["buf"] = len(self._frames_buf)
            if self._acc_applat_n:
                self.stat["applat_ms"] = self._acc_applat / self._acc_applat_n / 10.0
            if len(self._ts_hist) >= 10:
                med = sorted(self._ts_hist)[len(self._ts_hist) // 2]
                if 0 < med < 450:        # 旧 APK 的 ts 单位 bug：×1000 修正
                    med *= 1000
                self.stat["real_fps"] = 90000.0 / med if med > 0 else 0.0
            if self._acc_gap_pkts >= 20:
                self._need_idr.set()
        self._acc_frames = self._acc_bytes = 0
        self._acc_rs = self._acc_timeout = 0
        self._acc_gap = self._acc_gap_pkts = 0
        self._acc_applat = self._acc_applat_n = 0
        self._t0 = now

    def snapshot(self) -> dict:
        with self._lock:
            s = dict(self.stat)
        # 2 秒没新帧 = 断流
        s["connected"] = bool(s["last_frame_t"]) and \
            (time.time() - s["last_frame_t"] < 2.0)
        s["idr_periodic"] = self.stat["idr_periodic"]
        s["idr_demand"] = self.stat["idr_demand"]
        return s

    # ─────────── 关键帧请求（TCP 47000，本机监听）───────────

    def _ctrl_loop(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("0.0.0.0", self.cfg["ctrl_port"]))
            srv.listen(1)
            srv.settimeout(1.0)
            self._ctrl_srv = srv
        except OSError as e:
            print(f"[ctrl] 监听 {self.cfg['ctrl_port']} 失败：{e}", file=sys.stderr)
            return

        try:
            while self.running:
                try:
                    cs, _addr = srv.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                cs.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.stat["ctrl_connected"] = True
                last_kf = 0.0
                try:
                    while self.running:
                        # 周期兜底 2s；被事件唤醒则立即发
                        self._need_idr.wait(timeout=2.0)
                        if not self.running:
                            break
                        demand = self._need_idr.is_set()
                        now = time.time()
                        if now - last_kf < 0.3:      # 限流：IDR 太密会挤掉 P 帧预算
                            time.sleep(max(0.0, 0.3 - (now - last_kf)))
                            continue
                        cs.sendall(struct.pack(">I", 0x0301))   # SS_RFI_REQUEST
                        self._need_idr.clear()
                        last_kf = time.time()
                        with self._lock:
                            key = "idr_demand" if demand else "idr_periodic"
                            self.stat[key] += 1
                except OSError:
                    pass
                finally:
                    self.stat["ctrl_connected"] = False
                    try:
                        cs.close()
                    except OSError:
                        pass
        finally:
            try:
                srv.close()
            except OSError:
                pass
