package com.robomaster.control.bridge

import com.robomaster.control.comm.ConnState
import com.robomaster.control.comm.UsbSerialTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 串口透传桥：手机(OTG-CH343) ⇄ TCP ⇄ 电脑。
 *
 * **网络方向（2026-10 改）**：电脑开端口监听，手机主动 connect。
 * 旧实现是手机开 ServerSocket 等电脑连，方向相反、且电脑侧要维护服务端；
 * 现在统一由电脑监听，手机断线自动重连（见 [TcpChannel]）。
 *
 * 协议（低延迟、全双工）：
 *  - 传输层就是一条透明全双工字节管道（TCP 本身全双工；USB 串口 read/write 相互独立，
 *    因此天然支持 电脑↔CH343 双向同时通讯，无需额外流控帧）。
 *  - 连上电脑后，手机先发一行文本握手：`RB1 <baud> otg\n`，之后双向原样转发。
 */
class SerialBridge(
    private val scope: CoroutineScope,
    private val transport: UsbSerialTransport,
    private val host: String,
    private val port: Int = 8765,
) {
    var onLog: (String) -> Unit = {}
    val running = MutableStateFlow(false)

    private var channel: TcpChannel? = null

    fun start() {
        if (running.value) return
        running.value = true
        scope.launch { run() }
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        // 1) 先打开 CH343 串口
        try {
            onLog("[桥接] 打开 CH343 (baud=${transport.baudRate}) …")
            transport.connect()
            if (transport.state.value !is ConnState.Connected) {
                onLog("[桥接] 串口未连接：${transport.state.value}")
                running.value = false
                return@withContext
            }
            onLog("[桥接] 串口已打开")
        } catch (e: Exception) {
            onLog("[桥接] 串口打开失败：${e.message}")
            running.value = false
            return@withContext
        }

        // 2) 连接电脑（电脑侧需先开端口监听）
        val ch = TcpChannel(scope, host, port, label = "串口透传").apply {
            onLog = this@SerialBridge.onLog
            // send 是 suspend 的，必须包在协程里（onData 是普通 lambda）
            onData = { bytes -> scope.launch { transport.send(bytes) } }   // 电脑 → CH343
        }
        channel = ch
        ch.start()

        // 3) CH343 → 电脑
        transport.incoming
            .onEach { bytes -> if (ch.connected.value) ch.send(bytes) }
            .launchIn(scope)
    }

    fun stop() {
        running.value = false
        channel?.stop()
        channel = null
        scope.launch { transport.disconnect() }
    }
}
