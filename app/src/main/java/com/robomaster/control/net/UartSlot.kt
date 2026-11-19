package com.robomaster.control.net

/**
 * 60 字节定长槽的构造与解析。
 *
 * 槽就是 UART 帧原样（雷达 60B、主控 39B 补零到 60B），前两字节既是 UART 协议
 * 自己的帧头，也充当本网络流的**路由键**：
 *
 * | 帧头 | 方向 | 去向 |
 * |---|---|---|
 * | `55 AA` | 上行 | 雷达点云 → 上位机 |
 * | `AA 55` | 上行 | 主控遥测 → 上位机 |
 * | `4B 41` | 双向 | KEEP_ALIVE，上位机原样回显 |
 * | `FA AF` | 下行 | 开火指令 → UART |
 * | `FA BF` | 下行 | 导航指令 → UART |
 * | `FA 5B` | 下行 | 原始字节 → UART |
 * | `FA 5A` | 下行 | 本机操作（切 IP / 重连 / 清统计），不写串口 |
 *
 * 补零约定：主控帧目前仍是 39B，尾部补 0 到 60B；写入 UART 时按协议长度截断，
 * 不会把补的零发出去。
 */
object UartSlot {

    /** 单槽可承载的原始 UART 字节数：60 - 2(头) - 1(len) - 2(CRC) */
    private const val MAX_UART_CHUNK = 55

    fun header(slot: ByteArray): Int = NetProto.headerOf(slot)

    fun isKeepAlive(slot: ByteArray): Boolean =
        slot.size >= 2 && NetProto.headerOf(slot) == NetProto.H_KA

    /** 雷达帧原样（必须 60B） */
    fun lidar(frame: ByteArray): ByteArray {
        require(frame.size == NetProto.LEN_LIDAR) { "雷达帧应为 ${NetProto.LEN_LIDAR}B，实际 ${frame.size}" }
        return frame.copyOf(NetProto.SLOT)
    }

    /** 主控帧 39B → 补零到 60B 槽 */
    fun mcu(frame: ByteArray): ByteArray {
        require(frame.size <= NetProto.SLOT) { "主控帧超过槽长：${frame.size}" }
        return frame.copyOf(NetProto.SLOT)
    }

    /** 开火指令 10B → 补零 */
    fun fire(frame: ByteArray): ByteArray = frame.copyOf(NetProto.SLOT)

    /** 导航指令 7B → 补零 */
    fun nav(frame: ByteArray): ByteArray = frame.copyOf(NetProto.SLOT)

    /**
     * KEEP_ALIVE：60B。
     * ```
     * 0  2  'K' 'A'
     * 2  2  seq u16
     * 4  4  ts 发送时刻（elapsedRealtime 低 32 位）
     * 8  1  flags bit0=请求回显
     * 9 49  0
     * 58 2  crc16(0..57)
     * ```
     * 上位机原样回显，手机用 ts 算 RTT；同一 seq 只算第一次。
     */
    fun keepAlive(seq: Int, tsMs: Long, echoRequest: Boolean = true): ByteArray {
        val s = ByteArray(NetProto.SLOT)
        s[0] = 0x4B
        s[1] = 0x41
        NetProto.putU16(s, 2, seq)
        NetProto.putU32(s, 4, tsMs and 0xFFFFFFFFL)
        s[8] = if (echoRequest) 1 else 0
        NetProto.putU16(s, 58, NetProto.crc16(s, 0, 58))
        return s
    }

    fun kaSeq(slot: ByteArray): Int = NetProto.u16(slot, 2)

    fun kaTs(slot: ByteArray): Long = NetProto.u32(slot, 4)

    /** 本机操作槽：`FA 5A | op | argLen | arg...`，不写串口 */
    fun localOp(op: Int, arg: ByteArray = ByteArray(0)): ByteArray {
        require(arg.size <= 54) { "本机操作参数过长：${arg.size}" }
        val s = ByteArray(NetProto.SLOT)
        s[0] = 0xFA.toByte()
        s[1] = 0x5A
        s[2] = op.toByte()
        s[3] = arg.size.toByte()
        System.arraycopy(arg, 0, s, 4, arg.size)
        NetProto.putU16(s, 58, NetProto.crc16(s, 0, 58))
        return s
    }

    fun localOpCode(slot: ByteArray): Int = slot[2].toInt() and 0xFF

    fun localOpArg(slot: ByteArray): ByteArray {
        val n = slot[3].toInt() and 0xFF
        if (n <= 0) return ByteArray(0)
        return slot.copyOfRange(4, (4 + n).coerceAtMost(slot.size))
    }

    /**
     * 原始字节写串口：每槽最多 55B（`FA 5B | len | bytes...`，58..59 留给 CRC），
     * 超长自动分片。上位机用它下发任意 UART 指令，不需要为每条指令单独定义网络类型。
     */
    fun uartDown(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>((bytes.size + MAX_UART_CHUNK - 1) / MAX_UART_CHUNK)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(MAX_UART_CHUNK, bytes.size - off)
            val s = ByteArray(NetProto.SLOT)
            s[0] = 0xFA.toByte()
            s[1] = 0x5B
            s[2] = n.toByte()
            System.arraycopy(bytes, off, s, 3, n)
            NetProto.putU16(s, 58, NetProto.crc16(s, 0, 58))
            out += s
            off += n
        }
        return out
    }

    /** 下行槽 → 应写入 UART 的确切字节；非 UART 目标返回 null */
    fun uartPayload(slot: ByteArray): ByteArray? = when (header(slot)) {
        NetProto.H_FIRE -> slot.copyOfRange(0, NetProto.LEN_FIRE)
        NetProto.H_NAV -> slot.copyOfRange(0, NetProto.LEN_NAV)
        NetProto.H_UART_DOWN -> {
            val n = (slot[2].toInt() and 0xFF).coerceAtMost(MAX_UART_CHUNK)
            if (n <= 0) ByteArray(0) else slot.copyOfRange(3, 3 + n)
        }
        else -> null
    }

    /** 槽 → 上行类型（用于 onUpFrame 回调，保持旧接口语义） */
    fun upType(slot: ByteArray): Int? = when (header(slot)) {
        NetProto.H_LIDAR -> 0x03
        NetProto.H_MCU_UP -> 0x01
        else -> null
    }
}
