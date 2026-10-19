#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
串口透传上位机（快速验证 电脑 ⇄ CH343 双向通讯）。

原理：**电脑开端口监听，手机 App 主动连接**（2026-10 协议方向反转，
      旧版是手机起 TCP 服务、电脑连手机）。手机连上后会把 OTG-CH343
      透明转发出来，本脚本与 CH343 构成全双工字节管道（收/发各一路，互不阻塞）。

用法示例：
  # 交互式（typed 内容实时发给 CH343，收到的实时打印）
  python serial_host.py --bind 0.0.0.0 --port 8765

  # 单发 + 收包（适合脚本化验证）：发送一行 PING，持续打印 3 秒回应
  python serial_host.py --host 192.168.4.1 --send "PING" --nl --hex --after 3

  # 纯发送管道：把文件内容灌给 CH343
  python serial_host.py --host 192.168.4.1 --send-file cmds.txt --nl
"""
import argparse
import socket
import sys
import threading
import time


def main():
    ap = argparse.ArgumentParser(description="CH343 serial-over-TCP host (full-duplex)")
    ap.add_argument("--bind", default="0.0.0.0", help="监听地址（默认 0.0.0.0，等手机连过来）")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--hex", action="store_true", help="以 hex 显示收到的数据")
    ap.add_argument("--send", default=None, help="连接后发送的一段文本")
    ap.add_argument("--send-file", default=None, help="连接后发送整个文件内容")
    ap.add_argument("--nl", action="store_true", help="发送时补 \\r\\n")
    ap.add_argument("--after", type=float, default=0.0, help="发送后再持续接收 N 秒（0=不限）")
    args = ap.parse_args()

    # 电脑开端口监听，等手机连上来（协议方向：电脑监听、手机连接）
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((args.bind, args.port))
        srv.listen(1)
    except OSError as e:
        print(f"[listen failed] {args.bind}:{args.port} -> {e}", file=sys.stderr)
        sys.exit(1)
    print(f"[listening] {args.bind}:{args.port}，等待手机连接…", file=sys.stderr)
    try:
        s, addr = srv.accept()
    except KeyboardInterrupt:
        srv.close()
        sys.exit(1)
    srv.close()
    s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    print(f"[connected] 手机 {addr[0]}:{addr[1]}", file=sys.stderr)

    stop = threading.Event()

    def rx():
        try:
            while not stop.is_set():
                data = s.recv(4096)
                if not data:
                    print("[rx] 对端关闭", file=sys.stderr)
                    break
                if args.hex:
                    print(data.hex(" "), flush=True)
                else:
                    sys.stdout.buffer.write(data)
                    sys.stdout.buffer.flush()
        except Exception as e:
            print(f"[rx end] {e}", file=sys.stderr)
        finally:
            stop.set()

    t = threading.Thread(target=rx, daemon=True)
    t.start()

    try:
        # 首发的握手回显（手机发的 RB1 <baud> otg）会在 rx 里打印出来
        if args.send_file:
            with open(args.send_file, "rb") as f:
                s.sendall(f.read())
        if args.send is not None:
            payload = args.send.encode() + (b"\r\n" if args.nl else b"")
            s.sendall(payload)

        if args.after > 0:
            time.sleep(args.after)
            stop.set()
        elif args.send is None and args.send_file is None and sys.stdin.isatty():
            # 交互模式：stdin -> socket
            while not stop.is_set():
                line = sys.stdin.buffer.readline()
                if not line:
                    break
                s.sendall(line)
        else:
            # 非交互且无 --after：把剩余 stdin 灌进去
            while not stop.is_set():
                chunk = sys.stdin.buffer.read(4096)
                if not chunk:
                    break
                s.sendall(chunk)
            time.sleep(0.3)
            stop.set()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        try:
            s.close()
        except OSError:
            pass


if __name__ == "__main__":
    main()
