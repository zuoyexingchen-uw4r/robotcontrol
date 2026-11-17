package com.robomaster.control.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * 图传/桥接设置记忆（SharedPreferences）。
 *
 * 记忆内容：电脑 IP、分辨率（宽×高）、帧率、视频端口、控制端口、波特率、
 * FEC 比例、码率、编码器选择。下次打开界面自动回填，第一屏的「图传一键启停」
 * 也直接读这份配置，不用重复选。
 *
 * 分辨率/帧率存**值**而不是 spinner 位置——不同手机的相机能力列表可能不同，
 * 按值匹配索引更稳（匹配不到就回退到第一项）。
 */
class Settings private constructor(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("bridge_prefs", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString("host", "") ?: ""
        set(v) = sp.edit().putString("host", v).apply()

    var resW: Int
        get() = sp.getInt("res_w", 0)
        set(v) = sp.edit().putInt("res_w", v).apply()

    var resH: Int
        get() = sp.getInt("res_h", 0)
        set(v) = sp.edit().putInt("res_h", v).apply()

    var fps: Int
        get() = sp.getInt("fps", 30)
        set(v) = sp.edit().putInt("fps", v).apply()

    var videoPort: Int
        get() = sp.getInt("video_port", 46000)
        set(v) = sp.edit().putInt("video_port", v).apply()

    var controlPort: Int
        get() = sp.getInt("control_port", 47000)
        set(v) = sp.edit().putInt("control_port", v).apply()

    var serialPort: Int
        get() = sp.getInt("serial_port", 8765)
        set(v) = sp.edit().putInt("serial_port", v).apply()

    /** 第二数据流通道端口 */
    var dataPort: Int
        get() = sp.getInt("data_port", 8766)
        set(v) = sp.edit().putInt("data_port", v).apply()

    var baud: Int
        get() = sp.getInt("baud", 115200)
        set(v) = sp.edit().putInt("baud", v).apply()

    var fec: Int
        get() = sp.getInt("fec", 30)
        set(v) = sp.edit().putInt("fec", v).apply()

    /** 码率 kbps，0 = 自动（宽×高×fps/10） */
    var bitrateKbps: Int
        get() = sp.getInt("bitrate_kbps", 0)
        set(v) = sp.edit().putInt("bitrate_kbps", v).apply()

    /** 编码器 spinner 位置：0=自动(高通优先) 1=强制硬件 2=软件回退 */
    var encoderMode: Int
        get() = sp.getInt("encoder_mode", 0)
        set(v) = sp.edit().putInt("encoder_mode", v).apply()

    /** 编码格式 spinner 位置：0=H.264 1=H.265 */
    var codecIndex: Int
        get() = sp.getInt("codec_index", 0)
        set(v) = sp.edit().putInt("codec_index", v).apply()

    /**
     * 参数集重复策略 spinner 位置：
     * 0=自动（HEVC 修复/H.264 原样） 1=始终重复 2=从不重复
     */
    var repeatCsd: Int
        get() = sp.getInt("repeat_csd", 0)
        set(v) = sp.edit().putInt("repeat_csd", v).apply()

    var adaptiveKf: Boolean
        get() = sp.getBoolean("adaptive_kf", false)
        set(v) = sp.edit().putBoolean("adaptive_kf", v).apply()

    /** 激光桥波特率（雷达 230400，CH343 ≥ 此值） */
    var laserBaud: Int
        get() = sp.getInt("laser_baud", 230400)
        set(v) = sp.edit().putInt("laser_baud", v).apply()

    /** 激光桥传输模式：0=AUTO 1=TCP 2=UDP */
    var laserMode: Int
        get() = sp.getInt("laser_mode", 0)
        set(v) = sp.edit().putInt("laser_mode", v.coerceIn(0, 2)).apply()

    companion object {
        @Volatile private var inst: Settings? = null

        fun get(context: Context): Settings =
            inst ?: synchronized(this) { inst ?: Settings(context).also { inst = it } }
    }
}
