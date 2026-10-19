package com.robomaster.control.vision.detect

import com.robomaster.control.vision.solve.Pinhole
import kotlin.math.abs
import kotlin.math.PI

/**
 * 目标检测主管线（纯 Kotlin，无 OpenCV）。
 *
 * 流程（参考 rm_selection task1 灯条检测 + 课件小目标检测）：
 *   颜色分割(HSV) → 形态学去噪 → 连通域标记 → 几何分析(minAreaRect)
 *   → 目标判定（矩形度/圆形度/空间关系）→ 针孔测距测角
 *
 * 支持两类目标（第四届狼牙规则）：
 * - 高地靶：4 面板绿色标靶板（2 大 @5 分 + 2 小 @10 分）
 * - 基地靶：红色圆靶（3 固定 @15 分 + 1 移动 @45 分）
 *
 * 相机参数默认按典型手机广角估算（hFov=65°），可在 [CameraParams] 调整。
 */
object TargetDetector {

    /** 相机参数 */
    data class CameraParams(
        val imageWidth: Int,
        val imageHeight: Int,
        val focalPx: Double = Pinhole.focalFromFov(imageWidth, 65.0)
    )

    /** 检测到的目标 */
    data class Target(
        val type: Type,
        val cx: Double,          // 图像中心（像素）
        val cy: Double,
        val pixelSize: Double,   // 目标尺寸（像素，长边）
        val distanceMm: Double,  // 测距（mm，-1=无效）
        val yawDeg: Double,      // 相对光轴偏航角（度，右为正）
        val pitchDeg: Double,    // 相对光轴俯仰角（度，上为正）
        val confidence: Double   // 0-1
    ) {
        enum class Type { HIGHLAND, BASE }
    }

    // 目标物理尺寸（mm，规则已知）
    private const val HIGHLAND_PANEL_MM = 120.0   // 高地靶面板边长（估值，待实测标定）
    private const val BASE_TARGET_MM = 150.0      // 基地靶直径（估值，待实测标定）

    /**
     * 高地靶检测：绿色面板 → 几何过滤 → 4 面板聚类。
     *
     * 判定：连通域为近似矩形（矩形度 > 0.6），面积在合理范围，
     * 且多个面板在空间上聚集（2×2 或线性排列）。
     */
    fun detectHighland(rgb: ByteArray, w: Int, h: Int, cam: CameraParams): List<Target> {
        val mask = Hsv.greenMask(rgb, w, h)
        val cleaned = Morphology.open(mask, w, h)
        val blobs = Components.label(cleaned, w, h, minArea = 30)

        // 几何过滤：近似矩形的面板
        val panels = ArrayList<Components.Blob>()
        for (b in blobs) {
            val rectness = b.area.toDouble() / (b.width * b.height)
            if (rectness > 0.55 && b.width >= 8 && b.height >= 8) {
                panels.add(b)
            }
        }
        if (panels.isEmpty()) return emptyList()

        // 空间聚类：面板中心互相靠近（间距 < 平均尺寸的 3 倍）归为一组
        val groups = clusterPanels(panels)
        val targets = ArrayList<Target>()
        for (g in groups) {
            if (g.size < 2) continue  // 至少 2 个面板才算高地靶
            // 组中心
            var sx = 0.0; var sy = 0.0
            for (b in g) { sx += b.cx; sy += b.cy }
            val cx = sx / g.size
            val cy = sy / g.size
            // 组尺寸（所有面板外接框的并集）
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for (b in g) {
                if (b.minX < minX) minX = b.minX
                if (b.minY < minY) minY = b.minY
                if (b.maxX > maxX) maxX = b.maxX
                if (b.maxY > maxY) maxY = b.maxY
            }
            val pixelSize = maxOf(maxX - minX, maxY - minY).toDouble()
            val dist = Pinhole.distance(pixelSize, HIGHLAND_PANEL_MM * 2, cam.focalPx)
            val dx = cx - w / 2.0
            val dy = cy - h / 2.0
            val (yaw, pitch) = Pinhole.angles(dx, dy, cam.focalPx)
            targets.add(Target(
                type = Target.Type.HIGHLAND,
                cx = cx, cy = cy,
                pixelSize = pixelSize,
                distanceMm = dist,
                yawDeg = yaw, pitchDeg = pitch,
                confidence = (g.size / 4.0).coerceAtMost(1.0)
            ))
        }
        return targets
    }

    /**
     * 基地靶检测：红色圆靶 → 圆形度过滤。
     *
     * 判定：连通域近似圆形（圆形度 = 4π·面积/周长² > 0.6，
     * 简化用 面积/外接矩形面积 ≈ π/4 ≈ 0.785，容差到 0.6）。
     */
    fun detectBase(rgb: ByteArray, w: Int, h: Int, cam: CameraParams): List<Target> {
        val mask = Hsv.redMask(rgb, w, h)
        val cleaned = Morphology.open(mask, w, h)
        val blobs = Components.label(cleaned, w, h, minArea = 30)

        val targets = ArrayList<Target>()
        for (b in blobs) {
            // 圆形度：理想圆 面积/(πr²)=1，用外接框近似
            val fillRatio = b.area.toDouble() / (b.width * b.height)
            val aspect = b.width.toDouble() / b.height
            if (fillRatio > 0.55 && abs(aspect - 1.0) < 0.4 && b.width >= 10) {
                val pixelSize = maxOf(b.width, b.height).toDouble()
                val dist = Pinhole.distance(pixelSize, BASE_TARGET_MM, cam.focalPx)
                val dx = b.cx - w / 2.0
                val dy = b.cy - h / 2.0
                val (yaw, pitch) = Pinhole.angles(dx, dy, cam.focalPx)
                targets.add(Target(
                    type = Target.Type.BASE,
                    cx = b.cx, cy = b.cy,
                    pixelSize = pixelSize,
                    distanceMm = dist,
                    yawDeg = yaw, pitchDeg = pitch,
                    confidence = fillRatio
                ))
            }
        }
        return targets
    }

    /** 面板空间聚类（简单贪心：距离近的归一组） */
    private fun clusterPanels(panels: List<Components.Blob>): List<List<Components.Blob>> {
        val groups = ArrayList<MutableList<Components.Blob>>()
        val assigned = BooleanArray(panels.size)
        for (i in panels.indices) {
            if (assigned[i]) continue
            val g = ArrayList<Components.Blob>()
            g.add(panels[i])
            assigned[i] = true
            val avgSize = (panels[i].width + panels[i].height) / 2.0
            val maxDist = avgSize * 3.0
            for (j in i + 1 until panels.size) {
                if (assigned[j]) continue
                val dx = panels[j].cx - panels[i].cx
                val dy = panels[j].cy - panels[i].cy
                if (Math.hypot(dx, dy) < maxDist) {
                    g.add(panels[j])
                    assigned[j] = true
                }
            }
            groups.add(g)
        }
        return groups
    }
}
