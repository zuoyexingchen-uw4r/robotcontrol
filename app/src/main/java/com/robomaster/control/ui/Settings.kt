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
        get() = sp.getInt("video_port", 47000)
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
        get() = sp.getBoolean("adaptive_kf", true)
        set(v) = sp.edit().putBoolean("adaptive_kf", v).apply()

    // ─────────── 串口设备识别（控制台） ───────────

    /** 串口 VID（默认 0x1A86 = WCH）。0 表示不按 VID 过滤 */
    var serialVid: Int
        get() = sp.getInt("serial_vid", 0x1A86)
        set(v) = sp.edit().putInt("serial_vid", v.coerceIn(0, 0xFFFF)).apply()

    /** 串口 PID（默认 0x55D3 = CH343 CDC-ACM）。0 表示不按 PID 过滤 */
    var serialPid: Int
        get() = sp.getInt("serial_pid", 0x55D3)
        set(v) = sp.edit().putInt("serial_pid", v.coerceIn(0, 0xFFFF)).apply()

    /** 强制接管串口：忽略 VID/PID，用通用 CDC 驱动打开首个设备 */
    var serialForceTakeover: Boolean
        get() = sp.getBoolean("serial_force", false)
        set(v) = sp.edit().putBoolean("serial_force", v).apply()

    /** 预览旋转角（0/90/180/270）。相机 buffer 方向随机型不同，界面上可一键循环 */
    var previewRotation: Int
        get() = sp.getInt("preview_rotation", 0)
        set(v) = sp.edit().putInt("preview_rotation", ((v % 360) + 360) % 360).apply()

    /** 画面预览开关（第二路码流小窗） */
    var previewOn: Boolean
        get() = sp.getBoolean("preview_on", true)
        set(v) = sp.edit().putBoolean("preview_on", v).apply()

    /** 网络协议层模式是否已由用户显式选定（否则界面显示"未选择"） */
    var netModeChosen: Boolean
        get() = sp.getBoolean("net_mode_chosen", false)
        set(v) = sp.edit().putBoolean("net_mode_chosen", v).apply()

    // ─────────── 非图传流（v0.7） ───────────

    /**
     * 点云攒包槽数：1 / 10 / 20。攒得越多包越少、单包越大；
     * 丢一个包一次性丢 n 个槽，网络差时要调小（或让自适应切 TCP）。
     */
    var netBatch: Int
        get() = sp.getInt("net_batch", 10).let { if (it == 1 || it == 10 || it == 20) it else 10 }
        set(v) = sp.edit().putInt("net_batch", if (v == 1 || v == 10 || v == 20) v else 10).apply()

    /** 控制帧重放次数：0 / 1 / 2（UDP 下生效，TCP 不重放） */
    var netReplay: Int
        get() = sp.getInt("net_replay", 1).coerceIn(0, 2)
        set(v) = sp.edit().putInt("net_replay", v.coerceIn(0, 2)).apply()

    /** 承载模式：0=自适应 1=强制 UDP 2=强制 TCP */
    var netMode: Int
        get() = sp.getInt("net_mode", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("net_mode", v.coerceIn(0, 2)).apply()

    /** 非图传流 UDP 端口（默认 48000，与上位机约定，不随界面变） */
    var netUdpPort: Int
        get() = sp.getInt("net_udp_port", 48000)
        set(v) = sp.edit().putInt("net_udp_port", v).apply()

    /** 非图传流 TCP 备份端口（默认 49000） */
    var netTcpPort: Int
        get() = sp.getInt("net_tcp_port", 49000)
        set(v) = sp.edit().putInt("net_tcp_port", v).apply()

    /** 激光桥波特率（雷达 230400，CH343 ≥ 此值） */
    var laserBaud: Int
        get() = sp.getInt("laser_baud", 230400)
        set(v) = sp.edit().putInt("laser_baud", v).apply()

    /** 机器人 ID（槽位，默认 R1 红方射击） */
    var robotId: String
        get() = sp.getString("robot_id", "red_r1") ?: "red_r1"
        set(v) = sp.edit().putString("robot_id", v).apply()

    /** 机器人 IP（随 robotId 联动，切换后本机静态 IP） */
    var robotIp: String
        get() = sp.getString("robot_ip", "192.168.1.3") ?: "192.168.1.3"
        set(v) = sp.edit().putString("robot_ip", v).apply()

    /**
     * 端口自愈（v0.7.1）：
     * 1. 旧默认 46000 → 47000；
     * 2. 视频/控制必须同号——曾经 UI 只改视频不改控制，出现"视频发 48000、控制连 47000"；
     * 3. 视频端口撞上非图传端口（48000/49000）时退回 47000，否则上位机同一端口没法开两个监听。
     */
    private fun migrateLegacyPort() {
        val netUdp = sp.getInt("net_udp_port", 48000)
        val netTcp = sp.getInt("net_tcp_port", 49000)
        val video0 = sp.getInt("video_port", 47000)
        val control0 = sp.getInt("control_port", 47000)

        val video = if (video0 == 46000 || video0 == netUdp || video0 == netTcp) 47000 else video0
        val changed = video != video0 || control0 != video || !sp.getBoolean("migrated_port_v08", false)
        if (!changed) return

        sp.edit()
            .putInt("video_port", video)
            .putInt("control_port", video)
            .putBoolean("migrated_port_v08", true)
            .apply()
    }

    companion object {
        @Volatile private var inst: Settings? = null

        fun get(context: Context): Settings =
            inst ?: synchronized(this) {
                inst ?: Settings(context).also {
                    it.migrateLegacyPort()
                    inst = it
                }
            }
    }
}
