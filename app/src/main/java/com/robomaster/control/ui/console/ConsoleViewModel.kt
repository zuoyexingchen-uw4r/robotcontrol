package com.robomaster.control.ui.console

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings as AndroidSettings
import android.view.Surface
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.robomaster.control.comm.ConnState
import com.robomaster.control.laser.LaserBridgeService
import com.robomaster.control.laser.RobotSlots
import com.robomaster.control.net.NetCore
import com.robomaster.control.net.Pairing
import com.robomaster.control.net.UartLink
import com.robomaster.control.ui.Settings
import com.robomaster.control.ui.VisionSettings
import com.robomaster.control.video.CameraStreamer
import com.robomaster.control.video.EncoderMode
import com.robomaster.control.vision.VisionCamera
import com.robomaster.control.vision.VisionPipeline
import com.robomaster.control.vision.VisionUdpLink
import com.robomaster.control.vision.decide.FireController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条运行日志 */
data class LogLine(val time: String, val text: String)

/** 控制台界面状态：界面只读，所有动作走 ViewModel */
data class ConsoleState(
    // ── 连接配置（与 SharedPreferences 双向同步）──
    val host: String = "",
    val videoPort: String = "47000",
    val baud: String = "230400",
    val serialVid: String = "0x1A86",
    val serialPid: String = "0x55D3",
    val bitrate: String = "",
    val radarBatchPercent: Float = 0.4f,
    val resolution: String? = null,
    val fps: String? = null,
    val codec: String? = null,
    val encoder: String? = null,
    val fecMode: String? = null,
    val netMode: String? = null,
    val adaptiveKf: Boolean = true,
    val previewOn: Boolean = true,
    val forceSerial: Boolean = false,
    val slotIndex: Int = 0,

    // ── 可选值（来自相机 / 编码器真实能力，不是写死的样例）──
    val resolutionOptions: List<String> = emptyList(),
    val fpsOptions: List<String> = emptyList(),
    val capsError: String? = null,

    // ── 运行状态 ──
    val streaming: Boolean = false,
    val laserRunning: Boolean = false,
    val visionRunning: Boolean = false,
    val logs: List<LogLine> = emptyList(),

    // ── 实时读数 ──
    val upKbps: Float = 0f,
    val downKbps: Float = 0f,
    val rttMs: Int = 0,
    val linkMode: String = "—",
    val fpsLive: Float = 0f,
    val frameW: Int = 0,
    val frameH: Int = 0,

    // ── 设备自身信息（能拿就拿）──
    val batteryTempC: Float? = null,
    val batteryLevel: Int? = null,
    val cpuTempC: Float? = null,
    val accessibilityOn: Boolean = false,
    val adbOn: Boolean = false,

    // ── 扫码连接（复用视觉码流，不抢摄像头）──
    val scanning: Boolean = false,
    val scanMsg: String? = null,
    val scanApplied: Boolean = false,
) {
    /** 已选项的下标（槽位按钮组用） */
    val slotLabels: List<String> get() = RobotSlots.SLOTS.map { it.robotId.uppercase() }
}

/**
 * 控制台视图模型：把界面上的每个对象接到真实模块。
 *
 * | 界面对象 | 背后的东西 |
 * |---|---|
 * | 日志容器 | [com.robomaster.control.ui.MainViewModel] 不再使用，改为本类聚合各路日志 |
 * | 预览容器 | [VisionCamera] 第二路码流 + SurfaceView |
 * | 扫描连接码 | [Pairing] 编解码 + 落盘 |
 * | 槽位按钮组 | [RobotSlots] + `switch_ip` 广播 |
 * | 雷达分包滑块 | [Settings.netBatch]（1/10/20 三档） |
 * | 分辨率/帧率/编码/编码器/纠错 | [CameraStreamer.caps] 与 [CameraStreamer.Config] |
 * | 码率 / 空闲补关键帧 | Config.bitrate / adaptiveKeyframe |
 * | 网络协议层模式 | [UartLink.Mode] |
 * | 强制接管串口 | [Settings.serialForceTakeover] + 串口桥重启 |
 * | 上行/下行/延迟 | 编码器统计 + [UartLink.Stats] |
 * | 电池/CPU 温度/电量 | BatteryManager + thermal_zone |
 * | 无障碍 / ADB 状态 | AccessibilityManager + Settings.Global |
 */
class ConsoleViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx: Context = app.applicationContext
    private val settings = Settings.get(ctx)
    private val visionSettings = VisionSettings.get(ctx)
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    var state by mutableStateOf(ConsoleState())
        private set

    /**
     * 本地预览画面：从**视觉链路的 YUV 帧**复制一份下来，不在图传会话里挂第二个 target。
     *
     * 这样图传会话只剩编码器（与旧版 RobotControl 的配置一致，推流方向由 HAL 保证），
     * 预览和机器视觉共用同一路相机帧，不会多占一路相机会话。
     */
    var previewFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    private var previewTick = 0

    /** 扫码状态：为 true 时视觉会话的每一帧都会顺带解一次二维码（节流 [SCAN_EVERY]） */
    @Volatile
    private var scanning = false
    private var scanTick = 0

    /** 扫码结果回主线程的开关：识别成功后不再重复解，等主线程落盘 */
    @Volatile
    private var scanHit = false

    /** 一次性提示（Snackbar / Toast 都由界面决定怎么显示） */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** 界面注册：需要申请相机权限时回调 */
    var onRequestCameraPermission: (() -> Unit)? = null

    // ── 运行期对象 ──
    private var streamer: CameraStreamer? = null
    private var currentDevice: CameraDevice? = null
    private var visionCamera: VisionCamera? = null
    private var visionPipeline: VisionPipeline? = null
    private var visionThread: HandlerThread? = null
    private var laserRunning = false

    /**
     * 视觉结果 + 设置快照上行（v0.7 起走非图传流的长帧 JSON，与激光桥共用一条链路）。
     *
     * 这块不能省：上位机（pc_hud / spaa）靠它画检测框、做设置双向同步。
     * 界面重做时最容易漏掉的就是"视觉算完了但没人发出去"。
     */
    private var visionLink: VisionUdpLink? = null

    /** 第二路会话看门狗：4s 未就绪就关掉（真机并发受限时避免白占相机资源） */
    private var visionWatchdog: Job? = null


    // 下行码率：按 60B 槽数估算（rxPackets 每秒增量 × 槽长）
    private var lastRxPackets = 0L
    private var statsLink: UartLink? = null

    /** 非图传流统计（1Hz）：延迟、下行速率、当前承载 */
    private val statsListener: (UartLink.Stats) -> Unit = { st ->
        val deltaPackets = (st.rxPackets - lastRxPackets).coerceAtLeast(0)
        lastRxPackets = st.rxPackets
        viewModelScope.launch(Dispatchers.Main.immediate) {
            state = state.copy(
                rttMs = st.rttMs,
                downKbps = deltaPackets * 60f * 8f / 1000f,
                linkMode = st.mode,
            )
        }
    }

    private val mainHandler = Handler(android.os.Looper.getMainLooper())

    init {
        // 非图传流端口必须先注入：链路一旦按默认 48000/49000 建起来，改配置就要重开
        NetCore.configure(settings.netUdpPort, settings.netTcpPort)
        loadFromSettings()
        loadCameraCaps()
        startStatsTicker()
        attachNetStats()
        visionLink = VisionUdpLink(visionSettings, host = { settings.host }) { msg -> log(msg) }
            .also { it.start() }
        log("控制台就绪：${state.host.ifBlank { "未配置上位机 IP" }}")
    }

    // ───────────────────────── 初始化 ─────────────────────────

    private fun loadFromSettings() {
        val resLabel = if (settings.resW > 0 && settings.resH > 0) "${settings.resW}×${settings.resH}" else null
        state = state.copy(
            host = settings.host,
            videoPort = settings.videoPort.toString(),
            baud = settings.laserBaud.toString(),
            serialVid = "0x%04X".format(settings.serialVid),
            serialPid = "0x%04X".format(settings.serialPid),
            bitrate = if (settings.bitrateKbps > 0) settings.bitrateKbps.toString() else "",
            radarBatchPercent = batchToPercent(settings.netBatch),
            resolution = resLabel,
            fps = "${settings.fps} fps",
            codec = CODEC_OPTIONS.getOrNull(settings.codecIndex),
            encoder = ENCODER_OPTIONS.getOrNull(settings.encoderMode),
            fecMode = FEC_OPTIONS.minByOrNull { kotlin.math.abs(fecPercent(it) - settings.fec) },
            netMode = if (settings.netModeChosen) NET_MODE_OPTIONS.getOrNull(settings.netMode) else null,
            adaptiveKf = settings.adaptiveKf,
            previewOn = settings.previewOn,
            forceSerial = settings.serialForceTakeover,
            slotIndex = RobotSlots.SLOTS.indexOfFirst { it.robotId == settings.robotId }.coerceAtLeast(0),
        )
    }

    /** 相机真实能力（分辨率 ∩ 编码器支持、AE 帧率档），枚举失败时给常用档位兜底 */
    private fun loadCameraCaps() {
        viewModelScope.launch {
            val caps = withContext(Dispatchers.IO) {
                runCatching { CameraStreamer(cm, viewModelScope).caps("0") }.getOrNull()
            }
            if (caps == null || caps.sizes.isEmpty()) {
                state = state.copy(
                    resolutionOptions = FALLBACK_RES,
                    fpsOptions = FALLBACK_FPS,
                    capsError = "相机能力枚举失败，使用常用档位",
                )
                log("[相机] 能力枚举失败，分辨率使用常用档位")
            } else {
                val res = caps.sizes.map { "${it.width}×${it.height}" }
                val fps = caps.fpsRanges.map { if (it.lower == it.upper) "${it.upper} fps" else "${it.lower}-${it.upper} fps" }
                    .distinct()
                state = state.copy(resolutionOptions = res, fpsOptions = fps, capsError = null)
                log("[相机] 支持 ${res.size} 种分辨率、${fps.size} 档帧率")
                // 之前没选过分辨率：默认取最接近 1280×720 的一档，避免"没有可选值"
                if (state.resolution == null || state.resolution !in res) {
                    val best = caps.sizes.minByOrNull { kotlin.math.abs(it.width - 1280) + kotlin.math.abs(it.height - 720) }
                    if (best != null) selectResolution("${best.width}×${best.height}", persist = true, quiet = true)
                }
            }
        }
    }

    /** 1Hz：电池 / CPU 温度 / 无障碍 / ADB（读文件放 IO 线程，结果回主线程） */
    private fun startStatsTicker() {
        viewModelScope.launch {
            while (isActive) {
                val snap = withContext(Dispatchers.IO) { readDeviceSnapshot() }
                state = state.copy(
                    batteryTempC = snap.batteryTempC,
                    batteryLevel = snap.batteryLevel,
                    cpuTempC = snap.cpuTempC,
                    accessibilityOn = snap.accessibilityOn,
                    adbOn = snap.adbOn,
                )
                attachNetStats()   // 串口桥可能后启动/重启，跟着 NetCore 走
                delay(1000)
            }
        }
    }

    private data class DeviceSnapshot(
        val batteryTempC: Float?,
        val batteryLevel: Int?,
        val cpuTempC: Float?,
        val accessibilityOn: Boolean,
        val adbOn: Boolean,
    )

    private fun readDeviceSnapshot(): DeviceSnapshot {
        val battery = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val tempC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.div(10f)
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null

        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val accessibilityOn = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo?.serviceInfo?.packageName == ctx.packageName } == true

        val adbOn = runCatching {
            AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) == 1
        }.getOrDefault(false)

        return DeviceSnapshot(
            batteryTempC = tempC,
            batteryLevel = pct,
            cpuTempC = readCpuTemp(),
            accessibilityOn = accessibilityOn,
            adbOn = adbOn,
        )
    }

    /** CPU 温度：取 thermal_zone 里 type 含 cpu/soc 的最大值（拿不到就显示 —） */
    private fun readCpuTemp(): Float? {
        val dir = File("/sys/class/thermal")
        val zones = dir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        var best: Float? = null
        for (z in zones) {
            val type = runCatching { File(z, "type").readText().trim().lowercase() }.getOrNull() ?: continue
            if (!(type.contains("cpu") || type.contains("soc") || type.contains("ap"))) continue
            val raw = runCatching { File(z, "temp").readText().trim().toFloat() }.getOrNull() ?: continue
            val c = if (raw > 1000f) raw / 1000f else raw
            if (c in 1f..150f) best = maxOf(best ?: 0f, c)
        }
        return best
    }

    /** 订阅非图传流统计（激光桥可能晚于界面启动，所以跟着 NetCore 走） */
    private fun attachNetStats() {
        val l = NetCore.current() ?: return
        if (l === statsLink) return
        statsLink?.removeStatsListener(statsListener)
        statsLink = l
        l.addStatsListener(statsListener)
    }

    // ───────────────────────── 连接参数 ─────────────────────────

    /**
     * IP 输入：中文输入法很容易把 `.` 打成全角「。」/「．」，直接判错会让人以为框坏了。
     * 这里做一次规范化（全角点、顿号、逗号、空格 → 半角点/删除），只保留数字与点。
     */
    fun onHostChange(v: String) {
        val cleaned = buildString {
            for (ch in v) {
                when (ch) {
                    '。', '．', '，', '、', '·' -> append('.')
                    in '0'..'9', '.' -> append(ch)
                    ' ', '\t' -> Unit
                    else -> Unit
                }
            }
        }
        state = state.copy(host = cleaned)
        settings.host = cleaned.trim()
    }

    fun onVideoPortChange(v: String) {
        if (v.any { !it.isDigit() }) return
        state = state.copy(videoPort = v)
        v.toIntOrNull()?.let {
            if (it in 1..65535) {
                // 视频与控制是同一个端口号（UDP/TCP 端口空间独立），必须一起改，
                // 否则会出现「视频发 48000、控制连 47000」的裂开状态。
                settings.videoPort = it
                settings.controlPort = it
            }
        }
    }

    /** 图传端口不能和非图传流端口相同：上位机那边同一端口只能有一个监听。 */
    fun isReservedPort(p: Int): Boolean =
        p == settings.netUdpPort || p == settings.netTcpPort

    fun reservedPortsText(): String = "${settings.netUdpPort}/${settings.netTcpPort}"

    fun onBaudChange(v: String) {
        if (v.any { !it.isDigit() }) return
        state = state.copy(baud = v)
        v.toIntOrNull()?.let { if (it in 1200..6_000_000) settings.laserBaud = it }
    }

    fun onVidChange(v: String) {
        state = state.copy(serialVid = v)
        parseHex(v)?.let { settings.serialVid = it }
    }

    fun onPidChange(v: String) {
        state = state.copy(serialPid = v)
        parseHex(v)?.let { settings.serialPid = it }
    }

    fun onBitrateChange(v: String) {
        if (v.any { !it.isDigit() }) return
        state = state.copy(bitrate = v)
        settings.bitrateKbps = v.toIntOrNull() ?: 0
    }

    /** 雷达分包滑块：0..1 → 1/10/20 槽（40% 落在 10 槽档） */
    fun onRadarBatchChange(p: Float) {
        state = state.copy(radarBatchPercent = p)
        val batch = percentToBatch(p)
        settings.netBatch = batch
        NetCore.current()?.batch = batch
    }

    fun selectResolution(label: String, persist: Boolean = true, quiet: Boolean = false) {
        state = state.copy(resolution = label)
        if (persist) {
            val parts = label.split("×")
            val w = parts.getOrNull(0)?.toIntOrNull()
            val h = parts.getOrNull(1)?.toIntOrNull()
            if (w != null && h != null) {
                settings.resW = w
                settings.resH = h
                state = state.copy(frameW = w, frameH = h)
            }
        }
        if (!quiet) log("[视频] 分辨率 $label")
    }

    fun selectFps(label: String) {
        state = state.copy(fps = label)
        label.substringBefore(' ').toIntOrNull()?.let { settings.fps = it }
        log("[视频] 帧率 $label")
    }

    fun selectCodec(label: String) {
        state = state.copy(codec = label)
        settings.codecIndex = CODEC_OPTIONS.indexOf(label).coerceAtLeast(0)
    }

    fun selectEncoder(label: String) {
        state = state.copy(encoder = label)
        settings.encoderMode = ENCODER_OPTIONS.indexOf(label).coerceAtLeast(0)
    }

    fun selectFec(label: String) {
        state = state.copy(fecMode = label)
        settings.fec = fecPercent(label)
    }

    fun selectNetMode(label: String) {
        state = state.copy(netMode = label)
        val mode = when (label) {
            NET_MODE_OPTIONS[1] -> 1
            NET_MODE_OPTIONS[2] -> 2
            else -> 0
        }
        settings.netMode = mode
        settings.netModeChosen = true
        NetCore.current()?.mode = when (mode) {
            1 -> UartLink.Mode.UDP
            2 -> UartLink.Mode.TCP
            else -> UartLink.Mode.AUTO
        }
        log("[网络] 协议层模式 → $label")
    }

    /** 预览旋转角（度，0/90/180/270） */
    val previewRotation: Int get() = settings.previewRotation

    /** 循环切换预览旋转：0 → 90 → 180 → 270，下次出帧立即生效 */
    fun rotatePreview() {
        settings.previewRotation = settings.previewRotation + 90
        log("[预览] 旋转 → ${settings.previewRotation}°")
    }

    fun togglePreview(on: Boolean) {
        state = state.copy(previewOn = on)
        settings.previewOn = on
        log("[预览] ${if (on) "开" else "关"}画面预览")
        if (!on) previewFrame = null
        // 预览来自视觉会话：只重建这一路，不动图传编码
        if (state.streaming) restartVisionSession()
    }

    fun toggleAdaptiveKf(on: Boolean) {
        state = state.copy(adaptiveKf = on)
        settings.adaptiveKf = on
        log("[视频] 空闲补关键帧 ${if (on) "开" else "关"}")
    }

    fun toggleForceSerial(on: Boolean) {
        state = state.copy(forceSerial = on)
        settings.serialForceTakeover = on
        log("[串口] 强制接管 ${if (on) "开（忽略 VID/PID）" else "关（按 VID/PID 匹配）"}")
        if (laserRunning) restartLaserBridge()
    }

    // ───────────────────────── 槽位 / 配对 ─────────────────────────

    fun selectSlot(index: Int) {
        val slot = RobotSlots.SLOTS.getOrNull(index) ?: return
        state = state.copy(slotIndex = index)
        settings.robotId = slot.robotId
        settings.robotIp = slot.ip
        log("[槽位] ${slot.slot} ${slot.label} → ${slot.ip}（${slot.robotId}）")
        broadcastSwitchIp(slot.robotId)
        if (state.streaming) {
            stopStream()
            startStream()
        }
    }

    // ───────────────────────── 扫码连接 ─────────────────────────

    /**
     * 开始扫码。
     *
     * 相机只有一路：图传/视觉会话一旦起来，任何"另开扫码页"都抢不到 camera。
     * 所以这里不新开会话，而是让**视觉会话**的每一帧顺带解一次二维码
     * （[com.robomaster.control.net.QrFromVision] 直接读 Y 平面）。
     */
    fun startScan() {
        if (!state.visionRunning) {
            state = state.copy(
                scanning = false,
                scanApplied = false,
                scanMsg = "视觉码流没在跑：先点「启动图传」，扫码会复用这一路相机，不会另抢摄像头",
            )
            log("[扫码] 视觉码流未启动，无法扫码")
            return
        }
        scanning = true
        scanHit = false
        scanTick = 0
        state = state.copy(scanning = true, scanApplied = false, scanMsg = "把上位机二维码对准镜头…")
        log("[扫码] 开始从视觉码流识别二维码（每 $SCAN_EVERY 帧试一次）")
    }

    fun stopScan() {
        scanning = false
        scanHit = false
        state = state.copy(scanning = false, scanMsg = null)
    }

    /** 视觉线程识别到二维码后回主线程处理 */
    private fun onScanHit(text: String) {
        val err = applyPairingCode(text)
        scanning = false
        scanHit = false
        state = if (err == null) {
            log("[扫码] 识别成功，连接码已应用")
            state.copy(scanning = false, scanApplied = true, scanMsg = "已识别并应用：$text")
        } else {
            log("[扫码] $err")
            state.copy(scanning = false, scanApplied = false, scanMsg = "扫到了，但不是连接码：$err")
        }
    }

    /**
     * 恢复默认 DHCP：把本机静态 IP 交还路由器。
     *
     * 普通 App 改不了自己的 IP（Android 10+ 无权限），所以这里只做两件事：
     * 1. 清掉 App 记忆的静态 IP（`Settings.robotIp` 置空 = 该槽位不再钉死地址）；
     * 2. 广播 `switch_ip dhcp`，由电脑端 `scripts/switch_ip.sh --listen` 用 adb 执行
     *    `ip addr flush` + DHCP 重取（与该脚本切静态 IP 走同一条通道）。
     */
    fun restoreDhcp() {
        settings.robotIp = ""
        state = state.copy(scanMsg = null)
        log("[槽位] 恢复默认 DHCP：本机 IP 交给路由器分配")
        broadcastSwitchIp("dhcp")
    }

    /** 把切槽位指令广播给 PC 端 switch_ip.sh --listen（8769） */
    private fun broadcastSwitchIp(robotId: String) {        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                java.net.DatagramSocket().use { ds ->
                    ds.broadcast = true
                    val payload = "switch_ip $robotId".toByteArray(Charsets.UTF_8)
                    ds.send(
                        java.net.DatagramPacket(
                            payload, payload.size,
                            java.net.InetAddress.getByName("255.255.255.255"), 8769,
                        )
                    )
                }
                log("[槽位] 已通知 PC 脚本切换 IP")
            }.onFailure { log("[槽位] 通知 PC 失败：${it.message}") }
        }
    }

    /**
     * 应用一段连接码（扫码结果或手填）。
     *
     * @return null 表示成功，否则返回错误说明（界面据此提示并保持输入框内容）
     */
    fun applyPairingCode(code: String, slotHint: Int? = null): String? {
        val info = Pairing.parse(code)
        if (info == null) {
            return "连接码格式不对：需要包含合法 IPv4 的配对 JSON（HOST / IP / PORT / 槽位）"
        }
        val ok = Pairing.apply(ctx, info)
        if (!ok) return "连接码里的 IP 不合法"
        loadFromSettings()
        visionLink?.stop()
        visionLink = VisionUdpLink(visionSettings, host = { settings.host }) { msg -> log(msg) }
            .also { it.start() }
        if (slotHint != null) selectSlot(slotHint)
        log("[配对] 已写入 ${info.ip}:${info.port} 槽位 ${info.slot}（${info.host}）")
        return null
    }

    /** 生成当前配置的连接码，供另一台设备扫 */
    fun currentPairingCode(): String {
        val slot = RobotSlots.SLOTS.getOrNull(state.slotIndex)?.slot ?: "R1"
        return Pairing.encode(
            com.robomaster.control.net.PairInfo(
                host = state.host.ifBlank { "langya-host" },
                ip = state.host.ifBlank { "0.0.0.0" },
                port = state.videoPort.toIntOrNull() ?: 47000,
                slot = slot,
            )
        )
    }

    // ───────────────────────── 图传 ─────────────────────────

    fun toggleStream() {
        if (state.streaming) stopStream() else startStream()
    }

    fun startStream() {
        if (state.streaming) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            messages.tryEmit("需要相机权限才能启动图传")
            onRequestCameraPermission?.invoke()
            return
        }
        val host = state.host.trim()
        if (host.isBlank()) {
            messages.tryEmit("请先填写上位机 IP（或扫描连接码）")
            return
        }
        val res = state.resolution ?: run {
            messages.tryEmit("请先选择视频分辨率")
            return
        }
        val wh = res.split("×")
        val w = wh.getOrNull(0)?.toIntOrNull() ?: 0
        val h = wh.getOrNull(1)?.toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) {
            messages.tryEmit("分辨率无效")
            return
        }
        val fps = (state.fps ?: "${settings.fps} fps").substringBefore(' ').substringAfter('-').toIntOrNull()
            ?: settings.fps.coerceAtLeast(15)
        val codecMime = if (settings.codecIndex == 1) "video/hevc" else "video/avc"
        val bitrate = state.bitrate.toIntOrNull()?.takeIf { it > 0 }?.times(1000)
            ?: (w.toLong() * h * fps / 10).coerceIn(1_000_000, 40_000_000).toInt()
        val mode = when (settings.encoderMode) {
            1 -> EncoderMode.FORCE_HW
            2 -> EncoderMode.FORCE_SW
            else -> EncoderMode.AUTO_QUALCOMM
        }

        val vPort = state.videoPort.toIntOrNull() ?: 0
        if (vPort !in 1..65535) {
            messages.tryEmit("图传端口不合法：${state.videoPort}")
            return
        }
        if (isReservedPort(vPort)) {
            // 48000/49000 已经被非图传流占用，图传再抢同一个号，上位机只能绑上一个。
            val msg = "图传端口 $vPort 与非图传流冲突，请改用 47000（或在上位机 F2 里改非图传端口）"
            messages.tryEmit(msg)
            log("[图传] $msg")
            return
        }

        val cfg = CameraStreamer.Config(
            cameraId = "0",
            width = w,
            height = h,
            fps = fps,
            bitrate = bitrate,
            encoderMode = mode,
            codecMime = codecMime,
            fecPercentage = settings.fec,
            hostIp = host,
            videoPort = vPort,
            controlPort = vPort,   // 与视频同号：图传控制不再单独维护一个端口
            adaptiveKeyframe = state.adaptiveKf,
            repeatCsd = settings.repeatCsd,
            onCameraReady = { device ->
                currentDevice = device
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    // 等图传 session 稳定（repeating request 已提交）再建第二路：
                    // 同时建会让 HAL 把先建的 session 关掉，导致图传起不来。
                    delay(900)
                    if (state.streaming) startVisionSession(device, w, h)
                }
            },
        )

        streamer = CameraStreamer(cm, viewModelScope).apply {
            onLog = { msg -> log(msg) }
            onStats = { f, k ->
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    state = state.copy(fpsLive = f, upKbps = k)
                }
            }
        }
        streamer?.start(cfg)
        // 视觉/设置上行也要有链路：init 时若还没填 IP 会被跳过，这里补一次（幂等）
        visionLink?.start()
        state = state.copy(streaming = true, frameW = w, frameH = h)
        log("[图传] 启动 ${w}×${h}@${fps} $codecMime 码率 ${bitrate / 1000}kbps → $host:$vPort")
    }

    fun stopStream() {
        visionWatchdog?.cancel()
        visionWatchdog = null
        visionCamera?.stop()
        visionCamera = null
        currentDevice = null
        streamer?.stop()
        streamer = null
        visionThread?.quitSafely()
        visionThread = null
        visionPipeline?.reset()
        visionPipeline = null
        state = state.copy(streaming = false, visionRunning = false, fpsLive = 0f, upKbps = 0f)
        previewFrame = null
        log("[图传] 已停止")
    }

    /**
     * 把一帧 YUV 转成预览位图：**按 [Settings.previewRotation] 旋转 + 居中裁 16:9 + 降采样**。
     * 相机 buffer 的方向随机型/安装方向不同（本机需要顺时针 90°），所以角度做成可切换。
     *
     * 只按目标像素采样（480×270），不做全图转换：15fps 下每 3 帧一次，
     * CPU 开销约 0.4ms/帧，比整帧 yuv420ToRgb（1080p 约 40ms）省两个数量级。
     */
    private fun buildPreviewBitmap(image: android.media.Image, w: Int, h: Int): ImageBitmap? = runCatching {
        val tw = PREVIEW_W
        val th = PREVIEW_H
        val argb = IntArray(tw * th)
        val yP = image.planes[0]
        val uP = image.planes[1]
        val vP = image.planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer
        val yRow = yP.rowStride
        val yPix = yP.pixelStride
        val uRow = uP.rowStride
        val uPix = uP.pixelStride
        val vRow = vP.rowStride
        val vPix = vP.pixelStride

        // 旋转后画面尺寸；裁出居中的 16:9 区域
        val rot = settings.previewRotation
        val rw = if (rot % 180 == 0) w.toFloat() else h.toFloat()
        val rh = if (rot % 180 == 0) h.toFloat() else w.toFloat()
        var cw = rw
        var ch = rw * 9f / 16f
        if (ch > rh) {
            ch = rh
            cw = rh * 16f / 9f
        }
        val cx0 = (rw - cw) / 2f
        val cy0 = (rh - ch) / 2f

        var idx = 0
        for (ty in 0 until th) {
            val ry = cy0 + (ty + 0.5f) * ch / th
            for (tx in 0 until tw) {
                val rx = cx0 + (tx + 0.5f) * cw / tw
                val rxi = rx.toInt()
                val ryi = ry.toInt()
                // 目标(旋转后)坐标 → 源坐标，按角度反向映射
                val sx: Int
                val sy: Int
                when (rot) {
                    0 -> { sx = rxi; sy = ryi }
                    90 -> { sx = ryi; sy = h - 1 - rxi }
                    180 -> { sx = w - 1 - rxi; sy = h - 1 - ryi }
                    else -> { sx = w - 1 - ryi; sy = rxi }
                }
                val sxC = sx.coerceIn(0, w - 1)
                val syC = sy.coerceIn(0, h - 1)
                val uvX = (sxC / 2).coerceIn(0, w / 2 - 1)
                val uvY = (syC / 2).coerceIn(0, h / 2 - 1)
                val yv = yBuf.get(syC * yRow + sxC * yPix).toInt() and 0xFF
                val uv = uBuf.get(uvY * uRow + uvX * uPix).toInt() and 0xFF
                val vv = vBuf.get(uvY * vRow + uvX * vPix).toInt() and 0xFF
                val c = yv - 16
                val d = uv - 128
                val e = vv - 128
                val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                argb[idx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        android.graphics.Bitmap
            .createBitmap(argb, tw, th, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()
    }.getOrNull()

    /**
     * 第二路码流（视觉会话）：YUV 帧既喂视觉管线、又降采样一份给界面当预览。
     * 只有「有视觉开关」或「开了画面预览」时才启动。
     */
    private fun startVisionSession(device: CameraDevice, w: Int, h: Int) {
        val visionOn = visionSettings.deblurEnabled || visionSettings.smoothingEnabled ||
            visionSettings.deglareEnabled || visionSettings.highlandTargetEnabled ||
            visionSettings.baseTargetEnabled || visionSettings.trackingEnabled ||
            visionSettings.autoFireEnabled || visionSettings.navigationEnabled
        if (!visionOn && !state.previewOn) {
            state = state.copy(visionRunning = false)
            return
        }
        if (visionOn && visionPipeline == null) {
            visionPipeline = VisionPipeline(visionSettings)
        }
        val thread = (visionThread ?: HandlerThread("vision-camera").also {
            it.start()
            visionThread = it
        })
        val handler = Handler(thread.looper)
        val pipeline = visionPipeline
        visionCamera = VisionCamera(
            device = device,
            width = w,
            height = h,
            fps = VISION_FPS,
            handler = handler,
            previewSurface = null,
            onFrame = { image ->
                // 扫码优先：视觉会话本来就在出帧，直接借 Y 平面解二维码，
                // 不新开摄像头 → 图传/视觉都不受影响（这就是"从视觉里取视频流"）。
                if (scanning && !scanHit) {
                    if (++scanTick % SCAN_EVERY == 0) {
                        val text = com.robomaster.control.net.QrFromVision.decode(image, w, h)
                        if (text != null) {
                            scanHit = true
                            mainHandler.post { onScanHit(text) }
                        }
                    }
                }
                // 预览：从同一帧 YUV 降采样 + 旋转 90° + 居中裁 16:9，每 3 帧更新一次
                if (state.previewOn) {
                    if (++previewTick % PREVIEW_EVERY == 0) {
                        buildPreviewBitmap(image, w, h)?.let { bmp ->
                            mainHandler.post { previewFrame = bmp }
                        }
                    }
                }
                if (pipeline == null) return@VisionCamera
                val rgb = VisionCamera.yuv420ToRgb(image, w, h)
                val result = runCatching { pipeline.process(rgb, w, h) }.getOrNull() ?: return@VisionCamera
                if (result.processed) {
                    // 只有真正重算的帧才上行，节流帧复用旧结果，避免虚高帧率与重复开火
                    visionLink?.sendVision(result, w, h, VISION_FPS.toFloat())
                    result.fireCommand?.takeIf { it.shouldFire }?.let { fc ->
                        sendVisionCommand(FireController.serialize(fc))
                        log("[视觉] 开火 ${fc.reason}")
                    }
                }
            },
            onReady = { visionWatchdog?.cancel() },
            onError = { msg -> log("[视觉] $msg") },
        )
        visionCamera?.start()
        state = state.copy(visionRunning = true, frameW = w, frameH = h)
        log("[视觉] 第二路码流 ${w}×${h}@${VISION_FPS}（仅分析，不带预览）")
        // 真机并发能力有限：4s 内没配好就认为这一路开不出来，关掉别占资源
        visionWatchdog?.cancel()
        visionWatchdog = viewModelScope.launch {
            delay(4000)
            if (visionCamera != null && state.visionRunning) {
                visionCamera?.stop()
                visionCamera = null
                state = state.copy(visionRunning = false)
                log("[视觉] 第二路 4s 未就绪（本机不支持双会话并发），视觉分析暂停")
            }
        }
    }

    private fun restartVisionSession() {
        visionCamera?.stop()
        visionCamera = null
        val device = currentDevice ?: return
        viewModelScope.launch(Dispatchers.Main.immediate) {
            delay(400)
            if (state.streaming) startVisionSession(device, state.frameW, state.frameH)
        }
    }

    /** 开火指令：优先走 USB 持有者（激光桥）的写入入口，避免抢 CH343 */
    private fun sendVisionCommand(bytes: ByteArray) {
        val writer = NetCore.uartWriter
        if (writer != null) {
            writer(bytes)
        } else {
            log("[视觉] 串口未接管，开火指令未下发")
        }
    }

    // ───────────────────────── 串口桥 / 重连 ─────────────────────────

    /** 强制接管串口时顺带把串口桥拉起（USB 唯一持有者） */
    fun startSerialBridge() {
        val host = state.host.trim()
        if (host.isBlank()) {
            messages.tryEmit("请先填写上位机 IP")
            return
        }
        NetCore.configure(settings.netUdpPort, settings.netTcpPort)
        LaserBridgeService.start(ctx, host, state.baud.toIntOrNull() ?: settings.laserBaud)
        laserRunning = true
        attachNetStats()
        state = state.copy(laserRunning = true)
        log("[串口] 桥已启动 → $host，baud=${state.baud}" +
            if (state.forceSerial) "（强制接管）" else "（VID=${state.serialVid} PID=${state.serialPid}）")
    }

    fun stopSerialBridge() {
        ctx.stopService(Intent(ctx, LaserBridgeService::class.java))
        laserRunning = false
        state = state.copy(laserRunning = false)
        log("[串口] 桥已停止")
    }

    private fun restartLaserBridge() {
        stopSerialBridge()
        startSerialBridge()
    }

    /** 重新连接：停图传 → 重启串口桥/非图传流 → 再起图传 */
    fun reconnect() {
        log("[重连] 开始")
        stopStream()
        if (laserRunning) restartLaserBridge() else startSerialBridge()
        attachNetStats()
        viewModelScope.launch {
            delay(400)
            startStream()
        }
    }

    // ───────────────────────── 其他动作 ─────────────────────────

    /** 清空日志容器 */
    fun clearLogs() {
        state = state.copy(logs = emptyList())
    }

    fun log(msg: String) {
        // 相机/网络回调都在后台线程，Compose 状态统一回主线程写，避免并发写冲突
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { log(msg) }
            return
        }
        val line = LogLine(timeFmt.format(Date()), msg)
        // 同时镜像到 logcat：现场只连 adb 时能直接 `logcat -s LangyaConsole` 排查，
        // 不必盯着 4 行高的日志面板翻页。
        android.util.Log.i("LangyaConsole", msg)
        state = state.copy(logs = (state.logs + line).takeLast(LOG_CAP))
    }

    /** 串口桥是否连上（供界面显示） */
    fun usbConnected(): Boolean = state.laserRunning

    fun connectionLabel(): String = when {
        state.streaming && state.laserRunning -> "图传 + 串口在线"
        state.streaming -> "仅图传在线"
        state.laserRunning -> "仅串口在线"
        else -> "未连接"
    }

    override fun onCleared() {
        statsLink?.removeStatsListener(statsListener)
        statsLink = null
        visionLink?.stop()
        visionLink = null
        stopStream()
        super.onCleared()
    }

    companion object {
        const val VISION_FPS = 15
        /** 预览位图尺寸（16:9，够看清即可）与更新间隔（每 N 帧） */
        private const val PREVIEW_W = 480
        private const val PREVIEW_H = 270
        private const val PREVIEW_EVERY = 3

        /** 扫码节流：15fps 视觉流里每 6 帧解一次（≈2.5 次/秒），够扫又不拖慢视觉 */
        private const val SCAN_EVERY = 6
        private const val LOG_CAP = 300

        val CODEC_OPTIONS = listOf("H.264 (AVC)", "H.265 (HEVC)")
        val ENCODER_OPTIONS = listOf("自动（高通硬编优先）", "强制硬件编码", "软件回退")
        val FEC_OPTIONS = listOf("关闭（0%）", "10%", "20%", "30%", "40%")
        val NET_MODE_OPTIONS = listOf("自动模式（双路并发）", "强制UDP", "强制TCP", "无标签")
        val FALLBACK_RES = listOf("1920×1080", "1280×720", "640×480")
        val FALLBACK_FPS = listOf("30 fps", "15 fps")

        fun fecPercent(label: String): Int =
            label.filter { it.isDigit() }.toIntOrNull() ?: 0

        /** 0..1 → 1/10/20 槽：<25% 一槽，25%~75% 十槽，>75% 二十槽（40% → 10） */
        fun percentToBatch(p: Float): Int = when {
            p < 0.25f -> 1
            p < 0.75f -> 10
            else -> 20
        }

        fun batchToPercent(batch: Int): Float = when (batch) {
            1 -> 0.1f
            20 -> 1f
            else -> 0.4f
        }

        fun parseHex(text: String): Int? {
            val t = text.trim().removePrefix("0x").removePrefix("0X")
            if (t.isEmpty()) return null
            return t.toIntOrNull(16)?.takeIf { it in 0..0xFFFF }
        }
    }
}
