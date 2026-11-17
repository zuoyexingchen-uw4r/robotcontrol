package com.robomaster.control.laser

/**
 * 激光 + 主控混合协议 JVM 自测（无 Android 依赖）。
 *
 * 用 kotlinc 直接编译运行，验证：
 * 1. 主控帧 60 字节组帧/解析往返 + CRC；
 * 2. 雷达帧合法性校验；
 * 3. 混合流切帧器在粘包/断帧/乱字节下的无损分离。
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
        // ── 1. 主控帧组帧/解析往返 ──
        val fields = IntArray(LaserProtocol.FIELD_COUNT) { it + 1 }
        val frame = LaserProtocol.buildMaster(LaserProtocol.MASTER_UP_TYPE, 12345, fields)
        check("主控帧长度=60", frame.size == LaserProtocol.FRAME_LEN, "len=${frame.size}")
        check("主控帧头 AA55",
            (frame[0].toInt() and 0xFF) == LaserProtocol.MASTER_HDR0 &&
            (frame[1].toInt() and 0xFF) == LaserProtocol.MASTER_HDR1)

        val parsed = LaserProtocol.parseMaster(frame)
        check("主控帧解码", parsed != null && parsed.first == LaserProtocol.MASTER_UP_TYPE &&
            parsed.second == 12345, "parsed=${parsed?.let { "${it.first}/${it.second}" }}")
        check("字段往返", parsed != null && parsed.third.contentEquals(fields))

        // ── 2. CRC 校验：改 1 字节应失败 ──
        val bad = frame.copyOf()
        bad[10] = (bad[10].toInt() xor 0xFF).toByte()
        check("坏 CRC 拒绝", LaserProtocol.parseMaster(bad) == null)

        // ── 3. 雷达帧合法性 ──
        val lidar = ByteArray(LaserProtocol.FRAME_LEN)
        lidar[0] = 0x55; lidar[1] = 0xAA.toByte()
        lidar[4] = 0x2C; lidar[5] = 0x01            // 转速 300
        lidar[6] = 0x00; lidar[7] = 0x00            // 起始角 0
        lidar[56] = 0x58; lidar[57] = 0x02          // 终止角 600 (6.00°)
        for (i in 0 until 16) {
            val d = 1000 + i
            lidar[8 + i * 3] = (d and 0xFF).toByte()
            lidar[9 + i * 3] = ((d shr 8) and 0xFF).toByte()
            lidar[10 + i * 3] = 100
        }
        check("雷达帧合法", LaserProtocol.isLidarFrame(lidar))
        check("启动空帧拒绝", !LaserProtocol.isLidarFrame(ByteArray(LaserProtocol.FRAME_LEN)))

        // ── 4. 混合流切帧：粘包（雷达+主控一起喂）+ 乱字节 ──
        val framer = LaserStreamFramer()
        val out = ArrayList<LaserStreamFramer.Frame>()
        val stream = ByteArray(lidar.size + frame.size + 1)
        lidar.copyInto(stream, 0)
        frame.copyInto(stream, lidar.size)
        stream[lidar.size + frame.size] = 0x00     // 乱字节
        framer.feed(stream, out)
        check("粘包切出 2 帧", out.size == 2, "got=${out.size}")
        if (out.size == 2) {
            check("帧1=雷达", out[0].type == LaserStreamFramer.FrameType.LIDAR)
            check("帧2=主控", out[1].type == LaserStreamFramer.FrameType.MASTER)
        }
        check("雷达计数=1 主控计数=1", framer.lidarFrames == 1L && framer.masterFrames == 1L,
            "lidar=${framer.lidarFrames} master=${framer.masterFrames}")

        // ── 5. 断帧切分（逐字节喂入） ──
        val framer2 = LaserStreamFramer()
        val out2 = ArrayList<LaserStreamFramer.Frame>()
        val mixed = lidar + frame
        for (b in mixed) framer2.feed(byteArrayOf(b), out2)
        check("逐字节喂入无损分离", out2.size == 2, "got=${out2.size}")

        // ── 6. 网络封装 wrap ──
        val wrapped = LaserProtocol.wrap(LaserProtocol.T_LIDAR, lidar)
        check("wrap 后长度=61", wrapped.size == LaserProtocol.FRAME_LEN + 1, "len=${wrapped.size}")
        check("wrap 类型头=0x03", wrapped[0].toInt() and 0xFF == LaserProtocol.T_LIDAR)

        println("\n==== 结果: $pass 通过, $fail 失败 ====")
        if (fail > 0) throw IllegalStateException("$fail 项失败")
    }
}
