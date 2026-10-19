package com.robomaster.control.vision.detect

/**
 * 连通域标记（8 邻接，扫描线 flood fill，纯 Kotlin）。
 *
 * 输入二值掩码，输出每个连通域的统计信息（面积、外接框、像素列表）。
 * 参考 rm_selection/task1_lights/light_bar.py 的 findContours 流程，
 * 但用扫描线填充实现（比逐像素栈填充快，且天然给出外接框）。
 */
object Components {

    /** 连通域统计 */
    class Blob(
        val label: Int,
        var area: Int = 0,
        var minX: Int = Int.MAX_VALUE,
        var minY: Int = Int.MAX_VALUE,
        var maxX: Int = Int.MIN_VALUE,
        var maxY: Int = Int.MIN_VALUE,
        var sumX: Long = 0,
        var sumY: Long = 0
    ) {
        val cx: Double get() = if (area > 0) sumX.toDouble() / area else 0.0
        val cy: Double get() = if (area > 0) sumY.toDouble() / area else 0.0
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }

    /**
     * 标记连通域。
     *
     * @param mask 二值掩码（0 或 255）
     * @param w 宽
     * @param h 高
     * @param minArea 最小面积过滤（像素）
     * @return 连通域列表（按面积降序）
     */
    fun label(mask: ByteArray, w: Int, h: Int, minArea: Int = 20): List<Blob> {
        val visited = BooleanArray(w * h)
        val blobs = ArrayList<Blob>()
        var label = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (mask[idx] == 0.toByte() || visited[idx]) continue

                // 扫描线 flood fill（8 邻接）
                val blob = Blob(++label)
                val stackX = ArrayDeque<Int>()
                val stackY = ArrayDeque<Int>()
                stackX.addLast(x)
                stackY.addLast(y)
                visited[idx] = true

                while (stackX.isNotEmpty()) {
                    val px = stackX.removeLast()
                    val py = stackY.removeLast()
                    blob.area++
                    blob.sumX += px
                    blob.sumY += py
                    if (px < blob.minX) blob.minX = px
                    if (px > blob.maxX) blob.maxX = px
                    if (py < blob.minY) blob.minY = py
                    if (py > blob.maxY) blob.maxY = py

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = px + dx
                            val ny = py + dy
                            if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                            val nidx = ny * w + nx
                            if (visited[nidx] || mask[nidx] == 0.toByte()) continue
                            visited[nidx] = true
                            stackX.addLast(nx)
                            stackY.addLast(ny)
                        }
                    }
                }
                if (blob.area >= minArea) blobs.add(blob)
            }
        }
        blobs.sortByDescending { it.area }
        return blobs
    }
}
