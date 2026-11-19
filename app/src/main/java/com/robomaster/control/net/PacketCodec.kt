package com.robomaster.control.net

/**
 * 非图传流的网络包：16B 包头 + 负载。
 *
 * 包头布局（小端）：
 * ```
 * 0  2  magic 0x4C 0x56 ("LV")
 * 2  1  ver   0x01
 * 3  1  flags bit0=校验 bit1=长帧 bit2=紧急
 * 4  2  seq        包序号，每包 +1；重放包保持同号（接收端据此去重）
 * 6  2  blockId    FEC 块号；长帧时为分片组号
 * 8  1  shardStart 包内首槽的块内索引 0..4；校验包 = 5；长帧 = 分片序号
 * 9  1  count      槽数 1..20；长帧 = 分片总数
 * 10 2  ts         毫秒低 16 位（仅诊断）
 * 12 2  payloadLen 长帧字节数，定长包填 0
 * 14 2  crc16      对 0..13 计算
 * ```
 */
data class Packet(
    val flags: Int,
    val seq: Int,
    val blockId: Int,
    val shardStart: Int,
    val count: Int,
    val tsMs: Int,
    val payload: ByteArray,
) {
    val isParity: Boolean get() = flags and NetProto.F_PARITY != 0
    val isLong: Boolean get() = flags and NetProto.F_LONG != 0
    val isUrgent: Boolean get() = flags and NetProto.F_URGENT != 0

    /** 去重键：重放包 seq/blockId/起始槽完全一致 */
    fun dedupeKey(): Long =
        (seq.toLong() and 0xFFFF) shl 24 or
            (blockId.toLong() and 0xFFFF) shl 8 or
            (shardStart.toLong() and 0xFF)

    fun encode(): ByteArray {
        val out = ByteArray(NetProto.PKT_HDR + payload.size)
        out[0] = NetProto.MAGIC0.toByte()
        out[1] = NetProto.MAGIC1.toByte()
        out[2] = NetProto.VERSION.toByte()
        out[3] = flags.toByte()
        NetProto.putU16(out, 4, seq)
        NetProto.putU16(out, 6, blockId)
        out[8] = shardStart.toByte()
        out[9] = count.toByte()
        NetProto.putU16(out, 10, tsMs)
        NetProto.putU16(out, 12, if (isLong) payload.size else 0)
        NetProto.putU16(out, 14, NetProto.crc16(out, 0, 14))
        System.arraycopy(payload, 0, out, NetProto.PKT_HDR, payload.size)
        return out
    }

    companion object {
        /** 解析失败返回 null（magic/版本/长度/CRC 任一不符） */
        fun decode(buf: ByteArray, len: Int = buf.size): Packet? {
            if (len < NetProto.PKT_HDR) return null
            if ((buf[0].toInt() and 0xFF) != NetProto.MAGIC0) return null
            if ((buf[1].toInt() and 0xFF) != NetProto.MAGIC1) return null
            if ((buf[2].toInt() and 0xFF) != NetProto.VERSION) return null
            if (NetProto.crc16(buf, 0, 14) != NetProto.u16(buf, 14)) return null

            val flags = buf[3].toInt() and 0xFF
            val count = buf[9].toInt() and 0xFF
            val declared = NetProto.u16(buf, 12)
            val bodyLen = len - NetProto.PKT_HDR
            val long = flags and NetProto.F_LONG != 0

            if (long) {
                if (declared < 0 || declared > bodyLen) return null
            } else {
                if (count < 1 || count > NetProto.MAX_BATCH) return null
                if (bodyLen != count * NetProto.SLOT) return null
            }

            val payload = ByteArray(if (long) declared else bodyLen)
            System.arraycopy(buf, NetProto.PKT_HDR, payload, 0, payload.size)

            return Packet(
                flags = flags,
                seq = NetProto.u16(buf, 4),
                blockId = NetProto.u16(buf, 6),
                shardStart = buf[8].toInt() and 0xFF,
                count = count,
                tsMs = NetProto.u16(buf, 10),
                payload = payload,
            )
        }
    }
}
