import com.robomaster.control.vision.decide.FireController
import com.robomaster.control.vision.detect.Hsv
import com.robomaster.control.vision.detect.MinAreaRect
import com.robomaster.control.vision.detect.Nms
import com.robomaster.control.vision.detect.StructureTensor
import com.robomaster.control.vision.detect.SubpixelRect
import com.robomaster.control.vision.preprocess.Gaussian
import com.robomaster.control.vision.preprocess.GuidedFilter
import com.robomaster.control.vision.solve.Pinhole
import com.robomaster.control.vision.track.KalmanFilterCV
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

var pass = 0
var fail = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("PASS  $name  $detail") }
    else { fail++; println("FAIL  $name  $detail") }
}

/** 周期 180° 的角度距离 */
fun angDist180(a: Double, b: Double): Double {
    var d = abs(a - b) % 180.0
    if (d > 90.0) d = 180.0 - d
    return d
}

/** 抗锯齿矩形渲染（4×4 超采样面积平均），用于亚像素精定位核对 */
fun renderSoftRect(
    w: Int, h: Int, cx: Double, cy: Double,
    length: Double, width: Double, angleDeg: Double,
    bg: Double = 40.0, fg: Double = 220.0
): ByteArray {
    val img = ByteArray(w * h * 3)
    val ang = Math.toRadians(angleDeg)
    val ux = cos(ang); val uy = sin(ang)
    val vx = -uy; val vy = ux
    val hl = length / 2.0; val hw = width / 2.0
    val ss = 8   // 每轴 8 个子样本 → 64 次/像素，覆盖率量化误差 < 1/64
    for (y in 0 until h) {
        for (x in 0 until w) {
            var acc = 0.0
            for (sy in 0 until ss) {
                for (sx in 0 until ss) {
                    val px = x + (sx + 0.5) / ss - cx
                    val py = y + (sy + 0.5) / ss - cy
                    val u = px * ux + py * uy
                    val v = px * vx + py * vy
                    // 硬边界：像素面积内覆盖率 = 落在矩形内的子样本比例
                    if (abs(u) <= hl && abs(v) <= hw) acc += 1.0
                }
            }
            val cov = acc / (ss * ss)
            val g = (bg + (fg - bg) * cov).toInt().coerceIn(0, 255)
            val i = (y * w + x) * 3
            img[i] = g.toByte(); img[i + 1] = g.toByte(); img[i + 2] = g.toByte()
        }
    }
    return img
}

fun main() {
    // ═══════════ A. 可分离高斯（§4.2） ═══════════
    run {
        val k = Gaussian.kernel1D(1.5)
        var sum = 0.0
        for (v in k) sum += v
        check("高斯-核归一化", abs(sum - 1.0) < 1e-9, "sum=${"%.9f".format(sum)}")
        check("高斯-支撑半径=⌈3σ⌉", Gaussian.radiusOf(1.5) == 5, "a=${Gaussian.radiusOf(1.5)} (σ=1.5)")

        val w = 40; val h = 40
        val flat = ByteArray(w * h) { 100 }
        val bf = Gaussian.blur(flat, w, h, 2.0)
        var maxDev = 0
        for (v in bf) maxDev = maxOf(maxDev, abs((v.toInt() and 0xFF) - 100))
        check("高斯-常图不变", maxDev == 0, "最大偏差=$maxDev")

        // 自卷积闭合：σ1 ⊗ σ2 ≈ √(σ1²+σ2²)
        val k1 = Gaussian.kernel1D(1.0)
        val k2 = Gaussian.kernel1D(1.5)
        val kComb = DoubleArray(k1.size + k2.size - 1)
        for (i in k1.indices) for (j in k2.indices) kComb[i + j] += k1[i] * k2[j]
        val expect = Gaussian.kernel1D(sqrt(1.0 + 2.25))
        // 只在重叠区间比较（两侧截断尾部）
        val off = (kComb.size - expect.size) / 2
        var num = 0.0; var den = 0.0
        for (i in expect.indices) {
            val d = kComb[i + off] - expect[i]
            num += d * d; den += expect[i] * expect[i]
        }
        val relErr = sqrt(num / den)
        check("高斯-自卷积方差相加", relErr < 0.05, "相对误差=${"%.2f".format(relErr * 100)}%")

        // 噪声抑制
        val rnd = Random(7)
        val noisy = ByteArray(w * h) { (128 + rnd.nextInt(-40, 41)).coerceIn(0, 255).toByte() }
        fun std(a: ByteArray): Double {
            var m = 0.0
            for (v in a) m += v.toInt() and 0xFF
            m /= a.size
            var s = 0.0
            for (v in a) { val d = (v.toInt() and 0xFF) - m; s += d * d }
            return sqrt(s / a.size)
        }
        val sIn = std(noisy)
        val sOut = std(Gaussian.blur(noisy, w, h, 1.5))
        check("高斯-噪声抑制", sOut < sIn * 0.6, "σ ${"%.1f".format(sIn)}→${"%.1f".format(sOut)}")
    }

    // ═══════════ B. 导向滤波（§4.5） ═══════════
    run {
        val w = 64; val h = 64
        // 阶跃图：左 50 右 200
        val step = ByteArray(w * h) { i -> (if ((i % w) < w / 2) 50 else 200).toByte() }
        val rnd = Random(11)
        val noisy = ByteArray(w * h) { i ->
            val base = step[i].toInt() and 0xFF
            (base + rnd.nextInt(-20, 21)).coerceIn(0, 255).toByte()
        }
        val g = GuidedFilter.filter(noisy, w, h, radius = 4, eps = 0.05)

        fun stdOf(a: ByteArray, x0: Int, x1: Int): Double {
            var m = 0.0; var n = 0
            for (y in 10 until h - 10) for (x in x0 until x1) { m += a[y * w + x].toInt() and 0xFF; n++ }
            m /= n
            var s = 0.0
            for (y in 10 until h - 10) for (x in x0 until x1) {
                val d = (a[y * w + x].toInt() and 0xFF) - m; s += d * d
            }
            return sqrt(s / n)
        }
        val sIn = stdOf(noisy, 5, 25)
        val sOut = stdOf(g, 5, 25)
        check("导向滤波-平坦区降噪", sOut < sIn * 0.55, "σ ${"%.1f".format(sIn)}→${"%.1f".format(sOut)}")

        // 边缘保留：跨边缘差 > 100
        val left = g[(32 * w) + 20].toInt() and 0xFF
        val right = g[(32 * w) + 43].toInt() and 0xFF
        check("导向滤波-边缘保留", right - left > 100, "左=$left 右=$right")

        // 无过冲：输出不超出输入全域范围
        var mn = 255; var mx = 0
        for (v in noisy) { val x = v.toInt() and 0xFF; if (x < mn) mn = x; if (x > mx) mx = x }
        var outMin = 255; var outMax = 0
        for (v in g) { val x = v.toInt() and 0xFF; if (x < outMin) outMin = x; if (x > outMax) outMax = x }
        check("导向滤波-无过冲", outMin >= mn && outMax <= mx, "输入[$mn,$mx] 输出[$outMin,$outMax]")

        // 半径无关一致性：r=2 与 r=12 都能保边
        val g2 = GuidedFilter.filter(noisy, w, h, radius = 2, eps = 0.05)
        val g12 = GuidedFilter.filter(noisy, w, h, radius = 12, eps = 0.05)
        val d2 = (g2[(32 * w) + 43].toInt() and 0xFF) - (g2[(32 * w) + 20].toInt() and 0xFF)
        val d12 = (g12[(32 * w) + 43].toInt() and 0xFF) - (g12[(32 * w) + 20].toInt() and 0xFF)
        check("导向滤波-半径无关保边", d2 > 100 && d12 > 100, "r2=$d2 r12=$d12")
    }

    // ═══════════ C. 色相环形统计（§2.6） ═══════════
    run {
        val mean = Hsv.hueMean(doubleArrayOf(359.0, 1.0))
        check("色相-向量平均跨0", abs(mean) < 1.0 || abs(mean - 360.0) < 1.0, "mean=${"%.2f".format(mean)}°")
        val naive = (359.0 + 1.0) / 2.0
        check("色相-算术平均会错", abs(naive - 180.0) < 0.1, "算术平均=${naive}° (反例)")

        check("色相-环形距离", abs(Hsv.hueDistance(350.0, 10.0) - 20.0) < 1e-9,
            "d=${Hsv.hueDistance(350.0, 10.0)}°")
        check("色相-带内0回绕", Hsv.inHueBand(5.0, 345f, 40f), "H=5 ∈[345,40]")
        check("色相-带外", !Hsv.inHueBand(45.0, 345f, 40f), "H=45 ∉[345,40]")
        check("色相-集中度", Hsv.hueConcentration(doubleArrayOf(359.0, 1.0)) > 0.99,
            "R=${"%.4f".format(Hsv.hueConcentration(doubleArrayOf(359.0, 1.0)))}")

        // 掩码一致性：红命中、绿不命中
        val w = 8; val h = 8
        val redImg = ByteArray(w * h * 3) { i -> if (i % 3 == 0) 200.toByte() else 30.toByte() }
        val greenImg = ByteArray(w * h * 3) { i ->
            when (i % 3) { 0 -> 30.toByte(); 1 -> 200.toByte(); else -> 60.toByte() }
        }
        val redHit = (Hsv.redMask(redImg, w, h)[0].toInt() and 0xFF) == 255
        val greenHit = (Hsv.redMask(greenImg, w, h)[0].toInt() and 0xFF) == 255
        check("掩码-红色命中/绿色不命中", redHit && !greenHit, "red=$redHit green=$greenHit")
    }

    // ═══════════ D. NMS（§11.2） ═══════════
    run {
        val a = intArrayOf(10, 10, 50, 50)
        val b = intArrayOf(10, 10, 50, 50)
        check("NMS-IoU自重合=1", abs(Nms.iou(a, b) - 1.0) < 1e-9, "IoU=${Nms.iou(a, b)}")
        check("NMS-IoU不相交=0", Nms.iou(a, intArrayOf(60, 60, 90, 90)) == 0.0, "")

        data class Box(val s: Double, val r: IntArray)
        val near = listOf(Box(0.9, intArrayOf(10, 10, 50, 50)), Box(0.8, intArrayOf(14, 12, 54, 52)))
        val keptNear = Nms.greedy(near, 0.45, { it.s }, { it.r })
        check("NMS-重叠只留高分", keptNear.size == 1 && keptNear[0].s == 0.9,
            "保留 ${keptNear.size} 个，最高分 ${keptNear.firstOrNull()?.s}")

        val far = listOf(Box(0.9, intArrayOf(10, 10, 50, 50)), Box(0.7, intArrayOf(200, 200, 240, 240)))
        val keptFar = Nms.greedy(far, 0.45, { it.s }, { it.r })
        check("NMS-分离目标都保留", keptFar.size == 2, "保留 ${keptFar.size} 个")
    }

    // ═══════════ E. 结构张量（§7.4/§7.5） ═══════════
    run {
        val w = 40; val h = 40
        // 垂直边缘（灰度只随 x 变化）→ 梯度沿 x，秩 1
        val edge = ByteArray(w * h) { i -> (if ((i % w) < w / 2) 40 else 220).toByte() }
        val t = StructureTensor.compute(edge, w, h, 20.0, 20.0, radius = 5)
        check("结构张量-边缘相干度", t.coherence > 0.9, "C=${"%.3f".format(t.coherence)}")
        check("结构张量-边缘主方向≈0°", abs(t.orientationDeg) < 3.0 || abs(abs(t.orientationDeg) - 90.0) < 3.0,
            "θ=${"%.1f".format(t.orientationDeg)}°")
        check("结构张量-边缘各向异性", t.anisotropy > 0.9, "1-λ2/λ1=${"%.3f".format(t.anisotropy)}")

        val flat = ByteArray(w * h) { 128.toByte() }
        val tf = StructureTensor.compute(flat, w, h, 20.0, 20.0, radius = 5)
        check("结构张量-平坦区", tf.shape() == StructureTensor.Shape.FLAT,
            "λ1=${"%.4f".format(tf.lambda1)} shape=${tf.shape()}")

        // 双角平均：1° 与 179° 是同一走向，平均应为 0°（算术平均=90°，错）
        val m = StructureTensor.meanOrientationPeriodPi(doubleArrayOf(1.0, 179.0))
        check("双角平均-跨±90不跳变", angDist180(m, 0.0) < 2.0, "mean=${"%.2f".format(m)}° 算术=90°")
        val m2 = StructureTensor.meanOrientationPeriodPi(doubleArrayOf(80.0, -85.0))
        check("双角平均-近垂直", angDist180(m2, 87.5) < 5.0, "mean=${"%.2f".format(m2)}° 算术=-2.5°")
        check("双角平均-一致性", StructureTensor.orientationConsistency(doubleArrayOf(10.0, 10.0, 10.0)) > 0.999,
            "R=${"%.4f".format(StructureTensor.orientationConsistency(doubleArrayOf(10.0, 10.0, 10.0)))}")
        check("双角平均-无主导", StructureTensor.orientationConsistency(doubleArrayOf(0.0, 45.0, 90.0, 135.0)) < 0.05,
            "R=${"%.4f".format(StructureTensor.orientationConsistency(doubleArrayOf(0.0, 45.0, 90.0, 135.0)))}")
    }

    // ═══════════ F. 亚像素尺寸精定位（§10.4） ═══════════
    run {
        // 轴对齐抗锯齿矩形：真实 40.4 × 25.3 @ (50.0, 50.0)
        val w = 100; val h = 100
        val imgA = renderSoftRect(w, h, 50.0, 50.0, 40.4, 25.3, 0.0)
        val rectA = MinAreaRect.Rect(50.0, 50.0, 40.0, 25.0, 0.0)  // 整数初值 ±0.5px 内
        val refA = SubpixelRect.refine(imgA, w, h, rectA)
        check("亚像素-轴对齐长边", abs(refA.length - 40.4) < 0.4,
            "len=${"%.2f".format(refA.length)} (真值 40.40)")
        check("亚像素-轴对齐短边", abs(refA.width - 25.3) < 0.4,
            "wid=${"%.2f".format(refA.width)} (真值 25.30)")

        // 旋转 30°：真实 40.4 × 25.3
        val imgB = renderSoftRect(w, h, 50.0, 50.0, 40.4, 25.3, 30.0)
        val rectB = MinAreaRect.Rect(50.0, 50.0, 40.0, 25.0, 30.0)
        val refB = SubpixelRect.refine(imgB, w, h, rectB)
        check("亚像素-旋转长边", abs(refB.length - 40.4) < 0.5,
            "len=${"%.2f".format(refB.length)} (真值 40.40)")
        check("亚像素-旋转短边", abs(refB.width - 25.3) < 0.5,
            "wid=${"%.2f".format(refB.width)} (真值 25.30)")

        // 整数矩形自身误差（对照）
        check("亚像素-优于整数初值", abs(refA.length - 40.4) < abs(40.0 - 40.4),
            "|亚像素误差|=${"%.2f".format(abs(refA.length - 40.4))} < |整数误差|=0.40")

        // 无边缘（全平坦）→ 回退原矩形，不变差
        val flat = ByteArray(w * h * 3) { 128.toByte() }
        val rectF = MinAreaRect.Rect(50.0, 50.0, 30.0, 20.0, 0.0)
        val refF = SubpixelRect.refine(flat, w, h, rectF)
        check("亚像素-无边缘回退", refF.length == rectF.length && refF.width == rectF.width,
            "len=${refF.length} wid=${refF.width}")
    }

    // ═══════════ G. 卡尔曼 NIS 门控（§1.7/§9） ═══════════
    run {
        val kf = KalmanFilterCV(dt = 0.1, processNoise = 4.0, measurementNoise = 2.0)
        var x = 100.0
        for (i in 0 until 25) {
            kf.predict()
            kf.update(x, 200.0)
            x += 2.0   // 匀速运动
        }
        check("NIS-正常观测不相容度低", kf.lastNis < KalmanFilterCV.GATE_99,
            "NIS=${"%.2f".format(kf.lastNis)} < 9.21")

        // 注入离群观测：偏离 400px
        val before = kf.position().first
        kf.predict()
        val accepted = kf.update(before + 400.0, 200.0)
        val after = kf.position().first
        check("NIS-离群观测被拒", !accepted, "accepted=$accepted NIS=${"%.1f".format(kf.lastNis)}")
        check("NIS-轨迹不被拽飞", abs(after - before) < 60.0,
            "跳变=${"%.1f".format(after - before)}px (无门控时应≈150px)")

        // 门控后仍能跟住正常观测
        for (i in 0 until 10) { kf.predict(); kf.update(after + 2.0 * (i + 1), 200.0) }
        val reacq = kf.position().first
        check("NIS-门控后仍可跟踪", abs(reacq - (after + 20.0)) < 30.0,
            "位置=${"%.1f".format(reacq)} 期望≈${"%.1f".format(after + 20.0)}")
    }

    // ═══════════ H. 测距不确定度传播 + 逆方差融合（§1.2/§1.7） ═══════════
    run {
        val d = 8000.0; val p = 7.5; val sp = 0.5
        val s = Pinhole.distanceSigma(d, p, sp)
        check("测距σ-一阶传播", abs(s - 533.33) < 1.0, "σ=${"%.1f".format(s)}mm (d/p·σp)")
        check("测距σ-相对误差=σp/p", abs(s / d - sp / p) < 1e-9,
            "σ/d=${"%.4f".format(s / d)} σp/p=${"%.4f".format(sp / p)}")

        // 8m 与 2m 的绝对误差对比
        val s8 = Pinhole.distanceSigma(8000.0, 7.5, 0.5)
        val s2 = Pinhole.distanceSigma(2000.0, 30.0, 0.5)
        check("测距σ-随距离平方增长", s8 / s2 > 15.0,
            "σ8m=${"%.0f".format(s8)}mm σ2m=${"%.0f".format(s2)}mm 比=${"%.1f".format(s8 / s2)}")

        val fused = Pinhole.fuse(8000.0, 800.0, 7600.0, 76.0)
        check("融合-雷达权重更大", fused.radarWeight > 0.85,
            "w_radar=${"%.3f".format(fused.radarWeight)}")
        check("融合-结果靠近雷达", abs(fused.distanceMm - 7600.0) < 120.0,
            "d=${"%.0f"}mm σ=${"%.0f"}mm".format(fused.distanceMm, fused.sigmaMm))
        check("融合-方差必减小", fused.sigmaMm < 800.0,
            "σ ${"%.0f"}→${"%.0f"}".format(800.0, fused.sigmaMm))

        val noRadar = Pinhole.fuse(8000.0, 800.0, -1.0)
        check("融合-无雷达退化为视觉", noRadar.distanceMm == 8000.0 && noRadar.radarWeight == 0.0,
            "d=${noRadar.distanceMm} w=${noRadar.radarWeight}")
    }

    // ═══════════ I. 开火决策接入不确定度 ═══════════
    run {
        // 远处小目标：p=1.0px、σ_p=0.35px → σ_d/d = 35% > 25% 阈值
        val far = FireController.compute(
            distanceMm = 7000.0, yawDeg = 0.2, pitchDeg = 0.2,
            nowMs = 10_000, pixelSize = 1.0, pixelSigmaPx = 0.35
        )
        check("开火-测距置信不足拒发", !far.shouldFire && far.reason.contains("置信"),
            "reason=${far.reason}")

        val near = FireController.compute(
            distanceMm = 3000.0, yawDeg = 0.2, pitchDeg = 0.2,
            nowMs = 10_000, pixelSize = 30.0, pixelSigmaPx = 0.2
        )
        check("开火-近距高置信允许", near.shouldFire, "reason=${near.reason}")
        check("开火-输出σ", near.distSigmaMm > 0 && near.distSigmaMm < 100,
            "σ=${"%.1f".format(near.distSigmaMm)}mm")
        // 冷却窗生效：同一时刻再算一次不应击发
        val again = FireController.compute(
            distanceMm = 3000.0, yawDeg = 0.2, pitchDeg = 0.2,
            nowMs = 10_100, pixelSize = 30.0, pixelSigmaPx = 0.2
        )
        check("开火-冷却窗抑制连发", !again.shouldFire, "reason=${again.reason}")
    }

    println("\n═══ 结果: $pass 通过, $fail 失败 ═══")
    if (fail > 0) kotlin.system.exitProcess(1)
}
