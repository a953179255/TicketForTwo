package com.ticketfortwo.app.cinema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 放映厅同步协议。
 *
 * 这些数字（1.2s 才 seek、0.94~1.06 倍速、800ms 回声窗）是观众端体验的直接来源，
 * 也是 JS 那边同一套常量的镜像 —— 两边算得不一样，就会出现"房主看着对、观众总在追帧"
 * 这种最难查的现象。所以协议层在这里钉死，JS 只负责照做。
 */
class CinemaSyncTest {

    private fun state(
        pos: Long = 10_000L,
        dur: Long = 60_000L,
        playing: Boolean = true,
        rate: Double = 1.0,
        hostWall: Long = 1_000_000L,
        ver: Long = 7L,
    ) = CinemaSync.State(
        track = CinemaSync.Track("https://cdn/a/x.m3u8", "master", "《测试》3", dur),
        posMs = pos,
        durMs = dur,
        playing = playing,
        rate = rate,
        hostWallMs = hostWall,
        version = ver,
    )

    @Test
    fun `状态能往返，标题里的分隔符不会把字段撑破`() {
        val st = state().copy(track = CinemaSync.Track("https://a/b.mp4", "progressive", "a|b\"c\nd"))
        val f = CinemaSync.fields(st, true)
        val back = CinemaSync.parseState(f)
        assertNotNull(back)
        assertEquals("a b c d", back!!.track.title)
        assertEquals(st.posMs, back.posMs)
        assertEquals(st.version, back.version)
        assertEquals(1_000_000L, back.hostWallMs)
        assertTrue(CinemaSync.allowsControl(f))
        assertFalse(CinemaSync.allowsControl(CinemaSync.fields(st, false)))
    }

    @Test
    fun `脏数据一律拒收而不是半信半疑`() {
        assertNull(CinemaSync.parseState(""))
        assertNull(CinemaSync.parseState("1|javascript:alert(1)|mp4|t|0|0|1|1.0|0|1"))
        assertNull(CinemaSync.parseState("1|https://a/b.mp4|mp4"))   // 字段不够
        // 标题里带 | 也不会被误当成"多了一个字段"，因为写出去之前已经洗过
        assertNotNull(CinemaSync.parseState(CinemaSync.fields(state(), true)))
    }

    @Test
    fun `指令往返并钳住步进上限`() {
        assertEquals(CinemaSync.Cmd.Play, CinemaSync.parseCmd("play"))
        assertEquals(CinemaSync.Cmd.Pause, CinemaSync.parseCmd("pause"))
        assertEquals(CinemaSync.Cmd.Seek(30_000), CinemaSync.parseCmd("seek|30000"))
        assertEquals(CinemaSync.Cmd.Step(-10_000), CinemaSync.parseCmd("step|-10000"))
        // 观众手滑发个 -999999 秒，也只能退到步进上限
        assertEquals(
            CinemaSync.MAX_STEP_MS,
            (CinemaSync.parseCmd("step|-99999999") as CinemaSync.Cmd.Step).deltaMs.let { -it },
        )
        assertEquals(0L, (CinemaSync.parseCmd("seek|-1") as CinemaSync.Cmd.Seek).ms)
        assertNull(CinemaSync.parseCmd("rm|/"))
        assertNull(CinemaSync.parseCmd(""))
    }

    @Test
    fun `收回权限之后任何指令都不该被接受`() {
        val cmd = CinemaSync.parseCmd("step|10000")
        assertNotNull(CinemaSync.accept(true, cmd))
        assertNull(CinemaSync.accept(false, cmd))
        assertNull(CinemaSync.accept(true, null))
    }

    @Test
    fun `投影时间轴：播放中要按已经过去的时长往前推`() {
        val st = state(pos = 10_000L, hostWall = 1_000L)
        // 状态到手已经过了 3 秒，且还在放 —— 该在 13 秒，不是 10 秒
        assertEquals(13_000L, CinemaSync.projectedPos(st, 3_000L))
        // 暂停了就不该往前走，否则观众会自己滑出去
        assertEquals(10_000L, CinemaSync.projectedPos(st.copy(playing = false), 3_000L))
        // 2 倍速时这段时间按 2 倍算
        assertEquals(16_000L, CinemaSync.projectedPos(st.copy(rate = 2.0), 3_000L))
        // 时钟倒挂（对端时间比本地快）不能算出负数
        assertEquals(10_000L, CinemaSync.projectedPos(st, -5_000L))
    }

    @Test
    fun `偏差判定：小于阈值不 seek，超过才跳`() {
        assertFalse(CinemaSync.shouldSeek(1_199L))
        assertTrue(CinemaSync.shouldSeek(1_201L))
        assertTrue(CinemaSync.shouldSeek(-1_201L))
        assertFalse(CinemaSync.shouldSeek(0L))
    }

    @Test
    fun `倍速追赶：落后就快一点，超前就慢一点，且钳在上下限里`() {
        assertEquals(1.0, CinemaSync.rateWarp(50L), 1e-6)          // 死区里：什么都不做
        assertTrue(CinemaSync.rateWarp(1_000L) > 1.0)
        assertTrue(CinemaSync.rateWarp(-1_000L) < 1.0)
        assertEquals(CinemaSync.MAX_RATE, CinemaSync.rateWarp(99_000L), 1e-6)
        assertEquals(CinemaSync.MIN_RATE, CinemaSync.rateWarp(-99_000L), 1e-6)
    }

    @Test
    fun `步进换算成绝对位置时钳在片头片尾之间`() {
        assertEquals(20_000L, CinemaSync.stepTarget(10_000L, 60_000L, 10_000L))
        assertEquals(0L, CinemaSync.stepTarget(5_000L, 60_000L, -10_000L))
        assertEquals(60_000L, CinemaSync.stepTarget(55_000L, 60_000L, 10_000L))
        // 时长未知（直播/还没读到 metadata）时不做上限，但也不能为负
        assertEquals(0L, CinemaSync.stepTarget(0L, 0L, -10_000L))
    }

    @Test
    fun `回声窗口内拒绝重复处理，窗口过了自动失效`() {
        val g = CinemaSync.EchoGuard(windowMs = 800L)
        assertFalse(g.inEcho(1_000L))
        g.markApplied(1_000L)
        assertTrue(g.inEcho(1_500L))
        assertTrue(g.inEcho(1_799L))
        assertFalse(g.inEcho(1_800L))
        assertEquals(1, g.appliedCount())
        g.resetIfStale(2_000L)
        assertEquals(0, g.appliedCount())
    }

    @Test
    fun `时间格式在过小时和未过小时都对`() {
        assertEquals("0:00", CinemaSync.formatTime(0L))
        assertEquals("4:12", CinemaSync.formatTime(252_000L))
        assertEquals("1:02:03", CinemaSync.formatTime(3_723_000L))
        assertEquals("0:00", CinemaSync.formatTime(-5L))
    }
}
