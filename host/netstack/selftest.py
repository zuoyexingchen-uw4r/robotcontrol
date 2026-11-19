#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""netstack 自测（纯逻辑，不开端口、不需要 PyQt）。

    python host/netstack/selftest.py
    python -m netstack.selftest          # 在 host/ 目录下

覆盖：CRC / 包头往返 / 长帧 / 槽工具 / FEC 丢槽恢复 / 去重 / 合块 / JSON 重组 /
netsh 命令生成 / 二维码内容格式。
"""
from __future__ import annotations

import json
import os
import sys

if __package__ in (None, ""):
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from netstack import fec, ipcfg, pairing  # noqa: E402
from netstack.link import NetLink  # noqa: E402
from netstack.proto import (  # noqa: E402
    FEC_K, F_LONG, F_PARITY, H_KA, H_LIDAR, H_MCU_UP, H_UART_DOWN, SLOT,
    Packet, crc16, is_keepalive, ka_seq, ka_ts, keep_alive, local_op, local_op_arg,
    slot_header, uart_down, uart_payload,
)

PASS = 0
FAIL = 0


def check(name: str, cond: bool, extra: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
    else:
        FAIL += 1
        print(f"FAIL  {name} {extra}")


def test_crc() -> None:
    check("CRC16 标准向量", crc16(b"123456789") == 0x4B37, hex(crc16(b"123456789")))
    check("CRC16 空串", crc16(b"") == 0xFFFF)


def test_packet() -> None:
    slots = [bytes([(i + j) & 0xFF for j in range(SLOT)]) for i in range(20)]
    p = Packet(0, 7, 3, 2, 20, 1234, b"".join(slots))
    raw = p.encode()
    check("20 槽包 1216B", len(raw) == 1216, str(len(raw)))

    d = Packet.decode(raw)
    check("包头往返", d is not None and d.seq == 7 and d.block_id == 3
          and d.shard_start == 2 and d.count == 20 and len(d.payload) == 1200)
    check("槽切分", d is not None and d.slots()[19] == slots[19])

    bad = bytearray(raw)
    bad[5] ^= 0xFF
    check("CRC 拒篡改", Packet.decode(bytes(bad)) is None)

    bad2 = bytearray(raw)
    bad2[0] = 0
    check("magic 拒绝", Packet.decode(bytes(bad2)) is None)

    lp = Packet(F_LONG, 1, 9, 0, 1, 0, b"x" * 700)
    ld = Packet.decode(lp.encode())
    check("长帧往返", ld is not None and ld.is_long and len(ld.payload) == 700)

    check("去重键一致", p.dedupe_key() == Packet.decode(raw).dedupe_key())


def test_slots() -> None:
    ka = keep_alive(41, 0x11223344)
    check("KA 帧头", is_keepalive(ka) and slot_header(ka) == H_KA)
    check("KA 序号/时间", ka_seq(ka) == 41 and ka_ts(ka) == 0x11223344)
    check("KA CRC", crc16(ka, 0, 58) == int.from_bytes(ka[58:60], "little"))

    op = local_op(ipcfg.MODES["RR1"]["ip"] and 1, bytes([192, 168, 1, 13]))
    check("本机操作帧头", slot_header(op) == 0xFA5A)
    check("本机操作参数", local_op_arg(op) == bytes([192, 168, 1, 13]))

    raw = bytes(range(120))
    slots = uart_down(raw)
    check("120B 分 3 槽", len(slots) == 3, str(len(slots)))
    check("分片帧头", all(slot_header(s) == H_UART_DOWN for s in slots))
    check("原始字节往返", b"".join(uart_payload(s) for s in slots) == raw)
    check("本机操作不写 UART", uart_payload(op) is None)


def test_fec() -> None:
    data = [bytes([(i * 60 + j) % 251 for j in range(SLOT)]) for i in range(FEC_K)]
    parity = fec.encode(data)
    check("校验槽 60B", len(parity) == SLOT)

    lost = list(data)
    lost[2] = None
    rec = fec.recover(lost, parity)
    check("丢 1 槽可恢复", rec is not None and rec[0] == 2 and rec[1] == data[2])

    lost2 = list(data)
    lost2[0] = lost2[3] = None
    check("丢 2 槽拒绝", fec.recover(lost2, parity) is None)
    check("校验丢失拒绝", fec.recover([None] + data[1:], None) is None)


def test_link_blocks() -> None:
    got: list[tuple[int, bytes]] = []
    jsons: list[dict] = []
    link = NetLink({"net_udp": 0, "net_tcp": 0},
                   on_slot=lambda h, s: got.append((h, s)),
                   on_json=lambda m: jsons.append(m),
                   log=lambda *_: None)

    data = []
    for i in range(FEC_K):
        s = bytearray(SLOT)
        s[0], s[1] = 0x55, 0xAA
        s[2] = i
        data.append(bytes(s))
    parity = fec.encode(data)

    # 先发 4 个数据槽（丢第 5 个），再发校验包：应当恢复出第 5 槽
    pkt = Packet(0, 1, 0, 0, 4, 0, b"".join(data[:4]))
    link._handle(pkt.encode(), "udp", ("127.0.0.1", 1))
    check("缺 1 槽先不吐", len(got) == 0, str(len(got)))

    pp = Packet(F_PARITY, 2, 0, FEC_K, 1, 0, parity)
    link._handle(pp.encode(), "udp", ("127.0.0.1", 1))
    check("校验包补齐 5 槽", len(got) == 5, str(len(got)))
    check("恢复内容正确", got[4][1] == data[4])
    check("FEC 计数", link.stats["fec_recovered"] == 1)

    # 重复包去重
    link._handle(pkt.encode(), "udp", ("127.0.0.1", 1))
    check("重放去重", link.stats["rx_dup"] == 1, str(link.stats["rx_dup"]))

    # 长帧 JSON 分片
    js = json.dumps({"t": "vision", "targets": [1, 2, 3], "pad": "x" * 200}).encode()
    frags = [js[i:i + 300] for i in range(0, len(js), 300)]
    for i, chunk in enumerate(frags):
        link._handle(Packet(F_LONG, 10 + i, 77, i, len(frags), 0, chunk).encode(),
                     "udp", ("127.0.0.1", 1))
    check("JSON 分片重组", len(jsons) == 1 and jsons[0]["t"] == "vision")


def test_keepalive_echo() -> None:
    sent = []
    link = NetLink({"net_udp": 0, "net_tcp": 0}, log=lambda *_: None)
    link._send_raw = lambda pkt, transport, peer: sent.append((transport, peer, pkt)) or True
    link._greeted.add(("127.0.0.1", 2))     # 先标记已问候，避免 hello 混进断言
    ka = keep_alive(5, 999)
    link._handle(Packet(0, 1, 0, 0, 1, 0, ka).encode(), "udp", ("127.0.0.1", 2))
    check("KA 被回显", len(sent) == 1)
    if sent:
        echoed = Packet.decode(sent[0][2])
        check("回显内容不变", echoed is not None and echoed.payload[:SLOT] == ka)
    check("KA 不抛给上层", link.stats["ka_echoed"] == 1)


def test_ipcfg() -> None:
    rr1 = ipcfg.build_commands("RR1", "以太网")
    check("RR1 静态命令", rr1[0][-3:] == ["static", "192.168.1.13", "255.255.255.0"], str(rr1))
    dhcp = ipcfg.build_commands("DHCP", "WLAN")
    check("DHCP 命令", dhcp[0][-1] == "dhcp" and "WLAN" in " ".join(dhcp[0]))
    check("模式表 5 项", len(ipcfg.MODES) == 5 and ipcfg.MODE_ORDER[-1] == "DHCP")
    check("终端地址表", ipcfg.MODES["BR2"]["ip"] == "192.168.1.16")


def test_pairing() -> None:
    cfg = {"video_port": 47000, "net_udp": 48000, "net_tcp": 49000, "slot": "BR1"}
    p = pairing.payload(cfg, "192.168.1.15")
    check("二维码字段", p["ip"] == "192.168.1.15" and p["port"] == 47000
          and p["slot"] == "B1" and p["team"] == "blue")
    text = pairing.to_json(p)
    back = json.loads(text)
    check("二维码 JSON 可解析", back["slot"] == "B1" and back["udp"] == 48000)
    check("二维码体积可控", len(text) < 200, str(len(text)))


def run() -> int:
    test_crc()
    test_packet()
    test_slots()
    test_fec()
    test_link_blocks()
    test_keepalive_echo()
    test_ipcfg()
    test_pairing()
    print(f"netstack selftest: {PASS}/{PASS + FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(run())
