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
│   ├── preprocess/           # Deblur / Smoothing / Deglare
│   ├── detect/               # Hsv / Morphology / Components / MinAreaRect / TargetDetector
│   ├── track/                # KalmanFilterCV
│   ├── solve/                # Pinhole（测距测角弹道）
│   ├── decide/               # FireController
│   └── nav/                  # Navigation（占位）
└── ...
```

## 4. 构建与运行

```bash
# 本机已配 Gradle + SDK
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

自测（预处理算法，纯 Kotlin 无 Android 依赖）：

```bash
kotlinc vision/preprocess/*.kt vision/preprocess/Selftest.kt -include-runtime -d selftest.jar
java -jar selftest.jar   # 期望 7/7 通过
```

## 5. 比赛规则要点（第四届狼牙）

- 红蓝对抗 BO3，每局 5 分钟；R1 射击机器人（本 APP 载体）+ R2 取矿机器人
- **高地靶**：4 片开关（2 大 @5 分 + 2 小 @10 分，共 30 分，全点亮激活对方基地靶）
- **基地靶**：3 固定 @15 分 + 1 移动 @45 分（1.5m 直线往返，共 90 分）
- 弹丸 17mm 黄绿色荧光 TPU（3.2g，90±5A）；初始 8 弹，每兑换 1 矿石补 8 弹（最多 3 次）
- 机器人限 550×500×500mm；地形含隧道区/起伏路段/70mm 台阶/中央高地

## 6. 已知限制与后续

- 目标物理尺寸与相机内参为估值，需实机标定（棋盘格 + 实测靶标）
- 红外雷达数据未接入（FireController 雷达融合分支待启用）
- 视觉管线待接入 CameraStreamer 帧源（ImageReader Surface 预留）
- 导航待机械组完成机器人框架

## 7. 协议文档

- `PROTOCOL.md`：图传/桥接/串口协议
- `CHANGELOG.md`：版本变更记录
