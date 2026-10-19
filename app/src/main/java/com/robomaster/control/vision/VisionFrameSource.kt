package com.robomaster.control.vision

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/**
 * 视觉帧源：Camera2 ImageReader（YUV_420_888）→ RGB → VisionPipeline。
 *
 * 接入方式：把 [surface] 传给 CameraStreamer.Config.visionSurface，
 * 相机会话同时向编码器和本 Surface 输出帧。
 *
 * 线程模型：
 * - ImageReader 回调在独立 HandlerThread（vision-reader）
 * - 回调里做 YUV→RGB 转换 + pipeline.process（CPU 密集 30-50ms）
 * - maxImages=2：处理慢时 ImageReader 自动丢最旧帧（视觉不需要每帧）
 * - 结果经 onResult 回调（仍在 vision-reader 线程，调用方需切主线程更新 UI）
 *
 * YUV→RGB：BT.601 full range，逐像素处理，兼容各种 pixelStride/rowStride 布局。
 */
class VisionFrameSource(
    private val width: Int,
    private val height: Int,
    private val pipeline: VisionPipeline,
    private val onResult: (VisionPipeline.Result) -> Unit
) {
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** 传给 CameraStreamer.Config.visionSurface */
    val surface: Surface?
        get() = reader?.surface

    /** 启动帧源（创建 ImageReader + 监听器） */
    fun start() {
        if (reader != null) return
        thread = HandlerThread("vision-reader").also {
            it.start()
            handler = Handler(it.looper)
        }
        reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val rgb = yuv420ToRgb(image, width, height)
                val result = pipeline.process(rgb, width, height)
                onResult(result)
            } catch (e: Exception) {
                // 视觉处理失败不应影响图传，吞掉异常
            } finally {
                image.close()
            }
        }, handler)
    }

    /** 停止帧源（释放 ImageReader + 线程） */
    fun stop() {
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    companion object {
        /**
         * YUV_420_888 → RGB（BT.601 full range）。
         *
         * 兼容性处理：
         * - rowStride/pixelStride 逐 plane 读取（不同设备布局不同）
         * - U/V 按 2×2 下采样（色度子采样）
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
