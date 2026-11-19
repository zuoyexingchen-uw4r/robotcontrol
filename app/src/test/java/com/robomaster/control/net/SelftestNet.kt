package com.robomaster.control.net

import com.robomaster.control.laser.LaserProtocol

/**
 * 非图传流编解码自测（纯 Kotlin，无 Android 依赖）。
 *
 * 运行方式与仓库其它自测一致（用 Android Studio 自带 kotlinc，把 net 目录下的
 * NetProto / PacketCodec / FecBlock / UartSlot / UartSlotFramer 连同
 * video/ReedSolomon.kt、laser/LaserProtocol.kt 和本文件一起编译）：
 *
 *     kotlinc [上述源文件] -include-runtime -d st_net.jar && java -jar st_net.jar
 *
 * 期望：全部 PASS（末行 `net selftest: N/N`）。
 */
object SelftestNet {

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        slotTests()
        packetTests()
        fecTests()
        keepAliveTests()
        framerTests()
        println("net selftest: $passed/${passed + failed}")
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    private fun check(name: String, ok: Boolean) {
        if (ok) {
            passed++
        } else {
            failed++
            println("FAIL  $name")
        }
    }

    private fun slotTests() {
        val lidar = ByteArray(60) { (it * 7).toByte() }
        lidar[0] = 0x55; lidar[1] = 0xAA.toByte()
        val l = UartSlot.lidar(lidar)
        check("雷达槽长度", l.size == 60)
        check("雷达槽帧头", NetProto.headerOf(l) == NetProto.H_LIDAR)

        val mcu = ByteArray(39) { 1 }
        mcu[0] = 0xAA.toByte(); mcu[1] = 0x55
        val m = UartSlot.mcu(mcu)
        check("主控槽补零到 60", m.size == 60 && (39 until 60).all { m[it] == 0.toByte() })
        check("补零不污染原帧", (0 until 39).all { m[it] == mcu[it] })

        // 原始字节分片：55B/槽，末片按 len 截断
        val raw = ByteArray(120) { it.toByte() }
        val slots = UartSlot.uartDown(raw)
        check("120B 分 3 槽", slots.size == 3)
        val back = slots.flatMap { UartSlot.uartPayload(it)!!.toList() }.toByteArray()
        check("原始字节往返一致", back.contentEquals(raw))
        check("分片槽帧头", slots.all { NetProto.headerOf(it) == NetProto.H_UART_DOWN })

        val op = UartSlot.localOp(NetProto.OP_SWITCH_IP, byteArrayOf(192.toByte(), 168.toByte(), 1, 5))
        check("本机操作帧头", NetProto.headerOf(op) == NetProto.H_LOCAL)
        check("本机操作参数", UartSlot.localOpArg(op).contentEquals(byteArrayOf(192.toByte(), 168.toByte(), 1, 5)))
        check("本机操作不写 UART", UartSlot.uartPayload(op) == null)
    }

    private fun packetTests() {
        val slots = (0 until 20).map { i -> ByteArray(60) { (i + it).toByte() } }
        val payload = ByteArray(20 * 60)
        slots.forEachIndexed { i, s -> System.arraycopy(s, 0, payload, i * 60, 60) }
        val pkt = Packet(0, 7, 3, 2, 20, 1234, payload)
        val bytes = pkt.encode()
        check("20 槽包大小 1216", bytes.size == 1216)

        val d = Packet.decode(bytes)
        check("包头往返", d != null && d.seq == 7 && d.blockId == 3 &&
            d.shardStart == 2 && d.count == 20 && d.payload.size == 1200)

        val bad = bytes.copyOf()
        bad[5] = (bad[5].toInt() xor 0xFF).toByte()
        check("CRC 拒绝篡改", Packet.decode(bad) == null)

        bad[3] = NetProto.F_PARITY.toByte()
        check("magic 之外仍需 CRC", Packet.decode(bad) == null)

        val long = Packet(NetProto.F_LONG, 1, 9, 0, 1, 0, ByteArray(700) { 2 })
        val ld = Packet.decode(long.encode())
        check("长帧往返", ld != null && ld.isLong && ld.payload.size == 700)

        check("去重键一致", pkt.dedupeKey() == Packet.decode(bytes)!!.dedupeKey())
    }

    private fun fecTests() {
        val data = (0 until 5).map { i -> ByteArray(60) { ((i * 60 + it) % 251).toByte() } }
        val parity = FecBlock.encode(data)

        val withMissing = arrayOfNulls<ByteArray>(5)
        data.forEachIndexed { i, b -> if (i != 2) withMissing[i] = b }
        val rec = FecBlock.recover(withMissing, parity)
        check("丢 1 槽可恢复", rec != null && rec.first == 2 && rec.second.contentEquals(data[2]))

        val twoMissing = arrayOfNulls<ByteArray>(5)
        data.forEachIndexed { i, b -> if (i != 1 && i != 3) twoMissing[i] = b }
        check("丢 2 槽拒绝恢复", FecBlock.recover(twoMissing, parity) == null)

        val noParity = arrayOfNulls<ByteArray>(5)
        data.forEachIndexed { i, b -> if (i != 0) noParity[i] = b }
        check("校验丢失拒绝恢复", FecBlock.recover(noParity, null) == null)
    }

    private fun keepAliveTests() {
        val ka = UartSlot.keepAlive(41, 123456789L)
        check("KA 帧头", UartSlot.isKeepAlive(ka))
        check("KA 序号", UartSlot.kaSeq(ka) == 41)
        check("KA 时间戳", UartSlot.kaTs(ka) == 123456789L and 0xFFFFFFFFL)
        check("KA CRC", NetProto.crc16(ka, 0, 58) == NetProto.u16(ka, 58))
    }

    private fun framerTests() {
        val framer = UartSlotFramer()

        val lidar = ByteArray(60)
        lidar[0] = 0x55; lidar[1] = 0xAA.toByte()
        lidar[4] = 5; lidar[5] = 0
        lidar[6] = 100; lidar[7] = 0        // 起始角 100 (0.01°)
        lidar[56] = 0x4C; lidar[57] = 0x04  // 终止角 1100 → 跨度 1000，合法
        for (i in 8 until 56) lidar[i] = (i % 250).toByte()

        val master = LaserProtocol.buildMaster(LaserProtocol.MASTER_UP_TYPE, 3, IntArray(16) { it })
        val stream = lidar + master
        val out = ArrayList<ByteArray>()
        framer.feed(stream, out)

        check("切出 2 槽", out.size == 2)
        check("雷达槽帧头", out.getOrNull(0)?.let { NetProto.headerOf(it) } == NetProto.H_LIDAR)
        check("主控槽补零", out.getOrNull(1)?.let { it.size == 60 && (39 until 60).all { i -> it[i] == 0.toByte() } } == true)
    }
}
