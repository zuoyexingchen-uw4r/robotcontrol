package com.robomaster.control.vision.solve

import kotlin.math.atan2
import kotlin.math.tan

/**
 * 针孔相机模型：测距 + 测角（纯 Kotlin）。
 *
 * 原理（课件 3D）：
 *   单目相机缺深度信息，但已知目标**物理尺寸**时可用相似三角形测距：
 *     distance = focal_px × real_size / pixel_size
 *   角度（相对相机光轴）：
 *     yaw   = atan2(dx_px, focal_px)   （水平偏角）
 *     pitch = atan2(dy_px, focal_px)   （垂直偏角）
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
