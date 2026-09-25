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
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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

    /**
     * 队列空闲多久就往观众打一个 WS ping。
     * 实测隧道在 100~120 秒之间回收空闲连接，取 25s 是四倍余量 ——
     * 心跳本身只有 2 字节，25 秒一次对流量可以忽略（一小时不到 30KB）。
     */
    private const val KEEPALIVE_IDLE_MS = 25_000L
    private val EMPTY_FRAME = ByteArray(0)

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

    /** 待发送给观众的消息。写入由 [writeLoop] 那条专职线程做，调用方只入队。 */
    private val outbox = LinkedBlockingQueue<String>()

    /**
     * 入队 / 落地的两条计数，只为一件事：让 [sayGoodbye] 能判断"再见是不是真的写出去了"。
     * 用计数而不是 `outbox.isEmpty()`，因为 `poll` 一取走队列就空了，而那一次写还没发生。
     */
    private val offeredCount = AtomicLong()
    private val writtenCount = AtomicLong()

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var writeThread: Thread? = null
    /**
     * 观众页面。
     *
     * 初值为空是刻意的：不能引用类末尾才初始化的 [FALLBACK_PAGE]，否则 Kotlin
     * 会按声明顺序先跑到这里、拿到一个还没初始化的值。真正的内容在 [start] 里填。
     */
    private var pageHtml: String = ""
    /** hls.js 是二进制资源，启动时读一次进内存，别每个请求都去开 assets。 */
    private var hlsJs: ByteArray = ByteArray(0)

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
            hlsJs = runCatching {
                context.assets.open("viewer/hls.min.js").use { it.readBytes() }
            }.getOrDefault(ByteArray(0))
            accessKey = randomKey()
            val s = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            server = s
            port = s.localPort
            running = true
            acceptThread = thread(name = "t2-signal-accept", isDaemon = true) { acceptLoop(s) }
            writeThread = thread(name = "t2-signal-write", isDaemon = true) { writeLoop() }
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
        writeThread = null
        outbox.clear()
        // 队列被清空，那些消息永远不会"落地"了 —— 计数必须一起归零，
        // 否则下一次分享时 sayGoodbye() 会拿上一次的差额当"还没写完"白等一截。
        offeredCount.set(0)
        writtenCount.set(0)
        // 必须归零。`CallSession.isActive` 拿 `port != 0` 当"有没有在分享"的判据之一，
        // 而 port 一旦绑过就不会自己变回 0 —— 于是第一次分享之后 isActive 永久为真，
        // 之后每一次「开始分享」都被 startHost 的 guard 静默吞掉，
        // 用户看到的就是"点了『我知道了，继续』完全没反应"（真机实测踩过）。
        // 顺带：留着旧端口号也可能让下一次隧道指向一个已经关掉的本地端口。
        port = 0
        val v = synchronized(lock) { viewer?.also { viewer = null } }
        runCatching { v?.socket?.close() }
        _viewerConnected.value = false
    }

    /**
     * 拆通道**之前**跟观众说一声，并且等这句真的写出去。
     *
     * 为什么必须等：观众那边原先只能靠"媒体突然没了"反推房主走了，于是把一次正常结束
     * 报成「连不上房主的手机 / 纯直连在部分网络下会失败 / 没有中继兜底」，还附赠三条
     * 换网络建议 —— 用户照做就是白折腾。道个歉再说再见就能把这两种情况分开。
     *
     * 但"发了"不等于"到了"：这里的写是排队的（见 [sendToViewer]），而 [stop] 会
     * `outbox.clear()`、`TunnelManager.stop()` 会直接杀掉隧道进程 —— 任何一件先发生，
     * 再见就死在队列里。所以要等 [writtenCount] 追平，且必须**在拆 peer 与拆隧道之前**调。
     *
     * 有界等待：拿不到观众返回 false（本来就没必要说）；超时也照样往下拆，
     * 不能为了一个通知把停止按钮卡住。
     */
    fun sayGoodbye(graceMs: Long = 400L): Boolean {
        if (!sendToViewer("""{"t":"bye"}""")) return false
        val target = offeredCount.get()
        val deadline = System.nanoTime() + graceMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (writtenCount.get() >= target) {
                Log.i(TAG, "已告知观众：分享结束")
                return true
            }
            Thread.sleep(10)
        }
        Log.w(TAG, "再见没能在 ${graceMs}ms 内写完，直接拆通道")
        return false
    }

    /**
     * 把一条消息发给观众。**任何线程都可以调**。
     *
     * 为什么不直接写 socket：`Peer.onIceCandidate` 会把候选 post 到主线程再发，
     * 而 Android 禁止主线程网络 I/O —— 直接写就每条候选都抛
     * `NetworkOnMainThreadException`（message 是 null，日志上只看得见"发给观众失败：null"）。
     * 后果不是全局失败：同机测试靠 host 候选也能连通，所以看起来"有时好用"；
     * 一旦是手机蜂窝↔电脑这种必须用 srflx 的组合，观众就拿不到房主的公网地址，
     * ICE 永远停在 CHECKING，既不成功也不报失败（异地实测就是这个现象）。
     *
     * 所以写入交给一条专职线程按 FIFO 消费，调用方只入队。
     * 返回 false 只代表"当前没有观众可发"，不代表投递结果。
     */
    fun sendToViewer(json: String): Boolean {
        val hasViewer = synchronized(lock) { viewer != null }
        if (!hasViewer) return false
        if (!outbox.offer(json)) return false
        offeredCount.incrementAndGet()
        return true
    }

    /**
     * 专职写线程：唯一持有 socket 写权限的地方，永远不在调用者线程上发包。
     *
     * 它还负责**空闲心跳**：队列 25 秒没东西可发，就往观众那条 WS 上打一个 ping 帧。
     * 不加这个，一次正常的观看会在两分钟左右自己断掉 —— 实测：观众连上后双方什么都不做，
     * t=91s 还是 `ice=CONNECTED`，t=121s 两边同时 `ice=CLOSED`，
     * 房主侧日志是"观众已离开"、观众侧是"与房主的连接断了：对方可能已停"，
     * 两边都以为是对面走了。我们自己的代码里没有任何 socket 超时（查过），
     * 掐连接的是中间那层 Cloudflare 隧道对空闲 WS 的回收。
     * 媒体是 P2P 的，本来跟这条 WS 没关系，但信令一断我们就拆 peer —— 于是健康通话被杀。
     *
     * ping 而不是发个 JSON：浏览器端的 WebSocket 不允许 JS 主动发 ping，
     * 但会**自动回 pong**，所以一个方向的 ping 能让两条方向都有流量；
     * 我们自己的观众端 [WsClient] 也回 pong。且 ping 不占消息语义，老版本观众看不懂也不会坏。
     */
    private fun writeLoop() {
        var idleMs = 0L
        while (running) {
            val msg = try {
                outbox.poll(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                break
            }
            if (msg == null) {
                idleMs += 1_000
                if (idleMs >= KEEPALIVE_IDLE_MS) {
                    idleMs = 0
                    val c0 = synchronized(lock) { viewer }
                    if (c0 != null) {
                        runCatching {
                            synchronized(c0.writeLock) { writeFrame(c0.out, OP_PING, EMPTY_FRAME) }
                        }.onFailure { Log.w(TAG, "心跳没发出去：${it.javaClass.name}") }
                    }
                }
                continue
            }
            idleMs = 0
            val c = synchronized(lock) { viewer }
            if (c == null) {
                // 观众走了就丢弃，不阻塞队列。也算"处理完"——否则 sayGoodbye 会白等到超时。
                writtenCount.incrementAndGet()
                continue
            }
            try {
                synchronized(c.writeLock) { writeFrame(c.out, OP_TEXT, msg.toByteArray(Charsets.UTF_8)) }
            } catch (t: Throwable) {
                // 带上异常类型与位置：只打 t.message 时 NullPointerException /
                // NetworkOnMainThreadException 都显示成"null"，白查了一轮。
                val at = t.stackTrace.firstOrNull()?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
                Log.w(TAG, "发给观众失败：${t.javaClass.name}（${t.message}）于 $at")
            } finally {
                // 失败也算处理完：它在队列里不会再有第二次机会，等下去只是拖住停止按钮。
                writtenCount.incrementAndGet()
            }
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

                // 观众页要用的第三方库（hls.js，放 m3u8 用）。
                // 这条**不查访问口令**：它是公开库、不含任何会话信息，
                // 而浏览器发 `<script src="/hls.min.js">` 时也不会替我们带上 ?k=。
                path == "/hls.min.js" -> runCatching {
                    val bytes = hlsJs
                    require(bytes.isNotEmpty())
                    writeHttp(
                        output, 200, "OK", "application/javascript; charset=utf-8", bytes,
                    )
                }.getOrElse {
                    writeHttp(output, 500, "Server Error", "text/plain; charset=utf-8", "no lib".toByteArray())
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
            // 这是"socket 关了"，不是"观众按了退出"——观众那边根本不发 bye。
            // 之前把它伪造成 bye，于是隧道一掐线房主就 teardownPeer，
            // 把一条媒体还活着的 P2P 通话一起带走了。现在如实报"gone"，
            // 由 CallSession 按 ICE 健康度决定是保留等重连、还是真收场。
            _incoming.tryEmit("""{"t":"gone"}""")
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
