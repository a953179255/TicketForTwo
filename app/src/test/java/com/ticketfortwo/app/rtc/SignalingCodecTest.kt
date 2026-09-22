package com.ticketfortwo.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 信令编解码的纯逻辑测试。
 *
 * 这里同时产出 M0 要量的关键数字：一条真实规模的 offer 经"裁剪 + gzip + base64url"
 * 之后在链接里实际占多少字符。它决定"零服务器、链接自带 SDP"这条路在微信/QQ 里
 * 会不会被截断或折叠成卡片。
 */
class SignalingCodecTest {

    /** 一份贴近真机的 SDP：4G+WiFi 双栈，含 mDNS 与 IPv6 link-local 噪声候选。 */
    private val realisticSdp = buildString {
        append("v=0\r\n")
        append("o=- 4611731400430058336 2 IN IP4 127.0.0.1\r\n")
        append("s=-\r\nt=b=0 0\r\n")
        append("a=group:BUNDLE 0 1 2\r\n")
        // ---- 视频 m-line：8 条候选，混着该丢的 ----
        append("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
        append("c=IN IP4 0.0.0.0\r\na=rtcp:9 IN IP4 0.0.0.0\r\n")
        append("a=ice-ufrag:AbCd\r\na=ice-pwd:0123456789abcdef0123456789abcdef\r\n")
        append("a=fingerprint:sha-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\r\n")
        append("a=setup:actpass\r\na=mid:0\r\na=sendonly\r\n")
        append("a=rtcp-mux\r\n")
        append("a=rtpmap:96 H264/90000\r\n")
        append(CAND("1 1 udp 2122260223 192.168.1.42 49152 typ host generation 0 ufrag AbCd"))
        append(CAND("2 1 udp 2122194687 fe80::a1b2:c3d4:e5f6:7a8b 49153 typ host generation 0 ufrag AbCd"))
        append(CAND("3 1 udp 2122063615 a1b2c3d4-e5f6.local 49154 typ host generation 0 ufrag AbCd"))
        append(CAND("4 1 udp 1685987071 203.0.113.77 34567 typ srflx raddr 192.168.1.42 rport 49152 generation 0 ufrag AbCd"))
        append("a=end-of-candidates\r\n")
        // ---- 音频 m-line ----
        append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
        append("c=IN IP4 0.0.0.0\r\na=rtcp-mux\r\na=mid:1\r\na=sendonly\r\n")
        append("a=rtpmap:111 opus/48000/2\r\n")
        append(CAND("1 1 udp 2122260223 192.168.1.42 49200 typ host generation 0 ufrag AbCd"))
        append(CAND("2 1 udp 2122194687 fe80::a1b2:c3d4:e5f6:7a8b 49201 typ host generation 0 ufrag AbCd"))
        append(CAND("3 1 udp 1685987071 203.0.113.77 34600 typ srflx raddr 192.168.1.42 rport 49200 generation 0 ufrag AbCd"))
        append("a=end-of-candidates\r\n")
        // ---- 控制 DataChannel ----
        append("m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n")
        append("c=IN IP4 0.0.0.0\r\na=mid:2\r\na=sctp-port:5000\r\n")
    }

    private fun CAND(attr: String) = "a=candidate:$attr\r\n"

    @Test
    fun `丢弃 mDNS 与 IPv6 link-local 候选`() {
        val pruned = SignalingCodec.prune(realisticSdp)
        val lines = pruned.sdp.lineSequence().filter { it.startsWith("a=candidate:") }.toList()
        assertTrue("不该留 .local", lines.none { ".local" in it })
        assertTrue("不该留 fe80:", lines.none { "fe80::" in it })
        assertEquals("应丢 3 条噪声候选", 3, pruned.droppedCandidates)
        assertEquals("应留 4 条有效候选", 4, pruned.keptCandidates)
    }

    @Test
    fun `候选超过上限时按公网可达性截断`() {
        val many = buildString {
            append("v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
            repeat(10) { i ->
                append(CAND("f$i 1 udp ${1_000_000 + i} 10.0.0.$i ${40000 + i} typ host"))
            }
            repeat(3) { i ->
                append(CAND("s$i 1 udp ${2_000_000 + i} 198.51.100.$i 5000$i typ srflx raddr 10.0.0.1 rport 1"))
            }
        }
        val pruned = SignalingCodec.prune(many)
        val kept = pruned.sdp.lineSequence().filter { it.startsWith("a=candidate:") }.toList()
        assertEquals(SignalingCodec.MAX_CANDIDATES, kept.size)
        // srflx 排名高于私有 host，必须全部保留
        assertEquals(3, kept.count { " typ srflx" in it })
    }

    @Test
    fun `裁剪不破坏 SDP 其余内容`() {
        val pruned = SignalingCodec.prune(realisticSdp)
        assertTrue(pruned.sdp.contains("a=group:BUNDLE 0 1 2"))
        assertTrue(pruned.sdp.contains("a=rtpmap:96 H264/90000"))
        assertTrue(pruned.sdp.contains("a=rtpmap:111 opus/48000/2"))
        assertTrue(pruned.sdp.contains("a=sctp-port:5000"))
        assertTrue(pruned.sdp.contains("a=end-of-candidates"))
    }

    @Test
    fun `编码解码往返保持 SDP 完整`() {
        val pruned = SignalingCodec.prune(realisticSdp)
        val env = SignalingCodec.Envelope("K7M2", SignalingCodec.Kind.Offer, pruned.sdp)
        val back = SignalingCodec.decode(SignalingCodec.encode(env))
        assertNotNull(back)
        assertEquals(env, back)
    }

    @Test
    fun `链接往返并只保留 fragment 段`() {
        val env = SignalingCodec.Envelope(
            "K7M2", SignalingCodec.Kind.Offer, SignalingCodec.prune(realisticSdp).sdp
        )
        val url = SignalingCodec.toUrl("https://share.local/", env)
        assertTrue("token 必须在 # 之后，不进服务器日志", url.contains("#t2="))
        assertFalse("不得把 token 放进 query", url.contains("?t2="))
        val back = SignalingCodec.fromUrl(url)
        assertEquals(env, back)
        // 只有我们自己的链接才解得开
        assertEquals(null, SignalingCodec.fromUrl("https://evil.test/#x=1"))
    }

    @Test
    fun `量测真实链长——M0 关键数字`() {
        val unpruned = realisticSdp
        val pruned = SignalingCodec.prune(unpruned)
        val env = SignalingCodec.Envelope("K7M2", SignalingCodec.Kind.Offer, pruned.sdp)
        val wire = SignalingCodec.wireLength(env)
        println(
            "M0 链长实测: 未裁剪=${unpruned.length}ch 裁剪后=${pruned.sdp.length}ch " +
                "(丢 ${pruned.droppedCandidates} 条候选) 编码后=${wire}ch 压缩比=${
                    "%.2f".format(wire.toDouble() / unpruned.length)
                }"
        )
        assertTrue("裁剪应真的删掉内容", pruned.sdp.length < unpruned.length)
        assertTrue("编码后应显著短于原始 SDP（gzip 生效）", wire < unpruned.length)
        // 经验阈值：超过 ~1500 字符，聊天软件就可能把链接折成卡片或截断
        assertTrue("链长应在可转发范围内（实测 ${wire}ch）", wire < 1500)
    }
}
