package com.robomaster.control.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 「电脑开端口监听、手机主动连接」的 TCP 通道（带自动重连）。
 *
 * 与旧实现的区别：旧实现是手机开 ServerSocket 等电脑 connect；现在统一反过来——
 * **电脑监听端口，手机主动 connect**。好处是电脑侧不用维护服务端生命周期，
 * 手机侧断线自动重连，链路方向单一，不会出现"两边都在等对方"的死锁。
 *
 * 用途：
 *  - 串口透传（CH343 ⇄ 电脑）：[DataChannel]
 *  - 预留第二数据流通道：[DataChannel] 第二实例
 *  - 图传控制通道（关键帧请求 SS_RFI_REQUEST）：CameraStreamer 内部使用
 *
 * 线程模型：连接 + 读循环都在 IO 协程里，[send] 可跨线程调用。
 */
class TcpChannel(
    private val scope: CoroutineScope,
    private val host: String,
    private val port: Int,
    private val label: String = "TCP",
    private val reconnectMs: Long = 2000L,
) {
    var onLog: (String) -> Unit = {}

    /** 收到电脑发来的字节 */
    var onData: (ByteArray) -> Unit = {}

    /** 连接状态变化（true=已连接） */
    var onState: (Boolean) -> Unit = {}

    val connected = MutableStateFlow(false)

    @Volatile private var stopped = true
    private var sock: Socket? = null

    fun start() {
        if (!stopped) return
        stopped = false
        scope.launch { run() }
    }

    fun stop() {
        stopped = true
        runCatching { sock?.close() }
        sock = null
        if (connected.value) {
            connected.value = false
            onState(false)
        }
    }

    private suspend fun run() = withContext(Dispatchers.IO) {
        while (!stopped) {
            var s: Socket? = null
            try {
                s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), 5000)
                sock = s
                connected.value = true
                onState(true)
                onLog("[$label] 已连接电脑 $host:$port")

                val buf = ByteArray(8192)
                val inp = s.getInputStream()
                while (!stopped) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    if (n > 0) onData(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (!stopped) onLog("[$label] 连接失败/中断：${e.message}（${reconnectMs}ms 后重连）")
            } finally {
                runCatching { s?.close() }
                if (sock === s) sock = null
                if (connected.value) {
                    connected.value = false
                    onState(false)
                    onLog("[$label] 已断开")
                }
            }
            if (!stopped) delay(reconnectMs)
        }
    }

    /** 发送到电脑；未连接时返回 false（调用方决定丢弃或缓存） */
    fun send(bytes: ByteArray): Boolean {
        val s = sock ?: return false
        return runCatching {
            s.getOutputStream().write(bytes)
            s.getOutputStream().flush()
            true
        }.getOrDefault(false)
    }
}
