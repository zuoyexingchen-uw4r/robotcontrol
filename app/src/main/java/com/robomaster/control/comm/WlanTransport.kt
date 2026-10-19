package com.robomaster.control.comm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * WLAN 备用通道：TCP Socket 直连 ESP32-S3 的 AP/STA。
 * 默认假设 ESP32-S3 做软 AP，地址 192.168.4.1，端口 8888（需跟电控核对）。
 * 同样走 [Protocol] 帧，只是底层从 USB 换成 TCP。
 */
class WlanTransport(
    private val host: String = "192.168.4.1",
    private val tcpPort: Int = 8888,
    private val scope: CoroutineScope,
) : Transport {

    override val name = "WLAN"
    override val state = MutableStateFlow<ConnState>(ConnState.Idle)
    override val incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512)

    private var socket: Socket? = null

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.connect(InetSocketAddress(host, tcpPort), 3000)
            s.soTimeout = 50
            s.tcpNoDelay = true          // 控制指令要低延迟
            socket = s
            state.value = ConnState.Connected(name)
        }
        // 读循环
        scope.launch(Dispatchers.IO) {
            val s = socket ?: return@launch
            val buf = ByteArray(4096)
            try {
                val ins = s.getInputStream()
                while (state.value is ConnState.Connected) {
                    val n = ins.read(buf)
                    if (n > 0) incoming.emit(buf.copyOf(n))
                    if (n < 0) break
                }
                state.value = ConnState.Error(name, "socket closed")
            } catch (e: Exception) {
                state.value = ConnState.Error(name, e.message ?: "wlan read error")
            }
        }
    }

    override suspend fun disconnect() {
        runCatching { socket?.close() }
        socket = null
        state.value = ConnState.Idle
    }

    override suspend fun send(bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val s = socket ?: return@withContext false
        try {
            s.getOutputStream().apply { write(bytes); flush() }
            true
        } catch (e: Exception) {
            state.value = ConnState.Error(name, e.message ?: "wlan write error")
            false
        }
    }
}
