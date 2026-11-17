package com.robomaster.control.laser

/**
 * 激光 + 主控混合协议 JVM 自测（无 Android 依赖）。
 *
 * 验证：
 * 1. 主控帧 39 字节组帧/解析往返 + CRC；
 * 2. 雷达帧合法性校验；
 * 3. 混合流切帧器（60B 雷达 + 39B 主控）粘包/断帧无损分离；
 * 4. FEC 编码器：块 = k 数据 + m 校验，数据分片可 parseShard 还原。
 * （擦除解码的恢复能力由上位机 laser_host.py 自测覆盖，跨语言字节一致。）
 */
object SelftestLaser {
    private var pass = 0
    private var fail = 0

    private fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) { pass++; println("PASS  $name  $detail") }
        else { fail++; println("FAIL  $name  $detail") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        // ── 1. 主控帧（39B）组帧/解析往返 ──
        val fields = IntArray(LaserProtocol.FIELD_COUNT) { it + 1 }
        val frame = LaserProtocol.buildMaster(LaserProtocol.MASTER_UP_TYPE, 12345, fields)
        check("主控帧长度=39", frame.size == LaserProtocol.MASTER_FRAME_LEN, "len=${frame.size}")
        check("主控帧头 AA55",
            (frame[0].toInt() and 0xFF) == LaserProtocol.MASTER_HDR0 &&
            (frame[1].toInt() and 0xFF) == LaserProtocol.MASTER_HDR1)

        val parsed = LaserProtocol.parseMaster(frame)
        check("主控帧解码", parsed != null && parsed.first == LaserProtocol.MASTER_UP_TYPE &&
            parsed.second == 12345, "parsed=${parsed?.let { "${it.first}/${it.second}" }}")
        check("字段往返", parsed != null && parsed.third.contentEquals(fields))

        // ── 2. CRC：改 1 字节失败 ──
        val bad = frame.copyOf()
        bad[10] = (bad[10].toInt() xor 0xFF).toByte()
        check("坏 CRC 拒绝", LaserProtocol.parseMaster(bad) == null)

        // ── 3. 雷达帧合法性 ──
        val lidar = ByteArray(LaserProtocol.LIDAR_FRAME_LEN)
        lidar[0] = 0x55; lidar[1] = 0xAA.toByte()
        lidar[4] = 0x2C; lidar[5] = 0x01
        lidar[6] = 0x00; lidar[7] = 0x00
        lidar[56] = 0x58; lidar[57] = 0x02
        for (i in 0 until 16) {
            val d = 1000 + i
            lidar[8 + i * 3] = (d and 0xFF).toByte()
            lidar[9 + i * 3] = ((d shr 8) and 0xFF).toByte()
            lidar[10 + i * 3] = 100
        }
        check("雷达帧合法", LaserProtocol.isLidarFrame(lidar))
        check("启动空帧拒绝", !LaserProtocol.isLidarFrame(ByteArray(LaserProtocol.LIDAR_FRAME_LEN)))

        // ── 4. 混合流切帧（60B 雷达 + 39B 主控，粘包 + 乱字节） ──
        val framer = LaserStreamFramer()
        val out = ArrayList<LaserStreamFramer.Frame>()
        val stream = ByteArray(lidar.size + frame.size + 1)
        lidar.copyInto(stream, 0)
        frame.copyInto(stream, lidar.size)
        stream[lidar.size + frame.size] = 0x00
        framer.feed(stream, out)
        check("粘包切出 2 帧", out.size == 2, "got=${out.size}")
        if (out.size == 2) {
            check("帧1=雷达", out[0].type == LaserStreamFramer.FrameType.LIDAR)
            check("帧2=主控", out[1].type == LaserStreamFramer.FrameType.MASTER)
        }

        // ── 5. 断帧切分（逐字节） ──
        val framer2 = LaserStreamFramer()
        val out2 = ArrayList<LaserStreamFramer.Frame>()
        for (b in lidar + frame) framer2.feed(byteArrayOf(b), out2)
        check("逐字节喂入无损分离", out2.size == 2, "got=${out2.size}")

        // ── 6. FEC 编码器 ──
        val enc = LaserFecEncoder(k = 8, m = 4)
        var packets: List<LaserFecEncoder.Packet>? = null
        for (i in 0 until 8) {
            val t = if (i % 4 == 3) LaserProtocol.T_MASTER_UP else LaserProtocol.T_LIDAR
            val fr = if (t == LaserProtocol.T_MASTER_UP)
                LaserProtocol.buildMaster(LaserProtocol.MASTER_UP_TYPE, i, IntArray(16) { i })
            else lidar
            packets = enc.push(t, fr)
        }
        check("FEC 块 = k+m 个报文", packets != null && packets!!.size == 12,
            "got=${packets?.size}")
        if (packets != null) {
            // 数据分片 0..7 应能 parseShard 还原
            val shard = LaserProtocol.parseShard(packets!![0].bytes.copyOfRange(2, 66))
            check("FEC 数据分片可还原", shard != null && shard.first == LaserProtocol.T_LIDAR)
            // 报文头 [block_id][shard_idx]
            check("报文头 shard_idx", packets!![0].bytes[1].toInt() and 0xFF == 0)
            check("校验分片 idx=k", packets!![8].bytes[1].toInt() and 0xFF == 8)
        }

        // ── 7. 残块冲刷 ──
        val enc2 = LaserFecEncoder(k = 8, m = 4)
        enc2.push(LaserProtocol.T_LIDAR, lidar)
        val flushed = enc2.flush()
        check("残块冲刷补零到 k", flushed != null && flushed!!.size == 12, "got=${flushed?.size}")

        println("\n==== 结果: $pass 通过, $fail 失败 ====")
        if (fail > 0) throw IllegalStateException("$fail 项失败")
    }
}
