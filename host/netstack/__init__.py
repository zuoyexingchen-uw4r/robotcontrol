"""netstack — 狼牙视觉上位机非图传流（协议见仓库根 `NETSTACK.md`）。

模块：
- proto.py     60B 槽 / 16B 包头 / CRC16
- fec.py       5+1 Reed-Solomon（20%）
- link.py      UDP 48000 + TCP 49000 端点，去重 / 合块 / KA 回显 / 长帧
- ipcfg.py     四个固定终端 IP + DHCP，netsh 应用与提权
- pairing.py   扫码配对内容与二维码图像
"""
from .proto import (  # noqa: F401
    H_KA, H_LIDAR, H_MCU_UP, H_UART_DOWN, SLOT, TCP_PORT, UDP_PORT, VIDEO_PORT,
    Packet, crc16, local_op, uart_down, uart_payload,
)
