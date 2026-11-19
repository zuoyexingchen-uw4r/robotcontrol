package com.robomaster.control.net

import android.content.Context
import com.robomaster.control.laser.RobotSlots
import com.robomaster.control.ui.Settings
import org.json.JSONObject

/** 扫码配对信息：上位机地址、本机槽位 */
data class PairInfo(
    val host: String,
    val ip: String,
    val port: Int = NetProto.VIDEO_PORT,
    val slot: String = "R1",
) {
    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("host", host)
        .put("ip", ip)
        .put("port", port)
        .put("slot", slot)
        .toString()

    /** 槽位对应的机器人 ID / 本机静态 IP（用于切 IP 与日志标识） */
    fun robot(): RobotSlots.Slot? = RobotSlots.SLOTS.firstOrNull { it.slot == slot }
}

/**
 * 扫码配对。
 *
 * 二维码里只放四样东西：HOST（上位机名字）、IP、PORT（视频端口基址，其余按 47000/48000/49000
 * 固定推导）、槽位（R1/R2/B1/B2）。**扫描入口暂时不接**，先把编解码与落盘 API 留好：
 * 以后无论用 CameraX、ML Kit 还是系统扫码，扫到字符串后直接调 [parse] + [apply] 即可。
 */
object Pairing {

    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

    fun encode(info: PairInfo): String = info.toJson()

    /**
     * 解析扫码结果。容错：
     * - 缺 `host` 时用 `ip` 顶替（老二维码只写 IP 的情况）；
     * - `port` 缺省 47000；
     * - `slot` 缺省 R1，大小写不敏感。
     */
    fun parse(text: String): PairInfo? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null

        val ip = obj.optString("ip").ifBlank { obj.optString("host") }
        if (!isIpv4(ip)) return null

        val port = obj.optInt("port", NetProto.VIDEO_PORT)
            .let { if (it in 1024..65535) it else NetProto.VIDEO_PORT }

        val slotText = obj.optString("slot", "R1").uppercase()
        val slot = RobotSlots.SLOTS.firstOrNull { it.slot == slotText }?.slot ?: "R1"

        val host = obj.optString("host").ifBlank { ip }
        return PairInfo(host = host, ip = ip, port = port, slot = slot)
    }

    /** 写入配置（视频 IP/端口 + 机器人槽位），返回是否落盘成功 */
    fun apply(context: Context, info: PairInfo): Boolean {
        if (!isIpv4(info.ip)) return false
        val s = Settings.get(context)
        s.host = info.ip
        s.videoPort = info.port
        val robot = info.robot()
        if (robot != null) {
            s.robotId = robot.robotId
            s.robotIp = robot.ip
        }
        return true
    }

    private fun isIpv4(ip: String): Boolean {
        val m = IPV4.matchEntire(ip) ?: return false
        return (1..4).all { m.groupValues[it].toIntOrNull()?.let { v -> v in 0..255 } == true }
    }
}
