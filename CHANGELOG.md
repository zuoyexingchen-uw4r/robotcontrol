# CHANGELOG

本文档记录 RobotControl（狼牙视觉）的版本变更。格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

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
