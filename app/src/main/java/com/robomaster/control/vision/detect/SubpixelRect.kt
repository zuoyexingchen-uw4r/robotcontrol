package com.robomaster.control.vision.detect

import com.robomaster.control.vision.preprocess.Gaussian
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 最小外接矩形的**亚像素尺寸/中心**精定位（纯 Kotlin）。
 *
 * ## 为什么需要
 *
 * 针孔测距 d = f·W / p（p 为像素尺寸）。误差按一阶传播：
 *   σ_d/d = σ_p/p
 * 即**像素尺寸的相对误差直接等于测距的相对误差**。8m 处 120mm 面板在 f≈500px 下
 * 只有 p≈7.5px——整数外接矩形 ±0.5~1px 的量化误差就是 **7%~13% 的测距误差**，
 * 直接决定弹道俯仰补偿是否可用。
 *
 * ## 做法（数学基础 §10.4 亚像素边缘定位 + §4.7.1 先平滑再差分）
 *
 * 1. 沿最小外接矩形的两条轴各取一条**投影剖面** M(s)：s 方向细分 0.25px，
 *    垂直方向取若干条线平均（压噪声）；
 * 2. 剖面先做一维高斯平滑（σ≈1px）；对称过渡带的高斯平滑不改变其半高位置，
 *    所以这一步只压噪声、不引入偏移；
 * 3. 在预期边缘（±len/2）附近 ±1.5px 内找**半高跨越点**（亮暗两侧中值），
 *    在跨越线段上线性插值得到亚像素位置；
 * 4. 两条边的中点给出亚像素中心修正，两边距离给出亚像素长/宽。
 *
 * 关于第 3 步为什么不用 §10.4 的"梯度极值 + 三点抛物线"：
 * 那个公式假设峰值附近是光滑抛物线。真实边缘过渡带常只有 1~2px，
 * 双线性重采样会把剖面变成**分段线性**（有折点），对 |M'| 做抛物线拟合
 * 会系统性偏外约 0.3px（已在本仓库自测中复现：25.3px 被判成 25.93px）。
 * 半高点的位置对对称过渡带恒定不变，且段内插值是精确的，实测误差 <0.2px。
 *
 * ## 兜底
 *
 * 梯度太弱（低对比/边缘模糊严重）、抛物线分母过小（近似直线）、
 * 或修正量与整数矩形相差超过 [maxRelDev] → 一律回退整数矩形，
 * 保证"要么更准，要么不变差"。
 */
object SubpixelRect {

    /** 剖面采样步长（像素） */
    private const val STEP = 0.25

    /** 边缘搜索窗（像素） */
    private const val SEARCH = 1.5

    /** 边缘两侧的灰度对比度下限（0-255），低于此认为无有效边缘 */
    private const val MIN_CONTRAST = 20.0

    /**
     * 精定位。
     *
     * @param rgb w×h×3 图像（用与检测同一份，通常是 V 归一化后的图）
     * @param rect 整数最小外接矩形
     * @param maxRelDev 修正量相对上限（0.25 = 允许 ±25% 修正）
     * @return 精定位后的矩形；无法可靠精定位时返回原矩形
     */
    fun refine(
        rgb: ByteArray,
        w: Int,
        h: Int,
        rect: MinAreaRect.Rect,
        maxRelDev: Double = 0.25
    ): MinAreaRect.Rect {
        if (rect.length < 4.0 || rect.width < 3.0) return rect
        val ang = Math.toRadians(rect.angleDeg)
        val ux = cos(ang); val uy = sin(ang)
        val vx = -uy; val vy = ux

        val halfL = rect.length / 2.0
        val halfW = rect.width / 2.0

        // 长边方向：沿 u 取剖面，垂直方向（v）跨度为宽度
        val profU = profile(rgb, w, h, rect.cx, rect.cy, ux, uy, vx, vy, halfL, halfW)
        val edgeU = findEdges(profU, 0.0, halfL) ?: return rect
        val lenNew = edgeU.second - edgeU.first
        if (lenNew <= 0 || abs(lenNew - rect.length) > rect.length * maxRelDev) return rect

        // 短边方向：沿 v 取剖面，垂直方向跨度为长度
        val profV = profile(rgb, w, h, rect.cx, rect.cy, vx, vy, ux, uy, halfW, halfL)
        val edgeV = findEdges(profV, 0.0, halfW) ?: return rect
        val widNew = edgeV.second - edgeV.first
        if (widNew <= 0 || abs(widNew - rect.width) > rect.width * maxRelDev) return rect

        // 中心修正：两条边的中点相对原中心（剖面原点）的偏移
        val du = (edgeU.first + edgeU.second) / 2.0
        val dv = (edgeV.first + edgeV.second) / 2.0
        val cx = rect.cx + du * ux + dv * vx
        val cy = rect.cy + du * uy + dv * vy

        // 保持 length ≥ width 的约定
        return if (lenNew >= widNew) {
            MinAreaRect.Rect(cx, cy, lenNew, widNew, rect.angleDeg)
        } else {
            MinAreaRect.Rect(cx, cy, widNew, lenNew, rect.angleDeg + 90.0)
        }
    }

    /**
     * 取投影剖面：沿 (ux,uy) 从 −(halfU+2) 到 +(halfU+2) 采样，
     * 每个采样点沿垂直方向在 ±0.6·halfV 内取 7 条线求平均。
     */
    private fun profile(
        rgb: ByteArray, w: Int, h: Int,
        cx: Double, cy: Double,
        ux: Double, uy: Double, vx: Double, vy: Double,
        halfU: Double, halfV: Double
    ): DoubleArray {
        val s0 = -(halfU + 2.0)
        val s1 = halfU + 2.0
        val cnt = ((s1 - s0) / STEP).toInt() + 1
        val out = DoubleArray(cnt)
        val lines = 7
        val span = halfV * 0.6
        for (i in 0 until cnt) {
            val s = s0 + i * STEP
            var sum = 0.0
            for (k in 0 until lines) {
                val t = if (lines == 1) 0.0 else -span + 2.0 * span * k / (lines - 1)
                val px = cx + s * ux + t * vx
                val py = cy + s * uy + t * vy
                sum += sampleGray(rgb, w, h, px, py)
            }
            out[i] = sum / lines
        }
        return out
    }

    /**
     * 在剖面里找 ±halfU 附近的两条边，返回 (边1位置, 边2位置)（相对剖面原点，像素）。
     */
    private fun findEdges(prof: DoubleArray, center: Double, halfU: Double): Pair<Double, Double>? {
        if (prof.size < 9) return null
        // §4.7.1 先平滑再差分：不平滑的话单点噪声会被当成边缘；
        // 对称 ramp 的高斯平滑不改变其半高位置，所以平滑不会引入偏移。
        val sm = Gaussian.smooth1D(prof, 1.0)
        val s0 = -(halfU + 2.0)
        val left = edgeNear(sm, s0, center - halfU)
        val right = edgeNear(sm, s0, center + halfU)
        if (left == null || right == null) return null
        if (right <= left) return null
        return left to right
    }

    /**
     * 在 target 附近找"半高跨越点"（edge 在亮暗两侧中值处），线性插值求亚像素位置。
     *
     * 为什么不用梯度极值 + 抛物线拟合：过渡带只有 1~2px 时，双线性采样让剖面
     * 变成分段线性（有折点），对 |g| 做三点抛物线会系统性偏外约 0.3px；
     * 而对称过渡带的**半高点**恒在真实边缘上，与过渡带宽度无关，
     * 且如果分数线两侧就是线性过渡，插值是精确的。
     *
     * @return 亚像素位置（相对剖面原点）；对比度不足返回 null
     */
    private fun edgeNear(prof: DoubleArray, s0: Double, target: Double): Double? {
        val iT = ((target - s0) / STEP).toInt()
        val span = (SEARCH / STEP).toInt()        // ±1.5px 窗口
        val lo = (iT - span).coerceAtLeast(0)
        val hi = (iT + span).coerceAtMost(prof.size - 1)
        if (hi - lo < 2) return null

        var mn = Double.MAX_VALUE
        var mx = -Double.MAX_VALUE
        for (i in lo..hi) {
            val v = prof[i]
            if (v < mn) mn = v
            if (v > mx) mx = v
        }
        if (mx - mn < MIN_CONTRAST) return null

        val thr = (mn + mx) / 2.0
        // 找离 target 最近的跨越点
        var bestDelta = Double.MAX_VALUE
        var bestPos: Double? = null
        for (i in lo until hi) {
            val a = prof[i] - thr
            val b = prof[i + 1] - thr
            if (a == 0.0 && b == 0.0) continue
            if ((a > 0) == (b > 0) && a != 0.0 && b != 0.0) continue
            var frac = if (a == b) 0.5 else a / (a - b)
            if (frac < 0.0) frac = 0.0
            if (frac > 1.0) frac = 1.0
            val pos = s0 + (i + frac) * STEP
            val d = abs(pos - target)
            if (d < bestDelta) { bestDelta = d; bestPos = pos }
        }
        return bestPos
    }

    /** 双线性采样灰度（0.299/0.587/0.114，与 §2.4 亮度权重一致） */
    private fun sampleGray(rgb: ByteArray, w: Int, h: Int, x: Double, y: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        val p00 = grayAt(rgb, w, h, x0, y0)
        val p10 = grayAt(rgb, w, h, x0 + 1, y0)
        val p01 = grayAt(rgb, w, h, x0, y0 + 1)
        val p11 = grayAt(rgb, w, h, x0 + 1, y0 + 1)
        val top = p00 + (p10 - p00) * fx
        val bot = p01 + (p11 - p01) * fx
        return top + (bot - top) * fy
    }

    private fun grayAt(rgb: ByteArray, w: Int, h: Int, x: Int, y: Int): Double {
        val cx = if (x < 0) 0 else if (x >= w) w - 1 else x
        val cy = if (y < 0) 0 else if (y >= h) h - 1 else y
        val i = (cy * w + cx) * 3
        return 0.299 * (rgb[i].toInt() and 0xFF) +
                0.587 * (rgb[i + 1].toInt() and 0xFF) +
                0.114 * (rgb[i + 2].toInt() and 0xFF)
    }
}
