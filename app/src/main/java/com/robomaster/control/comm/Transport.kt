package com.robomaster.control.comm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 通道状态 */
sealed class ConnState {
    data object Idle : ConnState()
    data class Connecting(val channel: String) : ConnState()
    data class Connected(val channel: String) : ConnState()
    data class Error(val channel: String, val msg: String) : ConnState()
}

/**
 * 传输通道抽象：USB-OTG / WLAN / BLE 统一接口。
 * 断线自动降级切换在 [TransportManager] 里做。
 */
interface Transport {
    val name: String
    val state: StateFlow<ConnState>
    /** 收到的原始字节流 */
    val incoming: MutableSharedFlow<ByteArray>
    suspend fun connect()
    suspend fun disconnect()
    suspend fun send(bytes: ByteArray): Boolean
}

/**
 * 多通道管理：优先级 USB-OTG > WLAN > BLE。
 * 任何通道断开自动尝试下一通道（要求 4：异常自动切换）。
 */
class TransportManager(
    private val usb: Transport,
    private val wlan: Transport,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state

    private val _raw = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
    val raw: SharedFlow<ByteArray> = _raw

    @Volatile private var current: Transport? = null
    private var watchJob: Job? = null
    private val log = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val logs: SharedFlow<String> = log

    /** 按优先级逐个尝试连接 */
    fun start() {
        scope.launch {
            for (t in listOf(usb, wlan)) {
                _state.value = ConnState.Connecting(t.name)
                log.emit("尝试通道 ${t.name}")
                try {
                    t.connect()
                    current = t
                    _state.value = ConnState.Connected(t.name)
                    log.emit("已连接 ${t.name}")
                    watch(t)
                    return@launch
                } catch (e: Exception) {
                    _state.value = ConnState.Error(t.name, e.message ?: "unknown")
                    log.emit("${t.name} 连接失败: ${e.message}")
                }
            }
        }
    }

    /** 监视当前通道，断开后自动切换备用通道 */
    private fun watch(t: Transport) {
        watchJob?.cancel()
        watchJob = scope.launch {
            // 把当前通道的字节流转发出去
            launch { t.incoming.collect { _raw.emit(it) } }
            // 断线检测
            t.state.collect { s ->
                if (s is ConnState.Error || s is ConnState.Idle) {
                    log.emit("${t.name} 断开，切换备用通道")
                    current = null
                    delay(500)
                    start()
                }
            }
        }
    }

    suspend fun send(bytes: ByteArray): Boolean =
        current?.send(bytes) ?: false

    suspend fun stop() {
        watchJob?.cancel()
        current?.disconnect()
        current = null
        _state.value = ConnState.Idle
    }
}
