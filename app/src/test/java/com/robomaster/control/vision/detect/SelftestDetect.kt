import com.robomaster.control.vision.detect.TargetDetector
import kotlin.math.abs
import kotlin.math.hypot
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

    println("\n═══ 结果: $pass 通过, $fail 失败 ═══")
    if (fail > 0) kotlin.system.exitProcess(1)
}
