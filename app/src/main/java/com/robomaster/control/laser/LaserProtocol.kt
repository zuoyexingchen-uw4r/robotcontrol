package com.robomaster.control.laser

import java.io.ByteArrayOutputStream

/**
 * 激光雷达 + 主控板混合流协议（与 LASER_PROTOCOL.md v2 对齐）。
 *
 * **v2 关键约定：两类帧都是定长 60 字节**，靠帧头区分，零转义开销：
 * - 雷达点云帧：`55 AA ...`（60 字节，原样透传）
 * - 主控帧：`AA 55 type seq payload(52) crc16`（60 字节）
 *
 * 网络管道封装：手机端切帧后打 1 字节类型头串进 TCP/UDP 流。
 */
object LaserProtocol {

    // ── 网络管道帧类型头（1 字节，手机 → 上位机） ──
    const val T_MASTER_UP: Int = 0x01      // 主控上行帧（60 字节原始）
    const val T_MASTER_DOWN: Int = 0x02    // 主控下行帧（上位机→手机，透传下 UART）
    const val T_LIDAR: Int = 0x03          // 雷达点云帧（60 字节原样）

    // ── 统一定长 ──
    const val FRAME_LEN: Int = 60

    // ── 雷达帧 ──
    const val LIDAR_HDR0: Int = 0x55
    const val LIDAR_HDR1: Int = 0xAA

    // ── 主控帧 ──
    const val MASTER_HDR0: Int = 0xAA
    const val MASTER_HDR1: Int = 0x55
    const val MASTER_UP_TYPE: Int = 0x01    // 主控上行帧 type
    const val MASTER_DOWN_TYPE: Int = 0x02  // 主控下行帧 type

    // 主控帧内部偏移（60 字节）
    const val OFF_TYPE: Int = 2             // type 1B
    const val OFF_SEQ: Int = 3              // seq u16 LE 2B
    const val OFF_PAYLOAD: Int = 5          // payload 52B = 26×int16
    const val PAYLOAD_LEN: Int = 52
    const val OFF_CRC: Int = 58             // crc16 2B（= 60-2）

    /** 字段数：payload 52 字节 = 26 个 int16 */
    const val FIELD_COUNT: Int = 26

    // ── 默认端口 ──
    const val TCP_PORT: Int = 8767
    const val UDP_PORT: Int = 8768

    /** 主控帧字段名（占位/建议，具体语义后续与电控商讨；长度固定 26 个 int16） */
    val UP_FIELD_NAMES: Array<String> = arrayOf(
        "seq", "status", "voltage_mv", "current_ma", "temp_0_1c",
        "rpm_l", "rpm_r", "wheel_l_mm", "wheel_r_mm", "imu_yaw_0_1",
        "imu_pitch_0_1", "imu_roll_0_1", "tof_mm", "lidar_health",
        "res14", "res15", "res16", "res17", "res18", "res19",
        "res20", "res21", "res22", "res23", "res24", "res25",
    )
    val DOWN_FIELD_NAMES: Array<String> = arrayOf(
        "seq", "cmd", "vx_mmps", "wz_0_01", "yaw_0_01",
        "pitch_0_01", "fire_rpm", "res7", "res8", "res9",
        "res10", "res11", "res12", "res13", "res14", "res15",
        "res16", "res17", "res18", "res19", "res20", "res21",
        "res22", "res23", "res24", "res25",
    )

    /** CRC16-MODBUS（poly 0xA001，init 0xFFFF） */
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

    /**
     * 组一帧主控帧（60 字节）。
     *
     * @param type [MASTER_UP_TYPE] / [MASTER_DOWN_TYPE]
     * @param seq 帧序号 u16
     * @param fields 26 个 int16（超出截断、不足补 0）
     */
    fun buildMaster(type: Int, seq: Int, fields: IntArray): ByteArray {
        val frame = ByteArray(FRAME_LEN)
        frame[0] = MASTER_HDR0.toByte()
        frame[1] = MASTER_HDR1.toByte()
        frame[OFF_TYPE] = type.toByte()
        frame[OFF_SEQ] = (seq and 0xFF).toByte()
        frame[OFF_SEQ + 1] = ((seq shr 8) and 0xFF).toByte()
        for (i in 0 until FIELD_COUNT) {
            val v = if (i < fields.size) fields[i] else 0
            frame[OFF_PAYLOAD + i * 2] = (v and 0xFF).toByte()
            frame[OFF_PAYLOAD + i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        // CRC 对 type..payload 末尾计算（偏移 2..58）
        val crc = crc16(frame, OFF_TYPE, OFF_CRC)
        frame[OFF_CRC] = (crc and 0xFF).toByte()
        frame[OFF_CRC + 1] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    /** 解析主控帧，返回 (type, seq, fields[26])；CRC 错返回 null */
    fun parseMaster(frame: ByteArray): Triple<Int, Int, IntArray>? {
        if (frame.size != FRAME_LEN) return null
        if ((frame[0].toInt() and 0xFF) != MASTER_HDR0 ||
            (frame[1].toInt() and 0xFF) != MASTER_HDR1) return null
        val crc = crc16(frame, OFF_TYPE, OFF_CRC)
        val crcLow = frame[OFF_CRC].toInt() and 0xFF
        val crcHigh = frame[OFF_CRC + 1].toInt() and 0xFF
        if (crc != ((crcHigh shl 8) or crcLow)) return null
        val type = frame[OFF_TYPE].toInt() and 0xFF
        val seq = (frame[OFF_SEQ].toInt() and 0xFF) or ((frame[OFF_SEQ + 1].toInt() and 0xFF) shl 8)
        val fields = IntArray(FIELD_COUNT)
        for (i in 0 until FIELD_COUNT) {
            val lo = frame[OFF_PAYLOAD + i * 2].toInt() and 0xFF
            val hi = frame[OFF_PAYLOAD + i * 2 + 1].toInt() and 0xFF
            fields[i] = (lo or (hi shl 8)).toShort().toInt()  // 符号扩展
        }
        return Triple(type, seq, fields)
    }

    /**
     * 雷达点云帧有效性校验（与测试上位机 `_is_valid_data_frame` 一致）。
     * 只认 55 AA + 60 字节 + 起止角合法性，排除启动全零空帧。
     */
    fun isLidarFrame(frame: ByteArray): Boolean {
        if (frame.size != FRAME_LEN) return false
        if ((frame[0].toInt() and 0xFF) != LIDAR_HDR0 ||
            (frame[1].toInt() and 0xFF) != LIDAR_HDR1) return false
        val startRaw = (frame[7].toInt() and 0xFF shl 8) or (frame[6].toInt() and 0xFF)
        val endRaw = (frame[FRAME_LEN - 3].toInt() and 0xFF shl 8) or
                (frame[FRAME_LEN - 4].toInt() and 0xFF)
        if (startRaw >= 36000 || endRaw >= 36000) return false
        val span = (endRaw - startRaw).mod(36000)
        if (span == 0 || span > 9000) return false
        if (startRaw == 0 && frame.copyOfRange(8, 56).all { it.toInt() and 0xFF == 0 }) return false
        return true
    }

    /**
     * 给网络管道加类型头：[type][frame]。
     * 上位机按 1 字节 type 分流。
     */
    fun wrap(type: Int, frame: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(frame.size + 1)
        out.write(type)
        out.write(frame)
        return out.toByteArray()
    }
}
