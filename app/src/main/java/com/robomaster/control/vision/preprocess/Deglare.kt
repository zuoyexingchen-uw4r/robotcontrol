package com.robomaster.control.vision.preprocess

/**
 * 去辉光 / 抗强光干扰（Deglare）。
 *
 * 背景（课件 ICCV2015 辉光成像模型）：
 *   I(x) = J(x)·t(x) + A(x)·(1-t(x)) + APSF*L(x)
 *   强光源经镜头/空气散射在成像面形成大范围辉光，导致：
 *   - 局部过曝（像素饱和到 255）
 *   - 周围区域被抬亮（对比度下降，靶框边缘淹没）
 *   完整解法需估计光源位置 + APSF + 暗通道去光污染，计算量大。
 *
 * 本实现采用**工程简化版**（适配手机算力 + 实时性要求）：
 *   1. 过曝检测：像素 > threshold（默认 250）标记为辉光像素
 *   2. 邻域修复：辉光像素用逐渐扩大邻域内的非辉光像素中值填充
 *      （inpainting 简化：先 3×3，不够扩到 5×5、7×7）
 *   3. 大光斑兜底：若邻域全为辉光（整片过曝），用全局非辉光均值填充，
 *      把过曝区压回中等灰度，避免后续 HSV 分割误检大片白色区域
 *
 * 效果：抑制强光手电/环境反光对颜色分割的干扰，保住靶框可检测性。
 */
object Deglare {

    /**
     * 去辉光处理。
     *
     * @param gray 输入灰度图（w×h）
     * @param w 宽
     * @param h 高
     * @param threshold 过曝阈值（0-255，默认 250）
     * @return 处理后的灰度图（同尺寸）
     */
    fun process(
        gray: ByteArray,
        w: Int,
        h: Int,
        threshold: Int = 250
    ): ByteArray {
        if (w <= 0 || h <= 0) return gray.copyOf()

        val out = gray.copyOf()
        val src = IntArray(w * h) { gray[it].toInt() and 0xFF }

        // 全局非辉光均值（大光斑兜底用）
        var sum = 0L
        var cnt = 0
        for (v in src) {
            if (v <= threshold) { sum += v; cnt++ }
        }
        val globalMean = if (cnt > 0) (sum / cnt).toInt() else 128

        // 逐像素修复辉光点
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (src[idx] <= threshold) continue  // 非辉光，保留

                // 逐渐扩大邻域找非辉光像素
                var repaired = -1
                for (r in 1..3) {
                    var vsum = 0
                    var vcnt = 0
                    for (dy in -r..r) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -r..r) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val v = src[ny * w + nx]
                            if (v <= threshold) { vsum += v; vcnt++ }
                        }
                    }
                    if (vcnt > 0) {
                        repaired = vsum / vcnt
                        break
                    }
                }
                // 邻域全辉光 → 全局均值兜底
                if (repaired < 0) repaired = globalMean
                out[idx] = repaired.coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
