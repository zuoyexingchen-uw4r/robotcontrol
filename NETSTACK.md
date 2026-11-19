# 非图传网络栈（NETSTACK v1）

> 适用范围：`app/src/main/java/com/robomaster/control/net/`
> 状态：Android 侧已实现；上位机侧协议待实现（见 §10）。
> 取代：TCP 8765 / 8766 / 8767、UDP 8768、UDP 31001 / 31002 这五条旧通道。

---

## 1. 只有两条信号

| 类别 | 内容 | 承载 | 特征 |
|---|---|---|---|
| **图传信号** | 视频 + 图传控制（关键帧请求） | 视频 UDP **47000**，控制 TCP **47000** | UDP 与 TCP 端口空间独立，同号不冲突 |
| **非图传信号** | 雷达 / 主控遥测 / 视觉 / 设置 / 本机操作 | UDP **48000**（好网）<br>TCP **49000**（差网，常连备份） | 一条流，帧头分流 |

约定：
- **上位机监听，手机主动连出**；手机不监听任何端口（非图传链路只在自己的临时端口上
  `bind(0)` 收 UDP 回包，这是客户端行为，不是监听服务）。
- 端口**固定**，不按基址递推：`NetProto.VIDEO_PORT=47000 / UDP_PORT=48000 / TCP_PORT=49000`
  与 `host/netstack/proto.py`、`host/spaa/config.py` 逐字一致。
  （v0.8.0 的 Kotlin 侧曾写成 32768/32769/32770，与上位机对不上，v0.8.1 已修。）
- 扫码配对只交换四样：`host`（上位机名字）、`ip`、`port`（视频端口）、`slot`（R1/R2/B1/B2）。
  扫描走**视觉码流**（`net/QrFromVision.kt`）：不新开摄像头，直接读视觉第二会话的
  YUV `planes[0]` 降采样后喂 ZXing，图传与视觉都不受影响；粘帖通道保留为后备。

---

## 2. 60 字节定长槽

非图传流的最小单位是 60B 槽，**内容就是 UART 帧原样**，前两字节既是 UART 协议自己的帧头，
也是本网络流的路由键。

| 帧头 | 方向 | 去向 | 实际写 UART 的长度 |
|---|---|---|---|
| `55 AA` | 上行 | 雷达点云 → 上位机 | — |
| `AA 55` | 上行 | 主控遥测 → 上位机 | — |
| `4B 41` | 双向 | KEEP_ALIVE，上位机原样回显 | — |
| `FA AF` | 下行 | 开火指令 → UART | 10B |
| `FA BF` | 下行 | 导航指令 → UART | 7B |
| `FA 5B` | 下行 | 原始字节 → UART（`FA 5B \| len \| bytes`，每槽 ≤55B） | len |
| `FA 5A` | 下行 | **本机操作**，不写串口（`01` 切 IP / `02` 重连 / `03` 清统计） | — |

主控帧目前仍是 39B，**尾部补零到 60B**；写 UART 时按协议长度截断，补的零不会下发。
`UartSlotFramer` 会检测后面 21 字节是否全零，固件以后改成 60B 也能直接吃下，不用改 App。

---

## 3. 网络包

```
[包头 16B][槽 × n]        n = 1 / 10 / 20（滑杆）
[包头 16B][校验槽 60B]    每 5 个数据槽 1 个
[包头 16B][JSON 分片]     长帧，≤1200B/包
```

### 包头 16B（小端）

| off | len | 字段 | 说明 |
|---|---|---|---|
| 0 | 2 | magic | `4C 56`（"LV"） |
| 2 | 1 | ver | `01` |
| 3 | 1 | flags | bit0=校验包 bit1=长帧 bit2=紧急 |
| 4 | 2 | seq | 包序号，每包 +1；**重放副本保持同号** |
| 6 | 2 | blockId | FEC 块号；长帧时为分片组号 |
| 8 | 1 | shardStart | 包内首槽的块内索引 0..4；校验包 = 5；长帧 = 分片序号 |
| 9 | 1 | count | 槽数 1..20；长帧 = 分片总数 |
| 10 | 2 | ts | 毫秒低 16 位（仅诊断） |
| 12 | 2 | payloadLen | 长帧字节数，定长包填 0 |
| 14 | 2 | crc16 | 对 0..13 计算（MODBUS，poly 0xA001） |

接收端还原槽的全局序号：`globalIdx = blockId × 5 + shardStart`，之后逐槽 +1。
一个包可以跨块，块边界按 `globalIdx / 5` 计算。

---

## 4. 攒包（滑杆 1 / 10 / 20）

| 内容 | 攒包 | FEC | 时延 |
|---|---|---|---|
| 雷达点云（UDP） | 滑杆值 | 5+1 | 满 n 即发 |
| 雷达点云（TCP） | **强制 20** | 5+1 | 满 20 即发 |
| 主控遥测 | 随点云队列 | 5+1 | 同上 |
| 控制 / 本机操作 | **1** | 5+1 | 立即，插队，不等待 |
| 视觉 / 设置 JSON | 长帧 1 包 | 不进块 | 立即 |

MTU 预算：20 × 60B = 1200B，加包头 1216B，加 IP/UDP 头 28B = 1244B < 1500B，不会 IP 分片。
数据槽凑不满滑杆值时最多等 80ms 就发，避免低速时饿死（`UartLink.HOLD_MS`）。

> 攒得越大，单包丢失一次丢的槽越多（20 槽 = 4 个 FEC 块，只能救 1 槽）。
> 雷达数据不重要可以随便攒；控制帧永远不攒。

---

## 5. FEC：5 数据 + 1 校验（20%）

- 复用视频链路的 `ReedSolomon`（Cauchy MDS，GF(2^8)，poly 0x11D）；
- 一块 6 槽，任意丢 1 槽可恢复；`FecBlock.recover()` 只做「丢一个数据槽」的逆运算，
  不需要高斯消元，Python 端实现也就十几行；
- **校验槽必须单独成包**。和数据同包时整包丢失会把校验一起带走，等于没加冗余；
- 尾块不足 5 槽**不生成校验**：补零会让接收端分不清「补的零」和「真实零槽」，
  恢复出来的假槽可能被写进 UART。

---

## 6. 控制帧重放

- 滑杆 0 / 1 / 2 次；`N` 次 = 首发 + N 份副本，**字节完全相同**（seq 不变）；
- 接收端按 `(seq, blockId, shardStart)` 去重，重复包只处理第一次；
- TCP 下不重放（本身可靠，重放只浪费带宽）；
- JSON 长帧不重放（视觉 15Hz、设置 500ms 心跳本身就有冗余）。

---

## 7. KEEP_ALIVE

每 500ms 生成一个 `4B 41` 槽，**塞进和雷达同一条数据队列**（因此也参与攒包与 FEC），
用来同时观察两件事：

1. **网络延迟**：槽里带发送时刻（`elapsedRealtime` 低 32 位），上位机原样回显，
   手机算 RTT；
2. **整列健康度**：它能暴露「攒包顺序、FEC 块错位、发送队列堵塞」这类只影响批量
   数据、不影响控制帧的问题——因为它和点云走同一条路。

上位机只要把收到的 KA 包**原字节回发**即可，无需解析。若 2.5s 收不到回显，
且此前收到过回显（说明上位机确实支持），自动判定 UDP 不健康。

---

## 8. UDP / TCP 自适应

| 模式 | 行为 |
|---|---|
| `AUTO`（默认） | 收到过 KA 回显且 2.5s 内持续有回显 → UDP；否则切 TCP |
| 强制 UDP | 不看健康度 |
| 强制 TCP | 不看健康度 |

- TCP **常连**（断线 2s 重连），切换只换数据面，不重建连接、不丢已经入队的槽；
- TCP：`TCP_NODELAY` 关 Nagle，分帧 `[u32 len][packet]`，与 UDP 共用同一套报文；
- 从没收到过 KA 回显时（上位机还没实现回显）保持 UDP，避免误判成「网络差」。

---

## 9. 代码结构与 API

```
net/
├── NetProto.kt       端口/帧头/flags/CRC16
├── PacketCodec.kt    16B 包头编解码 + 去重键
├── FecBlock.kt       5+1 编码与单擦除恢复
├── UartSlot.kt       槽构造/解析、KA、本机操作、原始字节分片
├── UartSlotFramer.kt USB 字节流 → 60B 槽（兼容 39B/60B 主控帧）
├── UartLink.kt       单流复用：攒包 / 重放 / KA / 双承载 / 统计
├── NetCore.kt        进程级单例 + 引用计数 + JSON 分发 + UART 写入入口
└── Pairing.kt        扫码配对编解码（扫描入口未接）
```

```kotlin
// 取链路（第一个调用者负责创建，最后一个释放者负责关闭）
val link = NetCore.acquire("laser", host, ::log) ?: return

link.batch = 10          // 滑杆 1 / 10 / 20
link.replay = 1          // 0 / 1 / 2
link.mode = UartLink.Mode.AUTO

link.onSlot = { slot -> /* 下行槽：写 UART 或本机执行 */ }
link.onStats = { st -> /* 1Hz：mode / rtt / tx / rx / dup */ }
link.addStatsListener { st -> /* 额外订阅者（控制台上下行显示），与 onStats 并行 */ }

link.pushSlot(UartSlot.localOp(NetProto.OP_SWITCH_IP, ipBytes), urgent = true)
link.sendJson("""{"t":"vision",...}""")
```

`NetCore.uartWriter` 是「当前 USB 持有者」的写入口。视觉开火走它，
避免再 new 一个 `UsbSerialTransport` 去抢同一个 CH343。

---

## 10. 上位机实现（Python，已落地）

代码在 `host/netstack/`，不依赖 Qt，可单独自测：

| 文件 | 内容 |
|---|---|
| `proto.py` | 60B 槽 / 16B 包头 / CRC16 / 下行槽构造 |
| `fec.py` | 5+1 RS（GF(2^8) 0x11D，Cauchy），单擦除恢复 |
| `link.py` | UDP 48000 + TCP 49000 监听，去重 / 合块 / **KA 回显** / 长帧重组 / 下行 |
| `ipcfg.py` | 五个 IP 模式（RR1/RR2/BR1/BR2/DHCP）、网卡枚举、netsh 应用与 UAC 提权 |
| `pairing.py` | 二维码内容（与 Android `Pairing.kt` 对齐）与图像生成 |
| `selftest.py` | 38 项：CRC/包/槽/FEC/去重/合块/JSON/命令/二维码 |

接入点：

- `host/spaa/rx_aux.py` 用 `NetLink` 取代旧的 8768 + 31001 两个 socket；
- `host/spaa/window.py`：`0` 出配对二维码，`F2` 开设置（IP / 网卡 / 端口 / 槽位）；
- `host/spaa/settings_dialog.py`、`qr_dialog.py`：PyQt5 界面；
- `python host/spaa_host.py --selftest` 会同时跑 SPAA 与 netstack 两套自测。

运行态行为：

1. 上位机监听 UDP 48000、TCP 49000；手机连上后先收到 `{"t":"hello"}`，随后回设置快照；
2. 收到 KEEP_ALIVE 槽**立即原字节回显**（不走块重组），手机据此算 RTT、判断是否切 TCP；
3. 按 `(seq, blockId, shardStart)` 去重；块缺 1 槽且有校验包时恢复，缺更多只发剩下的；
4. 长帧按 `blockId` 分片重组，`settings` 快照存在 `NetLink.settings`，`vision` 交给 HUD；
5. 下行：`send_uart(bytes)` 生成 `FA 5B` 槽，`send_local_op()` 生成 `FA 5A` 槽。

> ⚠️ **端口占用**：48000 与 Sunshine/Moonlight 的 47989~48010 冲突完全是常态
> （开发机实测被 Sunshine 占用）。`F2` 里可改端口，但手机端必须一致，
> 所以改完要同步 Android `Settings.netUdpPort/netTcpPort` 并重启 App。

---

## 11. 迁移状态

| 旧通道 | 现在 |
|---|---|
| 视频 UDP 46000 | 视频 UDP 47000 |
| 控制 TCP 47000 | 不变（与视频同号） |
| 串口透传 TCP 8765 | 非图传流 `FA 5B` 槽（`SerialBridge` 保留但不要启动） |
| 数据流 2 TCP 8766 | 删除 |
| 激光上行 UDP 8768 | 非图传流 48000 |
| 激光下行 TCP 8767 | 删除；PC 改用 `FA 5B` / `FA 5A` |
| 视觉 + 设置 UDP 31001 / 31002 | 非图传流长帧 JSON |
| 切 IP UDP 8769 | 过渡期保留；上位机支持本机操作后删除 |
| 主控遥测 TCP 8888 | 非图传流（USB 仍是主路径） |

已知限制：
- 上位机未升级前，新链路发出去没人接——联调时先用 `net/` 的单测或抓包核对；
- `MainViewModel`（旧第一屏的状态机）已被 v0.8 的 `ConsoleViewModel` 取代，不再实例化，
  因此开机不会再抢 CH343；USB 所有权实际归 `LaserBridge`（经 `NetCore.uartWriter` 暴露）。
- v0.8 控制台已接：攒包滑杆（1/10/20）、网络协议层模式（AUTO/UDP/TCP）、上下行码率与
  RTT 显示；控制帧重放次数仍在 `Settings.netReplay`（界面暂未放滑杆）。
