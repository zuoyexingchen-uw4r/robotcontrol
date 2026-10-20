package com.robomaster.control.vision

import android.graphics.ImageFormat
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * 视觉分析相机通道（独立的第二条 Camera2 会话）。
 *
 * **架构**：与图传码流完全分离——
 * - 图传 session（CameraStreamer）：编码器 Surface + 预览 Surface，帧率/码率
 *   按推流需求配置，**不受视觉分析影响**
 * - 视觉 session（本类）：同一 CameraDevice 上的第二个 CaptureSession，
 *   独立 ImageReader（YUV_420_888）+ 独立帧率（可低于图传，如 10-15fps），
 *   专供 OpenCV / 视觉管线分析
 *
 * Camera2 支持同一 CameraDevice 创建多个 CaptureSession，各 session 独立
 * setRepeatingRequest；传感器输出帧按各 session 的请求节奏分发，互不阻塞。
 *
 * **帧消费**：onFrame 回调给出原始 [Image]（YUV_420_888），调用方自行处理：
 * - 纯 Kotlin 管线：用 [yuv420ToRgb] 转 RGB 喂 VisionPipeline
 * - OpenCV 管线：直接构造 org.opencv.core.Mat（YUV 布局）分析
 *
 * **生命周期**：必须在 CameraStreamer.stop() **之前**调用 [stop]
 * （streamer.stop 会 close CameraDevice）。
 */
class VisionCamera(
    private val device: CameraDevice,
    private val width: Int,
    private val height: Int,
    private val fps: Int = 15,
    private val handler: Handler,
    private val onFrame: (Image) -> Unit,
    private val onError: (String) -> Unit = {}
) {
    private var reader: ImageReader? = null
    private var session: android.hardware.camera2.CameraCaptureSession? = null

    /** 是否运行中 */
    @Volatile
    var running = false
        private set

    /** 启动第二会话（建 ImageReader + CaptureSession +  repeating request） */
    fun start() {
        if (running) return
        running = true
        try {
            reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
            reader?.setOnImageAvailableListener({ r ->
                // 视觉帧：拿最新帧，处理慢时自动丢旧（分析不需要每帧）
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    onFrame(image)
                } catch (e: Exception) {
                    Log.w(TAG, "帧处理异常: ${e.message}")
                } finally {
                    image.close()
                }
            }, handler)

            @Suppress("DEPRECATION")
            device.createCaptureSession(
                listOf<Surface>(reader!!.surface),
                object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: android.hardware.camera2.CameraCaptureSession) {
                        session = s
                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(reader!!.surface)
                            // 独立帧率：视觉分析可跑低于图传的帧率，减轻 CPU 负担
                            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(fps, fps))
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        }.build()
                        s.setRepeatingRequest(req, null, handler)
                        Log.i(TAG, "视觉第二会话就绪 ${width}x${height}@${fps}")
                    }

                    override fun onConfigureFailed(s: android.hardware.camera2.CameraCaptureSession) {
                        running = false
                        onError("视觉会话配置失败（设备可能不支持并发会话）")
                    }
                },
                handler
            )
        } catch (e: Exception) {
            running = false
            onError("视觉会话创建失败: ${e.message}")
        }
    }

    /** 停止第二会话（必须在 CameraStreamer.stop() 之前调用） */
    fun stop() {
        running = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { reader?.close() }
        reader = null
    }

    companion object {
        private const val TAG = "VisionCamera"

        /**
         * YUV_420_888 → RGB（BT.601 full range）。
         *
         * 兼容各种 pixelStride/rowStride 布局（不同设备 YUV 平面排布不同）。
         * OpenCV 接入时也可直接用 YUV 平面构造 Mat，省去转换。
         */
        fun yuv420ToRgb(image: Image, w: Int, h: Int): ByteArray {
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]
            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixStride = vPlane.pixelStride

            val rgb = ByteArray(w * h * 3)
            for (y in 0 until h) {
                val uvY = y / 2
                for (x in 0 until w) {
                    val yIndex = y * yRowStride + x * yPixStride
                    val yVal = yBuf.get(yIndex).toInt() and 0xFF
                    val uvX = x / 2
                    val uVal = uBuf.get(uvY * uRowStride + uvX * uPixStride).toInt() and 0xFF
                    val vVal = vBuf.get(uvY * vRowStride + uvX * vPixStride).toInt() and 0xFF

                    // BT.601 full range
                    val c = yVal - 16
                    val d = uVal - 128
                    val e = vVal - 128
                    val r = (298 * c + 409 * e + 128) shr 8
                    val g = (298 * c - 100 * d - 208 * e + 128) shr 8
                    val b = (298 * c + 516 * d + 128) shr 8

                    val idx = (y * w + x) * 3
                    rgb[idx] = r.coerceIn(0, 255).toByte()
                    rgb[idx + 1] = g.coerceIn(0, 255).toByte()
                    rgb[idx + 2] = b.coerceIn(0, 255).toByte()
                }
            }
            return rgb
        }
    }
}
