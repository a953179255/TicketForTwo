package com.ticketfortwo.app.signaling

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.concurrent.thread

/**
 * 手机端的「传话员」——一个跑在 127.0.0.1 上的极小 HTTP + WebSocket 服务。
 *
 * ## 为什么需要它
 *
 * WebRTC 握手天生是**双向**的：房主要拿到对方的 answer，观众要拿到房主的 offer。
 * 之前把 SDP 塞进链接，等于只有一张**单向纸条**，所以必须来回两趟、还靠人手动搬运。
 * 有了这个服务，两端的信令自己来回跑，用户只剩「发一次链接」这一个动作。
 *
 * ## 房主不走网络
 *
 * 房主和这个服务在**同一个进程**里，所以直接走内存（[sendToViewer] / [incoming]），
 * 不建本地 WebSocket 连接 —— 省掉一整条链路的失败模式。网络那一条只服务观众。
 *
 * ## 暴露到公网
 *
 * 这个服务只监听 127.0.0.1，本身外人访问不到。由 [com.ticketfortwo.app.tunnel.TunnelManager]
 * 用一条**出站**隧道（cloudflared）把它挂到一个临时公网地址上。出站这一点很关键：
 * 手机在运营商网络里拿不到入站可达的地址，只有主动连出去才可能被访问到。
 */
object SignalHub {

    private const val TAG = "SignalHub"
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_FRAME_BYTES = 1024 * 1024

    /** CR / LF —— 读 HTTP 头时用来找结束符。 */
    private const val CR = 13
    private const val LF = 10

    /** 观众页面。放在 assets 里，改文案不用动 Kotlin。 */
    private const val PAGE_ASSET = "viewer/index.html"

    // ---- 房主侧接口（同进程，不走 socket）--------------------------------

    /** 从观众来的消息。房主订阅它，喂给 [com.ticketfortwo.app.rtc.Peer]。 */
    private val _incoming = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    /** 观众是否已经连上。界面靠它决定「等待中」还是「已接入」。 */
    private val _viewerConnected = MutableStateFlow(false)
    val viewerConnected: StateFlow<Boolean> = _viewerConnected.asStateFlow()

    /** 服务实际监听的端口（bind 0 拿到，启动后才有效）。 */
    var port: Int = 0
        private set

    /** 邀请链接里的接入凭证。防止隧道地址被陌生人猜到就直接看画面。 */
    var accessKey: String = ""
        private set

    @Volatile
    private var running = false

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    /**
     * 观众页面。
     *
     * 初值为空是刻意的：不能引用类末尾才初始化的 [FALLBACK_PAGE]，否则 Kotlin
     * 会按声明顺序先跑到这里、拿到一个还没初始化的值。真正的内容在 [start] 里填。
     */
    private var pageHtml: String = ""

    private val lock = Any()
    private var viewer: Client? = null

    // ---- 生命周期 --------------------------------------------------------

    /**
     * 起服务。返回是否成功。
     *
     * 端口用 0 让系统分配 —— 写死端口会在多开、或被别的 App 占用时直接起不来，
     * 而这种失败对用户来说表现为「链接打不开」，极难排查。
     */
    fun start(context: Context): Boolean {
        if (running) return true
        return try {
            pageHtml = readPage(context)
            accessKey = randomKey()
            val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            running = true
            acceptThread = thread(name = "t2-signal-accept", isDaemon = true) { acceptLoop(s) }
            Log.i(TAG, "传话员已就位 127.0.0.1:$port")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "传话员启动失败：${t.message}")
            stop()
            false
        }
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread = null
        val v = synchronized(lock) { viewer?.also { viewer = null } }
        runCatching { v?.socket?.close() }
        _viewerConnected.value = false
    }

    /** 把一条消息发给观众。返回是否真的发出去了。 */
    fun sendToViewer(json: String): Boolean {
        val c = synchronized(lock) { viewer } ?: return false
        return try {
            synchronized(c.writeLock) { writeFrame(c.out, OP_TEXT, json.toByteArray(Charsets.UTF_8)) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "发给观众失败：${t.message}")
            false
        }
    }

    // ---- HTTP / WebSocket ------------------------------------------------

    private fun acceptLoop(s: ServerSocket) {
        while (running && !s.isClosed) {
            val socket = try {
                s.accept()
            } catch (t: Throwable) {
                if (running) Log.w(TAG, "accept 中断：${t.message}")
                break
            }
            thread(name = "t2-signal-conn", isDaemon = true) { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        var attached: Client? = null
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val header = readHttpHeader(input) ?: return
            val lines = header.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val target = requestLine.split(' ').getOrNull(1) ?: return
            val path = target.substringBefore('?')
            val query = parseQuery(target)

            when {
                path == "/ws" -> {
                    if (query["k"] != accessKey) {
                        Log.w(TAG, "凭证不对，拒绝 WebSocket")
                        writeHttp(output, 403, "Forbidden", "text/plain; charset=utf-8", "bad key".toByteArray())
                        return
                    }
                    val key = lines.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()
                    if (key.isNullOrEmpty() || !acceptUpgrade(output, key)) return
                    val client = Client(socket, output)
                    attached = client
                    attach(client)
                    readWebSocket(client, input)
                }

                path == "/" || path == "/index.html" -> {
                    if (query["k"] != accessKey) {
                        writeHttp(output, 403, "Forbidden", "text/plain; charset=utf-8", "bad key".toByteArray())
                        return
                    }
                    writeHttp(output, 200, "OK", "text/html; charset=utf-8", pageHtml.toByteArray(Charsets.UTF_8))
                }

                // 隧道健康检查：cloudflared 自己不会探，但我们可以用它做「门牌是否可用」的自检。
                path == "/health" -> writeHttp(
                    output, 200, "OK", "text/plain; charset=utf-8", "ok".toByteArray(),
                )

                else -> writeHttp(output, 404, "Not Found", "text/plain; charset=utf-8", "not found".toByteArray())
            }
        } catch (t: Throwable) {
            Log.d(TAG, "连接结束：${t.message}")
        } finally {
            attached?.let { detach(it) }
            runCatching { socket.close() }
        }
    }

    /**
     * 逐字节读到 `\r\n\r\n` 为止。HTTP 头一定是 ASCII，所以直接按字节比较。
     *
     * 用一个小状态机：[matched] = 已经连续匹配到的位置（0..4，到 4 就是结束了）。
     * 早先的版本用 `matched % 2` 判断奇偶，看着短但很容易读错边界 —— 换成显式的
     * 0/1/2/3 分支，虽然多两行，但一眼能看出"第 0、2 位要 CR，第 1、3 位要 LF"。
     */
    private fun readHttpHeader(input: InputStream): String? {
        val buf = ByteArrayOutputStream(1024)
        var matched = 0
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = when (matched) {
                0, 2 -> if (b == CR) matched + 1 else if (b == CR) 1 else 0
                1, 3 -> if (b == LF) matched + 1 else if (b == CR) 1 else 0
                else -> 0
            }
            if (matched == 4) break
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    private fun parseQuery(target: String): Map<String, String> {
        val q = target.substringAfter('?', "")
        if (q.isEmpty()) return emptyMap()
        return q.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
        }.toMap()
    }

    private fun writeHttp(out: OutputStream, code: Int, reason: String, contentType: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    /** 完成 WebSocket 握手。Sec-WebSocket-Accept = base64(sha1(key + GUID))。 */
    private fun acceptUpgrade(out: OutputStream, clientKey: String): Boolean {
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((clientKey + WS_GUID).toByteArray(Charsets.US_ASCII))
        )
        val resp = buildString {
            append("HTTP/1.1 101 Switching Protocols\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Accept: $accept\r\n")
            append("\r\n")
        }
        return try {
            out.write(resp.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "握手回写失败：${t.message}")
            false
        }
    }

    private fun readWebSocket(client: Client, input: InputStream) {
        var fragmentOpcode = -1
        var fragment: ByteArrayOutputStream? = null

        while (running && !client.socket.isClosed) {
            val frame = readFrame(input) ?: break
            when (frame.opcode) {
                OP_TEXT, OP_BINARY -> {
                    if (frame.fin) {
                        deliver(frame.payload)
                    } else {
                        fragmentOpcode = frame.opcode
                        fragment = ByteArrayOutputStream().also { it.write(frame.payload) }
                    }
                }

                OP_CONT -> {
                    val acc = fragment ?: continue
                    acc.write(frame.payload)
                    if (frame.fin) {
                        deliver(acc.toByteArray())
                        fragment = null
                        fragmentOpcode = -1
                    }
                }

                OP_CLOSE -> break

                OP_PING -> synchronized(client.writeLock) { writeFrame(client.out, OP_PONG, frame.payload) }

                OP_PONG -> Unit
            }
        }
    }

    private fun deliver(payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8).trim()
        if (text.isEmpty()) return
        Log.d(TAG, "观众 → 房主 ${text.length} 字符")
        _incoming.tryEmit(text)
    }

    private fun attach(c: Client) {
        val old = synchronized(lock) {
            viewer.also { viewer = c }
        }
        // 只保留一个观众：1v1 场景下多出来的一定是误连或旧连接残留。
        runCatching { old?.socket?.close() }
        _viewerConnected.value = true
        Log.i(TAG, "观众已接入")
    }

    private fun detach(c: Client) {
        val wasViewer = synchronized(lock) {
            if (viewer === c) {
                viewer = null
                true
            } else {
                false
            }
        }
        if (wasViewer) {
            _viewerConnected.value = false
            _incoming.tryEmit("""{"t":"bye"}""")
            Log.i(TAG, "观众已离开")
        }
    }

    // ---- WebSocket 帧编解码（RFC 6455 的最小子集）------------------------

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: InputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null

        val fin = (b0 and 0x80) != 0
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var len = (b1 and 0x7F).toLong()

        if (len == 126L) {
            len = ((input.read() shl 8) or input.read()).toLong()
        } else if (len == 127L) {
            len = 0
            repeat(8) { len = (len shl 8) or input.read().toLong() }
        }
        if (len < 0 || len > MAX_FRAME_BYTES) {
            Log.w(TAG, "帧过大或非法：$len")
            return null
        }

        val maskKey = if (masked) {
            ByteArray(4).also { readFully(input, it) }
        } else {
            null
        }

        val payload = ByteArray(len.toInt())
        if (payload.isNotEmpty()) readFully(input, payload)

        // 客户端发来的帧一律带掩码，服务端必须解开
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode) // FIN=1
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65_536 -> {
                out.write(126)
                out.write((payload.size shr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }

            else -> {
                out.write(127)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
        }
        // 服务端 → 客户端不加掩码（RFC 6455 §5.1）
        out.write(payload)
        out.flush()
    }

    /** Android 的 InputStream 不一定有 readNBytes，自己来。 */
    private fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw IllegalStateException("流提前结束（已读 $off / ${dst.size}）")
            off += n
        }
    }

    // ---- 杂项 ------------------------------------------------------------

    private fun readPage(context: Context): String = runCatching {
        context.assets.open(PAGE_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse {
        Log.w(TAG, "读不到 $PAGE_ASSET：${it.message}")
        FALLBACK_PAGE
    }

    private fun randomKey(): String {
        val bytes = ByteArray(9)
        SecureRandom().nextBytes(bytes)
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        // joinToString 的 lambda 要返回 CharSequence，Char 不算 —— 必须显式转成 String
        return bytes.joinToString("") { alphabet[((it.toInt() and 0xFF) % alphabet.length)].toString() }
    }

    private class Client(val socket: Socket, val out: OutputStream) {
        val writeLock = Any()
    }

    private const val OP_CONT = 0x0
    private const val OP_TEXT = 0x1
    private const val OP_BINARY = 0x2
    private const val OP_CLOSE = 0x8
    private const val OP_PING = 0x9
    private const val OP_PONG = 0xA

    private val FALLBACK_PAGE = """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>双人票 · 观看</title></head>
        <body style="margin:0;background:#000;color:#eee;font-family:system-ui;display:grid;place-items:center;height:100vh;text-align:center;padding:24px">
        <div><h1 style="font-size:20px">观众页面没加载出来</h1>
        <p style="color:#b3b3b3;font-size:14px">这是 App 内部的兜底页。请把 App 重新装一次，或让房主确认分享还在进行中。</p></div>
        </body></html>
    """.trimIndent()
}
