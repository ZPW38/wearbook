package com.webook.watch.data

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 手机传书：在手表上起一个极小的 HTTP 服务，手机浏览器打开就能把文件推上来。
 *
 * 为什么需要它：
 *   - Wear OS 上**没有文件管理器**、SAF 是空壳、也没有「所有文件访问」可开，
 *     系统途径一条都走不通；
 *   - 数据线 + 专属文件夹能用，但要让用户专门找根线、插电脑、翻目录，太重 ——
 *     那个只该当兜底，不该当主入口；
 *   - 手表上打字填直链同样难受（表盘小、输入法难用）。
 *   而"手机和手表在同一个 WiFi"是手表用户本来就成立的状况，所以让手表当服务端、
 *   手机当客户端是最省事的一条：手机键盘随便打、文件从手机相册/文件里直接选。
 *
 * 安全边界（刻意的）：
 *   - 只在**局域网**监听，端口随机（避免和别的服务撞车），页面打开时才提示用户记下地址；
 *   - 只接受**上传**，不提供任何文件下载/浏览接口 —— 手机上猜不到路径也拿不走手表里的任何东西；
 *   - 单次会话：用户点「停止」或退出页面即关闭；不常驻、不在后台偷偷跑；
 *   - 不做任何鉴权（同 WiFi 内本就可信），但因此**绝不暴露已存储的内容**。
 */
object WifiTransfer {

    /** 单文件上限，和链接导入保持一致 */
    const val MAX_BYTES = 512L * 1024 * 1024

    /** 端口从这个区间里随机挑，避开常见的 8080/8000 等 */
    private const val PORT_MIN = 18800
    private const val PORT_MAX = 18899

    /** 上传落地的回调：文件名 + 临时文件（调用方负责搬进书库/目录） */
    fun interface OnUpload {
        fun onFile(name: String, file: File)
    }

    /** 运行中的服务句柄 */
    class Server internal constructor(
        private val socket: ServerSocket,
        val port: Int
    ) {
        private val running = AtomicBoolean(true)
        internal var worker: Thread? = null

        val isRunning: Boolean get() = running.get()

        fun stop() {
            running.set(false)
            runCatching { socket.close() }
            worker?.interrupt()
        }
    }

    /**
     * 启动服务。失败（端口被占/没网络）返回 null，由界面提示用户。
     *
     * @param landingDir 上传文件先落到这里，再由 [OnUpload] 处理
     */
    fun start(landingDir: File, upload: OnUpload): Server? {
        if (!landingDir.exists()) landingDir.mkdirs()
        val socket = bindAnyPort() ?: return null
        val server = Server(socket, socket.localPort)
        val t = thread(name = "wearbook-wifi-transfer", isDaemon = true) {
            while (server.isRunning) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    break   // stop() 关掉了 socket，正常退出
                }
                // 每个请求单独一个线程：浏览器会并发拉 favicon，串行处理会卡住
                runCatching {
                    thread(isDaemon = true) { handle(client, landingDir, upload) }
                }
            }
        }
        server.worker = t
        return server
    }

    private fun bindAnyPort(): ServerSocket? {
        for (p in (PORT_MIN..PORT_MAX).shuffled()) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(p))
                }
            } catch (_: IOException) {
                // 这个端口被占了，试下一个
            }
        }
        return null
    }

    /* ---------------- HTTP 处理 ---------------- */

    private fun handle(sock: Socket, landingDir: File, upload: OnUpload) {
        try {
            sock.soTimeout = 30_000
            val input = BufferedInputStream(sock.getInputStream())
            val header = readHeader(input) ?: return
            val requestLine = header.lineSequence().firstOrNull() ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase(Locale.ROOT)
            val path = parts[1]

            when {
                method == "GET" && (path == "/" || path.startsWith("/index")) ->
                    respondHtml(sock, PAGE)

                method == "POST" && path.startsWith("/upload") -> {
                    val boundary = boundaryOf(header)
                    if (boundary == null) {
                        respondText(sock, "400 Bad Request", "缺少 multipart 边界")
                        return
                    }
                    val saved = readMultipart(input, boundary, landingDir)
                    if (saved.isEmpty()) respondText(sock, "400 Bad Request", "没有收到文件")
                    else {
                        saved.forEach { (n, f) -> upload.onFile(n, f) }
                        respondHtml(sock, donePage(saved.map { it.first }))
                    }
                }

                else -> respondText(sock, "404 Not Found", "只支持上传")
            }
        } catch (_: Throwable) {
            // 单个请求失败不影响服务继续跑（浏览器取消、超时都很常见）
        } finally {
            runCatching { sock.close() }
        }
    }

    /** 逐行读到空行为止（请求头结束），带上 Content-Length 供后续读 body */
    private fun readHeader(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            last4 = (last4 shl 8) or (b and 0xff)
            if (last4 == 0x0D0A0D0A) return sb.toString()   // \r\n\r\n
            if (sb.length > 32 * 1024) return null          // 防御：异常大的头
        }
    }

    private fun boundaryOf(header: String): String? =
        header.lineSequence()
            .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
            ?.substringAfter("boundary=", "")
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotEmpty() }

    /**
     * 解析 multipart/form-data，把每个文件段落写到 [dir]，返回 (原始文件名, 文件)。
     *
     * 手写极简解析器（不引第三方库，手表 APK 每 KB 都要省），只处理浏览器
     * `<input type=file>` 产生的标准格式。
     *
     * ⚠️ 关键点：判断"正文结束"要靠 **CRLF + delimiter**，而正文本身可能包含任意字节，
     * 所以不能用一次性 readLine —— 必须逐字节匹配、失配时把已吞掉的字节吐回去。
     * 早先版本用 pending 缓冲却没往里写，导致正文被吃掉、连接直接断（表现为上传无响应）。
     */
    private fun readMultipart(
        input: InputStream,
        boundary: String,
        dir: File
    ): List<Pair<String, File>> {
        val out = mutableListOf<Pair<String, File>>()
        val firstDelim = "--$boundary".toByteArray()
        if (!skipUntil(input, firstDelim)) return out

        while (true) {
            // delimiter 之后可能是 "--"（结束）或 CRLF（还有下一段）
            val after = readLineBytes(input) ?: return out
            val marker = String(after, Charsets.ISO_8859_1)
            if (marker.startsWith("--")) return out   // 结束标记

            val partHeader = readHeader(input) ?: return out
            val fileName = Regex("""filename="([^"]*)"""")
                .find(partHeader)?.groupValues?.get(1)
                ?.takeIf { it.isNotBlank() }

            if (fileName == null) {
                // 非文件字段（比如别的 input），把内容跳过去即可
                if (!skipUntil(input, firstDelim)) return out
                continue
            }

            val safe = sanitize(fileName)
            val target = File(dir, safe)
            val total = copyUntilBoundary(input, target, boundary)
            if (total > 0) out.add(safe to target) else target.delete()
            if (!skipUntilNext(input, boundary)) return out
        }
    }

    /**
     * 把正文拷到 [target]，直到遇到 `CRLF--boundary`，并**消费掉**这个分隔符。
     * @return 写入的字节数
     */
    private fun copyUntilBoundary(input: InputStream, target: File, boundary: String): Long {
        val needle = "\r\n--$boundary".toByteArray()
        var matched = 0
        var total = 0L
        // 已经被匹配上、但还不能确定是不是分隔符的字节要先攒着（失配时吐回去）
        val hold = ByteArrayOutputStream()
        target.outputStream().use { fos ->
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == (needle[matched].toInt() and 0xff)) {
                    hold.write(b)
                    matched++
                    if (matched == needle.size) {
                        // 完整命中分隔符：hold 里这些字节属于分隔符，不写进正文
                        hold.reset()
                        break
                    }
                } else {
                    // 失配：把 hold 的内容当作正文写掉，再重新对齐
                    if (hold.size() > 0) {
                        val arr = hold.toByteArray()
                        fos.write(arr); total += arr.size
                        hold.reset()
                    }
                    matched = 0
                    if (b == (needle[0].toInt() and 0xff)) {
                        hold.write(b); matched = 1
                    } else {
                        fos.write(b); total++
                    }
                }
                if (total > MAX_BYTES) break
            }
            if (hold.size() > 0) {
                val arr = hold.toByteArray(); fos.write(arr); total += arr.size
            }
        }
        return total
    }

    /** 分隔符已被 copyUntilBoundary 吃掉，这里只处理它后面剩下的 "--"/CRLF */
    private fun skipUntilNext(input: InputStream, boundary: String): Boolean {
        val after = readLineBytes(input) ?: return false
        return !String(after, Charsets.ISO_8859_1).startsWith("--")
    }

    /** 一直读到 needle 出现为止 */
    private fun skipUntil(input: InputStream, needle: ByteArray): Boolean {
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return false
            if (b == needle[matched].toInt() and 0xff) {
                matched++
                if (matched == needle.size) return true
            } else {
                matched = if (b == needle[0].toInt() and 0xff) 1 else 0
            }
        }
    }

    /** 读一行（到 \n），返回不含换行的字节 */
    private fun readLineBytes(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toByteArray()
            if (b == '\n'.code) return buf.toByteArray()
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 4096) return buf.toByteArray()
        }
    }

    /**
     * 文件名消毒：去掉路径分隔符与控制字符，避免写到目标目录之外。
     *
     * ⚠️ 别写成 `it !in "\u0000..\u001f"` —— Kotlin 的 String 不是范围，
     * 那样等于把 `.` 也列进了黑名单（字符串里恰好有这个字符），
     * 结果 `book.txt` 会变成 `booktxt`，扩展名丢失、格式判断直接失效。
     * 控制字符必须用 `it.code < 0x20` 这样判。
     */
    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = base
            .filter { it.code >= 0x20 && it != '\u007f' && it !in "/\\:*?\"<>|" }
            .trim()
            .trimStart('.')
        return cleaned.ifEmpty { "upload-${System.currentTimeMillis()}" }
    }

    /* ---------------- 响应 ---------------- */

    private fun respondText(sock: Socket, status: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        sock.getOutputStream().use { os: OutputStream ->
            os.write(head.toByteArray()); os.write(bytes); os.flush()
        }
    }

    private fun respondHtml(sock: Socket, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        sock.getOutputStream().use { os: OutputStream ->
            os.write(head.toByteArray()); os.write(bytes); os.flush()
        }
    }

    /**
     * 取本机的局域网地址，用来提示用户"手机上打开这个"。
     * 连不上任何网络时返回 null。
     */
    fun localAddresses(): List<String> {
        return runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    /* ---------------- 页面 ---------------- */

    private val PAGE = """
<!DOCTYPE html>
<html lang="zh"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>腕上书 · 手机传书</title>
<style>
  :root{color-scheme:dark}
  body{margin:0;font-family:-apple-system,system-ui,"PingFang SC","Microsoft YaHei",sans-serif;
       background:#100E16;color:#F8F5FD;display:flex;min-height:100vh;
       align-items:center;justify-content:center;padding:24px;box-sizing:border-box}
  .card{width:100%;max-width:460px}
  h1{font-size:20px;margin:0 0 6px}
  p{color:#A99FBE;font-size:14px;line-height:1.6;margin:0 0 20px}
  .drop{border:2px dashed #4A3D6B;border-radius:16px;padding:36px 20px;text-align:center;
        background:#17131F;transition:.2s;cursor:pointer}
  .drop.hot{border-color:#B49BF0;background:#1E1930}
  .drop b{display:block;font-size:15px;margin-bottom:6px;color:#B49BF0}
  .drop span{font-size:13px;color:#8B80A3}
  input[type=file]{display:none}
  button{width:100%;margin-top:16px;padding:14px;border:0;border-radius:12px;
         background:#B49BF0;color:#1A1526;font-size:15px;font-weight:600;cursor:pointer}
  button:disabled{opacity:.45;cursor:default}
  .list{margin-top:18px;font-size:13px;color:#A99FBE;line-height:1.9}
  .ok{color:#7BE0A8}
  .err{color:#FF8A8A}
</style></head><body>
<div class="card">
  <h1>手机传书</h1>
  <p>选一个或多个 TXT / EPUB / MOBI / AZW / HTML 文件传过去，手表会自动收进书架。</p>
  <div class="drop" id="drop">
    <b>点这里选文件</b>
    <span>或把文件拖进来</span>
    <input type="file" id="file" multiple
           accept=".txt,.epub,.mobi,.azw,.azw3,.html,.htm">
  </div>
  <button id="go" disabled>上传到手表</button>
  <div class="list" id="list"></div>
</div>
<script>
const drop=document.getElementById('drop'),fileEl=document.getElementById('file'),
      go=document.getElementById('go'),list=document.getElementById('list');
let picked=[];
const BOOK=/\.(txt|epub|mobi|azw|azw3|html?)$/i;
function show(files){
  picked=[...files].filter(f=>BOOK.test(f.name));
  list.innerHTML=picked.map(f=>'· '+f.name+' ('+(f.size/1048576).toFixed(1)+' MB)').join('<br>');
  if(files.length&&!picked.length) list.innerHTML='<span class="err">只支持 TXT / EPUB / MOBI / AZW / HTML</span>';
  go.disabled=!picked.length;
}
drop.onclick=()=>fileEl.click();
fileEl.onchange=e=>show(e.target.files);
drop.ondragover=e=>{e.preventDefault();drop.classList.add('hot')};
drop.ondragleave=()=>drop.classList.remove('hot');
drop.ondrop=e=>{e.preventDefault();drop.classList.remove('hot');show(e.dataTransfer.files)};
go.onclick=async()=>{
  go.disabled=true;go.textContent='上传中…';
  let ok=0,fail=0;
  for(const f of picked){
    const fd=new FormData();fd.append('f',f,f.name);
    try{ const r=await fetch('/upload',{method:'POST',body:fd});
         if(r.ok) ok++; else fail++; }catch(e){ fail++; }
  }
  go.textContent='再来一本';
  go.disabled=false;
  list.innerHTML+='<br><span class="'+(fail?'err':'ok')+'">完成：'+ok+' 本成功'+
    (fail?('，'+fail+' 本失败'):'')+'</span>';
};
</script></body></html>
"""

    private fun donePage(names: List<String>) = """
<!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>传好了</title>
<style>body{margin:0;font-family:system-ui,sans-serif;background:#100E16;color:#F8F5FD;
display:flex;align-items:center;justify-content:center;min-height:100vh;text-align:center;padding:24px}
.ok{font-size:44px;margin-bottom:12px}p{color:#A99FBE;font-size:14px;line-height:1.8}
a{color:#B49BF0}</style></head><body><div>
<div class="ok">✓</div>
<p>已收到 ${names.size} 个文件<br>${names.joinToString("<br>")}</p>
<p><a href="/">再传一本</a></p>
</div></body></html>
"""
}
