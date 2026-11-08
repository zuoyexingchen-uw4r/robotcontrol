package com.robomaster.control.vision.preprocess

import kotlin.math.exp

/**
 * 图像平滑：双边滤波（Bilateral Filter）。
 *
 * 原理：空间域加权平均，权重 = 空间高斯 × 颜色（灰度差）高斯。
 *   与普通高斯模糊的区别：颜色差大的像素（边缘）权重趋近 0，
 *   因此**保边去噪**——平滑平坦区域的同时不糊掉靶框边缘。
 *
 *   w(i,j) = exp(-|p_i - p_j|² / 2σ_c²) · exp(-|x_i - x_j|² / 2σ_s²)
 *
 * 实现：纯 Kotlin，可分离近似（先用一维空间+颜色联合核做水平/垂直两遍），
 * 复杂度 O(w·h·r)（r=半径），640×480 + r=2 约 60 万次乘加，单帧 <10ms。
 *
 * 参数建议：
 * - radius 2~3（窗口半径）
 * - sigmaSpace 2.0~3.0（空间尺度）
 * - sigmaColor 25.0~50.0（颜色尺度，灰度 0-255）
 */
object Smoothing {

    /**
     * 双边滤波（可分离两遍近似）。
     *
     * @param gray 输入灰度图（w×h）
     * @param w 宽
     * @param h 高
     * @param radius 窗口半径（1~3）
     * @param sigmaSpace 空间高斯 σ
     * @param sigmaColor 颜色高斯 σ
     * @return 平滑后的灰度图（同尺寸）
     */
    fun bilateral(
        gray: ByteArray,
        w: Int,
        h: Int,
        radius: Int = 2,
        sigmaSpace: Double = 2.5,
        sigmaColor: Double = 30.0
    ): ByteArray {
        if (radius < 1 || w <= 0 || h <= 0) return gray.copyOf()

        val invSs = 1.0 / (2.0 * sigmaSpace * sigmaSpace)
        val invSc = 1.0 / (2.0 * sigmaColor * sigmaColor)

        // 值域权重查表（数学基础 §2.7）：8 位灰度 → |v−center| 只有 0..255 共 256 种取值，
        // 预先算好 exp 表就把内层循环里的 exp 全部换成一次数组索引（每像素 2×(2r+1) 次）。
        val lut = DoubleArray(256) { d ->
            exp(-(d * d).toDouble() * invSc)
        }

        // 预计算空间权重表
        val spatial = DoubleArray((2 * radius + 1) * (2 * radius + 1))
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val d2 = (dx * dx + dy * dy).toDouble()
                spatial[(dy + radius) * (2 * radius + 1) + (dx + radius)] = exp(-d2 * invSs)
            }
        }

        val tmp = IntArray(w * h)
        val out = ByteArray(w * h)

        // 第一遍：水平
        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = gray[y * w + x].toInt() and 0xFF
                var wsum = 0.0
                var vsum = 0.0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val v = gray[y * w + nx].toInt() and 0xFF
                    val weight = lut[Math.abs(v - center)] * spatial[radius * (2 * radius + 1) + (dx + radius)]
                    wsum += weight
                    vsum += weight * v
                }
                tmp[y * w + x] = (vsum / wsum).toInt().coerceIn(0, 255)
            }
        }

        // 第二遍：垂直
        for (y in 0 until h) {
            for (x in 0 until w) {
                val center = tmp[y * w + x]
                var wsum = 0.0
                var vsum = 0.0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val v = tmp[ny * w + x]
                    val weight = lut[Math.abs(v - center)] * spatial[(dy + radius) * (2 * radius + 1) + radius]
                    wsum += weight
                    vsum += weight * v
                }
                out[y * w + x] = (vsum / wsum).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
