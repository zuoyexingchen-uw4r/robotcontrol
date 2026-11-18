"""spaa.window — 主窗口：GL 视频纹理 + HUD 叠加 + 快捷键。

渲染路径（为什么这么绕）：
    ffmpeg 解码 → rgb24 内存帧 → glTexSubImage2D 上传 → 全屏 quad 绘制
    → QPainter 在同一块 GL 画布上画 HUD
ffplay 做不到这一点：它独占窗口，叠加层没法跟画面严格对齐。
"""
from __future__ import annotations

import os
import queue
import time

from OpenGL import GL as gl
from PyQt5.QtCore import QRectF, Qt, QTimer
from PyQt5.QtGui import QColor, QImage, QPainter
from PyQt5.QtWidgets import QMainWindow, QOpenGLWidget

from . import config as cfgmod
from .decoder import VideoDecoder
from .game_state import GameState, MockSource
from .geometry import CameraModel
from .hud import SpaaHud
from .rx_aux import AuxLink
from .rx_video import VideoReceiver


class SpaaView(QOpenGLWidget):

    def __init__(self, win: "MainWindow"):
        super().__init__(win)
        self.win = win
        self.setMinimumSize(960, 540)
        self.setAttribute(Qt.WA_OpaquePaintEvent, True)
        self._frame: bytes | None = None
        self._seq = -1
        self._tex = 0
        self._tex_wh = (0, 0)

    # ─────────── GL ───────────

    def initializeGL(self) -> None:
        gl.glDisable(gl.GL_DEPTH_TEST)
        gl.glEnable(gl.GL_TEXTURE_2D)
        self._tex = gl.glGenTextures(1)
        gl.glBindTexture(gl.GL_TEXTURE_2D, self._tex)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MIN_FILTER, gl.GL_LINEAR)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_MAG_FILTER, gl.GL_LINEAR)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_S, gl.GL_CLAMP_TO_EDGE)
        gl.glTexParameteri(gl.GL_TEXTURE_2D, gl.GL_TEXTURE_WRAP_T, gl.GL_CLAMP_TO_EDGE)

    def resizeGL(self, w: int, h: int) -> None:
        dpr = self.devicePixelRatioF()
        gl.glViewport(0, 0, int(w * dpr), int(h * dpr))

    def set_frame(self, data: bytes, seq: int) -> None:
        self._frame = data
        self._seq = seq

    def video_rect(self) -> QRectF:
        """保持视频宽高比的居中矩形（letterbox）。"""
        W, H = self.width(), self.height()
        dw = float(self.win.cfg["decode_w"])
        dh = float(self.win.cfg["decode_h"])
        if W <= 0 or H <= 0 or dw <= 0 or dh <= 0:
            return QRectF(0, 0, W, H)
        ar = dw / dh
        if W / H > ar:
            h = H
            w = h * ar
        else:
            w = W
            h = w / ar
        return QRectF((W - w) / 2, (H - h) / 2, w, h)

    def paintGL(self) -> None:
        p = QPainter(self)
        p.beginNativePainting()

        gl.glClearColor(0.035, 0.045, 0.055, 1.0)
        gl.glClear(gl.GL_COLOR_BUFFER_BIT)

        frame = self._frame
        if frame:
            w = int(self.win.cfg["decode_w"])
            h = int(self.win.cfg["decode_h"])
            # 每帧都要显式开启：上一帧收尾时为了不让 Qt 的字形纹理错绑，关掉了它
            gl.glEnable(gl.GL_TEXTURE_2D)
            gl.glBindTexture(gl.GL_TEXTURE_2D, self._tex)
            if self._tex_wh != (w, h):
                gl.glTexImage2D(gl.GL_TEXTURE_2D, 0, gl.GL_RGB, w, h, 0,
                                gl.GL_RGB, gl.GL_UNSIGNED_BYTE, frame)
                self._tex_wh = (w, h)
            else:
                gl.glTexSubImage2D(gl.GL_TEXTURE_2D, 0, 0, 0, w, h,
                                   gl.GL_RGB, gl.GL_UNSIGNED_BYTE, frame)

            r = self.video_rect()
            W = max(1, self.width())
            H = max(1, self.height())
            x0, x1 = r.left() / W, r.right() / W
            y0, y1 = r.top() / H, r.bottom() / H

            gl.glMatrixMode(gl.GL_PROJECTION)
            gl.glLoadIdentity()
            gl.glOrtho(0.0, 1.0, 1.0, 0.0, -1.0, 1.0)   # y 向下，和窗口坐标一致
            gl.glMatrixMode(gl.GL_MODELVIEW)
            gl.glLoadIdentity()
            gl.glColor4f(1.0, 1.0, 1.0, 1.0)

            gl.glBegin(gl.GL_QUADS)
            # 屏幕顶部顶点取 v=0，画面才是正的：
            # glTexImage2D 的第一个像素对应纹理坐标 (0,0)（OpenGL 视它在底部），
            # 所以 v=0 是图像的**第一行**。这里若写成 v=1，整幅画面会上下颠倒。
            gl.glTexCoord2f(0.0, 0.0); gl.glVertex2f(x0, y0)
            gl.glTexCoord2f(1.0, 0.0); gl.glVertex2f(x1, y0)
            gl.glTexCoord2f(1.0, 1.0); gl.glVertex2f(x1, y1)
            gl.glTexCoord2f(0.0, 1.0); gl.glVertex2f(x0, y1)
            gl.glEnd()

        # 必须复位：QPainter 随后画 HUD 时，Qt 会给字形缓存绑自己的纹理。
        # 留着我们的视频纹理绑定 + GL_TEXTURE_2D 启用，字形就会采样错纹理，
        # 中文整片变方块（英文/数字因为走另一条路径不受影响）。
        gl.glBindTexture(gl.GL_TEXTURE_2D, 0)
        gl.glDisable(gl.GL_TEXTURE_2D)
        gl.glPixelStorei(gl.GL_UNPACK_ALIGNMENT, 4)

        p.endNativePainting()
        self.win.hud.paint(p, self.width(), self.height())
        p.end()


class MainWindow(QMainWindow):

    def __init__(self, cfg: dict | None = None):
        super().__init__()
        self.cfg = cfg or cfgmod.load()
        self.setWindowTitle("狼牙视觉 · 图传上位机")
        self.resize(1600, 900)
        self.setMinimumSize(1100, 620)

        self.rx_q: "queue.Queue[tuple[bytes, float]]" = queue.Queue(maxsize=2)
        self.rx = VideoReceiver(self.cfg, self.rx_q)
        self.dec = VideoDecoder(self.cfg, self.rx_q)
        self.aux = AuxLink(self.cfg)
        self.cam = CameraModel(self.cfg, int(self.cfg["decode_w"]),
                               int(self.cfg["decode_h"]))
        self.hud = SpaaHud(self.cfg, self.cam)

        self.view = SpaaView(self)
        self.setCentralWidget(self.view)
        self.view.setFocusPolicy(Qt.StrongFocus)

        self._last_seq = -1
        self._last_frame_t = 0.0
        self._ammo = int(self.cfg["match"]["ammo_start"])
        self._refill = 0
        self._fullscreen = False
        self._shot_n = 0

        # 赛事状态（接服务器前用 MockSource 产生演示事件）
        self.game = GameState()
        self.mock = MockSource(self.game)
        self.toasts: list[tuple[str, str, str, float]] = []
        self._hold: dict[str, float] = {}      # 长按动作 -> 按下时刻
        self._aim_mode = "aim_manual"
        self._gear = False

        self.rx.start()
        self.dec.start()
        self.aux.start()

        self.timer = QTimer(self)
        self.timer.timeout.connect(self._tick)
        self.timer.start(16)
        self._t0 = time.time()

    # ─────────── 主循环 ───────────

    def _tick(self) -> None:
        self.dec.maybe_fallback()
        frame, seq = self.dec.latest()
        if seq != self._last_seq and frame:
            self._last_seq = seq
            self.view.set_frame(frame, seq)
            self._last_frame_t = time.time()

        frame_ready = bool(frame) and (time.time() - self._last_frame_t < 1.5)
        aux = self.aux.snapshot()
        master = aux.get("master")

        now = time.time()
        # 赛事事件 → toast（差分事件由 game_state 产生）
        for ev in self.mock.poll():
            self.toasts.append((ev.text, ev.detail, ev.tone, now))
        self.toasts = [t for t in self.toasts if now - t[3] < 3.8][-8:]

        # 长按：进度交给 HUD 画，走满就执行
        hold_prog: dict[str, float] = {}
        for act in list(self._hold):
            dur = float(self.cfg["hold"].get(f"{act}_ms", 3000)) / 1000.0
            el = now - self._hold[act]
            if el >= dur:
                self._hold.pop(act, None)
                self._execute(act)
            else:
                hold_prog[act] = el / dur

        state = {
            "frame_ready": frame_ready,
            "vrect": self.view.video_rect(),
            "stats_video": self.rx.snapshot(),
            "stats_dec": self.dec.stats(),
            "dec_fps": self.dec.dec_fps,
            "master": master,
            "master_age": aux["master_age"],
            "master_lost": aux["master_lost"],
            "points": aux["points"],
            "points_age": aux["points_age"],
            "vision": aux["vision"],
            "vision_age": aux["vision_age"],
            "ammo": self._ammo,
            "refill": self._refill,
            "rec_seconds": self.rx.record_seconds() if self.rx.recording else 0,
            "game": self.game,
            "toasts": self.toasts,
            "aim_mode": self._aim_mode,
            "hold": hold_prog,
        }
        self.hud.update(state)
        self.view.update()

    # ─────────── 交互 ───────────

    def _action_for_key(self, num: int) -> str | None:
        for act, v in self.cfg.get("keys", {}).items():
            if int(v) == num:
                return act
        return None

    def keyPressEvent(self, e) -> None:
        if e.isAutoRepeat():
            return
        k = e.key()

        # 武器栏：3~9
        for n in range(3, 10):
            if k == getattr(Qt, f"Key_{n}"):
                act = self._action_for_key(n)
                if act:
                    if act == "restart":
                        self._hold["restart"] = time.time()   # 长按 3s 才发
                    else:
                        self._do_action(act)
                return

        # J 长按断电（不写进武器栏，但按下要能看到进度）
        if k == Qt.Key_J:
            self._hold["power_off"] = time.time()
            return

        if k == Qt.Key_F11:
            self._toggle_fullscreen()
        elif k == Qt.Key_Escape and self._fullscreen:
            self._toggle_fullscreen()
        elif k == Qt.Key_R:
            self._toggle_record()
        elif k == Qt.Key_P:
            self._screenshot()
        elif k == Qt.Key_K:
            self.rx.request_idr()
        elif k == Qt.Key_O:
            self.cfg["ui"]["show_rings"] = not self.cfg["ui"].get("show_rings", True)
            cfgmod.save(self.cfg)
        elif k == Qt.Key_Q:
            self.close()
        else:
            super().keyPressEvent(e)

    def keyReleaseEvent(self, e) -> None:
        if e.isAutoRepeat():
            return
        k = e.key()
        if k == Qt.Key_J:
            self._hold.pop("power_off", None)
        for n in range(3, 10):
            if k == getattr(Qt, f"Key_{n}") and self._action_for_key(n) == "restart":
                self._hold.pop("restart", None)
        super().keyReleaseEvent(e)

    def _do_action(self, act: str) -> None:
        names = {
            "aim_manual": "手动瞄准", "auto_high": "自动高地靶",
            "auto_base": "自动基地靶", "auto_move": "自动移动靶",
        }
        if act in names:
            self._aim_mode = act
            self._toast(f"瞄准模式：{names[act]}", "", "info")
        elif act == "reload":
            self._ammo = min(24, self._ammo + 8)
            self._toast("装填弹药", "+8", "good")
            # TODO 主控下行 cmd bit2 脉冲
        elif act == "gear":
            self._gear = not self._gear
            self._toast("起落架放下" if self._gear else "起落架收起", "", "info")
            # TODO 主控下行 cmd bit3/bit4

    def _execute(self, act: str) -> None:
        """长按走满后执行。两个都不可逆，先只弹提示并留下行接口。"""
        if act == "restart":
            self._toast("重启电控已下发", "", "bad")
            # TODO 主控下行 cmd bit5
        elif act == "power_off":
            self._toast("车辆电源已断开", "", "bad")
            # TODO 主控下行 cmd bit6

    def _toast(self, text: str, detail: str = "", tone: str = "info") -> None:
        self.toasts.append((text, detail, tone, time.time()))
        self.toasts = self.toasts[-8:]

    def _toggle_fullscreen(self) -> None:
        if self._fullscreen:
            self.showNormal()
        else:
            self.showFullScreen()
        self._fullscreen = not self._fullscreen

    def _toggle_record(self) -> None:
        if self.rx.recording:
            path = self.rx.stop_record()
            print(f"[rec] 已保存 {path}（{self.rx.rec_bytes / 1e6:.1f} MB）")
            return
        ext = "h265" if self.cfg.get("codec") == "hevc" else "h264"
        name = time.strftime("record_%Y%m%d-%H%M%S.") + ext
        path = os.path.join(os.getcwd(), name)
        self.rx.start_record(path)
        print(f"[rec] 开始录制 → {path}")

    def _screenshot(self) -> None:
        self._shot_n += 1
        name = time.strftime("shot_%Y%m%d-%H%M%S_") + f"{self._shot_n:02d}.png"
        path = os.path.join(os.getcwd(), name)
        img = self.view.grabFramebuffer()
        if img.save(path):
            print(f"[shot] {path}")

    def closeEvent(self, e) -> None:
        self.timer.stop()
        self.rx.stop()
        self.dec.stop()
        self.aux.stop()
        if self.rx.recording:
            self.rx.stop_record()
        super().closeEvent(e)
