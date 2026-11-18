"""spaa.fec — GF(256) Reed-Solomon 擦除解码内核。

与 `app/.../video/ReedSolomon.kt` 逐字节一致（Cauchy MDS）：
    C[t][j] = 1 / ((k+1+t) ^ (j+1))，只依赖 k，与 m 无关。

图传（GameStream）与激光桥（LaserBridge）共用同一套校验矩阵，
区别只在分片怎么装进包里。
"""
from __future__ import annotations

PRIM = 0x11D

GF_EXP = [0] * 512
GF_LOG = [0] * 256
_x = 1
for _i in range(255):
    GF_EXP[_i] = _x
    GF_LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= PRIM
for _i in range(255, 512):
    GF_EXP[_i] = GF_EXP[_i - 255]


def gf_mul(a: int, b: int) -> int:
    if a == 0 or b == 0:
        return 0
    return GF_EXP[GF_LOG[a] + GF_LOG[b]]


def gf_inv(a: int) -> int:
    return GF_EXP[255 - GF_LOG[a]]


# 每个系数一张 256 项翻译表：整段字节乘 GF 标量走 C 速度
_GF_TAB = [bytes(gf_mul(c, v) for v in range(256)) for c in range(256)]


def gf_mul_bytes(c: int, data: bytes) -> bytes:
    return data.translate(_GF_TAB[c])


def cauchy_row(k: int, t: int) -> list[int]:
    x = k + 1 + t
    return [gf_inv(x ^ (j + 1)) for j in range(k)]


def rs_erasures_decode(shards: dict[int, bytes], k: int) -> dict[int, bytes]:
    """补全丢失的数据分片。

    shards: {块内分片号: bytes}，0..k-1 为数据、>=k 为校验。
    返回 {分片号: bytes}；无法恢复返回 {}。
    """
    missing = [i for i in range(k) if i not in shards]
    n = len(missing)
    if n == 0:
        return {}
    avail = [i - k for i in sorted(shards) if i >= k]
    if len(avail) < n:
        return {}
    rows = avail[:n]
    size = len(next(iter(shards.values())))

    b = []
    for t in rows:
        crow = cauchy_row(k, t)
        acc = int.from_bytes(shards[k + t], "big")
        for d in range(k):
            if d in shards:
                c = crow[d]
                if c:
                    acc ^= int.from_bytes(gf_mul_bytes(c, shards[d]), "big")
        b.append(acc)

    A = [[cauchy_row(k, t)[d] for d in missing] for t in rows]

    def mul_big(c: int, x: int) -> int:
        if c == 0:
            return 0
        if c == 1:
            return x
        return int.from_bytes(x.to_bytes(size, "big").translate(_GF_TAB[c]), "big")

    M = [A[i][:] + [b[i]] for i in range(n)]
    for col in range(n):
        piv = None
        for r in range(col, n):
            if M[r][col] != 0:
                piv = r
                break
        if piv is None:
            return {}
        M[col], M[piv] = M[piv], M[col]
        inv = gf_inv(M[col][col])
        M[col] = [gf_mul(v, inv) for v in M[col][:n]] + [mul_big(inv, M[col][n])]
        for r in range(n):
            if r != col and M[r][col] != 0:
                f = M[r][col]
                M[r] = [a ^ gf_mul(f, bb) for a, bb in zip(M[r][:n], M[col][:n])] \
                    + [M[r][n] ^ mul_big(f, M[col][n])]
    return {missing[i]: M[i][n].to_bytes(size, "big") for i in range(n)}


def rs_encode(data_shards: list[bytes], parity: int) -> list[bytes]:
    """校验分片（自测用；主机侧不发送，只验证与查表一致）。"""
    k = len(data_shards)
    size = len(data_shards[0])
    out = []
    for t in range(parity):
        row = cauchy_row(k, t)
        p = bytearray(size)
        for d in range(k):
            c = row[d]
            if c:
                tab = _GF_TAB[c]
                src = data_shards[d]
                for i in range(size):
                    p[i] ^= tab[src[i]]
        out.append(bytes(p))
    return out
