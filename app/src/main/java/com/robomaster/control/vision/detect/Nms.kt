package com.robomaster.control.vision.detect

/**
 * 非极大值抑制（NMS，纯 Kotlin）。
 *
 * 数学依据（《传统视觉与深度学习视觉的数学基础》§11.2 检测框 NMS）：
 *
 *   IoU(bi, bj) = |bi ∩ bj| / |bi ∪ bj| ∈ [0,1]
 *   交集 = (min(x2) − max(x1))₊ · (min(y2) − max(y1))₊
 *
 * 为什么用 IoU 而不是"中心距离"或"面积差"：IoU 对尺度不敏感——
 * 大框和小框重叠一半都是 0.5，与"两个检测是否指向同一个物体"的直觉一致。
 *
 * 贪心算法（复杂度 O(n²)，目标数 < 10 时完全够用）：
 *   取当前最高分框 m 放入保留集 → 删除所有与 m 的 IoU > τ 的框 → 重复。
 *
 * 本项目用途：同色碎片经形态学闭合后仍可能产生多个重叠连通域
 * （尤其基地圆靶被反光切开时），NMS 把它们并回一个目标，避免
 * 卡尔曼在多个近邻观测之间来回跳变。
 */
object Nms {

    /** IoU 阈值：同类目标重叠超过 45% 视为同一个目标 */
    const val DEFAULT_IOU = 0.45

    /** 两个轴对齐框的 IoU，框格式 [x1, y1, x2, y2] */
    fun iou(a: IntArray, b: IntArray): Double {
        val ix1 = maxOf(a[0], b[0])
        val iy1 = maxOf(a[1], b[1])
        val ix2 = minOf(a[2], b[2])
        val iy2 = minOf(a[3], b[3])
        val iw = ix2 - ix1
        val ih = iy2 - iy1
        if (iw <= 0 || ih <= 0) return 0.0
        val inter = iw.toDouble() * ih
        val areaA = (a[2] - a[0]).coerceAtLeast(0).toDouble() * (a[3] - a[1]).coerceAtLeast(0)
        val areaB = (b[2] - b[0]).coerceAtLeast(0).toDouble() * (b[3] - b[1]).coerceAtLeast(0)
        val union = areaA + areaB - inter
        return if (union <= 0.0) 0.0 else inter / union
    }

    /**
     * 贪心 NMS。
     *
     * @param items 候选（保持输入顺序无关：内部按分数降序遍历）
     * @param iouThresh IoU 抑制阈值
     * @param score 分数（越高越优先保留，通常是置信度）
     * @param box 轴对齐框 [x1,y1,x2,y2]
     * @return 保留的候选（分数降序）
     */
    fun <T> greedy(
        items: List<T>,
        iouThresh: Double = DEFAULT_IOU,
        score: (T) -> Double,
        box: (T) -> IntArray
    ): List<T> {
        if (items.size <= 1) return items
        val sorted = items.sortedByDescending(score)
        val alive = BooleanArray(sorted.size) { true }
        val keep = ArrayList<T>(sorted.size)
        for (i in sorted.indices) {
            if (!alive[i]) continue
            keep.add(sorted[i])
            val bi = box(sorted[i])
            for (j in i + 1 until sorted.size) {
                if (!alive[j]) continue
                if (iou(bi, box(sorted[j])) > iouThresh) alive[j] = false
            }
        }
        return keep
    }
}
