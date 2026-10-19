package com.robomaster.control.vision

import com.robomaster.control.ui.VisionSettings
import com.robomaster.control.vision.decide.FireController
import com.robomaster.control.vision.detect.TargetDetector
import com.robomaster.control.vision.nav.Navigation
import com.robomaster.control.vision.preprocess.Deblur
import com.robomaster.control.vision.preprocess.Deglare
import com.robomaster.control.vision.preprocess.Smoothing
import com.robomaster.control.vision.track.KalmanFilterCV

/**
 * 视觉主管线：串联 预处理 → 检测 → 追踪 → 决策。
 *
 * 输入 RGB 帧（来自 Camera2 ImageReader），按 [VisionSettings] 的开关
 * 选择性启用各模块，输出检测/追踪/开火结果。
 *
 * 性能预算（640×480）：
 * - 预处理（双边+去辉光）：~10-20ms
 * - 检测（HSV+形态学+连通域）：~15-30ms
 * - 追踪（卡尔曼）：<1ms
 * - 按 detectEveryNFrames 节流（默认每 3 帧），整体 ~10fps 处理
 *
 * 线程模型：由调用方在后台线程调用 [process]，结果经回调回主线程更新 UI。
 */
class VisionPipeline(private val settings: VisionSettings) {

    /** 管线输出结果 */
    data class Result(
        val targets: List<TargetDetector.Target> = emptyList(),
        val trackedTarget: TrackedTarget? = null,
        val fireCommand: FireController.FireCommand? = null,
        val navState: Navigation.State = Navigation.State.IDLE,
        val preprocessMs: Long = 0,
        val detectMs: Long = 0,
        val totalMs: Long = 0
    )

    /** 追踪中的目标（卡尔曼滤波后） */
    data class TrackedTarget(
        val type: TargetDetector.Target.Type,
        val cx: Double,
        val cy: Double,
        val vx: Double,       // 像素/秒
        val vy: Double,
        val distanceMm: Double,
        val yawDeg: Double,
        val pitchDeg: Double,
        val age: Int          // 跟踪帧数
    )

    // 卡尔曼滤波器（每个目标类型一个）
    private val highlandKf = KalmanFilterCV(dt = 0.1)
    private val baseKf = KalmanFilterCV(dt = 0.1)
    private var frameCounter = 0
    private var trackedAge = 0

    /** 最近一次结果（UI 读取） */
    @Volatile
    var lastResult: Result? = null
        private set

    /**
     * 处理一帧。
     *
     * @param rgb RGB 帧（w×h×3）
     * @param w 宽
     * @param h 高
     * @return 处理结果（节流帧返回 lastResult 的副本或空结果）
     */
    fun process(rgb: ByteArray, w: Int, h: Int): Result {
        frameCounter++
        val everyN = settings.detectEveryNFrames
        if (frameCounter % everyN != 0) {
            return lastResult ?: Result()
        }

        val t0 = System.nanoTime()
        var img = rgb

        // ── 预处理 ──
        val tPre = System.nanoTime()
        if (settings.deblurEnabled || settings.smoothingEnabled || settings.deglareEnabled) {
            // 预处理需要灰度图：从 RGB 提取
            val gray = ByteArray(w * h) { i ->
                val r = img[i * 3].toInt() and 0xFF
                val g = img[i * 3 + 1].toInt() and 0xFF
                val b = img[i * 3 + 2].toInt() and 0xFF
                ((r * 30 + g * 59 + b * 11) / 100).toByte()
            }
            var processed = gray
            if (settings.deblurEnabled) {
                // 维纳反卷积周期性跑（每 5 个处理帧一次，降低开销）
                if (frameCounter % (everyN * 5L) == 0L) {
                    processed = Deblur.wienerDeconvolve(processed, w, h, kernelLength = 3, eta = 0.01)
                }
            }
            if (settings.smoothingEnabled) {
                processed = Smoothing.bilateral(processed, w, h, radius = 2)
            }
            if (settings.deglareEnabled) {
                processed = Deglare.process(processed, w, h)
            }
            // 灰度回填 RGB（检测用颜色掩码，但预处理后的灰度也保留在 R 通道）
            // 注：颜色检测仍用原 RGB；预处理结果用于调试显示与后续算法扩展
            img = ByteArray(w * h * 3) { i ->
                val v = processed[i / 3]
                if (i % 3 == 0) v else 0
            }
        }
        val preprocessMs = (System.nanoTime() - tPre) / 1_000_000

        // ── 检测 ──
        val tDet = System.nanoTime()
        val cam = TargetDetector.CameraParams(w, h)
        val targets = ArrayList<TargetDetector.Target>()
        if (settings.highlandTargetEnabled) {
            targets.addAll(TargetDetector.detectHighland(rgb, w, h, cam))
        }
        if (settings.baseTargetEnabled) {
            targets.addAll(TargetDetector.detectBase(rgb, w, h, cam))
        }
        val detectMs = (System.nanoTime() - tDet) / 1_000_000

        // ── 追踪 ──
        var tracked: TrackedTarget? = null
        if (settings.trackingEnabled && targets.isNotEmpty()) {
            // 选置信度最高的目标跟踪
            val best = targets.maxByOrNull { it.confidence }!!
            val kf = when (best.type) {
                TargetDetector.Target.Type.HIGHLAND -> highlandKf
                TargetDetector.Target.Type.BASE -> baseKf
            }
            kf.predict()
            kf.update(best.cx, best.cy)
            val (px, py) = kf.position()
            val (vx, vy) = kf.velocity()
            trackedAge++
            tracked = TrackedTarget(
                type = best.type,
                cx = px, cy = py,
                vx = vx, vy = vy,
                distanceMm = best.distanceMm,
                yawDeg = best.yawDeg,
                pitchDeg = best.pitchDeg,
                age = trackedAge
            )
        } else {
            trackedAge = 0
        }

        // ── 开火决策 ──
        var fireCmd: FireController.FireCommand? = null
        if (settings.autoFireEnabled && tracked != null && tracked.distanceMm > 0) {
            fireCmd = FireController.compute(
                distanceMm = tracked.distanceMm,
                yawDeg = tracked.yawDeg,
                pitchDeg = tracked.pitchDeg
                // radarDistanceMm 待红外雷达接入后传入
            )
        }

        // ── 导航（占位） ──
        val navState = if (settings.navigationEnabled) Navigation.update().state
        else Navigation.State.IDLE

        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val result = Result(
            targets = targets,
            trackedTarget = tracked,
            fireCommand = fireCmd,
            navState = navState,
            preprocessMs = preprocessMs,
            detectMs = detectMs,
            totalMs = totalMs
        )
        lastResult = result
        return result
    }

    /** 重置追踪状态（切换目标类型/重新启动时调用） */
    fun reset() {
        frameCounter = 0
        trackedAge = 0
        lastResult = null
    }
}
