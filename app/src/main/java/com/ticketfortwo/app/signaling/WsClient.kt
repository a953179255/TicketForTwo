package com.ticketfortwo.app.signaling

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * 极小的 WebSocket **客户端** —— App 当观众时，用它连房主手机上的传话员（[SignalHub]）。
 *
 * 为什么自己写：项目里没有 OkHttp（一期零服务器不需要网络库），而这套协议用到的
 * 只是"握手 + 文本帧"两个子集，自己写比引一个网络库更省，也和 [SignalHub] 的服务端
 * 实现互为镜像、好对照排障。
 *
 * 客户端与服务端的唯一差别是 **发出的帧必须掩码**（RFC 6455 §5.3），收帧则不掩码。
 *
 * 线程模型：**读**在 [run] 那条线程；**写**在 [writerThread] 那条专职线程，[send] 从
 * 任意线程调用都只是入队。锁只保证不并发写坏字节，**不能**替代这一点 ——
 * 见 [send] 的注释。
 */
class WsClient(
    private val url: String,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (String?) -> Unit,
) {

    private var socket: Socket? = null

    /**
     * 只在握手完成之后由 [writerThread] 写；握手请求头与 PONG 由 [run] 那条读线程直接写。
     * 两条都不是主线程 —— 关键约束是"绝不从调用方线程写"，见 [send]。
     * [run] 完成握手后才赋值，所以用 volatile 让写线程看得见。
     */
    @Volatile
    private var out: OutputStream? = null
    private val writeLock = Any()

    /**
     * 待发队列。有界：连不上房主时不该让它无限长。
     * 满了说明这条通道早就该判失败，多攒没有意义。
     */
    private val outbox = LinkedBlockingQueue<String>(OUTBOX_CAPACITY)

    @Volatile
    private var closed = false

    /**
     * [close()] 等写线程把队列刷完的信号（含刚 send 进来的 bye），见 [close]。
     * 没有写线程时不会有人 countDown —— 所以 close 侧只在写线程存在时才等。
     */
    private val flushDone = java.util.concurrent.CountDownLatch(1)

    /**
     * 最后一次真正写 socket 的那条线程名。**不参与任何逻辑**，只为回归测试存在：
     * `WsClientTest` 断言它等于 [WRITER_THREAD_NAME]，从而证明 [send] 的调用方
     * 是哪条线程都不影响落地。谁要是把写操作改回"调用方直接写"，这条断言就会红 ——
     * 而在 Android 上调用方恰好是主线程时，代价是候选全丢、观众永远连不上。
     */
    @Volatile
    var lastWriteThread: String? = null
        private set

    private var thread: Thread? = null
    private var writerThread: Thread? = null

    fun connect() {
        if (thread != null) return
        thread = thread(name = "t2-ws-client", isDaemon = true) { run() }
        writerThread = thread(name = WRITER_THREAD_NAME, isDaemon = true) { writeLoop() }
    }

    /**
     * 发一条文本。**任何线程都可以调**，包括主线程。
     *
     * 为什么不能在这里直接写 socket —— 这是 App 内观看一直连不上的根因：
     * [com.ticketfortwo.app.rtc.Peer.onIceCandidate] 把候选 `handler.post` 回**主线程**
     * 再交给 listener，而 `ViewerSession` 的 listener 就是在这里写 socket。
     * Android 禁止主线程做网络 I/O，于是每条候选都抛 `NetworkOnMainThreadException`；
     * 它的 `message` 是 **null**，旧日志只剩一句"发送失败：null"，看着像无关紧要的抖动。
     *
     * 后果是单点致命：answer 走 signaling 线程所以能送到（2026-09-25 实测，见
     * Peer.logThread），房主因此会正常起 ICE —— 但它手里**一条对方的地址都没有**，
     * 主动探测发不出去，ICE 敲不通，观众界面要么卡在"正在连接房主的手机…"，
     * 要么过一会儿报"直连失败"。网页观众端一直好用，正是因为它绕开了这条 Java 路径。
     *
     * 入队即可，真正的写由 [writeLoop] 那条专职线程按 FIFO 做。
     */
    fun send(text: String) {
        if (closed) return
        if (!outbox.offer(text)) Log.w(TAG, "发送队列已满，丢弃 1 条（${text.length} 字符）")
    }

    fun close() {
        val first = !closed
        closed = true
        /* 道别（bye）是"先 send 再 close"进的队列：老实现第一行就 outbox.clear()，
           几乎必然把它吞掉 —— 房主收不到 bye，只能等 5–10 秒 ICE FAILED 才知道人走了，
           正常离开被画成红屏故障（REVIEW-2026-09-27 P1）。这里交给写线程把剩余
           消息刷完再关 socket（写必须留在专职线程：主线程写 socket 是
           NetworkOnMainThreadException，见 send 的注释），限时 200ms 兜底。 */
        if (first && writerThread != null && out != null) {
            runCatching { flushDone.await(200, TimeUnit.MILLISECONDS) }
        }
        runCatching { socket?.close() }
        socket = null
        out = null
    }

    // ---- 写出线程 --------------------------------------------------------

    /**
     * 握手完成前 [out] 还是 null —— 那时来的消息必须**留在队列里**，不能先取出来再扔。
     *
     * 所以顺序是"先看能不能写，再取"，而不是 `poll()` 之后判 null 然后 continue ——
     * 后者看着无害，实际会把这条消息直接从队列里摘走丢掉。（`WsClientTest` 里
     * 「握手前入队的消息不会被丢掉」那条测的就是这一步：第一版我就是这么写错的。）
     *
     * [outbox] 的 poll 超时取 50 ms：它是 hello 的最坏额外延迟（[run] 设好 [out] 之后
     * 下一条消息多久才被写出去）。再小是白耗唤醒，再大用户会觉得"打招呼很慢"。
     */
    private fun writeLoop() {
        try {
            while (!closed) {
                val dst = out
                if (dst == null) {
                    Thread.sleep(10)
                    continue
                }
                val msg = try {
                    outbox.poll(50, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                try {
                    synchronized(writeLock) { writeFrame(dst, OP_TEXT, msg.toByteArray(Charsets.UTF_8), mask = true) }
                    lastWriteThread = Thread.currentThread().name
                } catch (t: Throwable) {
                    // 带上异常类名和线程名：message 为 null 的异常（如 NetworkOnMainThreadException）
                    // 只打 message 等于什么都没打，这个坑已经踩过一次。
                    val at = Thread.currentThread().name
                    Log.w(TAG, "发送失败：${t.javaClass.name}（${t.message}）于 $at")
                }
            }
            // 收尾刷队：closed 置位后把队列剩下的（bye 就在里面）写完再退 —— close() 正在等信号
            drainOutbox()
        } finally {
            flushDone.countDown()
        }
    }

    /** 把 outbox 里剩余消息全部写出；出错或没有输出流就放弃（连接已死，队列留着也发不出去）。 */
    private fun drainOutbox() {
        if (out == null) return
        while (true) {
            val msg = outbox.poll() ?: return
            try {
                synchronized(writeLock) {
                    out?.let { writeFrame(it, OP_TEXT, msg.toByteArray(Charsets.UTF_8), mask = true) }
                }
                lastWriteThread = Thread.currentThread().name
            } catch (t: Throwable) {
                Log.w(TAG, "收尾刷队中断：${t.javaClass.name}（${t.message}）")
                return
            }
        }
    }

    // ---- 连接与握手 ------------------------------------------------------

    private fun run() {
        var reason: String? = null
        try {
            val target = parse(url)
            Log.i(TAG, "连接 ${target.logLabel}")

            val raw = Socket()
            raw.tcpNoDelay = true
            raw.connect(InetSocketAddress(target.host, target.port), 12_000)
            // 握手阶段（TLS + HTTP 头）给短超时：隧道黑洞时原来会在这里无限阻塞，
            // onClosed 永不触发 → 观众卡在"正在连接"没有任何兜底（REVIEW-2026-09-27 P2）
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS

            val s: Socket = if (target.secure) {
                // SSLSocketFactory.getDefault() 的返回类型是 SocketFactory，
                // 要用"在已有连接上包一层 TLS"的四参重载必须先转回来
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                val ssl = factory.createSocket(raw, target.host, target.port, true) as SSLSocket
                // SNI：Cloudflare 这类边缘按 SNI 选证书，不发会被拒
                ssl.sslParameters = ssl.sslParameters.apply {
                    serverNames = listOf(javax.net.ssl.SNIHostName(target.host))
                }
                ssl.startHandshake()
                ssl
            } else {
                raw
            }
            socket = s
            s.soTimeout = HANDSHAKE_TIMEOUT_MS   // TLS 层单独设一次（层叠 socket 不继承）

            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            if (!handshake(input, output, target)) {
                onClosed("握手失败")
                runCatching { s.close() }
                return
            }
            out = output
            // 握手完成后放宽：房主 writeLoop 每 25s 发心跳，PONG 即重置此计时 ——
            // 90 秒连一帧都收不到才判死，走 onClosed → 既有 rescue 兜底。
            s.soTimeout = WS_READ_TIMEOUT_MS
            onOpen()

            readLoop(input)
        } catch (t: Throwable) {
            reason = if (closed) null else (t.message ?: t.javaClass.simpleName)
            Log.i(TAG, "连接结束：${reason ?: "已关闭"}")
        } finally {
            closed = true
            runCatching { socket?.close() }
            out = null
            onClosed(reason)
        }
    }

    private fun handshake(input: InputStream, output: OutputStream, t: Target): Boolean {
        val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val req = buildString {
            append("GET ${t.path} HTTP/1.1\r\n")
            append("Host: ${t.host}\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: $key\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("\r\n")
        }
        output.write(req.toByteArray(Charsets.ISO_8859_1))
        output.flush()

        val head = readHttpHeader(input) ?: return false
        val statusLine = head.lineSequence().firstOrNull().orEmpty()
        if (!statusLine.contains("101")) {
            Log.w(TAG, "握手被拒：$statusLine")
            return false
        }
        return true
    }

    /** 逐字节读到 `\r\n\r\n`。与服务端同款做法（HTTP 头是 ASCII）。 */
    private fun readHttpHeader(input: InputStream): String? {
        val buf = ByteArrayOutputStream(1024)
        var matched = 0
        while (buf.size() < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = when (matched) {
                0, 2 -> if (b == CR) matched + 1 else 0
                1, 3 -> if (b == LF) matched + 1 else 0
                else -> 0
            }
            if (matched == 4) break
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    // ---- 帧读写 ----------------------------------------------------------

    private class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    private fun readLoop(input: InputStream) {
        var fragment: ByteArrayOutputStream? = null
        while (!closed) {
            val f = readFrame(input) ?: break
            when (f.opcode) {
                OP_TEXT, OP_BINARY -> {
                    if (f.fin) {
                        dispatch(f.payload)
                    } else {
                        fragment = ByteArrayOutputStream().also { it.write(f.payload) }
                    }
                }

                OP_CONT -> {
                    val acc = fragment ?: continue
                    acc.write(f.payload)
                    if (f.fin) {
                        dispatch(acc.toByteArray())
                        fragment = null
                    }
                }

                OP_CLOSE -> break

                OP_PING -> synchronized(writeLock) {
                    out?.let { writeFrame(it, OP_PONG, f.payload, mask = true) }
                }
            }
        }
    }

    private fun dispatch(payload: ByteArray) {
        val text = String(payload, Charsets.UTF_8).trim()
        if (text.isNotEmpty()) onText(text)
    }

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
        if (len < 0 || len > 1024 * 1024) return null

        val maskKey = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(len.toInt())
        if (payload.isNotEmpty()) readFully(input, payload)
        // 服务端正常不掩码，但按规范解一下也不亏
        if (maskKey != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
            }
        }
        return Frame(fin, opcode, payload)
    }

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray, mask: Boolean) {
        out.write(0x80 or opcode)
        val maskBit = if (mask) 0x80 else 0
        when {
            payload.size < 126 -> out.write(maskBit or payload.size)
            payload.size < 65_536 -> {
                out.write(maskBit or 126)
                out.write((payload.size shr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }

            else -> {
                out.write(maskBit or 127)
                for (shift in 56 downTo 0 step 8) {
                    out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
        }
        if (mask) {
            val key = ByteArray(4).also { SecureRandom().nextBytes(it) }
            out.write(key)
            val masked = ByteArray(payload.size)
            for (i in payload.indices) {
                masked[i] = (payload[i].toInt() xor key[i % 4].toInt()).toByte()
            }
            out.write(masked)
        } else {
            out.write(payload)
        }
        out.flush()
    }

    private fun readFully(input: InputStream, dst: ByteArray) {
        var off = 0
        while (off < dst.size) {
            val n = input.read(dst, off, dst.size - off)
            if (n < 0) throw IllegalStateException("流提前结束")
            off += n
        }
    }

    // ---- URL 解析 --------------------------------------------------------

    private class Target(
        val secure: Boolean,
        val host: String,
        val port: Int,
        val path: String,
    ) {
        val logLabel: String get() = "${if (secure) "wss" else "ws"}://$host:$port$path"
    }

    private fun parse(raw: String): Target {
        val secure = raw.startsWith("wss://", ignoreCase = true)
        val rest = raw.removePrefix("wss://").removePrefix("ws://").removePrefix("WSS://").removePrefix("WS://")
        val slash = rest.indexOf('/')
        val authority = if (slash >= 0) rest.substring(0, slash) else rest
        val path = if (slash >= 0) rest.substring(slash) else "/"
        val colon = authority.lastIndexOf(':')
        return if (colon > 0 && authority.indexOf(']') < colon) {
            Target(secure, authority.substring(0, colon), authority.substring(colon + 1).toInt(), path)
        } else {
            Target(secure, authority, if (secure) 443 else 80, path)
        }
    }

    companion object {
        private const val TAG = "WsClient"

        /** 专职写线程的名字。[lastWriteThread] 与回归测试都以它为准。 */
        const val WRITER_THREAD_NAME = "t2-ws-write"

        /** 一条 answer ≈ 4 KB、一条候选 ≈ 150 B，128 格够一次完整握手用满。 */
        private const val OUTBOX_CAPACITY = 128
        /** 握手（连接/TLS/HTTP 头）的读超时；宽限后的读超时见 WS_READ_TIMEOUT_MS。 */
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        /**
         * 读超时：靠房主 writeLoop 每 25s 的心跳（我们回 PONG 即重置计时）保活，
         * 90 秒连一帧都收不到才判死 —— 隧道黑洞/半开连接由此从"无限转圈"
         * 变成有界失败并进入既有 rescue 路径（REVIEW-2026-09-27 P2）。
         */
        private const val WS_READ_TIMEOUT_MS = 90_000
        private const val CR = 13
        private const val LF = 10
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
    }
}
