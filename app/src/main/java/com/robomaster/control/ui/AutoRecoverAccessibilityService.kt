package com.robomaster.control.ui

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 自动恢复无障碍服务（极端工况兜底，仅局域网设备使用）。
 *
 * 用途：比赛/无人值守时，系统偶尔弹「USB 授权」「应用停止」「ANR」等对话框，
 * 或应用被挤到后台/系统回收，导致视觉与激光链路中断。本服务在 shell/无障碍层兜底：
 *
 * 1. 检测到 USB 授权对话框 → 自动点「确定/允许」；
 * 2. 检测到「应用无响应 / 已停止」对话框 → 自动点「关闭/确定」；
 * 3. 检测到本应用不在前台（TYPE_WINDOW_STATE_CHANGED 且非本包名）→ 周期性尝试拉回前台；
 * 4. 收到自定义广播 `ACTION_RECOVER` → 立即执行一次完整恢复（重启前台服务 + 拉回主界面）。
 *
 * 安全说明：**本设备仅局域网运行、无攻击面**，故放开自动点击。通用场景请勿照搬。
 */
class AutoRecoverAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastRecoverAt = 0L

    /** 监听 adb/shell 发来的恢复广播 */
    private val recoverReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_RECOVER) {
                recoverToForeground()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        registerReceiver(recoverReceiver, IntentFilter(ACTION_RECOVER))
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(recoverReceiver) }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                // 应用被切到后台时，尝试拉回（冷却 3s 防止抖动）
                if (pkg != packageName && System.currentTimeMillis() - lastRecoverAt > 3000) {
                    recoverToForeground()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                maybeDismissBlockingDialog(event)
            }
        }
    }

    override fun onInterrupt() {}

    /** 自动点击 USB 授权 / ANR / 已停止 等阻断性对话框的「确定/允许/关闭」按钮 */
    private fun maybeDismissBlockingDialog(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        // 只处理系统 UI 的对话框（com.android.systemui / android 包）
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.android.systemui" && pkg != "android" && pkg != packageName) return

        val targets = listOf("允许", "确定", "OK", "Allow", "关闭", "Close", "取消关闭")
        val node = findClickable(root, targets) ?: return
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        lastRecoverAt = System.currentTimeMillis()
    }

    private fun findClickable(
        node: AccessibilityNodeInfo,
        texts: List<String>,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(node)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            val t = n.text?.toString() ?: ""
            if (n.isClickable && texts.any { t.contains(it) }) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    /** 把应用拉回前台（重启 MainActivity，singleTask 会复用现有实例） */
    private fun recoverToForeground() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            lastRecoverAt = System.currentTimeMillis()
        } catch (_: Exception) {
            // 忽略（无前台权限等）
        }
    }

    companion object {
        const val ACTION_RECOVER = "com.robomaster.control.action.RECOVER"
    }
}
