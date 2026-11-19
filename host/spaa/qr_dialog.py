"""spaa.qr_dialog — 按 0 弹出的配对二维码。

二维码内容是与 Android `net/Pairing.kt` 约定的 JSON（见 `netstack/pairing.py`）：
本机 IP、视频端口基址、槽位/队伍；手机扫码后据此连接，不用手填 IP。

没装 `qrcode` 时不报错，退化成“显示文本 + 复制”面板。
"""
from __future__ import annotations

from PyQt5.QtCore import Qt
from PyQt5.QtGui import QImage, QPixmap
from PyQt5.QtWidgets import (
    QApplication, QDialog, QHBoxLayout, QLabel, QPushButton, QVBoxLayout,
)

from netstack import pairing

STYLE = """
QDialog { background: #0E1418; }
QLabel { color: #E8F0F4; font-family: "Microsoft YaHei UI"; }
QLabel#title { font-size: 16px; font-weight: 600; }
QLabel#sub { color: #7A8B94; font-size: 12px; }
QLabel#code { color: #B9C7CE; font-family: Consolas; font-size: 11px; }
QPushButton {
    background: #16303A; color: #E8F0F4; border: 1px solid #1F4A57;
    border-radius: 6px; padding: 6px 14px;
}
QPushButton:hover { background: #1B4150; }
"""


def _pil_to_pixmap(img) -> QPixmap:
    img = img.convert("RGB")
    w, h = img.size
    data = img.tobytes("raw", "RGB")
    qimg = QImage(data, w, h, 3 * w, QImage.Format_RGB888).copy()
    return QPixmap.fromImage(qimg)


class QrDialog(QDialog):

    def __init__(self, payload: dict, parent=None):
        super().__init__(parent)
        self.payload = payload
        self.text = pairing.to_json(payload)
        self.setWindowTitle("扫码配对")
        self.setStyleSheet(STYLE)
        self.setMinimumWidth(420)

        lay = QVBoxLayout(self)
        lay.setContentsMargins(20, 18, 20, 16)
        lay.setSpacing(10)

        title = QLabel("手机扫码连接本机")
        title.setObjectName("title")
        lay.addWidget(title)

        sub = QLabel(pairing.describe(payload) + f"   ·   UDP {payload.get('udp')} / TCP {payload.get('tcp')}")
        sub.setObjectName("sub")
        sub.setWordWrap(True)
        lay.addWidget(sub)

        img = pairing.qr_image(self.text, box_size=8)
        if img is not None:
            lab = QLabel()
            lab.setAlignment(Qt.AlignCenter)
            lab.setPixmap(_pil_to_pixmap(img))
            lay.addWidget(lab)
        else:
            warn = QLabel("未安装 qrcode，无法生成二维码。\n"
                          "pip install qrcode   （装完重开即可）")
            warn.setStyleSheet("color:#FFC53D;")
            warn.setAlignment(Qt.AlignCenter)
            lay.addWidget(warn)

        code = QLabel(self.text)
        code.setObjectName("code")
        code.setWordWrap(True)
        code.setTextInteractionFlags(Qt.TextSelectableByMouse)
        lay.addWidget(code)

        row = QHBoxLayout()
        row.addStretch(1)
        btn_copy = QPushButton("复制内容")
        btn_copy.clicked.connect(self._copy)
        row.addWidget(btn_copy)
        btn_ok = QPushButton("关闭")
        btn_ok.clicked.connect(self.accept)
        row.addWidget(btn_ok)
        lay.addLayout(row)

    def _copy(self) -> None:
        QApplication.clipboard().setText(self.text)
