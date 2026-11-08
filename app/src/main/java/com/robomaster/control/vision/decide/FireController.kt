package com.robomaster.control.vision.decide

import com.robomaster.control.vision.solve.Pinhole
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自动开火控制器（框架）。
 *
 * 职责：把视觉检测的目标（距离/角度）转换为执行器指令：
 * 1. **弹道解算**：由距离计算弹丸飞行时间与下坠，给出枪管俯仰补偿角
 * 2. **摩擦轮速率**：由所需初速换算摩擦轮转速（RPM）
 * 3. **开火决策**：角度误差在容差内 + 距离在有效射程内 → 允许击发
 * 4. **红外雷达融合**：接口预留（雷达测距校正视觉测距，抗视觉失效）
 *
 * 输出通道：经 SerialBridge → CH343 → 下位机（ESP32-S3）。
 * 协议帧格式见 PROTOCOL.md（串口桥接部分）。
 *
 * ⚠️ 当前为框架实现：弹道参数为估值，需实机标定；
 *    红外雷达数据接入后启用融合校正。
 */
object FireController {

    /** 弹道参数（估值，需实机标定） */
    data class Ballistics(
        val muzzleVelocityMin: Double = 15.0,   // 最小初速 m/s（最近距离）
        val muzzleVelocityMax: Double = 25.0,   // 最大初速 m/s（最远距离）
        val effectiveRangeMm: Double = 8000.0,  // 有效射程 mm
        val frictionWheelDiameterMm: Double = 60.0,  // 摩擦轮直径 mm
        val yawToleranceDeg: Double = 1.5,      // 偏航容差（度）
        val pitchToleranceDeg: Double = 1.5,    // 俯仰容差（度）
        val fireCooldownMs: Long = 500,         // 开火冷却 ms
        val maxRangeSigmaRel: Double = 0.25     // 测距相对不确定度上限（σ_d/d）
    )

    /** 开火指令 */
    data class FireCommand(
        val yawDeg: Double,           // 枪管目标偏航角（相对当前朝向）
        val pitchDeg: Double,         // 枪管目标俯仰角（含弹道补偿）
        val frictionWheelRpm: Int,    // 摩擦轮目标转速 RPM
        val shouldFire: Boolean,      // 是否击发
        val reason: String,           // 决策说明（调试用）
        val distSigmaMm: Double = -1.0,  // 测距标准差（mm，-1=未知）
        val radarWeight: Double = 0.0    // 融合中雷达的权重（0=纯视觉）
    )

    private var lastFireTime = 0L

    /**
     * 生成开火指令。
     *
     * @param distanceMm 目标距离（mm，视觉或雷达测距）
     * @param yawDeg 目标偏航角（度，右为正）
     * @param pitchDeg 目标俯仰角（度，上为正，未含弹道补偿）
     * @param radarDistanceMm 红外雷达测距（mm，-1=无效/未接入）
     * @param nowMs 当前时间戳（ms）
     * @param b 弹道参数
     * @param pixelSize 目标像素尺寸（px，>0 时按一阶传播算 σ_d；否则按 10% 估）
     * @param pixelSigmaPx 像素尺寸标准差（px，亚像素精定位后取 0.2 左右）
     * @param radarSigmaMm 雷达测距标准差（mm，<0 按 1% 估）
     */
    fun compute(
        distanceMm: Double,
        yawDeg: Double,
        pitchDeg: Double,
        radarDistanceMm: Double = -1.0,
        nowMs: Long = System.currentTimeMillis(),
        b: Ballistics = Ballistics(),
        pixelSize: Double = -1.0,
        pixelSigmaPx: Double = 0.5,
        radarSigmaMm: Double = -1.0
    ): FireCommand {
        // 视觉测距不确定度（§1.2 一阶传播）：σ_d = (d/p)·σ_p
        val visSigma = if (pixelSize > 0) {
            Pinhole.distanceSigma(distanceMm, pixelSize, pixelSigmaPx)
        } else {
            if (distanceMm > 0) distanceMm * 0.10 else -1.0
        }

        // 雷达融合（§1.7 逆方差加权：谁准听谁的，融合后方差必减小）
        val fused = Pinhole.fuse(distanceMm, visSigma, radarDistanceMm, radarSigmaMm)
        val dist = fused.distanceMm
        val sigma = fused.sigmaMm

        // 射程检查
        if (dist <= 0 || dist > b.effectiveRangeMm) {
            return FireCommand(yawDeg, pitchDeg, 0, false,
                "超射程 d=${dist.toInt()}mm", sigma, fused.radarWeight)
        }

        // 测距置信检查：相对不确定度太大时，弹道补偿本身就是噪声，不能击发
        val relSigma = if (sigma > 0) sigma / dist else Double.MAX_VALUE
        if (relSigma > b.maxRangeSigmaRel) {
            return FireCommand(yawDeg, pitchDeg, 0, false,
                "测距置信不足 σ/d=%.0f%% (d=%.0fmm σ=%.0fmm)"
                    .format(relSigma * 100, dist, sigma), sigma, fused.radarWeight)
        }

        // 初速选择：距离越远初速越大（线性插值）
        val v = b.muzzleVelocityMin +
                (b.muzzleVelocityMax - b.muzzleVelocityMin) *
                (dist / b.effectiveRangeMm).coerceIn(0.0, 1.0)

        // 弹道俯仰补偿
        val comp = Pinhole.ballisticPitchCompensation(dist, v)
        val pitchTotal = pitchDeg + comp

        // 摩擦轮转速：v = π·D·rpm/60 → rpm = 60·v/(π·D)
        val dM = b.frictionWheelDiameterMm / 1000.0
        val rpm = (60.0 * v / (PI * dM)).roundToInt()

        // 开火决策：角度容差 + 冷却
        // 注意：容差判的是"枪口是否已经对准目标"（目标相对光轴的偏角），
        // 不是判 pitchTotal——后者含弹道补偿（前馈量，6000mm 处约 3°），
        // 拿补偿后的绝对角去比 1.5° 容差会永远超差。
        val angleOk = abs(yawDeg) < b.yawToleranceDeg && abs(pitchDeg) < b.pitchToleranceDeg
        val cooled = nowMs - lastFireTime >= b.fireCooldownMs
        val shouldFire = angleOk && cooled
        if (shouldFire) lastFireTime = nowMs

        val reason = when {
            !angleOk -> "角度超差 yaw=%.1f pitch=%.1f".format(yawDeg, pitchTotal)
            !cooled -> "冷却中"
            else -> "允许击发 d=%.0fmm±%.0fmm v=%.1fm/s 雷达权重%.0f%%"
                .format(dist, sigma, v, fused.radarWeight * 100)
        }
        return FireCommand(yawDeg, pitchTotal, rpm, shouldFire, reason, sigma, fused.radarWeight)
    }

    /**
     * 序列化开火指令为串口帧（经 CH343 发下位机）。
     *
     * 帧格式（与 PROTOCOL.md 串口桥接对齐）：
     *   [0xFA][0xAF][yawL][yawH][pitchL][pitchH][rpmL][rpmH][fire][xor]
     *   角度单位 0.01°，RPM 原始值，fire 0/1
     */
    fun serialize(cmd: FireCommand): ByteArray {
        val yaw = (cmd.yawDeg * 100).toInt().coerceIn(-32768, 32767)
        val pitch = (cmd.pitchDeg * 100).toInt().coerceIn(-32768, 32767)
        val rpm = cmd.frictionWheelRpm.coerceIn(0, 65535)
        val fire = if (cmd.shouldFire) 1 else 0
        val frame = ByteArray(10)
        frame[0] = 0xFA.toByte()
        frame[1] = 0xAF.toByte()
        frame[2] = (yaw and 0xFF).toByte()
        frame[3] = ((yaw shr 8) and 0xFF).toByte()
        frame[4] = (pitch and 0xFF).toByte()
        frame[5] = ((pitch shr 8) and 0xFF).toByte()
        frame[6] = (rpm and 0xFF).toByte()
        frame[7] = ((rpm shr 8) and 0xFF).toByte()
        frame[8] = fire.toByte()
        var xor = 0
        for (i in 0 until 9) xor = xor xor (frame[i].toInt() and 0xFF)
        frame[9] = xor.toByte()
        return frame
    }
}
