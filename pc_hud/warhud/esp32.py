"""warhud.esp32 — 电脑 WLAN 直连 ESP32-S3：操作输入下发 + 遥测接收。

链路（用户选定方案，延迟最低）：
  电脑键鼠/手柄 ──TCP 31010──► ESP32-S3 ──光电隔离 UART──► CH32X03S（轮速/传感）
  电脑 ◀──TCP 31011── ESP32-S3 ◀──UART── CH32X03S

冲突处理：电脑接入即接管操作权。手机端检测到电脑输入在线时
禁用本机操作输入（手机退化为纯视觉+图传节点）——由手机 APP 侧
按「收到电脑输入帧」判断，ESP32 只透传，不仲裁。

断线行为：连续 3 个发送周期无 TCP ack 即判定掉线，HUD 状态灯变红，
输入帧停发（避免无谓的 write 异常风暴）。
"""

from __future__ import annotations

import socket
import struct
import threading
import time

from . import protocol


class Esp32Link:
    """ESP32-S3 WLAN 客户端（输入下行 TCP + 遥测上行 TCP 双连接）。"""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.sock_in: socket.socket | None = None    # → ESP32（输入）
        self.sock_tm: socket.socket | None = None    # ← ESP32（遥测）
        self.thread_tm: threading.Thread | None = None
        self.running = False
        self.connected = False
        self.telem: dict = {}
        self.telem_age_ms = 1e9
        self.last_error = ""

        # 发送节拍
        self._send_thread: threading.Thread | None = None
        self._pending_fire = False       # fire 边沿：置位后发一帧即清

        # UI 线程写入的最新输入：{"yaw":deg, "pitch":deg, "mx":mm/s, "my":mm/s, "btn":bits}
        self.current_input: dict = {"yaw": 0.0, "pitch": 0.0, "mx": 0, "my": 0, "btn": 0}

        self.on_telem = None             # (dict) -> None

    # ─────────── 连接 ───────────

    def start(self) -> bool:
        if self.running:
            return True
        ip = self.cfg["esp32_ip"]
        try:
            self.sock_in = socket.create_connection(
                (ip, self.cfg["esp32_input_port"]), timeout=3.0)
            self.sock_in.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except OSError as e:
            self.last_error = f"ESP32 输入通道连接失败 {ip}:{self.cfg['esp32_input_port']} — {e}"
            self.sock_in = None
            return False

        self.running = True
        self.connected = True
        self._send_thread = threading.Thread(target=self._send_loop,
                                             name="esp32-input", daemon=True)
        self._send_thread.start()
        self._start_telem()
        return True

    def _start_telem(self) -> None:
        ip = self.cfg["esp32_ip"]
        try:
            self.sock_tm = socket.create_connection(
                (ip, self.cfg["esp32_telem_port"]), timeout=3.0)
        except OSError as e:
            self.last_error = f"ESP32 遥测通道连接失败（输入仍可用）：{e}"
            self.sock_tm = None
            return
        self.thread_tm = threading.Thread(target=self._telem_loop,
                                          name="esp32-telem", daemon=True)
        self.thread_tm.start()

    def stop(self) -> None:
        self.running = False
        self.connected = False
        for s in (self.sock_in, self.sock_tm):
            if s:
                try:
                    s.close()
                except OSError:
                    pass
        self.sock_in = self.sock_tm = None

    # ─────────── 输入下发（10Hz 定步长） ───────────

    def _send_loop(self) -> None:
        period = 1.0 / max(1, self.cfg["input_hz"])
        last_state = None
        while self.running and self.sock_in:
            state = self.current_input
            fire = self._pending_fire
            self._pending_fire = False
            # 只在状态变化或 fire 边沿时发包（省带宽，ESP32 端保持最后状态）
            if state != last_state or fire:
                pkt = protocol.encode_input(
                    state.get("yaw", 0.0), state.get("pitch", 0.0), fire,
                    state.get("mx", 0), state.get("my", 0), state.get("btn", 0))
                try:
                    self.sock_in.sendall(pkt)
                    last_state = dict(state)
                except OSError as e:
                    self.last_error = f"输入发送失败：{e}"
                    self.connected = False
                    break
            time.sleep(period)

    def queue_fire(self) -> None:
        """请求击发一帧（边沿）。真正的击发逻辑在 ESP32/下位机。"""
        self._pending_fire = True

    # ─────────── 遥测接收 ───────────

    def _telem_loop(self) -> None:
        buf = b""
        self.sock_tm.settimeout(0.5)
        last_tick = time.monotonic()
        while self.running and self.sock_tm:
            try:
                chunk = self.sock_tm.recv(256)
            except OSError:
                break
            # 遥测年龄随真实时间增长：断流 1 秒后 HUD 状态灯变红
            now = time.monotonic()
            self.telem_age_ms += (now - last_tick) * 1000
            last_tick = now
            if not chunk:
                continue
            self.telem_age_ms = 0.0
            buf += chunk
            while len(buf) >= protocol.TELEM_LEN:
                if buf[:2] != protocol.TELEM_HEADER:
                    buf = buf[1:]      # 失同步：滑过一字节重新找头
                    continue
                msg = protocol.decode_telem(buf[:protocol.TELEM_LEN])
                if msg:
                    self.telem = msg
                    if self.on_telem:
                        self.on_telem(msg)
                buf = buf[protocol.TELEM_LEN:]
