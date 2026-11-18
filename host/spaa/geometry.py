"""spaa.geometry — 相机模型与地面等距环投影。

用途：把「地面上距离 r 的一圈点」投影到画面上，得到测距仪的等距弧线。
这不是装饰：同一圈上的目标，距离就是同一个值，瞄具可以直接读距离。

坐标系（OpenCV 习惯）：x 向右、y 向下、z 向前，单位为 mm。
相机装在车体上，离地 `mount_h_mm`，光轴下倾 `mount_pitch_deg`。

投影：
    p_world = (X, h, Z)                     # 相对相机，未加安装角
    p_cam   = Rx(θ) · p_world               # θ = 下倾角
    u = fx·x/z + cx,  v = fy·y/z + cy

θ 的符号用「远处地面出现在画面上方」校验：θ>0 时 y_c = h·cosθ - Z·sinθ，
Z 越大 y_c 越负 → v 越小 → 往上，符合低头看远的情形。
"""
from __future__ import annotations

import math


class CameraModel:

    def __init__(self, cfg: dict, frame_w: int, frame_h: int):
        c = cfg["camera"]
        self.fx = float(c["fx"])
        self.fy = float(c["fy"])
        self.cx = float(c["cx"])
        self.cy = float(c["cy"])
        self.h = float(c["mount_h_mm"])
        self.pitch = math.radians(float(c["mount_pitch_deg"]))
        self.frame_w = frame_w
        self.frame_h = frame_h
        self._cos = math.cos(self.pitch)
        self._sin = math.sin(self.pitch)
        self.ring_step = float(c.get("ring_step_mm", 1000))
        self.ring_max = float(c.get("ring_max_mm", 8000))

    # ─────────── 基础投影 ───────────

    def project_world(self, x_right: float, z_forward: float):
        """地面点（横向 x、前向 z，mm）→ (u, v)；在相机后方返回 None。"""
        xc = x_right
        yc = self.h * self._cos - z_forward * self._sin
        zc = self.h * self._sin + z_forward * self._cos
        if zc <= 50.0:
            return None
        return (self.fx * xc / zc + self.cx,
                self.fy * yc / zc + self.cy)

    def project_cam(self, x: float, y: float, z: float):
        if z <= 50.0:
            return None
        return (self.fx * x / z + self.cx, self.fy * y / z + self.cy)

    # ─────────── 地面等距环 ───────────

    def ring_segments(self, radius_mm: float, step_deg: float = 3.0):
        """一圈等距地面的投影折线（按可见性分段）。"""
        segs: list[list[tuple[float, float]]] = []
        cur: list[tuple[float, float]] = []
        for i in range(int(360 / step_deg) + 1):
            phi = math.radians(i * step_deg)
            p = self.project_world(radius_mm * math.sin(phi),
                                   radius_mm * math.cos(phi))
            if p is None:
                if len(cur) > 1:
                    segs.append(cur)
                cur = []
            else:
                cur.append(p)
        if len(cur) > 1:
            segs.append(cur)
        return segs

    def azimuth_ray(self, phi_deg: float, r0_mm: float, r1_mm: float,
                    step_mm: float | None = None):
        """一条方位射线（沿地面），用于把环形网格连成透视网格。"""
        step = step_mm or max(200.0, (r1_mm - r0_mm) / 16.0)
        pts = []
        r = r0_mm
        while r <= r1_mm:
            phi = math.radians(phi_deg)
            p = self.project_world(r * math.sin(phi), r * math.cos(phi))
            if p is not None:
                pts.append(p)
            r += step
        return pts

    def ground_rings(self):
        """[(半径mm, 分段折线)]，从 ring_step 到 ring_max。"""
        out = []
        r = self.ring_step
        while r <= self.ring_max + 1:
            out.append((r, self.ring_segments(r)))
            r += self.ring_step
        return out

    # ─────────── 读数 ───────────

    def optical_axis_range_mm(self) -> float | None:
        """光轴（画面中心）打到地面的距离。下倾角为 0 时无交点。"""
        if self.pitch <= 0.001:
            return None
        return self.h / math.sin(self.pitch)

    def pitch_at(self, v: float) -> float:
        """画面纵坐标 v 对应的俯视角度（相对光轴，向下为正，度）。"""
        return math.degrees(math.atan2(v - self.cy, self.fy)) + math.degrees(self.pitch)

    def horizon_v(self) -> float:
        """地平线在画面上的 v 坐标（下倾角越大，地平线越高）。

        Z→∞ 时 y_c/z_c → -tanθ，所以 v = cy - fy·tanθ。
        """
        return self.cy - self.fy * math.tan(self.pitch)

    def fit(self, radius_mm: float, segs) -> bool:
        """该距离环是否有可见段（用于决定是否标数字）。"""
        for s in segs:
            for (u, v) in s:
                if -50 <= u <= self.frame_w + 50 and -50 <= v <= self.frame_h + 50:
                    return True
        return False
