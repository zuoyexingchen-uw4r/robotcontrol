#!/usr/bin/env python3
"""netstack 端到端回环测试：用真 socket 假扮手机，打通 UDP 48000 / TCP 49000。

不是单元测试（`selftest.py` 已覆盖编解码），这里验证的是**端口与链路真的通**：
1. 起 `NetLink`（监听 48000 UDP + 49000 TCP）；
2. 从本机随机端口发一个 KEEP_ALIVE 槽 → 期望上位机原字节回显（手机据此算 RTT）；
3. 发 5 个雷达槽 + 校验槽 → 期望 `on_slot` 收到 5 个 `55 AA`；
4. 发一条 `vision` 长帧 → 期望 `on_json` 收到同一份 JSON；
5. 走 TCP 49000 发一个控制槽 → 期望 `on_slot` 收到 `FA AF`（下行回显方向验证）。

用法：python host/netstack/loopback_test.py [host]
"""
from __future__ import annotations

import json
import os
import socket
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from netstack import proto  # noqa: E402
from netstack.link import NetLink  # noqa: E402

PASS = 0
FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"PASS  {name}  {detail}")
    else:
        FAIL += 1
        print(f"FAIL  {name}  {detail}")


def ka_slot(seq: int) -> bytes:
    return proto.keep_alive(seq, int(time.time() * 1000) & 0xFFFFFFFF)


def lidar_slot(i: int) -> bytes:
    body = bytearray(proto.SLOT)
    body[0], body[1] = 0x55, 0xAA
    body[4] = i & 0xFF          # 转速位当序号用，便于核对
    body[6] = i & 0xFF          # 起始角低位
    return bytes(body)


def main() -> int:
    host = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
    slots: list[bytes] = []
    jsons: list[dict] = []
    stats: list[dict] = []

    cfg = {
        "video_port": proto.VIDEO_PORT,
        "net_udp": proto.UDP_PORT,
        "net_tcp": proto.TCP_PORT,
        "host": "loopback",
        "slot": "R1",
    }
    # on_slot(header, slot)：第一个参数是槽头（0x55AA / 0xAA55 …），第二个才是槽字节
    link = NetLink(cfg, on_slot=lambda h, s: slots.append(s),
                   on_json=lambda o: jsons.append(o),
                   on_stats=lambda st: stats.append(st),
                   log=lambda m: None)
    link.start()
    time.sleep(0.4)

    udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    udp.settimeout(2.0)

    # ── 1. KEEP_ALIVE 回显 ──
    # 注意：上位机收到第一个包时会先发 hello（长帧），所以要一直读到 KA 回显为止
    ka = ka_slot(1)
    pkt = proto.Packet(0, 100, 0, 0, 1, 0, ka).encode()
    udp.sendto(pkt, (host, proto.UDP_PORT))
    echoed = False
    hello_seen = False
    deadline = time.time() + 2.0
    while time.time() < deadline:
        try:
            data, _ = udp.recvfrom(4096)
        except socket.timeout:
            break
        if data[:2] != b"LV":
            continue
        if data[3] & proto.F_LONG:
            hello_seen = True
            continue
        if data[proto.PKT_HDR:proto.PKT_HDR + 2] == ka[:2]:
            echoed = True
            break
    check("UDP 48000 KA 回显", echoed, "上位机原字节回显保活槽")
    check("UDP 48000 首包 hello", hello_seen, "上位机主动发 hello 长帧")

    # ── 2. 5 槽 + 校验（FEC 块）──
    slots.clear()
    data_slots = [lidar_slot(i) for i in range(proto.FEC_K)]
    p = proto.Packet(0, 101, 7, 0, proto.FEC_K, 0, b"".join(data_slots)).encode()
    udp.sendto(p, (host, proto.UDP_PORT))
    par = proto.fec_encode(data_slots) if hasattr(proto, "fec_encode") else None
    if par is not None:
        pp = proto.Packet(proto.F_PARITY, 102, 7, proto.FEC_K, 1, 0, par).encode()
        udp.sendto(pp, (host, proto.UDP_PORT))
    time.sleep(0.5)
    got_lidar = [s for s in slots if s[:2] == b"\x55\xaa"]
    check("UDP 雷达 5 槽到达", len(got_lidar) == 5, f"收到 {len(got_lidar)} 槽")
    check("槽内容未被改写", len(got_lidar) == 5 and got_lidar[2][4] == 2, "第 3 槽序号=2")

    # ── 3. 视觉 JSON 长帧 ──
    jsons.clear()
    obj = {"t": "vision", "ts": 123456789, "fps": 15.0,
           "targets": [{"type": "highland", "cx": 320.0, "cy": 240.0, "conf": 0.9}]}
    body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
    lp = proto.Packet(proto.F_LONG, 103, 11, 0, 1, 0, body).encode()
    udp.sendto(lp, (host, proto.UDP_PORT))
    time.sleep(0.5)
    ok_json = len(jsons) == 1 and jsons[0].get("t") == "vision" and jsons[0].get("fps") == 15.0
    check("UDP 视觉长帧重组", ok_json, f"收到 {len(jsons)} 条")

    # ── 4. TCP 49000 下行 ──
    slots.clear()
    tcp = socket.create_connection((host, proto.TCP_PORT), timeout=3.0)
    tcp.settimeout(2.0)
    fire = bytearray(proto.LEN_FIRE)
    fire[0], fire[1] = 0xFA, 0xAF
    fire[2] = 5
    # 槽固定 60B：写 UART 时按协议长度截断，网络上必须满 60
    slot60 = bytes(fire) + b"\x00" * (proto.SLOT - len(fire))
    tcp_pkt = proto.Packet(proto.F_URGENT, 104, 0, 0, 1, 0, slot60).encode()
    tcp.sendall(len(tcp_pkt).to_bytes(4, "big") + tcp_pkt)

    # 上位机走 TCP 下发一条控制槽 → 手机（这里当"手机"）收到。
    # send_uart 会包成 FA 5B（原始字节写串口）槽，是 HUD 下发指令的通用路径。
    sent = link.send_uart(bytes(fire), transport="tcp")
    time.sleep(0.6)
    got_down = None
    deadline = time.time() + 2.0
    while time.time() < deadline:
        try:
            hdr = tcp.recv(4)
        except (socket.timeout, ConnectionResetError, OSError):
            break
        if len(hdr) < 4:
            break
        n = int.from_bytes(hdr, "big")
        if n <= 0 or n > 65536:
            break
        body = b""
        while len(body) < n:
            chunk = tcp.recv(n - len(body))
            if not chunk:
                break
            body += chunk
        pk = proto.Packet.decode(body)
        if pk is None:
            continue
        if pk.is_long:
            continue
        if any(s[:2] == b"\xfa\x5b" for s in pk.slots()):
            got_down = pk
            break
    check("TCP 49000 建链并收下行", got_down is not None,
          "收到包" if got_down is not None else "无回包")
    check("上位机下行发送返回 true", sent, "send_uart(transport=tcp)")
    if got_down is not None:
        s0 = got_down.slots()[0]
        check("下行槽为 FA 5B（原始字节）", s0[:2] == b"\xfa\x5b",
              f"头={s0[0]:02X}{s0[1]:02X}")
        check("下行负载带原字节", s0[2] == len(fire) and s0[3:3 + len(fire)] == bytes(fire),
              f"len={s0[2]}")

    # ── 5. 统计面（在关闭 TCP 之前取快照，否则客户端数已经归零）──
    time.sleep(0.4)
    snap = link.snapshot()
    check("统计有 RX 计数", snap.get("rx_packets", 0) >= 3, f"rx={snap.get('rx_packets')}")
    check("统计有 UDP 对端", bool(snap.get("udp_peer")), f"{snap.get('udp_peer')}")
    check("统计有 TCP 客户端", snap.get("tcp_clients", 0) >= 1, f"{snap.get('tcp_clients')}")

    tcp.close()
    link.stop()
    udp.close()
    print(f"\n═══ loopback: {PASS} 通过, {FAIL} 失败 ═══")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
