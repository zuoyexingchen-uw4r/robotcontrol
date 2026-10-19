package com.robomaster.control.vision.detect

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 最小外接矩形（旋转卡壳算法，纯 Kotlin）。
 *
 * 用途：对连通域像素求最小面积外接矩形，得到目标的
 * 中心、尺寸（长×宽）、旋转角——灯条/靶板几何分析的基础。
 *
 * 算法：旋转卡壳（Rotating Calipers）
 *   1. 求点集凸包（Andrew 单调链，O(n log n)）
 *   2. 对凸包每条边作为矩形的一条边，用卡壳求另外三个方向的极值点
 *   3. 取面积最小的矩形
 *
 * 参考 rm_selection/task1_lights/light_bar.py 的 minAreaRect 用法。
 */
object MinAreaRect {

    class Rect(
        val cx: Double,
        val cy: Double,
        val length: Double,   // 长边
        val width: Double,    // 短边
        val angleDeg: Double  // 长边方向（-90~90）
    )

    /**
     * 对像素点集求最小外接矩形。
     *
     * @param points 像素坐标（x,y 交替）
     * @return 最小外接矩形；点集为空或退化时返回 null
     */
    fun compute(points: IntArray): Rect? {
        if (points.size < 6) return null  // 至少 3 个点

        // 转 Point 列表
        val pts = ArrayList<Pair<Int, Int>>(points.size / 2)
        var i = 0
        while (i + 1 < points.size) {
            pts.add(points[i] to points[i + 1])
            i += 2
        }

        // 凸包（Andrew 单调链）
        val hull = convexHull(pts)
        if (hull.size < 3) {
            // 退化：共线点集，用主轴方向拟合
            return fitLineRect(pts)
        }

        // 旋转卡壳
        var bestArea = Double.MAX_VALUE
        var best: Rect? = null
        val n = hull.size
        for (k in 0 until n) {
            val a = hull[k]
            val b = hull[(k + 1) % n]
            val ex = (b.first - a.first).toDouble()
            val ey = (b.second - a.second).toDouble()
            val elen = Math.hypot(ex, ey)
            if (elen < 1e-6) continue
            // 边的单位向量（矩形局部 x 轴）
            val ux = ex / elen
            val uy = ey / elen
            // 局部 y 轴
            val vx = -uy
            val vy = ux

            // 所有点投影到局部坐标系
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (p in hull) {
                val dx = (p.first - a.first).toDouble()
                val dy = (p.second - a.second).toDouble()
                val u = dx * ux + dy * uy
                val v = dx * vx + dy * vy
                if (u < minU) minU = u
                if (u > maxU) maxU = u
                if (v < minV) minV = v
                if (v > maxV) maxV = v
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < bestArea) {
                bestArea = area
                // 矩形中心（局部坐标）→ 全局坐标
                val cu = (minU + maxU) / 2
                val cv = (minV + maxV) / 2
                val cx = a.first + cu * ux + cv * vx
                val cy = a.second + cu * uy + cv * vy
                val len = maxU - minU
                val wid = maxV - minV
                val angle = Math.toDegrees(atan2(uy, ux))
                best = if (len >= wid) Rect(cx, cy, len, wid, angle)
                else Rect(cx, cy, wid, len, angle + 90.0)
            }
        }
        return best
    }

    /** 凸包（Andrew 单调链） */
    private fun convexHull(pts: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val sorted = pts.sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return sorted

        fun cross(o: Pair<Int, Int>, a: Pair<Int, Int>, b: Pair<Int, Int>): Long {
            return (a.first - o.first).toLong() * (b.second - o.second) -
                    (a.second - o.second).toLong() * (b.first - o.first)
        }

        // 下凸包
        val lower = ArrayList<Pair<Int, Int>>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        // 上凸包
        val upper = ArrayList<Pair<Int, Int>>()
        for (k in sorted.indices.reversed()) {
            val p = sorted[k]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** 退化点集（共线）：用 PCA 主轴拟合矩形 */
    private fun fitLineRect(pts: List<Pair<Int, Int>>): Rect? {
        if (pts.isEmpty()) return null
        val n = pts.size
        val mx = pts.sumOf { it.first }.toDouble() / n
        val my = pts.sumOf { it.second }.toDouble() / n
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in pts) {
            val dx = p.first - mx
            val dy = p.second - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val theta = 0.5 * atan2(2 * sxy, sxx - syy)
        val ux = cos(theta); val uy = sin(theta)
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for (p in pts) {
            val dx = p.first - mx
            val dy = p.second - my
            val u = dx * ux + dy * uy
            val v = -dx * uy + dy * ux
            if (u < minU) minU = u
            if (u > maxU) maxU = u
            if (v < minV) minV = v
            if (v > maxV) maxV = v
        }
        val len = maxU - minU
        val wid = maxV - minV
        val angle = Math.toDegrees(theta)
        return if (len >= wid) Rect(mx, my, len, wid, angle)
        else Rect(mx, my, wid, len, angle + 90.0)
    }
}
