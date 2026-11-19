"""netstack.link — 上位机侧的非图传流端点（UDP 48000 + TCP 49000）。

上位机是**监听方**：UDP 绑 48000，TCP 听 49000；手机只出站。
一条流里混着雷达、主控遥测、视觉 JSON、设置快照、保活帧，靠 60B 槽的帧头分流。

本模块做四件事：
1. 解包（16B 包头 + CRC16），按 (seq, blockId, shardStart) 去重——手机的控制帧
   会重放 0~2 次，副本字节完全相同；
2. 5+1 的 FEC 块重组：任意丢 1 槽可恢复，丢多了只发剩下的（雷达丢帧不影响画面）；
3. **回显 KEEP_ALIVE**：不回显，手机就永远判不出 UDP 好坏，也就不会切 TCP；
4. 长帧 JSON 分片重组，交给上层。

下行（控制手机 / 写 UART）用 `send_slots()` / `send_uart()` / `send_local_op()`。
"""
from __future__ import annotations

import json
import socket
import struct
import threading
import time

from . import fec
from .proto import (
    FEC_K, F_LONG, F_URGENT, H_KA, H_LIDAR, H_MCU_UP, JSON_HELLO, MAX_PAYLOAD,
    Packet, SLOT, TCP_PORT, UDP_PORT, is_keepalive, uart_down,
)

GRACE_S = 0.12          # 缺槽的块最多等这么久（校验包紧跟数据包，正常 < 5ms）
BLOCK_WINDOW = 32       # 最多同时存这么多块，防止乱序/丢包把内存拖爆
SEEN_CAP = 512


class _Block:
    __slots__ = ("shards", "parity", "first_at", "done")

    def __init__(self):
        self.shards: dict[int, bytes] = {}
        self.parity: bytes | None = None
        self.first_at = time.monotonic()
        self.done = False


class NetLink:
    """一条非图传流。回调都在收包线程里触发，上层自己加锁。"""

    def __init__(self, cfg: dict, on_slot=None, on_json=None, on_stats=None, log=print):
        self.cfg = cfg or {}
        self.udp_port = int(self.cfg.get("net_udp", UDP_PORT))
        self.tcp_port = int(self.cfg.get("net_tcp", TCP_PORT))
        self.on_slot = on_slot            # (header:int, slot:bytes)
        self.on_json = on_json            # (dict)
        self.on_stats = on_stats
        self.log = log

        self.running = False
        self._udp: socket.socket | None = None
        self._tcp: socket.socket | None = None
        self._conns: list[socket.socket] = []
        self._threads: list[threading.Thread] = []
        self._lock = threading.Lock()

        self._peer_udp: tuple[str, int] | None = None
        self._peer_conn: socket.socket | None = None
        self._greeted: set = set()

        self._blocks: dict[int, _Block] = {}
        self._frags: dict[int, list[bytes | None]] = {}
        self._seen: dict[int, float] = {}
        self._seen_order: list[int] = []

        self._seq = 0
        self._last_rx = 0.0
        self._hello_at = 0.0
        self.settings: dict | None = None

        self.stats = {
            "rx_packets": 0, "rx_dup": 0, "rx_bad": 0, "slots": 0,
            "fec_recovered": 0, "fec_failed": 0, "ka_echoed": 0,
            "lidar": 0, "master": 0, "vision": 0, "settings_updates": 0,
            "err": "", "udp_port": self.udp_port, "tcp_port": self.tcp_port,
        }

    # ───────────────────────── 启停 ─────────────────────────

    def start(self) -> None:
        if self.running:
            return
        self.running = True
        self._spawn(self._udp_loop, "net-udp")
        self._spawn(self._tcp_loop, "net-tcp")
        self._spawn(self._tick_loop, "net-tick")
        self.log(f"[net] 监听 udp {self.udp_port} / tcp {self.tcp_port}")

    def stop(self) -> None:
        self.running = False
        for s in [self._udp, self._tcp, *self._conns]:
            try:
                if s:
                    s.close()
            except OSError:
                pass
        self._udp = self._tcp = None
        self._conns.clear()

    def _spawn(self, fn, name: str) -> None:
        t = threading.Thread(target=fn, name=name, daemon=True)
        t.start()
        self._threads.append(t)

    # ───────────────────────── 收 ─────────────────────────

    def _udp_loop(self) -> None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        except OSError:
            pass
        s.settimeout(0.3)
        try:
            s.bind(("0.0.0.0", self.udp_port))
        except OSError as e:
            self.stats["err"] = f"UDP {self.udp_port} 绑定失败: {e}"
            self.log(f"[net] {self.stats['err']}")
            return
        self._udp = s
        while self.running:
            try:
                data, addr = s.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break
            with self._lock:
                self._peer_udp = addr
            self._handle(data, "udp", addr)
        try:
            s.close()
        except OSError:
            pass

    def _tcp_loop(self) -> None:
        srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            srv.bind(("0.0.0.0", self.tcp_port))
            srv.listen(2)
            srv.settimeout(0.5)
        except OSError as e:
            self.stats["err"] = f"TCP {self.tcp_port} 监听失败: {e}"
            self.log(f"[net] {self.stats['err']}")
            return
        self._tcp = srv
        while self.running:
            try:
                conn, addr = srv.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            with self._lock:
                self._conns.append(conn)
                self._peer_conn = conn
            self.log(f"[net] TCP 客户端 {addr[0]}:{addr[1]} 已连接")
            self._send_hello("tcp", conn)
            self._spawn(lambda c=conn: self._tcp_conn_loop(c), f"net-tcp-{addr[1]}")
        try:
            srv.close()
        except OSError:
            pass

    def _tcp_conn_loop(self, conn: socket.socket) -> None:
        try:
            while self.running:
                hdr = self._recv_exact(conn, 4)
                if hdr is None:
                    break
                n = struct.unpack(">I", hdr)[0]
                if n <= 0 or n > 65536:
                    break
                body = self._recv_exact(conn, n)
                if body is None:
                    break
                self._handle(body, "tcp", conn)
        except OSError:
            pass
        finally:
            try:
                conn.close()
            except OSError:
                pass
            with self._lock:
                if conn in self._conns:
                    self._conns.remove(conn)
                if self._peer_conn is conn:
                    self._peer_conn = None
            self.log("[net] TCP 客户端断开")

    @staticmethod
    def _recv_exact(conn: socket.socket, n: int) -> bytes | None:
        buf = bytearray()
        while len(buf) < n:
            chunk = conn.recv(n - len(buf))
            if not chunk:
                return None
            buf += chunk
        return bytes(buf)

    # ───────────────────────── 解包 ─────────────────────────

    def _handle(self, data: bytes, transport: str, peer) -> None:
        p = Packet.decode(data)
        if p is None:
            self.stats["rx_bad"] += 1
            return
        if not self._mark_seen(p.dedupe_key()):
            self.stats["rx_dup"] += 1
            return

        self.stats["rx_packets"] += 1
        self._last_rx = time.monotonic()

        peer_key = peer if transport == "udp" else id(peer)
        if peer_key not in self._greeted:
            self._greeted.add(peer_key)
            self._send_hello(transport, peer)

        if p.is_long:
            self._on_long(p)
            return
        if p.is_parity:
            self._on_parity(p)
            return
        self._on_data(p, transport, peer)

    def _mark_seen(self, key: int) -> bool:
        with self._lock:
            if key in self._seen:
                return False
            self._seen[key] = time.monotonic()
            self._seen_order.append(key)
            while len(self._seen_order) > SEEN_CAP:
                self._seen.pop(self._seen_order.pop(0), None)
            return True

    def _on_data(self, p: Packet, transport: str, peer) -> None:
        base = p.block_id * FEC_K + p.shard_start
        evict = None
        for i, slot in enumerate(p.slots()):
            if is_keepalive(slot):
                self._echo_ka(slot, transport, peer)   # 到包即回，不掺块重组
            bid, shard = divmod(base + i, FEC_K)
            with self._lock:
                blk = self._blocks.get(bid)
                if blk is None:
                    if len(self._blocks) >= BLOCK_WINDOW:
                        evict = min(self._blocks)      # 锁外再 flush，Lock 不可重入
                    blk = _Block()
                    self._blocks[bid] = blk
                blk.shards.setdefault(shard, slot)
            if evict is not None:
                self._flush(evict, grace_expired=True)
                evict = None
            self._try_block(bid)

    def _on_parity(self, p: Packet) -> None:
        with self._lock:
            blk = self._blocks.get(p.block_id)
            if blk is None:
                blk = _Block()
                self._blocks[p.block_id] = blk
            blk.parity = p.payload[:SLOT]
        self._try_block(p.block_id)

    def _try_block(self, bid: int) -> None:
        with self._lock:
            blk = self._blocks.get(bid)
            if blk is None or blk.done:
                return
            full = len(blk.shards) >= FEC_K
            recoverable = len(blk.shards) == FEC_K - 1 and blk.parity is not None
        if full or recoverable:
            self._flush(bid)

    def _flush(self, bid: int, grace_expired: bool = False) -> None:
        with self._lock:
            blk = self._blocks.get(bid)
            if blk is None or blk.done:
                return
            if not grace_expired and len(blk.shards) == FEC_K - 1 and blk.parity is None:
                return                      # 刚缺一个，等校验包
            blk.done = True
            data = [blk.shards.get(i) for i in range(FEC_K)]
            parity = blk.parity
            self._blocks.pop(bid, None)

        if any(s is None for s in data):
            rec = fec.recover(data, parity)
            if rec is not None:
                idx, restored = rec
                data[idx] = restored
                self.stats["fec_recovered"] += 1
            else:
                self.stats["fec_failed"] += 1

        for slot in data:
            if slot is None:
                continue
            self.stats["slots"] += 1
            h = (slot[0] << 8) | slot[1]
            if h == H_KA:
                continue                    # 已回显过，不再往上层抛
            if h == 0x55AA:
                self.stats["lidar"] += 1
            elif h == 0xAA55:
                self.stats["master"] += 1

            if self.on_slot:
                try:
                    self.on_slot(h, slot)
                except Exception as e:      # 上层解析异常不能拖死收包线程
                    self.stats["err"] = f"on_slot: {e}"

    def _on_long(self, p: Packet) -> None:
        with self._lock:
            arr = self._frags.get(p.block_id)
            if arr is None or len(arr) != p.count:
                arr = [None] * p.count
                self._frags[p.block_id] = arr
            if 0 <= p.shard_start < len(arr):
                arr[p.shard_start] = p.payload
            if any(x is None for x in arr):
                return
            self._frags.pop(p.block_id, None)
            joined = b"".join(x for x in arr if x)
        try:
            msg = json.loads(joined.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.stats["rx_bad"] += 1
            return
        t = msg.get("t")
        if t == "settings":
            self.settings = msg.get("s") or {}
            self.stats["settings_updates"] += 1
        if t == "vision":
            self.stats["vision"] += 1
        if self.on_json:
            try:
                self.on_json(msg)
            except Exception as e:
                self.stats["err"] = f"on_json: {e}"

    def _tick_loop(self) -> None:
        while self.running:
            time.sleep(0.05)
            now = time.monotonic()
            with self._lock:
                stale = [b for b, blk in self._blocks.items()
                         if not blk.done and now - blk.first_at > GRACE_S]
            for bid in stale:
                self._flush(bid, grace_expired=True)
            # 迟迟收不到设置快照就把 hello 再喊一遍（手机上线才回快照）
            if now - self._hello_at > 5.0 and self.settings is None:
                self._send_hello("udp", None)
            if self.on_stats:
                try:
                    self.on_stats(self.snapshot())
                except Exception:
                    pass

    # ───────────────────────── 发 ─────────────────────────

    def _next_seq(self) -> int:
        with self._lock:
            s = self._seq
            self._seq = (self._seq + 1) & 0xFFFF
            return s

    def _echo_ka(self, slot: bytes, transport: str, peer) -> None:
        pkt = Packet(F_URGENT, self._next_seq(), 0, 0, 1, 0, slot).encode()
        self._send_raw(pkt, transport, peer)
        self.stats["ka_echoed"] += 1

    def _send_hello(self, transport: str, peer) -> None:
        self._hello_at = time.monotonic()
        self.send_json({"t": JSON_HELLO, "from": "spaa", "ts": int(time.time() * 1000)},
                       transport=transport, peer=peer)

    def send_json(self, obj: dict, transport: str = "auto", peer=None) -> bool:
        data = json.dumps(obj, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if not data:
            return False
        gid = self._next_seq()
        ok = False
        for i in range(0, len(data), MAX_PAYLOAD):
            chunk = data[i:i + MAX_PAYLOAD]
            frag = i // MAX_PAYLOAD
            total = (len(data) + MAX_PAYLOAD - 1) // MAX_PAYLOAD
            pkt = Packet(F_LONG, self._next_seq(), gid, frag, total, 0, chunk).encode()
            ok = self._send_raw(pkt, transport, peer) or ok
        return ok

    def send_slots(self, slots: list[bytes], transport: str = "auto") -> bool:
        if not slots:
            return False
        slots = [s for s in slots if len(s) == SLOT]
        ok = False
        for i in range(0, len(slots), 20):
            chunk = slots[i:i + 20]
            base = self._next_seq()
            pkt = Packet(F_URGENT, base, 0, 0, len(chunk), 0, b"".join(chunk)).encode()
            ok = self._send_raw(pkt, transport, None) or ok
        return ok

    def send_uart(self, data: bytes, transport: str = "auto") -> bool:
        """任意字节写进手机侧 UART（FA 5B 槽，自动分片）。"""
        return self.send_slots(uart_down(data), transport)

    def send_local_op(self, op: int, arg: bytes = b"", transport: str = "auto") -> bool:
        from .proto import local_op
        return self.send_slots([local_op(op, arg)], transport)

    def _send_raw(self, pkt: bytes, transport: str, peer) -> bool:
        if transport == "auto":
            with self._lock:
                conn = self._peer_conn
                addr = self._peer_udp
            transport = "tcp" if conn else "udp"
            peer = conn if conn else addr
        try:
            if transport == "tcp":
                conn = peer if isinstance(peer, socket.socket) else self._peer_conn
                if conn is None:
                    return False
                with self._lock:
                    conn.sendall(struct.pack(">I", len(pkt)) + pkt)
                return True
            addr = peer if isinstance(peer, tuple) else self._peer_udp
            if addr is None or self._udp is None:
                return False
            self._udp.sendto(pkt, addr)
            return True
        except OSError as e:
            self.stats["err"] = f"发送失败: {e}"
            return False

    # ───────────────────────── 快照 ─────────────────────────

    def snapshot(self) -> dict:
        s = dict(self.stats)
        s["rx_age"] = (time.monotonic() - self._last_rx) if self._last_rx else 1e9
        s["udp_peer"] = self._peer_udp[0] if self._peer_udp else ""
        s["tcp_clients"] = len(self._conns)
        s["settings"] = self.settings
        return s
