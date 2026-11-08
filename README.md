# 狼牙视觉（LangyaVision）— 机器人车载视觉决策 APP（Kotlin）

> 原 RobotControl。2026-10 转型：从「机器人遥控 HMI」改为**放置在机器人上的
> 车载视觉决策 APP**——输入手机摄像头画面，做图像识别、视觉处理、系统决策。

参赛：第四届狼牙机器人争霸赛（RoboMaster 赛制）。载体：R1 射击机器人。

---

## 1. 功能总览

| 模块 | 状态 | 说明 |
|---|---|---|
| 图传（Camera2 硬编 + GameStream 推流） | ✅ 已实现 | 电脑监听、手机连接，FEC 前向纠错 |
| 串口桥接（CH343 USB-OTG） | ✅ 已实现 | 与下位机（ESP32-S3）通信 |
| 视觉设置界面 | ✅ 已实现 | 各功能开关 + 状态栏避让 |
| 图像预处理 | ✅ 已实现 | 去模糊（维纳反卷积）/ 平滑（双边滤波）/ 去辉光 |
| 目标检测 | ✅ 框架已实现 | 高地靶（绿）/ 基地靶（红），HSV→形态学→连通域→几何分析 |
| 目标追踪 | ✅ 框架已实现 | 卡尔曼 CV 滤波 |
| 自动开火 | ✅ 框架已实现 | 弹道解算 + 摩擦轮速率 + 雷达融合接口预留 |
| 导航 | ⏳ 占位 | 等机械组完成机器人框架后开工 |

## 2. 视觉管线架构

```
Camera2（同一 CameraDevice，两条独立会话）
   │
   ├─ 会话 1（图传，CameraStreamer）：编码器 Surface + 预览 Surface
   │     帧率/码率按推流需求配置，不受视觉影响
   │
   └─ 会话 2（视觉，VisionCamera）：独立 ImageReader（YUV_420_888）
         独立帧率（默认 15fps，可低于图传减轻 CPU 负担）
              │
              ▼
┌─ 预处理（可开关） ─────────────────────────────┐
│ 去模糊 Deblur（维纳反卷积 FFT，周期性）        │
│ 平滑 Smoothing（双边滤波，保边去噪）           │
│ 去辉光 Deglare（过曝检测+邻域修复，抗强光）    │
└──────────────────────┬────────────────────────┘
                       ▼
┌─ 检测（可开关） ──────────────────────────────┐
│ HSV 颜色分割（绿=高地靶 / 红=基地靶）          │
│ 形态学（开运算去噪）                           │
│ 连通域标记（扫描线 flood fill）                │
│ 几何分析（最小外接矩形 / 圆形度）              │
│ 针孔测距测角（distance = focal×real/pixel）   │
└──────────────────────┬────────────────────────┘
                       ▼
┌─ 追踪（可开关） ──────────────────────────────┐
│ 卡尔曼 CV 滤波（位置+速度，Joseph 更新）       │
└──────────────────────┬────────────────────────┘
                       ▼
┌─ 决策 ────────────────────────────────────────┐
│ 自动开火 FireController（弹道解算+摩擦轮RPM） │
│ 导航 Navigation（占位）                       │
└──────────────────────┬────────────────────────┘
                       ▼
              串口帧 → CH343 → 下位机
```

性能预算（640×480）：预处理 ~10-20ms，检测 ~15-30ms，按「每 N 帧」节流
（默认 3），整体约 10fps 处理。OpenCV 接入时替换 YUV→RGB 步骤，
直接用 YUV 平面构造 Mat。

## 3. 目录结构

```
app/src/main/java/com/robomaster/control/
├── ui/                  # 界面（MainActivity / BridgeActivity / VisionSettingsActivity）
├── video/               # 图传（CameraStreamer / GameStreamSender / ReedSolomon）
├── bridge/              # 桥接（TcpChannel / SerialBridge / DataBridge）
├── comm/                # 通信协议（帧解析 / 状态机）
├── vision/              # 视觉（本次新增）
│   ├── VisionPipeline.kt      # 主管线
│   ├── VisionCamera.kt        # 独立第二会话（ImageReader + 帧回调 + YUV→RGB）
│   ├── VisionOverlayView.kt   # 检测结果叠加视图
│   ├── preprocess/           # Deblur / Smoothing / Deglare / VNormalize
│   │                         # Gaussian（可分离高斯）/ GuidedFilter（O(1) 导向滤波）
│   ├── detect/               # Hsv / Morphology / Components / MinAreaRect / TargetDetector
│   │                         # SubpixelRect（亚像素尺寸）/ Nms（IoU 抑制）
│   │                         # StructureTensor（λ1/λ2 + 双角平均）
│   ├── track/                # KalmanFilterCV（NIS 软门控）
│   ├── solve/                # Pinhole（测距测角 + 不确定度传播 + 逆方差融合）
│   ├── decide/               # FireController（弹道 + 置信度门限）
│   └── nav/                  # Navigation（占位）
└── ...
```

## 4. 构建与运行

```bash
# 本机已配 Gradle + SDK
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

自测（纯 Kotlin，无 Android 依赖；用 Android Studio 自带 kotlinc 即可）：

```bash
APP=app/src/main/java/com/robomaster/control/vision
T=app/src/test/java/com/robomaster/control/vision

# 1) 预处理 7 项
kotlinc $APP/preprocess/*.kt $T/preprocess/Selftest.kt -include-runtime -d st_pre.jar
java -jar st_pre.jar                       # 期望 7/7

# 2) 检测 36 项
kotlinc $APP/detect/*.kt $APP/preprocess/*.kt $APP/solve/Pinhole.kt \
        $T/detect/SelftestDetect.kt -include-runtime -d st_det.jar
java -jar st_det.jar                       # 期望 36/36

# 3) 数学增强 49 项（高斯/导向滤波/色相环形/亚像素/NMS/结构张量/NIS/不确定度）
kotlinc $APP/preprocess/*.kt $APP/detect/*.kt $APP/solve/*.kt \
        $APP/track/*.kt $APP/decide/*.kt $APP/nav/*.kt \
        $T/SelftestMath.kt -include-runtime -d st_math.jar
java -jar st_math.jar                      # 期望 49/49
```

> Windows 控制台是 GBK：`java -jar xx.jar > out.txt 2>&1 && iconv -f GBK -t UTF-8 out.txt`

## 5. 比赛规则要点（第四届狼牙）

- 红蓝对抗 BO3，每局 5 分钟；R1 射击机器人（本 APP 载体）+ R2 取矿机器人
- **高地靶**：4 片开关（2 大 @5 分 + 2 小 @10 分，共 30 分，全点亮激活对方基地靶）
- **基地靶**：3 固定 @15 分 + 1 移动 @45 分（1.5m 直线往返，共 90 分）
- 弹丸 17mm 黄绿色荧光 TPU（3.2g，90±5A）；初始 8 弹，每兑换 1 矿石补 8 弹（最多 3 次）
- 机器人限 550×500×500mm；地形含隧道区/起伏路段/70mm 台阶/中央高地

## 6. 已知限制与后续

- 目标物理尺寸与相机内参为估值，需实机标定（棋盘格 + 实测靶标）
- 红外雷达数据未接入：`radarDistanceMm` 传 -1 时融合自动退化为纯视觉；
  接入后按 §1.7 逆方差加权生效，无需改调用点
- 亚像素精定位依赖边缘对比度（阈值 20 灰阶），强逆光/严重失焦时
  自动回退整数矩形（不变差，也不变准）
- 视觉结果 UDP 31001 发送端未实现（APP 侧待办），视频链路已通
- 导航待机械组完成机器人框架

## 6.1 版本 0.6.0 的数学依据索引

每条算法都能指回《传统视觉与深度学习视觉的数学基础》的具体章节：

| 模块 | 依据 | 落地指标 |
|---|---|---|
| 可分离高斯 `Gaussian` | §4.2（可分离 / a=⌈3σ⌉ / 方差相加） | 自卷积误差 0.03% |
| 导向滤波 `GuidedFilter` | §4.4 双边梯度反转 → §4.5 局部线性模型 | O(1)/像素，平坦区 σ 减半，无过冲 |
| 色相环形 `Hsv` | §2.6（H 是环、S 在 V→0 不稳） | {359°,1°} 平均得 0° |
| 亚像素尺寸 `SubpixelRect` | §10.4 + §4.7.1 先平滑再差分 | 尺寸误差 0.40px → 0.07px |
| 检测框 NMS `Nms` | §11.2 IoU 贪心 | 重叠框去重 |
| 结构张量 `StructureTensor` | §7.4 / §7.5（双角平均陷阱） | 边缘相干度 1.000 |
| 卡尔曼门控 `KalmanFilterCV` | §1.7 高斯 + §9 协方差 | 400px 离群只扰动 3.3px |
| 测距不确定度 `Pinhole` | §1.2 一阶泰勒 + §1.7 逆方差 | σ_d=(d/p)σ_p，融合 σ 800→76mm |

## 7. 协议文档

- `PROTOCOL.md`：图传/桥接/串口协议
- `CHANGELOG.md`：版本变更记录
