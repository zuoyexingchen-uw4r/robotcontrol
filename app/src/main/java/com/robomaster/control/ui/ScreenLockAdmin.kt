package com.robomaster.control.ui

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 设备管理器接收器：只用于「锁定屏幕」。
 *
 * 车体在测试台上运行时屏幕常亮，比赛间隙需要一键锁屏省电、防止误触；
 * 普通应用没有锁屏权限，必须走 DevicePolicyManager + force-lock 策略。
 * 只申请 force-lock，不申请擦除/改密码等危险策略。
 */
class ScreenLockAdmin : DeviceAdminReceiver()

/** 锁屏辅助：申请 / 判断 / 执行 */
object ScreenLock {

    fun component(ctx: Context): ComponentName = ComponentName(ctx, ScreenLockAdmin::class.java)

    fun isAdmin(ctx: Context): Boolean {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return runCatching { dpm.isAdminActive(component(ctx)) }.getOrDefault(false)
    }

    /** 已授权则立即锁屏；返回 false 表示需要先申请设备管理器 */
    fun lock(ctx: Context): Boolean {
        if (!isAdmin(ctx)) return false
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return runCatching {
            dpm.lockNow()
            true
        }.getOrDefault(false)
    }

    /** 拉起系统的设备管理器授权页 */
    fun requestAdmin(activity: Activity) {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(activity))
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "允许「狼牙视觉」锁定屏幕：比赛间隙一键熄屏，不影响图传与视觉链路。",
            )
        }
        runCatching { activity.startActivity(intent) }
    }
}
