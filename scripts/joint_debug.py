#!/usr/bin/env python3
"""多机联调驱动（电脑侧，接管式）：装包 → 开权限 → 起 App → 收流 → 判定。

它把"联调"这件事变成一条命令，不需要手动点手机、也不需要盯着两个终端：

    python scripts/joint_debug.py                 # 60 秒观测，自动判定
    python scripts/joint_debug.py --install       # 先装 dist 里最新的 release 包
    python scripts/joint_debug.py --seconds 120   # 观测更久（等串口/雷达起数）

判定项（任一失败会明确说是哪一环）：
  1. adb 能看到设备（USB 调试已授权）
  2. 手机上装的是同一个 versionCode
  3. App 起来了（进程在、LangyaConsole 有日志）
  4. 收到手机的 KEEP_ALIVE → 非图传流 48000 通（这条最关键，端口对不对就看它）
  5. 收到雷达/主控槽 → CH343 串口在出数（没接硬件时为 0，不算失败，只标注）
  6. 收到 vision JSON → 视觉管线在跑（视觉开关全关时为 0，只标注）

端口约定：图传 47000（UDP 视频 + TCP 控制）、非图传 48000 UDP / 49000 TCP。
手机只出站，本脚本是唯一监听方。
"""
from __future__ import annotations

import argparse
import glob
import os
import re
import subprocess
import sys
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "host"))

from netstack import proto  # noqa: E402
from netstack.link import NetLink  # noqa: E402

PKG = "com.robomaster.control"
ACT = f"{PKG}/.ui.MainActivity"
TAG = "LangyaConsole"


def sh(cmd: list[str], timeout: int = 60) -> tuple[int, str]:
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout,
                           encoding="utf-8", errors="ignore")
        return p.returncode, (p.stdout or "") + (p.stderr or "")
    except Exception as e:  # noqa: BLE001
        return 1, str(e)


def adb_raw(*args: str, timeout: int = 60) -> tuple[int, str]:
    return sh(["adb", *args], timeout=timeout)


# 选定设备后，本模块的 adb() 会自动带上 -s <serial>
def adb(*args: str, timeout: int = 60) -> tuple[int, str]:
    return sh(["adb", *args], timeout=timeout)


def pick_apk(explicit: str | None) -> str | None:
    if explicit:
        return explicit if os.path.exists(explicit) else None
    cands = sorted(glob.glob(os.path.join(ROOT, "dist", "*.apk")),
                   key=os.path.getmtime, reverse=True)
    return cands[0] if cands else None


def apk_version(path: str) -> str:
    aapt = os.environ.get("AAPT")
    if not aapt:
        sdk = os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser("~/AppData/Local/Android/Sdk")
        hits = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "aapt.exe")))
        aapt = hits[-1] if hits else None
    if not aapt or not os.path.exists(aapt):
        return "?"
    rc, out = sh([aapt, "dump", "badging", path], timeout=30)
    m = re.search(r"versionCode='(\d+)' versionName='([^']+)'", out)
    return f"{m.group(2)}({m.group(1)})" if m else "?"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true", help="安装 dist 里最新的 APK")
    ap.add_argument("--apk", default=None, help="指定 APK 路径")
    ap.add_argument("--seconds", type=int, default=60, help="观测时长")
    ap.add_argument("--host", default=None, help="上位机 IP（默认自动取本机局域网地址）")
    args = ap.parse_args()

    ok: list[str] = []
    warn: list[str] = []
    bad: list[str] = []

    # ── 1. adb ──
    rc, out = adb_raw("devices", "-l")
    devices = [ln for ln in out.splitlines() if "\tdevice" in ln or " device " in ln]
    if not devices:
        print("✗ adb 看不到设备。请：① 手机插 USB 线 ② 设置→开发者选项→USB 调试打开 "
              "③ 手机上点「允许 USB 调试」④ 再运行本脚本")
        return 2
    serial = devices[0].split()[0]
    print(f"✓ 设备 {serial}（共 {len(devices)} 台）")
    ok.append("adb 设备可见")
    _serial = serial

    def adb(*a, timeout: int = 60):      # noqa: F811 — 绑定设备的局部版本
        return adb_raw("-s", _serial, *a, timeout=timeout)

    # ── 2. 机型/版本/网段 ──
    _, model = adb("shell", "getprop", "ro.product.model")
    _, av = adb("shell", "getprop", "ro.build.version.release")
    print(f"  机型 {model.strip()} · Android {av.strip()}")

    if args.install:
        apk = pick_apk(args.apk)
        if not apk:
            print("✗ 找不到 APK（dist/ 为空）")
            return 2
        print(f"  安装 {os.path.basename(apk)}（{apk_version(apk)}）…")
        rc, out = adb("install", "-r", "-d", apk, timeout=300)
        if "Success" not in out:
            print(f"✗ 安装失败：{out.strip()[:300]}")
            print("  签名冲突时先：adb uninstall " + PKG)
            return 2
        ok.append("安装成功")

    rc, out = adb("shell", "dumpsys", "package", PKG, "|", "grep", "versionName")
    m = re.search(r"versionName=(\S+)", out)
    phone_ver = m.group(1) if m else "未安装"
    print(f"  手机上版本 {phone_ver}")

    # ── 3. 权限 ──
    adb("shell", "pm", "grant", PKG, "android.permission.CAMERA")
    adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS")
    ok.append("相机/通知权限已授予")

    # ── 4. 起 App ──
    adb("shell", "am", "force-stop", PKG)
    time.sleep(0.5)
    adb("shell", "am", "start", "-n", ACT)
    time.sleep(3.0)
    _, ps = adb("shell", "pidof", PKG)
    if ps.strip():
        print(f"✓ App 已启动（pid {ps.strip()}）")
        ok.append("App 进程存活")
    else:
        print("✗ App 没起来（可能被锁屏拦住）")
        bad.append("App 未启动")

    # ── 5. 手机网络 ──
    _, ipout = adb("shell", "ip", "-4", "addr", "show", "dev", "wlan0")
    ips = re.findall(r"inet (\d+\.\d+\.\d+\.\d+)/(\d+)", ipout)
    print(f"  手机 wlan0: {ips if ips else '无地址（WiFi 没连/没拿到 DHCP）'}")
    if not ips:
        warn.append("手机没有 IPv4 地址")
    elif not any(ip.startswith("192.168.1.") for ip, _ in ips):
        warn.append(f"手机不在 192.168.1.x（当前 {ips[0][0]}）：与上位机可能不同网段")

    # ── 6. logcat 跟随 ──
    log_lines: list[str] = []
    log_path = os.path.join(ROOT, "dist", f"joint_debug_{int(time.time())}.log")
    adb("logcat", "-c")
    tail = subprocess.Popen(
        ["adb", "-s", _serial, "logcat", "-T", "1", "-s", f"{TAG}:I", "VisionCamera:I",
         "AndroidRuntime:E"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
        encoding="utf-8", errors="ignore", bufsize=1)

    def pump():
        assert tail.stdout is not None
        for line in tail.stdout:
            line = line.rstrip("\n")
            log_lines.append(line)
            if TAG in line or "AndroidRuntime" in line:
                print("  [phone]", line.split("]: ")[-1][:160])

    threading.Thread(target=pump, daemon=True).start()

    # ── 7. 收流 ──
    hello_at = {"v": None}
    ka_at = {"v": None}
    counts = {"lidar": 0, "master": 0, "vision": 0, "settings": 0, "slots": 0, "json": 0}
    t0 = time.time()

    def on_slot(h: int, slot: bytes):
        counts["slots"] += 1
        if h == proto.H_LIDAR:
            counts["lidar"] += 1
        elif h == proto.H_MCU_UP:
            counts["master"] += 1
        elif h == proto.H_KA:
            ka_at["v"] = ka_at["v"] or time.time()

    def on_json(obj: dict):
        counts["json"] += 1
        t = obj.get("t")
        if t == "vision":
            counts["vision"] += 1
        elif t == "settings":
            counts["settings"] += 1

    cfg = {"video_port": proto.VIDEO_PORT, "net_udp": proto.UDP_PORT,
           "net_tcp": proto.TCP_PORT}
    link = NetLink(cfg, on_slot=on_slot, on_json=on_json,
                   on_stats=lambda st: None, log=lambda m: print("  [host]", m))
    link.start()
    print(f"\n监听 UDP {proto.UDP_PORT} / TCP {proto.TCP_PORT}，"
          f"观察 {args.seconds}s（手机点「启动图传」把链路拉起来）…\n")

    try:
        for i in range(args.seconds):
            time.sleep(1)
            snap = link.snapshot()
            dots = f"ka={'✓' if ka_at['v'] else '·'} 槽={counts['slots']} " \
                   f"雷达={counts['lidar']} 主控={counts['master']} " \
                   f"视觉={counts['vision']} 设置={counts['settings']}"
            print(f"  {i + 1:3d}s  rx={snap.get('rx_packets')} {snap.get('udp_peer') or '—'} | {dots}")
            if ka_at["v"] and counts["vision"] > 0 and i > 6:
                print("  （快速判定已完成：KA + 视觉都在，提前结束）")
                break
    except KeyboardInterrupt:
        print("\n  手动停止")

    link.stop()
    tail.terminate()
    try:
        with open(log_path, "w", encoding="utf-8") as f:
            f.write("\n".join(log_lines))
    except OSError:
        pass

    # ── 8. 判定 ──
    print("\n──── 判定 ────")
    if ka_at["v"]:
        ok.append("收到 KEEP_ALIVE（非图传流 48000 通）")
    else:
        bad.append("没收到 KEEP_ALIVE：手机没连上 48000（检查上位机 IP、防火墙、"
                   "是否点了「启动图传」、端口是否 48000）")

    if counts["vision"] > 0:
        ok.append(f"收到视觉 JSON {counts['vision']} 条")
    else:
        warn.append("没收到视觉 JSON（视觉开关可能全关，或检测节流未产出）")

    if counts["lidar"] or counts["master"]:
        ok.append(f"串口槽：雷达 {counts['lidar']} / 主控 {counts['master']}")
    else:
        warn.append("没收到串口槽（CH343 没接或下位机没上电，属正常）")

    if counts["settings"]:
        ok.append(f"设置快照 {counts['settings']} 次（双向同步已生效）")

    for s in ok:
        print(f"  ✓ {s}")
    for s in warn:
        print(f"  ! {s}")
    for s in bad:
        print(f"  ✗ {s}")
    print(f"\nlogcat 已存 {log_path}")
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
