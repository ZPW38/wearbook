package com.webook.watch

import com.webook.watch.data.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * QR 编码器的结构性验证。
 *
 * 逐位正确性靠外部解码器验证（见 tools/verify-qr.py：把这里的输出喂给 zxing 解回来比对），
 * 这里只锁住容易写错的**结构不变量**：尺寸、定位图形、静区外缘、以及"同样输入必定同样输出"。
 */
class QrCodeTest {

    @Test
    fun `网址能编码且尺寸符合版本表`() {
        val m = QrCode.encode("http://192.168.1.14:18890")
        assertNotNull("25 字符的地址必须能编码", m)
        // v1=21, v2=25, v3=29 ... 边长 = 17 + 4*version
        assertEquals("25 字符应落在 v2", 25, m!!.size)
    }

    @Test
    fun `定位图形的三个角都是实心方块`() {
        val m = QrCode.encode("http://a.b")!!
        val s = m.size
        // 三个角各有一个 7×7 的定位图形：外框黑、内部 3×3 黑
        for ((ox, oy) in listOf(0 to 0, s - 7 to 0, 0 to s - 7)) {
            assertTrue("($ox,$oy) 处定位图形左上角应为黑", m.dark(ox, oy))
            assertTrue("($ox,$oy) 处定位图形中心应为黑", m.dark(ox + 3, oy + 3))
            assertTrue("($ox,$oy) 处定位图形外框右下应为黑", m.dark(ox + 6, oy + 6))
            // 外框与中心之间的白色环（第 1 行第 1 列起算的 5×5 边框内）
            assertTrue("($ox,$oy) 处定位图形应有白色分隔环", !m.dark(ox + 1, oy + 1))
        }
    }

    @Test
    fun `第四角不是定位图形`() {
        // 右下角没有定位图形，正好用于区分"画反了"这类错误
        val m = QrCode.encode("http://a.b")!!
        val s = m.size
        var darkCount = 0
        for (y in s - 7 until s) for (x in s - 7 until s) if (m.dark(x, y)) darkCount++
        // 定位图形在 7×7 里是 33 个黑块；右下角是数据区，几乎不可能刚好 33
        assertTrue("右下角黑块数 $darkCount 不该是定位图形的 33", darkCount != 33)
    }

    @Test
    fun `同样输入产生完全相同的矩阵`() {
        val a = QrCode.encode("http://192.168.1.50:18888")!!
        val b = QrCode.encode("http://192.168.1.50:18888")!!
        assertEquals(a.size, b.size)
        for (y in 0 until a.size) for (x in 0 until a.size) {
            assertEquals("($x,$y) 不一致", a.dark(x, y), b.dark(x, y))
        }
    }

    @Test
    fun `不同输入产生不同矩阵`() {
        val a = QrCode.encode("http://192.168.1.1:18800")!!
        val b = QrCode.encode("http://192.168.1.2:18800")!!
        var diff = 0
        for (y in 0 until a.size) for (x in 0 until a.size) if (a.dark(x, y) != b.dark(x, y)) diff++
        assertTrue("两个不同地址不该画出一模一样的码", diff > 10)
    }

    @Test
    fun `超出容量的输入返回 null 而不是崩溃`() {
        val huge = "http://" + "a".repeat(500)
        assertEquals(null, QrCode.encode(huge))
    }

    /**
     * 把若干真实地址的矩阵导出，供 tools/verify-qr.py 用 zxing 解码核对。
     * 这是"真的能扫"的唯一硬证据 —— 光看形状对是不够的。
     */
    @Test
    fun `导出矩阵供外部解码器校验`() {
        val cases = listOf(
            "http://192.168.1.14:18890",
            "http://10.0.2.15:18846",
            "http://192.168.100.200:18899",
            "http://192.168.1.6:18800"
        )
        val out = StringBuilder()
        for (url in cases) {
            val m = QrCode.encode(url)!!
            out.append("URL\t").append(url).append("\tSIZE\t").append(m.size).append("\n")
            for (y in 0 until m.size) {
                for (x in 0 until m.size) out.append(if (m.dark(x, y)) '1' else '0')
                out.append('\n')
            }
        }
        val f = File("build/qr-dump.txt")
        f.parentFile?.mkdirs()
        f.writeText(out.toString())
        assertTrue(f.length() > 100)
    }
    /**
     * 全长度扫描（v1~v3）：1~42 字节每个长度都导出一个矩阵，
     * 交给 tools/verify-qr.py 用 zxing 解码核对。
     *
     * 只测 4 个固定地址覆盖面太窄 —— QR 的版本选择、块结构、纠错码字数都随长度变化，
     * 某一档写错只有那个长度会坏。这次就是靠全长度扫描发现 v4+ 编码有缺陷的
     * （见 QrCode.MAX_SAFE_VERSION 的说明）。
     */
    @Test
    fun `导出全长度矩阵供外部解码器校验`() {
        val out = StringBuilder()
        var count = 0
        // 42 字节是 v3 的容量上限。注意 url = "h" + body 会多 1 字节，
        // 所以 body 最多 41 —— 循环写成 1..41 才是真的覆盖到 42 字节。
        for (len in 1..41) {
            val body = (0 until len).map { ('a' + it % 26) }.joinToString("")
            val url = "h" + body
            val m = QrCode.encode(url) ?: continue
            out.append("URL\t").append(url).append("\tSIZE\t").append(m.size).append("\n")
            for (y in 0 until m.size) {
                for (x in 0 until m.size) out.append(if (m.dark(x, y)) '1' else '0')
                out.append('\n')
            }
            count++
        }
        val f = File("build/qr-dump-full.txt")
        f.parentFile?.mkdirs()
        f.writeText(out.toString())
        assertEquals("1~42 字节应该全部能编码（body 1~41 + 前缀 h）", 41, count)
    }

    /**
     * 超过 v3 容量的内容必须**明确返回 null**，而不是生成一个扫不出来的码。
     * 调用方（QrTransferScreen）拿到 null 会退化成显示文字地址。
     */
    @Test
    fun `超出安全版本的内容返回 null`() {
        val tooLong = "http://" + "a".repeat(60)     // 67 字节，超过 v3 的 42
        assertEquals(null, QrCode.encode(tooLong))
        // 42 字节刚好还能编（v3 上限）
        assertNotNull(QrCode.encode("a".repeat(42)))
        // 43 字节就该拒绝
        assertEquals(null, QrCode.encode("a".repeat(43)))
    }
}
