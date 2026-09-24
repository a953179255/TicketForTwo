package com.ticketfortwo.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败判定的单测。
 *
 * 这块逻辑的失败模式很特殊：**它不会崩，只会对用户说错话**。
 * 判成"对称 NAT"用户就去换网络，判成"对方没公网地址"用户才去催对方 ——
 * 说反了就是白折腾，所以每条分支都得钉住。
 */
class IceProbeTest {

    private fun host() = "a=candidate:1 1 udp 2113937151 192.168.1.7 54321 typ host generation 0"
    private fun srflx() = "a=candidate:2 1 udp 16777215 203.0.113.9 40001 typ srflx raddr 192.168.1.7 rport 54321"
    private fun relay() = "a=candidate:3 1 udp 16777215 198.51.100.4 40002 typ relay"

    @Test
    fun candidateTypeParsingCoversAllFourKinds() {
        assertEquals("host", candidateTypeOf(host()))
        assertEquals("srflx", candidateTypeOf(srflx()))
        assertEquals("relay", candidateTypeOf(relay()))
        assertEquals("prflx", candidateTypeOf("a=candidate:4 1 udp 1862270847 1.2.3.4 5000 typ prflx"))
        assertNull("非候选行不该被硬归类", candidateTypeOf("a=mid:0"))
        assertNull(candidateTypeOf(null))
    }

    /**
     * srflx 才是打洞的本钱，所以"候选一大堆但全是 host"不能算有公网地址。
     * 这里两边都只有 host，正确结论是"两边都没问到"—— 重点是它**没有**因为
     * 候选数量多就误判成"都拿到了公网地址"那一类。
     */
    @Test
    fun manyHostCandidatesStillCountAsNoPublicAddress() {
        val v = classify(listOf("host", "host", "host"), listOf("host", "host"), listOf("CHECKING" to 100L))
        assertTrue(v.headline, v.headline.contains("两边都没问到"))
        assertFalse("全是内网地址不该被建议去加中继", v.onlyRelayHelps)
    }

    /** 两边都有公网地址却仍失败 —— 这才是对称 NAT，也是唯一该提中继的情形。 */
    @Test
    fun bothHaveReflexiveButFailedIsTheSymmetricNatCase() {
        val v = classify(listOf("host", "srflx"), listOf("host", "srflx"), listOf("CHECKING" to 200L))
        assertTrue(v.headline, v.headline.contains("都拿到了公网地址"))
        assertTrue("只有这一类才该建议中继", v.onlyRelayHelps)
    }

    /**
     * 连上过之后再失败，跟打洞成没成功已经无关。
     * 这条同时钉住判定顺序：即使双方都没有 srflx，也必须先落到"中途断的"。
     */
    @Test
    fun everConnectedWinsOverTheCandidateAnalysis() {
        val v = classify(
            listOf("host"), listOf("host"),
            listOf("CHECKING" to 0L, "CONNECTED" to 1_000L, "DISCONNECTED" to 60_000L, "FAILED" to 68_000L),
        )
        assertTrue(v.headline, v.headline.contains("中途断的"))
        assertFalse(v.onlyRelayHelps)
    }

    @Test
    fun neitherSideGotPublicAddressIsItsOwnCase() {
        val v = classify(listOf("host"), listOf("host"), listOf("CHECKING" to 50L))
        assertTrue(v.headline, v.headline.contains("两边都没问到"))
    }

    /**
     * 对方正常、我方被挡 —— 责任方必须说清楚，否则用户会去催错的人。
     * 用 startsWith 而不是"不含对方字样"：标题里本来就会带一句「（对方那边是正常的）」，
     * 那正是有用的信息，不该被当成误判。
     */
    @Test
    fun distinguishesWhichSideLacksPublicAddress() {
        val mine = classify(listOf("host"), listOf("host", "srflx"), listOf("CHECKING" to 0L))
        assertTrue(mine.headline, mine.headline.startsWith("我这边"))

        val theirs = classify(listOf("host", "srflx"), listOf("host"), listOf("CHECKING" to 0L))
        assertTrue(theirs.headline, theirs.headline.startsWith("对方那边"))
    }

    /** 压根没进 CHECKING 说明信令就没成，跟 NAT 无关。 */
    @Test
    fun neverReachedCheckingIsASignalingFailure() {
        val v = classify(listOf("host", "srflx"), listOf("host", "srflx"), emptyList())
        assertTrue(v.headline, v.headline.contains("还没开始试地址"))
        assertFalse(v.onlyRelayHelps)
    }

    /** 证据里必须带上双方候选与轨迹 —— 界面靠这几行让用户自己核对结论。 */
    @Test
    fun evidenceListsBothSidesAndTrace() {
        val v = classify(listOf("host", "srflx"), listOf("host"), listOf("CHECKING" to 0L, "FAILED" to 15_000L))
        assertEquals(3, v.evidence.size)
        assertTrue(v.evidence[0].contains("srflx=1"))
        assertTrue(v.evidence[1].contains("srflx=0") || v.evidence[1].endsWith("host=1"))
        assertTrue(v.evidence[2].contains("FAILED 15s"))
    }

    @Test
    fun formatRendersEmptyAsNone() {
        assertEquals("无", format(emptyList()))
        assertEquals("host=2 srflx=1", format(listOf("host", "srflx", "host")))
    }

    /** 一次新分享不能带上一次的候选，否则判定会凭空多出 srflx。 */
    @Test
    fun probeResetClearsPriorSession() {
        val p = IceProbe()
        p.onLocalCandidate(srflx())
        p.onIceState("FAILED")
        p.reset()
        val v = p.verdict()
        assertTrue(v.headline, v.headline.contains("还没开始试地址"))
    }
}
