package com.webook.watch.parser

/** PalmDOC 解压（纯 Kotlin，不依赖 Android，可 JVM 单测） */
object PalmDoc {
    fun decompress(u: ByteArray): ByteArray {
        val out = ArrayList<Int>(u.size * 2)
        var i = 0
        while (i < u.size) {
            val b = u[i++].toInt() and 0xFF
            when {
                b == 0 -> out.add(0)
                b <= 8 -> { val n = b; repeat(n) { if (i < u.size) out.add(u[i++].toInt() and 0xFF) } }
                b <= 0x7F -> out.add(b)
                b <= 0xBF -> {
                    if (i >= u.size) break
                    val c = (b shl 8) or (u[i++].toInt() and 0xFF)
                    val dist = (c shr 3) and 0x7FF
                    val len = (c and 7) + 3
                    val from = out.size - dist
                    for (j in 0 until len) out.add(if (from + j in 0 until out.size) out[from + j] else 32)
                }
                else -> { out.add(32); out.add(b xor 0x80) }
            }
        }
        return ByteArray(out.size) { out[it].toByte() }
    }
}
