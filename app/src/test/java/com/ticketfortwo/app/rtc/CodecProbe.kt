package com.ticketfortwo.app.rtc

/**
 * 跨语言互操作探针（不是单测，是工具）。
 *
 * 信令格式是"零服务器"这条路的承重墙：Kotlin 侧编出来、浏览器 JS 侧要能解开，
 * 反向也要成立。肉眼比对 base64/gzip 没有意义，所以做成可执行的双向探针：
 *
 *   java ... CodecProbeKt encode            → stdout 打印一个 Offer token
 *   java ... CodecProbeKt decode < token    → 打印解出的 room / kind / sdp 长度
 *
 * scripts/check-signaling-interop.sh 会把两端串起来跑闭环。
 */
fun main(args: Array<String>) {
    val mode = args.getOrNull(0) ?: "encode"
    when (mode) {
        "encode" -> println(SignalingCodec.encode(SignalingCodec.Envelope("K7M2", SignalingCodec.Kind.Offer, SAMPLE_SDP)))

        // 打印"期望值"：长度 + sha256。Node 侧对解出来的 SDP 算同样的哈希，
        // 只有字节级完全一致才算跨语言互通 —— 比对长度会漏掉行尾 CRLF 差异。
        "meta" -> {
            val bytes = SAMPLE_SDP.toByteArray(Charsets.UTF_8)
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            println("${bytes.size} $digest")
        }

        "decode" -> {
            val token = generateSequence(::readLine).joinToString("").trim()
            val env = SignalingCodec.decode(token)
            if (env == null) {
                println("DECODE_FAILED")
                kotlin.system.exitProcess(1)
            }
            println("room=${env.room}")
            println("kind=${env.kind}")
            println("sdpLen=${env.sdp.length}")
            println("hasCandidates=${env.sdp.lineSequence().any { it.startsWith("a=candidate:") }}")
        }
    }
}

/** 一份够用的假 SDP：多行、CRLF、含候选，覆盖 JS 侧解析的所有分支。 */
private val SAMPLE_SDP = buildString {
    append("v=0\r\n")
    append("o=- 123 2 IN IP4 127.0.0.1\r\n")
    append("s=-\r\n")
    append("t=0 0\r\n")
    append("a=group:BUNDLE 0 1\r\n")
    append("m=video 9 UDP/TLS/RTP/SAVPF 96\r\n")
    append("c=IN IP4 0.0.0.0\r\n")
    append("a=ice-ufrag:AbCd\r\n")
    append("a=ice-pwd:0123456789abcdef0123456789abcdef\r\n")
    append("a=fingerprint:sha-256 11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99:AA:BB:CC:DD:EE:FF:00:11\r\n")
    append("a=setup:actpass\r\n")
    append("a=mid:0\r\n")
    append("a=sendonly\r\n")
    append("a=rtcp-mux\r\n")
    append("a=rtpmap:96 H264/90000\r\n")
    append("a=candidate:1 1 udp 2122260223 192.168.1.42 49152 typ host generation 0\r\n")
    append("a=candidate:2 1 udp 1685987071 203.0.113.77 34567 typ srflx raddr 192.168.1.42 rport 49152 generation 0\r\n")
    append("a=end-of-candidates\r\n")
    append("m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n")
    append("a=mid:1\r\n")
    append("a=sendonly\r\n")
    append("a=rtpmap:111 opus/48000/2\r\n")
}
