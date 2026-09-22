package com.ticketfortwo.app.rtc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 一期零服务器信令：把 SDP + ICE 候选编码进邀请链接的 `#` 片段，
 * 观众端回一条同样自包含的「应答链接」，两次消息往返完成握手。
 *
 * 为什么走 `#` 而不是 `?`：hash 片段不会被任何中间代理/服务器访问日志记录，
 * 而这些链接要经过微信/QQ 转发，尽量少留痕。
 *
 * 压缩是必须的：4G+WiFi 双栈下 SDP 可达 2–2.5KB，base64 后约 3KB，塞进聊天消息
 * 有被截断/折叠成卡片的风险。SDP 文本高度可压缩，deflate 后通常能压到 1/3 左右。
 */
object SignalingCodec {

    /** 候选上限。超过这个数链接就可能过长，见 PLAN.md §9 的尺寸风险。 */
    const val MAX_CANDIDATES = 6

    enum class Kind { Offer, Answer }

    /** 一条自包含的信令负载。候选留在 [sdp] 里，不单独携带。 */
    data class Envelope(
        val room: String,
        val kind: Kind,
        val sdp: String,
    )

    // ---- 候选分类与裁剪 ------------------------------------------------

    private enum class CandRank(val score: Int) {
        Srflx(3),      // 公网反射地址：跨 NAT 唯一可能成的
        HostPublic(2), // 本机公网 IPv4（少见但存在）
        HostPrivate(1),// 192.168./10./172.16-31 私有地址：同网段直连有用
        Relay(0),      // 一期没有 TURN，理论上不会出现
        Useless(-1),   // mDNS .local 与 IPv6 link-local：跨网无意义且泄隐私
    }

    /** 判断一行 `a=candidate:` 该不该留。 */
    private fun rankOf(candidateLine: String): CandRank {
        val l = candidateLine.lowercase()
        if (l.contains(".local")) return CandRank.Useless          // mDNS 混淆地址
        if (Regex("""\bfe80:""").containsMatchIn(l)) return CandRank.Useless // IPv6 链路本地
        return when {
            l.contains(" typ relay") -> CandRank.Relay
            l.contains(" typ srflx") -> CandRank.Srflx
            l.contains(" typ host") -> if (isPrivateIpv4(l)) CandRank.HostPrivate else CandRank.HostPublic
            else -> CandRank.Useless
        }
    }

    private fun isPrivateIpv4(l: String): Boolean {
        val m = Regex("""\s(\d{1,3}(?:\.\d{1,3}){3})\s+\d+\s+typ""").find(l) ?: return false
        val ip = m.groupValues[1].split(".").map { it.toIntOrNull() ?: -1 }
        if (ip.size != 4 || ip.any { it < 0 }) return false
        return ip[0] == 10 ||
            (ip[0] == 192 && ip[1] == 168) ||
            (ip[0] == 172 && ip[1] in 16..31) ||
            ip[0] == 127
    }

    /**
     * 裁剪 SDP：**原地**删掉跨网无用的候选，其余按"公网可达性优先 + 原始 priority 优先"
     * 排序后截到 [MAX_CANDIDATES] 条。
     *
     * 注意不把候选挪出 SDP —— 挪出去并不会让负载变短（同样的文本换个位置），
     * 反而要接收方在 setRemoteDescription 之后逐条 addIceCandidate，多一个出错面。
     * 留在 SDP 里，对端一次 setRemote 就全拿到了。
     */
    fun prune(sdp: String): Pruned {
        val lines = sdp.split("\r\n", "\n")
        val ranked = ArrayList<Pair<Int, Pair<CandRank, String>>>()
        lines.forEachIndexed { i, line ->
            if (line.startsWith("a=candidate:")) {
                val r = rankOf(line)
                if (r != CandRank.Useless) ranked += i to (r to line)
            }
        }
        val dropped = lines.count { it.startsWith("a=candidate:") } - ranked.size

        // 保留原有相对顺序里排名靠前的 N 条：按 (rank, priority) 选集合，再按原索引排回去
        val keepIdx = ranked
            .sortedWith(
                compareByDescending<Pair<Int, Pair<CandRank, String>>> { it.second.first.score }
                    .thenByDescending { priorityOf(it.second.second) }
            )
            .take(MAX_CANDIDATES)
            .map { it.first }
            .toHashSet()

        val out = ArrayList<String>(lines.size)
        var seenCandidate = 0
        for ((i, line) in lines.withIndex()) {
            if (line.startsWith("a=candidate:")) {
                if (i in keepIdx) out += line
                seenCandidate++
                continue
            }
            out += line
        }
        return Pruned(
            sdp = out.joinToString("\r\n"),
            keptCandidates = keepIdx.size,
            droppedCandidates = dropped,
            hadCandidates = seenCandidate,
        )
    }

    /** 裁剪结果与诊断计数。 */
    data class Pruned(
        val sdp: String,
        val keptCandidates: Int,
        val droppedCandidates: Int,
        val hadCandidates: Int,
    )

    /** `a=candidate:... 123456 typ ...` 里 priority 是 generation 之后的那个数字。 */
    private fun priorityOf(candidateLine: String): Long =
        candidateLine.split(" ").let { parts ->
            // 形如：a=candidate:foundation 1 udp <priority> <ip> <port> typ <type> ...
            parts.getOrNull(3)?.toLongOrNull() ?: 0L
        }

    // ---- 链接编解码 ----------------------------------------------------

    private const val SEP = "\u0001"

    fun encode(env: Envelope): String {
        val raw = buildString {
            append(env.room); append(SEP)
            append(env.kind.name); append(SEP)
            append(env.sdp)
        }
        return b64url(gzip(raw.toByteArray(Charsets.UTF_8)))
    }

    fun decode(token: String): Envelope? = runCatching {
        val raw = gunzip(unb64url(token)).toString(Charsets.UTF_8)
        val p = raw.split(SEP, limit = 3)
        if (p.size < 3) return null
        Envelope(room = p[0], kind = Kind.valueOf(p[1]), sdp = p[2])
    }.getOrNull()

    /** 生成/解析邀请链接。token 放在 fragment 里，不进服务器日志。 */
    fun toUrl(base: String, env: Envelope): String = "$base#$TAG=${encode(env)}"

    fun fromUrl(url: String): Envelope? {
        val frag = url.substringAfter('#', "")
        val token = frag.split('&', ';')
            .firstOrNull { it.startsWith("$TAG=") }
            ?.removePrefix("$TAG=") ?: return null
        return decode(token)
    }

    private const val TAG = "t2"

    /** 编码后这条信令在链接里实际占多少字符——M0 要量的关键数字。 */
    fun wireLength(env: Envelope): Int = encode(env).length

    // ---- 压缩与 base64url ----------------------------------------------

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        ByteArrayOutputStream().use { out ->
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.copyTo(out) }
            return out.toByteArray()
        }
    }

    private fun b64url(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unb64url(s: String): ByteArray =
        java.util.Base64.getUrlDecoder().decode(s)
}
