package com.robomaster.control.vision.preprocess

/**
 * 导向滤波（Guided Filter，He et al. 2010，纯 Kotlin，O(1)/像素）。
 *
 * 动机（《传统视觉与深度学习视觉的数学基础》§4.4 / §4.5）：
 * 双边滤波是非线性、非移变滤波——权重依赖像素值，因此不满足卷积定理、不能用 FFT 加速，
 * 且存在"梯度反转（gradient reversal）"缺陷：强边缘两侧一侧变亮一侧变暗（光晕）。
 * 导向滤波改用**局部线性模型** q_i = a_k·I_i + b_k（窗口 k 内），
 * 通过最小二乘解出 (a_k, b_k)，再用均值滤波合并重叠窗口：
 *
 *   a_k = Var(I)_k / (Var(I)_k + ε)
 *   b_k = (1 − a_k)·mean(I)_k                    （p = I 时的自导向形式）
 *   q_i = mean(a)_i·I_i + mean(b)_i
 *
 * 三条直接收益：
 * 1. **O(1)/像素，与半径无关**——只需 box 均值（滑动窗口和），r=2 与 r=20 耗时相同；
 * 2. 边缘处 a→1（原样保留），平坦处 a→0（强力平滑），本质是"用方差做门控"；
 * 3. 无梯度反转：q 是 I 的局部线性函数，不会越过原图局部极值。
 *
 * ε 的物理含义：**方差阈值**。ε 大 → 更多区域被判为"平坦"→ 平滑更狠；
 * 本实现以归一化灰度（0~1）为单位给 ε，内部乘 255² 换算到 8 位域。
 */
object GuidedFilter {

    /**
     * 自导向滤波（p = I = 输入图）。
     *
     * @param gray w×h 灰度
     * @param w 宽
     * @param h 高
     * @param radius 窗口半径（O(1)，与半径无关；4 适合 640×480 去噪）
     * @param eps 方差阈值（归一化灰度单位，典型 0.01² ~ 0.1²）
     */
    fun filter(gray: ByteArray, w: Int, h: Int, radius: Int = 4, eps: Double = 0.01): ByteArray {
        if (w <= 0 || h <= 0) return gray.copyOf()
        val n = w * h
        val r = radius.coerceIn(1, 32)
        val eps8 = eps * eps * 255.0 * 255.0      // 换算到 8 位灰度域
        val epsSafe = if (eps8 < 1e-6) 1e-6 else eps8

        val i = FloatArray(n) { (gray[it].toInt() and 0xFF).toFloat() }
        val ii = FloatArray(n) { i[it] * i[it] }
        val tmp = DoubleArray(n)
        val mI = FloatArray(n)
        val mII = FloatArray(n)
        boxMeanInto(i, mI, tmp, w, h, r)
        boxMeanInto(ii, mII, tmp, w, h, r)

        // a、b 就地写回 i、ii（原图不再需要，省 2 份 1.2MB）
        for (k in 0 until n) {
            val mean = mI[k]
            var v = mII[k] - mean * mean
            if (v < 0f) v = 0f                    // 数值误差兜底（方差非负）
            val a = (v / (v + epsSafe)).toFloat()
            i[k] = a
            ii[k] = (1f - a) * mean
        }
        // 复用 mI、mII 承载 mean(a)、mean(b)
        boxMeanInto(i, mI, tmp, w, h, r)
        boxMeanInto(ii, mII, tmp, w, h, r)

        // q = mean(a)·I + mean(b)，I 从 gray 重新读（i 已被覆盖）
        val out = ByteArray(n)
        for (k in 0 until n) {
            val src = (gray[k].toInt() and 0xFF).toFloat()
            val q = mI[k] * src + mII[k]
            val v: Int = when {
                q < 0f -> 0
                q > 255f -> 255
                else -> (q + 0.5f).toInt()
            }
            out[k] = v.toByte()
        }
        return out
    }

    /**
     * box 均值（水平 + 垂直两遍滑动窗口和）。边界按 clamp 填充，
     * 两遍都除以 (2r+1) 保证前向/后向口径一致——这是"可分离 = 两次一维"的代价最小的实现。
     */
    private fun boxMeanInto(
        src: FloatArray, dst: FloatArray, tmp: DoubleArray,
        w: Int, h: Int, r: Int
    ) {
        val win = 2 * r + 1
        // 水平
        for (y in 0 until h) {
            val row = y * w
            var sum = 0.0
            for (j in -r..r) sum += src[row + j.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[row + x] = sum / win
                val addX = (x + r + 1).coerceAtMost(w - 1)
                val subX = (x - r).coerceIn(0, w - 1)
                sum += src[row + addX] - src[row + subX]
            }
        }
        // 垂直
        for (x in 0 until w) {
            var sum = 0.0
            for (j in -r..r) sum += tmp[j.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = (sum / win).toFloat()
                val addY = (y + r + 1).coerceAtMost(h - 1)
                val subY = (y - r).coerceIn(0, h - 1)
                sum += tmp[addY * w + x] - tmp[subY * w + x]
            }
        }
    }
}
