package com.robomaster.control.vision.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * HSV 颜色空间转换 + 颜色掩码（纯 Kotlin）。
 *
 * 用于目标检测的颜色分割：
 * - 高地靶：绿色标靶板（H≈90-150，0-360 尺度）
 * - 基地靶：红色圆靶（H≈0-15 或 345-360）
 *
 * HSV 相比 RGB 的优势：亮度（V）与色度（H）解耦，光照变化下颜色更稳定。
 * 阈值判定同时约束饱和度（S）与明度（V），过滤灰色/黑色噪声。
 *
 * ## 色相是环形量（数学基础 §2.6 的两个陷阱）
 *
 * 1. **H ∈ [0,360) 是环**：359° 与 1° 在环上相邻，数值却相差 358。
 *    因此阈值带必须按"环形距离"判定（[inHueBand]），色相统计必须用
 *    **向量平均**（[hueMean]：转成 (cosH, sinH) 求平均再 atan2 转回），
 *    否则红色目标附近的平均色相会算成 180°（青绿），完全跑偏。
 * 2. **S 在 V→0 时不稳定**：分母趋零、噪声放大——深色区域的颜色信息本就不可靠，
 *    所以所有掩码都同时约束 V 下限，不在深色区设阈值。
 */
object Hsv {

    /** RGB → HSV（H: 0-360, S: 0-1, V: 0-1） */
    fun rgbToHsv(r: Int, g: Int, b: Int, out: FloatArray) {
        val rf = r / 255.0
        val gf = g / 255.0
        val bf = b / 255.0
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC

        // H
        var h = 0.0
        if (delta > 1e-6) {
            h = when (maxC) {
                rf -> 60.0 * (((gf - bf) / delta) % 6.0)
                gf -> 60.0 * (((bf - rf) / delta) + 2.0)
                else -> 60.0 * (((rf - gf) / delta) + 4.0)
            }
            if (h < 0) h += 360.0
        }
        // S
        val s = if (maxC > 1e-6) delta / maxC else 0.0
        out[0] = h.toFloat()
        out[1] = s.toFloat()
        out[2] = maxC.toFloat()
    }

    /**
     * 生成颜色掩码。
     *
     * @param rgb 输入 RGB 图（w×h×3，行优先）
     * @param w 宽
     * @param h 高
     * @param hueMin 色相下限（0-360）
     * @param hueMax 色相上限（0-360，支持跨 0 回绕如 345-15）
     * @param satMin 饱和度下限（0-1）
     * @param valMin 明度下限（0-1）
     * @return 掩码（w×h，命中=255，未命中=0）
     */
    fun colorMask(
        rgb: ByteArray,
        w: Int,
        h: Int,
        hueMin: Float,
        hueMax: Float,
        satMin: Float = 0.35f,
        valMin: Float = 0.25f
    ): ByteArray {
        val mask = ByteArray(w * h)
        val hsv = FloatArray(3)
        // 环形色相带（§2.6）：带宽 = (max−min) mod 360，跨 0 回绕（如红 345→40）自然成立
        val width = bandWidth(hueMin, hueMax)
        val center = (hueMin + width / 2.0) % 360.0
        val half = width / 2.0
        for (i in 0 until w * h) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            rgbToHsv(r, g, b, hsv)
            val hueOk = hueDistance(hsv[0].toDouble(), center) <= half
            if (hueOk && hsv[1] >= satMin && hsv[2] >= valMin) {
                mask[i] = 255.toByte()
            }
        }
        return mask
    }

    // ─────────── 色相环形运算（§2.6 陷阱 1） ───────────

    /** 环形色相带宽度：支持 min > max 的跨 0 区间（结果 ∈ [0,360)） */
    fun bandWidth(hueMin: Float, hueMax: Float): Double {
        var w = hueMax.toDouble() - hueMin.toDouble()
        if (w < 0) w += 360.0
        return w
    }

    /** 环形最短距离（0~180） */
    fun hueDistance(a: Double, b: Double): Double {
        var d = abs(a - b) % 360.0
        if (d > 180.0) d = 360.0 - d
        return d
    }

    /** 是否落在环形色相带内（等价于旧版"跨 0 回绕"判定，但对称一致） */
    fun inHueBand(hue: Double, hueMin: Float, hueMax: Float): Boolean {
        val width = bandWidth(hueMin, hueMax)
        val center = (hueMin + width / 2.0) % 360.0
        return hueDistance(hue, center) <= width / 2.0
    }

    /**
     * 色相向量平均（圆统计）。
     *
     * 直接对角度做算术平均是错的：hueMean([359, 1]) 的算术平均 = 180°（青绿），
     * 正确结果是 0°（红）。做法：Σ(cos H, sin H) → atan2 → 归一到 [0,360)。
     *
     * @return 平均色相（0~360）；输入为空返回 0
     */
    fun hueMean(hues: DoubleArray): Double {
        if (hues.isEmpty()) return 0.0
        var sx = 0.0
        var sy = 0.0
        for (h in hues) {
            val rad = Math.toRadians(h)
            sx += cos(rad)
            sy += sin(rad)
        }
        val deg = Math.toDegrees(atan2(sy, sx))
        return if (deg < 0) deg + 360.0 else deg
    }

    /**
     * 色相集中度（合矢量长度 / 样本数，0~1）。
     * 1 = 全部同色相；0 = 在环上均匀分布（无主导色）。用于判断"这块区域是不是同一种颜色"。
     */
    fun hueConcentration(hues: DoubleArray): Double {
        if (hues.isEmpty()) return 0.0
        var sx = 0.0
        var sy = 0.0
        for (h in hues) {
            val rad = Math.toRadians(h)
            sx += cos(rad)
            sy += sin(rad)
        }
        return Math.hypot(sx, sy) / hues.size
    }

    /** 预置：高地靶绿色（荧光绿标靶板）。色相带不变，配合 VNormalize 提亮暗目标 */
    fun greenMask(rgb: ByteArray, w: Int, h: Int): ByteArray =
        colorMask(rgb, w, h, hueMin = 80f, hueMax = 160f, satMin = 0.30f, valMin = 0.20f)

    /**
     * 预置：基地靶红色（固定靶 + 移动靶）。
     *
     * 2026-10 公开数据集（rmcv/armor 9123 张真实图）像素级统计修正：
     * 真实暗光红目标的色相会偏橙到 20~40°、V 掉到 0.10~0.25，
     * 原阈值（345-15/S>=0.40/V>=0.30）在真实图上掩码覆盖率仅 3.9%。
     * 修正为 345-40 回绕 + S>=0.45 + V>=0.12（与公开数据基准配置逐项一致，
     * 配合 VNormalize CLAHE，实测覆盖率 3.9% -> 57.1%，灰灭灯板误燃 0%）。
     */
    fun redMask(rgb: ByteArray, w: Int, h: Int): ByteArray =
        colorMask(rgb, w, h, hueMin = 345f, hueMax = 40f, satMin = 0.45f, valMin = 0.12f)

    /**
     * 预置：蓝色目标（公开数据集中的蓝装甲板实测 H 195-270/S>=0.40/V>=0.15）。
     * 狼牙规则暂无蓝色目标；保留作通用 colorMask 配置与评测对照。
     */
    fun blueMask(rgb: ByteArray, w: Int, h: Int): ByteArray =
        colorMask(rgb, w, h, hueMin = 195f, hueMax = 270f, satMin = 0.40f, valMin = 0.15f)
}
