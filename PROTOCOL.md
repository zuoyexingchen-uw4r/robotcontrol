# RobotControl 无线桥接 / 图传协议（v2 — GameStream 兼容）

> 手机固定于机器人，作为无线桥：把 OTG-CH343 串口 与 摄像头 H.264/HEVC 视频 低延迟送到电脑。
> 电脑与手机需在同一局域网（手机可开热点，电脑连之）。
> v2 起视频部分改用 **Sunshine/Moonlight GameStream 包格式**（RTP + NV_VIDEO_PACKET + Reed-Solomon FEC + pacing 限速），
> 与 moonlight-common-c / Sunshine 的 wire format 对齐，方便以后直接接 Moonlight 客户端。

## ⚠️ 2026-10 网络方向变更：电脑监听、手机连接

三条通道的方向已统一为 **电脑开端口监听，手机主动 connect**（旧版相反，是手机开服务端）：

| 通道 | 传输 | 电脑侧 | 手机侧 | 默认端口 |
|---|---|---|---|---|
| 串口透传 | TCP | **监听** | 连接 + 断线重连 | 8765 |
| 第二数据流（预留） | TCP | **监听** | 连接 + 断线重连 | 8766 |
| 图传控制（关键帧请求） | TCP | **监听** | 连接 + 断线重连 | 47000 |
| 图传视频 | UDP | **监听**（`recvfrom`） | 发送 | 46000 |

好处：电脑侧不用维护服务端生命周期，链路方向单一，不会出现"两边都在等对方"的死锁；
手机侧断线由 `TcpChannel` 自动重连。UDP 无连接概念，等价于电脑监听端口。

## ① 串口透传（电脑 ⇄ CH343）

- 传输：**TCP**，**电脑为服务端**（默认端口 `8765`，开端口监听），手机为客户端（主动连接、断线重连）。
- 数据：**透明全双工字节管道**。手机连上后先发一行握手文本：
  ```
  RB1 <baud> otg\n
  ```
  之后双向原样转发，无额外封装（最低延迟）。TCP 本身全双工 + USB 串口 read/write 独立，天然支持双向同时通讯。
- 波特率：默认 `115200`（App 子菜单可改，**会记忆**）。CH343 上限远高于此。
- USB 设备选择：默认按 WCH VID `0x1A86` 匹配；子菜单可开「忽略设备 ID」强制接管
  （用通用 CDC 驱动直接打开指定 USB 设备，VCP 匹配失败时用）。
- 开机自检：第一屏启动时自动检测 CH343 是否连上（顶栏下方文字提示）。
- 电脑端验证：`python host/serial_host.py --bind 0.0.0.0 --port 8765 --send "PING" --nl --hex --after 3`

## ② 摄像头图传（GameStream 视频包格式）

- 视频：**H.264 / HEVC over UDP**（无重传、低延迟），Annex-B 裸流，编码器输出直接切分片。
- 控制：**TCP**（默认 `47000`），**电脑监听该端口**，手机连上后电脑周期性发 4B 大端 `0x0301`（`SS_RFI_REQUEST`）请求立即出 IDR 关键帧。
- 编码：Camera2 Surface 零拷贝 → MediaCodec（H.264 或 HEVC，App 子菜单可选）。
- 编码器选择见下。csd-0/1/2（SPS/PPS 或 VPS/SPS/PPS）缓存并拼到关键帧前。

### UDP 报文 = 32B 头 + 净载荷（对照 Sunshine `stream.cpp` / `video.h`）

```
[RTP 12B][reserved 4B][NV_VIDEO_PACKET 16B][payload]
```

| 段 | 字段 | 偏移 | 长度 | 说明 |
|---|---|---|---|---|
| RTP | header | 0 | 1 | 固定 `0x90`（V=2, X=1） |
| | payloadType | 1 | 1 | `97`（GameStream 视频） |
| | sequenceNumber | 2 | 2 | 全局发包序号（跨帧连续，u16 回绕） |
| | timestamp | 4 | 4 | 90kHz：`ptsUs × 90 / 1e6` |
| | ssrc | 8 | 4 | 随机 |
| — | reserved | 12 | 4 | 0 |
| NV | streamPacketIndex | 16 | 4 | `seq << 8` |
| | frameIndex | 20 | 4 | 帧序号 |
| | flags | 24 | 1 | bit0=`PIC_DATA`(0x01) bit1=`EOF`(0x02) bit2=`SOF`(0x04)；**parity 包 flags=0** |
| | extraFlags | 25 | 1 | bit0=LTR(0x01)，未用 |
| | multiFecFlags | 26 | 1 | `0x10` |
| | multiFecBlocks | 27 | 1 | `(blockNum & 0x3) << 4 \| ((numBlocks-1) & 0x3) << 6` |
| | fecInfo | 28 | 4 | `(pktIdx & 0x3FF) << 12 \| (dataShards & 0x3FF) << 22 \| (pct & 0xFF) << 4` |
| — | payload | 32 | ≤1008 | 净载荷 |

**fecInfo 的 `pktIdx` 是 FEC 块内分片序号**：数据分片 `0..k-1`，校验分片 `k..k+m-1`，跨块重新计数（块号看 `multiFecBlocks`）。

### 短帧头（每帧第一个包的 payload 前 8 字节）

| 字段 | 偏移 | 长度 | 说明 |
|---|---|---|---|
| magic | 0 | 1 | `0x01` |
| frameProcessingLatency | 1 | 2 | 小端，0.1ms 单位 |
| frameType | 3 | 1 | 1=P，2=IDR，4=intra-refresh，5=RFI 后 P |
| lastPayloadLen | 4 | 2 | 小端，**帧最后一个分片的字节数**（截尾用） |
| reserved | 6 | 2 | 0 |

### 分片与 FEC（Reed-Solomon，GF(2^8)，原始多项式 0x11D）

- 切片参数：`packetsize=1024 → blocksize=1040 → 净载荷 1008B`（首包再减 8B 短帧头）。
- 每帧按 ≤ `maxDataShardsPerBlock(pct)` 个数据分片拆 FEC 块（≤4 块）：
  `k ≤ 25500/(100+pct)`，给 parity 留足空间（Cauchy 矩阵要求 `k+m ≤ 255`）。
- parity 数：`m = clamp(ceil(k·pct/100), ≥4, ≤255-k)`，pct 默认 20（App 可改）。
- 校验矩阵（**Cauchy，MDS**）：`C[t][j] = 1/((k+1+t) ⊕ (j+1))`，
  与 Sunshine 的 RS 变体不同但 wire 兼容（FEC 负载对上层不透明）；任意丢失 ≤ m 个分片（数据/校验混合）可恢复。
- 系统码：数据分片原样 + 尾部 m 个校验分片；不足 1008B 的末分片补零，接收端按 `lastPayloadLen` 截尾。
- **pacing 限速**：按目标码率 **80%** 平滑发包，避免突发占满 WiFi 信道导致延迟暴涨。

### 电脑端解码

按 `(frameIndex, blockNum)` 分组缓存；RTP seq 缺段即知丢包位置 → 擦除解码（高斯消元）；
全块到齐后拼接，从数据流头部解析短帧头，按 `lastPayloadLen` 截尾，得到 Annex-B 帧送 ffplay。

## 编码器适配（高通 GPU + 软件回退）

- 自动：优先高通硬编（`c2.qti.*` / 名称含 `qcom`，Snapdragon 6 Gen1 / 8 Gen 系列走 Adreno 硬件通路，按名称特征识别，不写死型号）→ 任意硬编 → 软件（`OMX.google.*` / `c2.android.*`）回退。
- 低延迟：CQBR + `KEY_LATENCY=1`（硬编）+ Baseline/Main profile + 实时优先级（`KEY_PRIORITY=0`）。
- App 子菜单可强制：自动高通硬编 / 强制硬件 / 软件回退；H.264 / HEVC 可选。
- 分辨率/帧率：运行期从 `CameraCharacteristics` 枚举（录制尺寸 ∩ 编码器支持尺寸；AE 帧率范围），不硬编码。

## 电脑端验证

```bash
# 视频：电脑监听 46000(UDP 视频) + 47000(TCP 控制)，等手机连上来
# --host 仍要填手机 IP（仅用于日志/回连提示），实际是电脑监听、手机主动连接
python host/video_host.py --host <手机IP> --vport 46000 --cport 47000 --play
# 或存裸流事后看
python host/video_host.py --host <手机IP> --vport 46000 --out stream.h265

# 串口透传：电脑监听 8765，等手机连接
python host/serial_host.py --bind 0.0.0.0 --port 8765 --send "PING" --nl --hex --after 3

# FEC/组包自测（不连手机，纯本地）
python host/rs_selftest.py
```

> 端口/波特率/FEC 比例/码率可在 App「桥接/图传」子菜单修改，**会自动记忆**；
> 第一屏「图传启停」直接读这份记忆配置。改协议需同步 Android 与 `host/*.py`。
> Kotlin 侧 `ReedSolomon.parityMatrix` 与 Python 侧 `video_host.cauchyRow` 是同一公式，
> 交叉验证（JVM 实跑 vs Python）逐字节一致。

## 视觉指令协议（车载视觉决策 → 下位机）

视觉管线决策经 USB（OTG-CH343）直连下位机发送，**独立于主协议**
（主协议 0xA5 0x5A + CRC16 用于遥测/心跳/底盘）。视觉指令用专用帧头，
下位机按帧头第二字节区分命令类型。待与电控核对后可并入主协议。

### 开火指令（FireController，帧头 0xFA 0xAF）

| 字段 | 长度 | 说明 |
|---|---|---|
| HEAD | 2 | 0xFA 0xAF |
| yaw | 2 | 枪管目标偏航角（i16，0.01°，右为正） |
| pitch | 2 | 枪管目标俯仰角（i16，0.01°，含弹道补偿，上为正） |
| rpm | 2 | 摩擦轮目标转速（u16 RPM） |
| fire | 1 | 0=不击发 1=击发 |
| XOR | 1 | 前 9 字节异或 |

### 导航速度指令（Navigation，帧头 0xFA 0xBF，占位）

| 字段 | 长度 | 说明 |
|---|---|---|
| HEAD | 2 | 0xFA 0xBF |
| linear | 2 | 线速度（i16，mm/s，前为正） |
| angular | 2 | 角速度（i16，mrad/s，左转为正） |
| XOR | 1 | 前 6 字节异或 |

> 发送条件：USB 通道 Connected 才发（WLAN 降级不发，开火延迟敏感）。
> 序列化实现：`FireController.serialize()` / `Navigation.serialize()`。

## 电脑 HUD 协议（笔记本操控主控）

电脑端 PyQt HUD（pc_hud/）与手机、ESP32-S3 的三条链路。端口约定如下，
帧格式实现见 `pc_hud/warhud/protocol.py`（自测 13/13）。

### 1. 图传（既有链路，不变）

手机 CameraStreamer → UDP `video_port`（默认 31000）裸 H.264/HEVC + RS-FEC。
HUD 用 ffmpeg 子进程解码：`-flags low_delay` + `udp://...?fifo_size=8388608&overrun_nonfatal=1`。

### 2. 视觉结果 + 设置同步（UDP）

| 方向 | 端口 | 消息 | 频率 |
|---|---|---|---|
| 手机 → 电脑 | UDP 31001（广播） | `vision` 视觉结果帧 | 视觉帧率（~15Hz） |
| 手机 → 电脑 | UDP 31001（广播） | `settings` 设置快照 | 500ms 心跳 |
| 电脑 → 手机 | UDP 31002（单播） | `hello` 上线宣告 | 连接时一次 |
| 电脑 → 手机 | UDP 31002（单播） | `set_req` 设置变更 | 用户操作时 |

- **权威源是手机 SharedPreferences**：电脑改 → `set_req` 发手机 → 手机写入 →
  下一次 `settings` 广播全量确认 → 电脑刷新 UI。电脑乐观更新 + 快照校验。
- `vision` 帧 JSON：`{t, ts, fps, lat_ms, frame_w, frame_h, targets[], track{}, fire{}, proc_ms{}}`。
  targets 元素 `{type: highland|base, cx, cy, w, h, conf}`，坐标为手机视觉
  基准分辨率（默认 640×480，但以帧内 `frame_w`/`frame_h` 为准——v0.6.1 起
  视觉会话跟随图传分辨率，不再写死）；track 含 `vx/vy/dist_mm/yaw_deg/pitch_deg`。
- **v0.6.1 已实现手机端发送**：`vision/VisionUdpLink.kt` 绑定 31002 收 `hello`/`set_req`
  并回广播 `settings`，同时经同一 socket 广播 `vision`；远程设置仅接受白名单键 +
  类型校验（`VisionSettings.applyRemote`）。视觉结果只在管线真正重算的帧广播，
  节流复用帧不重复发送、不重复开火。

### 3. 操作输入 + 遥测（WLAN TCP，电脑直连 ESP32-S3）

| 方向 | 端口 | 帧 |
|---|---|---|
| 电脑 → ESP32 | TCP 31010 | 输入帧 `0xFA 0xAE` |
| ESP32 → 电脑 | TCP 31011 | 遥测帧 `0xFA 0xEA` |

**输入帧**（13 字节）：`0xFA 0xAE | yaw i16(0.01°) | pitch i16(0.01°) | fire u8 | mx i16(mm/s) | my i16(mm/s) | btn u8 | XOR`

- 10Hz 定步长，**仅状态变化或 fire 边沿时发包**（ESP32 保持最后状态）
- fire 边沿触发：电脑长按也只发一帧 True，击发逻辑在下位机

**输入源（v0.5.1 起主操控为 USB 手柄）**：HUD 通过 pygame/SDL 轮询手柄
（`warhud/gamepad.py`），映射如下；未插手柄自动降级键鼠（鼠标捕获 FPS 云台）。

| 手柄输入 | 帧字段 | 说明 |
|---|---|---|
| 左摇杆 / 十字键 | mx / my | 底盘平移（mm/s，满格 800，死区 0.12 + 指数曲线） |
| 右摇杆 | yaw / pitch | 云台角速度积分（满格 90°/s，LT 扳机 ×0.35 微瞄） |
| RT 扳机 | fire | 边沿触发一帧 |
| A / B / X / Y / START / SELECT | （本地动作） | 自动开火开关 / 切换追踪目标 / 叠加显隐 / 设置面板 / 计时暂停 / 面板显隐 |

序号差异在 `~/.warhud/config.json` 用 `gamepad_map` 覆盖（成对覆盖，
避免 aim_x 与 aim_axis 撞号）。

**遥测帧**（14 字节）：`0xFA 0xEA | voltage u16(mV) | current u16(mA) | temp i16(0.1°C) | rpm_l u16 | rpm_r u16 | status u8 | XOR`

### 操作权仲裁

电脑接入即接管：手机 APP 检测到电脑输入帧在线时禁用本机操作输入，
退化为纯视觉+图传节点。ESP32 只透传不仲裁。

### 电控侧待办

- ESP32-S3 写 WLAN TCP server：31010 收输入帧 → 解析后经光电隔离 UART
  转 CH32X03S；31011 推遥测帧（CH32X03S 数据 10Hz 汇总）
