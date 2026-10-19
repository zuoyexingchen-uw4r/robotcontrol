package com.robomaster.control.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Range
import android.util.Size
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.robomaster.control.bridge.DataBridge
import com.robomaster.control.bridge.SerialBridge
import com.robomaster.control.comm.UsbSerialTransport
import com.robomaster.control.databinding.ActivityBridgeBinding
import com.robomaster.control.video.CameraStreamer
import com.robomaster.control.video.EncoderMode

/**
 * 无线桥接 + 低延迟图传控制台。
 * ① 串口透传：OTG-CH343 ⇄ 电脑 TCP（透明全双工）。
 * ② 摄像头图传：Camera2 → H.264 高通硬编 → UDP 低延迟推流到电脑。
 */
class BridgeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBridgeBinding
    private val cm by lazy { getSystemService(Context.CAMERA_SERVICE) as CameraManager }

    private var bridge: SerialBridge? = null
    private var bridgeTransport: UsbSerialTransport? = null
    private var streamer: CameraStreamer? = null

    /** 第二数据流通道（预留，数据源可插拔） */
    private var dataBridge: DataBridge? = null

    private var cameraIds: List<Pair<String, String>> = emptyList()
    private var resList: List<Size> = emptyList()
    private var fpsList: List<Range<Int>> = emptyList()

    /** 上次记忆的分辨率/帧率，等相机能力列表加载完再回填 spinner */
    private var pendingResW = 0
    private var pendingResH = 0
    private var pendingFps = 0

    /** 把上次保存的设置回填到界面 */
    private fun restoreSettings() {
        val s = Settings.get(this)
        binding.etHostIp.setText(s.host)
        binding.etVideoPort.setText(s.videoPort.toString())
        binding.etCtrlPort.setText(s.controlPort.toString())
        binding.etSerialPort.setText(s.serialPort.toString())
        binding.etDataPort.setText(s.dataPort.toString())
        binding.etBaud.setText(s.baud.toString())
        binding.etFec.setText(s.fec.toString())
        binding.etBitrate.setText(if (s.bitrateKbps > 0) s.bitrateKbps.toString() else "")
        binding.cbAdaptiveKf.isChecked = s.adaptiveKf
        pendingResW = s.resW
        pendingResH = s.resH
        pendingFps = s.fps
        // spinner 等能力列表加载后由 applyPendingCaps() 回填
        binding.spCodec.setSelection(s.codecIndex.coerceIn(0, 1))
        binding.spEnc.setSelection(s.encoderMode.coerceIn(0, 2))
        binding.spRepeatCsd.setSelection(s.repeatCsd.coerceIn(0, 2))
        // 能力列表此时可能已由 setupCameraSpinners→loadCaps 加载完，立即回填一次
        applyPendingCaps()
    }

    /** 能力列表加载完后，按值把上次的分辨率/帧率选中（匹配不到就保持默认第一项） */
    private fun applyPendingCaps() {
        if (pendingResW > 0) {
            val i = resList.indexOfFirst { it.width == pendingResW && it.height == pendingResH }
            if (i >= 0) binding.spRes.setSelection(i)
        }
        if (pendingFps > 0) {
            val i = fpsList.indexOfFirst { it.upper == pendingFps }
            if (i >= 0) binding.spFps.setSelection(i)
        }
    }

    /** 保存当前界面上的设置为下次默认值 */
    private fun saveSettings() {
        val s = Settings.get(this)
        s.host = binding.etHostIp.text.toString().trim()
        s.videoPort = binding.etVideoPort.text.toString().toIntOrNull() ?: 46000
        s.controlPort = binding.etCtrlPort.text.toString().toIntOrNull() ?: 47000
        s.serialPort = binding.etSerialPort.text.toString().toIntOrNull() ?: 8765
        s.dataPort = binding.etDataPort.text.toString().toIntOrNull() ?: 8766
        s.baud = binding.etBaud.text.toString().toIntOrNull() ?: 115200
        s.fec = binding.etFec.text.toString().toIntOrNull() ?: 30
        s.bitrateKbps = binding.etBitrate.text.toString().toIntOrNull() ?: 0
        s.codecIndex = binding.spCodec.selectedItemPosition
        s.encoderMode = binding.spEnc.selectedItemPosition
        s.repeatCsd = binding.spRepeatCsd.selectedItemPosition
        s.adaptiveKf = binding.cbAdaptiveKf.isChecked
        val size = resList.getOrNull(binding.spRes.selectedItemPosition)
        if (size != null) { s.resW = size.width; s.resH = size.height }
        val fr = fpsList.getOrNull(binding.spFps.selectedItemPosition)
        if (fr != null) s.fps = fr.upper
    }

    private val camPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVideo() else log("相机权限被拒绝")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBridgeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        keepScreenOn()               // 阻止锁屏熄屏
        applySystemBarsInsets()      // 顶栏避让状态栏/刘海

        setupCameraSpinners()
        setupEncoderSpinner()
        restoreSettings()

        binding.btnSerialStart.setOnClickListener { startSerial() }
        binding.btnSerialStop.setOnClickListener { stopSerial() }
        binding.btnDataStart.setOnClickListener { startData() }
        binding.btnDataStop.setOnClickListener { stopData() }
        binding.btnVideoStart.setOnClickListener { if (hasCam()) startVideo() else camPerm.launch(Manifest.permission.CAMERA) }
        binding.btnVideoStop.setOnClickListener { stopVideo() }
    }

    private fun log(msg: String) {
        runOnUiThread {
            binding.tvBridgeLog.append(msg + "\n")
            binding.scrollLog.post { binding.scrollLog.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ── 串口透传 ──
    private fun startSerial() {
        if (bridge?.running?.value == true) { toast("桥接已在运行"); return }
        val baud = binding.etBaud.text.toString().toIntOrNull() ?: 115200
        val port = binding.etSerialPort.text.toString().toIntOrNull() ?: 8765
        val host = binding.etHostIp.text.toString().trim()
        if (host.isBlank()) { log("请先填电脑 IP"); return }
        saveSettings()
        val t = UsbSerialTransport(applicationContext, lifecycleScope, baud).apply {
            ignoreDeviceId = binding.cbForce.isChecked
            forcedDeviceName = binding.etDeviceName.text.toString().trim().ifBlank { null }
        }
        bridgeTransport = t
        bridge = SerialBridge(lifecycleScope, t, host, port).apply { onLog = ::log }
        bridge?.start()
        log("[UI] 启动桥接 → 电脑 $host:$port baud=$baud force=${binding.cbForce.isChecked}")
    }

    private fun stopSerial() {
        bridge?.stop(); bridge = null; bridgeTransport = null
        log("[UI] 已停止桥接")
    }

    // ── 第二数据流通道（预留） ──
    private fun startData() {
        if (dataBridge?.running?.value == true) { toast("数据流2 已在运行"); return }
        val host = binding.etHostIp.text.toString().trim()
        if (host.isBlank()) { log("请先填电脑 IP"); return }
        val port = binding.etDataPort.text.toString().toIntOrNull() ?: 8766
        saveSettings()
        dataBridge = DataBridge(lifecycleScope, host, port).apply {
            onLog = ::log
            onState = { ok -> log("[数据流2] ${if (ok) "已连接电脑" else "连接断开"}") }
        }
        dataBridge?.start()
        log("[UI] 启动数据流2 → 电脑 $host:$port（数据源预留）")
    }

    private fun stopData() {
        dataBridge?.stop(); dataBridge = null
        log("[UI] 已停止数据流2")
    }

    // ── 摄像头图传 ──
    private fun setupCameraSpinners() {
        cameraIds = CameraStreamer(cm, lifecycleScope).cameraIds()
        val labels = cameraIds.map { "${it.second} (${it.first})" }
        binding.spCamera.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spCamera.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = loadCaps(pos)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun loadCaps(pos: Int) {
        if (cameraIds.isEmpty()) return
        val caps = CameraStreamer(cm, lifecycleScope).caps(cameraIds[pos].first)
        resList = caps.sizes
        fpsList = caps.fpsRanges
        binding.spRes.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            resList.map { "${it.width}×${it.height}" })
        binding.spFps.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            fpsList.map { if (it.lower == it.upper) "${it.upper} fps" else "${it.lower}-${it.upper} fps" })
        applyPendingCaps()
    }

    private fun setupEncoderSpinner() {
        binding.spCodec.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, listOf("H.264 (AVC)", "H.265 (HEVC)"),
        )
        binding.spEnc.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf("自动（高通硬编优先）", "强制硬件编码", "软件回退"),
        )
        binding.spRepeatCsd.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            listOf(
                "自动（HEVC 修复 / H.264 原样）",
                "始终重复参数集（修复的 265/264）",
                "从不重复（未修复的 264）",
            ),
        )
    }

    private fun startVideo() {
        if (resList.isEmpty() || fpsList.isEmpty()) { log("[图传] 能力列表为空，稍候重试"); return }
        val camId = cameraIds[binding.spCamera.selectedItemPosition].first
        val size = resList[binding.spRes.selectedItemPosition]
        val fpsRange = fpsList[binding.spFps.selectedItemPosition]
        val fps = fpsRange.upper
        val mode = when (binding.spEnc.selectedItemPosition) {
            1 -> EncoderMode.FORCE_HW
            2 -> EncoderMode.FORCE_SW
            else -> EncoderMode.AUTO_QUALCOMM
        }
        val mime = if (binding.spCodec.selectedItemPosition == 1) "video/hevc" else "video/avc"
        val fec = binding.etFec.text.toString().toIntOrNull() ?: 30
        val host = binding.etHostIp.text.toString().trim()
        if (host.isBlank()) { log("[图传] 请填电脑 IP"); return }
        saveSettings()
        val vPort = binding.etVideoPort.text.toString().toIntOrNull() ?: 46000
        val cPort = binding.etCtrlPort.text.toString().toIntOrNull() ?: 47000
        // 码率：手动填写优先（单位 kbps），留空则按 宽×高×fps/10 自动估算
        val autoBitrate = (size.width.toLong() * size.height * fps / 10).coerceIn(1_000_000, 40_000_000)
        val bitrate = (binding.etBitrate.text.toString().toIntOrNull()?.toLong()?.coerceIn(500, 40_000)
            ?.times(1000L) ?: autoBitrate).toInt()

        val cfg = CameraStreamer.Config(
            cameraId = camId,
            width = size.width,
            height = size.height,
            fps = fps,
            bitrate = bitrate,
            encoderMode = mode,
            codecMime = mime,
            fecPercentage = fec,
            hostIp = host,
            videoPort = vPort,
            controlPort = cPort,
            adaptiveKeyframe = binding.cbAdaptiveKf.isChecked,
            repeatCsd = binding.spRepeatCsd.selectedItemPosition,
        )
        streamer?.stop()
        streamer = CameraStreamer(cm, lifecycleScope).apply {
            onLog = ::log
            onStats = { f, k -> runOnUiThread { binding.tvStats.text = "FPS %.1f · %d kbps".format(f, k.toInt()) } }
        }
        streamer?.start(cfg)
        log("[UI] 开始图传 ${size.width}×${size.height}@${fps} $mime FEC=$fec% 码率=${bitrate / 1000}kbps → $host:$vPort" +
            if (binding.cbAdaptiveKf.isChecked) "（空闲补关键帧 开）" else "")
    }

    private fun stopVideo() {
        streamer?.stop(); streamer = null
        binding.tvStats.text = "FPS 0.0 · 0 kbps"
        log("[UI] 已停止图传")
    }

    private fun hasCam() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        bridge?.stop()
        dataBridge?.stop()
        streamer?.stop()
        super.onDestroy()
    }
}
