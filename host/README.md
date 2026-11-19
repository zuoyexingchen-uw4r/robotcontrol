# 上位机开发说明（狼牙视觉 · 电脑端）

> 本文档写给接手**上位机（电脑端）完善工作**的程序员。你可以看到工作区的全部文件，
> 本文档帮你快速建立全局认知、找到入口、明确待办与测试方法。

---

## 1. 项目是什么

**狼牙视觉（LangyaVision）**：RoboMaster 赛制射击机器人（R1）的车载视觉 + 操控系统。

整体链路（三段）：

```
ESP32/S3 主控 ──UART── CH343 ──USB-OTG── 小米手机(视觉节点) ──WiFi── 笔记本(上位机)
    ▲               雷达(230400,55AA/60B) ──串口──┘
```

- **手机**（Android/Kotlin，`app/`）：摄像头视觉 + 图传 + 激光雷达/主控混合桥。
- **上位机**（PC，`host/` + `pc_hud/`）：接收视频/雷达/主控数据、渲染 HUD、下发指令。
- **电控**（`firmware/`）：ESP32 参考代码。

你负责的是 **上位机** 这一块（`host/` 与 `pc_hud/`，以及和它们打交道的协议）。

---

## 1.5 v0.7 新协议（先读这段）

非图传数据不再分 8767/8768/31001/31002 四条通道，改成**一条复用流**：

| 用途 | 传输 | 端口 |
|---|---|---|
| 图传视频 | UDP | 47000 |
| 图传控制（关键帧请求） | TCP | 47000（同号，UDP/TCP 不冲突） |
| 非图传（雷达 / 主控 / 视觉 / 设置 / 保活） | UDP 好网 / TCP 差网 | 48000 / 49000 |

上位机是**监听方**，手机只出站。协议细节见仓库根 `NETSTACK.md`，
实现见 `host/netstack/`（`proto / fec / link / ipcfg / pairing`）。

```bash
python host/netstack/selftest.py     # 38/38，纯逻辑，不开端口
python host/spaa_host.py             # 控制台
python host/spaa_host.py --check     # 依赖与端口自检
```

界面快捷键：

| 键 | 作用 |
|---|---|
| `0` | 弹出配对二维码（本机 IP + 视频端口 + 槽位/队伍），手机扫码即连 |
| `F2` | 设置：IP 模式（RR1/RR2/BR1/BR2/DHCP）、网卡下拉、端口、槽位 |

- 改 IP 走 `netsh`，非管理员会自动弹 UAC 提权；四个地址来自校内赛协议 §2.2
  （终端 .13/.14/.15/.16）。
- 二维码内容：`{"v":1,"host":...,"ip":...,"port":47000,"slot":"R1","team":"red","udp":48000,"tcp":49000}`，
  与 Android `net/Pairing.kt` 对齐，手机只读 `ip/port/slot`。
- 额外依赖：`qrcode`（出二维码）、`psutil`（枚举网卡）。没装 qrcode 也能跑，只是二维码退化成文本。

> ⚠️ **48000 可能被占用**：Sunshine/Moonlight 默认占用 47989~48010，实测本机 48000
> 就在 Sunshine 手里（`Get-NetUDPEndpoint -LocalPort 48000`）。启动日志会明确报
> “绑定失败”，此时在 F2 里换一个端口（如 48050），并同步手机端。

---

## 2. 文件地图（重点看这些）

| 路径 | 作用 | 状态 |
|---|---|---|
| `host/laser_host.py` | 激光雷达 + 主控混合流上位机（TCP+UDP+FEC） | ✅ 可跑，自测 10/10 |
| `host/video_host.py` | 图传视频接收（GameStream/RS-FEC 解码） | ✅ 已有 |
| `host/spaa/` | **图传上位机**（GL 纹理显示 + 叠加层，SPAA 信息布局） | ✅ 新增，自测 28/28 |
| `host/rs_selftest.py` | RS-FEC 自测 | ✅ 22/22 |
| `host/serial_host.py` | 串口透传测试 | ✅ 已有 |
| `pc_hud/` | 笔记本操控 HUD（PyQt5） | ✅ 已有 |
| `pc_hud/warhud/laser*.py` | （待补）激光点云叠加到 HUD | ⏳ 待办 |
| `LASER_PROTOCOL.md` | 激光+主控混合协议 v3（权威） | ✅ |
| `PROTOCOL.md` | 图传/串口/HUD 协议 | ✅ |
| `../laser_lader/测试上位机_优化版.py` | 你团队现成的雷达 360° 点云可视化 | ✅ 参考 |

---

## 3. 激光 + 主控混合流（你主要要完善的部分）

### 3.1 协议速览（详见 `LASER_PROTOCOL.md` v3）

两类帧都**定长**、靠帧头区分：

| 帧 | 帧头 | 长度 | 说明 |
|---|---|---|---|
| 雷达点云 | `55 AA` | 60 字节 | 原样透传，不解析 |
| 主控帧 | `AA 55` | 39 字节 | `type(1)+seq(2)+16×int16+CRC16(2)` |

**上行（雷达 + 主控遥测）走 UDP + Reed-Solomon 前向纠错**（不重传）：
- UDP 报文 = `[block_id u8][shard_idx u8][shard 64B]`；
- shard = `[type u8][frame(≤60B)][补零]`；块 = `k=8` 数据分片 + `m=4` 校验分片；
- 丢 ≤4 个分片可擦除解码恢复（Cauchy MDS，GF(2^8)）。

**下行（主控指令）走 TCP**：`[type=0x02][主控帧 39B]`，透传下 UART。

端口：TCP `8767`、UDP `8768`（激光混合流）。

### 3.2 运行上位机

```bash
cd host
python laser_host.py --selftest          # 自测（期望 10/10）
python laser_host.py                     # 监听 TCP 8767 + UDP 8768
python laser_host.py --print-lidar --print-master   # 打印雷达摘要 + 主控字段
```

手机端：主界面点「激光桥」启动前台服务，手机就会把 CH343 收到的雷达+主控流
经 UDP+FEC 发到电脑、TCP 建下行连接。

---

## 4. 待办（你接手后的完善方向）

按优先级：

1. **激光点云可视化接入 HUD**：`laser_host.py` 现在把雷达帧交给 `LidarParser.feed()`
   产出 `(angle, distance, intensity)` 点列表，但还没有画出来。参考
   `../laser_lader/测试上位机_优化版.py` 里的 `LidarCanvas`，把点云画进
   `pc_hud/warhud/` 的 HUD 画布（或独立窗口）。
2. **主控字段语义定稿**：`LASER_PROTOCOL.md` §3 的 16 个 int16 字段名是**占位**，
   需要和电控一起定下每个字段的实际含义（当前 `UP_FIELD_NAMES` / `DOWN_FIELD_NAMES`
   在 `laser_host.py` 里）。定稿后同步改 `app/.../LaserProtocol.kt` 和 `firmware/`。
3. **下行指令下发**：`LaserHost.send_master_down(seq, fields)` 已实现，但还没有
   对接操作输入（手柄/键鼠）→ 组下行帧 → 发给手机。可参考 `pc_hud/warhud/esp32.py`
   的输入帧逻辑，改成组主控下行帧。
4. **FEC 参数调优**：`FEC_K=8 / FEC_M=4`（丢 ≤4/8 可恢复，冗余 50%）。实测网络丢包率后
   可调 `LASER_PROTOCOL.md` 与 `app/.../LaserProtocol.kt` 的 `FEC_K/FEC_M`。
5. **IP 切换联调**：`scripts/switch_ip.sh` 改手机静态 IP、`app` 主界面「IP:」按钮切换
   槽位（R1/R2/B1/B2）。真机联调时验证「切槽位 → 通知脚本改 IP → 图传/激光重连」闭环。
6. **端到端联调**：视频 + 激光 + 主控 + HUD 一起跑，验证低延迟与稳定性。

---

## 5. 测试方法

```bash
# 激光混合流（含 FEC 恢复能力）
python host/laser_host.py --selftest        # 期望 10/10
# 图传 RS-FEC
python host/rs_selftest.py                   # 期望 22/22
# 图传上位机（协议/投影/组帧，不需要窗口）
python host/spaa_host.py --selftest          # 期望 28/28
# 图传上位机端到端（ffmpeg 造流 → 本机收 → 解码 → 截图，不需手机）
python host/spaa/e2e_test.py
# HUD 冒烟（需 PyQt5/pygame）
QT_QPA_PLATFORM=offscreen python pc_hud/smoke_test.py   # 期望 20/20
# HUD 协议 + 手柄
python pc_hud/warhud/protocol.py            # 13/13
python pc_hud/warhud/gamepad.py             # 19/19
```

HUD 依赖：`PyQt5`、`pygame`、`Pillow`、`imageio-ffmpeg`（ffmpeg 可放 `pc_hud/bin/`）。

---

## 6. 关键约定（改代码前必读）

- **不重传**：数据流靠 FEC 恢复丢包、靠 CRC/seq 感知坏帧，**不要引入重传**（低延迟优先）。
- **帧头别猜**：雷达帧 `data[2:4]` 是「保留/未解析」，**绝不能当帧类型/长度**——这是历史 bug。
- **小端**：主控帧与雷达帧全部小端（LE）。
- **跨语言一致**：`laser_host.py`（Python）与 `LaserProtocol.kt`（Kotlin）的主控帧
  字节必须逐字节一致（已用 CRC 交叉核对过）。改字段时两边 + `firmware/` 一起改。
- **适度解析边界**：手机只切帧、不解析内容；字段语义解析全在上位机。改协议字段
  不影响手机端。

---

## 7. 环境

- Python 3.13（HUD 用 `~/.workbuddy/binaries/python/envs/default`，已装 PyQt5/pygame）。
- 电脑需监听端口：视频 UDP 47000、图传控制 TCP 47000、非图传 UDP 48000 / TCP 49000。
  （v0.7 起 8765/8766/8767/8768/31001/31002 全部废弃，见 `NETSTACK.md`）
- 手机通过 adb 连接（`scripts/laser_recover.sh`、`scripts/switch_ip.sh` 依赖 adb）。

有疑问先看 `LASER_PROTOCOL.md`（激光/主控）和 `PROTOCOL.md`（图传/串口/HUD）。
