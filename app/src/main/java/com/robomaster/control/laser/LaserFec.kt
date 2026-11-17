package com.robomaster.control.laser

import com.robomaster.control.video.ReedSolomon

/**
 * 激光/主控上行流的 Reed-Solomon 前向纠错编码器。
 *
 * 块 = k 个数据分片 + m 个校验分片（每分片 64 字节），UDP 报文格式：
 *   `[block_id u8][shard_idx u8][shard 64B]`
 *   shard_idx ∈ [0,k) 为数据分片，∈ [k,k+m) 为校验分片。
 *
 * 不重传：接收端收到任意 ≥k 个分片即可擦除解码恢复整块（丢失 ≤m 个可恢复）。
 * 复用视频流已验证的 [ReedSolomon]（Cauchy MDS，GF(2^8)）。
 */
class LaserFecEncoder(
    private val k: Int = LaserProtocol.FEC_K,
    private val m: Int = LaserProtocol.FEC_M,
) {
    /** 完整 UDP 报文：`[block_id][shard_idx][shard]`，长度 = SHARD_SIZE + 2 */
    data class Packet(val blockId: Int, val shardIdx: Int, val bytes: ByteArray)

    private val pending = ArrayList<ByteArray>(k)
    private var blockId = 0

    /**
     * 喂入一帧。凑满 k 帧即编码返回一组报文（数据 + 校验）；未凑满返回 null。
     */
    fun push(type: Int, frame: ByteArray): List<Packet>? {
        pending.add(LaserProtocol.makeShard(type, frame))
        if (pending.size < k) return null
        val block = pending.toList()
        pending.clear()
        return encodeBlock(block)
    }

    /** 冲刷尾部不足 k 的残块（补零分片到 k 再编码），无残块返回 null */
    fun flush(): List<Packet>? {
        if (pending.isEmpty()) return null
        while (pending.size < k) {
            pending.add(ByteArray(LaserProtocol.SHARD_SIZE))  // type=0 空分片
        }
        val block = pending.toList()
        pending.clear()
        return encodeBlock(block)
    }

    private fun encodeBlock(dataShards: List<ByteArray>): List<Packet> {
        val parity = ReedSolomon.encode(dataShards, m)
        val out = ArrayList<Packet>(k + m)
        val bid = blockId++ and 0xFF
        for (i in 0 until k) {
            out.add(Packet(bid, i, header(bid, i) + dataShards[i]))
        }
        for (i in 0 until m) {
            out.add(Packet(bid, k + i, header(bid, k + i) + parity[i]))
        }
        return out
    }

    private fun header(blockId: Int, shardIdx: Int): ByteArray =
        byteArrayOf(blockId.toByte(), shardIdx.toByte())
}
