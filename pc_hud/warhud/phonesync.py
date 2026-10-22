"""warhud.phonesync — 视觉结果接收 + 设置双向同步（UDP）。

两条链路（见 protocol.py 头注释）：
- 收：手机每帧视觉结果（vision）+ 每 500ms 设置快照（settings）
- 发：电脑设置变更（set_req）+ 上线宣告（hello）

线程模型：一个 daemon 收包线程解析 JSON，通过 Qt 信号抛给 UI 线程
（PyQt 的跨线程更新必须走信号，直接 setText 会崩）。
"""

from __future__ import annotations

import json
import socket
import threading
import time

from . import protocol


class PhoneSync:
    """视觉结果 + 设置同步客户端。"""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.sock: socket.socket | None = None
        self.thread: threading.Thread | None = None
        self.running = False

        # 最新视觉结果（UI 线程读取，写入方为收包线程——字段级读取可接受）
        self.vision: dict | None = None
        self.vision_age_ms = 1e9          # 距上一帧 vision 的毫秒数
        self.settings: dict = {}          # 手机侧当前设置快照
        self.settings_age_ms = 1e9
        self.phone_ip = ""                # 收到广播后自动记住
        self.vision_frames = 0

        # 回调（由 UI 层赋值，Qt 信号或直接调用）
        self.on_vision = None             # (dict) -> None
        self.on_settings = None           # (dict) -> None

    # ─────────── 生命周期 ───────────

    def start(self) -> bool:
        if self.running:
            return True
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            self.sock.bind(("0.0.0.0", self.cfg["vision_port"]))
            self.sock.settimeout(0.5)
        except OSError as e:
            self.sock = None
            print(f"[phonesync] 绑定 {self.cfg['vision_port']} 失败：{e}")
            return False

        self.running = True
        self.thread = threading.Thread(target=self._run, name="phonesync", daemon=True)
        self.thread.start()
        self.send_hello()
        return True

    def stop(self) -> None:
        self.running = False
        if self.sock:
            try:
                self.sock.close()
            except OSError:
                pass
        self.sock = None

    # ─────────── 收包线程 ───────────

    def _run(self) -> None:
        last_tick = time.monotonic()
        while self.running:
            try:
                data, addr = self.sock.recvfrom(65535)
            except socket.timeout:
                data, addr = None, None
            except OSError:
                break

            now = time.monotonic()
            self.vision_age_ms += (now - last_tick) * 1000
            self.settings_age_ms += (now - last_tick) * 1000
            last_tick = now

            if data:
                self.phone_ip = addr[0]      # 记住手机 IP（set_req 单播目标）
                msg = protocol.decode_udp(data)
                if msg is None:
                    continue
                t = msg.get("t")
                if t == "vision":
                    self.vision = msg
                    self.vision_age_ms = 0.0
                    self.vision_frames += 1
                    if self.on_vision:
                        self.on_vision(msg)
                elif t == "settings":
                    self.settings = msg.get("s", {})
                    self.settings_age_ms = 0.0
                    if self.on_settings:
                        self.on_settings(self.settings)

    # ─────────── 发送 ───────────

    def _send_cmd(self, payload: bytes) -> bool:
        if not self.sock:
            return False
        target = self.phone_ip or self.cfg.get("phone_ip", "")
        if not target:
            return False
        try:
            self.sock.sendto(payload, (target, protocol.UDP_CMD_PORT))
            return True
        except OSError:
            return False

    def send_hello(self) -> bool:
        return self._send_cmd(protocol.encode_hello())

    def set_setting(self, key: str, value) -> bool:
        """请求手机修改一个设置。乐观更新本地快照，等手机广播确认。"""
        self.settings[key] = value
        return self._send_cmd(protocol.encode_set_req(key, value))

    # ─────────── 状态 ───────────

    @property
    def vision_alive(self) -> bool:
        return self.vision_age_ms < 1000

    @property
    def settings_alive(self) -> bool:
        return self.settings_age_ms < 2000
