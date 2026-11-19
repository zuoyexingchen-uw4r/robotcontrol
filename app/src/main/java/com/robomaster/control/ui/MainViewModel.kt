package com.robomaster.control.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robomaster.control.comm.ConnState
import com.robomaster.control.comm.TransportManager
import com.robomaster.control.comm.UsbSerialTransport
import com.robomaster.control.comm.WlanTransport
import com.robomaster.control.control.DriveController
import com.robomaster.control.data.PowerStatus
import com.robomaster.control.data.Sensors
import com.robomaster.control.data.TelemetryRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 第一屏（HMI）状态与通信。
 *
 * 说明：图传已从「MJPEG 拉流 + 本地预览」改为「Camera2 硬编 + GameStream 推流」，
 * 由 [BridgeActivity] 与第一屏的一键启停直接控制（见 CameraStreamer），
 * 本 ViewModel 不再持有图传相关状态；舵机控制已按需求移除。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // ---- 通信层 ----
    private val usb = UsbSerialTransport(app, viewModelScope)
    private val wlan = WlanTransport(scope = viewModelScope)
    private val manager = TransportManager(usb, wlan, viewModelScope)
    private val repo = TelemetryRepository(manager, viewModelScope)

    val connState: StateFlow<ConnState> = manager.state
    val sensors: StateFlow<Sensors> = repo.sensors
    val power: StateFlow<PowerStatus> = repo.power
    val logs = MutableStateFlow<List<String>>(emptyList())

    private var pingJob: Job? = null

    init {
        // 日志聚合（通道日志 + 板端日志），限长 200 行
        viewModelScope.launch {
            repo.logs.collect { line ->
                logs.value = (logs.value + line).takeLast(200)
            }
        }
        manager.start()
        startHeartbeat()
    }

    /** 1Hz 心跳：掉线时 TransportManager 会感知并降级切换 */
    private fun startHeartbeat() {
        pingJob = viewModelScope.launch {
            while (isActive) {
                repo.ping()
                delay(1000)
            }
        }
    }

    // ---- 控制 ----
    private var lastDriveAt = 0L

    /** 摇杆回调：50ms 节流，防止把光耦那头灌死 */
    fun onJoystick(x: Float, y: Float) {
        val now = System.currentTimeMillis()
        if (now - lastDriveAt < 50) return
        lastDriveAt = now
        val w = DriveController.fromJoystick(x, y)
        viewModelScope.launch { repo.drive(w.left, w.right) }
    }

    fun onJoystickRelease() {
        val w = DriveController.zero()
        viewModelScope.launch { repo.drive(w.left, w.right) }
    }

    fun powerSwitch(port: Int, on: Boolean) {
        viewModelScope.launch { repo.powerControl(port, on) }
    }

    /**
     * 视觉开火/导航指令下发（直连下位机）。
     *
     * 车载视觉决策的执行通道：必须走 USB（OTG-CH343），WLAN 降级时不发
     * （开火指令延迟敏感，不走网络）。帧格式见 FireController.serialize。
     */
    fun sendVisionCommand(bytes: ByteArray) {
        // 优先走 USB 持有者（激光桥）的写入入口：设备是独占的，本 ViewModel 另开一个
        // Transport 会抢不到端口。没接桥时退回自己的通道，保持旧行为。
        val writer = com.robomaster.control.net.NetCore.uartWriter
        if (writer != null) {
            writer(bytes)
            return
        }
        viewModelScope.launch {
            if (usb.state.value is ConnState.Connected) {
                usb.send(bytes)
            }
        }
    }

    /** 紧急停止：立即发 ESTOP，同时摇杆清零 */
    fun eStop() {
        viewModelScope.launch {
            repo.eStop()
            repo.drive(0, 0)
        }
    }

    override fun onCleared() {
        pingJob?.cancel()
        // onCleared 时 viewModelScope 已开始取消，不能再依赖它执行清理。
        runBlocking { manager.stop() }
        super.onCleared()
    }
}
