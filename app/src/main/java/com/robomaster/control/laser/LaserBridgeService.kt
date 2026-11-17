package com.robomaster.control.laser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.robomaster.control.R
import com.robomaster.control.ui.MainActivity
import com.robomaster.control.ui.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 激光 + 主控混合桥前台服务。
 *
 * - 前台运行（保活，应用退后台/系统回收时不易被杀）；
 * - START_STICKY：被系统回收后自动重启（Intent 为 null 时用上次配置恢复）；
 * - 内部 [LaserBridge] 自带 USB 断开自动重连 + TCP 自动重连。
 *
 * 与无障碍服务 [com.robomaster.control.ui.AutoRecoverAccessibilityService]、
 * shell 脚本三保险配合，覆盖极端工况自动恢复。
 */
class LaserBridgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bridge: LaserBridge? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = Settings.get(this)
        val host = s.host.ifBlank { intent?.getStringExtra("host") ?: "" }
        if (host.isBlank()) {
            // 没配电脑 IP：仍保持前台，等配置后重试（无障碍/shell 可拉回设置界面）
            return START_STICKY
        }
        val baud = intent?.getIntExtra("baud", s.laserBaud) ?: s.laserBaud
        val mode = LaserBridge.TransportMode.values().getOrElse(
            intent?.getIntExtra("mode", s.laserMode) ?: s.laserMode
        ) { LaserBridge.TransportMode.AUTO }

        bridge?.stop()
        bridge = LaserBridge(this, scope, host, baudRate = baud).apply {
            this.mode = mode
            onLog = { log -> sendLogToUi(log) }
        }
        bridge?.start()
        return START_STICKY
    }

    private fun sendLogToUi(log: String) {
        // 简单起见：日志仅本地；如需 UI 显示可广播
    }

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        createChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("狼牙视觉 · 激光桥")
            .setContentText("激光雷达 + 主控混合桥运行中")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "激光桥", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "laser_bridge"
        private const val NOTIF_ID = 1001

        fun start(ctx: android.content.Context, host: String, baud: Int, mode: Int) {
            val i = Intent(ctx, LaserBridgeService::class.java)
                .putExtra("host", host).putExtra("baud", baud).putExtra("mode", mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }
    }
}
