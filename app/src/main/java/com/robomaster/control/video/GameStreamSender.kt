package com.robomaster.control.video

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sunshine/Moonlight 兼容的视频发送器。
 *
 * 职责（对照 Sunshine stream.cpp 发送主循环）：
 *  1. 帧前插 8B 短帧头，按 1008B 净载荷切数据分片；
 *  2. 数据分片按 ≤255/块拆 FEC 块，Reed-Solomon 生成 parity 分片（flags=0 区分）；
 *  3. 填 RTP 全局序号 / 90kHz 时间戳 / frameIndex / streamPacketIndex=seq<<8 / SOF/EOF / fecInfo；
 *  4. **帧级 pacing**：按采集帧率决定「何时发下一帧」，帧内全速突发发完。
 *
 * 线程模型（核心设计，全部围绕「绝不阻塞编码回调线程」）：
 *
 *   MediaCodec 回调线程  ──sendFrame()──►  只做一次 buffer 拷贝 + 入队决策，立即返回
 *                                              （不组包、不 RS 编码、不 sleep）
 *   发送线程 gs-sender   ──take()──►  组包 + RS 编码 + pacing + socket.send()
 *
 *  三次踩坑记录（都实测复现过，别改回去）：
 *
 *  (a) 在编码回调线程里 sleep：debt 是累计绝对差，IDR 大帧让 debt 突增上百毫秒
 *      → 回调线程睡几百 ms → 编码器输出缓冲无人释放 → 编码阻塞 → 帧率衰减。
 *  (b) 按**码率**逐包平滑 pacing（bitrate*0.8 → 每包 2.5ms@4Mbps）：4Mbps@30fps
 *      每帧约 119 包，要 300ms 才发完，帧间隔仅 33ms → 发送低于编码产出，
 *      实测 161 发 / 112 丢。网络不是瓶颈（3.6M vs 100Mbps），是 pacing 自造的。
 *  (c) 组包 + RS 编码留在编码回调线程：一帧几百万次 GF 运算要几十毫秒，
 *      同样卡住编码器输出缓冲。=> 一并移到发送线程。
 *
 *  策略取向：**延迟优先，宁可丢帧**。队列只留很小的缓冲，发送跟不上就丢过期帧，
 *  绝不用 sleep/阻塞去"保帧"——那是拿延迟换帧数，本末倒置。
 */
class GameStreamSender(
    hostIp: String,
    private val port: Int,
    /** 仅作记录/诊断用，**不参与限速**——按码率 pacing 是错误做法，见坑 (b) */
    private val bitrateBps: Int,
    /** 采集帧率，帧级 pacing 用：决定「何时可以发下一帧」，帧内全速突发 */
    private val fps: Int = 30,
    private val fecPercentage: Int = 30,
) {
    private val socket = DatagramSocket()
    private val addr: InetAddress = InetAddress.getByName(hostIp)
    private val ssrc: Int = (Math.random() * Int.MAX_VALUE).toInt()

    private var seq = 0
    private var frameIndex = 0

    /** 帧级 pacing：下一帧可以开始发送的时刻（ms）。帧内全速，不做逐包限速 */
    private val frameIntervalMs: Long = if (fps > 0) (1000L / fps) else 0L
    private var nextFrameDueMs = 0L

    /**
     * 待发送的**原始帧**（未组包）。容量 2：一个正在被发送线程组包/发送，
     * 一个刚入队；超过就说明发送跟不上，按丢帧规则处理。
     * 存原始帧而非 DatagramPacket，是为了把组包开销从编码线程挪走。
     */
    private class RawFrame(
        val annexB: ByteArray,
        val frameType: Int,
        val ptsUs: Long,
        val latencyDeciMs: Int,
    )
    private val queue = LinkedBlockingQueue<RawFrame>(2)

    @Volatile private var stopped = false
    @Volatile private var sentFrames = 0
    @Volatile private var droppedFrames = 0
    @Volatile private var droppedIdrFrames = 0
    private var lastDropLog = 0L

    var onLog: (String) -> Unit = {}

    /**
     * pacing 空闲通知（发送线程调用）：这一帧提前发完，距下一帧还有 [idleMs] 毫秒富余。
     * 回调里**不要做重活**——会直接推迟本帧发送、抬高延迟。适合「标记一下、
     * 请求个关键帧」这类轻量动作。
     */
    var onIdle: ((idleMs: Long) -> Unit)? = null

    /**
     * 丢帧通知（**编码线程**调用）：刚丢弃了一帧。
     *
     * 为什么需要它：被丢的帧在码流里留下空洞，解码器的参考链（H.264 的 DPB /
     * HEVC 的 RPS）会断裂，后续 P 帧找不到参考帧 → 运动模糊、HEVC 直接报
     * "Could not find ref with POC"。所以丢帧后必须尽快补一个 IDR 把参考链重建起来。
     * 上层应在回调里请求关键帧（建议加节流，避免丢帧风暴引发 IDR 风暴）。
     */
    var onDrop: (() -> Unit)? = null

    private val worker = Thread({ runLoop() }, "gs-sender").apply { start() }

    init {
        runCatching { socket.sendBufferSize = 1 shl 20 }
    }

    /**
     * 由 MediaCodec 回调线程调用。**只做入队决策，绝不组包/编码/睡眠**——这些都是
     * 几十毫秒量级的活，留在编码线程上就会卡住编码器输出缓冲（坑 c）。
     *
     * 丢帧规则（队列已满时）：
     *  - 待发的是 IDR（关键帧）→ 丢**新帧**，保住 IDR。IDR 一丢，解码器没有参考帧，
     *    后续 P 帧全解不出，画面会糊到下一个 IDR（用户实测的「3~5 秒清晰一下又糊」）。
     *    丢几个过期 P 帧无妨，但白丢一个 IDR 的代价是两秒花屏。
     *  - 待发的是 P 帧 → 丢**旧帧**，旧帧到接收端已经过期，发它只是白占信道、抬延迟。
     */
    fun sendFrame(annexB: ByteArray, frameType: Int, ptsUs: Long, latencyDeciMs: Int = 0) {
        if (queue.size >= 2) {
            val pending = queue.peek()
            if (pending != null && pending.frameType == GameStream.FRAME_TYPE_IDR) {
                // 关键帧优先：丢新帧
                droppedFrames++
                if (frameType == GameStream.FRAME_TYPE_IDR) droppedIdrFrames++
                onDrop?.invoke()
                noteDrops()
                return
            }
            // 旧帧已过期：丢旧换新
            queue.clear()
            droppedFrames++
            onDrop?.invoke()
        }
        queue.offer(RawFrame(annexB, frameType, ptsUs, latencyDeciMs))
        noteDrops()
    }

    /** 发送线程主循环：取原始帧 → 组包 → 帧级 pacing → 全速发完 */
    private fun runLoop() {
        while (!stopped) {
            val f = try {
                queue.poll(200, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                continue
            } ?: continue
            runCatching { pacedSend(buildPackets(f)) }
                .onFailure { Log.w(TAG, "组包/发送失败: ${it.message}") }
        }
    }

    /**
     * 低延迟发送：**收到就立刻发，不等帧边界**。
     *
     * 曾经这里会 `Thread.sleep(nextFrameDueMs - now)`，即把这一帧硬等到"下一帧时刻"
     * 再发——最多白送一帧（1/fps，30fps 下 33ms）的延迟，纯属倒贴。
     * 实测对照：Moonlight+Sunshine 同链路只有 5ms 网络 + 6ms 解码，
     * 我们没有理由比它慢一个帧间隔。
     *
     * 空闲检测保留但不再靠睡眠体现：发完后若发现比采集节奏提前完成了，
     * 就把剩余时间通过 [onIdle] 告知上层（可拿去补关键帧），发送本身不等。
     */
    private fun pacedSend(packets: List<DatagramPacket>) {
        for (p in packets) {
            runCatching { socket.send(p) }
        }
        sentFrames++
        if (frameIntervalMs > 0) {
            val now = System.currentTimeMillis()
            val idleMs = nextFrameDueMs - now
            if (idleMs > 0) runCatching { onIdle?.invoke(idleMs) }
            nextFrameDueMs = maxOf(now, nextFrameDueMs) + frameIntervalMs
        }
    }

    /**
     * 组包（原 sendFrame 的 1~3 步，从编码线程迁到这里）：
     * 短帧头 + 切数据分片 + 分 FEC 块 + RS 编码 + RTP/NV 头填充。
     */
    private fun buildPackets(f: RawFrame): List<DatagramPacket> {
        val ts = GameStream.rtpTimestamp(f.ptsUs)
        val annexB = f.annexB

        // 1) 组装 [短帧头][NAL...]；超过 4 块容量的帧截断并告警
        var stream = ByteArray(annexB.size + GameStream.SHORT_FRAME_HEADER)
        System.arraycopy(annexB, 0, stream, GameStream.SHORT_FRAME_HEADER, annexB.size)
        var dataShards =
            (stream.size + GameStream.PAYLOAD_BLOCK_SIZE - 1) / GameStream.PAYLOAD_BLOCK_SIZE
        val maxTotal = GameStream.maxTotalShards(fecPercentage)
        if (dataShards > maxTotal) {
            Log.w(
                TAG,
                "帧过大 (${stream.size}B > ${maxTotal * GameStream.PAYLOAD_BLOCK_SIZE}B)，截断",
            )
            stream = stream.copyOf(maxTotal * GameStream.PAYLOAD_BLOCK_SIZE)
            dataShards = maxTotal
        }
        val lastLen = stream.size - (dataShards - 1) * GameStream.PAYLOAD_BLOCK_SIZE
        System.arraycopy(
            GameStream.shortFrameHeader(f.frameType, lastLen, f.latencyDeciMs), 0, stream, 0,
            GameStream.SHORT_FRAME_HEADER,
        )

        // 2) 切数据分片（不足的补零到定长，接收端按 lastPayloadLen 截尾）
        val shards = ArrayList<ByteArray>(dataShards)
        var off = 0
        for (i in 0 until dataShards) {
            val s = ByteArray(GameStream.PAYLOAD_BLOCK_SIZE)
            val n = minOf(GameStream.PAYLOAD_BLOCK_SIZE, stream.size - off)
            if (n > 0) System.arraycopy(stream, off, s, 0, n)
            shards += s
            off += n
        }

        // 3) 分 FEC 块 + RS 编码
        val blocks = GameStream.fecBlocksNeeded(dataShards, fecPercentage)
        val perBlock = GameStream.splitDataShards(dataShards, blocks)

        val packets = ArrayList<DatagramPacket>()
        var shardBase = 0
        var sofSent = false
        for (b in 0 until blocks) {
            val k = perBlock[b]
            if (k <= 0) continue
            val parity = GameStream.parityShards(k, fecPercentage)
            val blockData = shards.subList(shardBase, shardBase + k).toList()
            val parityData = if (parity > 0) ReedSolomon.encode(blockData, parity) else emptyList()
            val mfb = GameStream.packMultiFecBlocks(b, blocks)
            val lastBlock = b == blocks - 1

            // 数据分片：块内序号 0..k-1，flags=PIC_DATA
            for (i in 0 until k) {
                var flags = GameStream.FLAG_PIC_DATA
                if (!sofSent) {
                    flags = flags or GameStream.FLAG_SOF
                    sofSent = true
                }
                // EOF 对齐 Sunshine：只标在**最后一个数据分片**上，parity 分片不标。
                // 旧实现把 EOF 放在最后一个 parity 分片上，一旦该 parity 包丢失，
                // 标准客户端（Moonlight）就永远等不到帧结束标记。
                if (lastBlock && i == k - 1) flags = flags or GameStream.FLAG_EOF
                val fecInfo = GameStream.packFecInfo(i, k, fecPercentage)
                packets += packet(ts, frameIndex, flags, 0, mfb, fecInfo, blockData[i])
            }
            // 校验分片：块内序号 k..k+m-1，flags=0（不含图像数据，接收端靠序号区间区分）
            for (i in 0 until parity) {
                packets += packet(ts, frameIndex, 0, 0, mfb, GameStream.packFecInfo(k + i, k, fecPercentage), parityData[i])
            }
            shardBase += k
        }
        frameIndex++
        return packets
    }

    private fun packet(
        ts: Int,
        frameIndex: Int,
        flags: Int,
        extraFlags: Int,
        mfb: Int,
        fecInfo: Int,
        payload: ByteArray,
    ): DatagramPacket {
        val pkt = GameStream.buildPacket(
            seq++, ts, ssrc, frameIndex, flags, extraFlags, mfb, fecInfo, payload,
        )
        return DatagramPacket(pkt, pkt.size, addr, port)
    }

    /** 丢帧限流上报（每秒最多一条），便于在 App 日志里观察发送端是否跟不上 */
    private fun noteDrops() {
        val now = System.currentTimeMillis()
        if (now - lastDropLog < 1000) return
        val d = droppedFrames
        val s = sentFrames
        if (d == 0) return
        lastDropLog = now
        val idr = if (droppedIdrFrames > 0) "，其中 IDR 丢失 $droppedIdrFrames 次（画面会糊）" else ""
        onLog("[图传] 丢帧保延迟：已发 $s 帧 / 丢弃 $d 帧$idr")
    }

    fun close() {
        stopped = true
        runCatching { worker.interrupt() }
        runCatching { worker.join(500) }
        runCatching { socket.close() }
    }

    companion object {
        private const val TAG = "GameStreamSender"
    }
}
