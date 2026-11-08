package com.robomaster.control.vision.detect

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 结构张量（Structure Tensor，纯 Kotlin）。
 *
 * 数学依据（《传统视觉与深度学习视觉的数学基础》§7.4 / §7.5）：
 *
 *   J = Σ_窗口 Gσ * (∇I)(∇I)ᵀ = [[Jxx, Jxy], [Jxy, Jyy]]
 *
 * 它是**邻域内梯度向量的协方差矩阵（未去均值）**：
 * Jxx、Jyy 是两个方向梯度的方差，Jxy 是协方差（"x 方向梯度大时 y 方向是否也大"）。
 * 2×2 对称半正定，特征值 λ1 ≥ λ2 ≥ 0 给出三种形状：
 *
 * | 特征值关系 | 含义 | 视觉含义 |
 * |---|---|---|
 * | λ1 ≈ λ2 ≈ 0 | 平坦 | 无结构（背景/纯色区） |
 * | λ1 ≫ λ2 ≈ 0 | 秩 1 | 单边缘（方向明确） |
 * | λ1 ≥ λ2 ≫ 0 | 秩 2 | 角点/斑点（各向同性高能量） |
 *
 * 主方向 θ = ½·arctan2(2Jxy, Jxx − Jyy)，值域 (−90°, 90°]——注意它是**梯度方向模 180°**，
 * 与"边缘走向"差 90°；相干度 C = ((λ1−λ2)/(λ1+λ2))² ∈ [0,1]。
 *
 * ## 本项目的三个用途
 *
 * 1. **形状校验**：矩形面板/圆形靶应当是"单边缘主导"（λ1 ≫ λ2）；
 *    各向同性（λ1≈λ2）的连通域更像噪声团或纹理，可用于降误检；
 * 2. **组方向估计**：高地靶 4 片面板的朝向需要平均（[meanOrientationPeriodPi]）；
 * 3. **角度平均的正确做法**：角度是周期量，直接算术平均会在 ±90° 处跳变——
 *    必须转成"双角"(cos2θ, sin2θ) 再平均，最后 atan2 转回（§7.5.3 明确点出的陷阱，
 *    与 §2.6 的色相环形是同一种数学现象）。
 */
object StructureTensor {

    data class T(val jxx: Double, val jyy: Double, val jxy: Double) {
        val trace: Double get() = jxx + jyy
        private val det: Double get() = jxx * jyy - jxy * jxy
        private val disc: Double get() = sqrt(((trace * trace) / 4.0 - det).coerceAtLeast(0.0))

        /** 大特征值 λ1 */
        val lambda1: Double get() = trace / 2.0 + disc

        /** 小特征值 λ2 */
        val lambda2: Double get() = (trace / 2.0 - disc).coerceAtLeast(0.0)

        /** 相干度 ((λ1−λ2)/(λ1+λ2))²：≈1 单边缘，≈0 各向同性 */
        val coherence: Double
            get() {
                val s = lambda1 + lambda2
                if (s <= 1e-9) return 0.0
                val r = (lambda1 - lambda2) / s
                return r * r
            }

        /** 主梯度方向（度，(−90, 90]，模 180°） */
        val orientationDeg: Double
            get() = Math.toDegrees(0.5 * atan2(2.0 * jxy, jxx - jyy))

        /** 能量（λ1+λ2，梯度强度平方和） */
        val energy: Double get() = lambda1 + lambda2

        /** 形状分类（仅按特征值比值，绝对强度需调用方另设阈值） */
        fun shape(): Shape {
            if (lambda1 <= 1e-9) return Shape.FLAT
            val ratio = lambda2 / lambda1
            return if (ratio < 0.05) Shape.EDGE else Shape.CORNER
        }

        /** 各向异性 = 1 − λ2/λ1 ∈ [0,1]：1 纯边缘，0 各向同性 */
        val anisotropy: Double
            get() = if (lambda1 <= 1e-9) 0.0 else 1.0 - lambda2 / lambda1
    }

    enum class Shape { FLAT, EDGE, CORNER }

    /**
     * 在 (cx, cy) 周围 radius 窗口内计算结构张量（灰度图输入）。
     */
    fun compute(
        gray: ByteArray, w: Int, h: Int,
        cx: Double, cy: Double, radius: Int = 4
    ): T = computeWindow(w, h, cx, cy, radius) { x, y ->
        (gray[(y.coerceIn(0, h - 1)) * w + x.coerceIn(0, w - 1)].toInt() and 0xFF).toDouble()
    }

    /**
     * 在 (cx, cy) 周围 radius 窗口内计算结构张量（RGB 输入，内部按 0.299/0.587/0.114 取灰度）。
     */
    fun computeRgb(
        rgb: ByteArray, w: Int, h: Int,
        cx: Double, cy: Double, radius: Int = 4
    ): T = computeWindow(w, h, cx, cy, radius) { x, y ->
        val xx = x.coerceIn(0, w - 1)
        val yy = y.coerceIn(0, h - 1)
        val i = (yy * w + xx) * 3
        0.299 * (rgb[i].toInt() and 0xFF) +
                0.587 * (rgb[i + 1].toInt() and 0xFF) +
                0.114 * (rgb[i + 2].toInt() and 0xFF)
    }

    private inline fun computeWindow(
        w: Int, h: Int, cx: Double, cy: Double, radius: Int,
        gray: (Int, Int) -> Double
    ): T {
        val r = radius.coerceIn(1, 32)
        val ix = cx.toInt()
        val iy = cy.toInt()
        var jxx = 0.0
        var jyy = 0.0
        var jxy = 0.0
        for (y in iy - r..iy + r) {
            if (y < 1 || y >= h - 1) continue
            for (x in ix - r..ix + r) {
                if (x < 1 || x >= w - 1) continue
                // Sobel 梯度（3×3）
                val a = gray(x - 1, y - 1); val b = gray(x, y - 1); val c = gray(x + 1, y - 1)
                val d = gray(x - 1, y); val f = gray(x + 1, y)
                val g = gray(x - 1, y + 1); val hh = gray(x, y + 1); val i2 = gray(x + 1, y + 1)
                val gx = (c + 2.0 * f + i2) - (a + 2.0 * d + g)
                val gy = (g + 2.0 * hh + i2) - (a + 2.0 * b + c)
                jxx += gx * gx
                jyy += gy * gy
                jxy += gx * gy
            }
        }
        val n = ((2 * r + 1) * (2 * r + 1)).toDouble()
        // 归一到"每像素平均的梯度能量"，便于跨窗口大小比较
        return T(jxx / n, jyy / n, jxy / n)
    }

    /**
     * 周期为 180° 的角度平均（双角向量平均，§7.5.3）。
     *
     * @param anglesDeg 各角度（度，任意值域）
     * @return 平均方向，(−90, 90]
     */
    fun meanOrientationPeriodPi(anglesDeg: DoubleArray): Double {
        if (anglesDeg.isEmpty()) return 0.0
        var sx = 0.0
        var sy = 0.0
        for (a in anglesDeg) {
            val r = Math.toRadians(2.0 * a)
            sx += cos(r)
            sy += sin(r)
        }
        if (hypot(sx, sy) < 1e-12) return 0.0
        var deg = Math.toDegrees(0.5 * atan2(sy, sx))
        while (deg <= -90.0) deg += 180.0
        while (deg > 90.0) deg -= 180.0
        return deg
    }

    /**
     * 方向一致性（双角合矢量长度/样本数，0~1）。
     * 1 = 所有方向一致；0 = 在 180° 周期上均匀分布（方向无主导）。
     */
    fun orientationConsistency(anglesDeg: DoubleArray): Double {
        if (anglesDeg.isEmpty()) return 0.0
        var sx = 0.0
        var sy = 0.0
        for (a in anglesDeg) {
            val r = Math.toRadians(2.0 * a)
            sx += cos(r)
            sy += sin(r)
        }
        return hypot(sx, sy) / anglesDeg.size
    }
}
