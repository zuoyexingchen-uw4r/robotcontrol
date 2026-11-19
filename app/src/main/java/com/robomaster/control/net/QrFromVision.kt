package com.robomaster.control.net

import android.media.Image
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.ceil

/**
 * 从**视觉码流**里解二维码（不新开摄像头）。
 *
 * 为什么这样做：图传启动后摄像头被 Camera2 会话占用，任何"另开一个扫码页"的做法
 * 都会抢不到相机（CameraX/ML Kit 同样抢不到）。而视觉第二会话本来就一直在出
 * YUV 帧，直接把 Y 平面（亮度）喂给 ZXing 即可——相机只有一路，扫码与视觉共用。
 *
 * 实现要点：
 * - 只取 `planes[0]`（Y），不做 RGB 转换：ZXing 只需要灰度；
 * - 按 [targetMax] 降采样（默认最长边 720），QR 版本 1~10 在 720p 下仍可解，
 *   CPU 从 ~40ms 降到 ~10ms，够 2~3 次/秒；
 * - 只解 QR（[QRCodeReader]），不用 MultiFormatReader：少试 10 几种格式；
 * - `rowStride`/`pixelStride` 全按设备真实布局读，避免露出斜条纹。
 *
 * 调用方负责节流（例如每 6 帧一次），本类不做计时。
 */
object QrFromVision {

    private const val TAG = "QrFromVision"

    /** 同一线程内复用 reader（QRCodeReader 非线程安全，视觉会话只有一条线程） */
    private val reader = QRCodeReader()

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )

    /**
     * 解一帧。
     *
     * @param image 视觉会话的 YUV_420_888 帧（[android.media.Image]）
     * @param w 帧宽
     * @param h 帧高
     * @param targetMax 降采样后的最长边
     * @return 二维码文本；没有识别到返回 null
     */
    fun decode(image: Image, w: Int, h: Int, targetMax: Int = 720): String? {
        if (w <= 0 || h <= 0) return null
        return try {
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            if (rowStride <= 0 || pixStride <= 0) return null

            val step = ceil(maxOf(w, h).toDouble() / targetMax).toInt().coerceAtLeast(1)
            val ow = w / step
            val oh = h / step
            if (ow < 32 || oh < 32) return null

            val gray = ByteArray(ow * oh)
            var idx = 0
            var y = 0
            while (y < h) {
                val rowBase = y * rowStride
                var x = 0
                while (x < w) {
                    gray[idx++] = buf.get(rowBase + x * pixStride)
                    x += step
                }
                y += step
            }

            val source = PlanarYUVLuminanceSource(gray, ow, oh, 0, 0, ow, oh, false)
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)?.text
        } catch (e: Exception) {
            // NotFoundException 是常态（大多数帧里没有二维码），不刷日志
            if (e !is com.google.zxing.NotFoundException) {
                Log.w(TAG, "解码异常: ${e.message}")
            }
            null
        }
    }
}
