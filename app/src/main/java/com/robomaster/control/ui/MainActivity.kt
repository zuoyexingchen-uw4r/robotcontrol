package com.robomaster.control.ui

import android.content.Context
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.robomaster.control.databinding.ActivityMainBinding
import com.robomaster.control.video.CameraStreamer
import com.robomaster.control.video.EncoderMode
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

        // 图传一键启停（用第二屏记忆的配置）
        binding.btnVideo.setOnClickListener { toggleVideo() }

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

        streamer = CameraStreamer(cm, lifecycleScope).apply {
            // ⚠️ onLog/onStats 可能在 IO 线程回调（CameraStreamer 在 withContext(IO) 里调用），
            // 直接 toast / 更新 UI 会抛 CalledFromWrongThread / 无 Looper 崩溃，必须切主线程。
            onLog = { msg -> runOnUiThread { toast(msg) } }
            onStats = { f, k -> runOnUiThread { binding.tvFps.text = "FPS %.1f · %d kbps".format(f, k.toInt()) } }
        }
        streamer?.start(cfg)
        binding.btnVideo.text = "停止图传"
        log("[UI] 图传启动 ${s.resW}×${s.resH}@${fps} $mime FEC=${s.fec}% → ${s.host}:${s.videoPort}")
    }

    private fun stopVideo() {
        streamer?.stop()
        streamer = null
        binding.btnVideo.text = "图传启停"
        binding.tvFps.text = "FPS 0.0"
        log("[UI] 图传已停止")
    }

    private fun log(msg: String) {
        vm.logs.value = (vm.logs.value + msg).takeLast(200)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        stopVideo()
        super.onDestroy()
    }
}
