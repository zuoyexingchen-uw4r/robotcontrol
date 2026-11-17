package com.robomaster.control.laser

/**
 * 机器人槽位配置表（与 scripts/switch_ip.sh、LASER_PROTOCOL.md 保持一致）。
 *
 * 槽位 → 机器人 IP，用于赛前在安卓设备上切换本机 IP。
 * robot_id 里 `r` 是 robot 的简写：蓝方射击/兑矿为 `blue_r1` / `blue_r2`。
 */
object RobotSlots {

    data class Slot(
        val slot: String,     // 槽位号
        val label: String,    // 中文名
        val robotId: String,  // 机器人 ID
        val ip: String,       // 机器人 IP
    )

    val SLOTS: List<Slot> = listOf(
        Slot("R1", "红方射击", "red_r1", "192.168.1.3"),
        Slot("R2", "红方兑矿", "red_r2", "192.168.1.4"),
        Slot("B1", "蓝方射击", "blue_r1", "192.168.1.5"),
        Slot("B2", "蓝方兑矿", "blue_r2", "192.168.1.6"),
    )

    fun byIp(ip: String): Slot? = SLOTS.firstOrNull { it.ip == ip }
    fun byId(id: String): Slot? = SLOTS.firstOrNull { it.robotId == id }
    fun indexOf(slot: Slot): Int = SLOTS.indexOf(slot)

    /**
     * 预留的「切换 IP」UART 主控帧结构（赛前设置用，正常运行不出现）。
     *
     * 复用主控上行帧（39 字节）：`fields[1] = SWITCH_IP_MAGIC`，`fields[2..5]` =
     * IP 的 4 个八位组（如 192,168,1,5）。手机 USB-host 收到后触发改 IP + 重连。
     * 因改 IP 在赛前完成，此帧不会干扰设备正常运行。
     */
    const val SWITCH_IP_MAGIC: Int = 0x5349   // "SI"（Switch IP）

    fun encodeSwitchIpFrame(seq: Int, ip: String): ByteArray {
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        require(octets.size == 4) { "IP 格式错误: $ip" }
        val fields = IntArray(16)
        fields[1] = SWITCH_IP_MAGIC
        for (i in 0 until 4) fields[2 + i] = octets[i]
        return LaserProtocol.buildMaster(LaserProtocol.MASTER_UP_TYPE, seq, fields)
    }

    /** 若该主控帧是「切 IP」命令，返回目标 IP；否则 null */
    fun parseSwitchIpFrame(frame: ByteArray): String? {
        val parsed = LaserProtocol.parseMaster(frame) ?: return null
        if (parsed.first != LaserProtocol.MASTER_UP_TYPE) return null
        val f = parsed.third
        if (f[1] != SWITCH_IP_MAGIC) return null
        val octets = IntArray(4) { f[2 + it] }
        if (octets.any { it !in 0..255 }) return null
        return octets.joinToString(".")
    }
}
