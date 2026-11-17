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
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 激光雷达 + 主控板混合桥（手机 USB-host 适度解析 → UDP+FEC / TCP → 上位机）。
 *
 * 传输（不重传）：
 * - **上行（雷达 + 主控遥测）走 UDP + Reed-Solomon FEC**：块 = k 数据分片 + m 校验分片，
 *   丢失 ≤m 个分片可由擦除解码恢复，无重传延迟。
 * - **下行（主控指令）走 TCP**：命令低频且关键，可靠传输（唯一保留的可靠通道）。
 *
 * **USB 自动重连**：监督协程监控 [UsbSerialTransport.state]，断开后自动重连。
 * 极端工况下配合前台服务 + 无障碍服务 + adb shell 脚本三保险兜底。
 */
class LaserBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: String,
    private val tcpPort: Int = LaserProtocol.TCP_PORT,
    private val udpPort: Int = LaserProtocol.UDP_PORT,
    private val baudRate: Int = 230_400,   // 雷达 230400，CH343 ≥ 此值
    private val reconnectMs: Long = 1500L,
    private val fecK: Int = LaserProtocol.FEC_K,
    private val fecM: Int = LaserProtocol.FEC_M,
) {
    var onLog: (String) -> Unit = {}
    /** (type, frame) 上行帧回调：type ∈ T_MASTER_UP/T_LIDAR */
    var onUpFrame: ((Int, ByteArray) -> Unit)? = null
    /** 下行主控帧回调（上位机→手机，39 字节） */
    var onDownFrame: ((ByteArray) -> Unit)? = null
    /** 收到「切 IP」预留 UART 帧（赛前设置），参数为目标 IP */
    var onSwitchIpRequest: ((String) -> Unit)? = null

    @Volatile private var running = false
    private val transport = UsbSerialTransport(context, scope, baudRate)
    private val framer = LaserStreamFramer()
    private val fec = LaserFecEncoder(fecK, fecM)

    private var tcp: TcpChannel? = null
    private var udp: DatagramSocket? = null
    private val udpLock = Any()
    private val downBuf = ByteArrayOutputStream()

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
        scope.launch { flushLoop() }
    }

    fun stop() {
        running = false
        tcp?.stop(); tcp = null
        closeUdp()
        scope.launch { runCatching { transport.disconnect() } }
    }

    // ── 网络：TCP（仅下行主控指令，可靠） ──
    private fun startTcp() {
        tcp = TcpChannel(scope, host, tcpPort, label = "激光下行").apply {
            onLog = this@LaserBridge.onLog
            onData = { bytes -> onNetworkBytes(bytes) }
            onState = { ok ->
                tcpConnected.value = ok
                if (!ok) onLog("[激光] TCP 断开，自动重连中…")
            }
        }
        tcp?.start()
    }

    // ── 网络：UDP（上行雷达+主控，FEC） ──
    private fun startUdp() {
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
                    if (transport.state.value is ConnState.Connected) onLog("[激光] USB 已连接")
                } catch (e: Exception) {
                    if (running) {
                        onLog("[激光] USB 连接失败: ${e.message}，${reconnectMs}ms 后重试")
                        delay(reconnectMs)
                    }
                }
            }
        }
    }

    // ── 尾部残块定时冲刷（低延迟，不凑满整块也发） ──
    private suspend fun flushLoop() {
        while (running) {
            delay(5)
            fec.flush()?.let { sendFecPackets(it) }
        }
    }

    // ── 上行：USB 字节流 → 切帧 → FEC 编码 → UDP ──
    private fun onUsbBytes(bytes: ByteArray) {
        val frames = ArrayList<LaserStreamFramer.Frame>(4)
        framer.feed(bytes, frames)
        for (f in frames) {
            val type = when (f.type) {
                LaserStreamFramer.FrameType.LIDAR -> LaserProtocol.T_LIDAR
                LaserStreamFramer.FrameType.MASTER -> LaserProtocol.T_MASTER_UP
            }
            if (type == LaserProtocol.T_MASTER_UP) {
                RobotSlots.parseSwitchIpFrame(f.bytes)?.let { ip ->
                    onLog("[激光] 收到切 IP 帧 → $ip")
                    onSwitchIpRequest?.invoke(ip)
                }
            }
            onUpFrame?.invoke(type, f.bytes)
            fec.push(type, f.bytes)?.let { sendFecPackets(it) }
        }
    }

    private fun sendFecPackets(packets: List<LaserFecEncoder.Packet>) {
        val s = synchronized(udpLock) { udp } ?: return
        synchronized(udpLock) {
            try {
                val addr = InetAddress.getByName(host)
                for (p in packets) {
                    s.send(DatagramPacket(p.bytes, p.bytes.size, addr, udpPort))
                }
            } catch (_: Exception) {
                // UDP 发送失败静默丢弃（FEC 已提供冗余，不重传）
            }
        }
    }

    // ── 下行：TCP 字节 → [type][frame] 重组 → 主控下行帧透传下 UART ──
    private fun onNetworkBytes(bytes: ByteArray) {
        downBuf.write(bytes)
        val buf = downBuf.toByteArray()
        downBuf.reset()
        var i = 0
        while (i + 1 + LaserProtocol.MASTER_FRAME_LEN <= buf.size) {
            val type = buf[i].toInt() and 0xFF
            val frame = buf.copyOfRange(i + 1, i + 1 + LaserProtocol.MASTER_FRAME_LEN)
            when (type) {
                LaserProtocol.T_MASTER_DOWN -> {
                    onDownFrame?.invoke(frame)
                    scope.launch { runCatching { transport.send(frame) } }
                }
                else -> { /* 未知类型，滑 1 字节重找 */ }
            }
            i += 1 + LaserProtocol.MASTER_FRAME_LEN
        }
        if (i < buf.size) downBuf.write(buf, i, buf.size - i)
    }
}
