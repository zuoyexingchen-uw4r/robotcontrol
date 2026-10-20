# CHANGELOG

本文档记录 RobotControl（狼牙视觉）的版本变更。格式遵循
[Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循语义化版本。

## [Unreleased] — 2026-10-20

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
