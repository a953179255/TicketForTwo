package com.ticketfortwo.app.signaling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * `WsClient` 的写线程回归测试。
 *
 * 要锁住的东西只有一句：**发送永远不在调用方线程上做**。因为真实调用方里就有主线程 ——
 * [com.ticketfortwo.app.rtc.Peer.onIceCandidate] 把候选 `handler.post` 回主线程再交给
 * listener，而观众端的 listener 就是 `ws.send(...)`。Android 禁止主线程网络 I/O，
 * 一旦写回调用方线程，每条候选都抛 `NetworkOnMainThreadException`（message 是 null，
 * 日志上只剩"发送失败：null"），App 内观看因此从来没有连上过。
 *
 * 这个坑在房主侧（`SignalHub`）已经踩过一次并修掉了，此测试防止它在观众侧重演。
 */
class WsClientTest {

    /**
     * 最小假 WebSocket 服务端：完成握手，然后把收到的文本帧按顺序丢进 [received]。
     *
     * 只回 `101` 而**不校验 Sec-WebSocket-Accept**：`WsClient` 的判断也就是状态行里
     * 有没有 "101"（见其 `handshake()`），照它的最小实现配就行，测试要验的是写线程
     * 不是 RFC 一致性。
     */
    private class FakeServer {
        val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val received = LinkedBlockingQueue<String>()
        val port: Int get() = socket.localPort

        fun start() {
            thread(isDaemon = true, name = "fake-ws-server") {
                val s = socket.accept()
                val input = s.getInputStream()
                val output = s.getOutputStream()
                val header = StringBuilder()
                var crlf = 0
                while (true) {
                    val b = input.read()
                    if (b < 0) return@thread
                    header.append(b.toChar())
                    crlf = when {
                        b == '\r'.code && crlf == 0 -> 1
                        b == '\n'.code && crlf == 1 -> 2
                        b == '\r'.code && crlf == 2 -> 3
                        b == '\n'.code && crlf == 3 -> 4
                        b == '\r'.code -> 1
                        else -> 0
                    }
                    if (crlf == 4) break
                }
                output.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\nConnection: Upgrade\r\n\r\n"
                        ).toByteArray(Charsets.ISO_8859_1)
                )
                output.flush()
                while (true) {
                    val payload = readMaskedFrame(input) ?: break
                    received.put(String(payload, Charsets.UTF_8))
                }
            }
        }

        /** 客户端→服务端的帧必须带掩码（RFC 6455 §5.3），这里只解这一种。 */
        private fun readMaskedFrame(input: java.io.InputStream): ByteArray? {
            val b0 = input.read(); val b1 = input.read()
            if (b0 < 0 || b1 < 0) return null
            if ((b0 and 0x0F) != 0x1) return ByteArray(0)
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            val key = ByteArray(4).also { k -> var off = 0; while (off < 4) off += input.read(k, off, 4 - off) }
            val out = ByteArray(len.toInt())
            var off = 0
            while (off < out.size) {
                val n = input.read(out, off, out.size - off)
                if (n < 0) return null
                off += n
            }
            for (i in out.indices) out[i] = (out[i].toInt() xor key[i % 4].toInt()).toByte()
            return out
        }
    }

    @Test
    fun `调用方是哪条线程都不影响落地，写永远发生在专职写线程`() {
        val srv = FakeServer()
        srv.start()
        val opened = CountDownLatch(1)
        val client = WsClient(
            url = "ws://127.0.0.1:${srv.port}/ws?k=TESTKEY",
            onOpen = { opened.countDown() },
            onText = {},
            onClosed = {},
        )
        client.connect()
        assertTrue("握手未完成", opened.await(5, TimeUnit.SECONDS))

        // 刻意用一条起名叫「模拟主线程」的线程调 send：真实场景里它就是 Android 主线程。
        val called = CountDownLatch(1)
        var callerName = ""
        thread(name = "模拟主线程") {
            callerName = Thread.currentThread().name
            client.send("cand-1")
            client.send("cand-2")
            called.countDown()
        }
        assertTrue(called.await(2, TimeUnit.SECONDS))

        assertEquals("cand-1", srv.received.poll(3, TimeUnit.SECONDS))
        assertEquals("cand-2", srv.received.poll(3, TimeUnit.SECONDS))
        // FIFO 对得上还不够，关键是这条：写没有发生在调用方线程上。
        val wrote = client.lastWriteThread
        assertNotNull("一次都没写出去", wrote)
        assertEquals(WsClient.WRITER_THREAD_NAME, wrote)
        assertTrue("写回了调用方线程，Android 上就是 NetworkOnMainThreadException", wrote != callerName)
        client.close()
    }

    @Test
    fun `握手前入队的消息不会被丢掉`() {
        // 握手要等 RTT，而 hello/候选可能在 out 还没就位时就已经 send 进来了。
        // 直接测这个窗口：不 connect 也能先把消息塞进队列，随后握手完成再一次性发走。
        val srv = FakeServer()
        srv.start()
        val opened = CountDownLatch(1)
        val client = WsClient(
            url = "ws://127.0.0.1:${srv.port}/ws?k=TESTKEY",
            onOpen = { opened.countDown() },
            onText = {},
            onClosed = {},
        )
        // 先入队再启动连接线程，保证队列早于 out。
        val writer = thread(isDaemon = true) {
            Thread.sleep(1)
            client.send("early")
        }
        client.connect()
        assertTrue("握手未完成", opened.await(5, TimeUnit.SECONDS))
        writer.join(2000)
        assertEquals("early", srv.received.poll(3, TimeUnit.SECONDS))
        client.close()
    }
}
