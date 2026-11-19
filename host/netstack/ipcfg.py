"""netstack.ipcfg — 操作手电脑的网卡配置（Windows）。

比赛给了四个固定终端地址（校内赛通信协议 §2.2）＋ DHCP：

| 模式 | 终端 IP | 槽位 | 机器人 IP |
|---|---|---|---|
| RR1 | 192.168.1.13 | R1 红方射击 | 192.168.1.3 |
| RR2 | 192.168.1.14 | R2 红方兑矿 | 192.168.1.4 |
| BR1 | 192.168.1.15 | B1 蓝方射击 | 192.168.1.5 |
| BR2 | 192.168.1.16 | B2 蓝方兑矿 | 192.168.1.6 |
| DHCP | 自动获取 | — | — |

改 IP 需要管理员权限。本模块不替用户静默提权：非管理员时把任务写成 JSON，
用 `runas` 拉起 `ipcfg_helper.py`（会弹 UAC），再把结果写回临时文件。
"""
from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import tempfile
import time

MASK = "255.255.255.0"

MODES: dict[str, dict] = {
    "RR1": dict(ip="192.168.1.13", slot="R1", team="red", robot="red_r1", label="红方 R1 · 射击"),
    "RR2": dict(ip="192.168.1.14", slot="R2", team="red", robot="red_r2", label="红方 R2 · 兑矿"),
    "BR1": dict(ip="192.168.1.15", slot="B1", team="blue", robot="blue_r1", label="蓝方 B1 · 射击"),
    "BR2": dict(ip="192.168.1.16", slot="B2", team="blue", robot="blue_r2", label="蓝方 B2 · 兑矿"),
    "DHCP": dict(ip=None, slot="", team="", robot="", label="自动获取（DHCP）"),
}

MODE_ORDER = ["RR1", "RR2", "BR1", "BR2", "DHCP"]

HELPER = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ipcfg_helper.py")


def mode_label(key: str) -> str:
    m = MODES.get(key)
    if not m:
        return key
    return f"{key} · {m['label']}" + (f"（{m['ip']}）" if m["ip"] else "")


def list_adapters() -> list[dict]:
    """网卡列表：[{name, ipv4, mask, up}]，有线/已连接排前面。"""
    try:
        import psutil
    except ImportError:
        return []

    stats = {}
    try:
        stats = psutil.net_if_stats()
    except Exception:
        pass

    out = []
    for name, addrs in psutil.net_if_addrs().items():
        ipv4 = mask = ""
        for a in addrs:
            if a.family == socket.AF_INET and not a.address.startswith("127."):
                ipv4, mask = a.address, a.netmask or ""
                break
        st = stats.get(name)
        out.append({
            "name": name,
            "ipv4": ipv4,
            "mask": mask,
            "up": bool(st.isup) if st else False,
        })

    def rank(a: dict) -> tuple:
        return (not a["up"], not a["ipv4"], a["name"])

    return sorted(out, key=rank)


def detect_mode(adapters: list[dict] | None = None) -> str:
    """当前生效的模式：网卡上出现某个固定 IP 就认为处于该模式，否则 DHCP。"""
    addrs = adapters if adapters is not None else list_adapters()
    ips = {a["ipv4"] for a in addrs if a["ipv4"]}
    for key in MODE_ORDER:
        ip = MODES[key]["ip"]
        if ip and ip in ips:
            return key
    return "DHCP"


def adapter_ip(name: str) -> str:
    for a in list_adapters():
        if a["name"] == name:
            return a["ipv4"]
    return ""


def local_ip_hint() -> str:
    """默认路由出口 IP（不真发包）；失败回落到 127.0.0.1。"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def is_admin() -> bool:
    if os.name != "nt":
        return False
    try:
        import ctypes
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False


def build_commands(mode: str, adapter: str) -> list[list[str]]:
    """netsh 命令列表（纯函数，便于自测）。"""
    if mode not in MODES:
        raise ValueError(f"未知 IP 模式 {mode}")
    if not adapter:
        raise ValueError("未选择网卡")
    m = MODES[mode]
    if m["ip"]:
        return [[
            "netsh", "interface", "ip", "set", "address",
            f"name={adapter}", "static", m["ip"], MASK,
        ]]
    return [[
        "netsh", "interface", "ip", "set", "address", f"name={adapter}", "dhcp",
    ]]


def _run(cmds: list[list[str]]) -> dict:
    logs = []
    ok = True
    for cmd in cmds:
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=20,
                               encoding="mbcs", errors="replace")
        except Exception as e:
            return {"ok": False, "output": f"{' '.join(cmd)}\n{e}"}
        logs.append(" ".join(cmd))
        if p.stdout.strip():
            logs.append(p.stdout.strip())
        if p.stderr.strip():
            logs.append(p.stderr.strip())
        if p.returncode != 0:
            ok = False
            logs.append(f"退出码 {p.returncode}")
    return {"ok": ok, "output": "\n".join(logs)}


def apply(mode: str, adapter: str, timeout: float = 25.0) -> dict:
    """应用 IP 模式。非管理员时弹 UAC 走 helper。返回 {ok, output, elevated}。"""
    if os.name != "nt":
        return {"ok": False, "output": "只有 Windows 支持改网卡 IP", "elevated": False}
    try:
        cmds = build_commands(mode, adapter)
    except ValueError as e:
        return {"ok": False, "output": str(e), "elevated": False}

    if is_admin():
        r = _run(cmds)
        r["elevated"] = False
        return r

    fd, job_path = tempfile.mkstemp(prefix="spaa-ip-", suffix=".json")
    os.close(fd)
    fd, res_path = tempfile.mkstemp(prefix="spaa-ip-", suffix=".result")
    os.close(fd)
    with open(job_path, "w", encoding="utf-8") as f:
        json.dump({"adapter": adapter, "mode": mode, "result": res_path}, f, ensure_ascii=False)

    try:
        import ctypes
        rc = ctypes.windll.shell32.ShellExecuteW(
            None, "runas", sys.executable, f'"{HELPER}" "{job_path}"', None, 0,
        )
        if rc <= 32:
            return {"ok": False, "output": "提权被取消（UAC）", "elevated": True}
    except Exception as e:
        return {"ok": False, "output": f"提权失败：{e}", "elevated": True}

    deadline = time.time() + timeout
    while time.time() < deadline:
        time.sleep(0.25)
        try:
            with open(res_path, "r", encoding="utf-8") as f:
                r = json.load(f)
            r["elevated"] = True
            return r
        except (OSError, json.JSONDecodeError):
            continue
    return {"ok": False, "output": "提权进程超时未返回结果", "elevated": True}
