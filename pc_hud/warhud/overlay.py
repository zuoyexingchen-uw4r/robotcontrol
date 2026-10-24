"""warhud.overlay — War Thunder 风格 HUD 叠加层（QPainter 绘制）。

布局（1280×720 基准，按窗口等比缩放）：
┌──────────────────────────────────────────────────────┐
│ [视频画面全窗口]                                      │
│  ┌状态面板──────┐                ┌弹药/比分──────┐   │
│  │电压 电流 温度 │                │弹丸 局分 计时 │   │
│  └──────────────┘                └──────────────┘   │
│                    十 字 准 星                        │
│                  （提前量标记）                       │
│              目标距离环                               │
│  ┌连接状态────────────────────────────────────────┐  │
│  │ 视频 ●  视觉 ●  ESP32 ●   视觉延迟 45ms        │  │
│  └───────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────┘
检测框/追踪框跟随 vision 消息中的像素坐标（手机 640×480 基准，
按窗口尺寸等比映射）。
"""

from __future__ import annotations

import time

from PyQt5.QtCore import QPointF, QRectF, Qt
from PyQt5.QtGui import QColor, QFont, QPainter, QPen


class HudOverlay:
    """HUD 绘制器（无 QWidget 依赖，main_window 的 paintEvent 调用）。"""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.vision: dict | None = None
        self.telem: dict = {}
        self.telem_alive = False
        self.vision_alive = False
        self.video_alive = False
        self.esp32_alive = False
        self.vision_fps = 0.0
        self.vision_lat_ms = 0
        self.ammo = 8
        self.score_self = 0
        self.score_foe = 0
        self.round_no = 1
        self.time_left_s = 300
        self.mouse_pos: QPointF | None = None      # 操作手鼠标位置（瞄准）
        self.settings_open = False
        self.pad_connected = False                 # 手柄在线（HUD 状态）
        self.show_detect = True                    # X 键：检测框叠加显隐
        self.show_panel = True                     # SELECT 键：状态面板显隐
        self.paused = False                        # START 键：计时暂停
        self._fire_flash_until = 0.0               # 开火闪光（monotonic）

        self._font = QFont("Consolas", 10)
        self._font_b = QFont("Consolas", 10, QFont.Bold)
        self._font_big = QFont("Consolas", 14, QFont.Bold)

    def flash_fire(self) -> None:
        """开火闪光（主窗口收到 fire 动作时调用）。"""
        self._fire_flash_until = time.monotonic() + 0.12

    # ─────────── 主绘制 ───────────

    def paint(self, p: QPainter, w: int, h: int) -> None:
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.TextAntialiasing)
        # 手机视觉基准分辨率（检测框坐标来源）
        base_w, base_h = 640.0, 480.0
        sx, sy = w / base_w, h / base_h

        if self.show_detect:
            self._draw_detections(p, sx, sy)
        self._draw_crosshair(p, w, h)
        if self.show_panel:
            self._draw_status_panel(p, w, h)
            self._draw_ammo_panel(p, w, h)
        self._draw_conn_strip(p, w, h)
        if not self.video_alive:
            self._draw_no_video(p, w, h)

    # ─────────── 检测框 / 追踪框 ───────────

    def _draw_detections(self, p: QPainter, sx: float, sy: float) -> None:
        v = self.vision
        if not v:
            return
        for t in v.get("targets", []):
            cx, cy = t.get("cx", 0) * sx, t.get("cy", 0) * sy
            tw, th = t.get("w", 40) * sx, t.get("h", 40) * sy
            color = QColor(self.cfg["detect_color"] if t.get("type") == "base"
                           else "#32D74B")
            p.setPen(QPen(color, 2))
            p.drawRect(QRectF(cx - tw / 2, cy - th / 2, tw, th))
            p.setFont(self._font)
            p.drawText(QPointF(cx - tw / 2, cy - th / 2 - 4),
                       "高地靶" if t.get("type") == "highland" else "基地靶")

        tr = v.get("track")
        if tr:
            cx, cy = tr.get("cx", 0) * sx, tr.get("cy", 0) * sy
            vx, vy = tr.get("vx", 0), tr.get("vy", 0)
            p.setPen(QPen(QColor(self.cfg["track_color"]), 2, Qt.DashLine))
            r = 26
            p.drawEllipse(QPointF(cx, cy), r, r)
            # 速度矢量（预测 0.5s 位置）
            p.setPen(QPen(QColor(self.cfg["track_color"]), 2))
            p.drawLine(QPointF(cx, cy),
                       QPointF(cx + vx * 0.5 * sx, cy + vy * 0.5 * sy))
            p.setFont(self._font_b)
            dist = tr.get("dist_mm")
            if dist:
                p.drawText(QPointF(cx + r + 6, cy + 4), f"{dist / 1000:.2f} m")

    # ─────────── 中央准星 + 提前量 ───────────

    def _draw_crosshair(self, p: QPainter, w: int, h: int) -> None:
        cx, cy = w / 2, h / 2
        col = QColor(self.cfg["crosshair_color"])
        flash = time.monotonic() < self._fire_flash_until
        p.setPen(QPen(col, 2.5 if flash else 1.5))
        L, gap = 14, 5
        p.drawLine(QPointF(cx - L, cy), QPointF(cx - gap, cy))
        p.drawLine(QPointF(cx + gap, cy), QPointF(cx + L, cy))
        p.drawLine(QPointF(cx, cy - L), QPointF(cx, cy - gap))
        p.drawLine(QPointF(cx, cy + gap), QPointF(cx, cy + L))
        p.drawEllipse(QPointF(cx, cy), gap, gap)
        if flash:
            p.setPen(QPen(QColor("#FFFFFF"), 1))
            p.drawEllipse(QPointF(cx, cy), gap + 5, gap + 5)

        # 弹道下坠刻度（每格 1.8mrad ≈ 0.1°@50m，示意）
        p.setFont(QFont("Consolas", 8))
        for i in range(1, 6):
            dy = i * 22
            p.setPen(QPen(col, 1) if i % 2 else QPen(col, 1, Qt.DotLine))
            p.drawLine(QPointF(cx - 6, cy + dy), QPointF(cx + 6, cy + dy))
            p.setPen(QPen(col, 1))
            p.drawText(QPointF(cx + 9, cy + dy + 3), f"{i * 100}m")

        # 追踪目标提前量标记（跟随卡尔曼速度）
        tr = self.vision.get("track") if self.vision else None
        if tr:
            vx, vy = tr.get("vx", 0), tr.get("vy", 0)
            # 提前量 = 速度 × 弹丸飞行时间（简化为 0.3s）
            ax = cx + vx * 0.3
            ay = cy + vy * 0.3
            p.setPen(QPen(QColor(self.cfg["track_color"]), 2))
            p.drawEllipse(QPointF(ax, ay), 4, 4)
            p.drawLine(QPointF(ax - 8, ay), QPointF(ax + 8, ay))
            p.drawLine(QPointF(ax, ay - 8), QPointF(ax, ay + 8))

    # ─────────── 左上：机器人状态 ───────────

    def _draw_status_panel(self, p: QPainter, w: int, h: int) -> None:
        x, y, pw, ph = 12, 12, 168, 88
        self._panel_bg(p, QRectF(x, y, pw, ph))
        p.setFont(self._font_b)
        p.setPen(QPen(QColor("#E8F5E9")))
        p.drawText(QPointF(x + 8, y + 16), "机器人状态")
        p.setFont(self._font)
        t = self.telem if self.telem_alive else None
        rows = [
            ("电压", f"{t['voltage_v']:.2f} V" if t else "--"),
            ("电流", f"{t['current_a']:.1f} A" if t else "--"),
            ("温度", f"{t['temp_c']:.1f} °C" if t else "--"),
            ("轮速", f"{t['rpm_left']}/{t['rpm_right']} RPM" if t else "--"),
        ]
        for i, (k, v) in enumerate(rows):
            yy = y + 32 + i * 14
            p.setPen(QPen(QColor("#9E9E9E")))
            p.drawText(QPointF(x + 8, yy), k)
            p.setPen(QPen(QColor("#E8F5E9")))
            p.drawText(QPointF(x + 48, yy), v)

    # ─────────── 右上：弹药 / 比分 / 计时 ───────────

    def _draw_ammo_panel(self, p: QPainter, w: int, h: int) -> None:
        pw, ph = 190, 88
        x, y = w - pw - 12, 12
        self._panel_bg(p, QRectF(x, y, pw, ph))
        p.setFont(self._font_b)
        p.setPen(QPen(QColor("#E8F5E9")))
        p.drawText(QPointF(x + 8, y + 16), "弹丸 / 战局")
        p.setFont(self._font)
        p.drawText(QPointF(x + 8, y + 32), f"弹药  {self.ammo}")
        m, s = divmod(max(0, self.time_left_s), 60)
        p.drawText(QPointF(x + 8, y + 46), f"时间  {m:02d}:{s:02d}   第 {self.round_no} 局")
        if self.paused:
            p.setPen(QPen(QColor("#FFD60A")))
            p.drawText(QPointF(x + 8, y + 46), "        已暂停（START 继续）")
        p.drawText(QPointF(x + 8, y + 60), f"比分  己 {self.score_self} : {self.score_foe} 敌")
        p.setFont(QFont("Consolas", 8))
        p.setPen(QPen(QColor("#9E9E9E")))
        p.drawText(QPointF(x + 8, y + 76), "RT 开火 · LT 微瞄 · Y 设置 · START 暂停 · Esc 鼠标后备")

    # ─────────── 底部：连接状态条 ───────────

    def _draw_conn_strip(self, p: QPainter, w: int, h: int) -> None:
        y = h - 30
        self._panel_bg(p, QRectF(0, y, w, 30))
        x = 12
        for name, alive in (("视频", self.video_alive),
                            ("视觉", self.vision_alive),
                            ("ESP32", self.esp32_alive),
                            ("手柄", self.pad_connected)):
            p.setPen(QPen(QColor("#32D74B" if alive else "#FF3B30"), 2))
            p.drawEllipse(QPointF(x + 4, y + 15), 4, 4)
            p.setFont(self._font)
            p.setPen(QPen(QColor("#E8F5E9")))
            p.drawText(QPointF(x + 14, y + 19), name)
            x += 58
        p.setPen(QPen(QColor("#9E9E9E")))
        p.drawText(QPointF(x + 14, y + 19),
                   f"视觉 {self.vision_fps:.1f}fps · 延迟 {self.vision_lat_ms}ms")
        # 视觉性能读数
        v = self.vision
        if v and v.get("proc_ms"):
            pm = v["proc_ms"]
            p.drawText(QPointF(x + 190, y + 19),
                       f"预处理 {pm.get('pre', 0)}ms · 检测 {pm.get('det', 0)}ms")

    def _draw_no_video(self, p: QPainter, w: int, h: int) -> None:
        p.setPen(QPen(QColor("#FFD60A")))
        p.setFont(self._font_big)
        p.drawText(QRectF(0, h / 2 - 40, w, 30), Qt.AlignCenter,
                   "等待视频流…（检查手机图传是否启动、端口是否一致）")

    def _panel_bg(self, p: QPainter, r: QRectF) -> None:
        p.setPen(Qt.NoPen)
        c = QColor(0, 0, 0)
        c.setAlphaF(self.cfg["panel_opacity"])
        p.setBrush(c)
        p.drawRoundedRect(r, 4, 4)
