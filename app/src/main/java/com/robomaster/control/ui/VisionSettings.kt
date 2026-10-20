package com.robomaster.control.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * 视觉功能设置（SharedPreferences "vision_prefs"）。
 *
 * 对应设置界面的开关组：图像预处理（去模糊/平滑/去辉光）、目标检测
 * （高地靶/基地靶）、目标追踪（卡尔曼）、自动开火（弹道解算/摩擦轮速率）、
 * 导航（占位）。所有开关默认关闭，由用户在设置界面按需开启。
 *
 * 与 [Settings]（图传/桥接配置）分开存放，互不影响。
 */
class VisionSettings private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("vision_prefs", Context.MODE_PRIVATE)

    // ─────────── 图像预处理 ───────────

    /** 去模糊（维纳反卷积，周期性在低分辨率上跑） */
    var deblurEnabled: Boolean
        get() = sp.getBoolean("deblur", false)
        set(v) = sp.edit().putBoolean("deblur", v).apply()

    /** 图像平滑（双边滤波，保边去噪） */
    var smoothingEnabled: Boolean
        get() = sp.getBoolean("smoothing", false)
        set(v) = sp.edit().putBoolean("smoothing", v).apply()

    /** 去辉光（强光干扰抑制：高光检测 + 修复） */
    var deglareEnabled: Boolean
        get() = sp.getBoolean("deglare", false)
        set(v) = sp.edit().putBoolean("deglare", v).apply()

    /**
     * V 通道 CLAHE 归一化（颜色分割前提亮暗目标）。
     * 2026-10 公开数据集实测：暗光下不做归一化红色掩码覆盖率仅 3.9%，
     * 开启后 55.9%。默认开启。
     */
    var vNormalizeEnabled: Boolean
        get() = sp.getBoolean("v_normalize", true)
        set(v) = sp.edit().putBoolean("v_normalize", v).apply()

    // ─────────── 目标检测 ───────────

    /** 高地靶检测（4 面板绿框标靶板） */
    var highlandTargetEnabled: Boolean
        get() = sp.getBoolean("detect_highland", false)
        set(v) = sp.edit().putBoolean("detect_highland", v).apply()

    /** 基地靶检测（红圆靶：3 固定 + 1 移动） */
    var baseTargetEnabled: Boolean
        get() = sp.getBoolean("detect_base", false)
        set(v) = sp.edit().putBoolean("detect_base", v).apply()

    /**
     * 检测处理频率：每 N 帧跑一次视觉管线（1=每帧）。
     * 手机算力有限，640×480 全管线约 30-50ms/帧，默认每 3 帧一次（≈10fps 处理）。
     */
    var detectEveryNFrames: Int
        get() = sp.getInt("detect_every_n", 3)
        set(v) = sp.edit().putInt("detect_every_n", v.coerceIn(1, 30)).apply()

    // ─────────── 目标追踪 ───────────

    /** 卡尔曼滤波追踪（CV 模型：匀速直线） */
    var trackingEnabled: Boolean
        get() = sp.getBoolean("tracking", false)
        set(v) = sp.edit().putBoolean("tracking", v).apply()

    // ─────────── 自动开火 ───────────

    /** 自动开火（弹道解算 + 摩擦轮速率输出；红外雷达数据接入后生效） */
    var autoFireEnabled: Boolean
        get() = sp.getBoolean("auto_fire", false)
        set(v) = sp.edit().putBoolean("auto_fire", v).apply()

    // ─────────── 导航 ───────────

    /** 导航（占位：等机械组完成机器人框架后开工） */
    var navigationEnabled: Boolean
        get() = sp.getBoolean("navigation", false)
        set(v) = sp.edit().putBoolean("navigation", v).apply()

    companion object {
        @Volatile private var inst: VisionSettings? = null

        fun get(context: Context): VisionSettings =
            inst ?: synchronized(this) { inst ?: VisionSettings(context).also { inst = it } }
    }
}
