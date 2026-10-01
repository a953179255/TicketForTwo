package com.ticketfortwo.app.watch

import kotlin.math.abs

/**
 * 房主手机上、同看 WebView 里那个播放器的当前状态。
 *
 * 只有房主侧会真播；观众侧是这份状态的**只读镜像**（外加一条控制指令通道）。
 */
data class WatchState(
    val url: String = "",
    /** 页面上到底有没有 <video>。没有的时候观众那侧得能说清"这页不是播放器"。 */
    val found: Boolean = false,
    val posMs: Long = 0,
    val durMs: Long = 0,
    val playing: Boolean = false,
    val title: String = "",
)

/** 观众发过来的播放控制指令。 */
sealed class WatchCmd {
    data object Play : WatchCmd()
    data object Pause : WatchCmd()
    data class Seek(val posMs: Long) : WatchCmd()
    data class Step(val deltaMs: Long) : WatchCmd()

    fun label(): String = when (this) {
        Play -> "继续播放"
        Pause -> "暂停"
        is Seek -> "跳到 ${WatchSync.formatTime(posMs)}"
        is Step -> (if (deltaMs >= 0) "快进 " else "快退 ") + WatchSync.formatTime(abs(deltaMs))
    }
}

/**
 * 同看的"协议层"：状态怎么在两台机器之间传、指令怎么变成注入给网页的 JS。
 *
 * 这里刻意做成**不碰 Android API 的纯函数**，理由有两个：
 *  1. 能单测。注入 JS 的字符串拼接和字段解析是这个功能最容易悄悄坏掉的地方
 *     （少一个字段、多一个竖线，观众那侧就永远显示"没找到播放器"），
 *     而这些逻辑放在 Activity/WebView 里就测不到。
 *  2. 传输通道是**我们自己那台手机上跑的 WebSocket**，不是服务器，
 *     所以格式必须两边都能独立演进 —— 老观众收到不认识的字段要能忽略。
 *
 * 线上格式（外层 JSON 由 CallSession/ViewerSession 用 org.json 包，这里只管 f 字段）：
 *   房主 → 观众  `{"t":"watch","f":"found|pos|dur|playing|allow|title"}`
 *   观众 → 房主  `{"t":"wcmd","f":"act|arg"}`
 * 用竖线而不是再套一层 JSON：这两条消息每秒都要编解码一次，字段固定、
 * 值全是数字（title 除外，且 [sanitize] 会把竖线替掉），扁平格式最不容易出错，
 * 网页端一行 split 就能解析。
 */
object WatchSync {

    const val SEP = '|'

    /** 一次快进/快退的最大幅度：超过两分钟的"跳"只可能是误触或攻击，砍掉。 */
    val MAX_STEP_MS: Long get() = SyncProto.MAX_STEP_MS

    /** 标题是网页里抓的，可能带换行、竖线、引号 —— 洗法只有一份，见 [SyncProto.sanitize]。 */
    fun sanitize(title: String): String = SyncProto.sanitize(title)

    /** 房主侧：把状态 + "是否允许对方控制"打包成 f 字段。 */
    fun stateFields(state: WatchState, allow: Boolean): String = buildString {
        append(if (state.found) 1 else 0).append(SEP)
        append(state.posMs).append(SEP)
        append(state.durMs).append(SEP)
        append(if (state.playing) 1 else 0).append(SEP)
        append(if (allow) 1 else 0).append(SEP)
        append(sanitize(state.title))
    }

    /** 观众侧：解 f 字段。解不出来返回 null（调用方按"没有同看"处理，不崩）。 */
    fun parseState(raw: String?): Pair<WatchState, Boolean>? {
        val parts = raw?.split(SEP) ?: return null
        if (parts.size < 5) return null
        val state = WatchState(
            found = parts[0] == "1",
            posMs = parts[1].toLongOrNull() ?: return null,
            durMs = parts[2].toLongOrNull() ?: return null,
            playing = parts[3] == "1",
            title = if (parts.size > 5) parts.subList(5, parts.size).joinToString(SEP.toString()) else "",
        )
        return state to (parts[4] == "1")
    }

    fun cmdFields(act: String, arg: Long = 0L): String = "$act$SEP$arg"

    /** 观众侧解指令；act 不在白名单里一律不要，免得将来加动作时把老房主打崩。 */
    fun parseCmd(raw: String?): WatchCmd? {
        val parts = raw?.split(SEP) ?: return null
        if (parts.isEmpty()) return null
        return when (parts[0]) {
            "play" -> WatchCmd.Play
            "pause" -> WatchCmd.Pause
            "seek" -> parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0)?.let { WatchCmd.Seek(it) }
            "step" -> parts.getOrNull(1)?.toLongOrNull()
                ?.coerceIn(-MAX_STEP_MS, MAX_STEP_MS)?.let { WatchCmd.Step(it) }
            else -> null
        }
    }

    /** 权限闸门单独成一个函数：这是"观众能不能动房主手机"唯一的判定点，必须可测。 */
    fun accept(allowViewerControl: Boolean, cmd: WatchCmd?): WatchCmd? =
        SyncProto.accept(allowViewerControl, cmd)

    // ---- 注入给 WebView 的 JS ---------------------------------------------
    //
    // 只走 evaluateJavascript（Kotlin 主动问 / 主动下命令），**不用 addJavascriptInterface**：
    // 后者会把 Kotlin 对象暴露成 window 上的方法，网页里任何一段脚本都能调，
    // 这是 WebView 安全史上出过最多 CVE 的那类接口。我们只需要"读几个属性、写 currentTime"，
    // 用不到那种能力。
    //
    // 选"哪个 video"：按渲染面积取最大的那个。多视频页（预览小窗、广告位）里，
    // 面积最大的几乎一定是主播放器；querySelector('video') 取第一个则经常选中广告。

    /** 在页面里找出主 <video> 的那段表达式，探针和指令共用。 */
    private const val FIND =
        "(function(){var vs=Array.prototype.slice.call(document.querySelectorAll('video'));" +
            "if(!vs.length)return null;" +
            "vs.sort(function(a,b){return (b.clientWidth*b.clientHeight)-(a.clientWidth*a.clientHeight);});" +
            "return vs[0];})()"

    /**
     * 状态探针。返回竖线分隔的扁平串：`found|pos|dur|playing|title`。
     *
     * 不用 JSON.stringify 是因为 evaluateJavascript 会把返回值再 JSON 编码一层，
     * 双层转义在 Kotlin 侧解起来很容易错；这里把标题里的分隔字符全替掉，
     * 一层 trim 引号就够。
     */
    fun probeJs(): String =
        "(function(){var v=$FIND;" +
            "if(!v)return '0|0|0|0|';" +
            "function n(x){return (isFinite(x)&&x>0)?Math.round(x*1000):0;}" +
            "var t=(document.title||'').replace(/[|\"'\\\\\\r\\n\\t]/g,' ').slice(0,60);" +
            "return '1|'+n(v.currentTime)+'|'+n(v.duration)+'|'+(v.paused?0:1)+'|'+t;})()"

    /** 解 [probeJs] 的返回（evaluateJavascript 给的是带引号的 JSON 串）。 */
    fun parseProbe(value: String?, url: String = ""): WatchState? {
        val raw = value?.trim()?.removeSurrounding("\"") ?: return null
        val parts = raw.split(SEP)
        if (parts.size < 4) return null
        return WatchState(
            url = url,
            found = parts[0] == "1",
            posMs = parts[1].toLongOrNull() ?: return null,
            durMs = parts[2].toLongOrNull() ?: return null,
            playing = parts[3] == "1",
            title = if (parts.size > 4) parts.subList(4, parts.size).joinToString(SEP.toString()) else "",
        )
    }

    /** 把一条指令变成要 evaluate 的 JS。 */
    fun jsFor(cmd: WatchCmd, posMs: Long, durMs: Long): String = when (cmd) {
        WatchCmd.Play -> "(function(){var v=$FIND;if(v)v.play();})()"
        WatchCmd.Pause -> "(function(){var v=$FIND;if(v)v.pause();})()"
        // Seek 的落点在这里再夹一次：观众看到的进度是**上一次广播**的，
        // 房主这边可能已经播过去了，直接按观众给的秒数跳会跳过头。
        // 但"跳到哪"是观众的明确意图，所以只做上下界，不做"就近吸附"。
        is WatchCmd.Seek ->
            "(function(){var v=$FIND;if(!v)return;" +
                "var d=isFinite(v.duration)?v.duration:0;" +
                "var t=${cmd.posMs / 1000.0};if(d>0&&t>d)t=d;if(t<0)t=0;" +
                "v.currentTime=t;v.play();})()"
        /* Step 用**相对位移**，落点在页面里算：Kotlin 手里的 posMs 是 2 秒前的
           探针/广播快照，拿它换算绝对位置会让连点两次 +10 第二次落在同一处
           （REVIEW-2026-09-27 P2）。夹紧 [0, dur] 与原 stepTarget 换算语义一致；
           时长未知（直播）时只防负。 */
        is WatchCmd.Step ->
            "(function(){var v=$FIND;if(!v)return;" +
                "var d=isFinite(v.duration)?v.duration:0;" +
                "var t=v.currentTime+${cmd.deltaMs / 1000.0};" +
                "if(d>0&&t>d)t=d;if(t<0)t=0;" +
                "v.currentTime=t;v.play();})()"
    }

    /**
     * 把网页播放器拨到某倍速 —— **房主本机手势专用，不进协议**。
     *
     * 观众端的倍速跟随走的是放映广播里的 `State.rate` 字段（协议里本来就有），
     * 不需要新增指令；这条 JS 只负责"房主自己这屏的网页"在兜底态跟上手势。
     */
    fun rateJs(rate: Double): String =
        "(function(){var v=$FIND;if(v)v.playbackRate=$rate;})()"

    /** mm:ss / h:mm:ss —— 两端进度显示共用，避免同一秒在两边写成不同样子。 */
    fun formatTime(ms: Long): String = SyncProto.formatTime(ms)
}
