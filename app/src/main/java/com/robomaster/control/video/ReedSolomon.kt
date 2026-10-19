package com.robomaster.control.video

/**
 * GF(2^8) Reed-Solomon 系统编码（发送端用），GameStream FEC 分片保护。
 *
 * 域：原始多项式 0x11D（与 Sunshine/Moonlight 同族），α = x 为本原元。
 * 系统码：k 个数据分片原样传输，尾部追加 m 个校验分片。
 *
 * 校验矩阵取 **Cauchy 矩阵**：
 *   C[t][j] = 1 / (x_t ⊕ y_j)，x_t = k+1+t，y_j = j+1（t=0..m-1, j=0..k-1）
 * x 集 {k+1..k+m} 与 y 集 {1..k} 不相交、各自相异。由 Cauchy 矩阵性质
 * （任意方形子阵的行列式 = 两组差积之比，域内全非零），生成矩阵 [I; C] 是 MDS：
 * **任意丢失 ≤ m 个分片**（数据/校验混合计数）均可恢复。
 *
 * 矩阵只依赖 k、不依赖 m——接收端收到任意 n 个 parity 行都能算出对应行，
 * 无需知道发送端配置的 fecPercentage。
 *
 * 只实现编码；接收端已知丢包位置（RTP seq 缺段），做擦除解码（高斯消元）。
 * 解码端矩阵必须与这里一致：host/video_host.py 的 cauchyRow() 是同一公式。
 */
object ReedSolomon {

    private const val PRIM = 0x11D

    /** exp/log 表，GF(256) */
    val exp = IntArray(512)
    val log = IntArray(256)

    /** 256x256 乘法表，避免实时查表两跳 */
    private val mulTable = Array(256) { ByteArray(256) }

    init {
        var x = 1
        for (i in 0 until 255) {
            exp[i] = x
            log[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor PRIM
        }
        for (i in 255 until 512) exp[i] = exp[i - 255]
        for (a in 0 until 256) {
            for (b in 0 until 256) {
                mulTable[a][b] = (if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]).toByte()
            }
        }
    }

    fun mul(a: Int, b: Int): Int = mulTable[a][b].toInt() and 0xFF

    /** GF(256) 乘法逆元：a^254 = a^(-1) */
    fun inv(a: Int): Int {
        require(a != 0) { "0 没有逆元" }
        return exp[255 - log[a]]
    }

    /**
     * m×k Cauchy 校验矩阵：C[t][j] = inv((k+1+t) ⊕ (j+1))。
     * 要求 k≥1、m≥1、k+m≤255（GF(256) 仅 255 个非零元，x/y 两组必须不相交）。
     */
    fun parityMatrix(k: Int, m: Int): Array<IntArray> {
        require(k >= 1 && m >= 1 && k + m <= 255) {
            "Cauchy 矩阵要求 k>=1, m>=1, k+m<=255（当前 k=$k, m=$m）"
        }
        return Array(m) { t ->
            val x = k + 1 + t
            IntArray(k) { j -> inv(x xor (j + 1)) }
        }
    }

    /**
     * 系统编码：dataShards 必须等长，返回 m 个等长校验分片。
     * 按系数查表逐行累加（顺序访存），1080p 帧级别开销可忽略。
     */
    fun encode(dataShards: List<ByteArray>, parity: Int): List<ByteArray> {
        require(dataShards.isNotEmpty() && parity >= 1) { "encode 需要 k>=1 且 parity>=1" }
        val k = dataShards.size
        val size = dataShards[0].size
        require(dataShards.all { it.size == size }) { "分片必须等长" }
        val rows = parityMatrix(k, parity)
        val out = ArrayList<ByteArray>(parity)
        for (t in 0 until parity) {
            val p = ByteArray(size)
            val row = rows[t]
            for (d in 0 until k) {
                val c = row[d]
                if (c == 0) continue
                val tab = mulTable[c] // 该系数的 256 项乘法表
                val src = dataShards[d]
                for (i in 0 until size) {
                    p[i] = (p[i].toInt() xor (tab[src[i].toInt() and 0xFF].toInt() and 0xFF)).toByte()
                }
            }
            out += p
        }
        return out
    }
}
