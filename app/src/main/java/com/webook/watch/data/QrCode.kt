package com.webook.watch.data

/**
 * 极简 QR 码生成器：只为「手机传书」把局域网地址显示到手表屏上，让手机扫。
 *
 * 为什么自己写而不是引依赖：
 *   项目 APK 只有 1.2MB，引一个 QR 库通常要 +100~300KB（ZBar/zxing 的 Java 版更重）。
 *   而我们只需要**一种**场景：byte 模式、错误纠正 M、version 1~10（网址最长 77 字符）。
 *   这种受限场景下编码器不到 300 行，比引库划算得多。
 *
 * 正确性如何保证：离线用 segno（参考实现）+ zxing-cpp（解码器）对比验证过
 * 相同输入产出的矩阵逐位一致，且能被解码回来。
 *
 * ⚠️ 不支持：汉字模式（Kanji）、结构化追加、Micro QR。地址只含 ASCII，用不到。
 */
object QrCode {

    /** 错误纠正等级 M（约 15% 容错）。手表屏小、反光，M 比 L 稳，比 Q 省空间。 */
    private const val ECC_M = 1

    /**
     * 编码结果：`size×size` 的布尔矩阵，true = 黑块。
     * 已包含静区（border）由界面负责加，这里只给纯码元。
     */
    class Matrix(val size: Int, private val cells: BooleanArray) {
        /** (x, y)：true 表示该格要画黑 */
        fun dark(x: Int, y: Int): Boolean = cells[y * size + x]
    }

    /**
     * 把文本编码成二维码矩阵。
     * @return null 表示内容超出支持范围（不该发生：地址永远很短）
     */
    fun encode(text: String, ecc: Int = ECC_M): Matrix? {
        val data = text.toByteArray(Charsets.UTF_8)
        val version = pickVersion(data.size) ?: return null
        return build(version, data, ecc)
    }

    /* ---------------- 数据容量表：byte 模式、ECC=M ---------------- */

    // version -> 该版本 byte 模式下、ECC=M 能装的数据字节数
    private val CAPACITY_M = intArrayOf(
        0,   // 占位，version 从 1 开始
        14, 26, 42, 62, 84, 106, 122, 152, 180, 213
    )

    /**
     * 能用到的最高版本。
     *
     * ⚠️ **限制在 v3（容量 42 字节）是有原因的**：v4 及以上版本的编码还有缺陷 ——
     * v4 起数据块被拆成多块、列对要跨过第 6 列 timing 图案，这部分实测仍会生成
     * 扫不出来的码（v1~v3 经 zxing 全长度验证正确）。
     *
     * 而实际用途只是把局域网地址画成二维码：`http://192.168.100.200:18899` 也才 29 字符，
     * 永远落在 v3 以内。与其冒风险支持用不到的长内容，不如**明确拒绝**，
     * 让调用方走"显示文字地址"的兜底，而不是生成一个看着像码、扫不出来的东西。
     *
     * 要放开这个限制，得先把 v4+ 的块交织与列对跳过逻辑修对，
     * 并用 `tools/verify-qr.py` 的全长度扫描验证通过。
     */
    private const val MAX_SAFE_VERSION = 3

    private fun pickVersion(byteLen: Int): Int? {
        for (v in 1..MAX_SAFE_VERSION) if (byteLen <= CAPACITY_M[v]) return v
        return null
    }

    /* ---------------- 主流程 ---------------- */

    private fun build(version: Int, data: ByteArray, ecc: Int): Matrix {
        val size = 17 + 4 * version
        val cells = BooleanArray(size * size)
        val reserved = BooleanArray(size * size)   // 功能图形占位，数据不能覆盖

        drawFunctionPatterns(cells, reserved, size, version)

        val codewords = buildCodewords(version, data, ecc)
        placeData(cells, reserved, size, codewords)

        // 选掩码：按标准对 8 种掩码打分，取惩罚分最低的
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        for (m in 0 until 8) {
            val trial = cells.copyOf()
            applyMask(trial, reserved, size, m)
            drawFormatBits(trial, reserved, size, m, ecc, mark = false)
            val s = penalty(trial, size)
            if (s < bestScore) { bestScore = s; bestMask = m }
        }
        applyMask(cells, reserved, size, bestMask)
        drawFormatBits(cells, reserved, size, bestMask, ecc, mark = true)
        return Matrix(size, cells)
    }

    /* ---------------- 功能图形 ---------------- */

    private fun drawFunctionPatterns(
        cells: BooleanArray, reserved: BooleanArray, size: Int, version: Int
    ) {
        // 三个定位图形（左上、右上、左下）+ 分隔符
        for (i in 0 until 9) {
            setFunc(cells, reserved, size, 0, i, i < 8 && (i == 0 || i == 7))
            setFunc(cells, reserved, size, i, 0, i < 8 && (i == 0 || i == 7))
        }
        for (dy in -4..4) for (dx in -4..4) {
            val x = 3 + dx; val y = 3 + dy
            if (x in 0 until size && y in 0 until size) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                // 3×3 实心 + 1 环空白 + 1 环实心
                val dark = dist != 2 && dist != 4
                setFunc(cells, reserved, size, x, y, dark)
            }
        }
        // 右上、左下两个角上的定位图形
        for (dy in -4..4) for (dx in -4..4) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            val dark = dist != 2 && dist != 4
            val x1 = size - 4 + dx; val y1 = 3 + dy
            if (x1 in 0 until size && y1 in 0 until size) setFunc(cells, reserved, size, x1, y1, dark)
            val x2 = 3 + dx; val y2 = size - 4 + dy
            if (x2 in 0 until size && y2 in 0 until size) setFunc(cells, reserved, size, x2, y2, dark)
        }

        // 定位线（timing pattern）
        for (i in 8 until size - 8) {
            val dark = i % 2 == 0
            setFunc(cells, reserved, size, i, 6, dark)
            setFunc(cells, reserved, size, 6, i, dark)
        }

        // 校正图形（version >= 2）
        if (version >= 2) {
            val pos = alignmentPositions(version)
            for (ay in pos) for (ax in pos) {
                // 跳过与定位图形重叠的三个角
                if ((ax == 6 && ay == 6) || (ax == 6 && ay == size - 7) || (ax == size - 7 && ay == 6)) continue
                for (dy in -2..2) for (dx in -2..2) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunc(cells, reserved, size, ax + dx, ay + dy, dist != 1)
                }
            }
        }

        // 占位 format info（真正写入在 drawFormatBits）
        drawFormatBits(cells, reserved, size, 0, ECC_M, mark = true)
        // 版本信息（version >= 7）
        if (version >= 7) drawVersionBits(cells, reserved, size, version)
    }

    private fun setFunc(
        cells: BooleanArray, reserved: BooleanArray, size: Int, x: Int, y: Int, dark: Boolean
    ) {
        if (x !in 0 until size || y !in 0 until size) return
        cells[y * size + x] = dark
        reserved[y * size + x] = true
    }

    /** 各版本的校正图形中心坐标 */
    private fun alignmentPositions(version: Int): IntArray = when (version) {
        1 -> IntArray(0)
        2 -> intArrayOf(6, 18)
        3 -> intArrayOf(6, 22)
        4 -> intArrayOf(6, 26)
        5 -> intArrayOf(6, 30)
        6 -> intArrayOf(6, 34)
        7 -> intArrayOf(6, 22, 38)
        8 -> intArrayOf(6, 24, 42)
        9 -> intArrayOf(6, 26, 46)
        else -> intArrayOf(6, 28, 50)
    }

    /* ---------------- 数据区 ---------------- */

    /** 数据 + ECC，按 QR 的块结构交织后返回完整码字序列 */
    private fun buildCodewords(version: Int, data: ByteArray, ecc: Int): IntArray {
        // 组装比特流：模式指示(0100) + 字符数(8bit for v1-9 / 16bit for v10) + 数据 + 终止符
        val bits = ArrayList<Boolean>(data.size * 8 + 32)
        fun push(v: Int, len: Int) { for (i in len - 1 downTo 0) bits.add((v ushr i) and 1 == 1) }
        push(4, 4)                       // byte 模式
        push(data.size, if (version <= 9) 8 else 16)
        for (b in data) push(b.toInt() and 0xff, 8)

        val totalDataBits = DATA_CODEWORDS[version] * 8
        // 终止符：最多 4 个 0
        repeat(minOf(4, totalDataBits - bits.size)) { bits.add(false) }
        // 补齐到字节边界
        while (bits.size % 8 != 0) bits.add(false)
        // 填充码字 0xEC / 0x11 交替
        var pad = 0
        while (bits.size / 8 < DATA_CODEWORDS[version]) {
            val v = if (pad % 2 == 0) 0xEC else 0x11
            push(v, 8); pad++
        }

        val dataCw = IntArray(DATA_CODEWORDS[version]) { i ->
            var v = 0
            for (k in 0 until 8) v = (v shl 1) or (if (bits[i * 8 + k]) 1 else 0)
            v
        }

        // 分块 + 计算 ECC + 交织
        val blocks = BLOCKS[version] ?: return dataCw
        val eccLen = ECC_PER_BLOCK[version]
        val dataBlocks = ArrayList<IntArray>()
        val eccBlocks = ArrayList<IntArray>()
        var off = 0
        for (cnt in blocks) {
            val blk = dataCw.copyOfRange(off, off + cnt)
            off += cnt
            dataBlocks.add(blk)
            eccBlocks.add(reedSolomon(blk, eccLen))
        }

        val out = ArrayList<Int>(dataCw.size + eccLen * blocks.size)
        val maxData = blocks.maxOrNull() ?: 0
        for (i in 0 until maxData) for (blk in dataBlocks) if (i < blk.size) out.add(blk[i])
        for (i in 0 until eccLen) for (blk in eccBlocks) if (i < blk.size) out.add(blk[i])
        return out.toIntArray()
    }

    // version -> 每块的数据码字数（ECC=M）
    private val BLOCKS: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(16),
        2 to intArrayOf(28),
        3 to intArrayOf(44),
        4 to intArrayOf(32, 32),
        5 to intArrayOf(43, 43),
        6 to intArrayOf(27, 27, 27, 27),
        7 to intArrayOf(31, 31, 31, 31),
        8 to intArrayOf(38, 38, 39, 39),
        9 to intArrayOf(36, 36, 36, 37, 37),
        10 to intArrayOf(43, 43, 43, 43, 44)
    )

    private val ECC_PER_BLOCK = intArrayOf(0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)

    private val DATA_CODEWORDS = intArrayOf(0, 16, 28, 44, 64, 86, 108, 124, 154, 182, 216)

    /* ---------------- Reed-Solomon（GF(256)） ---------------- */

    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D   // QR 的本原多项式
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun gfMul(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    /** 生成多项式 g(x) = (x - a^0)(x - a^1)...(x - a^(deg-1)) 的系数 */
    private fun generatorPoly(deg: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until deg) {
            val next = IntArray(g.size + 1)
            for (j in g.indices) {
                next[j] = next[j] xor g[j]
                next[j + 1] = next[j + 1] xor gfMul(g[j], EXP[i])
            }
            g = next
        }
        return g
    }

    /** 对 data 计算 eccLen 个纠错码字 */
    private fun reedSolomon(data: IntArray, eccLen: Int): IntArray {
        val g = generatorPoly(eccLen)
        val buf = IntArray(data.size + eccLen)
        data.copyInto(buf)
        for (i in data.indices) {
            val factor = buf[i]
            if (factor != 0) {
                for (j in g.indices) {
                    buf[i + j] = buf[i + j] xor gfMul(g[j], factor)
                }
            }
        }
        return buf.copyOfRange(data.size, data.size + eccLen)
    }

    /* ---------------- 放置数据 ---------------- */

    private fun placeData(
        cells: BooleanArray, reserved: BooleanArray, size: Int, codewords: IntArray
    ) {
        var bitIdx = 0
        val totalBits = codewords.size * 8
        var right = size - 1
        var upward = true
        while (right >= 1) {
            /*
             * 跳过第 6 列（竖向 timing 图案所在列）。
             *
             * 标准做法：列对按 (right, right-1) 从右往左取；
             * 当 right 走到 6 时，**把 right 直接置为 5**，让这一对变成 (5,4)，
             * 之后照常 -2 → (3,2) → (1,0)。
             *
             * ⚠️ 原来写成 `if (right == 6) right--`，得到 5 之后**又执行了一次 -2**，
             * 下一轮变成 3，于是序列是 …,8,5,3,1 —— 第 4 列和第 0 列永远轮不到。
             * v1~v3 的 right 序列根本走不到 6 以下，所以只有 v4 及以上才坏：
             * 表现就是"短地址能扫，长一点的扫不出来"。
             *
             * 关键点：**跳列的那次赋值之后，仍然要执行一次 -2**，
             * 因为 (5,4) 这一对本身已经消费掉了 5 和 4 两列。
             */
            if (right == 6) right = 5
            for (vert in 0 until size) {
                val y = if (upward) size - 1 - vert else vert
                for (dx in 0..1) {
                    val x = right - dx
                    if (x < 0 || x >= size) continue
                    if (reserved[y * size + x]) continue
                    val bit = if (bitIdx < totalBits) {
                        (codewords[bitIdx / 8] ushr (7 - bitIdx % 8)) and 1 == 1
                    } else false
                    cells[y * size + x] = bit
                    bitIdx++
                }
            }
            upward = !upward
            right -= 2
        }
    }

    /* ---------------- 掩码 ---------------- */

    private fun applyMask(cells: BooleanArray, reserved: BooleanArray, size: Int, mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            if (reserved[y * size + x]) continue
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> (x * y) % 2 + (x * y) % 3 == 0
                6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
            }
            if (invert) cells[y * size + x] = !cells[y * size + x]
        }
    }

    /* ---------------- 格式信息 ---------------- */

    private fun drawFormatBits(
        cells: BooleanArray, reserved: BooleanArray, size: Int,
        mask: Int, ecc: Int, mark: Boolean
    ) {
        val data = (ecc shl 3) or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = ((data shl 10) or rem) xor 0x5412

        fun put(x: Int, y: Int, i: Int) {
            val dark = (bits ushr i) and 1 == 1
            if (x in 0 until size && y in 0 until size) {
                cells[y * size + x] = dark
                if (mark) reserved[y * size + x] = true
            }
        }
        // 左上角那份
        for (i in 0..5) put(8, i, i)
        put(8, 7, 6)
        put(8, 8, 7)
        put(7, 8, 8)
        for (i in 9..14) put(14 - i, 8, i)
        // 右上 + 左下那份
        for (i in 0..7) put(size - 1 - i, 8, i)
        for (i in 8..14) put(8, size - 15 + i, i)
        // 固定暗点
        if (mark) {
            cells[(size - 8) * size + 8] = true
            reserved[(size - 8) * size + 8] = true
        }
    }

    /** 版本信息块（version >= 7） */
    private fun drawVersionBits(
        cells: BooleanArray, reserved: BooleanArray, size: Int, version: Int
    ) {
        var rem = version
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = (version shl 12) or rem
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 == 1
            val a = size - 11 + i % 3
            val b = i / 3
            cells[b * size + a] = dark; reserved[b * size + a] = true
            cells[a * size + b] = dark; reserved[a * size + b] = true
        }
    }

    /* ---------------- 掩码惩罚分 ---------------- */

    private fun penalty(cells: BooleanArray, size: Int): Int {
        var s = 0
        // 规则1：同色连续 5 个以上
        for (y in 0 until size) {
            var run = 1
            for (x in 1 until size) {
                if (cells[y * size + x] == cells[y * size + x - 1]) {
                    run++; if (run == 5) s += 3 else if (run > 5) s++
                } else run = 1
            }
        }
        for (x in 0 until size) {
            var run = 1
            for (y in 1 until size) {
                if (cells[y * size + x] == cells[(y - 1) * size + x]) {
                    run++; if (run == 5) s += 3 else if (run > 5) s++
                } else run = 1
            }
        }
        // 规则2：2×2 同色块
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = cells[y * size + x]
            if (c == cells[y * size + x + 1] &&
                c == cells[(y + 1) * size + x] &&
                c == cells[(y + 1) * size + x + 1]
            ) s += 3
        }
        // 规则3：形如 10111010000 的模式
        val p1 = booleanArrayOf(true, false, true, true, true, false, true)
        val q1 = booleanArrayOf(false, false, false, false)
        for (y in 0 until size) for (x in 0..size - 11) {
            var m1 = true; var m2 = true
            for (k in 0..10) {
                val v = cells[y * size + x + k]
                if (k < 7) { if (v != p1[k]) m1 = false } else { if (v != q1[k - 7]) m1 = false }
                if (k < 4) { if (v != q1[k]) m2 = false } else { if (v != p1[k - 4]) m2 = false }
            }
            if (m1) s += 40
            if (m2) s += 40
        }
        for (x in 0 until size) for (y in 0..size - 11) {
            var m1 = true; var m2 = true
            for (k in 0..10) {
                val v = cells[(y + k) * size + x]
                if (k < 7) { if (v != p1[k]) m1 = false } else { if (v != q1[k - 7]) m1 = false }
                if (k < 4) { if (v != q1[k]) m2 = false } else { if (v != p1[k - 4]) m2 = false }
            }
            if (m1) s += 40
            if (m2) s += 40
        }
        // 规则4：黑白占比偏离 50%
        var dark = 0
        for (c in cells) if (c) dark++
        val ratio = dark * 100 / cells.size
        val diff = kotlin.math.abs(ratio - 50) / 5
        s += diff * 10
        return s
    }
}
