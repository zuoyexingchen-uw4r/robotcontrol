package com.robomaster.control.laser

import java.io.ByteArrayOutputStream

/**
 * 激光雷达 + 主控板混合流协议（与 LASER_PROTOCOL.md v3 对齐）。
 *
 * **v3 关键约定：**
 * - 雷达点云帧：`55 AA ...`（60 字节，原样透传）
 * - 主控帧：`AA 55 type seq payload(16×int16) crc16`（**39 字节**，v3 缩短以给 FEC 让位）
 * - 上行走 **UDP + Reed-Solomon 前向纠错**（不重传，擦除解码恢复丢包）
 * - 下行主控指令走 TCP（命令低频且关键）
 *
 * FEC 分片（SHARD_SIZE=64 字节）：`[type(1)][frame(≤60)][zero pad]`。
 * UDP 报文 = `[block_id(1)][shard_idx(1)][shard(64)]`，块内 0..k-1 为数据分片、
 * k..k+m-1 为校验分片。任意丢 ≤m 个分片可由 Cauchy MDS 恢复。
 */
object LaserProtocol {

    // ── 网络管道帧类型头 ──
    const val T_MASTER_UP: Int = 0x01      // 主控上行帧
    const val T_MASTER_DOWN: Int = 0x02    // 主控下行帧（上位机→手机，透传下 UART）
    const val T_LIDAR: Int = 0x03          // 雷达点云帧

    // ── 帧长度 ──
    const val LIDAR_FRAME_LEN: Int = 60
    const val MASTER_FRAME_LEN: Int = 39   // 2 头 + 1 type + 2 seq + 32 payload + 2 crc

    // ── 雷达帧 ──
    const val LIDAR_HDR0: Int = 0x55
    const val LIDAR_HDR1: Int = 0xAA

    // ── 主控帧 ──
    const val MASTER_HDR0: Int = 0xAA
    const val MASTER_HDR1: Int = 0x55
    const val MASTER_UP_TYPE: Int = 0x01
    const val MASTER_DOWN_TYPE: Int = 0x02

    const val OFF_TYPE: Int = 2             // type 1B
    const val OFF_SEQ: Int = 3              // seq u16 LE 2B
    const val OFF_PAYLOAD: Int = 5          // payload 32B = 16×int16
    const val PAYLOAD_LEN: Int = 32
    const val OFF_CRC: Int = 37             // crc16 2B（= 39-2）

    /** 字段数：payload 32 字节 = 16 个 int16 */
    const val FIELD_COUNT: Int = 16

    // ── FEC 参数（默认，可调） ──
    const val SHARD_SIZE: Int = 64          // 每分片 64 字节（type + frame + 补零）
    const val FEC_K: Int = 8                // 每块数据分片数
    const val FEC_M: Int = 4                // 每块校验分片数（丢 ≤4 可恢复）

    // ── 默认端口 ──
    const val TCP_PORT: Int = 8767
    const val UDP_PORT: Int = 8768

    /** 主控帧字段名（占位/建议，具体语义与电控商讨；长度固定 16 个 int16） */
    val UP_FIELD_NAMES: Array<String> = arrayOf(
        "seq", "status", "voltage_mv", "current_ma", "temp_0_1c",
        "rpm_l", "rpm_r", "wheel_l_mm", "wheel_r_mm", "imu_yaw_0_1",
        "imu_pitch_0_1", "imu_roll_0_1", "tof_mm", "lidar_health",
        "res14", "res15",
    )
    val DOWN_FIELD_NAMES: Array<String> = arrayOf(
        "seq", "cmd", "vx_mmps", "wz_0_01", "yaw_0_01",
        "pitch_0_01", "fire_rpm", "res7", "res8", "res9",
        "res10", "res11", "res12", "res13", "res14", "res15",
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
     * 组一帧主控帧（39 字节）。
     *
     * @param type [MASTER_UP_TYPE] / [MASTER_DOWN_TYPE]
     * @param seq 帧序号 u16
     * @param fields 16 个 int16（超出截断、不足补 0）
     */
    fun buildMaster(type: Int, seq: Int, fields: IntArray): ByteArray {
        val frame = ByteArray(MASTER_FRAME_LEN)
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
        val crc = crc16(frame, OFF_TYPE, OFF_CRC)
        frame[OFF_CRC] = (crc and 0xFF).toByte()
        frame[OFF_CRC + 1] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    /** 解析主控帧，返回 (type, seq, fields[16])；CRC 错返回 null */
    fun parseMaster(frame: ByteArray): Triple<Int, Int, IntArray>? {
        if (frame.size != MASTER_FRAME_LEN) return null
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
     */
    fun isLidarFrame(frame: ByteArray): Boolean {
        if (frame.size != LIDAR_FRAME_LEN) return false
        if ((frame[0].toInt() and 0xFF) != LIDAR_HDR0 ||
            (frame[1].toInt() and 0xFF) != LIDAR_HDR1) return false
        val startRaw = (frame[7].toInt() and 0xFF shl 8) or (frame[6].toInt() and 0xFF)
        val endRaw = (frame[LIDAR_FRAME_LEN - 3].toInt() and 0xFF shl 8) or
                (frame[LIDAR_FRAME_LEN - 4].toInt() and 0xFF)
        if (startRaw >= 36000 || endRaw >= 36000) return false
        val span = (endRaw - startRaw).mod(36000)
        if (span == 0 || span > 9000) return false
        if (startRaw == 0 && frame.copyOfRange(8, 56).all { it.toInt() and 0xFF == 0 }) return false
        return true
    }

    /** 组装一个 64 字节 FEC 分片：`[type(1)][frame][zero pad]` */
    fun makeShard(type: Int, frame: ByteArray): ByteArray {
        val s = ByteArray(SHARD_SIZE)
        s[0] = type.toByte()
        System.arraycopy(frame, 0, s, 1, minOf(frame.size, SHARD_SIZE - 1))
        return s
    }

    /** 从 FEC 分片还原 (type, frame)；空分片（type=0）返回 null */
    fun parseShard(shard: ByteArray): Pair<Int, ByteArray>? {
        if (shard.size != SHARD_SIZE) return null
        val type = shard[0].toInt() and 0xFF
        if (type == 0) return null
        val len = when (type) {
            T_LIDAR -> LIDAR_FRAME_LEN
            T_MASTER_UP, T_MASTER_DOWN -> MASTER_FRAME_LEN
            else -> return null
        }
        return type to shard.copyOfRange(1, 1 + len)
    }

    /**
     * 给 TCP 下行加类型头：[type][frame]。
     */
    fun wrap(type: Int, frame: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(frame.size + 1)
        out.write(type)
        out.write(frame)
        return out.toByteArray()
    }
}
