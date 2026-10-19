package com.robomaster.control.vision.nav

/**
 * 导航模块（占位框架）。
 *
 * 状态：**未实现**——等机械组完成机器人框架（底盘/电机/里程计）后开工。
 *
 * 预留设计（参考 RoboMaster 导航常见方案）：
 * - 输入：视觉（场地特征/路标）+ 超声/TOF/激光（避障）+ 地磁（航向）
 * - 输出：底盘速度指令（线速度 + 角速度）→ 下位机
 * - 算法候选：
 *   1. 路标导航：识别场地标志物（隧道区/高地/台阶）→ 航点规划
 *   2. 视觉里程计（VO）：连续帧特征跟踪 → 相对位姿（课件 3D 内容）
 *   3. 避障：超声/TOF 近距离检测 → 局部绕行
 *
 * 当前仅定义接口骨架，保证主管线可编译、设置界面开关可接线。
 */
object Navigation {

    /** 导航状态 */
    enum class State { IDLE, PLANNING, MOVING, ARRIVED, BLOCKED }

    /** 底盘速度指令 */
    data class VelocityCommand(
        val linearMS: Double,    // 线速度 m/s（前为正）
        val angularRadS: Double, // 角速度 rad/s（左转为正）
        val state: State
    )

    private var state = State.IDLE

    /** 当前状态 */
    fun currentState(): State = state

    /**
     * 导航更新（占位：暂返回停止指令）。
     *
     * 机械组完成框架后实现：
     * - 接收航点目标
     * - 融合传感器数据
     * - 输出速度指令
     */
    fun update(): VelocityCommand {
        // TODO: 机械组完成机器人框架后实现
        // 1. 视觉路标识别（隧道区/中央高地/台阶）
        // 2. 超声/TOF 避障
        // 3. 地磁航向保持
        // 4. 航点路径规划 + PID 跟踪
        state = State.IDLE
        return VelocityCommand(0.0, 0.0, state)
    }

    /** 序列化速度指令为串口帧（与 FireController 同通道，不同命令字） */
    fun serialize(cmd: VelocityCommand): ByteArray {
        // [0xFA][0xBF][linL][linH][angL][angH][xor]
        val lin = (cmd.linearMS * 1000).toInt().coerceIn(-32768, 32767)
        val ang = (cmd.angularRadS * 1000).toInt().coerceIn(-32768, 32767)
        val frame = ByteArray(7)
        frame[0] = 0xFA.toByte()
        frame[1] = 0xBF.toByte()
        frame[2] = (lin and 0xFF).toByte()
        frame[3] = ((lin shr 8) and 0xFF).toByte()
        frame[4] = (ang and 0xFF).toByte()
        frame[5] = ((ang shr 8) and 0xFF).toByte()
        var xor = 0
        for (i in 0 until 6) xor = xor xor (frame[i].toInt() and 0xFF)
        frame[6] = xor.toByte()
        return frame
    }
}
