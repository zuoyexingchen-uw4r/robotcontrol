package com.robomaster.control.video

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.robomaster.control.bridge.TcpChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 低延迟摄像头图传（参考 Sunshine：视频走 UDP 无重传，控制走 TCP 关键帧请求）。
 *
 * 数据通路：Camera2 ——(Surface 零拷贝)——> MediaCodec H.264 硬编输入 Surface
 *           ——> Annex-B 输出 ——> VideoSender 分片 ——> UDP ——> 电脑上位机。
 *
 * 编码器选择见 EncoderPicker：优先高通 c2.qti 硬编，软件回退。
 */
class CameraStreamer(
    private val cameraManager: CameraManager,
    private val scope: CoroutineScope,
) {
    data class Config(
        val cameraId: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val bitrate: Int,
        val encoderMode: EncoderMode,
        val codecMime: String = "video/avc",
        val fecPercentage: Int = 30,
        val hostIp: String,
        val videoPort: Int,
        val controlPort: Int,
        val previewSurface: Surface? = null,
        /**
         * 相机就绪回调：CameraDevice 打开后触发（图传 session 建立之前）。
         *
         * 视觉分析走**独立的第二条 Camera2 会话**（见 VisionCamera）：
         * 拿到 device 后自建 CaptureSession（独立 ImageReader target +
         * 独立帧率），与图传码流互不干扰——图传 session 只含编码器
         * 与预览 Surface，视觉分析降帧/降分辨率都不影响推流质量。
         */
        val onCameraReady: ((CameraDevice) -> Unit)? = null,
        /**
         * 空闲时补关键帧（adaptive GOP）：pacing 发现这一帧提前发完、带宽有富余时，
         * 预请求下一个采集帧编成 IDR，把空闲带宽换成更密的参考帧。
         *
         * **默认关闭**——项目是 CBR，IDR 是 P 帧的 3~10 倍大，rate control 会从后续
         * P 帧预算里抠比特补它，而画面主体是 P 帧。所以网络好、不丢包时开启反而
         * 降整体画质。它真正划算的场景是**弱网**：丢包导致恢复慢时，把 IDR 间隔
         * 从 2s 压到 [MIN_IDR_INTERVAL_MS]，恢复时间直接减半。
         */
        val adaptiveKeyframe: Boolean = false,
        /**
         * 参数集重复策略（每个 IDR 是否都重新拼 VPS/SPS/PPS 或 SPS/PPS）：
         *  0 = 自动（默认）：HEVC 每个 IDR 都拼，H.264 保持原行为
         *  1 = 始终重复：H.264 也每个 IDR 拼（H.264 遇到参数集丢失时用）
         *  2 = 从不重复：完全保持旧行为
         *
         * 为什么 HEVC 要修：HEVC 参数集只在编码器启动时输出一次，旧代码拼完第一个
         * 关键帧就清空缓存。一旦那个 IDR 有任何分片丢失，参数集永久消失，后续每个
         * P 帧都解不出——表现为一卡一卡 + 解码器刷
         * "First slice in a frame missing" / "Could not find ref with POC xx"。
         * H.264 参数集行为不同（实测可自愈），默认不动，选择权留给用户。
         */
        val repeatCsd: Int = 0,
    )

    data class StreamCaps(
        val sizes: List<Size>,
        val fpsRanges: List<android.util.Range<Int>>,
    )

    var onLog: (String) -> Unit = {}
    var onStats: (fps: Float, kbps: Float) -> Unit = { _, _ -> }

    private var camera: CameraDevice? = null
    private var session: android.hardware.camera2.CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var encSurface: Surface? = null
    private var sender: GameStreamSender? = null

    /** 当前推流配置，供动态调码率时取基准值 */
    private var currentCfg: Config? = null

    /** 每个 IDR 是否重复拼参数集。由 [Config.repeatCsd] + codecMime 决定，见 Config 注释 */
    private var repeatCsdPerIdr = false
    private var pendingCsd: ByteArray? = null
    private var controlChannel: TcpChannel? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var frameCount = 0
    private var byteCount = 0L
    private var statWindowStart = 0L

    @Volatile private var running = false
    /** PTS 时基自检：只报一次，避免刷屏 */
    @Volatile private var warnedLatBase = false
    /** 相机 HAL 最大在途帧数（REQUEST_PIPELINE_MAX_DEPTH），日志里对照实际深度用 */
    @Volatile private var pipelineMaxDepth: Int? = null

    /** 上次「空闲补关键帧」的时刻，用于 [MIN_IDR_INTERVAL_MS] 密度节流 */
    private var lastIdrRequestMs = 0L

    /** 上次「丢帧后补关键帧」的时刻，用于 [MIN_DROP_KF_INTERVAL_MS] 节流 */
    private var lastDropKfMs = 0L

    /** 枚举后置/前置摄像头 */
    fun cameraIds(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (id in cameraManager.cameraIdList) {
            val ch = cameraManager.getCameraCharacteristics(id)
            val facing = ch.get(CameraCharacteristics.LENS_FACING)
            val label = when (facing) {
                CameraCharacteristics.LENS_FACING_BACK -> "后置"
                CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                else -> "外置"
            }
            out += id to label
        }
        return out
    }

    /** 某摄像头支持的录制分辨率与 AE 帧率范围（运行期读，不硬编码） */
    fun caps(cameraId: String): StreamCaps {
        val ch = cameraManager.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = (map?.getOutputSizes(android.media.MediaRecorder::class.java) ?: emptyArray())
            .filter { it.width in 160..encoderMaxW() && it.height in 160..encoderMaxH() && it.width % 2 == 0 && it.height % 2 == 0 }
            .distinct()
            .sortedWith(compareByDescending<Size> { it.height }.thenByDescending { it.width })

        // 帧率：只提供 15/24/30 三档固定帧率，其余一律不列。
        //
        // 为什么钉死档位（而不是罗列设备支持的所有范围）：
        // 接收端 ffplay 对无 PTS 的裸流只能**猜**播放帧率。实测 30fps 档它按 29.8 播，
        // 每秒积压 0.2 帧 → 延迟线性上涨到数秒；而 15fps 档它猜的值偏高，播放比送入快，
        // 反而"抢拍子"追赶、完全不积压（延迟 24ms）。所以关键是让实际帧率落在
        // ffplay 猜测值的**安全侧**，且帧率本身必须稳定。
        // 固定档位 + Camera2 设 Range(n,n)（见 buildSession）保证 PTS 间隔均匀，
        // 猜测偏差再多也只是一个固定偏移，不会随时间为积压。
        // 档位选取规则：设备显式暴露 Range(n,n) 就用；否则只要某范围覆盖 n，
        // 也按 Range(n,n) 请求（Camera2 通常接受范围内的固定值）——即"往上匹配"。
        val allRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val explicitFixed = allRanges.filter { it.lower == it.upper }.map { it.upper }.toSet()
        val fps = listOf(30, 24, 15)
            .filter { n -> explicitFixed.contains(n) || allRanges.any { it.lower <= n && n <= it.upper } }
            .distinct()
            .map { android.util.Range(it, it) }

        return StreamCaps(sizes, fps.toList())
    }

    private fun encoderMaxW(): Int = maxOf(encMaxFor("video/avc", 1920), encMaxFor("video/hevc", 1920))
    private fun encoderMaxH(): Int = maxOf(encMaxFor("video/avc", 1080), encMaxFor("video/hevc", 1080))

    private fun encMaxFor(mime: String, def: Int): Int = try {
        val pick = EncoderPicker.pick(mime) ?: return def
        val ci = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == pick.name }
        ci?.getCapabilitiesForType(mime)?.videoCapabilities?.supportedWidths?.upper?.toInt() ?: def
    } catch (_: Exception) {
        def
    }

    fun start(cfg: Config) {
        if (running) stop()
        running = true
        frameCount = 0; byteCount = 0; statWindowStart = System.currentTimeMillis()

        thread = HandlerThread("cam-enc").also { it.start() }
        handler = Handler(thread!!.looper)

        scope.launch { openAndStream(cfg) }
    }

    private fun createEncoder(name: String, format: MediaFormat): MediaCodec =
        MediaCodec.createByCodecName(name).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

    /** 低延迟编码参数：CBR + Surface 输入 + 实时优先级 + (硬编)KEY_LATENCY */
    private fun buildFormat(cfg: Config, pick: EncoderPicker.Pick, withProfile: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(cfg.codecMime, cfg.width, cfg.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, cfg.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, cfg.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            if (withProfile) {
                if (cfg.codecMime == "video/hevc") {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                } else {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                }
            }
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setInteger(MediaFormat.KEY_LATENCY, if (pick.hardware) 1 else 0)
            }
        }

    private suspend fun openAndStream(cfg: Config) = withContext(Dispatchers.IO) {
        currentCfg = cfg
        repeatCsdPerIdr = when (cfg.repeatCsd) {
            1 -> true                       // 始终重复（修复的 264 / 265）
            2 -> false                      // 从不重复（未修复）
            else -> cfg.codecMime == "video/hevc"   // 自动：HEVC 修、H.264 不动
        }
        try {
            val pick = EncoderPicker.pick(cfg.codecMime, cfg.encoderMode)
                ?: throw IllegalStateException("无可用 ${cfg.codecMime} 编码器")
            onLog("[图传] 编码器 ${pick.name} (hw=${pick.hardware} qcom=${pick.qualcomm}) codec=${cfg.codecMime}")

            sender = GameStreamSender(cfg.hostIp, cfg.videoPort, cfg.bitrate, cfg.fps, cfg.fecPercentage)
                .apply {
                    onLog = this@CameraStreamer.onLog
                    onIdle = { idleMs -> maybeBoostKeyframe(cfg, idleMs) }
                    onDrop = { maybeKeyframeAfterDrop() }
                }
            startControlClient(cfg)

            encoder = try {
                createEncoder(pick.name, buildFormat(cfg, pick, withProfile = true))
            } catch (e: Exception) {
                onLog("[图传] 指定 profile 配置失败，改用默认 profile 重试")
                createEncoder(pick.name, buildFormat(cfg, pick, withProfile = false))
            }
            val enc = encoder ?: throw IllegalStateException("编码器创建失败")
            encSurface = enc.createInputSurface()
            enc.setCallback(codecCallback, handler)
            enc.start()
            onLog("[图传] 编码就绪 ${cfg.width}x${cfg.height}@${cfg.fps} ${cfg.bitrate / 1000}kbps FEC=${cfg.fecPercentage}%")
            logCameraPipelineDepth(cfg)

            cameraManager.openCamera(
                cfg.cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(cd: CameraDevice) {
                        camera = cd
                        // 先把 device 暴露给视觉分析（建独立第二会话），再建图传 session
                        cfg.onCameraReady?.invoke(cd)
                        buildSession(cd, cfg)
                    }
                    override fun onDisconnected(cd: CameraDevice) { cd.close(); camera = null }
                    override fun onError(cd: CameraDevice, error: Int) {
                        cd.close(); camera = null
                        onLog("[图传] 相机错误 $error")
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            onLog("[图传] 启动失败: ${e.message}")
            stop()
        }
    }

    /**
     * 相机 HAL 的在途帧深度——**App 侧延迟的另一半，之前完全没量化**。
     *
     * REQUEST_PIPELINE_MAX_DEPTH（API 28+）= 从 setRepeatingRequest 到出图之间，
     * HAL 最多可以缓存多少帧。这个数直接就是相机侧的延迟：
     *   深度 3 @30fps = 100ms，深度 4 = 133ms。
     * 实测 App 侧总延迟 128ms，如果这里报 3~4，说明延迟主要在相机 HAL，
     * 那是固件行为，软件侧能做的很有限（见 buildSession 的 ISP 低延迟设置）。
     */
    private fun logCameraPipelineDepth(cfg: Config) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        runCatching {
            val ch = cameraManager.getCameraCharacteristics(cfg.cameraId)
            val depth = ch.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)
            val hw = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            if (depth != null) {
                pipelineMaxDepth = depth.toInt()
                val perFrameMs = 1000 / maxOf(1, cfg.fps)
                onLog("[图传] 相机 HAL 最大在途 $depth 帧（≈${pipelineMaxDepth!! * perFrameMs}ms，硬件级别 $hw）")
            }
        }
    }

    private fun buildSession(cd: CameraDevice, cfg: Config) {
        val targets = mutableListOf<Surface>()
        cfg.previewSurface?.let { targets += it }
        encSurface?.let { targets += it }
        if (targets.isEmpty()) { onLog("[图传] 无输出 Surface"); return }

        // **读"实际"管线深度**（不是 REQUEST_PIPELINE_MAX_DEPTH 那个上限）。
        // CaptureResult.REQUEST_PIPELINE_DEPTH 是 HAL 每帧上报的真实深度，
        // 官方文档：REQUEST_PIPELINE_MAX_DEPTH 只是"最多"，"the actual pipeline depth
        // (specified by CaptureResult#REQUEST_PIPELINE_DEPTH) may be less than the max"。
        // 深度 X 帧 = 相机侧延迟 X 个帧间隔（30fps 下 X×33ms）。
        // 实测这台设备上限 8 帧，但实际用几帧必须读出来才知道——这是相机侧延迟的
        // 唯一真实读数，之前完全没取。
        var loggedDepth = -1
        val depthCb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                // ⚠️ 这个键是 **Byte** 类型（AOSP: new Key("android.request.pipelineDepth", Byte.class)），
                // 不是 Int——直接和 Int 比较编译不过。
                val d = result.get(CaptureResult.REQUEST_PIPELINE_DEPTH)?.toInt() ?: return
                if (d != loggedDepth) {
                    loggedDepth = d
                    val perFrame = 1000 / maxOf(1, cfg.fps)
                    onLog("[图传] 相机实际管线深度 $d 帧（≈${d * perFrame}ms，上限 ${pipelineMaxDepth ?: "?"}）")
                }
            }
        }

        val sessionCb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val base = cd.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    targets.forEach { addTarget(it) }
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(cfg.fps, cfg.fps))
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }
                // 低延迟 ISP 设置：NOISE_REDUCTION / EDGE / TONEMAP 的 HIGH_QUALITY
                // 档会做**多帧融合**，每项都可能多背 1~2 帧；FAST 是逐帧处理。
                // 视频预览/图传场景根本用不到 HIGH_QUALITY 的画质，白送几十毫秒。
                // 视频防抖同理（多一帧的重构延迟）。
                // ⚠️ 这些键不是所有设备都允许在会话内修改：setRepeatingRequest 会抛
                // CameraAccessException。所以先用"全套"试，失败就退回 base，
                // 绝不让图传因为一个画质优化起不来。
                val lowLat = try {
                    base.apply {
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
                        set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST)
                        set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_FAST)
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    }.build()
                } catch (_: IllegalArgumentException) {
                    null
                }
                try {
                    val req = if (lowLat != null) lowLat else base.build()
                    // 传 CaptureCallback 而非 null：要读**实际**管线深度，见下面 depthCb
                    s.setRepeatingRequest(req, depthCb, handler)
                    if (lowLat != null) {
                        onLog("[图传] 低延迟 ISP 已应用（NR/EDGE/TONEMAP=FAST，防抖关）")
                    } else {
                        onLog("[图传] 设备不支援会话内改 ISP 模式，用模板默认")
                    }
                } catch (e: CameraAccessException) {
                    s.setRepeatingRequest(base.build(), depthCb, handler)
                    onLog("[图传] 低延迟 ISP 被拒（${e.message}），已回退默认设置")
                }
                onLog("[图传] 推流中 → ${cfg.hostIp}:${cfg.videoPort}")
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                onLog("[图传] 会话配置失败")
            }
        }

        // 旧式 createCaptureSession：全 API 兼容（28+ 已 deprecated 但仍可用，最稳）
        //
        // ⚠️⚠️ 但先用 **Stream Use Case**（API 33+）试一把，这是本轮的核心修复：
        // 我们一直用 TEMPLATE_RECORD，它走 VIDEO_RECORD 语义，而官方文档原话是
        //   "VIDEO_RECORD: ... including high-quality image stabilization ...
        //    This option might produce output frames with a **substantial lag
        //    from real time**, to allow for highest-quality stabilization
        //    or other processing."
        // 实测这台设备相机 HAL 最大在途 8 帧（≈264ms @30fps），正是"高画质换延迟"的
        // 典型症状。VIDEO_CALL 用途会让 HAL 选低延迟的 sensor mode / 调优，
        // 关掉高画质防抖那条路径。
        var started = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 先问设备支持哪些 Stream Use Case，再挑最合适的——盲设 VIDEO_CALL
            // 碰上不支持的设备会白抛一次异常。
            // 常量名带 SCALER_AVAILABLE_ 前缀是因为 AOSP 只在这里公开了这几个值
            // （DEFAULT=0 / PREVIEW=1 / STILL_CAPTURE=2 / VIDEO_RECORD=3 /
            //   PREVIEW_VIDEO_STILL=4 / VIDEO_CALL=5），setStreamUseCase(long)
            //   消费的正是这套编码。
            val supported = runCatching {
                cameraManager.getCameraCharacteristics(cfg.cameraId)
                    .get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
            }.getOrNull()
            val hasVideoCall = supported?.contains(
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL.toLong()) == true
            val hasPreview = supported?.contains(
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()) == true
            val useCase = when {
                hasVideoCall -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL
                hasPreview -> CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW
                else -> null
            }
            val useCaseName = when (useCase) {
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_CALL -> "VIDEO_CALL"
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW -> "PREVIEW"
                else -> "无"
            }
            if (supported != null) {
                onLog("[图传] 设备支持的 Stream Use Case = ${supported.toList()}")
            }
            if (useCase != null) {
                runCatching {
                    val outs = mutableListOf<android.hardware.camera2.params.OutputConfiguration>()
                    cfg.previewSurface?.let {
                        outs += android.hardware.camera2.params.OutputConfiguration(it).apply {
                            setStreamUseCase(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong())
                        }
                    }
                    outs += android.hardware.camera2.params.OutputConfiguration(encSurface!!).apply {
                        setStreamUseCase(useCase.toLong())
                    }
                    cd.createCaptureSessionByOutputConfigurations(outs, sessionCb, handler)
                    started = true
                    onLog("[图传] Stream Use Case = $useCaseName"
                        + "（低延迟管线；TEMPLATE_RECORD 的 VIDEO_RECORD 语义官方明示会有 substantial lag）")
                }.onFailure {
                    onLog("[图传] Stream Use Case 设置失败（${it.message}），回退默认会话")
                }
            }
        }
        if (!started) {
            @Suppress("DEPRECATION")
            cd.createCaptureSession(targets, sessionCb, handler)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onLog("[图传] Android < 13 无 Stream Use Case，用 TEMPLATE_RECORD 默认管线")
            }
        }
    }

    private val codecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val buf = codec.getOutputBuffer(index) ?: return
            val bytes = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(bytes, 0, info.size)
            codec.releaseOutputBuffer(index, false)

            // 参数集（H.264 的 SPS/PPS；HEVC 的 VPS/SPS/PPS）缓存，拼到关键帧前，
            // 保证 PC 端拿到参数集才能起播（Sunshine 同思路）
            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                pendingCsd = if (pendingCsd == null) bytes else pendingCsd!! + bytes
                return
            }

            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            var payload = bytes
            if (key && pendingCsd != null) {
                payload = pendingCsd!! + bytes
                // repeatCsdPerIdr=false（H.264 默认）时拼一次就清空，保持旧行为；
                // =true（HEVC 默认）时**不清空**，每个 IDR 都重拼参数集——
                // HEVC 参数集只在启动时输出一次，一旦首个 IDR 丢包，不清空就等于
                // 参数集永久缺失，后续 P 帧全部解不出。
                if (!repeatCsdPerIdr) pendingCsd = null
            }

            val frameType = if (key) GameStream.FRAME_TYPE_IDR else GameStream.FRAME_TYPE_P
            // 真正的"采集→此刻"延迟：MediaCodec 的 presentationTimeUs 就是相机帧时间戳
            // （monotonic 时基，与 System.nanoTime() 可比），所以这个差值覆盖了
            // 相机 HAL 管线 + 编码器内部缓冲 + 本次回调处理，是端到端延迟里 **App 侧**的份额。
            // ⚠️ 之前这里测的是 `nanoTime() - t0`（t0 就在函数开头），只反映编码回调内部
            // 耗时（微秒级），对诊断 330ms 毫无价值——App 侧一直是盲区。
            // 单位 0.1ms、u16 上限 6553.5ms，足够覆盖异常值。
            val encLatMs = (System.nanoTime() - info.presentationTimeUs * 1000L) / 1_000_000.0
            val latencyDeciMs = encLatMs.coerceIn(0.0, 6553.5).let { (it * 10).toInt() }
            // 时基自检：presentationTimeUs 理论上与 System.nanoTime() 同为 monotonic 时基
            // （相机帧时间戳）。若某设备不是（比如 PTS 从 0 开始自己计数），算出来的值会
            // 溢出到 6553.5ms 上限——那就必须在日志里喊一声，不能默默给你一个错数。
            if ((encLatMs < -1.0 || encLatMs > 6553.5) && !warnedLatBase) {
                warnedLatBase = true
                onLog("[图传] ⚠️ PTS 时基异常：ptsUs=${info.presentationTimeUs} 与 nanoTime 不可比，"
                    + "applat 统计不可信（encLat=${encLatMs.toLong()}ms）")
            }
            sender?.sendFrame(payload, frameType, info.presentationTimeUs, latencyDeciMs)

            frameCount++; byteCount += payload.size
            val now = System.currentTimeMillis()
            if (now - statWindowStart >= 1000) {
                val dt = (now - statWindowStart) / 1000.0
                val fps = frameCount / dt
                val kbps = byteCount * 8 / 1000.0 / dt
                scope.launch { onStats(fps.toFloat(), kbps.toFloat()) }
                frameCount = 0; byteCount = 0; statWindowStart = now
            }
        }
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            onLog("[图传] 编码错误 ${e.message}")
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // 部分编码器把参数集放在 format 的 csd-0/1/2 里，这里兜底取出
            runCatching {
                val csd = (0 until 3).mapNotNull { format.getByteBuffer("csd-$it")?.let { b -> ByteArray(b.remaining()).also { arr -> b.get(arr); b.rewind() } } }
                if (csd.isNotEmpty()) {
                    var all = ByteArray(0)
                    csd.forEach { all = all + it }
                    pendingCsd = all
                }
            }
            // **编码器实际使用的延迟（帧）**：官方文档对 KEY_LATENCY 的原话是
            // "Use the output format to verify that this feature was enabled and the
            //  actual value used by the encoder"——我们必须验，不能设了就当生效。
            // 实测 App 侧延迟 128ms（≈4 帧），如果这里报 3~4，光它就占掉 100ms+，
            // 比继续调接收端有意义得多。报 -1 = 编码器没在 output format 里给出该值。
            runCatching {
                val lat = format.getInteger(MediaFormat.KEY_LATENCY, -1)
                if (lat >= 0) {
                    onLog("[图传] 编码器实际延迟 $lat 帧（KEY_LATENCY 生效，${lat * 1000 / maxOf(1, currentCfg?.fps ?: 30)}ms）")
                } else {
                    onLog("[图传] 编码器未报告 latency（不支持或未启用 KEY_LATENCY）")
                }
            }
        }
    }

    /**
     * TCP 控制通道（GameStream 风格）：电脑发 4 字节大端消息类型。
     * 0x0301 = SS_RFI_REQUEST → 让编码器立刻出 IDR 关键帧。
     *
     * **方向（2026-10 改）**：电脑开端口监听，手机主动 connect（见 [TcpChannel]）。
     * 旧实现是手机开 ServerSocket 等电脑连，与串口透传方向相反；现在三条通道
     * （视频 UDP / 控制 TCP / 串口 TCP）统一为「电脑监听、手机连接」。
     */
    private fun startControlClient(cfg: Config) {
        val ch = TcpChannel(scope, cfg.hostIp, cfg.controlPort, label = "控制通道").apply {
            onLog = this@CameraStreamer.onLog
            onData = { bytes ->
                // TCP 是字节流：多个消息可能粘在一个段里，按 4 字节边界切
                var off = 0
                while (bytes.size - off >= 4) {
                    val type = java.nio.ByteBuffer.wrap(bytes, off, 4).int
                    off += 4
                    when (type) {
                        0x0301 -> {
                            requestKeyframe()
                            onLog("[图传] 收到 SS_RFI_REQUEST，触发 IDR")
                        }
                        else -> onLog("[图传] 控制消息 0x${type.toString(16)}")
                    }
                }
            }
        }
        controlChannel = ch
        ch.start()
        onLog("[图传] 控制通道 → ${cfg.hostIp}:${cfg.controlPort}（电脑监听中，连接后可请求关键帧）")
    }

    fun requestKeyframe() {
        try {
            val b = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            encoder?.setParameters(b)
        } catch (_: Exception) {}
    }

    /**
     * 丢帧后立刻补关键帧（编码线程回调，**不受 [Config.adaptiveKeyframe] 开关限制**——
     * 这不是可选的画质优化，是丢帧的必然补救）。
     *
     * 原因：丢帧在码流里留下空洞，解码器参考链（H.264 DPB / HEVC RPS）断裂，
     * 后续 P 帧找不到参考 → 运动模糊；HEVC 更严重，直接报
     * "Could not find ref with POC xx" / "Error constructing the frame RPS" / "First slice
     * in a frame missing"，画面卡住。只有来一个 IDR 才能把参考链重建起来。
     *
     * 实测数据：帧级 pacing 修好后丢帧率已从 70% 降到 3.9%，但残余的丢帧仍足以
     * 让画质周期性崩坏，所以这一步必须有。
     *
     * 节流 [MIN_DROP_KF_INTERVAL_MS]：丢帧风暴时不能 IDR 风暴——CBR 下 IDR 密度过高
     * 会挤压 P 帧预算，反而整体变糊。300ms ≈ 每秒最多补 3 次，够跟上丢帧节奏。
     *
     * 同时会**临时抬高码率**（见 [boostBitrateForIdr]）：这是「关键帧多却更糊」的解药。
     */
    private fun maybeKeyframeAfterDrop() {
        if (!running) return
        val now = System.currentTimeMillis()
        if (now - lastDropKfMs < MIN_DROP_KF_INTERVAL_MS) return
        lastDropKfMs = now
        boostBitrateForIdr()
        requestKeyframe()
    }

    /**
     * 请求 IDR 前临时抬高码率，约 [IDR_BITRATE_BOOST_MS] 后恢复。
     *
     * 为什么必须这么做：IDR 是 P 帧的 3~10 倍大，而项目是 **CBR 固定码率**。
     * 码率封顶时 rate control 只能靠「降低量化参数以外的所有开销」来塞进这个 IDR，
     * 结果是关键帧本身编码质量骤降——参考帧一糊，后续所有 P 帧都跟着糊。
     * 用户实测现象正是「感觉关键帧很多，画面反而糊」。
     *
     * MediaCodec 支持运行时 KEY_VIDEO_BITRATE 动态调整，抬 60% 让这个关键帧拿到
     * 足够比特，参考帧清晰了，后面的 P 帧才有清晰的基准可参考。
     */
    private fun boostBitrateForIdr() {
        val enc = encoder ?: return
        val base = currentCfg?.bitrate ?: return
        val boosted = (base * 1.6).toInt().coerceAtMost(60_000_000)
        runCatching {
            enc.setParameters(Bundle().apply { putInt(MediaFormat.KEY_BIT_RATE, boosted) })
        }
        // IDR 通常在一个帧间隔内编码完成，400ms 后恢复基准码率
        handler?.postDelayed({
            runCatching {
                encoder?.setParameters(Bundle().apply { putInt(MediaFormat.KEY_BIT_RATE, base) })
            }
        }, IDR_BITRATE_BOOST_MS)
    }

    /**
     * pacing 空闲时预请求关键帧（仅 [Config.adaptiveKeyframe] 开启时生效，发送线程回调）。
     *
     * 架构限制：相机→编码器是 **Surface 直连**，App 无法主动"编一帧"、也无法中途中止
     * 已提交的帧。所以这里只能给**下一个到达的采集帧**打 IDR 标记——即"预请求"，
     * 最多等一个采集间隔（1/fps）就会有一个 IDR 出来。采集帧本身无论如何都要编码、
     * 要发送，把它从 P 换成 IDR 不占额外时间，只多占比特。
     *
     * 两道闸门：
     *  1. **富余要够**：IDR 比 P 帧大 3~10 倍，多出来的发送时间得靠富余消化。
     *     富余不足半个采集间隔时不补，否则会吃掉下一帧的预算、反而抬高延迟。
     *     这正是"用空闲带宽换画质"的含义——网络吃紧（无富余）时自动不补。
     *  2. **密度上限**：[MIN_IDR_INTERVAL_MS] 就是带宽上限。CBR 下 IDR 的比特是从
     *     后续 P 帧预算里抠的，密度太高会退化成全 I 帧，画面整体变糊。
     */
    private fun maybeBoostKeyframe(cfg: Config, idleMs: Long) {
        if (!cfg.adaptiveKeyframe || !running) return
        val frameIntervalMs = if (cfg.fps > 0) 1000L / cfg.fps else 33L
        if (idleMs < frameIntervalMs / 2) return
        val now = System.currentTimeMillis()
        if (now - lastIdrRequestMs < MIN_IDR_INTERVAL_MS) return
        lastIdrRequestMs = now
        requestKeyframe()
        onLog("[图传] 空闲 ${idleMs}ms → 补关键帧（密度上限 ${MIN_IDR_INTERVAL_MS}ms）")
    }

    fun stop() {
        running = false
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }; session = null
        runCatching { camera?.close() }; camera = null
        // 清掉挂起的码率恢复回调，否则停止后还会对已释放的编码器调 setParameters
        runCatching { handler?.removeCallbacksAndMessages(null) }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }; encoder = null
        runCatching { encSurface?.release() }; encSurface = null
        runCatching { sender?.close() }; sender = null
        currentCfg = null
        pendingCsd = null          // 别让下次启动用到上一轮的参数集
        repeatCsdPerIdr = false
        runCatching { controlChannel?.stop() }; controlChannel = null
        thread?.quitSafely(); thread = null; handler = null
    }

    companion object {
        /**
         * 空闲补关键帧的密度上限（ms）——也就是用户要的"带宽上限"。
         * CBR 下 IDR 的比特是从后续 P 帧预算里抠出来的，密度再高就是全 I 帧，
         * 画面主体（P 帧）反而变糊。700ms ≈ 比默认 2s GOP 密 3 倍，够用。
         */
        private const val MIN_IDR_INTERVAL_MS = 700L

        /**
         * 丢帧后补关键帧的节流间隔（ms）。CBR 下 IDR 比特是从后续 P 帧预算里抠的，
         * 丢帧风暴若引发 IDR 风暴，画面会整体变糊。300ms ≈ 每秒最多补 3 次，
         * 足以跟上实测的丢帧节奏（3.9% ≈ 每秒 1 次）。
         */
        private const val MIN_DROP_KF_INTERVAL_MS = 300L

        /** IDR 码率提升的持续时长（ms）——一个帧间隔内编码完成即可恢复 */
        private const val IDR_BITRATE_BOOST_MS = 400L
    }
}
