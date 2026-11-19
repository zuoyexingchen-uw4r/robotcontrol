"""spaa.settings_dialog — 上位机设置（F2）。

两件事：
1. **网络**：RR1/RR2/BR1/BR2/DHCP 五个模式，选网卡后一键切换（需管理员，会弹 UAC）；
2. **端口 / 槽位**：视频 47000、非图传 UDP 48000 / TCP 49000，以及自己属于哪个槽位。

改 IP 走 `netstack.ipcfg`：非管理员时它把任务写成 JSON、用 runas 拉起 helper，
这里用后台线程轮询结果，避免 UI 卡住。端口与槽位直接写 `~/.spaa/config.json`。
"""
from __future__ import annotations

import threading

from PyQt5.QtCore import QTimer
from PyQt5.QtWidgets import (
    QComboBox, QDialog, QDialogButtonBox, QFormLayout, QHBoxLayout, QLabel,
    QMessageBox, QPushButton, QSpinBox, QVBoxLayout,
)

from netstack import ipcfg, pairing

STYLE = """
QDialog { background: #0E1418; }
QLabel { color: #E8F0F4; font-family: "Microsoft YaHei UI"; }
QComboBox, QSpinBox {
    background: #111C22; color: #E8F0F4; border: 1px solid #1F4A57;
    border-radius: 5px; padding: 4px 8px; min-height: 22px;
}
QComboBox QAbstractItemView { background: #111C22; color: #E8F0F4; selection-background-color: #1B4150; }
QPushButton {
    background: #16303A; color: #E8F0F4; border: 1px solid #1F4A57;
    border-radius: 6px; padding: 6px 14px;
}
QPushButton:hover { background: #1B4150; }
QPushButton:disabled { color: #5F6E76; }
QLabel#hint { color: #7A8B94; font-size: 12px; }
QLabel#ok { color: #00E5C0; }
QLabel#bad { color: #FF4D4F; }
"""


class SettingsDialog(QDialog):

    def __init__(self, cfg: dict, parent=None):
        super().__init__(parent)
        self.cfg = cfg
        self._result: dict | None = None
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._poll)

        self.setWindowTitle("上位机设置")
        self.setStyleSheet(STYLE)
        self.setMinimumWidth(460)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 18, 20, 16)
        lay.setSpacing(12)

        # ── 网络 ──
        form = QFormLayout()
        form.setSpacing(8)

        self.cb_mode = QComboBox()
        for key in ipcfg.MODE_ORDER:
            self.cb_mode.addItem(ipcfg.mode_label(key), key)
        self.cb_mode.setCurrentIndex(max(0, ipcfg.MODE_ORDER.index(cfg.get("ip_mode", "RR1"))))
        self.cb_mode.currentIndexChanged.connect(self._on_mode_changed)
        form.addRow("IP 模式", self.cb_mode)

        self.cb_adapter = QComboBox()
        form.addRow("网卡", self.cb_adapter)

        self.btn_refresh = QPushButton("刷新网卡")
        self.btn_refresh.clicked.connect(self._refresh_adapters)
        form.addRow("", self.btn_refresh)

        self.lb_current = QLabel("")
        self.lb_current.setObjectName("hint")
        form.addRow("当前地址", self.lb_current)

        # ── 端口 / 槽位 ──
        self.sp_video = QSpinBox()
        self.sp_video.setRange(1024, 65535)
        self.sp_video.setValue(int(cfg.get("video_port", 47000)))
        form.addRow("视频端口", self.sp_video)

        self.sp_udp = QSpinBox()
        self.sp_udp.setRange(1024, 65535)
        self.sp_udp.setValue(int(cfg.get("net_udp", 48000)))
        form.addRow("非图传 UDP", self.sp_udp)

        self.sp_tcp = QSpinBox()
        self.sp_tcp.setRange(1024, 65535)
        self.sp_tcp.setValue(int(cfg.get("net_tcp", 49000)))
        form.addRow("非图传 TCP", self.sp_tcp)

        self.cb_slot = QComboBox()
        slot_labels = {m["slot"]: m["label"] for m in ipcfg.MODES.values() if m.get("slot")}
        for key in ("R1", "R2", "B1", "B2"):
            self.cb_slot.addItem(f"{key} · {slot_labels.get(key, '')}", key)
        idx = ("R1", "R2", "B1", "B2").index(cfg.get("slot", "R1")) if cfg.get("slot") in ("R1", "R2", "B1", "B2") else 0
        self.cb_slot.setCurrentIndex(idx)
        form.addRow("本机槽位", self.cb_slot)

        lay.addLayout(form)

        self.lb_status = QLabel("")
        self.lb_status.setWordWrap(True)
        lay.addWidget(self.lb_status)

        row = QHBoxLayout()
        self.btn_apply = QPushButton("应用网络设置（需管理员）")
        self.btn_apply.clicked.connect(self._apply_ip)
        row.addWidget(self.btn_apply)
        row.addStretch(1)
        lay.addLayout(row)

        box = QDialogButtonBox(QDialogButtonBox.Save | QDialogButtonBox.Cancel)
        box.button(QDialogButtonBox.Save).setText("保存")
        box.button(QDialogButtonBox.Cancel).setText("取消")
        box.accepted.connect(self._save)
        box.rejected.connect(self.reject)
        lay.addWidget(box)

        self._refresh_adapters()

    # ── 网卡 / 模式 ──

    def _refresh_adapters(self) -> None:
        adapters = ipcfg.list_adapters()
        keep = self.cb_adapter.currentText() or self.cfg.get("adapter", "")
        self.cb_adapter.clear()
        for a in adapters:
            label = f"{a['name']}  ·  {a['ipv4'] or '无 IPv4'}" + ("" if a["up"] else "  (未连接)")
            self.cb_adapter.addItem(label, a["name"])
        if keep:
            for i in range(self.cb_adapter.count()):
                if self.cb_adapter.itemData(i) == keep:
                    self.cb_adapter.setCurrentIndex(i)
                    break
        self._update_current()

    def _selected_adapter(self) -> str:
        return self.cb_adapter.currentData() or ""

    def _on_mode_changed(self) -> None:
        # 切模式时，若某个网卡已经处于目标地址，自动选中它
        mode = self.cb_mode.currentData()
        target = ipcfg.MODES.get(mode, {}).get("ip")
        if target:
            for a in ipcfg.list_adapters():
                if a["ipv4"] == target:
                    for i in range(self.cb_adapter.count()):
                        if self.cb_adapter.itemData(i) == a["name"]:
                            self.cb_adapter.setCurrentIndex(i)
                            break
                    break
        self._update_current()

    def _update_current(self) -> None:
        name = self._selected_adapter()
        ip = ipcfg.adapter_ip(name) if name else ""
        mode = ipcfg.detect_mode()
        self.lb_current.setText(f"{ip or '—'}    当前匹配模式：{mode}")

    # ── 应用网络 ──

    def _apply_ip(self) -> None:
        mode = self.cb_mode.currentData()
        adapter = self._selected_adapter()
        if not adapter:
            QMessageBox.warning(self, "没有网卡", "请先在下拉框里选择网卡。")
            return
        target = ipcfg.MODES.get(mode, {}).get("ip") or "DHCP"
        self.lb_status.setStyleSheet("color:#7A8B94;")
        self.lb_status.setText(f"正在把「{adapter}」切到 {mode}（{target}）…\n"
                               "若弹出 UAC 授权请点「是」。")
        self.btn_apply.setEnabled(False)
        self._result = None
        threading.Thread(target=self._apply_worker, args=(mode, adapter), daemon=True).start()
        self._timer.start(200)

    def _apply_worker(self, mode: str, adapter: str) -> None:
        self._result = ipcfg.apply(mode, adapter)

    def _poll(self) -> None:
        if self._result is None:
            return
        self._timer.stop()
        r = self._result
        self._result = None
        self.btn_apply.setEnabled(True)
        self._refresh_adapters()
        if r.get("ok"):
            self.lb_status.setStyleSheet("color:#00E5C0;")
            self.lb_status.setText("已应用。" + ("" if not r.get("elevated") else "（通过管理员授权执行）"))
        else:
            self.lb_status.setStyleSheet("color:#FF4D4F;")
            tail = (r.get("output") or "").strip().splitlines()
            self.lb_status.setText("应用失败：" + (tail[-1] if tail else "未知错误"))

    # ── 保存 ──

    def _save(self) -> None:
        cfg = self.cfg
        cfg["ip_mode"] = self.cb_mode.currentData()
        cfg["adapter"] = self._selected_adapter()
        cfg["slot"] = self.cb_slot.currentData()
        cfg["video_port"] = int(self.sp_video.value())
        cfg["ctrl_port"] = int(self.sp_video.value())   # 图传控制与视频同号
        cfg["net_udp"] = int(self.sp_udp.value())
        cfg["net_tcp"] = int(self.sp_tcp.value())
        self.accept()

    # ── 给窗口用 ──

    def pairing_payload(self) -> dict:
        name = self._selected_adapter()
        ip = ipcfg.adapter_ip(name)
        if not ip:
            ip = ipcfg.MODES.get(self.cb_mode.currentData(), {}).get("ip") or ""
        if not ip:
            ip = ipcfg.local_ip_hint()
        return pairing.payload(
            self.cfg, ip,
            port=int(self.sp_video.value()),
            slot=self.cb_slot.currentData(),
        )
