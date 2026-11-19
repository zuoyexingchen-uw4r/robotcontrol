"""netstack.fec — 5 数据槽 + 1 校验槽（20%）的 RS 纠错。

与 Android `net/FecBlock.kt`、`video/ReedSolomon.kt` 同一套参数：
GF(2^8)、poly 0x11D、Cauchy 矩阵 `C[t][j] = 1 / ((k+1+t) ^ (j+1))`。

m=1 时恢复退化成「异或掉其它数据槽，再乘逆元」，不需要高斯消元。
块内 6 个槽任意丢 1 个都能补回来；丢 2 个就放弃（宁缺勿错）。
"""
from __future__ import annotations

PRIM = 0x11D

EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= PRIM
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]

# 每个 GF 标量一张 256 项翻译表：整段乘系数走 C 速度
# ⚠️ v == 0 必须显式判掉：LOG[0] 默认 0，不加判断会算成 EXP[LOG[c]] = c
MUL_TAB = [
    bytes((0 if (c == 0 or v == 0) else EXP[LOG[c] + LOG[v]]) for v in range(256))
    for c in range(256)
]


def gf_mul(a: int, b: int) -> int:
    if a == 0 or b == 0:
        return 0
    return EXP[LOG[a] + LOG[b]]


def gf_inv(a: int) -> int:
    if a == 0:
        raise ZeroDivisionError("GF(256) 中 0 没有逆元")
    return EXP[255 - LOG[a]]


def cauchy_row(k: int, t: int) -> list[int]:
    x = k + 1 + t
    return [gf_inv(x ^ (j + 1)) for j in range(k)]


def encode(data_shards: list[bytes], k: int = 5) -> bytes:
    """5 个等长数据槽 → 1 个校验槽。"""
    if len(data_shards) != k:
        raise ValueError(f"需要 {k} 个数据槽，实际 {len(data_shards)}")
    size = len(data_shards[0])
    if any(len(s) != size for s in data_shards):
        raise ValueError("同块数据槽必须等长")
    row = cauchy_row(k, 0)
    acc = bytearray(size)
    for c, shard in zip(row, data_shards):
        if c:
            tab = MUL_TAB[c]
            for i, b in enumerate(shard):
                acc[i] ^= tab[b]
    return bytes(acc)


def recover(data: list[bytes | None], parity: bytes | None, k: int = 5) -> tuple[int, bytes] | None:
    """只丢 1 个数据槽时恢复；丢更多或校验也没了返回 None。"""
    if parity is None or len(data) != k:
        return None
    missing = [i for i, s in enumerate(data) if s is None]
    if len(missing) != 1:
        return None
    r = missing[0]
    size = len(parity)
    row = cauchy_row(k, 0)

    acc = bytearray(parity)
    for j, shard in enumerate(data):
        if j == r or shard is None:
            continue
        c = row[j]
        if c:
            tab = MUL_TAB[c]
            for i, b in enumerate(shard):
                acc[i] ^= tab[b]

    inv = MUL_TAB[gf_inv(row[r])]
    return r, bytes(inv[b] for b in acc)
