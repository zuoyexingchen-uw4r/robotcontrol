package com.robomaster.control.vision

import com.robomaster.control.net.NetCore
import com.robomaster.control.net.NetProto
import com.robomaster.control.ui.VisionSettings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 视觉结果 + 设置同步（走非图传流，不再是 31001/31002 两条 UDP 广播）。
 *
 * 迁移说明（v0.7）：
 * - 旧版自己 bind 31002、向 255.255.255.255:31001 广播，和第 8767/8768 那套互不相干；
 * - 现在统一走 `NetCore` 的单条非图传流，JSON 作为**长帧**发送（自动分片，单包 ≤1200B）；
 * - JSON 结构保持原样，上位机 `pc_hud` / `spaa` 的解析代码不用改字段名；
 * - 设置变更仍以手机 SharedPreferences 为权威源：电脑发 `set_req` → 本机写入 → 回发全量快照。
 *
 * 生命周期：本类只持有一个 `NetCore` 租约，停图传不再会把链路关掉。
 */
class VisionUdpLink(
    private val settings: VisionSettings,
    private val host: () -> String,
    private val onLog: (String) -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var lastErrorAt = 0L
    private var beat: Thread? = null

    private val jsonListener: (String) -> Unit = { json -> onJson(json) }

    fun start() {
        if (running) return
        val h = host()
        if (h.isBlank()) {
            onLog("[视觉同步] 未配置电脑 IP，暂不发送")
            return
        }
        NetCore.acquire(TAG, h, onLog)
        NetCore.addJsonListener(jsonListener)
        running = true
        beat = Thread({ beatLoop() }, "vision-beat").apply {
            isDaemon = true
            start()
        }
        onLog("[视觉同步] 已接入非图传流 → $h")
    }

    fun stop() {
        running = false
        runCatching { beat?.interrupt() }
        beat = null
        NetCore.removeJsonListener(jsonListener)
        NetCore.release(TAG)
    }

    /** 发送一帧实际处理结果；调用方应过滤节流帧，避免重复开火和虚高 FPS。 */
    fun sendVision(result: VisionPipeline.Result, width: Int, height: Int, fps: Float) {
        if (!running || width <= 0 || height <= 0) return
        NetCore.current()?.sendJson(buildVisionJson(result, width, height, fps))
    }

    private fun buildVisionJson(
        result: VisionPipeline.Result,
        width: Int,
        height: Int,
        fps: Float,
    ): String {
        val root = JSONObject()
            .put("t", NetProto.JSON_VISION)
            .put("ts", System.currentTimeMillis())
            .put("fps", finite(fps.toDouble()))
            .put("lat_ms", result.totalMs)
            .put("frame_w", width)
            .put("frame_h", height)

        val targets = JSONArray()
        result.targets.take(MAX_TARGETS).forEach { target ->
            targets.put(
                JSONObject()
                    .put("type", if (target.type == com.robomaster.control.vision.detect.TargetDetector.Target.Type.HIGHLAND) "highland" else "base")
                    .put("cx", finite(target.cx))
                    .put("cy", finite(target.cy))
                    .put("w", finite(target.pixelSize))
                    .put("h", finite(target.pixelSize))
                    .put("conf", finite(target.confidence))
            )
        }
        root.put("targets", targets)

        root.put(
            "track",
            result.trackedTarget?.let { track ->
                JSONObject()
                    .put("type", if (track.type == com.robomaster.control.vision.detect.TargetDetector.Target.Type.HIGHLAND) "highland" else "base")
                    .put("cx", finite(track.cx))
                    .put("cy", finite(track.cy))
                    .put("vx", finite(track.vx))
                    .put("vy", finite(track.vy))
                    .put("dist_mm", finite(track.distanceMm))
                    .put("yaw_deg", finite(track.yawDeg))
                    .put("pitch_deg", finite(track.pitchDeg))
                    .put("pixel_size", finite(track.pixelSize))
                    .put("orientation_deg", finite(track.orientationDeg))
                    .put("nis_gated", track.nisGated)
                    .put("track_sigma_px", finite(track.trackSigmaPx))
            } ?: JSONObject.NULL
        )

        root.put(
            "fire",
            result.fireCommand?.let { fire ->
                JSONObject().put("should", fire.shouldFire)
                    .put("yaw", finite(fire.yawDeg))
                    .put("pitch", finite(fire.pitchDeg))
                    .put("rpm", fire.frictionWheelRpm)
                    .put("reason", fire.reason)
            } ?: JSONObject.NULL
        )

        root.put(
            "proc_ms",
            JSONObject()
                .put("pre", result.preprocessMs)
                .put("det", result.detectMs)
                .put("track", (result.totalMs - result.preprocessMs - result.detectMs).coerceAtLeast(0))
                .put("total", result.totalMs)
        )
        return root.toString()
    }

    // ───────────────────────── 设置同步 ─────────────────────────

    private fun beatLoop() {
        while (running) {
            try {
                Thread.sleep(SETTINGS_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            if (running) sendSettings()
        }
    }

    private fun sendSettings() {
        if (!running) return
        val link = NetCore.current() ?: return
        val values = JSONObject()
        settings.snapshot().forEach { (key, value) -> values.put(key, value) }
        link.sendJson(
            JSONObject().put("t", NetProto.JSON_SETTINGS)
                .put("ts", System.currentTimeMillis())
                .put("s", values)
                .toString()
        )
    }

    /** 上位机下行 JSON：hello（上线宣告）/ set_req（设置变更） */
    private fun onJson(json: String) {
        if (!running) return
        try {
            val msg = JSONObject(json)
            when (msg.optString("t")) {
                NetProto.JSON_HELLO -> sendSettings()
                NetProto.JSON_SET_REQ -> {
                    val key = msg.optString("key")
                    if (key.isNotEmpty() && settings.applyRemote(key, msg.opt("value"))) {
                        sendSettings()
                    }
                }
            }
        } catch (e: Exception) {
            reportError("JSON 解析失败: ${e.message}")
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
        private const val TAG = "vision"
        private const val SETTINGS_INTERVAL_MS = 500L
        private const val ERROR_LOG_INTERVAL_MS = 5000L
        private const val MAX_TARGETS = 32
    }
}
