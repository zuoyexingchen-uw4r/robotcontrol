# CHANGELOG

本文档记录 RobotControl（狼牙视觉）的版本变更。格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [0.8.1] — 2026-11-19（端口按规格固定 + 恢复 DHCP + 从视觉码流扫码）

> 面向多机联调：把"手机偷偷开端口"和"扫码要枪相机"两个坑一并堵掉。

### 修复 / 变更

- **端口常量对齐**（`net/NetProto.kt`）：原来写的是 32768/32769/32770，与
  `NETSTACK.md`、`host/netstack/proto.py`、`host/spaa/config.py` 的
  **47000 / 48000 / 49000** 不一致 —— 两侧各按自己的常量跑必然不通。
  现在固定为：图传 47000（视频 UDP 与图传控制 TCP 同号，UDP/TCP 端口空间独立）、
  非图传 48000（UDP 好网）/ 49000（TCP 差网）。`PORT_BASE` 保留为视频端口别名。
- **手机零监听复核**：全仓库已无 `ServerSocket`、无固定端口 `bind`；非图传链路只在
  临时端口上 `bind(0)` 收回包（客户端行为，不是监听服务）。
- **新增「恢复DHCP」按钮**（`ConsoleViewModel.restoreDhcp` + 控制台 IP 行）：
  清掉 `Settings.robotIp` 的静态地址记忆，并广播 `switch_ip dhcp` 给
  `scripts/switch_ip.sh --listen`，由电脑端 adb 摘掉静态地址、重新走 DHCP。
- **`scripts/switch_ip.sh` 支持 dhcp**：新增 `set_dhcp()`（`dhcptool` → 回退
  「关/开 WiFi」触发系统重新 DHCP → 复查 `ip -4 addr`），CLI 参数与监听分支都认 `dhcp`。
- **手机端扫码改为复用视觉码流**（`net/QrFromVision.kt`）：
  相机被图传会话占着，另开扫码页必然抢不到 camera。改为把视觉第二会话的
  YUV `planes[0]`（亮度）降采样到最长边 720 后直接喂 ZXing `QRCodeReader`，
  15fps 流里每 6 帧解一次（≈2.5 次/秒），图传与视觉都不受影响。
  识别成功 → `Pairing.parse` → 落盘 → 自动关窗；粘贴通道保留为后备。
- 依赖：新增 `com.google.zxing:core:3.5.3`（纯 Java，无 NDK）。

### 联调前必读

- 电脑先起上位机（占 47000/48000/49000），手机再点「启动图传」；手机全程只出站。
- 扫码需要视觉码流在跑（图传已启动），因为扫码不另开相机。

## [0.8.0] — 2026-11-18（主控制台重做：Jetpack Compose + M3 浅色）

> 目标：把第一屏从「XML 表单堆叠」换成横屏平板（1280×800dp 基准）的控制台，
> 每个控件都必须连到真实模块，而不是摆着好看。

### 新增（UI 层，`ui/console` + `ui/theme`）

- `ui/theme/LangyaTheme.kt`：Purple 浅色配色方案（角色名集中定义，界面代码零硬编码
  色值）、收紧的形状档位、Roboto 排版、`ConsoleMotion`（等价 MotionScheme.standard：
  300/200/150ms tween，不用 spring，不回弹）。
- `ui/console/Controls.kt`：按规格调过的标准组件 —— 56dp 中号按钮（外侧圆角 = 半高）、
  相连按钮组（3dp 间距、内侧圆角 8dp）、56dp 描边输入框（16dp 圆角）、
  exposed dropdown（菜单 surfaceContainer / 4dp / 项高 48dp）、18dp 复选框、
  粗轨道滑块（轨道 16dp、手柄 4×44dp、左 primary 右 secondaryContainer）、
  容器框（surfaceContainerHigh / 28dp）与 1dp 分割线。
- `ui/console/ConsoleScreen.kt`：屏幕分两层 —— **折叠态**（顶栏 44dp + 日志/预览面板撑满 +
  底部四键：扫码连接 / 锁定屏幕 / 重新连接 / 展开项目，一屏不滚动）与 **展开态**
  （面板压到 42% 高，设置行可滚动，第四键变「收起项目」）。
- `ui/console/ConsoleViewModel.kt`：界面所有动作的落点（见下表）。
- `MainActivity` 改为 `ComponentActivity` + Compose；旧的 XML 页面
  （`BridgeActivity` / `VisionSettingsActivity`）保留为二级页。

### 真机验证与修正（ALI-AN00，1200×2652@520dpi → 816×369dp 横屏）

- **布局改成按比例**：宽度一律 weight（日志:预览 = 640:588）、面板高度 = 可用高 × 0.42〜0.46、
  断点 `maxWidth ≥ 1000dp` 时才用规格的一行排布；手机上 5 个下拉拆 3+2、槽位组独占一行。
  固定 640dp 会把预览挤成竖排字（真机实测）。
- **日志面板去掉标题栏**（一眼能看出是日志，省 28dp），清空改为长按日志区。
- **顶栏压到 44dp**（标题 titleSmall、状态胶囊 labelSmall、图传按钮 38dp）。
- **预览方向修正**：真机上相机画面转了 90°（圆柱横躺）。TextureView 用 Camera2 官方
  `configureTransform`（setRectToRect → 等比放大 → 按 displayRotation 旋转 ±90°），
  现在画面铺满 16:9、内容正立。
- **预览改挂图传会话**：该机型不支持「720p 编码会话 + 第二路 YUV 会话」并发
  （第二路卡在 createCaptureSession 无回调），预览改由 `CameraStreamer.Config.previewSurface`
  承载；第二路只在有视觉开关时启动，并加 4s 看门狗，配不上就关掉。
- 预览控件从 SurfaceView 换 **TextureView**：SurfaceView 在 Compose 里拿不到
  `surfaceCreated`（黑屏死框），TextureView 走普通 View 绘制稳定。
- 预览 buffer 显式 `setDefaultBufferSize(1280, 720)`：直接用 view 尺寸会让 HAL 静默丢流。
- 有预览时才用 `TEMPLATE_PREVIEW` 请求；无预览仍走原来的 `TEMPLATE_RECORD` 低延迟管线。
- `Settings.videoPort` 默认值 46000 → **47000**（PROTOCOL.md v0.7 已改端口，代码默认值忘了跟）。
- IP 输入自动规范化：中文输入法常把 `.` 打成全角「。」，现在全角点/顿号/逗号一律转半角点，
  并过滤非数字字符。
- 日志同时镜像到 logcat（`logcat -s LangyaConsole`），现场只连 adb 时不用盯 4 行高的面板。
- **崩溃修复**：折叠/展开切换会重建 TextureView → 预览 Surface 变化 → 图传 stop/start
  竞态，`CameraStreamer.onConfigured` 在 device 已关闭时抛
  `IllegalStateException: CameraDevice was already closed` 直接崩进程。三处一起改：
  面板放到固定 composition 位置（切换只改高度、不重建视图）；`onConfigured` 先查
  `running` 并 try/catch 兜住迟到回调；预览 Surface 变化改成 250ms 防抖重启。

### 控件 → 真实对象

| 界面对象 | 接到哪里 |
|---|---|
| 日志容器 | 编码器 / USB / 网络 / 视觉日志聚合（300 行，自动贴底，可清空） |
| 预览容器 | 图传会话的预览 Surface（`Config.previewSurface`），TextureView 输出 |
| 底部「扫码连接」 | `Pairing.parse` + `Pairing.apply`（对话框贴码，相机被图传占用） |
| RED_R1… 按钮组 | `RobotSlots` + `Settings.robotId/Ip` + `switch_ip` 广播（8769） |
| 雷达分包滑块 | `Settings.netBatch`（1/10/20，40% 落在 10 槽） |
| 串口波特率 / VID / PID | `Settings.laserBaud`、新增 `serialVid`/`serialPid`（`UsbSerialTransport` 按此匹配设备） |
| 视频分辨率 / 帧率 | `CameraStreamer.caps()` 真实能力 → `Config.width/height/fps` |
| 传输编码 / 编码器 | `Settings.codecIndex` / `encoderMode` → `EncoderMode` |
| 纠错模式 | `Settings.fec`（FEC 百分比） |
| 码率 / 空闲补关键帧 / 画面预览 | `Config.bitrate` / `adaptiveKeyframe` / 预览 Surface |
| 网络协议层模式 | `Settings.netMode` → `UartLink.Mode`；未选过显示"未选择" |
| 强制接管串口 | `Settings.serialForceTakeover` + 串口桥重启 |
| 上行 / 下行 / 延迟 | 编码器 `onStats` + 新增 `UartLink.addStatsListener`（不抢激光桥的 `onStats`） |
| 电池 / CPU 温度 / 电量 | BatteryManager + `/sys/class/thermal` |
| 无障碍 / ADB 状态 | AccessibilityManager + `Settings.Global.ADB_ENABLED` |
| 锁定屏幕 | 新增 `ScreenLockAdmin`（DeviceAdmin，只申请 force-lock） |
| 重新连接 | 停图传 → 重启串口桥/非图传流 → 再起图传 |

### 变更（后端小改动）

- `VisionUdpLink` 重新接回控制台：视觉结果与设置快照仍走非图传流长帧 JSON
  （init 时建链路，启动图传/应用连接码后幂等 `start()`），改 UI 不会把视觉上行弄断。
- `NetCore.configure(udp, tcp)` 由控制台按 `Settings.netUdpPort/netTcpPort` 注入，
  避免链路先按默认 48000/49000 建起来、改端口只能重启。
- `UartLink`：新增 `addStatsListener` / `removeStatsListener`（1Hz 并行分发），
  控制台不再和激光桥抢唯一的 `onStats`。
- `VisionCamera`：新增 `previewSurface`，预览并入视觉会话，不开第三条会话、
  不影响图传码流。
- `UsbSerialTransport`：`vidFilter` / `pidFilter` 可配（0 = 通配），prober 同步登记。
- `Settings`：新增 `serialVid` / `serialPid` / `serialForceTakeover` / `previewOn` /
  `netModeChosen`；`adaptiveKf` 默认改为 `true`（界面初始勾选）。
- 构建：Kotlin 2.2.10 + Compose Compiler 2.2.10、Compose 1.10.4、
  Material3 1.4.0；`lint.checkReleaseBuilds = false`（AGP 8.7.3 lint 与 K2 前端冲突）。

### 预览改走视觉链路（v0.8 真机迭代）

- **图传会话只保留编码器**：不再把预览 Surface 塞进编码 session（双 target 时 HAL 会走另一条
  输出路径，推流画面方向变歪）。`TEMPLATE_RECORD` 与项目原低延迟参数不变。
- **预览改为视觉帧**：`VisionCamera` 的 YUV 帧降采样成 480×270 位图（旋转 90° + 居中裁 16:9），
  每 3 帧更新一次（≈9fps），只按目标像素采样，CPU 约 0.4ms/帧；界面用 `Image` 直接贴。
- **两路 session 串行化**：图传 session 稳定后延迟 900ms 再建视觉 session，重建视觉有 400ms 延迟；
  `setRepeatingRequest` 增加 `IllegalStateException` 兜底（另一条 session 建立时 HAL 会关掉本条，
  之前会崩进程）。
- `Settings` 增加一次性端口迁移：老设备 prefs 里残留的 46000 自动改 47000（手填过的端口不动）。
- **预览方向可调**：预览面板标题行加 ⟳ 角度按钮（0/90/180/270 循环，存 `Settings.previewRotation`）。
  真机实测本机 **0°** 就是正的（之前误判为需要旋转 90°，是因为早先 TextureView 的变换算错导致的）。
  换机型或换摄像头安装方向时点按钮即可，不必改代码重装。

### 文档

- 新增 `JOINT_DEBUG.md`：上下位机联调指南 —— 端口表、启动顺序、手机/电脑两侧准备、
  S1~S6 分步联调（串口 → 图传 → 非图传流 → 视觉上行 → 开火 → 弱网）、验收清单、
  排障 FAQ（黑屏/方向/收不到流/端口占用/USB 识别）、抓日志与协议速查。

### 已知限制

- material3 **1.4.0** 的 `MaterialExpressiveTheme` / `MotionScheme` 是 `internal`，
  1.5.0 又要求 compileSdk 37（本机 AGP 8.7.3 上限 35/36），因此按
  MotionScheme.standard 的数值用 `ConsoleMotion` 落地；组件尺寸/形状按 expressive
  规格手工装配（ButtonGroup 在 1.4.0 里也没有公开 API）。
- **推流画面的方向仍是 sensor 原始方向**（侧躺 90°）：本地预览已经旋转修正，
  但 H.264 码流是编码器直接吃相机 Surface，Camera2 不支持旋转输出。要正过来需要
  在编码前插一层 GL 旋转，或由上位机（pc_hud / spaa）解码后旋转 90° 显示。
- 扫码入口仍是"贴码"：相机被图传占用，接 ZXing/CameraX 需要先做相机复用。
- 该机型不支持双相机会话并发，视觉分析（第二路 YUV）与图传同时开时会自动降级。

## [0.7.1] — 2026-11-19（图传端口对齐 + 上位机解码改为探测）

> 症状：手机上把「图传端口」改成 48000，控制通道却一直往 47000 连，且没有画面。

### 修复

- **控制端口跟随图传端口**：`ConsoleViewModel` 原来 `controlPort = settings.controlPort`
  （固定 47000，输入框改不到），导致视频发 48000、控制连 47000。现在两者同号。
- **端口自愈**：`Settings` 启动时把 46000 迁移到 47000；视频/控制不一致自动同步；
  图传端口撞上非图传端口（48000/49000）退回 47000。
- **UI 校验**：端口等于非图传端口时输入框标红，启动时直接拒绝并提示，不再“假装在跑”。

### 上位机（SPAA）

- `decoder.py` 改为**先探测码流再解码**：用 `ffprobe` 从 SPS 读出 `codec/width/height/fps`，
  再按真实参数起 ffmpeg。配置里的 H.264/HEVC 选错、分辨率写错都不再导致黑屏，
  也不再白做一次缩放。
- 探测失败（一直没等到 SPS）4 秒后按配置兜底；老版 ffmpeg 不认 `-fps_mode` 自动退 `-vsync 0`。
- 视图/HUD 跟随探测结果：`video_rect`、纹理上传、`CameraModel`（测距环）都按真实分辨率重建。
- 3 秒收不到视频包时打印明确提示（端口/推流状态），避免只看到 `SS_RFI_REQUEST` 却无从排查。
- `--check` 增加 ffprobe 检查。

## [0.7.0] — 2026-11-18（非图传网络栈重构：只剩两条信号）

> 目标：把「图传」之外的所有通道收成**一条流**。旧版同时跑 TCP 8765 / 8766 / 8767、
> UDP 8768 / 31001 / 31002、WLAN 8888 共八条，四套 socket 生命周期各写一遍，
> 端口常量散在六个文件、文档与实现已经漂移（文档写 31000，代码 46000）。
> 协议细节见 `NETSTACK.md`。

### 新增 `net/` 包

- `NetProto`：端口、槽帧头、flags、CRC16 统一到一处
- `PacketCodec`：16B 包头（magic/ver/flags/seq/blockId/shardStart/count/ts/payloadLen/crc16）
- `FecBlock`：5 数据槽 + 1 校验槽（20%），单擦除恢复；校验槽强制单独成包
- `UartSlot`：60B 槽构造与解析、KEEP_ALIVE、本机操作、原始字节分片
- `UartSlotFramer`：USB 字节流 → 60B 槽；主控帧 39B 补零，**兼容固件改成 60B**
- `UartLink`：单流复用（攒包滑杆 1/10/20、控制重放 0/1/2、KA、UDP⇄TCP 自适应、统计）
- `NetCore`：进程级单例 + 引用计数 + JSON 分发 + USB 写入入口
- `Pairing`：扫码配对编解码（HOST / IP / PORT / 槽位），**扫描入口未接**

### 变更

- `LaserBridge` 只保留 USB 读写与断线重连，网络部分交给 `UartLink`；
  下行不再有独立「主控下行」通道，PC 直接发 `FA 5B` 原始字节槽
- `VisionUdpLink` 改为长帧 JSON 走非图传流，JSON 字段不变（上位机解析代码可沿用）；
  不再 bind 31002、不再广播 31001
- 图传端口 46000 → **47000**，图传控制继续用 TCP 47000（UDP/TCP 端口空间独立）
- `Settings` 新增 `netBatch` / `netReplay` / `netMode` / `netUdpPort` / `netTcpPort`
- 视觉开火优先走 `NetCore.uartWriter`（USB 持有者），避免再开 Transport 抢设备
- 停图传不再关闭视觉同步链路（旧实现 `stopVideo()` 里 stop 之后再也不重启）

### 删除 / 废弃

- UDP 8768、TCP 8767：并入非图传流；`LaserFecEncoder` 仅保留给自测
- TCP 8766（数据流 2）：删除
- 视觉 UDP 31001 / 31002：不再使用
- `SerialBridge` / `DataBridge` / `TcpChannel`：保留编译，**不要与新链路同时启动**，UI 更新后移除

### 待办

- 上位机按 `NETSTACK.md` §10 补齐：解包 / FEC 解码 / **KA 回显** / 去重 / 长帧重组
- UI：攒包滑杆、重放滑杆、UDP/TCP 模式开关、扫码入口
- USB 所有权统一到 `NetCore`（`MainViewModel` 仍会开机抢 CH343）

## [0.6.0] — 2026-11-08（数学基础驱动的视觉改进）

> 依据《传统视觉与深度学习视觉的数学基础》讲义（62 页）逐节落实：
> 每条改进都能指到对应章节，且都配了可量化的自测指标。
> 只改 APP 视觉链路，图传/HUD/协议不变。

### 新增模块

- `preprocess/Gaussian.kt`：可分离高斯（a = ⌈3σ⌉，核缓存，1D 剖面平滑）。
  依据 §4.2「可分离 / 3σ 截断 / 自卷积方差相加」。自测：自卷积与
  √(σ₁²+σ₂²) 相对误差 **0.03%**（要求 <5%）
- `preprocess/GuidedFilter.kt`：导向滤波，**O(1)/像素、与半径无关**。
  依据 §4.4/§4.5「双边非线性非移变、有梯度反转光晕，导向滤波用局部
  线性模型替代」。自测：平坦区 σ 12.2→5.9、边缘差 145 灰阶保留、
  输出无过冲、r=2 与 r=12 都保边
- `detect/SubpixelRect.kt`：最小外接矩形**亚像素**长/宽/中心。
  依据 §10.4 亚像素边缘 + §4.7.1 先平滑再差分。自测：真值 40.40×25.30
  的旋转矩形测得 **40.33×25.26**（误差 0.07/0.04px），轴对齐
  40.26×25.34（整数外接矩形误差 0.40px）
- `detect/Nms.kt`：IoU 贪心 NMS。依据 §11.2。自测：重叠双框只留高分、
  分离双框都保留
- `detect/StructureTensor.kt`：结构张量（λ1/λ2、相干度、主方向、
  双角平均）。依据 §7.4/§7.5。自测：单边缘相干度 1.000、平坦区判 FLAT、
  双角平均把 {1°,179°} 平均成 0°（算术平均会给 90°）

### 改进

- **亚像素尺寸接入检测**：面板/圆靶尺寸改由亚像素精定位给出。
  8m 处 120mm 面板只有 7.5px，整数矩形 ±0.5~1px 就是 7~13% 测距误差，
  现在降到 ~2%。开火链路因此首次可以判断"这一炮的测距够不够准"
- **测距不确定度传播**（`Pinhole.distanceSigma`，§1.2 一阶近似）：
  σ_d = (d/p)·σ_p，即**相对测距误差 = 相对像素尺寸误差**；
  8m 处 σ≈530mm、2m 处 σ≈33mm，绝对误差随 d² 增长
- **视觉/雷达逆方差加权融合**（`Pinhole.fuse`，§1.7）：取代原来写死的
  `0.7·雷达 + 0.3·视觉`。谁准听谁的，融合后 σ 必小于任一路
  （自测：8000±800mm 视觉 + 7600±76mm 雷达 → 7604±76mm，雷达权重 0.99）
- **开火决策引入置信度**（`FireController`）：σ_d/d > 25% 直接拒绝击发并
  给出原因；`FireCommand` 新增 `distSigmaMm` / `radarWeight`
- **开火角度容差修正**：原来拿"含弹道补偿的绝对俯仰角"去比 1.5° 容差，
  远距离补偿本身就有 3°，导致永远超差判不出发射。改为判目标偏角，
  补偿量归入前馈
- **色相环形运算**（`Hsv`，§2.6）：掩码判定改为环形距离，新增
  `hueMean`（向量平均）/`hueDistance`/`hueConcentration`。
  自测：{359°,1°} 平均得 0°（算术平均会得 180° 青绿）
- **卡尔曼 NIS 软门控**（`KalmanFilterCV`，§1.7/§9）：马氏距离² 超过
  χ²₂ 99% 分位 9.21 时按 d²/9.21 膨胀 R。自测：注入 400px 离群观测，
  轨迹只移动 **3.3px**（无门控时约 150px），且门控后仍能跟住正常目标
- **组朝向双角平均**（§7.5.3）：4 面板朝向不再算术平均（会在 ±90° 跳变），
  改为 (cos2θ, sin2θ) 平均
- **双侧 LUT 加速**：双边滤波值域权重改成 256 项查表（§2.7 8 位量化），
  内层 exp 调用归零
- **叠加显示**：检测框标注改为 `距离±1σ 偏航角`，高地靶画出朝向线，
  追踪标注带 `σ` 与 NIS 门控 `!` 标记

### 自测

| 套件 | 结果 |
|---|---|
| `SelftestMath.kt`（新增） | **49 / 49** |
| `SelftestDetect.kt`（回归） | 36 / 36 |
| `preprocess/Selftest.kt`（回归） | 7 / 7 |

### 已知限制

- 亚像素精定位依赖边缘对比度（`MIN_CONTRAST=20`），强逆光/严重失焦时
  自动回退整数矩形（不会变差，也不会变准）
- 雷达仍未接入，`radarDistanceMm` 传 -1 时融合自动退化为纯视觉
- 视觉结果 UDP 31001 发送端仍未实现（APP 侧待办）

## [0.5.1] — 2026-10-24（笔记本 HUD：USB 手柄操控）

> 仅改电脑 HUD 侧（pc_hud/），APP 无改动、APK 不变。操控输入从键鼠
> FPS 云台改为 **USB 游戏手柄**（用户提供有线手柄，PS/XInput 布局）。

### 变更（pc_hud/）

- 新增 `warhud/gamepad.py`：pygame/SDL 手柄轮询（100Hz），
  死区 0.12 + 指数响应曲线、USB 热插拔退避重连、无手柄静默降级键鼠；
  扳机支持按钮/模拟轴两种硬件形态；`gamepad_map` 覆盖序号
- 映射：左摇杆/十字键 → 底盘平移（800mm/s 满格）；右摇杆 → 云台
  角速度积分（90°/s 满格，LT 微瞄 ×0.35）；RT → 开火边沿 + 准星闪光；
  A → 自动开火开关；B → 切换追踪目标（高地/基地）；X → 检测叠加显隐；
  Y → 设置面板；START → 计时暂停；SELECT → 状态面板显隐
- `main_window.py`：`_tick` 接入手柄（连续量积分 + 边沿动作队列），
  顶栏手柄状态标签，掉线自动清底盘指令；键鼠保留为后备
- `overlay.py`：连接状态条加「手柄」灯，开火闪光，暂停指示，
  按键提示更新；`show_detect`/`show_panel` 显隐开关
- `config.py`：7 项手柄默认参数 + `gamepad_map`
- 自测：gamepad 19/19（MockSource）、smoke 20/20（含手柄注入、
  掉线降级）、protocol 13/13 不变
- 文档：pc_hud/README.md 补手柄映射表 + 「手机画面传到电脑」
  操作步骤（组网/查 IP/端口对齐 31000/编码 H.264/防火墙/排障）；
  PROTOCOL.md 输入源改手柄映射

### 已知问题

- 视觉结果 UDP 31001 发送端仍未实现（APP 侧待办），视频链路可用、
  检测框叠加待手机端补齐

## [0.5.0] — 2026-10-22（60fps 整合 + 笔记本操控 HUD）

> 本版汇集 10-20 ~ 10-22 三条线的全部工作：图传线（合作者延迟优化 + RTP 修复 +
> 60fps 解锁整合）、视觉线（数据集驱动的检测改进 + 第二码流 + 开火下发 + 叠加 UI）、
> 新增电脑端（笔记本操控 HUD + MC mod 任务书）。

### 新增（60fps 实验档，整合合作者高帧率研究）

合作者逆向荣耀相机 dex 解锁 1080p60/720p120 并以 Camera60Test 实测：
**HAL 超表接受 `[60,60]`**（设备 AE 表通常只报到 [30,30]）。整合为：

- `CameraStreamer.caps()`：帧率档 15/24/30 → **15/24/30/60**；60 档准入放宽为
  「最高显式固定档 ≥30 即列出」（超表尝试）
- **小米适配**（本机载体为小米，非荣耀）：
  - 厂商私有 tag 分支：仅荣耀下发 `com.hihonor.capture.metadata.hw-video-fps=60`；
    小米/HyperOS 对第三方 Camera2 的 fps 限制更宽松，超表请求通常直接被 HAL 接受，
    其余厂商无公开记录 tag 一律跳过（反射失败无害）
  - `onConfigureFailed` 兜底：60fps 被 HAL 拒绝时自动回退 30fps 重试
    （重建 sender 帧率参数，编码器/surface 不动，秒级恢复；`fpsFallbackTried` 防死循环）
  - HAL 实际 fps range 回读日志（CaptureResult.CONTROL_AE_TARGET_FPS_RANGE）：
    请求 60 实际给多少一看便知
- 720p120 高速会话（`createConstrainedHighSpeedCaptureSession`）**未整合**：
  与图传三 surface（编码器+预览+视觉）架构冲突且 API 30 起 deprecated，
  参考 Camera60Test `startHighSpeedRecording` 留待办

### 改进（端到端延迟诊断与低延迟优化，合作者线）

实测设备 HAL 在途 8 帧（≈264ms @30fps）是延迟大头：

- App 侧（CameraStreamer）：
  - **Stream Use Case**（API 33+）：VIDEO_CALL/PREVIEW 替代 TEMPLATE_RECORD 的
    VIDEO_RECORD 语义（官方明示 substantial lag）
  - 低延迟 ISP：NR/EDGE/TONEMAP=FAST + 视频防抖关（HIGH_QUALITY 多帧融合每项多背 1~2 帧）
  - HAL 管线深度量化：REQUEST_PIPELINE_MAX_DEPTH 上限 + CaptureResult 实际值（Byte 键）
  - 真实 applat：`nanoTime - ptsUs×1000`（旧值只测编码回调内部耗时）
  - 编码器延迟验证：KEY_LATENCY output format 实际值
- PC 侧（video_host.py）：
  - SO_RCVBUF 4MB（Win 默认 64KB，一帧 ~100KB 突发装不下→gap 丢包）
  - 写队列 `--write-q 3`（丢帧保延迟）
  - 事件驱动关键帧（组装超时/解码错误/突发丢包 → SS_RFI_REQUEST，Moonlight 同款，
    IDR 丢失恢复 2s → 约 1 帧）
  - 净积压速率判据（>0.3 帧/s 才判漂移，避免起播探测期误判）+ 退出延迟拆解报告

### 新增（笔记本操控 HUD，pc_hud/）

PyQt5 桌面程序（用户选定方案：PyQt 桌面 + 电脑本地叠加 + 设置双向同步 + WLAN 直连 ESP32）：

- `pc_hud/warhud/stream.py`：ffmpeg 子进程解码裸流（`-flags low_delay` +
  `udp://...?fifo_size=8388608&overrun_nonfatal=1`）→ RGB 帧管道
- `pc_hud/warhud/overlay.py`：War Thunder 风格 HUD——中央准星 + 弹道下坠刻度 +
  提前量标记（跟随卡尔曼速度）、检测框（绿高地/红基地）、追踪框 + 速度矢量、
  状态面板（电压/电流/温度/轮速）、弹药/计时/连接状态条
- `pc_hud/warhud/phonesync.py`：视觉结果 UDP 接收（31001）+ 设置双向同步
  （31002；手机 SharedPreferences 为权威源，电脑改→发手机→广播快照确认）
- `pc_hud/warhud/esp32.py`：WLAN 直连 ESP32-S3（TCP 31010 输入帧 10Hz /
  31011 遥测帧；fire 边沿触发，仅状态变化发包）
- `pc_hud/warhud/main_window.py`：鼠标捕获 FPS 云台控制（0.12°/px）+ Tab 设置面板
- 自测：`protocol.py` 13/13 + `smoke_test.py`（offscreen）13/13
- 帧格式见 `PROTOCOL.md`「电脑 HUD 协议」节；电控待办：ESP32 WLAN TCP server

### 改进（公开数据集驱动的检测管线）

基于 rmcv/armor 公开数据集（9123 张真实赛场图 / 15751 装甲板，CC-BY-4.0）
的评测结论：真实暗光下红/蓝目标 V 掉到 0.1-0.3、色相偏橙，原 HSV 阈值在
真实图上覆盖率仅 2.4%。

- **`vision/preprocess/VNormalize.kt`（新）**：V 通道 CLAHE 归一化
  （8×8 分块、clip=2.0×均值、CDF 映射表、块间双线性插值、RGB 按 V 等比例缩放）。
  单一 object 无 native 依赖，实测 V 提升 17.2%、H 漂移 0.4°。设置项
  `v_normalize`（默认开）经 `VisionPipeline` 传入两个检测器
- **`vision/detect/Hsv.kt`**：红色掩码 345-15/S≥0.40/V≥0.30 →
  **345-40 回绕/S≥0.45/V≥0.12**（与公开数据基准配置逐项一致）；
  新增蓝色掩码预置（195-270/S≥0.40/V≥0.15，狼牙暂无蓝目标，备用）
- **`vision/detect/TargetDetector.kt`**：
  - 基地靶检测增加闭运算（open → open+close）：公开数据集上断裂/带孔洞的
    红色掩码会被圆形度判据误拒（合成断裂圆 5 碎片 → 合并为 1 圆检出）
  - 几何分析改用 `MinAreaRect`（旋转卡壳）：轴对齐外接框把倾斜目标长边
    估大 8-15%（实测 30° 旋转面板 60px 目标，bbox 65px vs minRect 60px）
  - `scaledMinArea()`：最小连通域面积随分辨率缩放（640×480→30，
    1440×1080→103），修复大图漏检远处小目标
  - `detectHighland/detectBase` 新增 `normalizeV` 参数（默认 true）
- **`vision/detect/Components.kt`**：Blob 增加像素列表（供最小外接矩形），
  共享 scratch 缓冲 + ThreadLocal 复用，PIXEL_CAP=20000 防巨型连通域
- **自测**：`SelftestDetect.kt` 11/11 → **36/36**（新增暗红圆旧阈值漏检
  复现、阈值边缘暗红圆 CLAHE 救回、断裂圆合并、旋转面板 minAreaRect 精度、
  minArea 缩放、CLAHE 单元、红圆场景不误检高地靶）；预处理自测 7/7 保持
- 构建：修复仓库缺失的 gradle wrapper jar（清单补 Main-Class）

### 修复（整合合作者更新）

- **RTP 时间戳 1000 倍 bug**（`GameStream.rtpTimestamp`）：`×90/1e6` 应为
  `×90000/1e6`（RTP 时钟 90kHz）。旧值 30fps 帧间隔只得 2 tick（应 3000），
  上位机反推帧率会算出 30000fps；任何按 RTP ts 做时钟同步的客户端
  （Moonlight 等）都会被带崩
- **video_host.py 四项修复**（电脑端，APK 不动）：
  1. `vq=302KBKB` 显示残留：`\r` 覆盖时新行短于旧行会留尾巴，
     改为按终端实际宽度补齐（CJK 全角按 2 列），不用 ANSI `\x1b[K`
     （conhost 无 VT 模式会原样打出转义序列）
  2. vq 从累计峰值改当前值（每秒刷新看趋势），峰值单独进退出报告
     并换算毫秒（302KB @5742kbps ≈ 431ms），注明不代表播放延迟
  3. 新增 `dec=`（解码器每秒实际解出帧数，showinfo 累计差值）与
     「净积压 = 累计送出 − 累计解码」退出报告；结论行自适应
     （积压建议加大 --fps-boost，fd>0 建议调小，正常才说节奏匹配）
  4. ffplay 猝死检测：每秒检查进程状态，死了立刻停并打印退出码
     （原来主循环会傻傻继续收包计数）

### 新增（视觉独立第二码流）

- **视觉分析走独立第二条 Camera2 会话**（`VisionCamera`）：
  - 图传 session 只含编码器 + 预览 Surface，**码流完全不受视觉影响**
  - 视觉 session 在同一 CameraDevice 上自建 CaptureSession，独立
    ImageReader（YUV_420_888）+ 独立帧率（默认 15fps，可低于图传）
  - 传感器输出帧按两 session 各自请求节奏分发，互不阻塞
  - 帧回调暴露原始 YUV Image：纯 Kotlin 管线经 `yuv420ToRgb` 转 RGB；
    OpenCV 接入时可直接用 YUV 平面构造 Mat
- 移除旧的共享会话方案（VisionFrameSource / Config.visionSurface）
- 停止顺序：先停视觉会话（依赖 device），再停图传（close device）

### 新增（开火下发 + 检测叠加 UI）

- **开火指令串口下发**：`MainViewModel.sendVisionCommand()` 经 USB
  （OTG-CH343）直连下位机发送 FireController 序列化帧；WLAN 降级时不发
  （开火延迟敏感不走网络）
- **本地预览 + 检测叠加**：主界面新增预览区（SurfaceView），
  `VisionOverlayView` 绘制检测框（绿=高地靶/红=基地靶）、追踪框（黄，
  含速度矢量）、准星 + 开火容差圈、开火状态文本
- 图传启动时自动接入预览 Surface（surface 有效时）

### 已知限制

- 目标物理尺寸（高地靶面板 120mm、基地靶 150mm）为估值，需实机标定
- 相机内参按典型广角 hFov=65° 估算，精确值需棋盘格标定
- 红外雷达数据未接入，FireController 的雷达融合分支待启用
- 开火指令帧格式（0xFA 0xAF）为视觉专用子协议，需与电控核对后
  并入主协议（0xA5 0x5A + CRC16）

## [0.4.0] — 2026-10-19（车载视觉决策转型）

### 新增（车载视觉决策转型）

**APP 定位转变**：从「机器人遥控 HMI」改为**放置在机器人上的车载视觉决策 APP**
（输入手机摄像头画面，做图像识别、视觉处理、系统决策）。显示名改为「狼牙视觉」。

**设置界面（需求 1）**
- 新增第三屏「视觉功能设置」：图像预处理（去模糊/平滑/去辉光）、目标检测
  （高地靶/基地靶）、目标追踪（卡尔曼）、自动开火、导航（占位）全部可开关
- 检测频率可调（每 N 帧处理一次，1-30）
- 状态栏遮挡防护：ScrollView + `applySystemBarsInsets()` 按实测系统栏 inset
  加 padding，所有按钮可点；开关行整行可点击

**图像预处理管线（需求 2，核心）**
- `vision/preprocess/Deblur.kt`：维纳非盲反卷积去模糊（radix-2 FFT，
  运动模糊核参数化，核中心对齐 (0,0) 修复了频域相位偏移 bug）
- `vision/preprocess/Smoothing.kt`：双边滤波保边平滑（可分离两遍近似）
- `vision/preprocess/Deglare.kt`：去辉光/抗强光干扰（过曝检测 + 邻域修复 +
  大光斑全局均值兜底）
- 自测 7/7 通过：维纳 MAE=5.65、边缘梯度 50→126 恢复；双边方差 35.4→1.7
  且边缘保留；去辉光光斑修复/非过曝区不变/全过曝兜底全过

**目标检测框架（需求 3）**
- `vision/detect/Hsv.kt`：RGB→HSV + 颜色掩码（绿=高地靶、红=基地靶，支持跨 0 回绕）
- `vision/detect/Morphology.kt`：3×3 腐蚀/膨胀/开/闭运算
- `vision/detect/Components.kt`：8 邻接连通域标记（扫描线 flood fill）
- `vision/detect/MinAreaRect.kt`：旋转卡壳最小外接矩形（凸包 + 卡壳）
- `vision/detect/TargetDetector.kt`：高地靶（绿色面板几何过滤 + 空间聚类）/
  基地靶（红色圆形度过滤）检测，含针孔测距测角
- `vision/track/KalmanFilterCV.kt`：CV 模型卡尔曼滤波（移植 rm_selection
  kf_core.py，Joseph 形式更新）
- `vision/solve/Pinhole.kt`：针孔测距（distance = focal×real/pixel）、
  测角、FOV 焦距估算、弹道俯仰补偿

**自动开火框架（需求 4）**
- `vision/decide/FireController.kt`：弹道解算（距离→初速→摩擦轮 RPM）、
  角度容差开火决策、冷却控制、红外雷达融合接口预留、串口帧序列化
- `vision/nav/Navigation.kt`：导航占位（等机械组完成机器人框架后开工）

**主管线**
- `vision/VisionPipeline.kt`：串联 预处理→检测→追踪→决策，按设置开关
  选择性启用，按 detectEveryNFrames 节流，性能预算 640×480 约 10fps

### 变更

- `strings.xml`：app_name → 「狼牙视觉」
- `activity_main.xml`：标题改「狼牙视觉」，新增「视觉设置」入口按钮
- `MainActivity.kt`：新增视觉设置界面跳转
- `AndroidManifest.xml`：注册 VisionSettingsActivity

### 修复

- 维纳反卷积核对齐：模糊核原先画在图像中心导致频域半幅相位偏移，
  反卷积结果全错（MAE=138）；改为以 (0,0) 为中心绘制后 MAE=5.65

### 已知限制

- 目标物理尺寸（高地靶面板 120mm、基地靶 150mm）为估值，需实机标定
- 相机内参按典型广角 hFov=65° 估算，精确值需棋盘格标定
- 红外雷达数据未接入，FireController 的雷达融合分支待启用
- 视觉管线尚未接入 CameraStreamer 帧源（ImageReader Surface 预留），
  需在 CameraStreamer.Config 加预览 Surface 后接线

## [0.3.0] — 2026-10（合作者版本，已合并）

### 新增

- 网络方向统一为「电脑监听、手机主动连接」（TcpChannel 自动重连）
- GameStreamSender 线程模型：MediaCodec 回调线程只做 buffer 拷贝+入队，
  独立 gs-sender 线程做组包+RS+pacing
- 帧级 pacing、丢帧策略（队列容量 2，IDR 保关键帧）
- EOF 只标最后一个数据分片（对齐 Sunshine）
- HEVC 参数集每个 IDR 重拼（repeatCsd 三档）
- 丢帧后补关键帧（节流 300ms）+ IDR 前临时抬码率 60%
- 固定帧率档 15/24/30
- 设置记忆（Settings.kt，SharedPreferences）
- 状态栏适配（UiExt.kt applySystemBarsInsets）

### 修复

- GameStream FEC：Cauchy MDS 矩阵、多块分片序号、短帧头偏移修正
  （22/22 自测 + Kotlin/Python 交叉验证逐字节一致）
