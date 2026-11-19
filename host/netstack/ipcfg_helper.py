#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""提权执行 IP 配置。由 `ipcfg.apply()` 通过 ShellExecuteW("runas") 拉起。

用法（内部）：
    python ipcfg_helper.py job.json

job.json = {"adapter": "以太网", "mode": "RR1", "result": "C:\\...\\out.json"}
结果写进 result 文件，父进程轮询读取（ShellExecuteW 拿不到子进程退出码）。
"""
from __future__ import annotations

import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from ipcfg import build_commands, _run  # noqa: E402


def main() -> int:
    if len(sys.argv) < 2:
        return 2
    with open(sys.argv[1], "r", encoding="utf-8") as f:
        job = json.load(f)

    result_path = job.get("result") or ""
    try:
        cmds = build_commands(job["mode"], job["adapter"])
        res = _run(cmds)
    except Exception as e:
        res = {"ok": False, "output": str(e)}

    if result_path:
        try:
            with open(result_path, "w", encoding="utf-8") as f:
                json.dump(res, f, ensure_ascii=False)
        except OSError:
            pass
    return 0 if res.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
