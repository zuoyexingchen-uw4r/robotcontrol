package com.robomaster.control.video

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sunshine / Moonlight GameStream 视频包格式（对照 Sunshine stream.cpp / video.h 源码）。
 *
 * 单个未加密视频包 = 32 字节头 + NAL 切片：
 *   [RTP 12B][reserved 4B][NV_VIDEO_PACKET 16B][payload]
 * 每帧第一个包，payload 前再插 8 字节短帧头 video_short_frame_header_t。
 *
 * 切片参数：packetsize=1024 → blocksize=1040 → 净载荷 1008B（首包再减 8B 帧头）。
 */
object GameStream {

    const val RTP_HEADER = 0x90              // V=2, X=1(扩展=那 4B reserved)
    const val PAYLOAD_TYPE_VIDEO = 97        // GameStream 视频动态 payload type
    const val PACKET_SIZE = 1024
    const val BLOCK_SIZE = PACKET_SIZE + 16  // 1040，每包线上总长
    const val HEADER_SIZE = 32               // RTP12 + reserved4 + NV16
    const val PAYLOAD_BLOCK_SIZE = BLOCK_SIZE - HEADER_SIZE // 1008 净载荷
    const val SHORT_FRAME_HEADER = 8
    const val DATA_SHARDS_MAX = 255
    const val MAX_FEC_BLOCKS = 4
    const val MIN_FEC_PACKETS = 4            // 低于此自动提升（仍受 255-k 上限约束）

    // NV_VIDEO_PACKET.flags
    const val FLAG_PIC_DATA = 0x01
    const val FLAG_EOF = 0x02
    const val FLAG_SOF = 0x04

    // NV_VIDEO_PACKET.extraFlags
    const val EXTRA_FLAG_LTR = 0x01

    // frameType（短帧头）
    const val FRAME_TYPE_P = 1
    const val FRAME_TYPE_IDR = 2
    const val FRAME_TYPE_INTRA_REFRESH = 4
    const val FRAME_TYPE_RFI = 5

    const val MULTI_FEC_FLAGS = 0x10         // 匹配 Moonlight

    /** 8 字节短帧头。frame_processing_latency 为小端、0.1ms 单位 */
    fun shortFrameHeader(frameType: Int, lastPayloadLen: Int, latencyDeciMs: Int): ByteArray =
        byteArrayOf(
            0x01,
            (latencyDeciMs and 0xFF).toByte(), ((latencyDeciMs ushr 8) and 0xFF).toByte(),
            frameType.toByte(),
            (lastPayloadLen and 0xFF).toByte(), ((lastPayloadLen ushr 8) and 0xFF).toByte(),
            0, 0,
        )

    /** 组一个完整视频包 */
    fun buildPacket(
        seq: Int,
        timestamp: Int,
        ssrc: Int,
        frameIndex: Int,
        flags: Int,
        extraFlags: Int,
        multiFecBlocks: Int,
        fecInfo: Int,
        payload: ByteArray,
    ): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(RTP_HEADER.toByte())
        buf.put(PAYLOAD_TYPE_VIDEO.toByte())
        buf.putShort((seq and 0xFFFF).toShort())     // sequenceNumber
        buf.putInt(timestamp)                        // 90kHz
        buf.putInt(ssrc)
        buf.put(ByteArray(4))                        // reserved
        buf.putInt((seq and 0xFFFFFF) shl 8)         // streamPacketIndex = seq << 8
        buf.putInt(frameIndex)
        buf.put(flags.toByte())
        buf.put(extraFlags.toByte())
        buf.put(MULTI_FEC_FLAGS.toByte())
        buf.put(multiFecBlocks.toByte())
        buf.putInt(fecInfo)
        buf.put(payload)
        return buf.array()
    }

    /**
     * fecInfo 打包：bits0-3=0 | bits4-11=fecPercentage | bits12-21=包序号x | bits22-31=dataShards
     */
    fun packFecInfo(packetIndexInFrame: Int, dataShards: Int, fecPercentage: Int): Int =
        ((packetIndexInFrame and 0x3FF) shl 12) or
            ((dataShards and 0x3FF) shl 22) or
            ((fecPercentage and 0xFF) shl 4)

    /** multiFecBlocks：bits4-5=块号，bits6-7=(块数-1) */
    fun packMultiFecBlocks(blockNum: Int, numBlocks: Int): Int =
        ((blockNum and 0x3) shl 4) or (((numBlocks - 1) and 0x3) shl 6)

    /**
     * 90kHz RTP 时间戳 = ptsUs × 90000 / 1e6
     *
     * ⚠️ 之前写成 ×90/1e6，整整差 1000 倍：30fps 一帧间隔 33333µs 本应得 3000 tick，
     * 实际只得到 3 tick。上位机拿 ts 反推帧率会算出 30000fps——当时就是靠这个
     * 离谱的数字才暴露出来的。任何按 RTP ts 做时钟/同步的客户端（Moonlight 等）
     * 都会被这个错误带崩，必须按 90000 tick/秒 算。
     */
    fun rtpTimestamp(ptsUs: Long): Int = ((ptsUs * 90_000L) / 1_000_000L).toInt()

    /**
     * 每块最大数据分片数。FEC 块约束 k+m≤255（Cauchy 矩阵需要 k+m 个相异非零元），
     * 若 k 取满 255，parity 会被压到 0——所以按 fecPercentage 反推：
     *   k + ceil(k·pct/100) ≤ 255  →  k ≤ 25500/(100+pct)
     * 同时受 MIN_FEC_PACKETS 约束再减一点，保证保底 parity 放得下。
     */
    fun maxDataShardsPerBlock(fecPercentage: Int): Int {
        val pct = fecPercentage.coerceIn(1, 99)
        return minOf(25500 / (100 + pct), DATA_SHARDS_MAX - MIN_FEC_PACKETS)
    }

    /** 一帧拆成多少个 FEC 块（每块数据分片数 ≤ maxDataShardsPerBlock，最多 4 块） */
    fun fecBlocksNeeded(dataShards: Int, fecPercentage: Int): Int {
        val per = maxDataShardsPerBlock(fecPercentage)
        if (dataShards <= per) return 1
        return minOf(MAX_FEC_BLOCKS, (dataShards + per - 1) / per)
    }

    /** 一帧最多能装多少分片（超了只能截断，见 GameStreamSender） */
    fun maxTotalShards(fecPercentage: Int): Int = MAX_FEC_BLOCKS * maxDataShardsPerBlock(fecPercentage)

    /**
     * 把 dataShards 拆成 blocks 组，每组 ≤255 且总和恰为 dataShards。
     * 不能简单均拆：511 分片拆 2 块均分会得到 256 > 255。
     * 调用方需保证 blocks*255 >= dataShards（见 maxTotalShards()）。
     */
    fun splitDataShards(dataShards: Int, blocks: Int): IntArray {
        val out = IntArray(blocks)
        var rem = dataShards
        for (i in 0 until blocks) {
            val left = blocks - i
            val take = minOf(DATA_SHARDS_MAX, (rem + left - 1) / left)
            out[i] = take
            rem -= take
        }
        return out
    }

    /**
     * 给定块内数据分片数与 fecPercentage，算 parity 分片数。
     * 保底 MIN_FEC_PACKETS；受 Cauchy 矩阵约束 k+m≤255，故上限 255-k
     * （k=255 时返回 0，表示该块无 FEC 保护）。
     */
    fun parityShards(k: Int, fecPercentage: Int): Int {
        val want = (k * fecPercentage + 99) / 100
        return maxOf(MIN_FEC_PACKETS, want).coerceAtMost(DATA_SHARDS_MAX - k).coerceAtLeast(0)
    }
}
