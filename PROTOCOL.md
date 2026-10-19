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
