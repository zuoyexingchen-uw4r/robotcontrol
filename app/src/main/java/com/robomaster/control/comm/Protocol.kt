package com.robomaster.control.comm

import java.io.ByteArrayOutputStream

/**
 * APP <-> ESP32S3(主控1) 串口帧协议（建议稿）。
 *
 * ⚠ 主控1 与 主控2(CH32X035) 之间只有光耦隔离的 UART 自定义协议，无电气连接。
 *   APP 永远只跟主控1 说话，主控2 由主控1 转发——软件上严禁把两条链路当作一条总线。
 *
 * 帧格式（小端）：
 * ┌──────┬──────┬──────┬──────┬─────────┬────────┐
 * │ HEAD │ HEAD │ LEN  │ TYPE │ PAYLOAD │ CRC16  │
 * │ 0xA5 │ 0x5A │ u16  │ u8   │ LEN字节 │ u16    │
 * └──────┴──────┴──────┴──────┴─────────┴────────┘
 * LEN 不含帧头(4B)与CRC(2B)，= TYPE(1) + PAYLOAD(LEN-1)
 * CRC16-MODBUS(poly 0xA001, init 0xFFFF)，从 LEN 起到 PAYLOAD 末尾计算
 */
object Protocol {

    const val HEADER0: Int = 0xA5
    const val HEADER1: Int = 0x5A
    const val MAX_PAYLOAD = 256

    // ---- 下行（APP -> 主控1）----
    const val T_DRIVE = 0x01      // 底盘：vx, wz (int16 * 1000)
    const val T_SERVO = 0x02      // 舵机/云台：id, angle(u16, 0.1°)
    const val T_ESTOP = 0x03      // 紧急停止：无参数
    const val T_POWER = 0x04      // 电源控制：port(TypeC1/2), on(u8)
    const val T_PING = 0x05       // 心跳

    // ---- 上行（主控1 -> APP）----
    const val T_SENSORS = 0x81    // 超声/TOF/地磁/激光
    const val T_STATUS = 0x82     // 电压/电流/温度/CH224A 状态
    const val T_LOG = 0x83        // 文本日志

    /** 组一帧完整报文 */
    fun build(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size + 1 <= MAX_PAYLOAD) { "payload too large" }
        val out = ByteArrayOutputStream()
        out.write(HEADER0); out.write(HEADER1)
        val len = payload.size + 1                       // TYPE + payload
        out.write(len and 0xFF); out.write((len shr 8) and 0xFF)
        out.write(type)
        out.write(payload)
        val body = out.toByteArray().copyOfRange(2, out.size()) // LEN..payload
        val crc = crc16(body)
        out.write(crc and 0xFF); out.write((crc shr 8) and 0xFF)
        return out.toByteArray()
    }

    fun i16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    // ---- 具体指令封装 ----
    /** vx: 前后速度 m/s*1000；wz: 旋转角速度 rad/s*1000 */
    fun drive(vx: Int, wz: Int) = build(T_DRIVE, i16(vx) + i16(wz))
    /** 舵机 id、角度 0.1° */
    fun servo(id: Int, angleTenth: Int) = build(T_SERVO, byteArrayOf(id.toByte()) + u16(angleTenth))
    fun estop() = build(T_ESTOP)
    fun power(port: Int, on: Boolean) = build(T_POWER, byteArrayOf(port.toByte(), if (on) 1 else 0))
    fun ping() = build(T_PING)

    /** CRC16-MODBUS */
    fun crc16(data: ByteArray, start: Int = 0, end: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in start until end) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    /** 解码收到的 Frame */
    data class Frame(val type: Int, val payload: ByteArray)

    /**
     * 流式拆帧器：喂任意长度的字节流，吐出完整帧。
     * 光耦隔离会增加抖动与粘包/断帧概率，所以拆帧必须容错：
     * 帧头不对 → 滑动 1 字节重找；CRC 错 → 丢帧并计数。
     */
    class FrameParser {
        private val buf = ByteArray(1024 * 8)
        private var head = 0
        private var tail = 0
        var crcErrors = 0L; private set
        var frames = 0L; private set

        fun feed(data: ByteArray, out: MutableList<Frame>) {
            for (b in data) {
                buf[tail] = b
                tail = (tail + 1) % buf.size
                tryExtract(out)
            }
        }

        private fun tryExtract(out: MutableList<Frame>) {
            while (true) {
                val available = (tail - head + buf.size) % buf.size
                if (available < 8) return                       // 最短帧 8B
                // 找帧头
                if (buf[head].toInt() and 0xFF != HEADER0 || buf[(head + 1) % buf.size].toInt() and 0xFF != HEADER1) {
                    head = (head + 1) % buf.size
                    continue
                }
                val len = (buf[(head + 2) % buf.size].toInt() and 0xFF) or
                        ((buf[(head + 3) % buf.size].toInt() and 0xFF) shl 8)
                if (len < 1 || len > MAX_PAYLOAD) { head = (head + 1) % buf.size; continue }
                val total = 4 + len + 2
                if (available < total) return                   // 未收完整，等下一个字节
                // 取出 body(LEN..payload) 与 crc
                val body = ByteArray(len + 2)
                for (i in body.indices) body[i] = buf[(head + 2 + i) % buf.size]
                val crcLow = buf[(head + 4 + len) % buf.size].toInt() and 0xFF
                val crcHigh = buf[(head + 5 + len) % buf.size].toInt() and 0xFF
                val expect = (crcHigh shl 8) or crcLow
                if (crc16(body) != expect) {
                    crcErrors++; head = (head + 1) % buf.size   // CRC 错，滑动重找
                    continue
                }
                out.add(Frame(body[1].toInt() and 0xFF, body.copyOfRange(2, body.size)))
                frames++
                head = (head + total) % buf.size
            }
        }
    }
}
