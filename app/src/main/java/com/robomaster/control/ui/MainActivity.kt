package com.robomaster.control.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.robomaster.control.databinding.ActivityMainBinding
import com.robomaster.control.video.CameraStreamer
import com.robomaster.control.video.EncoderMode
import com.robomaster.control.vision.VisionCamera
import com.robomaster.control.vision.VisionPipeline
import com.robomaster.control.vision.VisionUdpLink
import kotlinx.coroutines.launch

/**
 * 第一屏（HMI）：连接状态 / 遥测 / 电源 / 图传一键启停。
 *
 * 2026-10 改动：
 *  - 去掉舵机转向滑条与 MJPEG 拉流（原 http://192.168.4.1:81/stream 自动连接）
 *  - 图传改为 Camera2 硬编 + GameStream 推流，**一键启停直接放在本屏**，
 *    配置（IP/分辨率/帧率/端口）读第二屏保存的记忆（见 [Settings]）
 *  - 开机自动检测 CH343 是否连上
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private val cm by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    /** 图传发送器（本屏一键启停用） */
    private var streamer: CameraStreamer? = null

    /** 视觉分析相机（独立第二会话，图传码流不受影响） */
    private var visionCamera: VisionCamera? = null
    private var visionThread: android.os.HandlerThread? = null
    private var visionPipeline: VisionPipeline? = null
    private var visionLink: VisionUdpLink? = null

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (streamer == null) toggleVideo()
        } else {
            toast("相机权限被拒绝，无法启动图传与视觉")
        }
    }

    /** 视觉分析帧率（独立于图传，低于图传帧率减轻 CPU 负担） */
    private val visionFps = 15

    /** CH343(WCH) USB 厂商 ID */
    private val wchVid = 0x1A86

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keepScreenOn()               // 阻止锁屏熄屏
        applySystemBarsInsets()      // 顶栏避让状态栏/刘海

        observe()
        setupControls()
        checkCh343()
        visionLink = VisionUdpLink(VisionSettings.get(this)) { msg ->
            runOnUiThread { if (!isDestroyed) log("[视觉同步] $msg") }
        }.also { it.start() }
    }

    private fun observe() {
        lifecycleScope.launch {
            vm.connState.collect { s ->
                binding.tvConn.text = when (s) {
                    is com.robomaster.control.comm.ConnState.Connected -> "● ${s.channel}"
                    is com.robomaster.control.comm.ConnState.Connecting -> "◌ ${s.channel}"
                    is com.robomaster.control.comm.ConnState.Error -> "✕ ${s.channel}: ${s.msg}"
                    else -> "○ 未连接"
                }
                binding.tvConn.setTextColor(
                    if (s is com.robomaster.control.comm.ConnState.Connected) Color.GREEN else Color.YELLOW
                )
            }
        }
        lifecycleScope.launch {
            vm.sensors.collect { s ->
                binding.tvUltrasonic.text = "超声 ${s.ultrasonicMm}mm"
                binding.tvTof.text = "TOF ${s.tofMm}mm"
                binding.tvMag.text = "地磁 ${s.magX}/${s.magY}/${s.magZ}"
                binding.tvLaser.text = "激光 ${s.laserMm}mm"
            }
        }
        lifecycleScope.launch {
            vm.power.collect { p ->
                binding.tvPower.text =
                    "V ${(p.voltageMV / 1000.0)}V  I ${p.currentMA}mA  T ${p.tempDeciC / 10.0}℃\n" +
                    "C1 ${p.typec1W}W/100W  C2 ${p.typec2W}W/30W  CH224 ${p.ch224State}"
            }
        }
        lifecycleScope.launch {
            vm.logs.collect { list ->
                binding.tvLog.text = list.takeLast(30).joinToString("\n")
                binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }

    private fun setupControls() {
        // 打开无线桥接 / 图传控制台（第二屏，含全部设置）
        binding.btnBridge.setOnClickListener {
            startActivity(android.content.Intent(this, BridgeActivity::class.java))
        }

        // 打开视觉功能设置（第三屏：预处理/检测/追踪/开火/导航开关）
        binding.btnVisionSettings.setOnClickListener {
            startActivity(android.content.Intent(this, VisionSettingsActivity::class.java))
        }

        // 激光桥：启动/停止激光+主控混合桥前台服务（USB 自动重连 + TCP/UDP）
        binding.btnLaser.setOnClickListener {
            toggleLaserBridge()
        }

        // 图传一键启停（用第二屏记忆的配置）
        binding.btnVideo.setOnClickListener { toggleVideo() }

        // IP 切换：循环切槽位 R1/R2/B1/B2，通知 PC 脚本改 IP 并重连
        binding.btnSwitchIp.setOnClickListener { switchRobotSlot() }

        // 电源开关
        binding.btnTypec1.setOnClickListener {
            vm.powerSwitch(1, !binding.btnTypec1.isActivated)
            binding.btnTypec1.isActivated = !binding.btnTypec1.isActivated
        }
        binding.btnTypec2.setOnClickListener {
            vm.powerSwitch(2, !binding.btnTypec2.isActivated)
            binding.btnTypec2.isActivated = !binding.btnTypec2.isActivated
        }
    }

    /** 开机检测 CH343 是否连上（USB-OTG，WCH 厂商 ID 0x1A86） */
    private fun checkCh343() {
        val usb = getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        val found = usb.deviceList.values.any { it.vendorId == wchVid }
        val msg = if (found) {
            val dev = usb.deviceList.values.first { it.vendorId == wchVid }
            "CH343 已连接（${dev.productName ?: dev.deviceName}）"
        } else {
            "未检测到 CH343，请检查 OTG 线"
        }
        binding.tvCh343.text = msg
        binding.tvCh343.setTextColor(if (found) Color.GREEN else Color.YELLOW)
        if (!found) toast(msg)
    }

    private fun toggleVideo() {
        if (streamer != null) {
            stopVideo()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            camPerm.launch(Manifest.permission.CAMERA)
            return
        }
        val s = Settings.get(this)
        if (s.host.isBlank()) {
            toast("请先到第二屏配置电脑 IP")
            return
        }
        if (s.resW <= 0 || s.resH <= 0) {
            toast("请先到第二屏选择分辨率")
            return
        }

        val mode = when (s.encoderMode) {
            1 -> EncoderMode.FORCE_HW
            2 -> EncoderMode.FORCE_SW
            else -> EncoderMode.AUTO_QUALCOMM
        }
        val mime = if (s.codecIndex == 1) "video/hevc" else "video/avc"
        val fps = if (s.fps > 0) s.fps else 30
        val bitrate = if (s.bitrateKbps > 0) s.bitrateKbps * 1000
        else (s.resW.toLong() * s.resH * fps / 10).coerceIn(1_000_000, 40_000_000).toInt()

        val cfg = CameraStreamer.Config(
            cameraId = "0",
            width = s.resW,
            height = s.resH,
            fps = fps,
            bitrate = bitrate,
            encoderMode = mode,
            codecMime = mime,
            fecPercentage = s.fec,
            hostIp = s.host,
            videoPort = s.videoPort,
            controlPort = s.controlPort,
            adaptiveKeyframe = s.adaptiveKf,
        )

        // 视觉管线：任一视觉开关开启时，相机就绪后建独立第二会话（VisionCamera）
        val vs = VisionSettings.get(this)
        var finalCfg = cfg
        val visionOn = vs.deblurEnabled || vs.smoothingEnabled || vs.deglareEnabled ||
                vs.highlandTargetEnabled || vs.baseTargetEnabled || vs.trackingEnabled ||
                vs.autoFireEnabled || vs.navigationEnabled
        if (visionOn) {
            visionPipeline = VisionPipeline(vs)
            // 视觉帧处理线程（ImageReader 回调 + YUV→RGB + 管线，CPU 密集）
            visionThread = android.os.HandlerThread("vision-camera").also { it.start() }
            val vHandler = android.os.Handler(visionThread!!.looper)
            finalCfg = cfg.copy(onCameraReady = { device ->
                visionCamera = VisionCamera(
                    device = device,
                    width = s.resW,
                    height = s.resH,
                    fps = visionFps,
                    handler = vHandler,
                    onFrame = { image ->
                        // 第二会话帧：YUV→RGB → 视觉管线（OpenCV 接入时可替换此步）
                        val rgb = VisionCamera.yuv420ToRgb(image, s.resW, s.resH)
                        val result = visionPipeline?.process(rgb, s.resW, s.resH) ?: return@VisionCamera
                        // 只有真正重算的帧才广播视觉结果；节流帧复用旧结果，禁止重复开火。
                        if (result.processed) {
                            visionLink?.sendVision(result, s.resW, s.resH, visionFps.toFloat())
                        }
                        runOnUiThread {
                            binding.visionOverlay.update(result, s.resW, s.resH)
                            val t = result.trackedTarget
                            if (t != null) {
                                binding.tvFps.text = "FPS %.1f · %d kbps · 视觉: %s d=%dmm yaw=%.1f°".format(
                                    0f, 0,  // fps/kbps 由 onStats 覆盖，这里只显示视觉行
                                    if (t.type == com.robomaster.control.vision.detect.TargetDetector.Target.Type.HIGHLAND) "高地靶" else "基地靶",
                                    t.distanceMm.toInt(), t.yawDeg
                                )
                            }
                            if (result.processed) {
                                result.fireCommand?.let { fc ->
                                    if (fc.shouldFire) {
                                        // 开火指令经 USB 直连下位机（帧格式见 FireController.serialize）
                                        vm.sendVisionCommand(
                                            com.robomaster.control.vision.decide.FireController.serialize(fc)
                                        )
                                        log("[视觉] 开火! ${fc.reason}")
                                    }
                                }
                            }
                        }
                    },
                    onError = { msg -> runOnUiThread { log("[视觉] $msg") } }
                )
                visionCamera?.start()
            })
        }

        // 本地预览 Surface（surface 有效才传，无效时仅推流无预览）
        val previewSurface = binding.svPreview.holder.surface?.takeIf { it.isValid }
        if (previewSurface != null) {
            finalCfg = finalCfg.copy(previewSurface = previewSurface)
        }

        streamer = CameraStreamer(cm, lifecycleScope).apply {
            // ⚠️ onLog/onStats 可能在 IO 线程回调（CameraStreamer 在 withContext(IO) 里调用），
            // 直接 toast / 更新 UI 会抛 CalledFromWrongThread / 无 Looper 崩溃，必须切主线程。
            onLog = { msg -> runOnUiThread { toast(msg) } }
            onStats = { f, k -> runOnUiThread { binding.tvFps.text = "FPS %.1f · %d kbps".format(f, k.toInt()) } }
        }
        streamer?.start(finalCfg)
        binding.btnVideo.text = "停止图传"
        log("[UI] 图传启动 ${s.resW}×${s.resH}@${fps} $mime FEC=${s.fec}% → ${s.host}:${s.videoPort}" +
                if (visionOn) " + 视觉管线" else "")
    }

    private fun stopVideo() {
        // 顺序：先停视觉第二会话（依赖 CameraDevice），再停图传（会 close device）
        visionCamera?.stop()
        visionCamera = null
        streamer?.stop()
        streamer = null
        visionThread?.quitSafely()
        visionThread = null
        visionPipeline?.reset()
        visionPipeline = null
        visionLink?.stop()
        binding.btnVideo.text = "图传启停"
        binding.tvFps.text = "FPS 0.0"
        log("[UI] 图传已停止")
    }

    private fun log(msg: String) {
        vm.logs.value = (vm.logs.value + msg).takeLast(200)
    }

    private var laserRunning = false

    /** 启动/停止激光桥前台服务 */
    private fun toggleLaserBridge() {
        val s = Settings.get(this)
        if (!laserRunning) {
            if (s.host.isBlank()) {
                toast("请先到第二屏配置电脑 IP")
                return
            }
            com.robomaster.control.laser.LaserBridgeService.start(
                this, s.host, s.laserBaud
            )
            laserRunning = true
            binding.btnLaser.text = "停止激光桥"
            log("[激光] 启动混合桥 → ${s.host} (baud=${s.laserBaud})")
        } else {
            stopService(android.content.Intent(this, com.robomaster.control.laser.LaserBridgeService::class.java))
            laserRunning = false
            binding.btnLaser.text = "激光桥"
            log("[激光] 已停止")
        }
    }

    // ── 机器人槽位切换（改本机 IP + 重连） ──
    private var slotIndex = -1

    /** 循环切换到下一个槽位，通知 PC 脚本改 IP，并重开图传/激光端口 */
    private fun switchRobotSlot() {
        val slots = com.robomaster.control.laser.RobotSlots.SLOTS
        slotIndex = (slotIndex + 1) % slots.size
        val slot = slots[slotIndex]
        val s = Settings.get(this)
        s.robotId = slot.robotId
        s.robotIp = slot.ip
        binding.btnSwitchIp.text = "IP:${slot.slot}"
        log("[IP] 切换槽位 ${slot.slot} ${slot.label} → ${slot.ip} (${slot.robotId})")

        // 1) 通知 PC 脚本（adb 打开）改 IP：UDP 广播 "switch_ip <robot_id>"
        sendSwitchIpRequest(slot.robotId)

        // 2) 重开图传端口 + 重连：先停再按新 IP 重建
        stopVideo()
        // 3) 重启激光桥（TCP 会按新 IP 自动重连）
        if (laserRunning) {
            stopService(android.content.Intent(this, com.robomaster.control.laser.LaserBridgeService::class.java))
            com.robomaster.control.laser.LaserBridgeService.start(this, s.host, s.laserBaud)
        }
        toast("已切换 ${slot.slot} ${slot.label} ${slot.ip}，正在重连")
    }

    /** 把切 IP 指令广播给 PC 端 switch_ip.sh --listen（端口 8769） */
    private fun sendSwitchIpRequest(robotId: String) {
        try {
            java.net.DatagramSocket().use { ds ->
                ds.broadcast = true
                val payload = "switch_ip $robotId".toByteArray(Charsets.UTF_8)
                ds.send(java.net.DatagramPacket(
                    payload, payload.size,
                    java.net.InetAddress.getByName("255.255.255.255"), 8769,
                ))
            }
        } catch (e: Exception) {
            log("[IP] 通知 PC 脚本失败: ${e.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopVideo()
        visionLink?.stop()
        visionLink = null
        super.onDestroy()
    }
}
