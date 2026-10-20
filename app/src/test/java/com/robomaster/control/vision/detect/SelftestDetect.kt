import com.robomaster.control.vision.detect.Components
import com.robomaster.control.vision.detect.Hsv
import com.robomaster.control.vision.detect.MinAreaRect
import com.robomaster.control.vision.detect.Morphology
import com.robomaster.control.vision.detect.TargetDetector
import com.robomaster.control.vision.preprocess.VNormalize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.PI

var pass = 0
var fail = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("PASS  $name  $detail") }
    else { fail++; println("FAIL  $name  $detail") }
}

fun main() {
    val w = 640; val h = 480
    val rgb = ByteArray(w * h * 3)  // 全黑底

    fun setPx(x: Int, y: Int, r: Int, g: Int, b: Int) {
        val idx = (y * w + x) * 3
        rgb[idx] = r.toByte()
        rgb[idx + 1] = g.toByte()
        rgb[idx + 2] = b.toByte()
    }

    // ═══ 合成高地靶：2×2 绿色面板（左上区域）═══
    // 面板 40×30，间距 10，整体约 90×70
    val panelW = 40; val panelH = 30; val gap = 10
    val baseX = 100; val baseY = 80
    for (row in 0 until 2) {
        for (col in 0 until 2) {
            val px = baseX + col * (panelW + gap)
            val py = baseY + row * (panelH + gap)
            for (y in py until py + panelH) {
                for (x in px until px + panelW) {
                    setPx(x, y, 0, 255, 0)  // 纯绿
                }
            }
        }
    }
    // 高地靶组中心（4 面板并集中心）
    val hlCx = baseX + panelW + gap / 2.0
    val hlCy = baseY + panelH + gap / 2.0

    // ═══ 合成基地靶：2 个红色圆（右下区域）═══
    val r1 = 25
    val c1x = 450; val c1y = 350
    for (y in c1y - r1..c1y + r1) {
        for (x in c1x - r1..c1x + r1) {
            if (hypot((x - c1x).toDouble(), (y - c1y).toDouble()) <= r1) {
                setPx(x, y, 255, 0, 0)  // 纯红
            }
        }
    }
    val r2 = 18
    val c2x = 520; val c2y = 200
    for (y in c2y - r2..c2y + r2) {
        for (x in c2x - r2..c2x + r2) {
            if (hypot((x - c2x).toDouble(), (y - c2y).toDouble()) <= r2) {
                setPx(x, y, 255, 0, 0)
            }
        }
    }

    val cam = TargetDetector.CameraParams(w, h)

    // ═══ 测试 1：高地靶检测 ═══
    val hlTargets = TargetDetector.detectHighland(rgb, w, h, cam)
    check("高地靶-检出", hlTargets.isNotEmpty(), "检出 ${hlTargets.size} 个")
    if (hlTargets.isNotEmpty()) {
        val t = hlTargets[0]
        val err = hypot(t.cx - hlCx, t.cy - hlCy)
        check("高地靶-中心误差", err < 15.0,
            "误差 ${"%.1f".format(err)}px (期望<15) 检出(${"%.0f".format(t.cx)},${"%.0f".format(t.cy)}) 实际(${hlCx.toInt()},${hlCy.toInt()})")
        check("高地靶-测距有效", t.distanceMm > 0, "d=${t.distanceMm.toInt()}mm")
        check("高地靶-角度合理", abs(t.yawDeg) < 30.0, "yaw=${"%.1f".format(t.yawDeg)}°")
    }

    // ═══ 测试 2：基地靶检测 ═══
    val baseTargets = TargetDetector.detectBase(rgb, w, h, cam)
    check("基地靶-检出2个", baseTargets.size >= 2, "检出 ${baseTargets.size} 个")
    if (baseTargets.size >= 2) {
        // 按 x 排序，验证两个圆心
        val sorted = baseTargets.sortedBy { it.cx }
        val e1 = hypot(sorted[0].cx - c1x, sorted[0].cy - c1y)
        val e2 = hypot(sorted[1].cx - c2x, sorted[1].cy - c2y)
        check("基地靶-圆心1误差", e1 < 10.0, "误差 ${"%.1f".format(e1)}px")
        check("基地靶-圆心2误差", e2 < 10.0, "误差 ${"%.1f".format(e2)}px")
        check("基地靶-测距有效", sorted[0].distanceMm > 0, "d=${sorted[0].distanceMm.toInt()}mm")
    }

    // ═══ 测试 3：无目标时不误检 ═══
    val empty = ByteArray(w * h * 3)  // 全黑
    val hlEmpty = TargetDetector.detectHighland(empty, w, h, cam)
    val baseEmpty = TargetDetector.detectBase(empty, w, h, cam)
    check("无目标-高地靶不误检", hlEmpty.isEmpty(), "检出 ${hlEmpty.size}")
    check("无目标-基地靶不误检", baseEmpty.isEmpty(), "检出 ${baseEmpty.size}")

    // ═══ 测试 4：测距数值验证（针孔公式一致性）═══
    // 验证 TargetDetector 用的距离 = Pinhole.distance(focal, real, pixel)
    // （focal 从 CameraParams 取实际值，不硬编码；形态学边缘效应已含在 pixelSize 里）
    if (hlTargets.isNotEmpty()) {
        val t = hlTargets[0]
        val expected = cam.focalPx * 240.0 / t.pixelSize
        check("测距-针孔公式一致", abs(t.distanceMm - expected) < 1.0,
            "d=${t.distanceMm.toInt()} 期望=${expected.toInt()} focal=${"%.1f".format(cam.focalPx)}")
    }

    // ════════════════════════════════════════════════════════════════
    // 2026-10 公开数据集（rmcv/armor 9123 张真实赛场图）驱动的改进用例
    // ════════════════════════════════════════════════════════════════

    /** 新建黑底画布 */
    fun blackCanvas(): ByteArray = ByteArray(w * h * 3)

    /** 圆域填充（用于合成靶标） */
    fun fillCircle(img: ByteArray, cx: Int, cy: Int, r: Int, color: IntArray) {
        for (y in cy - r..cy + r) {
            for (x in cx - r..cx + r) {
                if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= r) {
                    val idx = (y * w + x) * 3
                    img[idx] = color[0].toByte()
                    img[idx + 1] = color[1].toByte()
                    img[idx + 2] = color[2].toByte()
                }
            }
        }
    }

    /** 旋转矩形填充（局部坐标判定，长 L 沿 angleDeg 方向） */
    fun fillRotatedRect(img: ByteArray, cx: Int, cy: Int, len: Double, wid: Double, angleDeg: Double, color: IntArray) {
        val rad = Math.toRadians(angleDeg)
        val ux = cos(rad); val uy = sin(rad)
        val hl = len / 2; val hw = wid / 2
        val margin = (hypot(len, wid) / 2).toInt() + 2
        for (y in (cy - margin)..(cy + margin)) {
            for (x in (cx - margin)..(cx + margin)) {
                if (x < 0 || x >= w || y < 0 || y >= h) continue
                val dx = (x - cx).toDouble(); val dy = (y - cy).toDouble()
                val u = dx * ux + dy * uy
                val v = -dx * uy + dy * ux
                if (abs(u) <= hl && abs(v) <= hw) {
                    val idx = (y * w + x) * 3
                    img[idx] = color[0].toByte()
                    img[idx + 1] = color[1].toByte()
                    img[idx + 2] = color[2].toByte()
                }
            }
        }
    }

    // ═══ 测试 5：暗红圆（真实暗光装甲板统计：H≈25°, S≈0.77, V=0.20）═══
    // Python 移植版预验证：旧掩码(345-15/S.40/V.30)覆盖率 0%，新掩码 100%
    val dimRed = intArrayOf(51, 28, 12)   // H=24.0 S=0.765 V=0.200
    val imgDim = blackCanvas()
    fillCircle(imgDim, 450, 350, 25, dimRed)
    // 旧阈值复现：Hsv.colorMask 直接以旧参数生成，验证漏检根因
    val oldMask = Hsv.colorMask(imgDim, w, h, hueMin = 345f, hueMax = 15f, satMin = 0.40f, valMin = 0.30f)
    check("暗红圆-旧阈值漏检(根因复现)", oldMask.none { it != 0.toByte() },
        "旧掩码命中 ${oldMask.count { it != 0.toByte() }} px")
    val dimHits = TargetDetector.detectBase(imgDim, w, h, cam, normalizeV = false)
    check("暗红圆-新阈值检出", dimHits.isNotEmpty(), "检出 ${dimHits.size} 个")
    if (dimHits.isNotEmpty()) {
        val err = hypot(dimHits[0].cx - 450.0, dimHits[0].cy - 350.0)
        check("暗红圆-中心误差<10px", err < 10.0, "误差 ${"%.1f".format(err)}px")
        check("暗红圆-测距有效", dimHits[0].distanceMm > 0, "d=${dimHits[0].distanceMm.toInt()}mm")
    }

    // ═══ 测试 6：阈值边缘暗红圆（V=0.114 < 0.12）——VNormalize 决定检出 ═══
    // Python 移植版预验证：不开 CLAHE 漏检；开 CLAHE 后 V→0.129 检出
    val edgeRed = intArrayOf(29, 17, 7)   // H=28.0 S=0.757 V=0.114
    val imgEdge = blackCanvas()
    fillCircle(imgEdge, 450, 350, 25, edgeRed)
    val edgeNoNorm = TargetDetector.detectBase(imgEdge, w, h, cam, normalizeV = false)
    val edgeNorm = TargetDetector.detectBase(imgEdge, w, h, cam, normalizeV = true)
    check("边缘暗红圆-不归一化漏检", edgeNoNorm.isEmpty(), "检出 ${edgeNoNorm.size} 个")
    check("边缘暗红圆-CLAHE救回", edgeNorm.isNotEmpty(), "检出 ${edgeNorm.size} 个")
    if (edgeNorm.isNotEmpty()) {
        val err = hypot(edgeNorm[0].cx - 450.0, edgeNorm[0].cy - 350.0)
        check("边缘暗红圆-中心误差<10px", err < 10.0, "误差 ${"%.1f".format(err)}px")
    }

    // ═══ 测试 7：断裂红圆（4 弧 + 4°/2px 缝）——开+闭运算合并 ═══
    // Python 移植版预验证：仅开运算 5 个碎片；开+闭 1 个圆域，fill=0.81
    val imgFrag = blackCanvas()
    val fcx = 450; val fcy = 350; val fr = 25
    for (y in fcy - fr..fcy + fr) {
        for (x in fcx - fr..fcx + fr) {
            val dx = x - fcx; val dy = y - fcy
            val rad = hypot(dx.toDouble(), dy.toDouble())
            if (rad > fr) continue
            if (rad % 12.0 < 2.0) continue                      // 径向 2px 缝（r=0/12/24）
            val ang = (Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())) + 360) % 360
            val arcOk = (ang < 88) || (ang in 92.0..179.999) ||
                        (ang in 184.0..271.999) || (ang >= 276)  // 4 段 88° 弧，缝 4°
            if (!arcOk) continue
            val idx = (y * w + x) * 3
            imgFrag[idx] = 255.toByte(); imgFrag[idx + 1] = 0; imgFrag[idx + 2] = 0
        }
    }
    val fragRawMask = Hsv.redMask(imgFrag, w, h)
    val fragOpen = Morphology.open(fragRawMask, w, h)
    val fragBlobsRaw = Components.label(fragOpen, w, h, minArea = 30)
    val fragBlobsMerged = Components.label(Morphology.close(fragOpen, w, h), w, h, minArea = 30)
    check("断裂圆-开运算后多碎片", fragBlobsRaw.size >= 3, "${fragBlobsRaw.size} 个碎片")
    check("断裂圆-闭运算合并为1", fragBlobsMerged.size == 1, "${fragBlobsMerged.size} 个域")
    val fragHits = TargetDetector.detectBase(imgFrag, w, h, cam, normalizeV = false)
    check("断裂圆-检出", fragHits.isNotEmpty(), "检出 ${fragHits.size} 个")
    if (fragHits.isNotEmpty()) {
        val err = hypot(fragHits[0].cx - fcx.toDouble(), fragHits[0].cy - fcy.toDouble())
        check("断裂圆-中心误差<10px", err < 10.0, "误差 ${"%.1f".format(err)}px")
    }

    // ═══ 测试 8：旋转面板（60×30 @30°）——最小外接矩形几何精度 ═══
    // Python 移植版预验证：minAreaRect 长边误差 1.8%，轴对齐外接框 10.0%
    val imgRot = blackCanvas()
    fillRotatedRect(imgRot, 320, 240, 60.0, 30.0, 30.0, intArrayOf(0, 255, 0))
    val rotMask = Morphology.open(Hsv.greenMask(imgRot, w, h), w, h)
    val rotBlobs = Components.label(rotMask, w, h, minArea = 30)
    check("旋转面板-连通域存在", rotBlobs.isNotEmpty(), "${rotBlobs.size} 个")
    if (rotBlobs.isNotEmpty()) {
        val b = rotBlobs[0]
        val rect = b.pixels?.let { MinAreaRect.compute(it) }
        check("旋转面板-minAreaRect可计算", rect != null)
        if (rect != null) {
            check("旋转面板-长边≈60", abs(rect.length - 60.0) <= 4.0,
                "length=${"%.1f".format(rect.length)} (期望60±4)")
            check("旋转面板-短边≈30", abs(rect.width - 30.0) <= 3.0,
                "width=${"%.1f".format(rect.width)} (期望30±3)")
            check("旋转面板-中心≈(320,240)",
                hypot(rect.cx - 320.0, rect.cy - 240.0) < 2.0,
                "(${"%.1f".format(rect.cx)},${"%.1f".format(rect.cy)})")
        }
        val axisSide = maxOf(b.width, b.height).toDouble()
        check("旋转面板-轴对齐外接框膨胀>5%", axisSide > 63.0,
            "axis=${axisSide.toInt()}px vs minAreaRect 60px（体现改用旋转卡壳的动机）")
    }

    // ═══ 测试 9：minArea 随分辨率缩放 ═══
    check("minArea-640x480=30", TargetDetector.scaledMinArea(640, 480) == 30,
        "${TargetDetector.scaledMinArea(640, 480)}")
    check("minArea-1920x1080=103", TargetDetector.scaledMinArea(1920, 1080) == 103,
        "${TargetDetector.scaledMinArea(1920, 1080)}")
    check("minArea-320x240=30", TargetDetector.scaledMinArea(320, 240) == 30,
        "${TargetDetector.scaledMinArea(320, 240)}")

    // ═══ 测试 10：VNormalize CLAHE 单元测试（提亮 + 色保真 + 黑底不污染）═══
    val imgClahe = blackCanvas()
    fillCircle(imgClahe, 450, 350, 25, edgeRed)
    val before = IntArray(3) { imgClahe[((350 * w) + 450) * 3 + it].toInt() and 0xFF }
    val clahed = VNormalize.claheVCopy(imgClahe, w, h)
    val after = IntArray(3) { clahed[((350 * w) + 450) * 3 + it].toInt() and 0xFF }
    val vBefore = maxOf(before[0], before[1], before[2])
    val vAfter = maxOf(after[0], after[1], after[2])
    check("CLAHE-V提升>=5%", vAfter >= vBefore * 1.05,
        "V $vBefore→$vAfter (+${"%.1f".format((vAfter - vBefore) * 100.0 / vBefore)}%)")
    val hsvB = FloatArray(3); val hsvA = FloatArray(3)
    Hsv.rgbToHsv(before[0], before[1], before[2], hsvB)
    Hsv.rgbToHsv(after[0], after[1], after[2], hsvA)
    check("CLAHE-色相漂移<15°", abs(hsvA[0] - hsvB[0]) < 15.0,
        "H ${"%.1f".format(hsvB[0])}°→${"%.1f".format(hsvA[0])}°")
    check("CLAHE-饱和度基本不变", abs(hsvA[1] - hsvB[1]) < 0.05,
        "S ${"%.3f".format(hsvB[1])}→${"%.3f".format(hsvA[1])}")
    val bg = IntArray(3) { clahed[(10 * w + 10) * 3 + it].toInt() and 0xFF }
    check("CLAHE-黑底保持黑", bg[0] == 0 && bg[1] == 0 && bg[2] == 0, "(${bg[0]},${bg[1]},${bg[2]})")

    // ═══ 测试 11：红圆场景不误检高地靶（绿色掩码特异性）═══
    val hlFromRed = TargetDetector.detectHighland(imgDim, w, h, cam)
    check("红圆场景-高地靶不误检", hlFromRed.isEmpty(), "检出 ${hlFromRed.size} 个")

    println("\n═══ 结果: $pass 通过, $fail 失败 ═══")
    if (fail > 0) kotlin.system.exitProcess(1)
}
