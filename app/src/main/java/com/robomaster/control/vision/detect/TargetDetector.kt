package com.robomaster.control.vision.detect

import com.robomaster.control.vision.preprocess.VNormalize
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
 *
 * ## 2026-10 公开数据集驱动的改进（rmcv/armor 9123 张真实赛场图评测）
 *
 * | 改进 | 依据 | 实测（公开数据红/蓝装甲板，GT 覆盖率口径） |
 * |---|---|---|
 * | V 通道 CLAHE 归一化（[VNormalize]，默认开） | 暗光目标 V 掉到 0.1~0.3 被阈值滤掉 | 红 3.9%→55.9%，蓝 20%→86% |
 * | 红色掩码 345-15/S0.40/V0.30 → 345-40/S0.45/V0.12 | 真实暗红偏橙 H 20-40°、V 0.10-0.25 | 同上 |
 * | 几何过滤改用最小外接矩形（旋转卡壳） | 轴对齐外接框把倾斜目标长边估大 8-15% | 尺寸误差 8.4%→6.9%（红）/ 5.2%→3.5%（蓝） |
 * | 基地靶检测增加闭运算 | 断裂/孔洞掩码被圆形度判据误拒 | 合成断裂圆目标从不检出到检出 |
 * | minArea 随分辨率缩放 | 固定 30 在 1440×1080 上漏检远处小目标 | 大图 77 / 小图 30 |
 *
 * 灰灭灯板误燃率保持 0%（红掩码）/ 0.5%（蓝掩码）。
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

    // ─────────── 几何判据（公开数据评测后维持，但改用旋转矩形计算）───────────
    private const val RECTNESS_MIN = 0.55   // 矩形度阈值（area / 最小外接矩形面积）
    private const val ASPECT_TOL = 0.4      // 圆形度判据的宽高比容差
    private const val FILL_MIN = 0.55       // 圆形度（填充比）阈值

    /**
     * 高地靶检测：绿色面板 → 几何过滤 → 4 面板聚类。
     *
     * 判定：连通域为近似矩形（最小外接矩形矩形度 > 0.55），长边 >= 8px，
     * 且多个面板在空间上聚集（2×2 或线性排列）。
     *
     * @param normalizeV 是否先做 V 通道 CLAHE 归一化（默认 true，暗光必须开）
     */
    fun detectHighland(
        rgb: ByteArray, w: Int, h: Int, cam: CameraParams,
        normalizeV: Boolean = true
    ): List<Target> {
        val img = if (normalizeV) VNormalize.claheVCopy(rgb, w, h) else rgb
        val mask = Hsv.greenMask(img, w, h)
        val cleaned = Morphology.open(mask, w, h)
        val minArea = scaledMinArea(w, h)
        val blobs = Components.label(cleaned, w, h, minArea = minArea)

        // 几何过滤：近似矩形的面板（用最小外接矩形，旋转不变）
        val panels = ArrayList<Components.Blob>()
        for (b in blobs) {
            val rect = rectOf(b) ?: continue
            val rectness = b.area.toDouble() / (rect.length * rect.width)
            if (rectness > RECTNESS_MIN && rect.length >= 8 && rect.width >= 8) {
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
            // 组尺寸（所有面板最小外接矩形四角点并集的轴对齐范围）
            var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE
            for (b in g) {
                val rect = rectOf(b) ?: continue
                forEachCorner(rect) { x, y ->
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (minX > maxX || minY > maxY) continue
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
     * 判定：连通域近似圆形（填充比 = 面积/最小外接矩形面积 > 0.55，
     * 宽高比接近 1），直径 >= 10px。
     *
     * 2026-10: 增加闭运算（先开后闭）——公开数据集上断裂/带孔洞的红色掩码
     * 会被圆形度判据误拒，闭运算可合并碎片、填补孔洞。
     *
     * @param normalizeV 是否先做 V 通道 CLAHE 归一化（默认 true）
     */
    fun detectBase(
        rgb: ByteArray, w: Int, h: Int, cam: CameraParams,
        normalizeV: Boolean = true
    ): List<Target> {
        val img = if (normalizeV) VNormalize.claheVCopy(rgb, w, h) else rgb
        val mask = Hsv.redMask(img, w, h)
        // 圆目标用 开+闭：去噪同时合并断裂碎片、填补孔洞
        val cleaned = Morphology.close(Morphology.open(mask, w, h), w, h)
        val minArea = scaledMinArea(w, h)
        val blobs = Components.label(cleaned, w, h, minArea = minArea)

        val targets = ArrayList<Target>()
        for (b in blobs) {
            val rect = rectOf(b) ?: continue
            val fill = b.area.toDouble() / (rect.length * rect.width)
            val aspect = rect.length / rect.width
            if (fill > FILL_MIN && abs(aspect - 1.0) < ASPECT_TOL && rect.length >= 10) {
                val pixelSize = rect.length
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
                    confidence = fill
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

    /** 取 blob 的最小外接矩形（旋转卡壳）；像素被截断（巨型连通域）时返回 null */
    private fun rectOf(b: Components.Blob): MinAreaRect.Rect? {
        val px = b.pixels ?: return null
        // pixels 在巨型连通域（area > PIXEL_CAP）时被截断——此时 rect 不准，弃用
        if (b.area > Components.PIXEL_CAP) return null
        return MinAreaRect.compute(px)
    }

    /** 遍历最小外接矩形的 4 个角点（用于组尺寸并集） */
    private inline fun forEachCorner(rect: MinAreaRect.Rect, action: (Int, Int) -> Unit) {
        val ang = Math.toRadians(rect.angleDeg)
        val ux = Math.cos(ang); val uy = Math.sin(ang)
        val vx = -uy; val vy = ux
        val hl = rect.length / 2.0
        val hw = rect.width / 2.0
        for (su in intArrayOf(-1, 1)) {
            for (sv in intArrayOf(-1, 1)) {
                val x = Math.round(rect.cx + su * hl * ux + sv * hw * vx).toInt()
                val y = Math.round(rect.cy + su * hl * uy + sv * hw * vy).toInt()
                action(x, y)
            }
        }
    }

    /**
     * 最小连通域面积随分辨率缩放：640×480 → 30，1440×1080 → 77。
     * 固定值在大图上会把远处小目标当噪点滤掉，在小图上让噪点通过。
     */
    fun scaledMinArea(w: Int, h: Int): Int =
        maxOf(30, w * h / 20000)
}
