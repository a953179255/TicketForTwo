package com.ticketfortwo.app.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同看协议层的单测。
 *
 * 为什么这一层值得单独测：它是**两台设备之间唯一的约定**，而且没有编译器帮忙 ——
 * 字段顺序错一位、标题里多一个竖线，观众那侧就永远显示"对方还没打开播放器"，
 * 而这种坏法在真机上看起来和"网络不行"一模一样。注入 JS 那半边同理：
 * 字符串拼错一个字符，evaluateJavascript 只会静默返回 null。
 */
class WatchSyncTest {

    @Test
    fun `状态字段往返一致`() {
        val s = WatchState(url = "u", found = true, posMs = 12_345, durMs = 600_000, playing = true, title = "某片")
        val (back, allow) = WatchSync.parseState(WatchSync.stateFields(s, true))!!
        assertTrue(back.found)
        assertEquals(12_345L, back.posMs)
        assertEquals(600_000L, back.durMs)
        assertTrue(back.playing)
        assertEquals("某片", back.title)
        assertTrue(allow)
    }

    @Test
    fun `标题里的分隔符不会把字段撑开`() {
        val s = WatchState(found = true, title = "a|b\"c\\d\ne")
        val fields = WatchSync.stateFields(s, false)
        // 6 个字段 = 5 个分隔符；标题里的竖线必须已经被替换掉
        assertEquals(5, fields.count { it == WatchSync.SEP })
        val (back, allow) = WatchSync.parseState(fields)!!
        assertFalse(back.title.contains('|'))
        assertFalse(allow)
    }

    @Test
    fun `解不开的字段一律按没有同看处理`() {
        assertNull(WatchSync.parseState(null))
        assertNull(WatchSync.parseState(""))
        assertNull(WatchSync.parseState("1|abc|0|0|0"))
    }

    @Test
    fun `只认白名单里的动作_越界的step被砍住`() {
        assertEquals(WatchCmd.Play, WatchSync.parseCmd("play|0"))
        assertEquals(WatchCmd.Pause, WatchSync.parseCmd("pause|0"))
        assertEquals(WatchCmd.Seek(30_000), WatchSync.parseCmd("seek|30000"))
        assertEquals(WatchCmd.Step(-10_000), WatchSync.parseCmd("step|-10000"))
        assertNull(WatchSync.parseCmd("dance|10"))
        assertNull(WatchSync.parseCmd("seek|xx"))
        assertEquals(
            WatchCmd.Step(WatchSync.MAX_STEP_MS),
            WatchSync.parseCmd("step|999999999"),
        )
        assertEquals(WatchCmd.Seek(0), WatchSync.parseCmd("seek|-5000"))
    }

    @Test
    fun `收回权限后指令一律进不去`() {
        val cmd = WatchCmd.Step(10_000)
        assertNotNull(WatchSync.accept(true, cmd))
        assertNull(WatchSync.accept(false, cmd))
        assertNull(WatchSync.accept(true, null))
    }

    @Test
    fun `快进落点夹在零与时长之间`() {
        assertEquals(20_000L, WatchSync.stepTarget(10_000, 600_000, 10_000))
        assertEquals(0L, WatchSync.stepTarget(5_000, 600_000, -10_000))
        assertEquals(600_000L, WatchSync.stepTarget(595_000, 600_000, 10_000))
        // 直播没有时长：只保证不回到负数
        assertEquals(0L, WatchSync.stepTarget(0, 0, -10_000))
        assertEquals(10_000L, WatchSync.stepTarget(0, 0, 10_000))
    }

    @Test
    fun `探针返回值解析`() {
        val s = WatchSync.parseProbe("\"1|1500|60000|1|Big Buck Bunny\"", url = "file://x")!!
        assertTrue(s.found)
        assertEquals(1_500L, s.posMs)
        assertEquals(60_000L, s.durMs)
        assertTrue(s.playing)
        assertEquals("Big Buck Bunny", s.title)
        assertEquals("file://x", s.url)

        val none = WatchSync.parseProbe("\"0|0|0|0|\"", "u")!!
        assertFalse(none.found)
        assertNull(WatchSync.parseProbe(null))
        assertNull(WatchSync.parseProbe("\"1|2\""))
    }

    @Test
    fun `指令生成的JS真的去动currentTime`() {
        val seek = WatchSync.jsFor(WatchCmd.Seek(30_000), 0, 60_000)
        // Seek 走的是"先夹上下界再赋值"，所以秒数出现在 t= 而不是直接写在 currentTime= 上
        assertTrue(seek, seek.contains("var t=30.0"))
        assertTrue(seek, seek.contains("v.currentTime=t"))
        assertTrue(seek, seek.contains(".play()"))

        val step = WatchSync.jsFor(WatchCmd.Step(10_000), 20_000, 60_000)
        // 20s + 10s = 30s
        assertTrue(step, step.contains("currentTime=30"))

        val pause = WatchSync.jsFor(WatchCmd.Pause, 0, 0)
        assertTrue(pause, pause.contains(".pause()"))
        // 每条指令都得自己找主 <video>：页面结构随时会变，不能依赖上一次的结果
        assertTrue(pause, pause.contains("querySelectorAll('video')"))
    }

    @Test
    fun `时间显示两端一致`() {
        assertEquals("0:00", WatchSync.formatTime(0))
        assertEquals("1:05", WatchSync.formatTime(65_000))
        assertEquals("1:02:00", WatchSync.formatTime(3_720_000))
        assertEquals("59:59", WatchSync.formatTime(3_599_000))
    }
}
