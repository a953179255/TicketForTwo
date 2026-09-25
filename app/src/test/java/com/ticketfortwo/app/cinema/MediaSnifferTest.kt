package com.ticketfortwo.app.cinema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 片源嗅探的分类与取舍。
 *
 * 这一层是 S 档的地基：判错一条，要么房主面前一屏候选都是埋点，
 * 要么真片源被当成分片丢掉。所以每条规则都钉一个用例，尤其是那些
 * "长得像但不是"的（TypeScript 的 .ts、广告 .mp4、签名串里的 .m3u8）。
 */
class MediaSnifferTest {

    private fun kind(u: String) = MediaSniffer.classify(u)

    @Test
    fun `埋点把片名塞进查询串也不算片源`() {
        // 2026-09-25 在模拟器上真抓到的：B 站的 log 端点，查询串里带着 .mp4 字样
        assertEquals(
            MediaSniffer.Kind.Ignored,
            kind(
                "https://data.bilibili.com/log/web?001117903314|mobile-wplayer|" +
                    "137649199_da2-1-16.mp4|quit",
            ),
        )
        assertEquals(MediaSniffer.Kind.Ignored, kind("https://gw.alipayobjects.com/v/log.gif?x=1"))
        // 真片源仍然要认出来（同一条 B 站地址，去掉埋点域名）
        assertEquals(
            MediaSniffer.Kind.Progressive,
            kind("https://upos-sz-estgcos.bilivideo.com/upgcxcode/99/137649199_da2-1-16.mp4?e=abc"),
        )
    }

    @Test
    fun `HLS 清单认得出来，带签名参数也认得`() {
        assertEquals(MediaSniffer.Kind.Master, kind("https://cdn.example.com/a/index.m3u8"))
        assertEquals(
            MediaSniffer.Kind.Master,
            kind("https://cdn.example.com/a/index.m3u8?sign=abc123&t=1700000000"),
        )
        assertEquals(
            MediaSniffer.Kind.Master,
            kind("https://v.example.com/playlist/m3u8?vid=8842&type=m3u8&token=x"),
        )
    }

    @Test
    fun `单文件是最理想的候选，清单次之`() {
        assertEquals(MediaSniffer.Kind.Progressive, kind("https://cdn/a/b/movie.mp4"))
        assertEquals(MediaSniffer.Kind.Progressive, kind("https://cdn/a/b/movie.webm?start=0"))
        assertTrue(MediaSniffer.playable(MediaSniffer.Kind.Progressive))
        assertTrue(MediaSniffer.playable(MediaSniffer.Kind.Master))
        // DASH 清单浏览器原生放不了，先不当候选（要 dash.js）
        assertFalse(MediaSniffer.playable(kind("https://cdn/a/manifest.mpd")))
        assertFalse(MediaSniffer.playable(MediaSniffer.Kind.Segment))
    }

    @Test
    fun `分片只计数不当候选，但 TypeScript 源码要放过`() {
        assertEquals(MediaSniffer.Kind.Segment, kind("https://cdn/hls/seg-7.ts"))
        assertEquals(MediaSniffer.Kind.Segment, kind("https://cdn/dash/video-init.m4s"))
        // .ts 后缀的歧义：没有分片特征的就不是媒体，别把它塞进列表
        assertEquals(MediaSniffer.Kind.Ignored, kind("https://unpkg.com/vite/main.ts"))
    }

    @Test
    fun `埋点与广告即使后缀是 mp4 也不算片源`() {
        assertEquals(MediaSniffer.Kind.Ignored, kind("https://google-analytics.com/collect"))
        assertEquals(MediaSniffer.Kind.Ignored, kind("https://ads.example.com/pre-roll.mp4"))
        assertEquals(MediaSniffer.Kind.Ignored, kind("https://sentry.io/api/1/envelope/"))
        assertEquals(MediaSniffer.Kind.Ignored, kind(""))
        assertEquals(MediaSniffer.Kind.Ignored, kind("   "))
    }

    @Test
    fun `字幕单独归类，不混进片源`() {
        assertEquals(MediaSniffer.Kind.Subtitle, kind("https://cdn/a/zh-cn.vtt"))
        assertFalse(MediaSniffer.playable(MediaSniffer.Kind.Subtitle))
    }

    @Test
    fun `排序让单文件和主清单浮到顶，分片沉底`() {
        val now = 1_000L
        val seg = MediaSniffer.Hit(
            "https://cdn/hls/seg-1.ts", MediaSniffer.Kind.Segment, null, null, now, hits = 99,
        )
        val mp4 = MediaSniffer.Hit(
            "https://cdn/a/movie.mp4", MediaSniffer.Kind.Progressive, null, null, now, hits = 1,
        )
        val master = MediaSniffer.Hit(
            "https://cdn/a/index.m3u8", MediaSniffer.Kind.Master, null, null, now, hits = 1,
        )
        assertTrue(MediaSniffer.score(mp4) > MediaSniffer.score(master))
        assertTrue(MediaSniffer.score(master) > MediaSniffer.score(seg))
    }

    @Test
    fun `bestOf 优先选页面亲口报过的那条`() {
        val fromRequest = MediaSniffer.Hit(
            "https://cdn/a/proxy.mp4", MediaSniffer.Kind.Progressive, null, null, 1L,
            sources = SRC_REQUEST,
        )
        val fromPage = MediaSniffer.Hit(
            "https://cdn/a/index.m3u8", MediaSniffer.Kind.Master, null, null, 2L,
            sources = SRC_REQUEST or SRC_PAGE,
        )
        // 请求流里那条虽然是单文件，但页面没承认在用它 —— 选页面报的那条
        assertEquals(fromPage, CinemaProbe.bestOf(listOf(fromRequest, fromPage)))
        assertEquals(fromRequest, CinemaProbe.bestOf(listOf(fromRequest)))
        assertNull(CinemaProbe.bestOf(emptyList()))
        assertNull(
            CinemaProbe.bestOf(
                listOf(
                    MediaSniffer.Hit(
                        "https://cdn/hls/seg-1.ts", MediaSniffer.Kind.Segment, null, null, 1L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `展示时截断签名串，否则一屏全是 token`() {
        val long = "https://cdn.example.com/a/index.m3u8?sign=" + "x".repeat(120)
        val short = MediaSniffer.shorten(long, 40)
        assertTrue(short.length <= 40)
        assertTrue(short.endsWith("…"))
        // # 片段只是前端路由，截掉不丢信息
        assertTrue(
            MediaSniffer.shorten("https://a.com/x.mp4#t=10,20", 96) == "https://a.com/x.mp4",
        )
    }

    @Test
    fun `EME 每种状态都翻译成一句人话，不让人再猜`() {
        assertTrue(
            CinemaProbe.describeEme(null).contains("还没探到"),
        )
        assertTrue(
            CinemaProbe.describeEme(CinemaProbe.EmeReport(state = "ok", keySystem = "com.widevine.alpha"))
                .contains("可用"),
        )
        assertTrue(
            CinemaProbe.describeEme(CinemaProbe.EmeReport(state = "no-api", api = false))
                .contains("没带 DRM 栈"),
        )
        assertTrue(
            CinemaProbe.describeEme(CinemaProbe.EmeReport(state = "fail", detail = "NotSupportedError"))
                .contains("NotSupportedError"),
        )
    }
}
