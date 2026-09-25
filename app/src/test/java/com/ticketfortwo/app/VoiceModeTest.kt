package com.ticketfortwo.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 声音档的三条判断。
 *
 * 为什么值得单独钉：这三条判断决定的是"对方到底听不听得见"，而它错的方向很难看穿 ——
 * 麦克风该开没开，观众那边只是安静，房主以为对方没说话；反过来该关没关，
 * 观众听到两份叠在一起的影片声，听起来像网络问题。两种都不会报错，只能靠测试钉住。
 */
class VoiceModeTest {

    @Test
    fun `默认档是只有视频声`() {
        // 用户明确要求："默认的就是只有视频声，连麦和只连麦需要额外开启"
        assertEquals(VoiceMode.VideoOnly, ShareQuality().voiceMode)
    }

    @Test
    fun `只有只连麦不传画面`() {
        assertTrue(VoiceMode.sendsVideo(VoiceMode.VideoOnly))
        assertTrue(VoiceMode.sendsVideo(VoiceMode.VideoPlusCall))
        assertFalse(VoiceMode.sendsVideo(VoiceMode.CallOnly))
        // videoEnabled 是派生值，两者必须永远一致（六处判断在读它）
        assertEquals(VoiceMode.sendsVideo(VoiceMode.CallOnly), ShareQuality(voiceMode = VoiceMode.CallOnly).videoEnabled)
        assertEquals(true, ShareQuality().videoEnabled)
    }

    @Test
    fun `对方本地播原声时才关房主麦克风`() {
        // 关键的一条：B 档（屏幕分享 / App 内收看）时观众的声音**只有**房主外放灌麦克风这一条路，
        // 那时关掉麦克风 = 观众那边彻底静音，比回声严重得多。
        assertFalse(VoiceMode.hostMicNeeded(VoiceMode.VideoOnly, viewerPlaysLocally = true))
        assertTrue(VoiceMode.hostMicNeeded(VoiceMode.VideoOnly, viewerPlaysLocally = false))
        // 连麦两档永远要麦，跟对方在不在本地播无关
        for (local in listOf(true, false)) {
            assertTrue(VoiceMode.hostMicNeeded(VoiceMode.VideoPlusCall, local))
            assertTrue(VoiceMode.hostMicNeeded(VoiceMode.CallOnly, local))
        }
    }

    @Test
    fun `只有视频声时房主听不到对方`() {
        assertFalse(VoiceMode.hostHearsViewer(VoiceMode.VideoOnly))
        assertTrue(VoiceMode.hostHearsViewer(VoiceMode.VideoPlusCall))
        assertTrue(VoiceMode.hostHearsViewer(VoiceMode.CallOnly))
    }

    @Test
    fun `三档文案不重名`() {
        val labels = VoiceMode.entries.map { VoiceMode.label(it) }
        assertEquals(3, labels.toSet().size)
        assertEquals("只有视频声", VoiceMode.label(VoiceMode.VideoOnly))
        assertEquals("只连麦", VoiceMode.label(VoiceMode.CallOnly))
    }

    @Test
    fun `摘要行把声音档放在最前面`() {
        // 首页那行摘要是用户判断"我这场是什么模式"的唯一入口，声音档必须在最左。
        val s = ShareQuality(voiceMode = VoiceMode.CallOnly).summary(1080)
        assertEquals("只连麦", s)
        val t = ShareQuality(voiceMode = VoiceMode.VideoPlusCall, scale = 0.5f, fps = 30, maxVideoBps = 2_000_000)
            .summary(1080)
        assertTrue(t, t.startsWith("视频声 + 连麦 · "))
        assertTrue(t, t.contains("540p"))
    }
}
