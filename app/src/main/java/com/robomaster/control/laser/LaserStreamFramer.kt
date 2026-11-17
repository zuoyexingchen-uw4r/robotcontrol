package com.robomaster.control.laser

/**
 * 混合 UART 字节流切帧器。
 *
 * 输入：ESP32 经 CH343 传来的原始字节流，混有两种**定长**帧：
 * - 雷达点云帧 `55 AA ...`（60 字节）
 * - 主控帧 `AA 55 ...`（39 字节）
 *
 * 帧同步：逐个字节找帧头，遇 `55 AA` 取满 60 字节校验雷达帧，
 * 遇 `AA 55` 取满 39 字节校验主控帧；校验失败滑 1 字节重找。
 */
class LaserStreamFramer {

    enum class FrameType { LIDAR, MASTER }

    data class Frame(val type: FrameType, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = type.hashCode() * 31 + bytes.contentHashCode()
        override fun toString(): String = "${type.name}(${bytes.size}B)"
    }

    private val buf = ByteArray(RING_SIZE)
    private var head = 0
    private var tail = 0

    var lidarFrames = 0L; private set
    var masterFrames = 0L; private set
    var badFrames = 0L; private set

    fun feed(data: ByteArray, out: MutableList<Frame>) {
        for (b in data) {
            buf[tail] = b
            tail = (tail + 1) % RING_SIZE
            extract(out)
        }
    }

    private fun available(): Int = (tail - head + RING_SIZE) % RING_SIZE

    private fun extract(out: MutableList<Frame>) {
        while (true) {
            val avail = available()
            if (avail < 2) return
            val b0 = buf[head].toInt() and 0xFF
            val b1 = buf[(head + 1) % RING_SIZE].toInt() and 0xFF

            val isLidarHdr = b0 == LaserProtocol.LIDAR_HDR0 && b1 == LaserProtocol.LIDAR_HDR1
            val isMasterHdr = b0 == LaserProtocol.MASTER_HDR0 && b1 == LaserProtocol.MASTER_HDR1

            if (!isLidarHdr && !isMasterHdr) {
                head = (head + 1) % RING_SIZE
                continue
            }

            val len = if (isLidarHdr) LaserProtocol.LIDAR_FRAME_LEN else LaserProtocol.MASTER_FRAME_LEN
            if (avail < len) return   // 未收满，等更多字节

            val frame = ByteArray(len)
            for (i in 0 until len) frame[i] = buf[(head + i) % RING_SIZE]

            val ok = if (isLidarHdr) {
                LaserProtocol.isLidarFrame(frame)
            } else {
                LaserProtocol.parseMaster(frame) != null
            }
            if (ok) {
                out.add(Frame(if (isLidarHdr) FrameType.LIDAR else FrameType.MASTER, frame))
                if (isLidarHdr) lidarFrames++ else masterFrames++
                head = (head + len) % RING_SIZE
            } else {
                badFrames++
                head = (head + 1) % RING_SIZE
            }
        }
    }

    companion object {
        private const val RING_SIZE = 4096
    }
}
