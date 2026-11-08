package com.robomaster.control.vision.solve

import kotlin.math.atan2
import kotlin.math.tan

/**
 * 针孔相机模型：测距 + 测角 + 不确定度传播（纯 Kotlin）。
 *
 * 原理（课件 3D）：
 *   单目相机缺深度信息，但已知目标**物理尺寸**时可用相似三角形测距：
 *     distance = focal_px × real_size / pixel_size
 *   角度（相对相机光轴）：
 *     yaw   = atan2(dx_px, focal_px)   （水平偏角）
 *     pitch = atan2(dy_px, focal_px)   （垂直偏角）
 *
 * ## 不确定度传播（数学基础 §1.2 一阶近似 / §1.7 方差）
 *
 * 单目测距的误差几乎全来自像素尺寸的测量误差。对 d = f·W/p 做一阶（泰勒）近似：
 *   |∂d/∂p| = f·W/p² = d/p   ⟹   σ_d = (d/p)·σ_p   ⟹   σ_d/d = σ_p/p
 * **相对测距误差 = 相对像素尺寸误差**，而且 d 越大、p 越小，绝对误差按 d² 增长。
 * 这不是"更精确的公式"，而是判断"这个距离能不能用来打"的唯一依据：
 * 8m 处 p=7.5px、σ_p=0.5px 时 σ_d≈530mm；而 2m 处 p=30px 时 σ_d≈33mm。
 *
 * ## 视觉 + 雷达融合（逆方差加权）
 *
 * 两个独立测距的加权平均，权重取 1/σ² 时融合方差最小：
 *   w_i = (1/σ_i²) / Σ(1/σ_j²)，  d̂ = Σ w_i·d_i，  1/σ̂² = Σ 1/σ_i²
 * 比固定权重（0.7/0.3）合理：谁准就听谁的，且融合后的 σ 会真的变小。
 *
 * 参数标定：
 * - focal_px：焦距（像素）。可用 FOV 换算：focal_px = (w/2) / tan(hFov/2)
 *   手机典型广角 hFov≈60-70°。精确值需标定（棋盘格），此处给默认估算。
 * - real_size：目标实际尺寸（mm）。规则已知：
 *   高地靶面板、基地靶直径等（见 RULES.md）。
 */
object Pinhole {

    /**
     * 测距。
     *
     * @param pixelSize 目标在图像中的尺寸（像素，长边）
     * @param realSizeMm 目标实际尺寸（mm）
     * @param focalPx 焦距（像素）
     * @return 距离（mm）；pixelSize<=0 返回 -1
     */
    fun distance(pixelSize: Double, realSizeMm: Double, focalPx: Double): Double {
        if (pixelSize <= 0 || focalPx <= 0) return -1.0
        return focalPx * realSizeMm / pixelSize
    }

    /**
     * 测角（相对光轴）。
     *
     * @param dxPx 目标中心相对图像中心的水平偏移（像素，右为正）
     * @param dyPx 目标中心相对图像中心的垂直偏移（像素，下为正）
     * @param focalPx 焦距（像素）
     * @return (yawDeg, pitchDeg)，右为正、上为正（云台常规）
     */
    fun angles(dxPx: Double, dyPx: Double, focalPx: Double): Pair<Double, Double> {
        if (focalPx <= 0) return 0.0 to 0.0
        val yaw = Math.toDegrees(atan2(dxPx, focalPx))
        val pitch = -Math.toDegrees(atan2(dyPx, focalPx))  // 图像 y 向下，取反
        return yaw to pitch
    }

    /**
     * 测距标准差（一阶传播）：σ_d = (d/p)·σ_p。
     *
     * @param distanceMm 测距结果（mm）
     * @param pixelSize 目标像素尺寸（px）
     * @param pixelSigmaPx 像素尺寸标准差（px，整数外接矩形取 0.5，亚像素精定位取 0.15~0.25）
     * @return σ_d（mm）；参数非法返回 -1
     */
    fun distanceSigma(distanceMm: Double, pixelSize: Double, pixelSigmaPx: Double = 0.5): Double {
        if (distanceMm <= 0 || pixelSize <= 0 || pixelSigmaPx < 0) return -1.0
        return distanceMm / pixelSize * pixelSigmaPx
    }

    /**
     * 测角标准差（弧度→度）：θ = atan(dx/f)，dθ/ddx = f/(f²+dx²)。
     * 小角度下 ≈ σ_dx/f。
     */
    fun angleSigmaDeg(dxPx: Double, sigmaPx: Double, focalPx: Double): Double {
        if (focalPx <= 0 || sigmaPx < 0) return -1.0
        val d = focalPx / (focalPx * focalPx + dxPx * dxPx)
        return Math.toDegrees(d * sigmaPx)
    }

    /** 融合结果 */
    data class Fused(val distanceMm: Double, val sigmaMm: Double, val radarWeight: Double)

    /**
     * 视觉 + 雷达逆方差加权融合。
     *
     * w_i ∝ 1/σ_i²；σ 无效（<0）的一方直接弃用；双方都无效返回视觉值与 -1。
     *
     * @param visualMm 视觉测距（mm）
     * @param visualSigmaMm 视觉测距标准差（mm，[distanceSigma] 给出；<0 表示未知）
     * @param radarMm 雷达测距（mm，<0 表示未接入/无效）
     * @param radarSigmaMm 雷达测距标准差（mm，<0 表示未知，按 1% 距离估）
     */
    fun fuse(
        visualMm: Double,
        visualSigmaMm: Double,
        radarMm: Double,
        radarSigmaMm: Double = -1.0
    ): Fused {
        val radarValid = radarMm > 0
        val visValid = visualMm > 0
        if (!radarValid) {
            return Fused(visualMm, if (visValid) visualSigmaMm else -1.0, 0.0)
        }
        // 未知 σ 时按经验估：视觉 10% 距离，雷达 1% 距离（毫米波/红外测距通常更稳）
        val sv = if (visValid && visualSigmaMm > 0) visualSigmaMm else visualMm * 0.10
        val sr = if (radarSigmaMm > 0) radarSigmaMm else radarMm * 0.01
        if (!visValid) return Fused(radarMm, sr, 1.0)

        val wv = 1.0 / (sv * sv)
        val wr = 1.0 / (sr * sr)
        val sum = wv + wr
        val d = (wv * visualMm + wr * radarMm) / sum
        val sigma = kotlin.math.sqrt(1.0 / sum)
        return Fused(d, sigma, wr / sum)
    }

    /**
     * 由水平 FOV 估算焦距（像素）。
     *
     * @param imageWidth 图像宽（像素）
     * @param hFovDeg 水平视场角（度）
     */
    fun focalFromFov(imageWidth: Int, hFovDeg: Double): Double {
        val halfRad = Math.toRadians(hFovDeg / 2.0)
        return (imageWidth / 2.0) / tan(halfRad)
    }

    /**
     * 弹道俯仰补偿（简化抛物线模型）。
     *
     * 弹丸受重力下坠，远距离需要抬高枪管：
     *   drop = g·d² / (2·v²)   （d 距离，v 初速）
     *   pitchComp = atan(drop / d)
     *
     * @param distanceMm 目标距离（mm）
     * @param muzzleVelocity 弹丸初速（m/s，摩擦轮转速决定）
     * @return 需要额外抬起的角度（度，正=抬高）
     */
    fun ballisticPitchCompensation(distanceMm: Double, muzzleVelocity: Double): Double {
        if (muzzleVelocity <= 0 || distanceMm <= 0) return 0.0
        val d = distanceMm / 1000.0  // mm → m
        val g = 9.81
        val drop = g * d * d / (2.0 * muzzleVelocity * muzzleVelocity)
        return Math.toDegrees(atan2(drop, d))
    }
}
