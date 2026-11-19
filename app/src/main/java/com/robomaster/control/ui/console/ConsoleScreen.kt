package com.robomaster.control.ui.console

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「屏幕 1」——横屏控制台（浅色 M3，目标设备为手机横屏 780–900×350–420dp）。
 *
 * 布局分两层：
 * 1. **折叠态（默认）**：顶栏 + 日志/预览两块面板（撑满）+ 底部四键
 *    （扫码连接 / 锁定屏幕 / 重新连接 / 展开项目），一屏看全、不滚动。
 * 2. **展开态**：面板压到约 40% 高，下面展开全部设置行（可滚动），
 *    底部第四键变成「收起项目」。
 *
 * 横向尺寸一律用 weight 比例（日志:预览 = 640:588），高度按可用高度取比例，
 * 不再按 1280×800dp 平板基准摆死尺寸 —— 手机上固定 640dp 会把预览挤成竖排字。
 */
@Composable
fun ConsoleScreen(
    vm: ConsoleViewModel,
    onOpenVisionSettings: () -> Unit,
    onOpenBridge: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onLockScreen: () -> Unit,
    onExit: () -> Unit = {},
) {
    val state = vm.state
    val snackbar = remember { SnackbarHostState() }
    var pairingDialog by remember { mutableStateOf(false) }
    var entered by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val hostInvalid = state.host.isNotBlank() && !isIpv4(state.host)
    val portInvalid = state.videoPort.toIntOrNull()
        ?.let { it !in 1..65535 || vm.isReservedPort(it) }
        ?: state.videoPort.isNotEmpty()

    // 返回：反向播放进入动画后再退出，系统返回手势与返回键一致
    BackHandler(enabled = !pairingDialog) {
        if (entered) {
            entered = false
            scope.launch {
                delay(180)
                onExit()
            }
        } else {
            onExit()
        }
    }

    LaunchedEffect(Unit) { entered = true }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        AnimatedVisibility(
            visible = entered,
            enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.98f, animationSpec = tween(220)),
            exit = fadeOut(tween(180)) + scaleOut(targetScale = 0.98f, animationSpec = tween(180)),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(inner)
            ) {
                ConsoleTopBar(
                    state = state,
                    onToggleStream = vm::toggleStream,
                    onOpenVisionSettings = onOpenVisionSettings,
                    onOpenBridge = onOpenBridge,
                )

                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val boxW = this.maxWidth
                    val boxH = this.maxHeight
                    val wide = boxW >= 1000.dp
                    val bigText = if (wide) 28.sp else 22.sp
                    val midText = if (wide) 22.sp else 18.sp

                    // 面板放在**固定调用点**：折叠/展开只改高度，不换 composition 分支，
                    // 否则 TextureView 会被销毁重建 → 预览 Surface 变化 → 图传重启（真机实测会崩）。
                    val panelHeight = if (expanded) {
                        (boxH * 0.42f).coerceIn(150.dp, 280.dp)
                    } else {
                        (boxH - 16.dp).coerceAtLeast(120.dp)
                    }
                    Column(Modifier.fillMaxSize()) {
                    PanelsRow(
                        vm = vm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelHeight)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    if (expanded) {
                        // 展开态：下方设置行滚动
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // ── 连接参数 ──
                            if (wide) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HostField(state, vm, hostInvalid, Modifier.weight(1.1f))
                                    PortField(state, vm, portInvalid, Modifier.weight(1.1f))
                                    ConnectedButtonGroup(
                                        labels = state.slotLabels,
                                        selectedIndex = state.slotIndex,
                                        onSelect = vm::selectSlot,
                                        modifier = Modifier.weight(1.6f),
                                    )
                                    // 恢复默认 DHCP：清掉本机静态 IP，交回路由器分配
                                    ConsoleButton(
                                        label = "恢复DHCP",
                                        onClick = vm::restoreDhcp,
                                        kind = ConsoleButtonKind.Outlined,
                                        modifier = Modifier.weight(0.9f),
                                    )
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    HostField(state, vm, hostInvalid, Modifier.weight(1.15f))
                                    PortField(state, vm, portInvalid, Modifier.weight(1f))
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ConnectedButtonGroup(
                                        labels = state.slotLabels,
                                        selectedIndex = state.slotIndex,
                                        onSelect = vm::selectSlot,
                                        modifier = Modifier.weight(1f),
                                    )
                                    ConsoleButton(
                                        label = "恢复DHCP",
                                        onClick = vm::restoreDhcp,
                                        kind = ConsoleButtonKind.Outlined,
                                    )
                                }
                            }

                            // ── 雷达分包 + 串口设备 ──
                            if (wide) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadarBatchLabel(bigText)
                                    RadarBatchControl(state, vm, Modifier.weight(1f))
                                    BaudField(state, vm, Modifier.weight(0.9f))
                                    VidField(state, vm, Modifier.weight(0.8f))
                                    PidField(state, vm, Modifier.weight(1f))
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadarBatchLabel(bigText)
                                    RadarBatchControl(state, vm, Modifier.weight(1f))
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BaudField(state, vm, Modifier.weight(1f))
                                    VidField(state, vm, Modifier.weight(0.85f))
                                    PidField(state, vm, Modifier.weight(1f))
                                }
                            }

                            // ── 编码参数（手机上 3 + 2 两段，否则值会被截断）──
                            if (wide) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ResolutionDropdown(state, vm, Modifier.weight(1f))
                                    FpsDropdown(state, vm, Modifier.weight(1f))
                                    CodecDropdown(state, vm, Modifier.weight(1f))
                                    EncoderDropdown(state, vm, Modifier.weight(1f))
                                    FecDropdown(state, vm, Modifier.weight(1f))
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ResolutionDropdown(state, vm, Modifier.weight(1f))
                                    FpsDropdown(state, vm, Modifier.weight(1f))
                                    CodecDropdown(state, vm, Modifier.weight(1f))
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    EncoderDropdown(state, vm, Modifier.weight(1f))
                                    FecDropdown(state, vm, Modifier.weight(1f))
                                }
                            }

                            // ── 码率 / 网络模式 / 复选框 ──
                            if (wide) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BitrateField(state, vm, Modifier.weight(1f))
                                    ConsoleCheckbox(
                                        checked = state.adaptiveKf,
                                        label = "空闲补关键帧",
                                        onCheckedChange = vm::toggleAdaptiveKf,
                                    )
                                    ConsoleCheckbox(
                                        checked = state.previewOn,
                                        label = "开启画面预览",
                                        onCheckedChange = vm::togglePreview,
                                    )
                                    NetModeDropdown(state, vm, Modifier.weight(1.3f))
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BitrateField(state, vm, Modifier.weight(1.15f))
                                    NetModeDropdown(state, vm, Modifier.weight(1f))
                                }
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ConsoleCheckbox(
                                        checked = state.adaptiveKf,
                                        label = "空闲补关键帧",
                                        onCheckedChange = vm::toggleAdaptiveKf,
                                    )
                                    ConsoleCheckbox(
                                        checked = state.previewOn,
                                        label = "开启画面预览",
                                        onCheckedChange = vm::togglePreview,
                                    )
                                }
                            }

                            // ── 强制接管串口 + 打开无障碍 ──
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ForceSerialButton(state, vm, Modifier.weight(1f))
                                ConsoleButton(
                                    label = "打开无障碍服务",
                                    onClick = onOpenAccessibility,
                                    icon = Icons.Rounded.Add,
                                    modifier = Modifier.weight(1f),
                                )
                            }

                            // ── 上行 / 下行 / 延迟 ──
                            Text(
                                text = "上行码率 ${fmtKbps(state.upKbps)}     " +
                                    "下行码率 ${fmtKbps(state.downKbps)}     " +
                                    "连接延迟 ${if (state.rttMs > 0) "${state.rttMs} ms" else "—"}     " +
                                    "[${state.linkMode}]",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = midText),
                                color = MaterialTheme.colorScheme.onSurface,
                            )

                            // ── 设备自身状态 ──
                            Text(
                                text = "电池温度 ${state.batteryTempC?.let { "%.1f℃".format(it) } ?: "—"}     " +
                                    "剩余电量 ${state.batteryLevel?.let { "$it%" } ?: "—"}     " +
                                    "CPU温度 ${state.cpuTempC?.let { "%.1f℃".format(it) } ?: "—"}",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = midText),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "无障碍状态 ${if (state.accessibilityOn) "已开启" else "未开启"} | " +
                                    "ADB脚本状态 ${if (state.adbOn) "已开启" else "未开启"}",
                                style = MaterialTheme.typography.titleMedium.copy(fontSize = midText),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    }
                }

                // ── 底部四键 ──
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        ConnectedButtonGroup(
                            labels = listOf(
                                "扫码连接",
                                "锁定屏幕",
                                "重新连接",
                                if (expanded) "收起项目" else "展开项目",
                            ),
                            selectedIndex = -1,
                            allFilled = true,
                            onSelect = { i ->
                                when (i) {
                                    0 -> pairingDialog = true
                                    1 -> onLockScreen()
                                    2 -> vm.reconnect()
                                    else -> expanded = !expanded
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (pairingDialog) {
        PairingDialog(vm = vm, onDismiss = { pairingDialog = false })
    }
}

/** 日志 + 预览两块面板（宽度按 640:588 比例，高度由外部给） */
@Composable
private fun PanelsRow(vm: ConsoleViewModel, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LogPanel(vm, Modifier.weight(640f).fillMaxHeight())
        PreviewPanel(vm, Modifier.weight(588f).fillMaxHeight())
    }
}

// ───────────────────────── 行内控件 ─────────────────────────

@Composable
private fun HostField(
    state: ConsoleState,
    vm: ConsoleViewModel,
    invalid: Boolean,
    modifier: Modifier,
) {
    ConsoleField(
        label = "输入连接IP",
        value = state.host,
        onValueChange = vm::onHostChange,
        modifier = modifier,
        leadingIcon = Icons.Rounded.Home,
        isError = invalid,
        supportingText = if (invalid) "IP 形如 192.168.1.3" else null,
    )
}

@Composable
private fun PortField(
    state: ConsoleState,
    vm: ConsoleViewModel,
    invalid: Boolean,
    modifier: Modifier,
) {
    ConsoleField(
        label = "图传端口",
        value = state.videoPort,
        onValueChange = vm::onVideoPortChange,
        modifier = modifier,
        leadingIcon = Icons.Rounded.Search,
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        isError = invalid,
        supportingText = if (invalid) "1–65535，避开非图传 ${vm.reservedPortsText()}" else null,
    )
}

@Composable
private fun RadarBatchLabel(fontSize: androidx.compose.ui.unit.TextUnit) {
    Text(
        text = "雷达分包",
        style = MaterialTheme.typography.titleMedium.copy(fontSize = fontSize, lineHeight = fontSize * 1.35f),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

@Composable
private fun RadarBatchControl(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    Column(modifier) {
        ConsoleSlider(
            value = state.radarBatchPercent,
            onValueChange = vm::onRadarBatchChange,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${(state.radarBatchPercent * 100).toInt()}% · " +
                "${ConsoleViewModel.percentToBatch(state.radarBatchPercent)} 槽/包",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BaudField(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleField(
        label = "串口波特率",
        value = state.baud,
        onValueChange = vm::onBaudChange,
        modifier = modifier,
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
    )
}

@Composable
private fun VidField(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleField(
        label = "串口VID",
        value = state.serialVid,
        onValueChange = vm::onVidChange,
        modifier = modifier,
    )
}

@Composable
private fun PidField(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleField(
        label = "串口PID",
        value = state.serialPid,
        onValueChange = vm::onPidChange,
        modifier = modifier,
    )
}

@Composable
private fun ResolutionDropdown(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleDropdown(
        label = "视频分辨率",
        options = state.resolutionOptions,
        selected = state.resolution,
        onSelect = vm::selectResolution,
        modifier = modifier,
    )
}

@Composable
private fun FpsDropdown(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleDropdown(
        label = "视频帧率",
        options = state.fpsOptions,
        selected = state.fps,
        onSelect = vm::selectFps,
        modifier = modifier,
    )
}

@Composable
private fun CodecDropdown(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleDropdown(
        label = "传输编码",
        options = ConsoleViewModel.CODEC_OPTIONS,
        selected = state.codec,
        onSelect = vm::selectCodec,
        modifier = modifier,
    )
}

@Composable
private fun EncoderDropdown(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleDropdown(
        label = "编码器选择",
        options = ConsoleViewModel.ENCODER_OPTIONS,
        selected = state.encoder,
        onSelect = vm::selectEncoder,
        modifier = modifier,
    )
}

@Composable
private fun FecDropdown(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleDropdown(
        label = "纠错模式",
        options = ConsoleViewModel.FEC_OPTIONS,
        selected = state.fecMode,
        onSelect = vm::selectFec,
        modifier = modifier,
    )
}

@Composable
private fun NetModeDropdown(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleDropdown(
        label = "网络协议层模式",
        options = ConsoleViewModel.NET_MODE_OPTIONS,
        selected = state.netMode,
        onSelect = vm::selectNetMode,
        modifier = modifier,
    )
}

@Composable
private fun BitrateField(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleField(
        label = "码率",
        value = state.bitrate,
        onValueChange = vm::onBitrateChange,
        modifier = modifier,
        leadingIcon = Icons.Rounded.Search,
        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
        supportingText = if (state.bitrate.isBlank()) "留空=自动" else null,
    )
}

@Composable
private fun ForceSerialButton(state: ConsoleState, vm: ConsoleViewModel, modifier: Modifier) {
    ConsoleButton(
        label = if (state.forceSerial) "强制接管中" else "强制接管串口",
        onClick = {
            vm.toggleForceSerial(!state.forceSerial)
            if (!state.laserRunning) vm.startSerialBridge()
        },
        icon = Icons.Rounded.Add,
        kind = if (state.forceSerial) ConsoleButtonKind.Tonal else ConsoleButtonKind.Filled,
        modifier = modifier,
    )
}

// ───────────────────────── 顶栏 / 面板 / 对话框 ─────────────────────────

/** 紧凑顶栏（44dp）：标题 + 状态胶囊 + 图传开关 + 两个二级页入口 */
@Composable
private fun ConsoleTopBar(
    state: ConsoleState,
    onToggleStream: () -> Unit,
    onOpenVisionSettings: () -> Unit,
    onOpenBridge: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "狼牙视觉",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            StatusChip(
                text = if (state.streaming) "图传 ${fmtKbps(state.upKbps)}" else "图传 停止",
                active = state.streaming,
            )
            StatusChip(text = if (state.laserRunning) "串口 在线" else "串口 离线", active = state.laserRunning)
            StatusChip(
                text = if (state.rttMs > 0) "网络 ${state.rttMs}ms" else "网络 ${state.linkMode}",
                active = state.rttMs > 0,
            )
            Spacer(Modifier.weight(1f))
            ConsoleButton(
                label = if (state.streaming) "停止图传" else "启动图传",
                onClick = onToggleStream,
                icon = if (state.streaming) Icons.Rounded.Close else Icons.Rounded.Add,
                kind = if (state.streaming) ConsoleButtonKind.Tonal else ConsoleButtonKind.Filled,
                height = 38.dp,
            )
            IconButton(onClick = onOpenVisionSettings, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = "视觉设置", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onOpenBridge, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Build, contentDescription = "桥接控制台", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, active: Boolean) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 日志容器：**不带标题栏**（一眼能看出是日志，省下 28dp 高度）。
 * 新行自动贴底；长按面板清空。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogPanel(vm: ConsoleViewModel, modifier: Modifier = Modifier) {
    val state = vm.state
    val listState = rememberLazyListState()
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) listState.scrollToItem(state.logs.size - 1)
    }
    ConsolePanel(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        vm.clearLogs()
                        vm.messages.tryEmit("日志已清空")
                    },
                )
        ) {
            if (state.logs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无日志\n启动图传或串口桥后这里会滚动显示\n（长按清空）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(state.logs) { line ->
                        Row(Modifier.padding(vertical = 1.dp)) {
                            Text(
                                line.time,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 预览容器：本地预览（挂在图传会话上，见 ConsoleViewModel.setPreviewSurface） */
@Composable
private fun PreviewPanel(vm: ConsoleViewModel, modifier: Modifier = Modifier) {
    val state = vm.state
    ConsolePanel(modifier) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "摄像机",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (state.streaming) {
                        "${state.frameW}×${state.frameH} · ${state.fpsLive.toInt()}fps"
                    } else {
                        "未推流"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 预览方向可调：相机 buffer 方向随机型/安装方向不同，点一下转 90°
                IconButton(onClick = vm::rotatePreview, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "旋转预览",
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    "${vm.previewRotation}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.width(4.dp))
                ConsoleCheckbox(
                    checked = state.previewOn,
                    label = "预览",
                    onCheckedChange = vm::togglePreview,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val frame = vm.previewFrame
                when {
                    !state.previewOn -> Text(
                        "预览已关闭（勾选「预览」重开）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    frame != null -> Image(
                        bitmap = frame,
                        contentDescription = "本地预览",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.inverseSurface),
                    )
                    else -> Text(
                        "等待画面…（视觉链路出帧后显示）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 扫码连接：优先复用视觉码流扫码，粘帖通道保留为后备 */
@Composable
private fun PairingDialog(vm: ConsoleViewModel, onDismiss: () -> Unit) {
    val state = vm.state
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 扫码成功并已落盘 → 自动关窗
    LaunchedEffect(state.scanApplied) {
        if (state.scanApplied) onDismiss()
    }

    AlertDialog(
        onDismissRequest = {
            vm.stopScan()
            onDismiss()
        },
        shape = RoundedCornerShape(12.dp),
        title = { Text("扫码连接", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "扫码不另开摄像头：相机被图传占着，这里直接读视觉码流（第二路 YUV）解二维码，" +
                        "图传和视觉都不受影响。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.scanning) {
                    ConsoleButton(
                        label = "停止扫码",
                        onClick = vm::stopScan,
                        kind = ConsoleButtonKind.Outlined,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ConsoleButton(
                        label = if (state.visionRunning) "用相机扫码" else "用相机扫码（需先启动图传）",
                        onClick = { vm.startScan() },
                        kind = ConsoleButtonKind.Tonal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                state.scanMsg?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.scanApplied) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "—— 或者直接粘连接码 ——",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ConsoleField(
                    label = "连接码内容",
                    value = code,
                    onValueChange = {
                        code = it
                        error = null
                    },
                    singleLine = false,
                    supportingText = error,
                    isError = error != null,
                )
                Text(
                    "当前配置的连接码：${vm.currentPairingCode()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val err = vm.applyPairingCode(code)
                if (err == null) onDismiss() else error = err
            }) { Text("应用") }
        },
        dismissButton = {
            TextButton(onClick = {
                vm.stopScan()
                onDismiss()
            }) { Text("取消") }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

/** 0 → "0 kbps"，1000+ → "1.2 Mbps" */
private fun fmtKbps(kbps: Float): String = when {
    kbps <= 0f -> "0 kbps"
    kbps >= 1000f -> "%.1f Mbps".format(kbps / 1000f)
    else -> "%.0f kbps".format(kbps)
}

/** 严格 IPv4（四段 0–255），用于输入框即时校验 */
private fun isIpv4(ip: String): Boolean {
    val parts = ip.trim().split('.')
    if (parts.size != 4) return false
    return parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true && p.length <= 3 }
}
