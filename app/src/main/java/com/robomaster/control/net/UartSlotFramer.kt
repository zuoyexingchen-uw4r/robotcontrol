package com.robomaster.control.net

import com.robomaster.control.laser.LaserProtocol

/**
 * USB-UART 字节流 → 60B 定长槽。
 *
 * 与旧的 `LaserStreamFramer` 的区别：
 * - 输出统一 60B 槽（主控 39B 补零），网络层不再关心两类帧长度不同；
 * - **兼容主控帧后续改成 60B**：主控帧校验通过后，如果紧跟的 21 字节全是 0，
 *   就认为固件已经补零到 60B，一次吃掉 60 字节。这样固件升级前后都不用改 App。
 *
 * 帧同步沿用原策略：帧头不对滑 1 字节；校验失败计数并滑 1 字节。
 */
class UartSlotFramer {

    private val buf = ByteArray(RING_SIZE)
    private var head = 0
    private var tail = 0

    var lidarFrames = 0L; private set
    var mcuFrames = 0L; private set
    var badFrames = 0L; private set

    fun feed(data: ByteArray, out: MutableList<ByteArray>) {
        for (b in data) {
            buf[tail] = b
            tail = (tail + 1) % RING_SIZE
            extract(out)
        }
    }

    private fun available(): Int = (tail - head + RING_SIZE) % RING_SIZE

    private fun at(i: Int): Int = buf[(head + i) % RING_SIZE].toInt() and 0xFF

    private fun extract(out: MutableList<ByteArray>) {
        while (true) {
            val avail = available()
            if (avail < 2) return
            val h = (at(0) shl 8) or at(1)

            when (h) {
                NetProto.H_LIDAR -> {
                    if (avail < NetProto.LEN_LIDAR) return
                    val frame = ByteArray(NetProto.LEN_LIDAR)
                    for (i in frame.indices) frame[i] = buf[(head + i) % RING_SIZE]
                    if (LaserProtocol.isLidarFrame(frame)) {
                        out += frame
                        lidarFrames++
                        head = (head + NetProto.LEN_LIDAR) % RING_SIZE
                    } else {
                        badFrames++
                        head = (head + 1) % RING_SIZE
                    }
                }

                NetProto.H_MCU_UP -> {
                    if (avail < NetProto.LEN_MCU) return
                    val frame = ByteArray(NetProto.LEN_MCU)
                    for (i in frame.indices) frame[i] = buf[(head + i) % RING_SIZE]
                    if (LaserProtocol.parseMaster(frame) == null) {
                        badFrames++
                        head = (head + 1) % RING_SIZE
                    } else {
                        // 固件已补零到 60B 时，把尾部 21 个零一起吃掉
                        val padded = avail >= NetProto.SLOT &&
                            (NetProto.LEN_MCU until NetProto.SLOT).all { at(it) == 0 }
                        out += UartSlot.mcu(frame)
                        mcuFrames++
                        head = (head + if (padded) NetProto.SLOT else NetProto.LEN_MCU) % RING_SIZE
                    }
                }

                else -> head = (head + 1) % RING_SIZE
            }
        }
    }

    companion object {
        private const val RING_SIZE = 8192
    }
}
