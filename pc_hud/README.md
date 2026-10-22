# 狼牙视觉 · 笔记本操控 HUD（War Thunder 风格）

电脑端操控主控：显示手机图传画面 + 本地渲染视觉叠加层（检测框/准星/提前量），
视觉设置与手机实时双向同步，键鼠操作经 WLAN 直连 ESP32-S3。

## 依赖

- Python 3.10+
- PyQt5：`pip install PyQt5`
- ffmpeg（系统 PATH，或改 `~/.warhud/config.json` 的 `ffmpeg_path`）

```bash
python hud.py --check     # 检查依赖
python hud.py             # 启动
```

## 架构

```
小米手机（视觉节点）
├─ CameraStreamer 图传（H.264/HEVC + RS-FEC + pacing）──UDP 31000──┐
├─ VisionPipeline（第二 Camera2 会话 15fps）                          │
│   └─ 视觉结果 JSON ──UDP 31001 广播───────────────────────────────► │
└─ 设置快照 ──UDP 31001（500ms 心跳）──────────────────────────────► │
                                                                     ▼
笔记本电脑（本程序）                                            ┌─────────┐
├─ ffmpeg 子进程解码裸流 ──► RGB 帧 ──► 画布                      │  HUD   │
├─ 叠加层：检测框/追踪框/准星/提前量/状态面板（本地渲染）          │  窗口  │
├─ 视觉设置面板（Tab 开关）──UDP 31002 单播──► 手机写 SharedPrefs  └─────────┘
└─ 键鼠输入 ──TCP 31010──► ESP32-S3 ──光电隔离 UART──► CH32X03S
    ◀──TCP 31011── 遥测（电压/电流/温度/轮速）
```

## 链路端口

| 链路 | 方向 | 端口 | 内容 |
|---|---|---|---|
| 图传 | 手机→电脑 | UDP 31000 | H.264/HEVC 裸流（RS-FEC） |
| 视觉+设置 | 手机→电脑 | UDP 31001 广播 | vision 帧 + settings 快照（500ms） |
| 设置变更 | 电脑→手机 | UDP 31002 单播 | set_req / hello |
| 操作输入 | 电脑→ESP32 | TCP 31010 | 输入帧 0xFA 0xAE（10Hz，fire 边沿） |
| 遥测 | ESP32→电脑 | TCP 31011 | 遥测帧 0xFA 0xEA（10Hz） |

帧格式详见仓库根 `PROTOCOL.md`「电脑 HUD 协议」节。

## 操作

| 输入 | 行为 |
|---|---|
| 左键（画面外） | 捕获鼠标 → 云台 FPS 控制 |
| 鼠标移动 | 云台 yaw/pitch（0.12°/px） |
| 左键 / F | 开火（边沿触发一帧） |
| Esc | 释放鼠标 |
| Tab | 视觉设置面板开关 |

## 配置

首次运行生成 `~/.warhud/config.json`，关键项：

- `video_port` / `codec`：与手机推流设置一致
- `esp32_ip`：ESP32-S3 的 WLAN 地址
- `ffmpeg_path`：ffmpeg 可执行文件

## 状态

- [x] 视频解码（ffmpeg 管道，low_delay + 8MB UDP 缓冲）
- [x] HUD 叠加层（检测框/追踪框/提前量/状态面板/弹药/计时）
- [x] 视觉结果 UDP 接收 + 设置双向同步（协议层 13/13 自测）
- [x] ESP32 输入下发 + 遥测（协议层自测）
- [ ] 手机端视觉结果发送（APP 侧开发中）
- [ ] ESP32-S3 WLAN TCP server（电控侧）
- [ ] 比赛计时/比分手动控制（裁判指令）
- [ ] 小地图（等里程计数据）
