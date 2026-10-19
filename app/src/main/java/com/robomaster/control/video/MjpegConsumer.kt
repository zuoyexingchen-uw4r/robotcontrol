package com.robomaster.control.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MJPEG 图传拉流器。
 *
 * 背景：ESP32-S3 端跑 OpenCV 后用 HTTP multipart/x-mixed-replace 吐 MJPEG
 * （ESP32 系列编 H.264 吃力，MJPEG 是最现实的图传格式）。
 * 手机端不需要 ffmpeg，直接按 multipart 边界切 JPEG → BitmapFactory 解码即可。
 *
 * 三种图传方案对比（README 里有完整表）：
 *   ① UVC：摄像头直插手机 OTG —— 用 saki4510t/UVCCamera，延迟最低(~50ms)，但只支持 USB 摄像头；
 *   ② MJPEG over HTTP(本实现)：ESP32-S3 做服务器，手机拉流，延迟 100~200ms，最稳；
 *   ③ RTSP/RTMP：需要 ESP32-S3 编码 H.264 或外挂编码芯片，延迟与复杂度都高，不建议第一版。
 */
class MjpegConsumer(
    private val url: String,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null

    /** 帧回调（已在主线程外，切回主线程再贴 UI） */
    var onFrame: ((Bitmap) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    /** 实时帧率，用于调试图传质量 */
    var onFps: ((Float) -> Unit)? = null

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            var frames = 0
            var lastFpsTime = System.currentTimeMillis()
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 3000
                conn.readTimeout = 5000
                conn.requestProperties // keep-alive default
                conn.connect()
                val ins = BufferedInputStream(conn.getInputStream(), 64 * 1024)

                // 逐字节找 JPEG SOI(0xFFD8)/EOI(0xFFD9)，比解析 multipart 边界更鲁棒
                val buf = ArrayList<Byte>(64 * 1024)
                var prev = -1
                var inFrame = false
                while (isActive) {
                    val b = ins.read()
                    if (b < 0) break
                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) { inFrame = true; buf.clear(); buf.add(0xFF.toByte()); buf.add(0xD8.toByte()) }
                    } else {
                        buf.add(b.toByte())
                        if (prev == 0xFF && b == 0xD9) {
                            // 一帧结束
                            val bytes = ByteArray(buf.size) { buf[it].toByte() }
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) {
                                onFrame?.invoke(bmp)
                                frames++
                                val now = System.currentTimeMillis()
                                if (now - lastFpsTime >= 1000) {
                                    onFps?.invoke(frames * 1000f / (now - lastFpsTime))
                                    frames = 0; lastFpsTime = now
                                }
                            }
                            inFrame = false
                        }
                    }
                    prev = b
                }
                if (isActive) onError?.invoke("图传流结束")
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "mjpeg error")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun awaitStop() = withContext(Dispatchers.IO) { stop() }
}
