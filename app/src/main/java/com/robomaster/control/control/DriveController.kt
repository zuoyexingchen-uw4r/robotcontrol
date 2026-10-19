package com.robomaster.control.control

import kotlin.math.abs
import kotlin.math.max

/**
 * 底盘运动学：摇杆 (x, y) → 左右轮速。
 * 差速底盘，x=前后，y=左右转向。
 * 输出范围 [-1000, 1000]（对应固件 PWM/速度环输入，具体标定跟电控对）。
 */
object DriveController {

    const val MAX_OUTPUT = 1000

    data class Wheels(val left: Int, val right: Int)

    fun fromJoystick(x: Float, y: Float): Wheels {
        // 死区
        val jx = if (abs(x) < 0.08f) 0f else x
        val jy = if (abs(y) < 0.08f) 0f else y
        val throttle = clamp(jy)          // 前+
        val turn = clamp(jx)              // 左转+
        val l = throttle - turn
        val r = throttle + turn
        // 归一化防溢出
        val peak = max(1f, max(abs(l), abs(r)))
        return Wheels(
            left = (l / peak * MAX_OUTPUT).toInt(),
            right = (r / peak * MAX_OUTPUT).toInt(),
        )
    }

    fun zero() = Wheels(0, 0)

    private fun clamp(v: Float) = v.coerceIn(-1f, 1f)
}
