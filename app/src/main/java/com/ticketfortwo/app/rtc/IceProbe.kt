package com.ticketfortwo.app.rtc

/**
 * ICE 过程的取证器。
 *
 * 为什么必须有：失败界面原本只有一句"一方可能在对称 NAT 之后"，而那是**猜**的 ——
 * 代码里只要 `ice=FAILED` 就打这句。可实际原因至少分四种（我方 STUN 不通 / 对方没拿到
 * 公网地址 / 两边都只有内网地址 / 连上之后才断），处置方式完全不同。用户拿着一个
 * 猜出来的结论去换网络，等于瞎试。这里改成**从证据推结论**，并把证据一起显示出来。
 *
 * 隐私：只统计候选**类型**，绝不记录 IP 与端口 —— 这个界面是要被截图发出去的。
 *
 * 刻意不依赖 android.os.SystemClock：这是纯逻辑，用 JVM 时钟才能直接在单测里跑
 * （本项目的单测走 JUnitCore 直跑，classpath 里没有 android.jar）。
 */
class IceProbe {

    private val startedAt = System.nanoTime()
    private val localTypes = ArrayList<String>()
    private val remoteTypes = ArrayList<String>()
    private val states = ArrayList<Pair<String, Long>>()

    fun onLocalCandidate(sdp: String?) { candidateTypeOf(sdp)?.let { localTypes += it } }

    fun onRemoteCandidate(sdp: String?) { candidateTypeOf(sdp)?.let { remoteTypes += it } }

    fun onIceState(state: String) { states += state to elapsedMs() }

    /** 一次新的分享要清空，否则上一次的候选会污染这一次的判定。 */
    fun reset() {
        localTypes.clear(); remoteTypes.clear(); states.clear()
    }

    fun verdict(): Verdict = classify(localTypes, remoteTypes, states)

    /** 给 logcat 用的紧凑摘要（同样不含 IP）。 */
    fun summary(): String =
        "本地[${format(localTypes)}] 远端[${format(remoteTypes)}] 轨迹[" +
            states.joinToString(">") { it.first } + "]"

    private fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000
}

/**
 * 一次失败的判定结果。
 *
 * @param onlyRelayHelps 是否属于"纯 P2P 无解、必须加中继"那一类。
 *                       这个标志位是给界面用的：不该在没有证据时吓唬用户。
 */
data class Verdict(
    val headline: String,
    val nextStep: String,
    val evidence: List<String>,
    val onlyRelayHelps: Boolean,
)

/** 从一行 `a=candidate:` 里取类型。取不到返回 null，不猜。 */
internal fun candidateTypeOf(line: String?): String? {
    val l = line?.lowercase() ?: return null
    return when {
        " typ relay" in l -> "relay"
        " typ srflx" in l -> "srflx"
        " typ prflx" in l -> "prflx"
        " typ host" in l -> "host"
        else -> null
    }
}

internal fun format(types: List<String>): String =
    if (types.isEmpty()) "无" else types.groupingBy { it }.eachCount()
        .entries.joinToString(" ") { (t, n) -> "$t=$n" }

/**
 * 判定逻辑。顺序是刻意排的：
 *
 * 先判"连上过没有" —— 连上过再断，跟打洞成功与否已经无关了，
 * 这时候谈对称 NAT 是错的（那是第一次就没通的人才有的问题）。
 *
 * 然后才按"谁没拿到公网地址"分档。**srflx 是打洞的唯一本钱**：
 * 它是 STUN 问回来的公网反射地址，两边都得有一个才可能互敲成功。
 * 所以"有没有 srflx"比"候选总数"有意义得多 —— 一堆 host 候选在跨网场景里全是废票。
 */
internal fun classify(
    local: List<String>,
    remote: List<String>,
    states: List<Pair<String, Long>>,
): Verdict {
    val evidence = buildList {
        add("我这边的候选：${format(local)}")
        add("对方的候选：${format(remote)}")
        add(
            "状态轨迹：" + if (states.isEmpty()) "（还没走到 ICE）"
            else states.joinToString(" → ") { (s, ms) -> "$s ${ms / 1000}s" }
        )
    }
    val iConnected = states.any { it.first == "CONNECTED" || it.first == "COMPLETED" }
    val lSrflx = local.count { it == "srflx" }
    val rSrflx = remote.count { it == "srflx" }

    return when {
        iConnected -> Verdict(
            headline = "之前连上了，是中途断的",
            nextStep = "这不是打洞失败。多半是切网络或运营商把地址映射收了 —— 让对方重新点一次链接最快。",
            evidence = evidence,
            onlyRelayHelps = false,
        )

        states.none { it.first == "CHECKING" } -> Verdict(
            headline = "还没开始试地址就失败了",
            nextStep = "信令那一步就没走通（对方可能没真正打开链接，或临时地址失效）。重发一次邀请。",
            evidence = evidence,
            onlyRelayHelps = false,
        )

        lSrflx == 0 && rSrflx == 0 -> Verdict(
            headline = "两边都没问到自己对外的公网地址",
            nextStep = "这一步就失败了，后面不可能通。先让对方也开一次移动数据（或你连热点）再试 —— " +
                "同一网络下不需要公网地址，能直接验证是不是这个问题。",
            evidence = evidence,
            onlyRelayHelps = false,
        )

        lSrflx == 0 -> Verdict(
            headline = "我这边没问到公网地址（对方那边是正常的）",
            nextStep = "问题在我这侧网络：STUN 请求出不去或被挡。换一次网络（WiFi↔移动数据）再发起分享，" +
                "公司/校园网最常见这种限制。",
            evidence = evidence,
            onlyRelayHelps = false,
        )

        rSrflx == 0 -> Verdict(
            headline = "对方那边没问到公网地址（我这边是正常的）",
            nextStep = "问题在对方网络。让他换一次网络（WiFi↔移动数据）再点链接；" +
                "他那边如果是公司/校园网，多半就是被挡了。",
            evidence = evidence,
            onlyRelayHelps = false,
        )

        else -> Verdict(
            headline = "两边都拿到了公网地址，却还是敲不通",
            nextStep = "这是对称 NAT 的典型特征：两边问到的公网地址只对「问它的那台服务器」有效，" +
                "互相敲门时对方不认。纯点对点在这种情况下无解，只能加中继（TURN）兜底。",
            evidence = evidence,
            onlyRelayHelps = true,
        )
    }
}
