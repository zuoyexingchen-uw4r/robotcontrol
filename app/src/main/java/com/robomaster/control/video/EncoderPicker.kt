package com.robomaster.control.video

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/** 编码器选择策略 */
enum class EncoderMode {
    /** 自动：优先高通硬编(c2.qti.*)，退化任意硬编，再退化软件 */
    AUTO_QUALCOMM,

    /** 强制硬件编码（任意厂商） */
    FORCE_HW,

    /** 强制软件编码（回退方案，最兼容但最耗 CPU） */
    FORCE_SW,
}

/**
 * H.264/H.265 编码器挑选。
 * 适配高通 GPU：Snapdragon 6 Gen1 / 8 Gen 系列的硬编码器在 MediaCodecList 里
 * 命名为 c2.qti.avc.encoder / OMX.qcom.video.encoder.avc（走 Adreno 硬件通路）。
 * 用名称特征识别，避免把具体型号写死——6Gen1 与 8Gen 走同一套 C2 编码器接口。
 *
 * 注：MediaCodecInfo 无公开 isSoftware，软件编示器按名称前缀判定
 * （OMX.google.* / c2.android.*）。
 */
object EncoderPicker {

    data class Pick(val name: String, val hardware: Boolean, val qualcomm: Boolean)

    private fun isSw(ci: MediaCodecInfo): Boolean =
        ci.name.startsWith("OMX.google.", true) ||
            ci.name.startsWith("c2.android.", true) ||
            ci.name.startsWith("google.", true)

    private fun isQcom(ci: MediaCodecInfo): Boolean =
        !isSw(ci) && (ci.name.contains("qti", true) || ci.name.contains("qcom", true))

    fun pick(mime: String = "video/avc", mode: EncoderMode = EncoderMode.AUTO_QUALCOMM): Pick? {
        val encoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) }
        }
        if (encoders.isEmpty()) return null

        val chosen: MediaCodecInfo = when (mode) {
            EncoderMode.AUTO_QUALCOMM ->
                encoders.firstOrNull { isQcom(it) }
                    ?: encoders.firstOrNull { !isSw(it) }
                    ?: encoders.firstOrNull { isSw(it) }

            EncoderMode.FORCE_HW ->
                encoders.firstOrNull { isQcom(it) }
                    ?: encoders.firstOrNull { !isSw(it) }

            EncoderMode.FORCE_SW ->
                encoders.firstOrNull { isSw(it) }
                    ?: encoders.firstOrNull { !isSw(it) }
        } ?: return null

        return Pick(chosen.name, !isSw(chosen), isQcom(chosen))
    }
}
