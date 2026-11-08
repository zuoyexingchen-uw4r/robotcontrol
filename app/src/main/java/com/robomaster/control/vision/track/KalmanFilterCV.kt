package com.robomaster.control.vision.track

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 卡尔曼滤波器（匀速模型 CV，纯 Kotlin）。
 *
 * 移植自 rm_selection/task2_kalman/kf_core.py 的 KalmanFilterCV：
 * - 状态 [x, y, vx, vy]（位置 + 速度）
 * - 离散白噪声加速度模型（过程噪声 Q）
 * - Joseph 形式协方差更新（数值稳定）
 *
 * 用途：目标检测结果的时序平滑——检测框有抖动/漏检时，
 * 用卡尔曼预测填补漏检帧、平滑跟踪轨迹，为枪管随动提供稳定角度。
 *
 * ## 2026-11 新增：NIS 软门控（数学基础 §1.7 / §9）
 *
 * 创新协方差 S = H·P·Hᵀ + R 已知时，马氏距离平方
 *   d² = yᵀ·S⁻¹·y  ~  χ²(2)（模型正确、噪声为高斯）
 * 自由度 2 的 99% 分位是 9.21。超过门限说明这条观测与运动模型不相容
 * （典型来源：误检到别的目标、掩码被反光拉走）。
 * 处理方式取**软门控**而非硬丢弃：按 d²/9.21 膨胀 R 再更新，
 * 等价于"可信度按马氏距离打折"——既不会让一条离群观测把轨迹拽飞，
 * 又能在目标真正机动时不至于跟丢（硬丢弃连续几帧就会丢线索）。
 */
class KalmanFilterCV(
    private val dt: Double = 0.1,          // 帧间隔（秒），按检测频率调整
    processNoise: Double = 1.0,            // 过程噪声强度（加速度方差）
    private val measurementNoise: Double = 4.0,  // 测量噪声（像素方差）
    private val gateNis: Double = GATE_99  // NIS 门限（χ²₂ 99% 分位 = 9.21）
) {
    // 状态 [x, y, vx, vy]
    private val x = DoubleArray(4)
    // 协方差 4×4
    private val p = Array(4) { DoubleArray(4) }
    // 状态转移矩阵 F（匀速）
    private val f = arrayOf(
        doubleArrayOf(1.0, 0.0, dt, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, dt),
        doubleArrayOf(0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    )
    // 观测矩阵 H（只观测位置）
    private val h = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0)
    )
    // 过程噪声 Q
    private val q = Array(4) { DoubleArray(4) }.also { m ->
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        val dt4 = dt3 * dt
        val qv = processNoise
        m[0][0] = dt4 / 4 * qv; m[0][2] = dt3 / 2 * qv
        m[1][1] = dt4 / 4 * qv; m[1][3] = dt3 / 2 * qv
        m[2][0] = dt3 / 2 * qv; m[2][2] = dt2 * qv
        m[3][1] = dt3 / 2 * qv; m[3][3] = dt2 * qv
    }
    // 测量噪声 R
    private val r = arrayOf(
        doubleArrayOf(measurementNoise, 0.0),
        doubleArrayOf(0.0, measurementNoise)
    )

    var initialized = false
        private set

    /** 最近一次更新的 NIS（马氏距离平方）：模型相容时 ≈ 自由度 2；> 9.21 判为离群 */
    var lastNis: Double = 0.0
        private set

    /** 用首次观测初始化 */
    fun init(mx: Double, my: Double) {
        x[0] = mx; x[1] = my; x[2] = 0.0; x[3] = 0.0
        for (i in 0 until 4) {
            for (j in 0 until 4) p[i][j] = 0.0
        }
        p[0][0] = measurementNoise
        p[1][1] = measurementNoise
        p[2][2] = 100.0
        p[3][3] = 100.0
        initialized = true
    }

    /** 预测（时间推进） */
    fun predict() {
        if (!initialized) return
        // x = F·x
        val nx = DoubleArray(4)
        for (i in 0 until 4) {
            var s = 0.0
            for (j in 0 until 4) s += f[i][j] * x[j]
            nx[i] = s
        }
        for (i in 0 until 4) x[i] = nx[i]
        // P = F·P·Fᵀ + Q
        val fp = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += f[i][k] * p[k][j]
                fp[i][j] = s
            }
        }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = q[i][j]
                for (k in 0 until 4) s += fp[i][k] * f[j][k]
                p[i][j] = s
            }
        }
    }

    /** 更新（观测校正）。带 NIS 软门控，返回 false 表示该观测被判为离群（已降权）。 */
    fun update(mx: Double, my: Double): Boolean {
        if (!initialized) { init(mx, my); return true }
        // 创新 y = z - H·x
        val zx = mx - x[0]
        val zy = my - x[1]
        // H·P（只算一次，S 在不同 R 缩放下复用）
        val ph = Array(2) { DoubleArray(4) }
        for (i in 0 until 2) {
            for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += h[i][k] * p[k][j]
                ph[i][j] = s
            }
        }

        // ── NIS 门控（§1.7 高斯 + §9 协方差）──
        // d² = yᵀS⁻¹y 在模型正确时服从 χ²(2)；超过 99% 分位说明观测与预测不相容
        val s00n = ph[0][0] + r[0][0]
        val s01n = ph[0][1] + r[0][1]
        val s10n = ph[1][0] + r[1][0]
        val s11n = ph[1][1] + r[1][1]
        val detN = s00n * s11n - s01n * s10n
        if (abs(detN) < 1e-12) return false
        val nis = (s11n * zx * zx - (s01n + s10n) * zx * zy + s00n * zy * zy) / detN
        lastNis = nis
        var accepted = true
        var rScale = 1.0
        if (gateNis > 0 && nis > gateNis) {
            // 软门控：不丢观测（丢了会跟丢机动目标），而是按 nis/gate 膨胀 R，
            // 等价于"这条观测的可信度按马氏距离打折"。
            rScale = (nis / gateNis).coerceAtMost(MAX_INFLATE)
            accepted = false
        }

        // S = H·P·Hᵀ + R·rScale
        val s00 = ph[0][0] + r[0][0] * rScale
        val s01 = ph[0][1] + r[0][1] * rScale
        val s10 = ph[1][0] + r[1][0] * rScale
        val s11 = ph[1][1] + r[1][1] * rScale
        val det = s00 * s11 - s01 * s10
        if (abs(det) < 1e-12) return false
        val inv00 = s11 / det
        val inv01 = -s01 / det
        val inv10 = -s10 / det
        val inv11 = s00 / det
        // K = P·Hᵀ·S⁻¹
        val k = Array(4) { DoubleArray(2) }
        for (i in 0 until 4) {
            k[i][0] = p[i][0] * inv00 + p[i][1] * inv10
            k[i][1] = p[i][0] * inv01 + p[i][1] * inv11
        }
        // x = x + K·y
        x[0] += k[0][0] * zx + k[0][1] * zy
        x[1] += k[1][0] * zx + k[1][1] * zy
        x[2] += k[2][0] * zx + k[2][1] * zy
        x[3] += k[3][0] * zx + k[3][1] * zy
        // Joseph 形式：P = (I-KH)P(I-KH)ᵀ + K(R·rScale)Kᵀ
        val kh = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (m in 0 until 2) s += k[i][m] * h[m][j]
                kh[i][j] = s
            }
        }
        val ikh = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) ikh[i][j] = (if (i == j) 1.0 else 0.0) - kh[i][j]
        }
        val tmp = Array(4) { DoubleArray(4) }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (m in 0 until 4) s += ikh[i][m] * p[m][j]
                tmp[i][j] = s
            }
        }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (m in 0 until 4) s += tmp[i][m] * ikh[j][m]
                p[i][j] = s
            }
        }
        // + K·(R·rScale)·Kᵀ
        val kr = Array(4) { DoubleArray(2) }
        for (i in 0 until 4) {
            for (j in 0 until 2) {
                kr[i][j] = k[i][j] * r[j][j] * rScale
            }
        }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (m in 0 until 2) s += kr[i][m] * k[j][m]
                p[i][j] += s
            }
        }
        return accepted
    }

    /** 当前位置（滤波后） */
    fun position(): Pair<Double, Double> = x[0] to x[1]

    /** 当前速度（像素/秒） */
    fun velocity(): Pair<Double, Double> = x[2] to x[3]

    /** 预测 N 步后的位置（用于提前量计算） */
    fun predictAhead(steps: Int): Pair<Double, Double> {
        val t = steps * dt
        return (x[0] + x[2] * t) to (x[1] + x[3] * t)
    }

    /** 位置协方差（不确定性，用于判断跟踪质量） */
    fun positionCovariance(): Double = sqrt(p[0][0] * p[1][1])

    /** 位置标准差（像素，单轴平均）：σ = √(σx·σy) */
    fun positionSigma(): Double = positionCovariance().coerceAtLeast(0.0)

    companion object {
        /** χ²(2) 的 99% 分位数：观测与预测不相容的判定门限 */
        const val GATE_99 = 9.21

        /** R 膨胀上限（防止发散） */
        const val MAX_INFLATE = 64.0
    }
}
