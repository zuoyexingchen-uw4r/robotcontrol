package com.robomaster.control.net

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 非图传流的进程级单例。
 *
 * 为什么需要一个中心：图传、视觉、激光桥以前各自 new 一套 socket（`TcpChannel`、
 * `VisionUdpLink`、`GameStreamSender`、`LaserBridge` 的内置 UDP），重连策略、端口常量、
 * 错误上报全是四份。现在只有一条非图传流，就必须只有**一个** socket 对和**一份**生命周期。
 *
 * 引用计数：激光桥（USB 侧）和视觉上行各持一个租约，谁最后释放谁关链路。
 * 这样停图传不会顺手关掉激光桥的链路（旧代码的 [com.robomaster.control.vision.VisionUdpLink.stop]
 * 就踩过这个坑）。
 */
object NetCore {

    private val holders = LinkedHashSet<String>()
    private val jsonListeners = CopyOnWriteArrayList<(String) -> Unit>()

    @Volatile private var link: UartLink? = null
    @Volatile private var logger: (String) -> Unit = {}

    /** 非图传流端口；由调用方从 Settings 注入，默认 48000 / 49000 */
    @Volatile private var udpPort: Int = NetProto.UDP_PORT
    @Volatile private var tcpPort: Int = NetProto.TCP_PORT

    /**
     * 设置端口。**只影响之后创建的链路**：已经跑着的链路不会重建，
     * 所以改完端口要重启 App（和手机端同一个端口，否则两边对不上）。
     */
    fun configure(udp: Int, tcp: Int) {
        if (udp in 1024..65535) udpPort = udp
        if (tcp in 1024..65535) tcpPort = tcp
    }

    /**
     * 当前 USB 串口的写入入口（由 [com.robomaster.control.laser.LaserBridge] 在启动时挂上）。
     *
     * 为什么要这个：视觉开火指令必须在**本机**写 UART，不能绕网络一圈再回来。
     * 但 USB 设备是独占的，谁先打开谁持有——挂在这里，其它模块就不用各自再开一个
     * Transport 去抢设备（旧代码 [com.robomaster.control.ui.MainViewModel] 就是这样和激光桥打架的）。
     */
    @Volatile var uartWriter: ((ByteArray) -> Unit)? = null

    /**
     * 取链路（必要时创建并启动）。同一个 host 重复调用只加引用，不会重建。
     *
     * @param tag 租约名，例如 "laser" / "vision"
     */
    @Synchronized
    fun acquire(tag: String, host: String, onLog: (String) -> Unit): UartLink? {
        if (host.isBlank()) return null
        logger = onLog

        val cur = link
        if (cur != null && cur.host == host) {
            holders.add(tag)
            return cur
        }
        if (cur != null) {
            logger("[网络] 主机变更 ${cur.host} → $host，重建链路")
            cur.stop()
            link = null
            holders.clear()
        }

        val created = UartLink(host, udpPort, tcpPort).apply {
            this.onLog = { msg -> logger(msg) }
            this.onJson = { json -> jsonListeners.forEach { runCatching { it(json) } } }
        }
        created.start()
        link = created
        holders.add(tag)
        return created
    }

    /** 释放租约；最后一个使用者释放时关链路 */
    @Synchronized
    fun release(tag: String) {
        holders.remove(tag)
        if (holders.isEmpty()) {
            link?.stop()
            link = null
        }
    }

    fun current(): UartLink? = link

    fun addJsonListener(listener: (String) -> Unit) {
        if (!jsonListeners.contains(listener)) jsonListeners.add(listener)
    }

    fun removeJsonListener(listener: (String) -> Unit) {
        jsonListeners.remove(listener)
    }

    /** 进程退出或主机配置清空时调用 */
    @Synchronized
    fun shutdown() {
        holders.clear()
        link?.stop()
        link = null
        jsonListeners.clear()
    }
}
