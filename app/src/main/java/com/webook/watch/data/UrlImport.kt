package com.webook.watch.data

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * 链接导入：**通用的 http(s) 直链下载器**。
 *
 * 定位必须说清楚：这里只做一件事 —— 把一个 http/https 链接的字节流**原样**存成文件。
 * 不解析任何站点结构、不注入 Cookie、不做签名或反爬处理、不维护任何"接口地址表"。
 * 它和浏览器地址栏里敲一个直链下载是同一件事：
 *
 *   链接在无痕窗口里不登录、不装插件就能直接存成文件 → 这里就能下；
 *   需要登录、需要验证码、需要绕过访问控制才能拿到 → 这里同样下不了，也不会有别的办法。
 *
 * 这条边界是刻意的：有了它，这个功能就只是个中立的网络文件获取工具；
 * 越过它，代码就会滑向"针对某个平台的抓取"，那不是本项目该有的东西。
 */
object UrlImport {

    /** 单本上限 512MB：再大就超出"下一本书"的范畴，手表存储和流量也吃不消 */
    const val MAX_BYTES = 512L * 1024 * 1024

    private const val MAX_REDIRECT = 5
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    /** 兜底 UA。正常情况下由调用方从 AppVersion.userAgent() 取真实版本号传进来 */
    private const val UA_FALLBACK = "WearBook (Android)"

    /** 下载过程中的状态，供界面显示进度 */
    data class Job(
        val url: String,
        val read: Long = 0L,
        val total: Long = 0L,      // 0 = 服务器没给 Content-Length，长度未知
        val error: String? = null
    ) {
        /** 0f~1f；长度未知时返回 -1，界面改成显示"已下载多少" */
        val fraction: Float
            get() = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else -1f
    }

    // ---------- 纯逻辑部分（可 JVM 单测） ----------

    /**
     * 校正用户输入的链接：只认 http / https，漏写协议时补 https://。
     * @return 规范化后的链接；不是可用网址时返回 null
     */
    fun normalize(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        // 明显不是网址的（比如误粘了一段文字）直接拒掉，省得白等一次超时
        if (t.any { it.isWhitespace() }) return null
        // 写了协议就按它校验；没写才补 https://。
        // ⚠️ 千万别对 "ftp://x" 也补 https:// —— 那会得到 "https://ftp://x" 这种
        // 看似合法、实则毫无意义的地址，界面上还显示"下载失败"，白白让人摸不着头脑。
        val withScheme = if (t.contains("://")) t else "https://$t"
        val u = runCatching { URL(withScheme) }.getOrNull() ?: return null
        if (u.protocol.lowercase() !in setOf("http", "https")) return null
        if (u.host.isBlank()) return null
        // 协议小写化：用户可能敲成 "HTTP://"，统一成规范形式，后面拼文件名/日志都好读
        val scheme = withScheme.substringBefore("://").lowercase()
        return scheme + withScheme.substring(withScheme.indexOf("://"))
    }

    /** 从链接路径里猜文件名；猜不到（路径以 / 结尾、或被服务端重写成首页）时给个占位名 */
    fun guessName(url: String): String {
        val path = runCatching { URL(url).path }.getOrDefault("")
        val raw = path.substringAfterLast('/')
        if (raw.isBlank()) return ""
        val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        // 文件名里带斜杠/控制字符会写盘失败，直接弃用
        if (decoded.any { it == '/' || it == '\\' || it.code < 0x20 }) return ""
        return decoded.take(120)
    }

    /**
     * 由文件头判断格式，返回带点的扩展名；认不出来返回空串（调用方按 txt 处理）。
     *
     * 为什么需要：很多下载链接是 `/download?id=123` 这种，没有扩展名，
     * 而本项目的解析器是按扩展名分派的 —— 认错了就会拿错解析器去读。
     */
    fun sniff(head: ByteArray): String {
        if (head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()) return ".epub" // PK\x03\x04
        // PalmDOC: 第 60 字节起是 "BOOKMOBI"
        if (head.size >= 68) {
            val tag = String(head, 60, 8, Charsets.US_ASCII)
            if (tag == "BOOKMOBI") return ".mobi"
        }
        val n = minOf(1024, head.size)
        val text = String(head, 0, n, Charsets.ISO_8859_1).trimStart()
        val lower = text.lowercase()
        if (lower.startsWith("<!doctype html") || lower.startsWith("<html") ||
            lower.startsWith("<?xml") && lower.contains("<html")
        ) return ".html"
        return ""
    }

    /** 文件名是否已经带着我们支持的扩展名 */
    fun hasBookExt(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_NAMES

    private val SUPPORTED_NAMES = setOf("txt", "epub", "mobi", "azw", "html", "htm")

    // ---------- 实际下载（阻塞，只在 IO 线程调用） ----------

    /**
     * 把链接下载到 [dir]，返回最终文件。
     *
     * 整个过程**流式写盘**：8KB 一块地写，绝不把整本书读进内存 —— 手表只有 96MB 堆，
     * 一本几百 MB 的书进来就 OOM 了。
     *
     * 本身是阻塞的，**必须在 IO 线程调用**；进度回调是 suspend 的，方便回调里直接改界面状态。
     */
    suspend fun download(
        url: String,
        dir: File,
        userAgent: String = UA_FALLBACK,
        onProgress: suspend (read: Long, total: Long) -> Unit
    ): File {
        if (!dir.exists()) dir.mkdirs()
        val part = File(dir, "_dl.part")
        part.delete()

        var current = url
        var conn: HttpURLConnection? = null
        var hops = 0
        while (true) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false      // 自己跟，才能拦住"跳到非 http 协议"
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "*/*")
            c.connect()
            conn = c
            val code = c.responseCode
            if (code !in 300..399) break
            val loc = c.getHeaderField("Location")
            c.disconnect()
            if (loc.isNullOrBlank() || ++hops > MAX_REDIRECT) throw IOException("重定向太多次，链接可能有问题")
            current = URL(URL(current), loc).toString()
            // 重定向后必须重新校验协议：这是防止"从 http 悄悄降到别的协议"的关键一道
            val scheme = runCatching { URL(current).protocol.lowercase() }.getOrDefault("")
            if (scheme !in setOf("http", "https")) throw IOException("链接跳转到了不支持的协议：$scheme")
        }

        val live = conn ?: throw IOException("连不上")
        try {
            if (live.responseCode !in 200..299) {
                throw IOException("服务器返回 ${live.responseCode}，多半是链接过期了或需要登录")
            }
            val total = runCatching { live.contentLengthLong }.getOrDefault(0L)
            if (total > MAX_BYTES) throw IOException("文件 ${total / 1048576} MB，超过上限 ${MAX_BYTES / 1048576} MB")

            var read = 0L
            var lastReport = 0L
            val head = ByteArray(1024)
            var headLen = 0
            live.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (read + n > MAX_BYTES) {
                            throw IOException("下载超过上限 ${MAX_BYTES / 1048576} MB，已中止")
                        }
                        out.write(buf, 0, n)
                        read += n
                        if (headLen < head.size) {
                            val take = minOf(n, head.size - headLen)
                            System.arraycopy(buf, 0, head, headLen, take)
                            headLen += take
                        }
                        // 进度节流：每 128KB 报一次，太密会把界面拖垮
                        if (read - lastReport >= 128 * 1024) {
                            lastReport = read
                            onProgress(read, total)
                        }
                    }
                }
            }
            onProgress(read, if (total > 0) total else read)
            if (read == 0L) throw IOException("下载到的是空文件")

            // 定名：链接里带的扩展名优先，认不出来就用文件头嗅探补一个
            val fromUrl = guessName(url)
            val name = when {
                fromUrl.isNotEmpty() && hasBookExt(fromUrl) -> fromUrl
                else -> {
                    val ext = sniff(head.copyOf(headLen))
                    val stem = fromUrl.substringBeforeLast('.', fromUrl).ifBlank { "downloaded" }
                    stem.take(100) + (ext.ifBlank { ".txt" })
                }
            }

            val target = uniqueFile(dir, name)
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } finally {
            live.disconnect()
        }
    }

    /** 同名文件不覆盖，按 (1)(2) 递增 —— 免得把用户已有的书冲掉 */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists() && i < 100) {
            f = File(dir, "$stem($i)$ext")
            i++
        }
        return f
    }
}
