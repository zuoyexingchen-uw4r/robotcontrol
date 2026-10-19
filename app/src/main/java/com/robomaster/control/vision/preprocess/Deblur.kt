package com.robomaster.control.vision.preprocess

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 去模糊：维纳非盲反卷积（Wiener deconvolution）。
 *
 * 原理（课件 2D-去模糊）：
 *   运动模糊模型 B = I ⊗ K + N（I 原图，K 模糊核，N 噪声）
 *   频域维纳解：  F(K̂) = F(B)·conj(F(K)) / (|F(K)|² + η)
 *   η 为噪声功率比（正则化项），防止 |F(K)|≈0 处噪声爆炸。
 *
 * 实现说明：
 * - 纯 Kotlin，无 OpenCV 依赖。radix-2 Cooley-Tukey FFT（迭代版，含位反转）。
 * - 图像 pad 到 2 的幂尺寸后做二维 FFT；模糊核同样 pad 并循环移位对齐。
 * - 性能预算：256×256 二维 FFT ≈ 2×256×8×256 ≈ 100 万次蝶形，约 20-40ms，
 *   适合周期性（如每 500ms）在低分辨率上跑，不适合每帧全尺寸跑。
 *
 * 模糊核估计：本实现用参数化运动模糊核（长度 + 角度）。
 * 陀螺仪数据接入后可用角速度初值细化核参数；当前默认小核（长度 3-5）。
 */
object Deblur {

    /**
     * 维纳反卷积去模糊。
     *
     * @param gray 输入灰度图（w×h，行优先）
     * @param w 宽
     * @param h 高
     * @param kernelLength 运动模糊核长度（像素，1=无模糊）
     * @param angleDeg 模糊方向（度，0=水平向右）
     * @param eta 正则化系数（0.001~0.05，越大越保守/越平滑）
     * @return 去模糊后的灰度图（同尺寸）
     */
    fun wienerDeconvolve(
        gray: ByteArray,
        w: Int,
        h: Int,
        kernelLength: Int = 3,
        angleDeg: Double = 0.0,
        eta: Double = 0.01
    ): ByteArray {
        if (kernelLength <= 1 || w <= 0 || h <= 0) return gray.copyOf()

        // pad 到 2 的幂
        val pw = nextPow2(w)
        val ph = nextPow2(h)
        val n = pw * ph

        // 图像 → double 实部/虚部
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (y in 0 until h) {
            for (x in 0 until w) {
                re[y * pw + x] = (gray[y * w + x].toInt() and 0xFF).toDouble()
            }
        }

        // 二维 FFT（先行后列）
        fft2d(re, im, pw, ph, inverse = false)

        // 模糊核 → 同尺寸，循环移位使中心落在 (0,0)
        val kre = DoubleArray(n)
        val kim = DoubleArray(n)
        buildMotionKernel(kre, kim, pw, ph, kernelLength, angleDeg)
        fft2d(kre, kim, pw, ph, inverse = false)

        // 维纳滤波：G = F·conj(H) / (|H|² + η)
        for (i in 0 until n) {
            val hr = kre[i]; val hi = kim[i]
            val fr = re[i]; val fi = im[i]
            val hMag2 = hr * hr + hi * hi
            val denom = hMag2 + eta
            // conj(H) = (hr, -hi)；F·conj(H) = (fr·hr + fi·hi, fi·hr - fr·hi)
            val gr = (fr * hr + fi * hi) / denom
            val gi = (fi * hr - fr * hi) / denom
            re[i] = gr; im[i] = gi
        }

        // 逆二维 FFT
        fft2d(re, im, pw, ph, inverse = true)

        // 裁剪回原尺寸并 clamp
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = re[y * pw + x]
                out[y * w + x] = when {
                    v < 0 -> 0
                    v > 255 -> 255.toByte()
                    else -> v.toInt().toByte()
                }
            }
        }
        return out
    }

    /** 生成运动模糊核（直线型，以 (0,0) 为中心绘制，负坐标循环回绕） */
    private fun buildMotionKernel(
        kre: DoubleArray, kim: DoubleArray,
        pw: Int, ph: Int,
        length: Int, angleDeg: Double
    ) {
        val rad = angleDeg * PI / 180.0
        val dx = cos(rad)
        val dy = sin(rad)
        val half = length / 2
        // 核中心对齐 (0,0)：与图像循环卷积 B = I ⊗ K 的频域表示一致。
        // 若把核画在图像中心，频域相乘会引入半幅相位偏移，反卷积结果全错。
        for (t in -half..half) {
            val x = (((t * dx).toInt()) + pw) % pw
            val y = (((t * dy).toInt()) + ph) % ph
            kre[y * pw + x] += 1.0
        }
        // 归一化（保持能量）
        var sum = 0.0
        for (v in kre) sum += v
        if (sum > 0) for (i in kre.indices) kre[i] /= sum
    }

    /** 二维 FFT：先行（每行长度 pw）后列（每列长度 ph） */
    private fun fft2d(re: DoubleArray, im: DoubleArray, pw: Int, ph: Int, inverse: Boolean) {
        // 行变换
        for (y in 0 until ph) {
            val off = y * pw
            fft1d(re, im, off, pw, inverse)
        }
        // 列变换（跨步访问，抽到临时数组）
        val colRe = DoubleArray(ph)
        val colIm = DoubleArray(ph)
        for (x in 0 until pw) {
            for (y in 0 until ph) {
                colRe[y] = re[y * pw + x]
                colIm[y] = im[y * pw + x]
            }
            fft1d(colRe, colIm, 0, ph, inverse)
            for (y in 0 until ph) {
                re[y * pw + x] = colRe[y]
                im[y * pw + x] = colIm[y]
            }
        }
    }

    /**
     * radix-2 Cooley-Tukey FFT（迭代版）。
     * 对 re/im 从 offset 开始、长度 n 的段做变换。n 必须是 2 的幂。
     */
    private fun fft1d(re: DoubleArray, im: DoubleArray, offset: Int, n: Int, inverse: Boolean) {
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[offset + i]; re[offset + i] = re[offset + j]; re[offset + j] = t
                t = im[offset + i]; im[offset + i] = im[offset + j]; im[offset + j] = t
            }
        }
        // 蝶形运算
        var len = 2
        while (len <= n) {
            val ang = 2.0 * PI / len * if (inverse) 1.0 else -1.0
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cwr = 1.0
                var cwi = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val a = offset + i + k
                    val b = a + half
                    val vr = re[b] * cwr - im[b] * cwi
                    val vi = re[b] * cwi + im[b] * cwr
                    re[b] = re[a] - vr; im[b] = im[a] - vi
                    re[a] += vr; im[a] += vi
                    val nwr = cwr * wr - cwi * wi
                    cwi = cwr * wi + cwi * wr
                    cwr = nwr
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            for (i in 0 until n) {
                re[offset + i] = re[offset + i] / n
                im[offset + i] = im[offset + i] / n
            }
        }
    }

    private fun nextPow2(v: Int): Int {
        var p = 1
        while (p < v) p = p shl 1
        return p
    }
}
