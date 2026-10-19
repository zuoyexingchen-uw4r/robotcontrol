package com.robomaster.control.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * 虚拟摇杆：按下拖动，松开自动回中。
 * 回调输出归一化 (x, y) ∈ [-1, 1]，x 右+，y 下+。
 */
class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onMove: ((Float, Float) -> Unit)? = null
    var onRelease: (() -> Unit)? = null

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2A38"); style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2FA8FF"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5252"); style = Paint.Style.FILL
    }

    private var knobX = 0f
    private var knobY = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        radius = minOf(w, h) / 2f * 0.8f
        knobX = centerX(); knobY = centerY()
    }

    private fun centerX() = width / 2f
    private fun centerY() = height / 2f

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(centerX(), centerY(), radius, basePaint)
        canvas.drawCircle(centerX(), centerY(), radius, ringPaint)
        canvas.drawCircle(knobX, knobY, radius * 0.32f, knobPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                var dx = e.x - centerX()
                var dy = e.y - centerY()
                val d = hypot(dx, dy)
                if (d > radius) { dx = dx / d * radius; dy = dy / d * radius }
                knobX = centerX() + dx
                knobY = centerY() + dy
                onMove?.invoke(dx / radius, dy / radius)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                knobX = centerX(); knobY = centerY()
                invalidate()
                onRelease?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
