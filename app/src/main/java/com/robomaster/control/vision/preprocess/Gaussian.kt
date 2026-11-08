package com.robomaster.control.vision.preprocess

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 可分离高斯滤波（纯 Kotlin）。
 *
 * 数学依据（《传统视觉与深度学习视觉的数学基础》§4.2）：
 * - 高斯核可分离：Gσ(x,y) = Gσ(x)·Gσ(y)，二维卷积拆成两遍一维，复杂度 O(n·a) 而非 O(n·a²)；
 * - 支撑半径取 a = ⌈3σ⌉：a 之外的权重 < 0.3%，可截断而不影响结果；
 * - 自卷积闭合：Gσ₁ * Gσ₂ = G√(σ₁²+σ₂²)（方差相加，形状不变）——
 *   这是"多次轻微模糊 ≈ 一次较重模糊"的依据，也是本模块自测的核对方式；
 * - 频域仍是高斯（无旁瓣、无振铃），区别于均值滤波的 sinc 响应。
 *
 * 用途：结构张量的局部平均窗、亚像素剖面"先平滑再差分"（§4.7.1）、
 * 以及需要明确尺度含义的任意平滑环节。
 */
object Gaussian {

    /** 核缓存（按 σ 的百分之一量化，避免每帧重算 exp） */
    private val cache = HashMap<Int, DoubleArray>()

    /** 支撑半径 a = ⌈3σ⌉（§4.2：a 之外权重 < 0.3%） */
    fun radiusOf(sigma: Double): Int = ceil(3.0 * sigma).toInt().coerceAtLeast(1)

    /**
     * 一维归一化高斯核，长度 2a+1。
     *
     * @param sigma 标准差（像素）
     */
    fun kernel1D(sigma: Double): DoubleArray {
        val s = if (sigma < 0.1) 0.1 else sigma   // 防止 σ→0 时退化为冲激导致除零
        val key = (s * 100).toInt()
        cache[key]?.let { return it }
        val a = radiusOf(s)
        val k = DoubleArray(2 * a + 1)
        val inv = 1.0 / (2.0 * s * s)
        var sum = 0.0
        for (i in -a..a) {
            val v = exp(-(i * i).toDouble() * inv)
            k[i + a] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum            // 归一化，保证 DC 增益 = 1
        cache[key] = k
        return k
    }

    /**
     * 可分离高斯模糊（灰度图，边界 clamp）。
     *
     * @param gray w×h 灰度
     * @param w 宽
     * @param h 高
     * @param sigma 标准差（像素）
     * @return 模糊后的灰度图
     */
    fun blur(gray: ByteArray, w: Int, h: Int, sigma: Double = 1.5): ByteArray {
        if (w <= 0 || h <= 0) return gray.copyOf()
        val a = radiusOf(sigma)
        if (a <= 0) return gray.copyOf()
        val k = kernel1D(sigma)

        val tmp = DoubleArray(w * h)
        // 水平
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0.0
                for (i in -a..a) {
                    val nx = x + i
                    val cx = if (nx < 0) 0 else if (nx >= w) w - 1 else nx
                    s += k[i + a] * (gray[row + cx].toInt() and 0xFF)
                }
                tmp[row + x] = s
            }
        }
        // 垂直
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0.0
                for (i in -a..a) {
                    val ny = y + i
                    val cy = if (ny < 0) 0 else if (ny >= h) h - 1 else ny
                    s += k[i + a] * tmp[cy * w + x]
                }
                val v = s.toInt()
                out[y * w + x] = (if (v < 0) 0 else if (v > 255) 255 else v).toByte()
            }
        }
        return out
    }

    /**
     * 一维剖面高斯平滑（浮点，用于亚像素剖面的"先平滑再差分"）。
     *
     * @param profile 采样剖面
     * @param sigma 平滑尺度
     */
    fun smooth1D(profile: DoubleArray, sigma: Double): DoubleArray {
        if (profile.isEmpty() || sigma <= 0.0) return profile.copyOf()
        val a = radiusOf(sigma)
        val k = kernel1D(sigma)
        val out = DoubleArray(profile.size)
        for (i in profile.indices) {
            var s = 0.0
            for (j in -a..a) {
                val idx = (i + j).coerceIn(0, profile.size - 1)
                s += k[j + a] * profile[idx]
            }
            out[i] = s
        }
        return out
    }

    /**
     * 两个高斯核的等效尺度。自卷积闭合：√(σ₁²+σ₂²)。
     * 供多次模糊链的尺度记账使用（例：σ=1.0 两次 ≡ σ=1.414 一次）。
     */
    fun combineSigma(sigmas: DoubleArray): Double {
        var s = 0.0
        for (v in sigmas) s += v * v
        return sqrt(s)
    }
}
