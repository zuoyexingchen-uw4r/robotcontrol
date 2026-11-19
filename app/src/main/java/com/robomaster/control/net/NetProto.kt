package com.robomaster.control.net

/**
 * 非图传流协议常量（NETSTACK.md v1）。
 *
 * 设计要点：
 * - 全链路只有两条信号：图传（视频 UDP + 图传控制 TCP）和非图传（UDP / TCP 备份）。
 * - 端口按校内赛要求固定成一组：**一个图传 47000（UDP 视频 + TCP 控制同号，UDP/TCP
 *   端口空间独立所以不冲突）、两个控制 48000（UDP 好网）/ 49000（TCP 差网）**。
 *   手机只出站、不监听，所以这三个端口只在上位机上监听。
 * - 非图传流的最小单位是 **60 字节定长槽**，槽内容就是 UART 帧原样，前两字节决定去向。
 * - 网络包 = 16 字节包头 + n 个槽（n = 1/10/20，由滑杆决定）。
 * - FEC 按 5 数据槽 + 1 校验槽（20%），校验槽必须独立成包。
 */
object NetProto {

    /** 定长槽：UART 帧原样，雷达 60B / 主控 39B 补零到 60B */
    const val SLOT = 60

    /** 网络包头 */
    const val PKT_HDR = 16

    /** FEC 块：k 数据 + m 校验（m/k = 20%） */
    const val FEC_K = 5
    const val FEC_M = 1

    /** 滑杆上限；20 槽 = 1200B，加包头 1216B，仍在 1500 MTU 内 */
    const val MAX_BATCH = 20

    /** 长帧单包负载上限（避免 IP 分片） */
    const val MAX_PAYLOAD = 1200

    /**
     * 端口三件套（与 `host/netstack/proto.py`、`host/spaa/config.py` 逐字对齐）：
     * - [VIDEO_PORT] 视频 UDP 与图传控制 TCP **同号**（UDP/TCP 端口空间独立）；
     * - [UDP_PORT] 非图传流（雷达/遥测/视觉/设置/保活），好网；
     * - [TCP_PORT] 非图传流的 TCP 备份，差网。
     *
     * 图传端口可在界面上改（`Settings.videoPort`），但必须与上位机一致；
     * 非图传两条端口按约定固定，只有上位机监听。
     */
    const val VIDEO_PORT = 47000
    const val UDP_PORT = 48000
    const val TCP_PORT = 49000

    /** 视频端口基址（二维码里携带的就是它，其余两条由上面两个常量固定） */
    const val PORT_BASE = VIDEO_PORT

    const val MAGIC0 = 0x4C // 'L'
    const val MAGIC1 = 0x56 // 'V'
    const val VERSION = 1

    // ── 包头 flags ──
    const val F_PARITY = 1 shl 0 // 校验包
    const val F_LONG = 1 shl 1   // 长帧（JSON），payload 不是槽数组
    const val F_URGENT = 1 shl 2 // 控制帧，接收端可优先处理

    // ── 槽帧头（前 2 字节，大端序书写，实际按小端存放） ──
    const val H_LIDAR = 0x55AA    // 雷达点云 60B → 上位机
    const val H_MCU_UP = 0xAA55   // 主控遥测 39B+补零 → 上位机
    const val H_KA = 0x4B41       // 'K','A' 保活/延迟探测
    const val H_FIRE = 0xFAAF     // 开火指令 10B ← 上位机
    const val H_NAV = 0xFABF      // 导航指令 7B ← 上位机
    const val H_LOCAL = 0xFA5A    // 本机操作（不写串口）
    const val H_UART_DOWN = 0xFA5B // 原始字节写串口

    // ── 帧长（写 UART 时截取的实际长度） ──
    const val LEN_LIDAR = 60
    const val LEN_MCU = 39
    const val LEN_FIRE = 10
    const val LEN_NAV = 7

    // ── 本机操作码（H_LOCAL 槽的第 3 字节） ──
    const val OP_SWITCH_IP = 0x01
    const val OP_RECONNECT = 0x02
    const val OP_RESET_STATS = 0x03

    // ── 长帧 JSON 类型（payload 内 "t" 字段） ──
    const val JSON_VISION = "vision"
    const val JSON_SETTINGS = "settings"
    const val JSON_SET_REQ = "set_req"
    const val JSON_HELLO = "hello"

    /** CRC16-MODBUS（poly 0xA001, init 0xFFFF），与现有 UART / 激光协议一致 */
    fun crc16(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }

    fun u16(data: ByteArray, off: Int): Int =
        (data[off].toInt() and 0xFF) or ((data[off + 1].toInt() and 0xFF) shl 8)

    fun putU16(data: ByteArray, off: Int, v: Int) {
        data[off] = (v and 0xFF).toByte()
        data[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    fun u32(data: ByteArray, off: Int): Long =
        (data[off].toLong() and 0xFF) or
            ((data[off + 1].toLong() and 0xFF) shl 8) or
            ((data[off + 2].toLong() and 0xFF) shl 16) or
            ((data[off + 3].toLong() and 0xFF) shl 24)

    fun putU32(data: ByteArray, off: Int, v: Long) {
        data[off] = (v and 0xFF).toByte()
        data[off + 1] = ((v ushr 8) and 0xFF).toByte()
        data[off + 2] = ((v ushr 16) and 0xFF).toByte()
        data[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    /** 取槽的前两字节作为帧头标识 */
    fun headerOf(slot: ByteArray): Int =
        ((slot[0].toInt() and 0xFF) shl 8) or (slot[1].toInt() and 0xFF)
}
