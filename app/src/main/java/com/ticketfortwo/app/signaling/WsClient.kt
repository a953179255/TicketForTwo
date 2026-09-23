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
 * 线程模型：一个读线程跑 [run]；[send] 从任意线程调用，内部用锁串行化写。
 */
class WsClient(
    private val url: String,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: (String?) -> Unit,
) {

    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()

    @Volatile
    private var closed = false

    private var thread: Thread? = null

    fun connect() {
        if (thread != null) return
        thread = thread(name = "t2-ws-client", isDaemon = true) { run() }
    }

    fun send(text: String) {
        val dst = out ?: return
        try {
            synchronized(writeLock) {
                writeFrame(dst, OP_TEXT, text.toByteArray(Charsets.UTF_8), mask = true)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "发送失败：${t.message}")
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
        out = null
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

            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())

            if (!handshake(input, output, target)) {
                onClosed("握手失败")
                runCatching { s.close() }
                return
            }
            out = output
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
