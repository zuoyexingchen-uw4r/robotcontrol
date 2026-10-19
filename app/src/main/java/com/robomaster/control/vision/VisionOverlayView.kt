package com.robomaster.control.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.robomaster.control.vision.decide.FireController
import com.robomaster.control.vision.detect.TargetDetector

/**
 * 视觉检测结果叠加视图。
 *
 * 覆盖在相机预览（SurfaceView）之上，绘制：
 * - 检测框：绿=高地靶面板、红=基地靶（含中心点与尺寸标注）
 * - 追踪框：黄色（卡尔曼滤波后的稳定目标）+ 速度矢量
 * - 准星：屏幕中心 + 开火容差圈（角度误差可视化）
 * - 状态文本：目标类型/距离/偏航角/开火决策
 *
 * 线程模型：数据由 vision-reader 线程写入（@Volatile 字段），
 * onDraw 在主线程读取——无锁，靠 volatile 可见性 + 整体引用替换。
 * 图像坐标 → View 坐标按比例缩放（适应任意预览尺寸）。
 */
class VisionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── 数据（vision-reader 线程写，主线程 onDraw 读）──
    @Volatile var targets: List<TargetDetector.Target> = emptyList()
    @Volatile var tracked: VisionPipeline.TrackedTarget? = null
    @Volatile var fireCmd: FireController.FireCommand? = null
    @Volatile var imageWidth: Int = 0
    @Volatile var imageHeight: Int = 0

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }
    private val trackPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.YELLOW
        isAntiAlias = true
    }
    private val crossPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
        isAntiAlias = true
    }
    private val tolPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.argb(160, 255, 255, 255)
        isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isAntiAlias = true
    }
    private val firePaint = Paint().apply {
        color = Color.RED
        textSize = 34f
        isFakeBoldText = true
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val iw = imageWidth
        val ih = imageHeight
        if (iw <= 0 || ih <= 0) return

        // 图像坐标 → View 坐标缩放（保持比例，居中）
        val scale = minOf(width.toFloat() / iw, height.toFloat() / ih)
        val offX = (width - iw * scale) / 2f
        val offY = (height - ih * scale) / 2f
        fun sx(x: Double) = (offX + x * scale).toFloat()
        fun sy(y: Double) = (offY + y * scale).toFloat()

        // ── 检测框 ──
        for (t in targets) {
            boxPaint.color = when (t.type) {
                TargetDetector.Target.Type.HIGHLAND -> Color.GREEN
                TargetDetector.Target.Type.BASE -> Color.RED
            }
            val half = (t.pixelSize / 2 * scale).toFloat()
            val cx = sx(t.cx)
            val cy = sy(t.cy)
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint)
            // 中心点
            canvas.drawCircle(cx, cy, 4f, boxPaint)
            // 标注
            val label = when (t.type) {
                TargetDetector.Target.Type.HIGHLAND -> "高地靶"
                TargetDetector.Target.Type.BASE -> "基地靶"
            }
            canvas.drawText(
                "$label ${t.distanceMm.toInt()}mm ${"%.1f".format(t.yawDeg)}°",
                cx - half, cy - half - 8f, textPaint
            )
        }

        // ── 追踪框（卡尔曼滤波后）──
        tracked?.let { t ->
            val cx = sx(t.cx)
            val cy = sy(t.cy)
            val half = 30f
            canvas.drawRect(cx - half, cy - half, cx + half, cy + half, trackPaint)
            // 速度矢量（像素/秒 → 屏幕像素，缩放 0.1s 预测）
            val vx = sx(t.cx + t.vx * 0.1) - cx
            val vy = sy(t.cy + t.vy * 0.1) - cy
            canvas.drawLine(cx, cy, cx + vx, cy + vy, trackPaint)
            canvas.drawText("TRACK #${t.age}", cx + half + 6f, cy, textPaint)
        }

        // ── 准星 + 开火容差圈 ──
        val ccx = width / 2f
        val ccy = height / 2f
        canvas.drawLine(ccx - 20f, ccy, ccx + 20f, ccy, crossPaint)
        canvas.drawLine(ccx, ccy - 20f, ccx, ccy + 20f, crossPaint)
        // 容差圈（1.5° ≈ focal_px × tan(1.5°)，focal 按 65° FOV 估算）
        val focalPx = (iw / 2f) / Math.tan(Math.toRadians(32.5)).toFloat()
        val tolRadius = (focalPx * Math.tan(Math.toRadians(1.5)) * scale).toFloat()
        canvas.drawCircle(ccx, ccy, tolRadius, tolPaint)

        // ── 开火状态 ──
        fireCmd?.let { fc ->
            val text = if (fc.shouldFire) "● 开火 ${fc.frictionWheelRpm}rpm" else "○ ${fc.reason}"
            canvas.drawText(text, 16f, height - 16f, firePaint)
        }
    }

    /** 视觉结果回调中调用（任意线程安全） */
    fun update(result: VisionPipeline.Result, w: Int, h: Int) {
        targets = result.targets
        tracked = result.trackedTarget
        fireCmd = result.fireCommand
        imageWidth = w
        imageHeight = h
        postInvalidate()
    }
}
