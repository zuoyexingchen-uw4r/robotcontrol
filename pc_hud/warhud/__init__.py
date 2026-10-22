"""warhud — 狼牙视觉笔记本操控 HUD（War Thunder 风格）。

模块：
- protocol    手机/ESP32 协议编解码（纯函数，可独立自测）
- config      JSON 配置持久化
- stream      ffmpeg 子进程解码裸流 → RGB 帧
- phonesync   视觉结果 UDP 接收 + 设置双向同步
- esp32       WLAN 直连 ESP32-S3（输入下发 + 遥测）
- overlay     HUD 叠加层绘制
- main_window 主窗口组装
"""
