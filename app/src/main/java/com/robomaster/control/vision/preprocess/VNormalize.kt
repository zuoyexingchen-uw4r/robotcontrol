package com.robomaster.control.vision.preprocess

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * V 通道 CLAHE 归一化（纯 Kotlin，无 native 依赖）。
 *
 * 动机（2026-10 公开数据集实测，rmcv/armor 9123 张真实赛场图）：
 * 真实暗光/过曝环境下，目标颜色载体的 V 会掉到 0.1~0.3、色相偏 20~40°，
 * Kotlin 原版 HSV 阈值（红 S>=0.40/V>=0.30）在真实图上掩码覆盖率仅 3.9%。
 * 对 V 通道做 CLAHE（对比度受限自适应直方图均衡）后，同一阈值覆盖率
 * 提升至 55.9%（红）/ 86.0%（蓝）。全局百分位拉伸只有 36.2%，不够，
 * 所以用 CLAHE。
 *
 * 算法（标准 CLAHE）：
 *   1. V = max(R,G,B)（与 HSV 的 V 一致）
 *   2. 8x8 分块，每块统计 V 直方图；clip limit = 2.0 倍均值，超出部分均摊回放
 *   3. 每块由 CDF 建立 0..255 映射表
 *   4. 像素值 = 4 个邻近块映射表的双线性插值
 *   5. 按 V 的缩放比例同步缩放 R/G/B（保持色度 H/S 不变）
 *
 * 性能（640x480，估计）：~2 遍像素扫描 + 每像素 4 次查表，
 * 纯 Kotlin 约 10~20ms；检测管线每 3 帧跑一次（默认），预算内。
 */
object VNormalize {

    /** 分块数（8x8=64 块） */
    private const val TILES = 8

    /** clip limit 系数（相对块内均值的倍数） */
    private const val CLIP = 2.0

    /**
     * 就地归一化 RGB 图的 V 通道（CLAHE）。
     *
     * @param rgb w*h*3，行优先 R,G,B
     * @param w 宽
     * @param h 高
     */
    fun claheVInPlace(rgb: ByteArray, w: Int, h: Int) {
        val n = w * h
        if (n <= 0) return

        // 块尺寸（含边缘不足一块的余量，按浮点块坐标处理）
        val tw = w.toDouble() / TILES
        val th = h.toDouble() / TILES

        // ── 1) 每块直方图 + clip + CDF 映射表 ──
        val hists = Array(TILES * TILES) { IntArray(256) }
        var i = 0
        while (i < n) {
            val v = maxOf(
                rgb[i * 3].toInt() and 0xFF,
                rgb[i * 3 + 1].toInt() and 0xFF,
                rgb[i * 3 + 2].toInt() and 0xFF
            )
            val tx = min(TILES - 1, ((i % w) / tw).toInt())
            val ty = min(TILES - 1, ((i / w) / th).toInt())
            hists[ty * TILES + tx][v]++
            i++
        }

        val maps = Array(TILES * TILES) { IntArray(256) }
        for (t in 0 until TILES * TILES) {
            val hist = hists[t]
            var total = 0
            for (v in 0..255) total += hist[v]
            if (total == 0) {
                for (v in 0..255) maps[t][v] = v
                continue
            }
            // clip: 每 bin 上限 = CLIP * total/256，超出量均摊到所有 bin
            val limit = max(1, (CLIP * total / 256.0).roundToInt())
            var excess = 0
            for (v in 0..255) {
                if (hist[v] > limit) {
                    excess += hist[v] - limit
                    hist[v] = limit
                }
            }
            val add = excess / 256
            val rem = excess - add * 256
            for (v in 0..255) hist[v] += add
            for (v in 0 until rem) hist[v] += 1
            // CDF → 映射
            var cdf = 0
            val scale = 255.0 / total
            for (v in 0..255) {
                cdf += hist[v]
                maps[t][v] = min(255, (cdf * scale).roundToInt())
            }
        }

        // ── 2) 逐像素：双线性插值 4 个邻近块的映射，再按比例缩放 RGB ──
        i = 0
        while (i < n) {
            val x = i % w
            val y = i / w
            // 像素所在块的浮点坐标（块中心在 (t+0.5)*tile）
            val fx = x / tw - 0.5
            val fy = y / th - 0.5
            val tx0 = fx.toInt().coerceIn(0, TILES - 1)
            val ty0 = fy.toInt().coerceIn(0, TILES - 1)
            val tx1 = (tx0 + 1).coerceIn(0, TILES - 1)
            val ty1 = (ty0 + 1).coerceIn(0, TILES - 1)
            // 双线性权重（相对块中心）
            val wx = (fx - tx0).coerceIn(0.0, 1.0)
            val wy = (fy - ty0).coerceIn(0.0, 1.0)
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            val v = maxOf(r, g, b)
            // 4 块映射插值
            val m00 = maps[ty0 * TILES + tx0][v]
            val m10 = maps[ty0 * TILES + tx1][v]
            val m01 = maps[ty1 * TILES + tx0][v]
            val m11 = maps[ty1 * TILES + tx1][v]
            val top = m00 + (m10 - m00) * wx
            val bot = m01 + (m11 - m01) * wx
            val vNew = (top + (bot - top) * wy).roundToInt()
            if (vNew != v && v > 0) {
                val s = vNew.toDouble() / v
                rgb[i * 3] = clamp255((r * s).roundToInt()).toByte()
                rgb[i * 3 + 1] = clamp255((g * s).roundToInt()).toByte()
                rgb[i * 3 + 2] = clamp255((b * s).roundToInt()).toByte()
            }
            i++
        }
    }

    /** 非就地版本：返回新数组 */
    fun claheVCopy(rgb: ByteArray, w: Int, h: Int): ByteArray {
        val out = rgb.copyOf()
        claheVInPlace(out, w, h)
        return out
    }

    private fun clamp255(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v
}
