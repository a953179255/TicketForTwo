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
    fun `URL 里的竖线先转义，字段就不会错位`() {
        // 2026-09-25 在模拟器上真抓到的地址形状：签名参数里带 `|`（见 MediaSnifferTest）
        val raw = "https://data.example.com/log/web?0011|16.mp4|quit"
        val st = state().copy(track = CinemaSync.Track(raw, "progressive", "标题"))
        val f = CinemaSync.fields(st, true)
        assertEquals(10, f.split("|").size)
        val back = CinemaSync.parseState(f)
        assertNotNull(back)
        // 不在解析端解码：%7C 本身就是合法 URL，播放端等价；解码反而会破坏原本就含 %7C 的地址
        assertEquals(raw.replace("|", "%7C"), back!!.track.url)
        assertEquals(st.posMs, back.posMs)
        assertEquals(st.version, back.version)
        // 老版本房主没转义 → 11 段：整条拒收，不能把标题当进度
        assertNull(CinemaSync.parseState("7|$raw|progressive|标题|10000|60000|1|1.0|1000000|1"))
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

    // ---- 播放回执：S 档命中率的原始数据 --------------------------------

    @Test
    fun `回执往返，detail 里再出现分隔符也不会丢`() {
        val ok = CinemaSync.parseAck("7|ok|ok|1920x1080")
        assertNotNull(ok)
        assertEquals(7L, ok!!.version)
        assertTrue(ok.ok)
        assertEquals("1920x1080", ok.detail)

        // hls.js 的 details 里就带竖线（networkError|manifestError），不能被切成两截
        val fail = CinemaSync.parseAck("3|fail|stream|这条流取不到：networkError|manifestError")
        assertNotNull(fail)
        assertFalse(fail!!.ok)
        assertEquals("stream", fail.code)
        assertEquals("这条流取不到：networkError|manifestError", fail.detail)
    }

    @Test
    fun `格式不对的回执当没收到，不能把信令流带崩`() {
        assertNull(CinemaSync.parseAck(""))
        assertNull(CinemaSync.parseAck(null))
        assertNull(CinemaSync.parseAck("ok|1920x1080"))          // 少了版本号
        assertNull(CinemaSync.parseAck("7|maybe|ok|1280x720"))   // 头一个词不是 ok/fail
        // code 缺省时落到 other，而不是数组越界
        assertEquals("other", CinemaSync.parseAck("7|fail")!!.code)
    }

    @Test
    fun `旧片源的回执不采信，未知版本一律采信`() {
        val a = CinemaSync.parseAck("7|ok|ok|")!!
        assertTrue(CinemaSync.isFreshAck(a, 7L))
        assertFalse(CinemaSync.isFreshAck(a, 8L))     // 房主已经换片了
        assertTrue(CinemaSync.isFreshAck(a, null))    // 收厅之后不作判断
        val unknown = CinemaSync.parseAck("-1|fail|timeout|x")!!
        assertTrue(CinemaSync.isFreshAck(unknown, 42L))
    }

    @Test
    fun `五种状态各有说法，长话不挤进标题行`() {
        val ok = CinemaSync.parseAck("7|ok|ok|1920x1080")!!
        val bad = CinemaSync.parseAck("7|fail|timeout|这条流 8 秒没出画面")!!
        val app = CinemaSync.parseAck("7|fail|appviewer|App 内不放原画，厅里只有语音，App 里还没有画面")!!

        val off = CinemaSync.describeAck(null, viewerOnline = false, timedOut = false)
        assertEquals("对方还没进厅", off.head)
        assertNull(off.detail)
        assertEquals(CinemaSync.AckTone.Neutral, off.tone)

        val waiting = CinemaSync.describeAck(null, viewerOnline = true, timedOut = false)
        assertEquals("等对方那边出画面…", waiting.head)
        assertEquals(CinemaSync.AckTone.Waiting, waiting.tone)

        val live = CinemaSync.describeAck(ok, viewerOnline = true, timedOut = true)
        assertEquals(CinemaSync.AckTone.Live, live.tone)
        assertTrue(live.head.contains("1920x1080"))

        val fail = CinemaSync.describeAck(bad, viewerOnline = true, timedOut = true)
        assertEquals(CinemaSync.AckTone.Bad, fail.tone)
        assertEquals("对方放不出这条", fail.head)
        assertEquals("这条流 8 秒没出画面", fail.detail)

        // App 里的观众不是故障，也不能被算成"已播起来"
        val inApp = CinemaSync.describeAck(app, viewerOnline = true, timedOut = true)
        assertEquals(CinemaSync.AckTone.Neutral, inApp.tone)
        assertFalse(inApp.head.contains("已播起来"))
        assertEquals("对方在 App 里", inApp.head)
        assertTrue(inApp.detail!!.contains("厅里只有语音"))

        // 等不到回执：既不是绿也不是红，是给房主指一条退路
        val stale = CinemaSync.describeAck(null, viewerOnline = true, timedOut = true)
        assertEquals(CinemaSync.AckTone.Warn, stale.tone)
        assertTrue(stale.detail!!.contains("收厅"))

        /* 手机浏览器不许自己放出声音 → 观众那边只是等一次点击。
           报成红色（放不出）会把房主支去查网络，而正确的做法是"等他点一下"；
           报成"没等到回执"同样是误导。手机上实测到过：第一条片源必然走这条路。 */
        val tap = CinemaSync.describeAck(
            CinemaSync.parseAck("9|fail|gesture|浏览器要先点一下才开始播")!!,
            viewerOnline = true, timedOut = false,
        )
        assertEquals(CinemaSync.AckTone.Waiting, tap.tone)
        assertEquals("对方点一下就开始播", tap.head)
        assertTrue(tap.detail!!.contains("点一下"))

        /* 标题行长度是**几何约束**，不是文风问题：
           上一版把长话拼进标题行，App 那句在卡里换了行，第二行正好压在"正在放映"下面，
           两个字叠在一起（截图实测）。所有分支的 head 都得短到不会换行。
           宽度按"中日韩算两格、其余算一格"估 —— 按字符数不行，
           "1920x1080" 九个字符只占六个汉字的位置。 */
        fun width(s: String) = s.sumOf { if (it.code >= 0x2E80) 2 else 1 }
        listOf(off, waiting, live, fail, inApp, stale, tap).forEach {
            val w = width(it.head)
            assertTrue("标题行太宽（$w 格）：${it.head}", w <= 30)
        }
        // 反面样本：拼成一句时那句 App 的话确实是超宽的，这就是当初换行压字的原因
        assertTrue(width("对方在 App 里 · App 内不放原画，厅里只有语音，App 里还没有画面") > 30)
    }
}
