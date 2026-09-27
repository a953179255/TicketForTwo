package com.ticketfortwo.app.cinema

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「递链接」入口：别的浏览器 → 系统分享 → 双人票放映厅。
 *
 * 为什么用系统分享而不是自己做"从剪贴板读链接"：
 * 读剪贴板在 Android 12+ 会弹提示、Android 13+ 非前台应用读不到，而且"打开 App 再粘"
 * 本来就是要消灭的那一步。分享菜单是**用户已经会用的动作**，我们只要接住。
 *
 * 冷启动走 [fromActivity]（onCreate 时 intent 已经在 Activity 上），
 * App 已开着时走 [onNewIntent] → [push]（MainActivity 是 singleTop）。
 */
object CinemaIntents {

    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun push(url: String?) {
        val u = url?.trim().orEmpty()
        if (u.isNotEmpty()) _pending.value = u
    }

    fun consume() {
        _pending.value = null
    }

    /** 冷启动：从 Activity 当前持有的 intent 里捞一次。 */
    fun fromActivity(activity: Activity?): String? = extractSharedUrl(activity?.intent)
}

/**
 * 从 intent 里取出那条链接。认两种：
 * 1. `ACTION_SEND` + `text/plain`（系统分享面板）；
 * 2. `--es url`（调试与将来的深链：`adb shell am start -a SEND ...`）。
 *
 * 分享文本里经常混着页面标题和一堆 URL（微信、QQ 的分享格式各不相同），
 * 所以不是"整段当地址"，而是**挑第一条像 http(s) 的**；顺手把 `#` 后面的
 * 前端路由片段留着 —— 很多单页应用的片名就挂在那儿。
 */
fun extractSharedUrl(intent: Intent?): String? {
    if (intent == null) return null
    intent.getStringExtra("url")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (intent.action != Intent.ACTION_SEND) return null
    if (intent.type != null && !intent.type!!.startsWith("text/")) return null
    val raw = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim() ?: return null
    firstUrlIn(raw)?.let { return it }
    // 没有 http 开头的就整段交给用户自己判断：可能是"bilibili.com/video/BV…"这种省了协议的
    return raw.trimUrlTail().takeIf { it.contains(".") && !it.contains(" ") }
}

/**
 * 只认 http(s)。字符集**直接排除中文标点**：它们不可能合法地出现在未编码的 URL 里，
 * 而聊天文案习惯贴着链接写（`…abc123，快`、`…/v）`）—— 不在正则里截断，
 * 光靠事后 trim 只救得了"标点在末尾"的情况（REVIEW-2026-09-27 P2）。
 * ASCII 的 `)` **不排除** —— 它是合法 URL 字符（`/wiki/Foo_(bar)`）。
 */
private val URL_IN_TEXT = Regex(
    """https?://[^\s"'<>。，；：！？、）】》」』]+""",
    RegexOption.IGNORE_CASE,
)

/**
 * 再剥一层尾巴标点（正则没截到的末尾 ASCII 标点：`. , ;` 等）。
 * 全角与中文标点通常已被上面的字符集挡掉，留着是双保险。
 */
private fun String.trimUrlTail(): String =
    trimEnd { it in ".,;:!?，。；：！？、）】》」』\"'" }

fun firstUrlIn(text: String): String? =
    URL_IN_TEXT.find(text)?.value?.trimUrlTail()
