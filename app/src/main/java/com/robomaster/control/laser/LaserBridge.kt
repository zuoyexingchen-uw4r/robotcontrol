package com.robomaster.control.laser

import android.content.Context
import com.robomaster.control.bridge.TcpChannel
import com.robomaster.control.comm.ConnState
import com.robomaster.control.comm.UsbSerialTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 激光雷达 + 主控板混合桥（手机 USB-host 适度解析 → TCP/UDP → 上位机）。
 *
 * 架构：ESP32 经 UART 把「雷达 60 字节帧 + 主控 60 字节帧」混在一起发到 CH343，
 * 本桥从 USB 串口读出字节流 → [LaserStreamFramer] 切帧（不解析内容）→ 打 1 字节
 * 类型头 → 串进 TCP/UDP 管道。下行主控帧（上位机→手机）打 [LaserProtocol.T_MASTER_DOWN]
 * 头透传下 UART，手机同样不解析。
 *
 * **USB 自动重连**：独立监督协程监控 [UsbSerialTransport.state]，断开后自动重连
 * （连接失败按 [reconnectMs] 重试）。极端工况下配合无障碍服务/前台服务兜底。
 *
 * **传输自适应**（不重传，坏帧靠 CRC/seq 丢弃感知）：
 * - [TransportMode.TCP]：雷达+主控都走 TCP（可靠，主控指令不丢）。
 * - [TransportMode.UDP]：雷达走 UDP（低延迟、可丢），主控走 TCP。
 * - [TransportMode.AUTO]：TCP 正常时全走 TCP；TCP 掉线时雷达自动降级 UDP。
 */
class LaserBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: String,
    private val tcpPort: Int = LaserProtocol.TCP_PORT,
    private val udpPort: Int = LaserProtocol.UDP_PORT,
    private val baudRate: Int = 230_400,   // 雷达 230400，CH343 ≥ 此值
    private val reconnectMs: Long = 1500L,
) {
    enum class TransportMode { AUTO, TCP, UDP }

    var mode: TransportMode = TransportMode.AUTO
    var onLog: (String) -> Unit = {}
    /** (type, frame) 上行帧回调：type ∈ T_MASTER_UP/T_LIDAR，frame 为 60 字节 */
    var onUpFrame: ((Int, ByteArray) -> Unit)? = null
    /** 下行主控帧回调（上位机→手机，已去掉类型头，60 字节） */
    var onDownFrame: ((ByteArray) -> Unit)? = null

    @Volatile private var running = false
    private val transport = UsbSerialTransport(context, scope, baudRate)
    private val framer = LaserStreamFramer()

    private var tcp: TcpChannel? = null
    private var udp: DatagramSocket? = null
    private val udpLock = Any()
    private val downBuf = ByteArrayOutputStream()

    /** 网络(TCP)连接状态 */
    val tcpConnected = MutableStateFlow(false)

    fun start() {
        if (running) return
        running = true

        startTcp()
        startUdp()
        scope.launch { supervise() }
        scope.launch(Dispatchers.IO) {
            transport.incoming.onEach { onUsbBytes(it) }.launchIn(this)
        }
    }

    fun stop() {
        running = false
        tcp?.stop(); tcp = null
        closeUdp()
        scope.launch { runCatching { transport.disconnect() } }
    }

    // ── 网络：TCP（可靠，主控上下行 + 雷达主力） ──
    private fun startTcp() {
        tcp = TcpChannel(scope, host, tcpPort, label = "激光混合").apply {
            onLog = this@LaserBridge.onLog
            onData = { bytes -> onNetworkBytes(bytes) }
            onState = { ok ->
                tcpConnected.value = ok
                if (!ok) onLog("[激光] TCP 断开，自动重连中…")
            }
        }
        tcp?.start()
    }

    // ── 网络：UDP（雷达旁路，低延迟） ──
    private fun startUdp() {
        if (mode == TransportMode.TCP) return
        synchronized(udpLock) {
            if (udp != null) return
            udp = try {
                DatagramSocket().apply { broadcast = false }
            } catch (_: Exception) { null }
        }
    }

    private fun closeUdp() {
        synchronized(udpLock) {
            runCatching { udp?.close() }
            udp = null
        }
    }

    // ── USB 监督：断开自动重连 ──
    private suspend fun supervise() {
        while (running) {
            if (transport.state.value is ConnState.Connected) {
                transport.state.filter { it !is ConnState.Connected }.first()
                onLog("[激光] USB 断开，${reconnectMs}ms 后重连")
                delay(reconnectMs)
            } else {
                try {
                    onLog("[激光] 连接 CH343 (baud=$baudRate) …")
                    transport.connect()
                    if (transport.state.value is ConnState.Connected) {
                        onLog("[激光] USB 已连接")
                    }
                } catch (e: Exception) {
                    if (running) {
                        onLog("[激光] USB 连接失败: ${e.message}，${reconnectMs}ms 后重试")
                        delay(reconnectMs)
                    }
                }
            }
        }
    }

    // ── 上行：USB 字节流 → 切帧 → 打类型头 → 网络 ──
    private fun onUsbBytes(bytes: ByteArray) {
        val frames = ArrayList<LaserStreamFramer.Frame>(4)
        framer.feed(bytes, frames)
        for (f in frames) {
            val type = when (f.type) {
                LaserStreamFramer.FrameType.LIDAR -> LaserProtocol.T_LIDAR
                LaserStreamFramer.FrameType.MASTER -> LaserProtocol.T_MASTER_UP
            }
            onUpFrame?.invoke(type, f.bytes)
            routeUp(type, f.bytes)
        }
    }

    private fun routeUp(type: Int, frame: ByteArray) {
        when (type) {
            LaserProtocol.T_MASTER_UP -> {
                // 主控帧必须可靠：仅 TCP
                if (tcpConnected.value) tcp?.send(LaserProtocol.wrap(type, frame))
            }
            LaserProtocol.T_LIDAR -> {
                when (mode) {
                    TransportMode.TCP -> if (tcpConnected.value) tcp?.send(LaserProtocol.wrap(type, frame))
                    TransportMode.UDP -> sendUdp(frame)
                    TransportMode.AUTO ->
                        if (tcpConnected.value) tcp?.send(LaserProtocol.wrap(type, frame))
                        else sendUdp(frame)
                }
            }
        }
    }

    private fun sendUdp(frame: ByteArray) {
        val s = synchronized(udpLock) { udp } ?: return
        synchronized(udpLock) {
            try {
                val payload = LaserProtocol.wrap(LaserProtocol.T_LIDAR, frame)
                s.send(DatagramPacket(payload, payload.size, InetAddress.getByName(host), udpPort))
            } catch (_: Exception) {
                // UDP 发送失败静默丢弃（不重传）
            }
        }
    }

    // ── 下行：网络字节 → [type][60B] 重组 → 主控下行帧透传下 UART ──
    private fun onNetworkBytes(bytes: ByteArray) {
        downBuf.write(bytes)
        val buf = downBuf.toByteArray()
        downBuf.reset()
        var i = 0
        while (i + 1 + LaserProtocol.FRAME_LEN <= buf.size) {
            val type = buf[i].toInt() and 0xFF
            val frame = buf.copyOfRange(i + 1, i + 1 + LaserProtocol.FRAME_LEN)
            when (type) {
                LaserProtocol.T_MASTER_DOWN -> {
                    onDownFrame?.invoke(frame)
                    // 透传下 UART（原样，不解析）
                    scope.launch { runCatching { transport.send(frame) } }
                }
                else -> { /* 未知类型，跳过 1 字节重找 */ }
            }
            i += 1 + LaserProtocol.FRAME_LEN
        }
        if (i < buf.size) downBuf.write(buf, i, buf.size - i)
    }
}
