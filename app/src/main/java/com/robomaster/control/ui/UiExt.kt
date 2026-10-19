package com.robomaster.control.ui

import android.app.Activity
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 系统栏（状态栏 / 导航栏 / 刘海）适配 + 屏幕常亮。
 *
 * 背景：targetSdk 36 + Android 15 默认 edge-to-edge，内容会延伸到状态栏底下，
 * 导致顶栏按钮被状态栏遮住。这里统一处理：
 *
 * 1. [applySystemBarsInsets]：给 content 根视图按**实测**的系统栏 inset 加 padding，
 *    自动适配状态栏高度、导航栏高度、刘海/挖孔，不硬编码任何尺寸。
 * 2. [keepScreenOn]：加 FLAG_KEEP_SCREEN_ON 阻止锁屏熄屏。用 window flag 而不是
 *    WakeLock——系统推荐做法，不会因忘记释放而泄漏，也不受后台省电策略影响。
 */

/** 按实测系统栏 inset 给内容根视图加 padding，避免顶栏被状态栏遮挡 */
fun Activity.applySystemBarsInsets() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    // 先请求一次，确保首次布局就拿到 insets（部分 ROM 不会主动回调）
    WindowCompat.setDecorFitsSystemWindows(window, false)
    ViewCompat.requestApplyInsets(content)
}

/** 阻止锁屏：屏幕常亮（Activity 级，随窗口销毁自动失效） */
fun Activity.keepScreenOn() {
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}
