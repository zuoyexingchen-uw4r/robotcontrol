package com.robomaster.control.vision.detect

import kotlin.math.max
import kotlin.math.min

/**
 * HSV 颜色空间转换 + 颜色掩码（纯 Kotlin）。
 *
 * 用于目标检测的颜色分割：
 * - 高地靶：绿色标靶板（H≈90-150，0-360 尺度）
 * - 基地靶：红色圆靶（H≈0-15 或 345-360）
 *
 * HSV 相比 RGB 的优势：亮度（V）与色度（H）解耦，光照变化下颜色更稳定。
 * 阈值判定同时约束饱和度（S）与明度（V），过滤灰色/黑色噪声。
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
        val wraps = hueMin > hueMax  // 跨 0 回绕（如红色 345-15）
        for (i in 0 until w * h) {
            val r = rgb[i * 3].toInt() and 0xFF
            val g = rgb[i * 3 + 1].toInt() and 0xFF
            val b = rgb[i * 3 + 2].toInt() and 0xFF
            rgbToHsv(r, g, b, hsv)
            val hueOk = if (wraps) hsv[0] >= hueMin || hsv[0] <= hueMax
            else hsv[0] >= hueMin && hsv[0] <= hueMax
            if (hueOk && hsv[1] >= satMin && hsv[2] >= valMin) {
                mask[i] = 255.toByte()
            }
        }
        return mask
    }

    /** 预置：高地靶绿色（荧光绿标靶板） */
    fun greenMask(rgb: ByteArray, w: Int, h: Int): ByteArray =
        colorMask(rgb, w, h, hueMin = 80f, hueMax = 160f, satMin = 0.30f, valMin = 0.20f)

    /** 预置：基地靶红色（固定靶 + 移动靶） */
    fun redMask(rgb: ByteArray, w: Int, h: Int): ByteArray =
        colorMask(rgb, w, h, hueMin = 345f, hueMax = 15f, satMin = 0.40f, valMin = 0.30f)
}
