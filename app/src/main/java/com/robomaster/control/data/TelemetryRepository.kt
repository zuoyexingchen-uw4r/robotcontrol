package com.robomaster.control.data

import com.robomaster.control.comm.Protocol
import com.robomaster.control.comm.TransportManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 传感器快照（主控1 侧：高速传感器） */
data class Sensors(
    val ultrasonicMm: Int = 0,   // 超声波
    val tofMm: Int = 0,          // TOF
    val magX: Int = 0, val magY: Int = 0, val magZ: Int = 0,  // 地磁
    val laserMm: Int = 0,        // 激光
)

/** 电源/健康快照（主控2 侧经主控1 转发：低速模拟传感） */
data class PowerStatus(
    val voltageMV: Int = 0,      // 母线电压 mV
    val currentMA: Int = 0,      // 电流 mA（电流保护用）
    val tempDeciC: Int = 0,      // 温度 0.1℃
    val typec1W: Int = 0,        // Type-C1 实时功率 W（max 100W）
    val typec2W: Int = 0,        // Type-C2 实时功率 W（max 30W）
    val ch224State: Int = 0,     // CH224A 协议握手状态
)

/**
 * 机器人数据仓库：拆帧 → 状态流，并集中下发控制指令。
 * MVVM 里的 M 层，ViewModel 只订阅这里。
 */
class TelemetryRepository(
    private val transport: TransportManager,
    private val scope: CoroutineScope,
) {
    private val _sensors = MutableStateFlow(Sensors())
    val sensors: StateFlow<Sensors> = _sensors

    private val _power = MutableStateFlow(PowerStatus())
    val power: StateFlow<PowerStatus> = _power

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 256)
    val logs: SharedFlow<String> = _logs

    private val parser = Protocol.FrameParser()
    private val pending = mutableListOf<Protocol.Frame>()

    init {
        scope.launch {
            transport.raw.collect { bytes ->
                synchronized(parser) {
                    parser.feed(bytes, pending)
                    pending.forEach { f -> handle(f) }
                    pending.clear()
                }
            }
        }
        scope.launch {
            transport.logs.collect { _logs.emit("[通道] $it") }
        }
    }

    private fun handle(f: Protocol.Frame) {
        when (f.type) {
            Protocol.T_SENSORS -> {
                if (f.payload.size < 10) return
                _sensors.value = Sensors(
                    ultrasonicMm = leU16(f.payload, 0),
                    tofMm = leU16(f.payload, 2),
                    magX = leI16(f.payload, 4),
                    magY = leI16(f.payload, 6),
                    magZ = leI16(f.payload, 8),
                    laserMm = if (f.payload.size >= 12) leU16(f.payload, 10) else 0,
                )
            }
            Protocol.T_STATUS -> {
                if (f.payload.size < 12) return
                _power.value = PowerStatus(
                    voltageMV = leU16(f.payload, 0),
                    currentMA = leU16(f.payload, 2),
                    tempDeciC = leI16(f.payload, 4),
                    typec1W = leU16(f.payload, 6),
                    typec2W = leU16(f.payload, 8),
                    ch224State = f.payload[10].toInt() and 0xFF,
                )
            }
            Protocol.T_LOG -> {
                _logs.tryEmit("[板] " + String(f.payload, Charsets.UTF_8))
            }
        }
    }

    // ---- 下行指令 ----
    suspend fun drive(vx: Int, wz: Int) { transport.send(Protocol.drive(vx, wz)) }
    suspend fun servo(id: Int, angleTenth: Int) { transport.send(Protocol.servo(id, angleTenth)) }
    suspend fun powerControl(port: Int, on: Boolean) { transport.send(Protocol.power(port, on)) }
    suspend fun ping() { transport.send(Protocol.ping()) }

    /** 紧急停止：清空所有待发帧，直接抢占发送（要求 5） */
    suspend fun eStop() {
        transport.send(Protocol.estop())
        _logs.tryEmit("!! 紧急停止已发送 !!")
    }

    private fun leU16(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun leI16(b: ByteArray, off: Int): Int {
        val v = leU16(b, off)
        return if (v >= 0x8000) v - 0x10000 else v
    }
}
