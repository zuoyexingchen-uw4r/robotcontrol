"""spaa.hud — SPAA 布局的叠加层绘制。

分区（照搬自行防空炮的信息架构，不抄皮肤）：
    左上   车辆信息（温度/电压/电流），异常黄/红，黑阴影白字
    左上↓  功能指示（预留占位）
    中央   视频 + 相机标定出的地面等距环 + 准星 + 俯仰刻度
    右侧   雷达 PPI（激光点云 + 视觉目标方位）
    左下   车辆姿态仪（IMU yaw/pitch/roll + 轮速）
    底部   弹药 / 主控 / 管道统计 / 码率
    右上   链路状态一行

坐标约定：面板用窗口坐标；测距环与目标框用「视频帧坐标」绘制——
先 translate+scale 到视频显示矩形，环才和画面严格对齐。
字号一律传**基准像素**，由 _font 内部乘窗口缩放系数。
"""
from __future__ import annotations

import math
import time

from PyQt5.QtCore import QPointF, QRectF, Qt
from PyQt5.QtGui import QColor, QFont, QPainter, QPainterPath, QPen, QPolygonF

from .geometry import CameraModel


def _fmt_dist(mm: float) -> str:
    return f"{mm / 1000.0:.1f}m" if mm >= 1000 else f"{mm:.0f}mm"


def _frange(a: float, b: float, step: float) -> list[float]:
    out = []
    x = a
    while x <= b + 1e-6:
        out.append(x)
        x += step
    return out


class SpaaHud:

    def __init__(self, cfg: dict, cam: CameraModel):
        self.cfg = cfg
        self.cam = cam
        self.ui = cfg["ui"]
        self.warn = cfg["warn"]
        self.radar_cfg = cfg["radar"]

        self.c_ok = QColor(self.ui["grid"])
        self.c_text = QColor(self.ui["text"])
        self.c_dim = QColor(self.ui["dim"])
        self.c_warn = QColor(self.ui["warn"])
        self.c_crit = QColor(self.ui["crit"])
        self.c_panel = QColor(0, 0, 0, int(255 * self.ui.get("panel_alpha", 0.55)))

        self.state: dict = {}
        self.scale = 1.0
        self.W = 1600
        self.H = 900

        # 雷达按 1° 分桶累积：一帧只带 16 个点，一圈要靠多帧拼
        self._bins = [None] * 360
        self._bin_t = [0.0] * 360
        self._ring_cache: tuple | None = None
        self._ring_paths: list = []
        self._scan_angle = 0.0
        self._last_t = time.time()

    # ═══════════════════════ 入口 ═══════════════════════

    def update(self, state: dict) -> None:
        now = time.time()
        dt = min(0.1, now - self._last_t)
        self._last_t = now
        self._scan_angle = (self._scan_angle + 120.0 * dt) % 360.0

        pts = state.get("points") or []
        if pts and state.get("points_age", 9e9) < 1.0:
            for ang, dist, _inten in pts:
                if dist <= 0:
                    continue
                i = int(ang) % 360
                self._bins[i] = (dist, _inten)
                self._bin_t[i] = now
        self.state = state

    def paint(self, p: QPainter, W: int, H: int) -> None:
        self.W, self.H = W, H
        self.scale = max(0.72, min(1.7, H / 900.0))
        st = self.state

        p.setRenderHint(QPainter.Antialiasing, True)
        p.setRenderHint(QPainter.TextAntialiasing, True)

        vrect = st.get("vrect")
        if vrect and st.get("frame_ready"):
            self._paint_rings(p, vrect)
            self._paint_targets(p, vrect)

        self._paint_reticle(p, vrect)
        self._paint_vehicle_info(p)
        if self.ui.get("show_radar", True):
            self._paint_radar(p)
        if self.ui.get("show_attitude", True):
            self._paint_stabilizer(p)
        self._paint_weapons(p)
        self._paint_top_bar(p)
        self._paint_toasts(p)
        self._paint_alerts(p)

    # ═══════════════════════ 工具 ═══════════════════════

    def _font(self, px: float, bold: bool = False, mono: bool = True) -> QFont:
        """按**像素**字号取字体（内部乘窗口缩放）。

        中文必须走 font_cjk：本机只有 "Microsoft YaHei UI"，写 "Microsoft YaHei"
        会静默回退到无中文字形的字体 → 整片方块。
        QFont 第二参数是 pointSize，传像素会被 DPI 放大 1.33 倍，故用 setPixelSize。
        """
        fam = self.ui["font"] if mono else self.ui["font_cjk"]
        f = QFont(fam)
        f.setPixelSize(max(9, int(round(px * self.scale))))
        f.setBold(bold)
        f.setStyleStrategy(QFont.PreferAntialias)
        return f

    def _shadow_text(self, p: QPainter, x: float, y: float, s: str,
                     color: QColor, px: float, bold: bool = False,
                     mono: bool | None = None, align=Qt.AlignLeft) -> None:
        """黑阴影 + 彩色字，y 为基线；含中文自动切雅黑，纯 ASCII 用等宽。"""
        if mono is None:
            mono = s.isascii()
        f = self._font(px, bold, mono)
        p.setFont(f)
        w = p.fontMetrics().width(s)
        dx = -w / 2 if align == Qt.AlignHCenter else (-w if align == Qt.AlignRight else 0)
        p.setPen(QColor(0, 0, 0, 215))
        p.drawText(QPointF(x + dx + 1.8, y + 1.8), s)
        p.setPen(color)
        p.drawText(QPointF(x + dx, y), s)

    def _panel(self, p: QPainter, r: QRectF, radius: float = 6) -> None:
        p.setPen(QPen(QColor(255, 255, 255, 24), 1.0))
        p.setBrush(self.c_panel)
        p.drawRoundedRect(r, radius, radius)

    def _label(self, p: QPainter, x: float, y: float, s: str) -> None:
        p.setFont(self._font(11, False, False))
        p.setPen(self.c_dim)
        p.drawText(QPointF(x, y), s)

    # ═══════════════════════ 左上：车辆信息 ═══════════════════════

    def _paint_vehicle_info(self, p: QPainter) -> None:
        m = self.state.get("master")
        age = self.state.get("master_age", 9e9)
        s = self.scale

        temp = volt = curr = None
        if m and age < 2.0:
            temp = m.get("temp_0_1c", 0) / 10.0
            volt = m.get("voltage_mv", 0) / 1000.0
            curr = m.get("current_ma", 0) / 1000.0

        x = 24 * s
        y0 = 94 * s
        gap = 24 * s

        t_txt = "温度  --" if temp is None else f"温度  {temp:.0f} 度"
        v_txt = "电压  --" if volt is None else f"电压  {volt:.1f} v"
        a_txt = "电流  --" if curr is None else f"电流  {curr:.1f} a"

        tc = vc = ac = self.c_text if m else self.c_dim
        if temp is not None:
            tc = self.c_crit if temp >= self.warn["temp_crit"] else (
                self.c_warn if temp >= self.warn["temp_warn"] else self.c_text)
        if volt is not None:
            vc = self.c_crit if volt <= self.warn["volt_crit"] else (
                self.c_warn if volt <= self.warn["volt_warn"] else self.c_text)
        if curr is not None:
            ac = self.c_crit if curr >= self.warn["curr_crit"] else (
                self.c_warn if curr >= self.warn["curr_warn"] else self.c_text)

        self._shadow_text(p, x, y0, t_txt, tc, 19)
        self._shadow_text(p, x, y0 + gap, v_txt, vc, 19)
        self._shadow_text(p, x, y0 + gap * 2, a_txt, ac, 19)

        if m is None:
            self._shadow_text(p, x, y0 + gap * 3, "主控未连接", self.c_dim, 12)
        elif age >= 2.0:
            self._shadow_text(p, x, y0 + gap * 3, f"主控断流 {age:.0f}s", self.c_warn, 12)

    # ═══════════════════════ 左上↓：功能指示（预留）═══════════════════════

    def _paint_function_strip(self, p: QPainter) -> None:
        s = self.scale
        labels = ["稳定器", "追踪", "自动开火", "导航"]
        x = 24 * s
        y = 148 * s
        w = 66 * s
        h = 22 * s
        gap = 6 * s
        self._label(p, x, y - 8 * s, "功能 · 预留")
        f = self._font(11, False, False)
        for i, name in enumerate(labels):
            r = QRectF(x + i * (w + gap), y, w, h)
            p.setPen(QPen(QColor(255, 255, 255, 34), 1.0))
            p.setBrush(QColor(0, 0, 0, 95))
            p.drawRoundedRect(r, 3, 3)
            p.setFont(f)
            p.setPen(QColor(168, 182, 192, 190))
            p.drawText(r, int(Qt.AlignCenter), name)

    # ═══════════════════════ 中央：测距环 ═══════════════════════

    def _ensure_rings(self) -> None:
        cam = self.cam
        show = tuple(int(v) for v in self.ui.get("ring_show_mm", [2000, 4000, 6000]))
        seg = max(8, int(self.ui.get("ring_segments", 24)))
        key = (cam.frame_w, cam.frame_h, cam.fx, cam.fy, cam.cx, cam.cy,
               cam.h, cam.pitch, show, seg)
        if self._ring_cache == key:
            return
        self._ring_cache = key
        step = 360.0 / seg
        paths = []
        for radius in show:
            segs = cam.ring_segments(radius, step)
            path = QPainterPath()
            for s in segs:
                if len(s) < 2:
                    continue
                path.moveTo(s[0][0], s[0][1])
                for pt in s[1:]:
                    path.lineTo(pt[0], pt[1])
            paths.append((radius, path, cam.fit(radius, segs), self._axis_v(radius)))
        self._ring_paths = paths

    def _axis_v(self, radius: float) -> float | None:
        """环与画面中轴的交点（取最靠下的那个）——距离数字就标在这儿。"""
        best = None
        for seg in self.cam.ring_segments(radius, 1.5):
            for (u, v) in seg:
                if abs(u - self.cam.cx) < 4 and 0 < v < self.cam.frame_h:
                    if best is None or v > best:
                        best = v
        return best

    def _target_dist(self) -> float | None:
        vis = self.state.get("vision")
        if not vis or self.state.get("vision_age", 9e9) > 1.5:
            return None
        tr = vis.get("track") or {}
        d = tr.get("dist_mm")
        return float(d) if d and d > 0 else None

    def _ring_path(self, radius: float, step_deg: float) -> QPainterPath:
        path = QPainterPath()
        for s in self.cam.ring_segments(radius, step_deg):
            if len(s) < 2:
                continue
            path.moveTo(s[0][0], s[0][1])
            for pt in s[1:]:
                path.lineTo(pt[0], pt[1])
        return path

    def _paint_rings(self, p: QPainter, vrect: QRectF) -> None:
        if not self.ui.get("show_rings", True):
            return
        self._ensure_rings()
        cam = self.cam
        p.save()
        p.translate(vrect.x(), vrect.y())
        p.scale(vrect.width() / cam.frame_w, vrect.height() / cam.frame_h)

        # 参考距离：两三道就够。密网格会把画面糊住，操作手只看几个关键距离。
        for radius, path, _fit, _v in self._ring_paths:
            p.setPen(QPen(QColor(0, 229, 192, 110), 1.3))
            p.drawPath(path)

        # 锁定目标的实际距离：单独一圈高亮，这是唯一"活"的那道线
        tgt = self._target_dist()
        if tgt:
            p.setPen(QPen(QColor(255, 213, 61, 200), 1.8))
            p.drawPath(self._ring_path(tgt, 7.5))

        p.setFont(self._font(11.5))
        for idx, (radius, _path, _fit, v) in enumerate(self._ring_paths):
            if v is None:
                continue
            p.setPen(QColor(0, 229, 192, 160))
            lbl = f"{radius / 1000.0:.0f}m"
            if idx % 2 == 0:      # 交错左右，避免远处几圈标签叠成一列
                p.drawText(QRectF(cam.cx - 72, v - 15, 62, 14),
                           int(Qt.AlignRight | Qt.AlignVCenter), lbl)
            else:
                p.drawText(QPointF(cam.cx + 8, v - 3), lbl)
        if tgt:
            v = self._axis_v(tgt)
            if v is not None:
                # 目标距离不贴在环上：那里会跟目标框的标签连成一句
                p.setPen(QColor(255, 213, 61, 220))
                p.drawText(QPointF(cam.cx + 66, cam.cy - 30),
                           f"◆ {_fmt_dist(tgt)}")

        p.restore()

    # ═══════════════════════ 中央：准星 + 俯仰 ═══════════════════════

    def _paint_reticle(self, p: QPainter, vrect: QRectF | None) -> None:
        cx = self.W / 2.0
        cy = self.H / 2.0
        s = self.scale
        arm = 34.0 * s
        gap = 9.0 * s

        # 先画一层黑描边：暗场景里纯青色细线会糊掉，描边保证任何背景都能看见
        for pen in (QPen(QColor(0, 0, 0, 130), 3.0), QPen(QColor(0, 229, 192, 215), 1.3)):
            p.setPen(pen)
            p.drawLine(QPointF(cx - arm, cy), QPointF(cx - gap, cy))
            p.drawLine(QPointF(cx + gap, cy), QPointF(cx + arm, cy))
            p.drawLine(QPointF(cx, cy - arm), QPointF(cx, cy - gap))
            p.drawLine(QPointF(cx, cy + gap), QPointF(cx, cy + arm))
        p.setBrush(QColor(0, 229, 192, 230))
        p.setPen(Qt.NoPen)
        p.drawEllipse(QPointF(cx, cy), 2.0, 2.0)
        p.setBrush(Qt.NoBrush)
        p.setPen(QPen(QColor(0, 229, 192, 90), 1.0))
        p.drawEllipse(QPointF(cx, cy), 26 * s, 26 * s)

        # 俯仰刻度：准星右侧竖排
        p.setFont(self._font(10))
        step = 20.0 * s
        for d in (-10, -5, 5, 10):
            yy = cy + d / 5.0 * step
            p.setPen(QPen(QColor(0, 229, 192, 95), 0.9))
            p.drawLine(QPointF(cx + 40 * s, yy), QPointF(cx + 48 * s, yy))
            p.setPen(QColor(0, 229, 192, 120))
            p.drawText(QPointF(cx + 53 * s, yy + 4), f"{d:+d}")

        rng = self.cam.optical_axis_range_mm()
        if rng:
            self._shadow_text(p, cx, cy + 52 * s, f"光轴落地 {_fmt_dist(rng)}",
                              QColor(0, 229, 192, 185), 12, False, False,
                              Qt.AlignHCenter)

    # ═══════════════════════ 目标框 ═══════════════════════

    def _paint_targets(self, p: QPainter, vrect: QRectF) -> None:
        vis = self.state.get("vision")
        if not vis or self.state.get("vision_age", 9e9) > 1.5:
            return
        fw = float(vis.get("frame_w") or self.cfg["decode_w"])
        fh = float(vis.get("frame_h") or self.cfg["decode_h"])
        if fw <= 0 or fh <= 0:
            return

        scale = min(vrect.width() / fw, vrect.height() / fh)
        ox = vrect.x() + (vrect.width() - fw * scale) / 2
        oy = vrect.y() + (vrect.height() - fh * scale) / 2

        for t in (vis.get("targets") or []):
            try:
                cx = float(t["cx"]) * scale + ox
                cy = float(t["cy"]) * scale + oy
                w = float(t["w"]) * scale
                h = float(t["h"]) * scale
            except (KeyError, TypeError, ValueError):
                continue
            conf = float(t.get("conf", 0))
            kind = "高地" if t.get("type") == "highland" else "基地"
            col = self.c_crit if t.get("type") == "highland" else self.c_warn

            r = QRectF(cx - w / 2, cy - h / 2, w, h)
            p.setPen(QPen(QColor(col.red(), col.green(), col.blue(), 200), 1.4))
            self._corner_box(p, r, min(w, h) * 0.28)

            dist = t.get("dist_mm")
            label = f"{kind} {_fmt_dist(dist) if dist else ''} {conf * 100:.0f}%"
            self._shadow_text(p, r.left() + 2, r.top() - 9, label, col, 13, True, False)

    def _corner_box(self, p: QPainter, r: QRectF, k: float) -> None:
        x1, y1, x2, y2 = r.left(), r.top(), r.right(), r.bottom()
        for (ax, ay, bx, by) in (
            (x1, y1, x1 + k, y1), (x1, y1, x1, y1 + k),
            (x2, y1, x2 - k, y1), (x2, y1, x2, y1 + k),
            (x1, y2, x1 + k, y2), (x1, y2, x1, y2 - k),
            (x2, y2, x2 - k, y2), (x2, y2, x2, y2 - k),
        ):
            p.drawLine(QPointF(ax, ay), QPointF(bx, by))

    # ═══════════════════════ 右侧：扇形雷达 ═══════════════════════

    def _paint_radar(self, p: QPainter) -> None:
        """扇形扫描雷达：扫描角 90~360 可配，4 道距离环，带缩放标度。

        360 时退化成整圆；缩放只改半径映射（range/zoom），点云不重采样。
        """
        s = self.scale
        R = 158.0 * s
        cx = self.W - R - 34.0 * s
        cy = self.H - 48.0 * s - R
        rc = self.radar_cfg
        sweep = max(90.0, min(360.0, float(rc.get("sweep_deg", 120))))
        zoom = max(0.25, min(4.0, float(rc.get("zoom", 1.0))))
        eff = max(500.0, float(rc["range_mm"]) / zoom)
        half = sweep / 2.0
        rmin = float(rc.get("min_mm", 120))
        full = sweep >= 359.0

        def polar(r, ang_deg):
            th = math.radians(ang_deg)
            return QPointF(cx + r * math.sin(th), cy - r * math.cos(th))

        # 底圆只作"全向范围"的暗示，压低存在感，别像一块黑饼
        p.setBrush(QColor(8, 14, 18, 70))
        p.setPen(QPen(QColor(0, 229, 192, 22), 0.8))
        p.drawEllipse(QPointF(cx, cy), R, R)

        # 扇面填充
        if not full:
            pts = [QPointF(cx, cy)]
            a = -half
            while a <= half + 0.01:
                pts.append(polar(R, a))
                a += 3.0
            p.setBrush(QColor(0, 229, 192, 16))
            p.setPen(QPen(QColor(0, 229, 192, 70), 0.8))
            p.drawPolygon(QPolygonF(pts))
        else:
            p.setBrush(QColor(0, 229, 192, 10))
            p.setPen(QPen(QColor(0, 229, 192, 70), 0.8))
            p.drawEllipse(QPointF(cx, cy), R, R)

        # 距离环（2/4/6/8m，超出当前缩放范围的不画）
        p.setFont(self._font(10))
        for ring in rc.get("rings_mm", [2000, 4000, 6000, 8000]):
            if ring > eff:
                continue
            rr = R * ring / eff
            arc = [polar(rr, a) for a in _frange(-half, half, 3.0)] if not full else None
            if arc:
                p.setPen(QPen(QColor(0, 229, 192, 34), 0.7))
                p.drawPolyline(QPolygonF(arc))
            else:
                p.setPen(QPen(QColor(0, 229, 192, 34), 0.7))
                p.drawEllipse(QPointF(cx, cy), rr, rr)
            # 环标度贴在 0° 方向
            p.setPen(QColor(0, 229, 192, 95))
            p.drawText(QPointF(cx + 4, cy - rr + 9), f"{ring / 1000:.0f}")

        # 角度刻度：每 15° 一小格
        p.setPen(QPen(QColor(0, 229, 192, 70), 0.7))
        a = -half
        while a <= half + 0.01:
            r0 = R if full else R
            p.drawLine(polar(r0, a), polar(r0 - 6 * s, a))
            a += 15.0
        p.setFont(self._font(9.5))
        p.setPen(QColor(0, 229, 192, 105))
        for lbl in (-half, 0.0, half):
            q = polar(R + 12 * s, lbl)
            p.drawText(QRectF(q.x() - 22, q.y() - 8, 44, 16),
                       int(Qt.AlignCenter), f"{lbl:+.0f}")

        # 扫描线（在扇面内往复）
        span = sweep if full else sweep
        sa = (self._scan_angle % span) - half
        p.setPen(QPen(QColor(0, 229, 192, 90), 1.1))
        p.drawLine(QPointF(cx, cy), polar(R, sa))

        # 点云
        now = time.time()
        p.setPen(Qt.NoPen)
        for i in range(360):
            b = self._bins[i]
            if b is None or now - self._bin_t[i] > 1.2:
                continue
            dist, _ = b
            if dist < rmin or dist > eff:
                continue
            ang = i if i <= 180 else i - 360
            if not full and abs(ang) > half:
                continue
            rr = R * dist / eff
            fade = max(0.25, 1.0 - dist / eff)
            p.setBrush(QColor(0, 229, 192, int(80 + 150 * fade)))
            p.drawEllipse(polar(rr, ang), 1.7 * s, 1.7 * s)

        # 视觉锁定目标
        vis = self.state.get("vision")
        if vis and self.state.get("vision_age", 9e9) < 1.5:
            track = vis.get("track") or {}
            yaw, dist = track.get("yaw_deg"), track.get("dist_mm")
            if yaw is not None and dist:
                q = polar(min(R, R * float(dist) / eff), float(yaw))
                p.setPen(QPen(self.c_crit, 1.5))
                p.setBrush(Qt.NoBrush)
                p.drawRect(QRectF(q.x() - 4 * s, q.y() - 4 * s, 8 * s, 8 * s))

        # 标度
        p.setFont(self._font(10.5))
        p.setPen(QColor(0, 229, 192, 165))
        p.drawText(QPointF(cx - R, cy + 20 * s), f"RNG {eff / 1000:.0f}m")
        p.drawText(QPointF(cx - R, cy + 34 * s), f"ZOOM ×{zoom:.1f}")
        p.setPen(QColor(0, 229, 192, 90))
        p.drawText(QPointF(cx - R, cy + 48 * s), f"扫描 {sweep:.0f}°")

        if self.state.get("points_age", 9e9) > 1.5:
            p.setFont(self._font(12, False, False))
            p.setPen(QColor(150, 160, 168, 165))
            p.drawText(QRectF(cx - 60 * s, cy - 10 * s, 120 * s, 20 * s),
                       int(Qt.AlignCenter), "无雷达数据")

    # ═══════════════════════ 左下：稳定器（车辆俯视）═══════════════════════

    def _paint_stabilizer(self, p: QPainter) -> None:
        """圆形面板里画车辆俯视 + 双同心环，下面三行：档位 / 转速 / 速度。

        车头按 IMU yaw 旋转，炮管再叠加视觉给出的目标偏角；
        圆环固定不动，代表车体坐标系。
        """
        s = self.scale
        R = 92.0 * s
        cx = R + 40.0 * s
        cy = self.H - 138.0 * s - R
        rect = QRectF(cx - R, cy - R, R * 2, R * 2)

        m = self.state.get("master")
        age = self.state.get("master_age", 9e9)
        live = m is not None and age < 2.0
        vis = self.state.get("vision")
        vis_live = bool(vis) and self.state.get("vision_age", 9e9) < 1.5

        p.setBrush(QColor(10, 18, 26, 155))
        p.setPen(QPen(QColor(120, 180, 210, 55), 1.0))
        p.drawEllipse(rect)
        p.setBrush(Qt.NoBrush)
        p.setPen(QPen(QColor(120, 180, 210, 65), 1.0))
        p.drawEllipse(QPointF(cx, cy), R * 0.96, R * 0.96)
        p.setPen(QPen(QColor(120, 180, 210, 95), 1.0))
        p.drawEllipse(QPointF(cx, cy), R * 0.58, R * 0.58)

        yaw = (m.get("imu_yaw_0_1", 0) / 10.0) if live else 0.0
        turret = float(((vis.get("track") or {}).get("yaw_deg") or 0.0)) if vis_live else 0.0

        body = QColor(34, 46, 56, 245) if live else QColor(52, 58, 64, 210)
        edge = QColor(150, 205, 225, 225) if live else QColor(110, 120, 130, 150)

        p.save()
        p.translate(cx, cy)
        p.rotate(-yaw)          # IMU yaw 逆时针为正，屏幕顺时针为正
        bw, bh = R * 0.42, R * 0.80

        p.setPen(QPen(edge, 0.9))
        p.setBrush(QColor(18, 26, 32, 245))
        p.drawRoundedRect(QRectF(-bw * 0.98, -bh / 2, bw * 0.30, bh), 2 * s, 2 * s)
        p.drawRoundedRect(QRectF(bw * 0.68, -bh / 2, bw * 0.30, bh), 2 * s, 2 * s)

        p.setBrush(body)
        p.setPen(QPen(edge, 1.2))
        p.drawRoundedRect(QRectF(-bw * 0.66, -bh / 2, bw * 1.32, bh), 3 * s, 3 * s)

        p.setBrush(QColor(196, 218, 230, 250) if live else QColor(112, 122, 132, 210))
        p.setPen(QPen(QColor(20, 28, 34, 220), 1.0))
        p.drawEllipse(QPointF(0, 0), bw * 0.44, bw * 0.44)

        p.save()
        p.rotate(turret)        # 炮管指向目标（视觉偏角），没有视觉就与车头同向
        p.setPen(QPen(QColor(236, 248, 253, 245), max(2.0, 2.8 * s),
                      Qt.SolidLine, Qt.RoundCap))
        p.drawLine(QPointF(0, -bw * 0.40), QPointF(0, -bh * 0.98))
        p.restore()
        p.restore()

        p.setFont(self._font(12.5, False, False))
        p.setPen(QColor(200, 222, 232, 200))
        p.drawText(QPointF(rect.left() + 2, cy - R - 9 * s), "稳定器")

        # 乘员位没有对应数据，改成"识别到的目标数"——同样代表可用资源
        nt = len(vis.get("targets") or []) if vis_live else 0
        ix, iy = rect.left() + 14 * s, cy + R * 0.42
        p.setPen(QPen(QColor(170, 220, 235, 175), 1.2))
        p.setBrush(Qt.NoBrush)
        p.drawEllipse(QPointF(ix, iy - 5 * s), 3.0 * s, 3.0 * s)
        p.drawArc(QRectF(ix - 6 * s, iy - 2 * s, 12 * s, 11 * s), 15 * 16, 150 * 16)
        p.setFont(self._font(12.5, True, True))
        p.setPen(QColor(205, 228, 238, 205))
        p.drawText(QPointF(ix + 14 * s, iy + 5 * s), str(nt))

        # 三行全部取真车字段，不做任何换算模型：
        #   档位 = 电机 PWM 占空比上限（主控上报）
        #   转速 = 左右电机转速均值
        #   速度 = 主控上报的真实车速（mm/s → km/h 只是单位换算）
        rpm = int((abs(m.get("rpm_l", 0)) + abs(m.get("rpm_r", 0))) / 2) if live else 0
        gear = m.get("gear") if live else None
        spd = m.get("speed_mmps") if live else None
        spd_txt = f"{spd * 3.6 / 1000.0:.0f} km/h" if spd is not None else "--"

        rows = [("档位", f"{gear}" if gear is not None else "--"),
                ("转速", f"{rpm}" if live else "--"),
                ("速度", spd_txt)]
        y = rect.bottom() + 20 * s
        for i, (k, v) in enumerate(rows):
            yy = y + i * 17 * s
            self._shadow_text(p, rect.left(), yy, k, QColor(175, 195, 205, 190), 12)
            self._shadow_text(p, rect.left() + 36 * s, yy, v,
                              self.c_text if live else self.c_dim, 12.5, True)

    # ═══════════════════════ 靶标图标 ═══════════════════════

    def _tower_icon(self, p: QPainter, cx: float, cy: float, col: QColor) -> None:
        """高地靶：防御塔线框（底座 + 塔身 + 平台 + 尖顶）。"""
        w, h = 18.0, 28.0
        p.setPen(QPen(col, 1.0))
        p.setBrush(Qt.NoBrush)
        p.drawRect(QRectF(cx - w * 0.55, cy + h * 0.30, w * 1.1, 3.0))
        p.drawPolygon(QPolygonF([
            QPointF(cx - w * 0.30, cy + h * 0.30),
            QPointF(cx - w * 0.18, cy - h * 0.10),
            QPointF(cx + w * 0.18, cy - h * 0.10),
            QPointF(cx + w * 0.30, cy + h * 0.30),
        ]))
        p.drawRect(QRectF(cx - w * 0.36, cy - h * 0.20, w * 0.72, 3.2))
        p.drawPolyline(QPolygonF([
            QPointF(cx - w * 0.36, cy - h * 0.20),
            QPointF(cx, cy - h * 0.52),
            QPointF(cx + w * 0.36, cy - h * 0.20),
        ]))

    def _base_icon(self, p: QPainter, cx: float, cy: float, col: QColor) -> None:
        """基地靶：主塔 + 顶部水晶。"""
        w, h = 24.0, 34.0
        p.setPen(QPen(col, 1.1))
        p.setBrush(Qt.NoBrush)
        p.drawRect(QRectF(cx - w * 0.58, cy + h * 0.30, w * 1.16, 3.4))
        p.drawPolygon(QPolygonF([
            QPointF(cx - w * 0.34, cy + h * 0.30),
            QPointF(cx - w * 0.20, cy - h * 0.06),
            QPointF(cx + w * 0.20, cy - h * 0.06),
            QPointF(cx + w * 0.34, cy + h * 0.30),
        ]))
        p.drawRect(QRectF(cx - w * 0.40, cy - h * 0.16, w * 0.80, 3.2))
        p.drawPolygon(QPolygonF([
            QPointF(cx, cy - h * 0.52),
            QPointF(cx + w * 0.18, cy - h * 0.34),
            QPointF(cx, cy - h * 0.16),
            QPointF(cx - w * 0.18, cy - h * 0.34),
        ]))

    def _hp_bar(self, p: QPainter, x: float, y: float, w: float,
                remain: int, total: int, col: QColor) -> None:
        """血条按片数分段：剩几片就亮几段。"""
        seg = (w - (total - 1) * 2.0) / total
        for i in range(total):
            p.setPen(Qt.NoPen)
            p.setBrush(col if i < remain else QColor(58, 69, 80))
            p.drawRect(QRectF(x + i * (seg + 2.0), y, seg, 3.2))

    # ═══════════════════════ 顶栏：比分 / 靶标 / 倒计时 ═══════════════════════

    def _paint_top_bar(self, p: QPainter) -> None:
        s = self.scale
        g = self.state.get("game")
        h = 66.0 * s

        p.setPen(Qt.NoPen)
        # 半透明：让画面透出来，不要一条死灰的横条
        p.setBrush(QColor(8, 14, 18, int(self.ui.get("topbar_alpha", 96))))
        p.drawRect(QRectF(0, 0, self.W, h))
        p.setPen(QPen(QColor(255, 255, 255, 20), 0.8))
        p.drawLine(QPointF(0, h), QPointF(self.W, h))

        cy = 38.0 * s
        dim = QColor(70, 82, 92)
        red = QColor("#E06A5F")
        blue = QColor("#6FA8DC")

        # 阶段（顶栏最上一行小字，放在图标上方，避免压住靶标）
        if g:
            p.setFont(self._font(10, False, False))
            p.setPen(QColor(152, 166, 176, 200))
            p.drawText(QRectF(24 * s, 10 * s, 240 * s, 12 * s),
                       int(Qt.AlignLeft), f"R{g.round_no} · {g.stage_text()}")

        # ── 红方靶标紧贴比分（整体居中，两侧留空给画面）──
        m = self.W / 2
        hr = g.high_remaining("red") if g else 0
        self._tower_icon(p, m - 242 * s, cy - 2 * s, red if hr else dim)
        self._hp_bar(p, m - 257 * s, 58 * s, 30 * s, hr, 4, red)

        ru = bool(g and g.base_unlocked("red"))
        br = g.base_remaining("red") if (g and ru) else 0
        self._base_icon(p, m - 190 * s, cy - 4 * s, red if (ru and br) else dim)
        self._hp_bar(p, m - 208 * s, 62 * s, 36 * s, br, 4,
                     red if ru else QColor(58, 69, 80))

        # ── 比分 ──
        p.setFont(self._font(21, True, True))
        p.setPen(red)
        p.drawText(QRectF(m - 210 * s, 12 * s, 90 * s, 30 * s),
                   int(Qt.AlignRight | Qt.AlignVCenter),
                   str(g.red_score if g else 0))
        p.setPen(blue)
        p.drawText(QRectF(m + 120 * s, 12 * s, 90 * s, 30 * s),
                   int(Qt.AlignLeft | Qt.AlignVCenter),
                   str(g.blue_score if g else 0))

        # ── 中央倒计时 ──
        cw, chh = 66 * s, 34 * s
        cxm = self.W / 2
        p.setBrush(QColor(30, 38, 46, 238))
        p.setPen(QPen(QColor(76, 92, 104, 220), 1.0))
        p.drawPolygon(QPolygonF([
            QPointF(cxm - cw / 2, 29 * s), QPointF(cxm, 29 * s - chh / 2),
            QPointF(cxm + cw / 2, 29 * s), QPointF(cxm, 29 * s + chh / 2),
        ]))
        p.setFont(self._font(9.5, False, False))
        p.setPen(QColor(138, 154, 164, 220))
        p.drawText(QRectF(cxm - cw / 2, 13 * s, cw, 12 * s), int(Qt.AlignCenter), "剩余")
        rem = g.remaining_now_ms() if g else 0
        mm, ss = divmod(max(0, rem) // 1000, 60)
        p.setFont(self._font(15, True, True))
        p.setPen(self.c_text if rem > 30000 else self.c_crit)
        p.drawText(QRectF(cxm - cw / 2, 22 * s, cw, 18 * s),
                   int(Qt.AlignCenter), f"{mm:02d}:{ss:02d}")

        # ── 蓝方靶标（与红方镜像）──
        bx = m + 190 * s
        hx = m + 242 * s
        bu = bool(g and g.base_unlocked("blue"))
        bbr = g.base_remaining("blue") if (g and bu) else 0
        self._base_icon(p, bx, cy - 4 * s, blue if (bu and bbr) else dim)
        self._hp_bar(p, bx - 18 * s, 62 * s, 36 * s, bbr, 4,
                     blue if bu else QColor(58, 69, 80))

        hb = g.high_remaining("blue") if g else 0
        self._tower_icon(p, hx, cy - 2 * s, blue if hb else dim)
        self._hp_bar(p, hx - 15 * s, 58 * s, 30 * s, hb, 4, blue)

    # ═══════════════════════ 底部：武器栏 ═══════════════════════

    WEAPONS = [
        ("aim_manual", "手动瞄准", "cross"),
        ("auto_high", "自动高地靶", "high"),
        ("auto_base", "自动基地靶", "base"),
        ("auto_move", "自动移动靶", "move"),
        ("reload", "装填弹药", "ammo"),
        ("gear", "起落架", "gear"),
        ("restart", "重启电控", "power"),
    ]

    def _weapon_icon(self, p: QPainter, kind: str, cx: float, cy: float,
                     col: QColor) -> None:
        p.setPen(QPen(col, 1.1))
        p.setBrush(Qt.NoBrush)
        if kind == "cross":
            p.drawEllipse(QPointF(cx, cy), 5.0, 5.0)
            for dx, dy in ((0, -8), (0, 8), (-8, 0), (8, 0)):
                p.drawLine(QPointF(cx + dx * 0.55, cy + dy * 0.55), QPointF(cx + dx, cy + dy))
        elif kind == "high":
            p.drawPolygon(QPolygonF([QPointF(cx - 4, cy + 5), QPointF(cx - 2, cy - 2),
                                     QPointF(cx + 2, cy - 2), QPointF(cx + 4, cy + 5)]))
            p.drawLine(QPointF(cx, cy - 2), QPointF(cx, cy - 7))
            p.drawRect(QRectF(cx + 5, cy - 8, 5, 5))
        elif kind == "base":
            p.drawPolygon(QPolygonF([QPointF(cx - 5, cy + 5), QPointF(cx - 3, cy - 1),
                                     QPointF(cx + 3, cy - 1), QPointF(cx + 5, cy + 5)]))
            p.drawPolygon(QPolygonF([QPointF(cx, cy - 9), QPointF(cx + 3, cy - 5),
                                     QPointF(cx, cy - 1), QPointF(cx - 3, cy - 5)]))
        elif kind == "move":
            p.drawEllipse(QPointF(cx - 2, cy), 4, 4)
            p.drawLine(QPointF(cx + 4, cy), QPointF(cx + 9, cy))
            p.drawLine(QPointF(cx + 9, cy), QPointF(cx + 6, cy - 2))
            p.drawLine(QPointF(cx + 9, cy), QPointF(cx + 6, cy + 2))
        elif kind == "ammo":
            p.drawRect(QRectF(cx - 4, cy - 5, 6, 10))
            p.drawPolygon(QPolygonF([QPointF(cx + 2, cy - 1), QPointF(cx + 7, cy),
                                     QPointF(cx + 2, cy + 1)]))
        elif kind == "gear":
            p.drawEllipse(QPointF(cx, cy + 4), 3.5, 3.5)
            p.drawLine(QPointF(cx, cy + 1), QPointF(cx, cy - 5))
            p.drawLine(QPointF(cx - 5, cy - 5), QPointF(cx + 5, cy - 5))
        elif kind == "power":
            p.drawEllipse(QPointF(cx, cy + 1), 5, 5)
            p.drawLine(QPointF(cx, cy - 6), QPointF(cx, cy))

    def _paint_weapons(self, p: QPainter) -> None:
        s = self.scale
        keys = self.cfg.get("keys", {})
        aim = self.state.get("aim_mode", "aim_manual")
        hold = self.state.get("hold") or {}

        cw, chh, gap = 48 * s, 34 * s, 4 * s
        n = len(self.WEAPONS)
        total = n * cw + (n - 1) * gap
        x0 = max(150 * s, (self.W - total) / 2)
        y0 = self.H - chh - 12 * s

        ammo = self.state.get("ammo", 0)
        mode_name = next((nm for k, nm, _ in self.WEAPONS if k == aim), "手动瞄准")
        self._shadow_text(p, x0, y0 - 8 * s,
                          f"瞄准模式：{mode_name}    弹药 {ammo:02d}/24",
                          QColor(168, 188, 198, 205), 11.5, False, False)

        for i, (key, name, icon) in enumerate(self.WEAPONS):
            r = QRectF(x0 + i * (cw + gap), y0, cw, chh)
            active = (key == aim)
            dangerous = (key == "restart")
            border = (QColor(0, 229, 192, 220) if active else
                      (QColor(122, 59, 50, 210) if dangerous else QColor(46, 56, 64, 200)))
            p.setBrush(QColor(24, 34, 40, 228) if active else QColor(18, 24, 29, 212))
            p.setPen(QPen(border, 1.2 if active else 0.8))
            p.drawRoundedRect(r, 2, 2)

            p.setFont(self._font(10.5, True, True))
            p.setPen(QColor(159, 216, 204) if active else QColor(126, 143, 154))
            p.drawText(QRectF(r.x(), r.y() + 1 * s, r.width(), 13 * s),
                       int(Qt.AlignCenter), str(keys.get(key, 0)))

            icol = QColor(224, 131, 122) if dangerous else QColor(220, 232, 238)
            self._weapon_icon(p, icon, r.center().x(), r.y() + 23 * s, icol)

            if key in hold and hold.get(key, 0) > 0:
                prog = min(1.0, hold[key])
                p.setPen(Qt.NoPen)
                p.setBrush(QColor(255, 77, 79, 225))
                p.drawRect(QRectF(r.x() + 2, r.bottom() - 3.5 * s,
                                  (r.width() - 4) * prog, 2.5 * s))

        # 断电长按：不在武器栏里，单独给一条进度
        po = hold.get("power_off")
        if po:
            bw = 240 * s
            bx = self.W / 2 - bw / 2
            by = self.H - 96 * s
            p.setFont(self._font(12, True, False))
            p.setPen(QColor(255, 77, 79, 238))
            p.drawText(QRectF(bx, by - 18 * s, bw, 16 * s),
                       int(Qt.AlignCenter), "长按 J 断电")
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(42, 24, 24, 220))
            p.drawRect(QRectF(bx, by, bw, 6 * s))
            p.setBrush(QColor(255, 77, 79, 240))
            p.drawRect(QRectF(bx, by, bw * min(1.0, po), 6 * s))

    # ═══════════════════════ Toast ═══════════════════════

    def _paint_toasts(self, p: QPainter) -> None:
        items = self.state.get("toasts") or []
        if not items:
            return
        s = self.scale
        w, hh, gap = 244.0 * s, 24.0 * s, 5.0 * s
        x = self.W / 2 - w / 2
        y = 74.0 * s
        tones = {"good": QColor(0, 229, 192), "warn": QColor(232, 192, 90),
                 "bad": QColor(255, 77, 79), "info": QColor(150, 170, 182)}
        now = time.time()
        for (text, detail, tone, t0) in items[-4:]:
            age = now - t0
            alpha = 1.0 if age <= 3.0 else max(0.0, 1.0 - (age - 3.0) / 0.6)
            if alpha <= 0:
                continue
            col = tones.get(tone, tones["info"])
            p.setBrush(QColor(10, 16, 20, int(215 * alpha)))
            p.setPen(QPen(QColor(col.red(), col.green(), col.blue(), int(95 * alpha)), 0.8))
            p.drawRoundedRect(QRectF(x, y, w, hh), 3, 3)
            p.setPen(Qt.NoPen)
            p.setBrush(QColor(col.red(), col.green(), col.blue(), int(230 * alpha)))
            p.drawRect(QRectF(x, y, 3 * s, hh))

            p.setFont(self._font(11.5, False, False))
            p.setPen(QColor(216, 228, 234, int(235 * alpha)))
            p.drawText(QRectF(x + 12 * s, y, w - 62 * s, hh),
                       int(Qt.AlignLeft | Qt.AlignVCenter), text)
            if detail:
                p.setFont(self._font(11.5, True, True))
                p.setPen(QColor(col.red(), col.green(), col.blue(), int(240 * alpha)))
                p.drawText(QRectF(x + w - 58 * s, y, 48 * s, hh),
                           int(Qt.AlignRight | Qt.AlignVCenter), detail)
            y += hh + gap

    # ═══════════════════════ 告警 ═══════════════════════

    def _paint_alerts(self, p: QPainter) -> None:
        s = self.scale
        st = self.state
        msgs: list[tuple[str, QColor]] = []

        if not st.get("frame_ready"):
            msgs.append(("等待视频流", self.c_warn))
        elif st.get("stats_video", {}).get("connected") is False:
            msgs.append(("视频中断", self.c_crit))

        m = st.get("master")
        if m and st.get("master_age", 9e9) < 2.0:
            if m.get("status", 0) & 0x01:
                msgs.append(("急停", self.c_crit))
            temp = m.get("temp_0_1c", 0) / 10.0
            if temp >= self.warn["temp_crit"]:
                msgs.append(("过温", self.c_crit))
            elif temp >= self.warn["temp_warn"]:
                msgs.append(("温度偏高", self.c_warn))

        vis = st.get("vision")
        if vis and st.get("vision_age", 9e9) < 1.5:
            track = vis.get("track") or {}
            sd = track.get("dist_sigma_mm") or 0
            d = track.get("dist_mm") or 0
            if d and sd and sd / d > 0.25:
                msgs.append((f"测距不可信 {sd / d * 100:.0f}%", self.c_warn))

        if not msgs:
            return
        f = self._font(13, True, False)
        p.setFont(f)
        fm = p.fontMetrics()
        boxes = [(t, c, fm.width(t) + 26 * s) for t, c in msgs]
        total = sum(w for _, _, w in boxes) + (len(boxes) - 1) * 8 * s
        left = self.W / 2 - total / 2
        y = self.H - 78 * s
        for txt, col, bw in boxes:
            r = QRectF(left, y - 16 * s, bw, 24 * s)
            p.setPen(QPen(QColor(col.red(), col.green(), col.blue(), 115), 1.0))
            p.setBrush(QColor(0, 0, 0, 155))
            p.drawRoundedRect(r, 3, 3)
            p.setPen(col)
            p.drawText(r, int(Qt.AlignCenter), txt)
            left += bw + 8 * s
