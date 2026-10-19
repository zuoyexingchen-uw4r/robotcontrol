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
 */
class KalmanFilterCV(
    private val dt: Double = 0.1,          // 帧间隔（秒），按检测频率调整
    processNoise: Double = 1.0,            // 过程噪声强度（加速度方差）
    private val measurementNoise: Double = 4.0  // 测量噪声（像素方差）
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

    /** 更新（观测校正） */
    fun update(mx: Double, my: Double) {
        if (!initialized) { init(mx, my); return }
        // 创新 y = z - H·x
        val zx = mx - x[0]
        val zy = my - x[1]
        // S = H·P·Hᵀ + R
        val ph = Array(2) { DoubleArray(4) }
        for (i in 0 until 2) {
            for (j in 0 until 4) {
                var s = 0.0
                for (k in 0 until 4) s += h[i][k] * p[k][j]
                ph[i][j] = s
            }
        }
        val s00 = ph[0][0] + r[0][0]
        val s01 = ph[0][1] + r[0][1]
        val s10 = ph[1][0] + r[1][0]
        val s11 = ph[1][1] + r[1][1]
        val det = s00 * s11 - s01 * s10
        if (abs(det) < 1e-12) return
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
        // Joseph 形式：P = (I-KH)P(I-KH)ᵀ + KRKᵀ
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
        // + K·R·Kᵀ
        val kr = Array(4) { DoubleArray(2) }
        for (i in 0 until 4) {
            for (j in 0 until 2) {
                var s = 0.0
                for (m in 0 until 2) s += k[i][m] * r[m][j]
                kr[i][j] = s
            }
        }
        for (i in 0 until 4) {
            for (j in 0 until 4) {
                var s = 0.0
                for (m in 0 until 2) s += kr[i][m] * k[j][m]
                p[i][j] += s
            }
        }
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
}
