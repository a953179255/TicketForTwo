package com.ticketfortwo.app.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

/**
 * stats 解析的单测。
 *
 * 这块逻辑的唯一失败模式是"静默返回 null"或"读到探测噪声"—— 界面上就是
 * 控制岛永远显示 "—"，或者显示一个根本没在跑流量的数字。都不会崩，所以只能靠测。
 */
class StatsParseTest {

    private fun report(vararg stats: RTCStats) =
        RTCStatsReport(0L, stats.associateBy { it.id })

    private fun pair(
        id: String,
        rttSec: Double?,
        selected: Boolean,
        bytesSent: Long,
        localId: String,
        remoteId: String,
    ) = RTCStats(
        // 形参顺序是 (timestampUs, **type**, **id**, members) —— 把 id/type 写反，
        // 过滤 type=="candidate-pair" 就一条也匹配不上，测试会以"全 null"失败。
        // 第一次就是这么翻的车，所以这行注释留着。
        0L, "candidate-pair", id,
        buildMap {
            put("selected", selected)
            put("bytesSent", bytesSent)
            put("localCandidateId", localId)
            put("remoteCandidateId", remoteId)
            if (rttSec != null) put("currentRoundTripTime", rttSec)
        },
    )

    private fun candidate(id: String, type: String) =
        RTCStats(0L, "local-candidate", id, mapOf("candidateType" to type))

    /**
     * currentRoundTripTime 的单位是**秒**。这条测的是最容易写错的地方：
     * 当成毫秒直接显示，2.5ms 的局域网会变成"2500ms"。
     */
    @Test
    fun rttIsSecondsNotMilliseconds() {
        val r = report(
            pair("p1", rttSec = 0.0025, selected = true, bytesSent = 1_000_000, "l1", "r1"),
            candidate("l1", "host"), candidate("r1", "host"),
        )
        assertEquals(3, parseStats(r).rttMs)
    }

    /**
     * 报告里会同时留着**探测过**的候选对（曾经 succeeded）。
     * 只有 selected 那条在实际跑流量，必须优先选它，哪怕别的 bytesSent 更大。
     */
    @Test
    fun selectedPairWinsEvenWhenAnotherSentMoreBytes() {
        val r = report(
            pair("probe", rttSec = 0.5, selected = false, bytesSent = 9_000_000, "l1", "r1"),
            pair("real", rttSec = 0.004, selected = true, bytesSent = 100, "l1", "r1"),
            candidate("l1", "host"), candidate("r1", "host"),
        )
        assertEquals(4, parseStats(r).rttMs)
    }

    /** 没有 selected 标记的旧实现：退化成"发过最多字节"的那条。 */
    @Test
    fun fallsBackToHighestBytesSentWhenNothingSelected() {
        val r = report(
            pair("a", rttSec = 0.02, selected = false, bytesSent = 10, "l1", "r1"),
            pair("b", rttSec = 0.03, selected = false, bytesSent = 5_000, "l1", "r1"),
            candidate("l1", "srflx"), candidate("r1", "srflx"),
        )
        assertEquals(30, parseStats(r).rttMs)
    }

    /**
     * 刚连上、一个字节都还没发出去时，bytesSent 全为 0，排不出"哪条在用"。
     * 这时才允许退回"任意有 RTT 的一条" —— 这是最后一级，不是第一级。
     */
    @Test
    fun fallsBackToAnyRttPairOnlyWhenNoBytesFlowed() {
        val r = report(
            pair("a", rttSec = 0.012, selected = false, bytesSent = 0, "l1", "r1"),
            pair("b", rttSec = null, selected = false, bytesSent = 0, "l1", "r1"),
            candidate("l1", "host"), candidate("r1", "host"),
        )
        assertEquals(12, parseStats(r).rttMs)
    }

    /**
     * 通路类型的判读口径：两端都是 host 才算"同一网络"（没出网关）；
     * 任何一端 srflx 就是经过公网地址反射的跨 NAT 直连。
     */
    @Test
    fun viaLabelDistinguishesLanFromStun() {
        val lan = report(
            pair("p", 0.001, true, 10, "l1", "r1"),
            candidate("l1", "host"), candidate("r1", "host"),
        )
        assertEquals("同一网络", parseStats(lan).viaLabel)

        val across = report(
            pair("p", 0.03, true, 10, "l1", "r1"),
            candidate("l1", "host"), candidate("r1", "srflx"),
        )
        assertEquals("跨网直连", parseStats(across).viaLabel)
    }

    @Test
    fun viaLabelTruthTable() {
        assertEquals("同一网络", viaLabel("host", "host"))
        assertEquals("跨网直连", viaLabel("host", "srflx"))
        assertEquals("跨网直连", viaLabel("srflx", "host"))
        // 本项目没有 TURN，relay 理论上不出现；真出现了要能看出来，不能被归进"直连"
        assertEquals("中继", viaLabel("host", "relay"))
        assertNull(viaLabel(null, null))
    }

    /** candidate id 指不到人（报告被截断/字段缺失）时，类型留 null，不猜。 */
    @Test
    fun missingCandidateStatsYieldNullLabel() {
        val r = report(pair("p", 0.002, true, 10, "missing", "alsoMissing"))
        val s = parseStats(r)
        assertEquals(2, s.rttMs)
        assertNull(s.viaLabel)
    }

    @Test
    fun emptyReportHasNoStats() {
        val s = parseStats(report())
        assertNull(s.rttMs)
        assertNull(s.viaLabel)
        assertNull(parseStats(null).rttMs)
    }
}
