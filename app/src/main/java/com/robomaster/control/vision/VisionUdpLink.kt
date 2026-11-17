package com.robomaster.control.vision

import com.robomaster.control.ui.VisionSettings
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * 手机与电脑 HUD 的 UDP 链路：31001 广播视觉/设置，31002 接收设置请求。
 *
 * 同一个已绑定 31002 的 socket 同时负责收发；接收线程周期发送设置快照，
 * 视觉处理线程只发送真正重新计算过的结果。所有远程输入均做 JSON、键和类型校验。
 */
class VisionUdpLink(
    private val settings: VisionSettings,
    private val onLog: (String) -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var lastErrorAt = 0L
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val sendLock = Any()

    fun start() {
        if (running) return
        try {
            val s = DatagramSocket(null)
            s.reuseAddress = true
            s.broadcast = true
            s.bind(InetSocketAddress(CMD_PORT))
            s.soTimeout = 500
            socket = s
            running = true
            thread = Thread({ receiveLoop(s) }, "vision-udp").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            running = false
            runCatching { socket?.close() }
            socket = null
            reportError("UDP 启动失败: ${e.message}")
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        if (thread !== Thread.currentThread()) runCatching { thread?.join(1000) }
        thread = null
    }

    /** 发送一帧实际处理结果；调用方应过滤节流帧，避免重复开火和虚高 FPS。 */
    fun sendVision(result: VisionPipeline.Result, width: Int, height: Int, fps: Float) {
        if (!running || width <= 0 || height <= 0) return
        try {
            val root = JSONObject()
                .put("t", "vision")
                .put("ts", System.currentTimeMillis())
                .put("fps", finite(fps.toDouble()))
                .put("lat_ms", result.totalMs)
                .put("frame_w", width)
                .put("frame_h", height)
            val targets = JSONArray()
            result.targets.take(MAX_TARGETS).forEach { target ->
                targets.put(JSONObject()
                    .put("type", if (target.type == com.robomaster.control.vision.detect.TargetDetector.Target.Type.HIGHLAND) "highland" else "base")
                    .put("cx", finite(target.cx)).put("cy", finite(target.cy))
                    .put("w", finite(target.pixelSize)).put("h", finite(target.pixelSize))
                    .put("conf", finite(target.confidence)))
            }
            root.put("targets", targets)
            root.put("track", result.trackedTarget?.let { track ->
                JSONObject()
                    .put("type", if (track.type == com.robomaster.control.vision.detect.TargetDetector.Target.Type.HIGHLAND) "highland" else "base")
                    .put("cx", finite(track.cx)).put("cy", finite(track.cy))
                    .put("vx", finite(track.vx)).put("vy", finite(track.vy))
                    .put("dist_mm", finite(track.distanceMm))
                    .put("yaw_deg", finite(track.yawDeg)).put("pitch_deg", finite(track.pitchDeg))
                    .put("pixel_size", finite(track.pixelSize))
                    .put("orientation_deg", finite(track.orientationDeg))
                    .put("nis_gated", track.nisGated)
                    .put("track_sigma_px", finite(track.trackSigmaPx))
            } ?: JSONObject.NULL)
            root.put("fire", result.fireCommand?.let { fire ->
                JSONObject().put("should", fire.shouldFire)
                    .put("yaw", finite(fire.yawDeg)).put("pitch", finite(fire.pitchDeg))
                    .put("rpm", fire.frictionWheelRpm).put("reason", fire.reason)
            } ?: JSONObject.NULL)
            root.put("proc_ms", JSONObject()
                .put("pre", result.preprocessMs).put("det", result.detectMs)
                .put("track", (result.totalMs - result.preprocessMs - result.detectMs).coerceAtLeast(0))
                .put("total", result.totalMs))
            broadcast(root.toString())
        } catch (e: Exception) {
            reportError("视觉结果编码失败: ${e.message}")
        }
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buffer = ByteArray(MAX_DATAGRAM)
        val packet = DatagramPacket(buffer, buffer.size)
        var nextSnapshotAt = 0L
        while (running && socket === s) {
            val now = System.currentTimeMillis()
            if (now >= nextSnapshotAt) {
                sendSettings()
                nextSnapshotAt = now + SETTINGS_INTERVAL_MS
            }
            packet.length = buffer.size
            try {
                s.receive(packet)
                val msg = JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
                when (msg.optString("t")) {
                    "hello" -> sendSettings()
                    "set_req" -> {
                        val key = msg.optString("key")
                        if (key.isNotEmpty() && settings.applyRemote(key, msg.opt("value"))) {
                            sendSettings()
                        }
                    }
                }
            } catch (_: SocketTimeoutException) {
                // 到点发送下一次设置心跳。
            } catch (e: Exception) {
                if (running) reportError("UDP 收包失败: ${e.message}")
            }
        }
    }

    private fun sendSettings() {
        val values = JSONObject()
        settings.snapshot().forEach { (key, value) -> values.put(key, value) }
        broadcast(JSONObject().put("t", "settings")
            .put("ts", System.currentTimeMillis()).put("s", values).toString())
    }

    private fun broadcast(json: String) {
        val s = socket ?: return
        if (!running) return
        try {
            val bytes = json.toByteArray(Charsets.UTF_8)
            synchronized(sendLock) {
                s.send(DatagramPacket(bytes, bytes.size, BROADCAST_ADDRESS, VISION_PORT))
            }
        } catch (e: Exception) {
            if (running) reportError("UDP 发送失败: ${e.message}")
        }
    }

    private fun reportError(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastErrorAt >= ERROR_LOG_INTERVAL_MS) {
            lastErrorAt = now
            onLog(message)
        }
    }

    private fun finite(value: Double): Double = if (value.isFinite()) value else 0.0

    companion object {
        const val VISION_PORT = 31001
        const val CMD_PORT = 31002
        private const val SETTINGS_INTERVAL_MS = 500L
        private const val ERROR_LOG_INTERVAL_MS = 5000L
        private const val MAX_DATAGRAM = 8192
        private const val MAX_TARGETS = 32
        private val BROADCAST_ADDRESS: InetAddress = InetAddress.getByName("255.255.255.255")
    }
}
