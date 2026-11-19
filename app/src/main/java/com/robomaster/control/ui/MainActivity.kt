package com.robomaster.control.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.robomaster.control.ui.console.ConsoleScreen
import com.robomaster.control.ui.console.ConsoleViewModel
import com.robomaster.control.ui.theme.LangyaTheme

/**
 * 第一屏：横屏平板控制台（Material 3 Expressive 浅色）。
 *
 * 责任划分：
 * - UI 全是 Compose（[ConsoleScreen]），本类只做窗口、权限、二级页面跳转；
 * - 所有业务（图传 / 视觉 / 串口 / 非图传流 / 系统状态）都在 [ConsoleViewModel]；
 * - 旧的 XML 控制台（BridgeActivity / VisionSettingsActivity）保留为二级页面。
 *
 * 保留 `screenOrientation=landscape` 与 USB_DEVICE_ATTACHED 过滤器：
 * 插上 CH343 时系统会直接拉起本界面。
 */
class MainActivity : ComponentActivity() {

    private val vm: ConsoleViewModel by viewModels()

    /** 相机权限：授权后继续启动图传 */
    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            vm.startStream()
        } else {
            Toast.makeText(this, "相机权限被拒绝，无法启动图传与视觉", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 浅色控制台：系统栏透明 + 深色图标（不做深色模式）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        keepScreenOn()

        vm.onRequestCameraPermission = { camPerm.launch(Manifest.permission.CAMERA) }

        setContent {
            LangyaTheme {
                ConsoleScreen(
                    vm = vm,
                    onOpenVisionSettings = {
                        startActivity(Intent(this, VisionSettingsActivity::class.java))
                    },
                    onOpenBridge = {
                        startActivity(Intent(this, BridgeActivity::class.java))
                    },
                    onOpenAccessibility = { openAccessibilitySettings() },
                    onLockScreen = { lockScreen() },
                    onExit = { finish() },
                )
            }
        }

        // 首次进入自动检查相机权限与 USB 设备，给出明确提示而不是静默失败
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            vm.log("[权限] 尚未授予相机权限，点「启动图传」时会申请")
        }
        vm.log("[USB] ${describeUsb()}")
    }

    private fun describeUsb(): String {
        val usb = getSystemService(USB_SERVICE) as android.hardware.usb.UsbManager
        val s = com.robomaster.control.ui.Settings.get(this)
        val dev = usb.deviceList.values.firstOrNull { it.vendorId == s.serialVid }
            ?: usb.deviceList.values.firstOrNull()
        return if (dev == null) {
            "未发现串口设备（检查 OTG 线）"
        } else {
            "发现 USB 设备 ${dev.productName ?: dev.deviceName} " +
                "VID=0x%04X PID=0x%04X".format(dev.vendorId, dev.productId)
        }
    }

    /** 打开系统的无障碍服务列表（本 App 的自动恢复服务在里面） */
    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "无法打开无障碍设置：${it.message}", Toast.LENGTH_SHORT).show()
        }
        vm.log("[无障碍] 打开系统无障碍设置")
    }

    /** 锁定屏幕：已授权设备管理器则直接锁屏，否则先申请 */
    private fun lockScreen() {
        if (ScreenLock.lock(this)) {
            vm.log("[电源] 已锁屏")
        } else {
            vm.log("[电源] 需要先授予设备管理器权限，已打开授权页")
            ScreenLock.requestAdmin(this)
        }
    }

    override fun onDestroy() {
        vm.onRequestCameraPermission = null
        super.onDestroy()
    }
}
