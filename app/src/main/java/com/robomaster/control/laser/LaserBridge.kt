package com.robomaster.control.laser

import android.content.Context
import com.robomaster.control.comm.ConnState
import com.robomaster.control.comm.UsbSerialTransport
import com.robomaster.control.net.NetCore
import com.robomaster.control.net.NetProto
import com.robomaster.control.net.UartLink
import com.robomaster.control.net.UartSlot
import com.robomaster.control.net.UartSlotFramer
import com.robomaster.control.ui.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * UART ⇄ 非图传流 的桥（USB 侧）。
 *
 * 职责收窄为两件事，网络部分全部交给 [UartLink]：
 * 1. USB 串口读写 + 断线自动重连；
 * 2. 把 UART 字节切成 60B 槽送上行；把下行槽按帧头送到 UART 或本机执行。
 *
 * 与旧版（v0.6）的区别：
 * - 不再自己开 TCP 8767 / UDP 8768，也不再自己算 FEC（旧 LaserFec 已并入 `net.FecBlock`）；
 * - 雷达与主控走同一条流，靠 60B 槽的帧头区分；
 * - 下行不再有「主控下行」这条独立通道，PC 直接发 `FA 5B` 原始字节槽。
 */
class LaserBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: String,
    private val baudRate: Int = 230_400,   // 雷达 230400，CH343 ≥ 此值
    private val reconnectMs: Long = 1500L,
) {
    var onLog: (String) -> Unit = {}

    /** (type, slot)：type 沿用旧语义 0x01=主控 / 0x03=雷达，slot 恒为 60B */
    var onUpFrame: ((Int, ByteArray) -> Unit)? = null

    /** 下行要写入 UART 的原始字节（已按协议长度截断，不含补零） */
    var onDownFrame: ((ByteArray) -> Unit)? = null

    /** 收到「切 IP」本机操作槽 */
    var onSwitchIpRequest: ((String) -> Unit)? = null

    /** 链路统计（1Hz） */
    var onStats: ((UartLink.Stats) -> Unit)? = null

    @Volatile private var running = false
    private val transport = UsbSerialTransport(context, scope, baudRate)
    private val framer = UartSlotFramer()
    private var link: UartLink? = null
    private var usbJob: Job? = null

    val connected: Boolean get() = transport.state.value is ConnState.Connected

    fun start() {
        if (running) return
        val cfg = Settings.get(context)
        NetCore.configure(cfg.netUdpPort, cfg.netTcpPort)
        val l = NetCore.acquire(TAG, host, onLog) ?: run {
            onLog("[激光] 未配置电脑 IP，未启动")
            return
        }
        running = true
        link = l
        l.batch = cfg.netBatch
        l.replay = cfg.netReplay
        l.mode = when (cfg.netMode) {
            1 -> UartLink.Mode.UDP
            2 -> UartLink.Mode.TCP
            else -> UartLink.Mode.AUTO
        }
        l.onSlot = ::onDownSlot
        l.onStats = { st -> onStats?.invoke(st) }

        // 视觉开火等本机指令复用这条 USB，避免再开一个 Transport 抢设备
        NetCore.uartWriter = { bytes -> scope.launch { runCatching { transport.send(bytes) } } }

        // 串口设备识别参数（控制台「串口VID / 串口PID / 强制接管串口」）
        transport.baudRate = baudRate
        transport.ignoreDeviceId = cfg.serialForceTakeover
        transport.vidFilter = cfg.serialVid
        transport.pidFilter = cfg.serialPid

        usbJob = scope.launch(Dispatchers.IO) {
            transport.incoming.collect { onUsbBytes(it) }
        }
        scope.launch { superviseUsb() }
        onLog("[激光] 已接入非图传流（${l.snapshot().mode}，batch=${l.batch} replay=${l.replay}）")
    }

    fun stop() {
        running = false
        usbJob?.cancel()
        usbJob = null
        link?.onSlot = null
        link = null
        NetCore.uartWriter = null
        NetCore.release(TAG)
        scope.launch { runCatching { transport.disconnect() } }
    }

    /** 本机操作（切 IP / 重连 / 清统计）→ 立即上行，按 replay 重放 */
    fun sendLocalOp(op: Int, arg: ByteArray = ByteArray(0)) {
        link?.pushSlot(UartSlot.localOp(op, arg), urgent = true)
    }

    /** 把一段原始字节写进 UART（不经过网络） */
    fun writeUart(bytes: ByteArray) {
        scope.launch { runCatching { transport.send(bytes) } }
    }

    // ───────────────────────── USB → 上行 ─────────────────────────

    private fun onUsbBytes(bytes: ByteArray) {
        val slots = ArrayList<ByteArray>(8)
        framer.feed(bytes, slots)
        if (slots.isEmpty()) return
        for (slot in slots) {
            if (NetProto.headerOf(slot) == NetProto.H_MCU_UP) {
                // 兼容旧固件的「切 IP」预留帧（39B 主控帧 fields[1]='SI'）
                RobotSlots.parseSwitchIpFrame(slot.copyOfRange(0, NetProto.LEN_MCU))?.let { ip ->
                    onLog("[激光] 收到切 IP 帧 → $ip")
                    onSwitchIpRequest?.invoke(ip)
                }
            }
            UartSlot.upType(slot)?.let { onUpFrame?.invoke(it, slot) }
            link?.pushSlot(slot, urgent = false)
        }
    }

    // ───────────────────────── 下行 → UART / 本机 ─────────────────────────

    private fun onDownSlot(slot: ByteArray) {
        if (NetProto.headerOf(slot) == NetProto.H_LOCAL) {
            handleLocal(slot)
            return
        }
        val bytes = UartSlot.uartPayload(slot) ?: return
        if (bytes.isEmpty()) return
        onDownFrame?.invoke(bytes)
        scope.launch { runCatching { transport.send(bytes) } }
    }

    private fun handleLocal(slot: ByteArray) {
        when (UartSlot.localOpCode(slot)) {
            NetProto.OP_SWITCH_IP -> {
                val arg = UartSlot.localOpArg(slot)
                if (arg.size == 4) {
                    val ip = arg.joinToString(".") { (it.toInt() and 0xFF).toString() }
                    onLog("[激光] 本机操作：切 IP → $ip")
                    onSwitchIpRequest?.invoke(ip)
                }
            }
            NetProto.OP_RECONNECT -> {
                onLog("[激光] 本机操作：重连 USB")
                scope.launch {
                    runCatching { transport.disconnect() }
                    delay(300)
                    runCatching { transport.connect() }
                }
            }
            NetProto.OP_RESET_STATS -> {
                link?.resetStats()
                onLog("[激光] 本机操作：清空统计")
            }
            else -> onLog("[激光] 未知本机操作 ${UartSlot.localOpCode(slot)}")
        }
    }

    // ───────────────────────── USB 监督 ─────────────────────────

    private suspend fun superviseUsb() {
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

    companion object {
        private const val TAG = "laser"
    }
}
