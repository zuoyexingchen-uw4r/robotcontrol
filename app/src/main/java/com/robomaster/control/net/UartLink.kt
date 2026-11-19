package com.robomaster.control.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 非图传信号的单流复用链路（UDP 48000 / TCP 49000）。
 *
 * 一条逻辑流，两种承载：
 * - **UDP 48000**：默认。点云按滑杆 1/10/20 攒包，控制帧 n=1 立即发。
 * - **TCP 49000**：常连做备份。点云强制 20/包，控制帧立即发；`TCP_NODELAY` 关 Nagle，
 *   分帧 `[u32 len][packet]`，与 UDP 共用同一套报文，切换时不需要改格式。
 *
 * 发送侧三件事：
 * 1. **攒包**：数据槽（雷达/遥测/KA）攒够 [batch] 个再发；控制槽（[pushSlot] urgent=true）
 *    插队、单独成包、不等待。
 * 2. **重放**：[replay] 次额外副本（0/1/2），副本与原包**字节完全相同**（seq 不变），
 *    接收端按 (seq, blockId, shardStart) 去重；TCP 下不重放（本来就不丢）。
 * 3. **FEC**：每 5 个数据槽算 1 个校验槽，校验槽**单独成包**。尾块不足 5 个不发校验，
 *    避免补零歧义（见 [FecBlock]）。
 *
 * 接收侧：
 * - 同一个 UDP socket 收上位机回包（源端口由系统分配）；
 * - 按包头 flags 分流：长帧 JSON → [onJson]，普通包 → 逐槽校验帧头后交给 [onSlot]；
 * - KEEP_ALIVE 由本类消费（算 RTT），不向上抛；
 * - 重放副本按去重表丢弃。
 *
 * 自适应：AUTO 模式下用 KA 回显判断 UDP 健康度，2.5s 收不到回显就切 TCP；恢复后切回。
 * 没收到过任何回显时（例如上位机尚未实现回显）保持 UDP，避免误判。
 */
class UartLink(
    val host: String,
    private val udpPort: Int = NetProto.UDP_PORT,
    private val tcpPort: Int = NetProto.TCP_PORT,
) {

    enum class Mode { AUTO, UDP, TCP }

    data class Stats(
        val mode: String,
        val batch: Int,
        val replay: Int,
        val udpOk: Boolean,
        val tcpOk: Boolean,
        val rttMs: Int,
        val txPackets: Long,
        val txSlots: Long,
        val txReplay: Long,
        val rxPackets: Long,
        val rxDup: Long,
        val rxBad: Long,
        val kaSent: Long,
        val kaEcho: Long,
        val lastError: String,
    )

    var onLog: (String) -> Unit = {}
    var onSlot: ((ByteArray) -> Unit)? = null
    var onJson: ((String) -> Unit)? = null
    var onStats: ((Stats) -> Unit)? = null

    /**
     * 额外统计订阅者（1Hz）。
     *
     * [onStats] 只有一个槽位，先注册的模块（激光桥）会一直占着；控制台要显示
     * 上下行码率与延迟，又不能抢激光桥的回调，所以用订阅列表并行分发。
     */
    private val statsListeners = java.util.concurrent.CopyOnWriteArrayList<(Stats) -> Unit>()

    fun addStatsListener(l: (Stats) -> Unit) {
        if (!statsListeners.contains(l)) statsListeners.add(l)
    }

    fun removeStatsListener(l: (Stats) -> Unit) {
        statsListeners.remove(l)
    }

    /** 点云攒包数：1 / 10 / 20（TCP 下强制 20） */
    @Volatile var batch: Int = 10

    /** 控制帧重放次数：0 / 1 / 2 */
    @Volatile var replay: Int = 1

    /** 承载选择；AUTO 由 KA 健康度决定 */
    @Volatile var mode: Mode = Mode.AUTO

    private val lock = ReentrantLock()
    private val work = lock.newCondition()
    private val dataQ = ArrayDeque<ByteArray>()
    private val urgentQ = ArrayDeque<ByteArray>()
    private val pktQ = ArrayDeque<ByteArray>()

    private var firstDataAt = 0L
    private val fecBuf = ArrayList<ByteArray>(NetProto.FEC_K)
    private var blockNo = 0
    private var globalIdx = 0L
    private var seq = 0
    private var fragGroup = 0
    private var kaSeqNo = 0

    @Volatile private var running = false

    private var udpSock: DatagramSocket? = null
    private var udpAddr: InetAddress? = null
    private val udpSendLock = Any()

    private var tcpSock: Socket? = null
    private var tcpOut: DataOutputStream? = null
    private val tcpWriteLock = Any()

    @Volatile private var tcpOk = false
    @Volatile private var kaEchoSeen = false
    @Volatile private var lastEchoAt = 0L
    @Volatile private var rttMs = 0
    @Volatile private var lastError = ""

    private val txPackets = AtomicLong()
    private val txSlots = AtomicLong()
    private val txReplay = AtomicLong()
    private val rxPackets = AtomicLong()
    private val rxDup = AtomicLong()
    private val rxBad = AtomicLong()
    private val kaSent = AtomicLong()
    private val kaEcho = AtomicLong()

    private val seen = LinkedHashSet<Long>()
    private val seenOrder = ArrayDeque<Long>()
    private val frags = HashMap<Int, Array<ByteArray?>>()

    private val threads = ArrayList<Thread>()

    fun start() {
        if (running) return
        running = true
        // 上一次会话可能停在半块上，别让残槽混进新块
        fecBuf.clear()
        firstDataAt = nowMs()
        openUdp()
        spawn("net-sender") { senderLoop() }
        spawn("net-udp") { udpLoop() }
        spawn("net-tcp") { tcpLoop() }
        spawn("net-ka") { kaLoop() }
        spawn("net-stat") { statLoop() }
        log("非图传流启动 → $host udp=$udpPort tcp=$tcpPort（batch=$batch replay=$replay）")
    }

    fun stop() {
        if (!running) return
        running = false
        lock.withLock { work.signalAll() }
        closeUdp()
        closeTcp()
        threads.forEach { runCatching { it.interrupt() } }
        threads.clear()
        log("非图传流已停止")
    }

    // ───────────────────────── 发送 ─────────────────────────

    /**
     * 送一个 60B 槽。
     * @param urgent 控制类帧（本机操作 / 开火 / 导航）传 true：立即成包、插队、按 [replay] 重放。
     */
    fun pushSlot(slot: ByteArray, urgent: Boolean = false) {
        require(slot.size == NetProto.SLOT) { "槽长必须 ${NetProto.SLOT}B" }
        lock.withLock {
            if (urgent) {
                if (urgentQ.size >= 64) urgentQ.removeFirst()
                urgentQ.addLast(slot)
            } else {
                if (dataQ.size >= 400) dataQ.removeFirst()
                if (dataQ.isEmpty()) firstDataAt = nowMs()
                dataQ.addLast(slot)
            }
            work.signal()
        }
    }

    /** 长帧 JSON（视觉结果 / 设置快照 / set_req），超 1200B 自动分片，立即发送 */
    fun sendJson(json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return
        val fragCount = (bytes.size + NetProto.MAX_PAYLOAD - 1) / NetProto.MAX_PAYLOAD
        if (fragCount > 255) {
            log("JSON 过大（${bytes.size}B），丢弃")
            return
        }
        val gid = fragGroup++ and 0xFFFF
        for (i in 0 until fragCount) {
            val from = i * NetProto.MAX_PAYLOAD
            val to = minOf(bytes.size, from + NetProto.MAX_PAYLOAD)
            val chunk = bytes.copyOfRange(from, to)
            val pkt = Packet(
                flags = NetProto.F_LONG,
                seq = nextSeq(),
                blockId = gid,
                shardStart = i,
                count = fragCount,
                tsMs = nowMs().toInt(),
                payload = chunk,
            ).encode()
            lock.withLock {
                if (pktQ.size >= 64) pktQ.removeFirst()
                pktQ.addLast(pkt)
                work.signal()
            }
        }
    }

    private fun senderLoop() {
        while (running) {
            val prepared = lock.withLock { pktQ.pollFirst() }
            if (prepared != null) {
                sendWire(prepared, urgent = false)   // 长帧不做重放，靠视觉帧率与心跳冗余
                continue
            }
            val urgent = lock.withLock { urgentQ.pollFirst() }
            if (urgent != null) {
                sendSlots(listOf(urgent), urgent = true)
                continue
            }
            val slots = takeBatch()
            if (slots != null && slots.isNotEmpty()) sendSlots(slots, urgent = false)
        }
    }

    /** 数据槽凑批：够 [batch] 个或等待超过 [HOLD_MS] 就发，避免低速时饿死 */
    private fun takeBatch(): List<ByteArray>? {
        lock.withLock {
            while (running) {
                if (dataQ.isEmpty() && urgentQ.isEmpty() && pktQ.isEmpty()) {
                    work.await(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                    continue
                }
                // TCP 下点云强制 20 槽一包：减少小包数量，把延迟交给 TCP 的流式缓冲处理
                val target = if (useTcp()) NetProto.MAX_BATCH else batch.coerceIn(1, NetProto.MAX_BATCH)
                val waited = nowMs() - firstDataAt
                if (dataQ.size >= target || (dataQ.isNotEmpty() && waited >= HOLD_MS)) {
                    val n = minOf(target, dataQ.size)
                    val out = ArrayList<ByteArray>(n)
                    repeat(n) { out += dataQ.removeFirst() }
                    if (dataQ.isNotEmpty()) firstDataAt = nowMs()
                    return out
                }
                work.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            return null
        }
    }

    private fun sendSlots(slots: List<ByteArray>, urgent: Boolean) {
        val startIdx = globalIdx
        globalIdx += slots.size

        val parities = ArrayList<Pair<Int, ByteArray>>()
        for (s in slots) {
            fecBuf.add(s)
            if (fecBuf.size == NetProto.FEC_K) {
                parities += blockNo to FecBlock.encode(fecBuf)
                fecBuf.clear()
                blockNo = (blockNo + 1) and 0xFFFF
            }
        }

        val payload = ByteArray(slots.size * NetProto.SLOT)
        slots.forEachIndexed { i, s -> System.arraycopy(s, 0, payload, i * NetProto.SLOT, NetProto.SLOT) }
        val dataPkt = Packet(
            flags = if (urgent) NetProto.F_URGENT else 0,
            seq = nextSeq(),
            blockId = (startIdx / NetProto.FEC_K).toInt() and 0xFFFF,
            shardStart = (startIdx % NetProto.FEC_K).toInt(),
            count = slots.size,
            tsMs = nowMs().toInt(),
            payload = payload,
        ).encode()

        sendWire(dataPkt, urgent)
        txSlots.addAndGet(slots.size.toLong())

        // 校验槽单独成包：和数据同包时整包丢失会连校验一起丢，等于没有冗余
        for ((bid, par) in parities) {
            val parityPkt = Packet(
                flags = NetProto.F_PARITY,
                seq = nextSeq(),
                blockId = bid,
                shardStart = NetProto.FEC_K,
                count = 1,
                tsMs = nowMs().toInt(),
                payload = par,
            ).encode()
            sendWire(parityPkt, urgent = false)
        }
    }

    private fun sendWire(pkt: ByteArray, urgent: Boolean) {
        // 控制帧按 replay 配置重发副本；TCP 本身可靠，不重复占用带宽
        val copies = if (urgent && !useTcp()) 1 + replay.coerceIn(0, 2) else 1
        var sent = 0
        for (i in 0 until copies) {
            val ok = if (useTcp()) sendTcp(pkt) else sendUdp(pkt)
            if (ok) sent++ else break
        }
        if (sent > 0) {
            txPackets.incrementAndGet()
            if (sent > 1) txReplay.addAndGet((sent - 1).toLong())
        }
    }

    private fun useTcp(): Boolean {
        if (mode == Mode.TCP) return tcpOk
        if (mode == Mode.UDP) return false
        if (!tcpOk) return false
        if (!kaEchoSeen) return false          // 上位机还没回显过，不能判定 UDP 差
        return nowMs() - lastEchoAt > KA_LOST_MS
    }

    private fun sendUdp(pkt: ByteArray): Boolean {
        val s = udpSock ?: return false
        return try {
            val addr = udpAddr ?: InetAddress.getByName(host).also { udpAddr = it }
            val dg = DatagramPacket(pkt, pkt.size, addr, udpPort)
            synchronized(udpSendLock) { s.send(dg) }
            true
        } catch (e: Exception) {
            lastError = "UDP 发送失败: ${e.message}"
            false
        }
    }

    private fun sendTcp(pkt: ByteArray): Boolean {
        val out = tcpOut ?: return false
        return try {
            synchronized(tcpWriteLock) {
                out.writeInt(pkt.size)
                out.write(pkt)
                out.flush()
            }
            true
        } catch (e: Exception) {
            lastError = "TCP 发送失败: ${e.message}"
            closeTcp()
            false
        }
    }

    // ───────────────────────── 接收 ─────────────────────────

    private fun openUdp() {
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            s.soTimeout = 500
            udpSock = s
        } catch (e: Exception) {
            lastError = "UDP 绑定失败: ${e.message}"
            log(lastError)
        }
    }

    private fun closeUdp() {
        runCatching { udpSock?.close() }
        udpSock = null
    }

    private fun udpLoop() {
        val buf = ByteArray(2048)
        while (running) {
            val s = udpSock ?: break
            val dg = DatagramPacket(buf, buf.size)
            try {
                s.receive(dg)
                dispatch(buf, dg.length)
            } catch (_: SocketTimeoutException) {
                // 空闲正常
            } catch (e: Exception) {
                if (running) {
                    lastError = "UDP 接收失败: ${e.message}"
                    Thread.sleep(200)
                }
            }
        }
    }

    private fun tcpLoop() {
        while (running) {
            try {
                if (tcpSock == null) {
                    val s = Socket()
                    s.tcpNoDelay = true       // 关 Nagle：控制帧不攒
                    s.keepAlive = true
                    s.connect(InetSocketAddress(host, tcpPort), 3000)
                    tcpSock = s
                    tcpOut = DataOutputStream(s.getOutputStream())
                    tcpOk = true
                    log("TCP 备份已连接 $host:$tcpPort")
                }
                val inp = DataInputStream(tcpSock!!.getInputStream())
                while (running && tcpSock != null) {
                    val len = inp.readInt()
                    if (len <= 0 || len > 65536) throw IOException("非法帧长 $len")
                    val buf = ByteArray(len)
                    inp.readFully(buf)
                    dispatch(buf, buf.size)
                }
            } catch (e: Exception) {
                if (running) lastError = "TCP 链路: ${e.message}"
            } finally {
                closeTcp()
            }
            if (running) Thread.sleep(2000)
        }
    }

    private fun closeTcp() {
        tcpOk = false
        runCatching { tcpSock?.close() }
        tcpSock = null
        tcpOut = null
    }

    private fun dispatch(buf: ByteArray, len: Int) {
        val p = Packet.decode(buf, len)
        if (p == null) {
            rxBad.incrementAndGet()
            return
        }
        rxPackets.incrementAndGet()

        if (!markSeen(p.dedupeKey())) {
            rxDup.incrementAndGet()
            return
        }

        if (p.isLong) {
            onLong(p)
            return
        }
        if (p.isParity) return          // 下行不做块纠错，收到校验包忽略

        for (i in 0 until p.count) {
            val from = i * NetProto.SLOT
            val slot = p.payload.copyOfRange(from, from + NetProto.SLOT)
            route(slot)
        }
    }

    private fun route(slot: ByteArray) {
        if (UartSlot.isKeepAlive(slot)) {
            val ts = UartSlot.kaTs(slot)
            val rtt = ((nowMs() and 0xFFFFFFFFL) - ts) and 0xFFFFFFFFL
            if (rtt in 0..5000) rttMs = rtt.toInt()
            kaEchoSeen = true
            lastEchoAt = nowMs()
            kaEcho.incrementAndGet()
            return
        }
        onSlot?.invoke(slot)
    }

    private fun onLong(p: Packet) {
        val done: ByteArray?
        synchronized(frags) {
            if (frags.size > 64) frags.clear()   // 半截分片不会无限堆积
            var arr = frags[p.blockId]
            if (arr == null || arr.size != p.count) {
                arr = arrayOfNulls(p.count)
                frags[p.blockId] = arr
            }
            if (p.shardStart in arr.indices) arr[p.shardStart] = p.payload
            done = if (arr.all { it != null }) {
                frags.remove(p.blockId)
                val total = arr.sumOf { it!!.size }
                val joined = ByteArray(total)
                var off = 0
                for (part in arr) {
                    val b = part!!
                    System.arraycopy(b, 0, joined, off, b.size)
                    off += b.size
                }
                joined
            } else null
        }
        if (done != null) onJson?.invoke(String(done, Charsets.UTF_8))
    }

    /** 去重：重放副本字节相同 → 键相同 → 只处理第一次 */
    private fun markSeen(key: Long): Boolean {
        synchronized(seen) {
            if (!seen.add(key)) return false
            seenOrder.addLast(key)
            while (seenOrder.size > SEEN_CAP) {
                seen.remove(seenOrder.removeFirst())
            }
            return true
        }
    }

    // ───────────────────────── 保活 / 统计 ─────────────────────────

    private fun kaLoop() {
        while (running) {
            Thread.sleep(KA_INTERVAL_MS)
            if (!running) break
            val slot = UartSlot.keepAlive(kaSeqNo++, nowMs())
            pushSlot(slot, urgent = false)   // 与雷达同一条数据流，暴露整列问题
            kaSent.incrementAndGet()
        }
    }

    private fun statLoop() {
        while (running) {
            Thread.sleep(1000)
            if (!running) break
            val st = snapshot()
            onStats?.invoke(st)
            statsListeners.forEach { runCatching { it(st) } }
        }
    }

    fun snapshot(): Stats = Stats(
        mode = when {
            mode == Mode.TCP -> "TCP"
            mode == Mode.UDP -> "UDP"
            useTcp() -> "TCP(auto)"
            else -> "UDP(auto)"
        },
        batch = batch,
        replay = replay,
        udpOk = !useTcp(),
        tcpOk = tcpOk,
        rttMs = rttMs,
        txPackets = txPackets.get(),
        txSlots = txSlots.get(),
        txReplay = txReplay.get(),
        rxPackets = rxPackets.get(),
        rxDup = rxDup.get(),
        rxBad = rxBad.get(),
        kaSent = kaSent.get(),
        kaEcho = kaEcho.get(),
        lastError = lastError,
    )

    fun resetStats() {
        txPackets.set(0); txSlots.set(0); txReplay.set(0)
        rxPackets.set(0); rxDup.set(0); rxBad.set(0)
        kaSent.set(0); kaEcho.set(0)
        synchronized(seen) { seen.clear(); seenOrder.clear() }
    }

    // ───────────────────────── 工具 ─────────────────────────

    private fun nextSeq(): Int {
        val s = seq
        seq = (seq + 1) and 0xFFFF
        return s
    }

    private fun spawn(name: String, body: () -> Unit) {
        val t = Thread({
            try {
                body()
            } catch (_: InterruptedException) {
                // stop() 打断，正常退出
            } catch (e: Exception) {
                if (running) {
                    lastError = "$name 异常: ${e.message}"
                    log(lastError)
                }
            }
        }, name)
        t.isDaemon = true
        t.start()
        threads += t
    }

    private fun log(msg: String) {
        runCatching { onLog(msg) }
    }

    companion object {
        /** 数据槽凑不满时的最长等待：宁可多发几个小包，也不无限等 */
        private const val HOLD_MS = 80L

        /** 保活间隔；同时也是 UDP 健康度判定的基础 */
        private const val KA_INTERVAL_MS = 500L

        /** 连续这么久收不到 KA 回显 → 判 UDP 不健康，切 TCP */
        private const val KA_LOST_MS = 2500L

        private const val SEEN_CAP = 512

        fun nowMs(): Long = System.nanoTime() / 1_000_000L
    }
}
