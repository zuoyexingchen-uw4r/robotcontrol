import com.robomaster.control.vision.preprocess.Deblur
import com.robomaster.control.vision.preprocess.Smoothing
import com.robomaster.control.vision.preprocess.Deglare
import kotlin.math.abs

var pass = 0
var fail = 0

fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) { pass++; println("PASS  $name  $detail") }
    else { fail++; println("FAIL  $name  $detail") }
}

fun main() {
    val w = 64; val h = 64
    val orig = ByteArray(w * h) { i -> (if ((i % w) < w / 2) 50 else 200).toByte() }

    // ═══ 测试 1：维纳反卷积往返（模糊 → 反卷积 → 接近原图）═══
    val blurred = ByteArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var s = 0
            for (dx in -1..1) {
                val nx = (x + dx).coerceIn(0, w - 1)
                s += orig[y * w + nx].toInt() and 0xFF
            }
            blurred[y * w + x] = (s / 3).toByte()
        }
    }
    val restored = Deblur.wienerDeconvolve(blurred, w, h, kernelLength = 3, angleDeg = 0.0, eta = 0.01)
    var errSum = 0.0; var errCnt = 0
    for (y in 10 until h - 10) {
        for (x in 10 until w / 2 - 5) {
            val d = abs((restored[y * w + x].toInt() and 0xFF) - 50)
            errSum += d; errCnt++
        }
    }
    val mae = errSum / errCnt
    check("维纳反卷积-平坦区恢复", mae < 25.0, "MAE=%.2f (期望<25)".format(mae))

    // 边缘锐化：取边缘两侧 1px（3px 均值模糊会把 150 梯度抹到 ~33）
    fun edgeSharpness1px(img: ByteArray): Double {
        val y = h / 2
        val left = (img[y * w + w / 2 - 1].toInt() and 0xFF)
        val right = (img[y * w + w / 2].toInt() and 0xFF)
        return abs(right - left).toDouble()
    }
    val sharpOrig = edgeSharpness1px(orig)
    val sharpBlur = edgeSharpness1px(blurred)
    val sharpRest = edgeSharpness1px(restored)
    check("维纳反卷积-边缘锐化", sharpRest > sharpBlur * 1.5,
        "原图梯度=$sharpOrig 模糊=$sharpBlur 恢复=$sharpRest")

    // ═══ 测试 2：双边滤波-平坦区平滑 + 边缘保留 ═══
    val noisy = ByteArray(w * h)
    val rng = java.util.Random(42)
    for (i in noisy.indices) {
        val base = if ((i % w) < w / 2) 50 else 200
        noisy[i] = (base + rng.nextInt(21) - 10).coerceIn(0, 255).toByte()
    }
    val smooth = Smoothing.bilateral(noisy, w, h, radius = 2, sigmaSpace = 2.5, sigmaColor = 30.0)
    fun variance(img: ByteArray, x0: Int, x1: Int): Double {
        var s = 0.0; var s2 = 0.0; var n = 0
        for (y in 5 until h - 5) for (x in x0 until x1) {
            val v = (img[y * w + x].toInt() and 0xFF).toDouble()
            s += v; s2 += v * v; n++
        }
        val m = s / n
        return s2 / n - m * m
    }
    val varNoisy = variance(noisy, 5, w / 2 - 5)
    val varSmooth = variance(smooth, 5, w / 2 - 5)
    check("双边滤波-平坦区降噪", varSmooth < varNoisy * 0.5,
        "方差 噪声=%.1f 平滑=%.1f".format(varNoisy, varSmooth))
    val sharpSmooth = edgeSharpness1px(smooth)
    check("双边滤波-边缘保留", sharpSmooth > sharpOrig * 0.7,
        "梯度 原图=$sharpOrig 平滑=$sharpSmooth")

    // ═══ 测试 3：去辉光-过曝修复 ═══
    val glare = ByteArray(w * h) { 100 }
    for (y in h / 2 - 5 until h / 2 + 5) {
        for (x in w / 2 - 5 until w / 2 + 5) {
            glare[y * w + x] = 255.toByte()
        }
    }
    val fixed = Deglare.process(glare, w, h, threshold = 250)
    val centerVal = fixed[h / 2 * w + w / 2].toInt() and 0xFF
    check("去辉光-光斑修复", abs(centerVal - 100) < 10, "中心值=$centerVal (期望≈100)")
    val cornerVal = fixed[0].toInt() and 0xFF
    check("去辉光-非过曝区不变", cornerVal == 100, "角点值=$cornerVal")

    // ═══ 测试 4：大光斑兜底（整片过曝）═══
    val allGlare = ByteArray(w * h) { 255.toByte() }
    val fixed2 = Deglare.process(allGlare, w, h, threshold = 250)
    val v2 = fixed2[0].toInt() and 0xFF
    check("去辉光-全过曝兜底", v2 in 100..160, "值=$v2 (期望≈128)")

    println("\n═══ 结果: $pass 通过, $fail 失败 ═══")
    if (fail > 0) kotlin.system.exitProcess(1)
}
