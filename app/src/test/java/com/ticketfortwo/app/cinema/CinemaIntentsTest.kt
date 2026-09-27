package com.ticketfortwo.app.cinema

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「从分享文本里挑链接」的中文标点回归。
 *
 * 起因：微信/QQ 的分享文案习惯贴着链接写中文标点（`…xxx。`、`…，快`、`（url）`），
 * 原实现只在事后剥 ASCII 的 `. , ;`，带 `。`/`，` 的链接会被整段带进 WebView
 * 按 UTF-8 编码请求 → 404（REVIEW-2026-09-27 P2）。现在字符集直接在正则里
 * 截断中文标点，末尾 ASCII 标点再由 trimUrlTail 兜一层。
 */
class CinemaIntentsTest {

    @Test
    fun `链接后面贴着中文标点时截在链接处`() {
        assertEquals(
            "https://www.yjllq.com/#home",
            firstUrlIn("一起看这个 https://www.yjllq.com/#home。"),
        )
        // 中间夹着"，快"：字符集必须在 ， 处截断，trimEnd 救不了这种
        assertEquals(
            "https://b23.tv/abc123",
            firstUrlIn("速看！https://b23.tv/abc123，快"),
        )
        assertEquals(
            "https://a.com/b",
            firstUrlIn("（https://a.com/b）"),
        )
        assertEquals(
            "https://a.com/b",
            firstUrlIn("https://a.com/b、"),
        )
        // 末尾 ASCII 标点由 trimUrlTail 兜（字符集不排除 ASCII . , ; —— 它们是合法 URL 字符）
        assertEquals(
            "https://a.com/b",
            firstUrlIn("看 https://a.com/b."),
        )
    }

    @Test
    fun `ASCII 括号是合法 URL 字符不截断`() {
        assertEquals(
            "https://en.wikipedia.org/wiki/Foo_(bar)",
            firstUrlIn("看这个 https://en.wikipedia.org/wiki/Foo_(bar)"),
        )
    }

    @Test
    fun `普通聊天没有链接返回 null`() {
        assertNull(firstUrlIn("没有任何链接的普通聊天"))
        assertNull(firstUrlIn(""))
    }
}
