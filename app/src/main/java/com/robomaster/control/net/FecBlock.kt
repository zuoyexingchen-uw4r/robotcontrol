package com.robomaster.control.net

import com.robomaster.control.video.ReedSolomon

/**
 * 5 数据槽 + 1 校验槽（20% 冗余）的块级纠错。
 *
 * 复用视频链路已验证的 [ReedSolomon]（Cauchy MDS，GF(2^8)，poly 0x11D）：
 * 一个块 6 个槽，任意丢 1 个槽都能恢复。校验槽必须**单独成包**——和数据塞进
 * 同一个网络包时，整包丢失会把校验一起带走，等于没加冗余。
 *
 * 为什么只做 k=5：
 * - 20% 是约定的冗余比例，5:1 正好整数且能覆盖单个包丢一槽；
 * - m=1 时擦除解码退化为「异或 + 乘逆元」，接收端不需要高斯消元，Python 端也简单。
 *
 * 尾块（不足 5 槽）**不生成校验**：补零会让接收端无法区分「补的零」和「真实的零槽」，
 * 恢复出来的假槽可能被写进 UART。宁可少一层保护也不下发脏数据。
 */
object FecBlock {

    const val K = NetProto.FEC_K
    const val M = NetProto.FEC_M

    /** 5 个等长数据槽 → 1 个校验槽；槽长必须一致（本协议固定 60B） */
    fun encode(dataShards: List<ByteArray>): ByteArray {
        require(dataShards.size == K) { "FEC 块要求 $K 个数据槽，实际 ${dataShards.size}" }
        val size = dataShards[0].size
        require(dataShards.all { it.size == size }) { "同块数据槽必须等长" }
        return ReedSolomon.encode(dataShards, M)[0]
    }

    /**
     * 只丢 1 个数据槽时的恢复（校验槽在位）。
     * 返回恢复出的槽；丢超过 1 个或校验也丢了则返回 null。
     *
     * @param data 长度为 K 的数组，缺失位为 null
     * @param parity 校验槽；为 null 表示校验包也丢了，无法恢复
     */
    fun recover(data: Array<ByteArray?>, parity: ByteArray?): Pair<Int, ByteArray>? {
        if (parity == null) return null
        val missing = data.indices.filter { data[it] == null }
        if (missing.size != 1) return null
        val r = missing[0]
        val size = parity.size

        val row = ReedSolomon.parityMatrix(K, M)[0]
        val acc = ByteArray(size)
        System.arraycopy(parity, 0, acc, 0, size)

        for (j in 0 until K) {
            if (j == r) continue
            val shard = data[j] ?: return null
            val c = row[j]
            if (c == 0) continue
            for (i in 0 until size) {
                acc[i] = (acc[i].toInt() xor ReedSolomon.mul(c, shard[i].toInt() and 0xFF)).toByte()
            }
        }

        // d_r = acc / c_r
        val cr = row[r]
        if (cr == 0) return null
        val inv = ReedSolomon.inv(cr)
        val out = ByteArray(size)
        for (i in 0 until size) out[i] = ReedSolomon.mul(inv, acc[i].toInt() and 0xFF).toByte()
        return r to out
    }
}
