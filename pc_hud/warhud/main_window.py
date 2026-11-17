"""warhud.main_window — HUD 主窗口：视频帧 + 叠加层 + 输入捕获 + 设置面板。"""

from __future__ import annotations

import time

from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QImage, QKeyEvent, QMouseEvent, QPainter, QWheelEvent
from PyQt5.QtWidgets import (
    QCheckBox, QHBoxLayout, QLabel, QMainWindow, QPushButton, QScrollArea,
    QVBoxLayout, QWidget,
)

from . import config as cfgmod
from . import protocol
from .esp32 import Esp32Link
from .gamepad import Gamepad
from .overlay import HudOverlay
from .phonesync import PhoneSync
from .stream import StreamDecoder

SETTING_LABELS = {
    "deblur": "去模糊（维纳反卷积）",
    "smoothing": "图像平滑（双边滤波）",
    "deglare": "去辉光（抗强光）",
    "highland": "高地靶检测",
    "base": "基地靶检测",
    "tracking": "卡尔曼滤波追踪",
    "autofire": "自动开火",
    "navigation": "导航（占位）",
}


class _Signals(QObject):
    """收包线程 → UI 线程的信号桥。"""
    vision = pyqtSignal(dict)
    settings = pyqtSignal(dict)
    telem = pyqtSignal(dict)


class MainWindow(QMainWindow):

    def __init__(self, gamepad: Gamepad | None = None):
        super().__init__()
        self.cfg = cfgmod.load()
        self.setWindowTitle("狼牙视觉 · 操控 HUD v0.2")
        self.resize(1360, 800)
        self.setMinimumSize(960, 600)

        # ─── 三个链路 ───
        self.decoder = StreamDecoder(self.cfg)
        self.phone = PhoneSync(self.cfg)
        self.esp32 = Esp32Link(self.cfg)
        self.signals = _Signals()
        self.signals.vision.connect(self._on_vision)
        self.signals.settings.connect(self._on_settings)
        self.signals.telem.connect(self._on_telem)
        self.phone.on_vision = self.signals.vision.emit
        self.phone.on_settings = self.signals.settings.emit
        self.esp32.on_telem = self.signals.telem.emit

        self.hud = HudOverlay(self.cfg)
        self._last_frame_seq = -1
        self._fps_window = []          # (t, seq) 估视频解码帧率

        # 输入状态（手柄连续量积分 + 键鼠后备）
        self._input_yaw = 0.0
        self._input_pitch = 0.0
        self._mouse_captured = False
        self._last_mouse_x = 0
        self._last_mouse_y = 0
        self._elapsed_s = 0.0           # 比赛已进行秒数（START 可暂停）
        self._paused = False
        self._last_tick = time.monotonic()
        # 手柄（USB，pygame/SDL；未插自动降级键鼠）。测试可注入 MockSource。
        self.pad = gamepad if gamepad is not None else Gamepad(cfg=self.cfg)

        self._build_ui()
        self._build_settings_panel()

        # 主循环 60Hz：拉最新解码帧 + 重绘 + 状态老化
        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.timer.start(16)

        self._start_links()

    # ─────────── UI 骨架 ───────────

    def _build_ui(self) -> None:
        central = QWidget(self)
        self.setCentralWidget(central)
        lay = QVBoxLayout(central)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)

        # 顶部工具条
        bar = QWidget()
        bl = QHBoxLayout(bar)
        bl.setContentsMargins(8, 4, 8, 4)
        title = QLabel("狼牙视觉 HUD")
        title.setStyleSheet("color:#E8F5E9;font-weight:bold;")
        bl.addWidget(title)
        self.lbl_phone_ip = QLabel("手机：--")
        self.lbl_phone_ip.setStyleSheet("color:#9E9E9E;")
        bl.addWidget(self.lbl_phone_ip)
        self.lbl_pad = QLabel("手柄：检测中…")
        self.lbl_pad.setStyleSheet("color:#9E9E9E;")
        bl.addWidget(self.lbl_pad)
        bl.addStretch(1)
        btn_settings = QPushButton("视觉设置 (Tab)")
        btn_settings.clicked.connect(self._toggle_settings)
        bl.addWidget(btn_settings)
        self.btn_link = QPushButton("ESP32：连接")
        self.btn_link.clicked.connect(self._toggle_esp32)
        bl.addWidget(self.btn_link)
        bar.setStyleSheet("background:#1B1B1B;")
        lay.addWidget(bar)

        # 视频 + HUD 画布
        self.canvas = _Canvas(self)
        lay.addWidget(self.canvas, 1)

        self.setStyleSheet("""
            QMainWindow, QWidget { background:#101010; color:#E8F5E9;
                                   font-family:Consolas, monospace; }
            QPushButton { background:#2A2A2A; color:#E8F5E9; border:1px solid #444;
                          padding:4px 12px; border-radius:3px; }
            QPushButton:hover { background:#3A3A3A; }
        """)

    def _build_settings_panel(self) -> None:
        """右侧折叠设置面板：开关与手机 SharedPreferences 双向同步。"""
        self.settings_panel = QWidget()
        self.settings_panel.setFixedWidth(300)
        self.settings_panel.setStyleSheet("background:#161616;border-left:1px solid #333;")
        lay = QVBoxLayout(self.settings_panel)
        lay.setContentsMargins(12, 12, 12, 12)

        t = QLabel("视觉设置（与手机实时同步）")
        t.setStyleSheet("font-weight:bold;color:#E8F5E9;")
        lay.addWidget(t)
        st = QLabel("改动即时下发手机；手机侧改动也会刷新这里")
        st.setStyleSheet("color:#777;font-size:11px;")
        lay.addWidget(st)

        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        inner = QWidget()
        il = QVBoxLayout(inner)
        il.setSpacing(6)
        self._switches = {}
        for key in protocol.SETTING_KEYS:
            cb = QCheckBox(SETTING_LABELS[key])
            cb.setStyleSheet("color:#E8F5E9;")
            cb.stateChanged.connect(lambda _, k=key: self._on_switch(k))
            self._switches[key] = cb
            il.addWidget(cb)
        il.addStretch(1)
        scroll.setWidget(inner)
        lay.addWidget(scroll, 1)

        self.lbl_sync_state = QLabel("同步状态：等待手机…")
        self.lbl_sync_state.setStyleSheet("color:#9E9E9E;font-size:11px;")
        lay.addWidget(self.lbl_sync_state)
        self.settings_panel.setVisible(False)

        # 挂到主布局
        row = QHBoxLayout()
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(0)
        row.addWidget(self.centralWidget(), 1)
        row.addWidget(self.settings_panel)
        wrap = QWidget()
        wrap.setLayout(row)
        self.setCentralWidget(wrap)
        # 保留 canvas 引用（重建 centralWidget 后父子关系不变，无需重新挂 timer）

    # ─────────── 链路启停 ───────────

    def _start_links(self) -> None:
        if not self.decoder.start():
            self.hud.last_stream_error = self.decoder.last_error
        self.phone.start()
        if self.esp32.start():
            self.btn_link.setText("ESP32：已连接")
        else:
            self.btn_link.setText(f"ESP32：重试（{self.esp32.last_error[:24]}…）")
        self.pad.start()

    def _toggle_esp32(self) -> None:
        if self.esp32.connected:
            self.esp32.stop()
            self.btn_link.setText("ESP32：连接")
        else:
            if self.esp32.start():
                self.btn_link.setText("ESP32：已连接")
            else:
                self.btn_link.setText("ESP32：连接失败")

    # ─────────── 主循环 ───────────

    def _tick(self) -> None:
        now = time.monotonic()
        dt = min(0.1, max(0.0, now - self._last_tick))
        self._last_tick = now
        frame, seq = self.decoder.latest_frame()
        if seq != self._last_frame_seq and frame:
            self._last_frame_seq = seq
            img = QImage(frame, self.cfg["decode_w"], self.cfg["decode_h"],
                         QImage.Format_RGB888)
            self.canvas.set_frame(img)
            now = time.monotonic()
            self._fps_window.append((now, seq))
            self._fps_window = [x for x in self._fps_window if now - x[0] < 2.0]
            self.hud.video_alive = True
        else:
            # 帧序号 2 秒没动 = 视频流中断
            if not self._fps_window or time.monotonic() - self._fps_window[-1][0] > 2.0:
                self.hud.video_alive = False

        # 状态老化
        self.hud.vision_alive = self.phone.vision_alive
        self.hud.esp32_alive = self.esp32.connected and self.esp32.telem_age_ms < 1000
        self.hud.telem_alive = self.esp32.telem_age_ms < 1000
        # 视觉帧率：由收包线程在收到 vision 时滑动计算，避免用累计帧数越涨越高
        self.hud.vision_fps = self.phone.vision_fps
        self.lbl_phone_ip.setText(f"手机：{self.phone.phone_ip or '--'}"
                                  f"   视觉帧：{self.phone.vision_frames}")
        self.lbl_sync_state.setText(
            "同步状态：已连接" if self.phone.settings_alive else "同步状态：等待手机…")
        self.lbl_sync_state.setStyleSheet(
            "color:#32D74B;font-size:11px;" if self.phone.settings_alive
            else "color:#9E9E9E;font-size:11px;")

        # 手柄输入（连续量积分 + 边沿动作）
        self._poll_gamepad(dt)

        # 比赛计时（START 可暂停）
        if not self._paused:
            self._elapsed_s += dt
        self.hud.paused = self._paused
        self.hud.time_left_s = max(0, self.cfg["match_minutes"] * 60
                                   - int(self._elapsed_s))
        self.canvas.update()

    # ─────────── 手柄输入（主操控；键鼠降级后备） ───────────

    def _poll_gamepad(self, dt: float) -> None:
        st = self.pad.state()
        self.hud.pad_connected = st.connected
        if st.connected:
            self.lbl_pad.setText(f"手柄：{self.pad.name}")
            self.lbl_pad.setStyleSheet("color:#32D74B;")
            # 右摇杆角速度积分 → 云台目标角（LT 精确瞄准减速）
            scale = self.cfg.get("gamepad_aim_slow_scale", 0.35) if st.aim_slow else 1.0
            self._input_yaw += st.yaw_rate * scale * dt
            self._input_pitch = max(-45.0, min(45.0,
                                               self._input_pitch + st.pitch_rate * scale * dt))
            self.esp32.current_input["yaw"] = self._input_yaw
            self.esp32.current_input["pitch"] = self._input_pitch
            self.esp32.current_input["mx"] = st.mx
            self.esp32.current_input["my"] = st.my
        else:
            self.lbl_pad.setText("手柄：未检测到（键鼠后备）")
            self.lbl_pad.setStyleSheet("color:#9E9E9E;")
            # 无手柄时清底盘指令，避免摇杆回中前的残值
            self.esp32.current_input["mx"] = 0
            self.esp32.current_input["my"] = 0

        for kind in self.pad.drain_actions():
            self._on_pad_action(kind)

    def _on_pad_action(self, kind: str) -> None:
        if kind == "fire":
            self.esp32.queue_fire()
            self.hud.flash_fire()
        elif kind == "toggle_settings":
            self._toggle_settings()
        elif kind == "toggle_pause":
            self._paused = not self._paused
        elif kind == "toggle_autofire":
            self._set_switch("autofire", not self._switches["autofire"].isChecked())
        elif kind == "cycle_target":
            if self._switches["highland"].isChecked():
                self._set_switch("highland", False)
                self._set_switch("base", True)
            else:
                self._set_switch("highland", True)
                self._set_switch("base", False)
        elif kind == "toggle_overlay":
            self.hud.show_detect = not self.hud.show_detect
        elif kind == "cycle_panel":
            self.hud.show_panel = not self.hud.show_panel

    def _set_switch(self, key: str, value: bool) -> None:
        cb = self._switches[key]
        if cb.isChecked() != value:
            cb.setChecked(value)   # stateChanged → _on_switch → 下发手机

    # ─────────── 信号回调（UI 线程） ───────────

    def _on_vision(self, msg: dict) -> None:
        self.hud.vision = msg
        self.hud.vision_lat_ms = msg.get("lat_ms", 0)
        # 视觉帧率：按最近 2 秒窗口（PhoneSync 计数，这里粗算）
        fc = msg.get("fire")
        if fc and fc.get("should"):
            self.hud.ammo = max(0, self.hud.ammo - 1)

    def _on_settings(self, s: dict) -> None:
        for k, cb in self._switches.items():
            if k in s:
                cb.blockSignals(True)
                cb.setChecked(bool(s[k]))
                cb.blockSignals(False)

    def _on_telem(self, msg: dict) -> None:
        self.hud.telem = msg

    def _on_switch(self, key: str) -> None:
        cb = self._switches[key]
        self.phone.set_setting(key, cb.isChecked())

    # ─────────── 设置面板 ───────────

    def _toggle_settings(self) -> None:
        self.settings_panel.setVisible(not self.settings_panel.isVisible())

    # ─────────── 输入捕获（鼠标 FPS 式云台控制） ───────────

    def keyPressEvent(self, e: QKeyEvent) -> None:
        if e.key() == Qt.Key_Tab:
            self._toggle_settings()
        elif e.key() == Qt.Key_Escape and self._mouse_captured:
            self._release_mouse()
        elif e.key() == Qt.Key_F and self._mouse_captured:
            self.esp32.queue_fire()
            self.hud.flash_fire()
        else:
            super().keyPressEvent(e)

    def mousePressEvent(self, e: QMouseEvent) -> None:
        if e.button() == Qt.LeftButton and not self._mouse_captured:
            self._capture_mouse()
        elif self._mouse_captured and e.button() == Qt.LeftButton:
            self.esp32.queue_fire()

    def _capture_mouse(self) -> None:
        self._mouse_captured = True
        self._last_mouse_x, self._last_mouse_y = 0, 0
        self.canvas.setFocus()
        self.setCursor(Qt.BlankCursor)
        self.canvas._show_capture_hint = True

    def _release_mouse(self) -> None:
        self._mouse_captured = False
        self.unsetCursor()
        self.canvas._show_capture_hint = False

    def mouseMoveEvent(self, e: QMouseEvent) -> None:
        if not self._mouse_captured:
            return
        # 相对位移 → 云台角速度（灵敏度 0.12°/px）
        dx = e.x() - self._last_mouse_x if self._last_mouse_x else 0
        dy = e.y() - self._last_mouse_y if self._last_mouse_y else 0
        self._last_mouse_x, self._last_mouse_y = e.x(), e.y()
        self._input_yaw += dx * 0.12
        self._input_pitch = max(-45.0, min(45.0, self._input_pitch + dy * 0.12))
        self.esp32.current_input["yaw"] = self._input_yaw
        self.esp32.current_input["pitch"] = self._input_pitch

    def wheelEvent(self, e: QWheelEvent) -> None:
        pass

    def closeEvent(self, e) -> None:
        self.pad.stop()
        self.esp32.stop()
        self.phone.stop()
        self.decoder.stop()
        cfgmod.save(self.cfg)
        super().closeEvent(e)


class _Canvas(QWidget):
    """视频帧画布：paintEvent 画帧 + HUD 叠加层。"""

    def __init__(self, win: MainWindow):
        super().__init__()
        self.win = win
        self._img: QImage | None = None
        self._show_capture_hint = False
        self.setFocusPolicy(Qt.StrongFocus)
        self.setMouseTracking(True)

    def set_frame(self, img: QImage) -> None:
        self._img = img

    def paintEvent(self, e) -> None:
        p = QPainter(self)
        w, h = self.width(), self.height()
        p.fillRect(0, 0, w, h, Qt.black)
        if self._img:
            src = self._img.rect()
            scale = min(w / src.width(), h / src.height())
            dw, dh = int(src.width() * scale), int(src.height() * scale)
            x, y = (w - dw) // 2, (h - dh) // 2
            p.drawImage(x, y, self._img.scaled(dw, dh, Qt.KeepAspectRatio,
                                               Qt.SmoothTransformation))
        self.win.hud.paint(p, w, h)
        if self._show_capture_hint:
            p.setPen(Qt.white)
            p.setFont(self.win.hud._font)
            p.drawText(self.rect(), Qt.AlignCenter,
                       "鼠标已捕获（云台控制·键鼠后备）· 左键/F 开火 · Esc 释放")
        p.end()
