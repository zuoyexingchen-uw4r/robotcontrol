package com.robomaster.control.vision.detect

/**
 * 二值形态学操作（3×3 结构元，纯 Kotlin）。
 *
 * 用途：颜色分割后的掩码去噪——
 * - 腐蚀（erode）：消除孤立小噪点（比结构元小的亮斑被抹掉）
 * - 膨胀（dilate）：填补目标内部小孔洞、连接断裂区域
 * - 开运算（先腐后胀）：去噪同时保持目标大小（灯条检测标准流程）
 * - 闭运算（先胀后腐）：填补孔洞
 *
 * 参考 rm_selection/task1_lights/light_bar.py 的形态学流程。
 */
object Morphology {

    /** 腐蚀：3×3 窗口内全为 255 才保留 */
    fun erode(mask: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var all = true
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (mask[(y + dy) * w + (x + dx)] == 0.toByte()) { all = false; break }
                    }
                    if (!all) break
                }
                out[y * w + x] = if (all) 255.toByte() else 0
            }
        }
        return out
    }

    /** 膨胀：3×3 窗口内有 255 就保留 */
    fun dilate(mask: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var any = false
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        if (mask[ny * w + nx] != 0.toByte()) { any = true; break }
                    }
                    if (any) break
                }
                out[y * w + x] = if (any) 255.toByte() else 0
            }
        }
        return out
    }

    /** 开运算：先腐蚀后膨胀（去孤立噪点） */
    fun open(mask: ByteArray, w: Int, h: Int): ByteArray = dilate(erode(mask, w, h), w, h)

    /** 闭运算：先膨胀后腐蚀（填补孔洞） */
    fun close(mask: ByteArray, w: Int, h: Int): ByteArray = erode(dilate(mask, w, h), w, h)
}
