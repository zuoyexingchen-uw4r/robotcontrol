package com.robomaster.control.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 第二数据流通道（预留）。
 *
 * 与 [SerialBridge] 同构：**电脑开端口监听，手机主动连接**，断线自动重连（底层
 * 复用 [TcpChannel]）。区别是数据源可插拔——
 *
 *  - [producer]：要发给电脑的字节从哪来。返回 null 表示暂无数据。
 *    当前默认不接（预留），后续要接第二路串口 / 传感器 / CAN 时，
 *    在 Activity 里赋一个 lambda 即可，无需改动本类。
 *  - [consumer]：电脑发来的字节交给谁处理。同样默认空。
 *
 * 这样这一路就是一条"已接通、可按需注入数据"的空管道，随时可以启用。
 */
class DataBridge(
    private val scope: CoroutineScope,
    private val host: String,
    private val port: Int,
) {
    var onLog: (String) -> Unit = {}
    var onState: (Boolean) -> Unit = {}

    /** 预留：电脑 → 手机 的数据处理钩子 */
    var consumer: ((ByteArray) -> Unit)? = null

    /** 预留：手机 → 电脑 的数据来源钩子（20ms 轮询，返回 null 跳过） */
    var producer: (() -> ByteArray?)? = null

    val connected = MutableStateFlow(false)
    val running = MutableStateFlow(false)

    private var channel: TcpChannel? = null
    private var pollJob: Job? = null

    fun start() {
        if (running.value) return
        running.value = true
        val ch = TcpChannel(scope, host, port, label = "数据流2").apply {
            onLog = this@DataBridge.onLog
            onData = { bytes -> consumer?.invoke(bytes) }
            onState = { ok ->
                connected.value = ok
                onState(ok)
            }
        }
        channel = ch
        ch.start()
        onLog("[数据流2] 连接电脑 $host:$port（数据源预留，接通后按需注入）")

        // producer 轮询：当前未接数据源时是空转，只维持连接
        pollJob = scope.launch {
            while (isActive && running.value) {
                val bytes = producer?.invoke()
                if (bytes != null && bytes.isNotEmpty()) ch.send(bytes)
                delay(20)
            }
        }
    }

    fun stop() {
        running.value = false
        pollJob?.cancel()
        pollJob = null
        channel?.stop()
        channel = null
        connected.value = false
    }

    /** 手动往电脑发一帧（数据源未接 producer 时可直接调用） */
    fun send(bytes: ByteArray): Boolean = channel?.send(bytes) ?: false
}
