package com.robomaster.control.laser

/**
 * 混合 UART 字节流切帧器。
 *
 * 输入：ESP32 经 CH343 传来的原始字节流，其中混有两种定长 60 字节帧：
 * - 雷达点云帧 `55 AA ...`（[LaserProtocol.isLidarFrame] 校验）
 * - 主控帧 `AA 55 ...`（[LaserProtocol.parseMaster] 校验）
 *
 * 输出：已切好的帧（整 60 字节），调用方决定如何封装/转发。
 *
 * 容错策略：帧头不对或校验失败时**滑动 1 字节**重找（不整帧跳过），
 * 保证粘包/断帧/丢字节后能重新同步；坏帧直接丢弃（不重传，靠 seq 感知）。
 */
class LaserStreamFramer {

    /** 切出的帧类型 */
    enum class FrameType { LIDAR, MASTER }

    /** 切出的完整帧 */
    data class Frame(val type: FrameType, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Frame && type == other.type && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = type.hashCode() * 31 + bytes.contentHashCode()
        override fun toString(): String =
            "${type.name}(${bytes.size}B)"
    }

    private val buf = ByteArray(RING_SIZE)
    private var head = 0
    private var tail = 0

    var lidarFrames = 0L; private set
    var masterFrames = 0L; private set
    var badFrames = 0L; private set

    /** 喂入一段字节流，切出的完整帧追加到 [out]。 */
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
            if (avail < LaserProtocol.FRAME_LEN) return

            val b0 = buf[head].toInt() and 0xFF
            val b1 = buf[(head + 1) % RING_SIZE].toInt() and 0xFF

            val isLidarHdr = b0 == LaserProtocol.LIDAR_HDR0 && b1 == LaserProtocol.LIDAR_HDR1
            val isMasterHdr = b0 == LaserProtocol.MASTER_HDR0 && b1 == LaserProtocol.MASTER_HDR1

            if (!isLidarHdr && !isMasterHdr) {
                head = (head + 1) % RING_SIZE   // 找不到帧头，滑 1 字节
                continue
            }

            // 取满 60 字节
            val frame = ByteArray(LaserProtocol.FRAME_LEN)
            for (i in 0 until LaserProtocol.FRAME_LEN) {
                frame[i] = buf[(head + i) % RING_SIZE]
            }

            if (isLidarHdr) {
                if (LaserProtocol.isLidarFrame(frame)) {
                    out.add(Frame(FrameType.LIDAR, frame))
                    lidarFrames++
                    head = (head + LaserProtocol.FRAME_LEN) % RING_SIZE
                } else {
                    badFrames++
                    head = (head + 1) % RING_SIZE
                }
            } else { // isMasterHdr
                if (LaserProtocol.parseMaster(frame) != null) {
                    out.add(Frame(FrameType.MASTER, frame))
                    masterFrames++
                    head = (head + LaserProtocol.FRAME_LEN) % RING_SIZE
                } else {
                    badFrames++
                    head = (head + 1) % RING_SIZE
                }
            }
        }
    }

    companion object {
        private const val RING_SIZE = 4096
    }
}
