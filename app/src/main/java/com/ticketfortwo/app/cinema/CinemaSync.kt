package com.ticketfortwo.app.cinema

import com.ticketfortwo.app.watch.SyncProto

/**
 * 放映厅的同步协议 —— **纯逻辑，不碰 Android、不碰网络**，所以能在 JVM 里跑单测。
 *
 * 线上格式沿用这个仓库既有的口径：外层是 JSON，业务字段挤在一条 `f` 里用 `|` 分隔。
 * 这样信令服务器（`SignalHub` / `WsClient`）完全不用懂业务，加一种消息只加一个分支。
 *
 * 房主是权威：房主报"我在放哪条、第几毫秒、放没放"，观众把自己投影出来的时间轴回报，
 * 两边都往房主那条轴靠。抄的是 synctv / SyncWatch / couple-cinema 三家收敛出的同一套：
 *
 *   - 对钟：`generatedAt` 是房主发出那一刻的墙钟，观众用它换算本地对应时刻；
 *   - 小偏差用倍速悄悄追（0.94~1.06），大偏差才 seek —— 频繁 seek 比漂移更难受；
 *   - `version` 单调递增，观众据此丢掉过期状态（乱序到达的信令很常见）；
 *   - **回声抑制**：刚执行过一条指令的短暂窗口内，不要把"因此变化了的状态"再当成新事实回灌。
 */
object CinemaSync {

    /** 一条媒体。`kind` 决定观众端怎么播：直链交给 `<video>`，m3u8 要走 hls.js。 */
    data class Track(
        val url: String,
        val kind: String,
        val title: String,
        val durationMs: Long = 0L,
    )

    /** 房主侧的放映状态（观众看到的是它的投影）。 */
    data class State(
        val track: Track,
        val posMs: Long,
        val durMs: Long,
        val playing: Boolean,
        val rate: Double = 1.0,
        /** 房主发出这条时的墙钟（毫秒）。观众用它换算"现在该到哪儿了"。 */
        val hostWallMs: Long = System.currentTimeMillis(),
        /** 单调递增的版本号，用来丢弃乱序到达的旧状态。 */
        val version: Long = 0L,
    )

    /** 观众发回来的控制请求。`Seek` 的绝对值由房主裁，观众只表达意图。 */
    sealed class Cmd {
        object Play : Cmd()
        object Pause : Cmd()
        data class Seek(val ms: Long) : Cmd()
        data class Step(val deltaMs: Long) : Cmd()

        fun label(): String = when (this) {
            Play -> "继续播放"
            Pause -> "暂停"
            is Seek -> "跳到 ${CinemaSync.formatTime(ms)}"
            is Step -> if (deltaMs >= 0) "快进 ${deltaMs / 1000} 秒" else "后退 ${-deltaMs / 1000} 秒"
        }
    }

    /**
     * 单次步进的天花板：观众手滑连点也不该一下跳到片尾。
     * 值只有一份，住在 [SyncProto]（watch 那套用的是同一个）。
     */
    val MAX_STEP_MS: Long get() = SyncProto.MAX_STEP_MS

    /** 偏差超过这个数才 seek（synctv 用 1.2s，couple-cinema 用 1.8s，取更敏感的）。 */
    const val SEEK_THRESHOLD_MS = 1_200L

    /** 低于这个数就当噪声，什么都不做，免得和网页自己的抖动打架。 */
    const val DEADBAND_MS = 120L

    /** 倍速追的上下限：SyncWatch 用 0.94~1.06，这里照抄。 */
    const val MIN_RATE = 0.94
    const val MAX_RATE = 1.06

    /** 回声抑制窗口：执行完一条指令后这么久之内，不把由它引起的状态变化当新事实。 */
    const val ECHO_WINDOW_MS = 800L

    private const val SEP = "|"

    /** 标题是网页里抓的，可能带换行、竖线、引号，必须先洗再拼（实现见 [SyncProto.sanitize]）。 */
    fun sanitize(s: String?): String = SyncProto.sanitize(s)

    /**
     * 房主 → 观众。
     *
     * `allow` 是"方向盘给不给对方"，跟着状态一起发而不是单独一条 ——
     * 观众端任何一次刷新都要能立刻知道当前权限，少一条消息就少一个不一致的窗口。
     */
    fun fields(st: State, allow: Boolean): String = listOf(
        st.version.toString(),
        /* URL 是唯一没过 sanitize 的字段，而分隔符就是 `|`：真实片源里带竖线的
           不罕见（测试集里就有 2026-09-25 模拟器真抓到的 bilibili 地址）。
           不转义的话两端全错位 —— 观众解析出 11 段、pos 落在标题上，放映状态
           整条作废（REVIEW-2026-09-27 P1）。%7C 是 URL 里的标准写法，
           浏览器/服务器解码后与 `|` 等价，播放不受影响。 */
        st.track.url.replace("|", "%7C"),
        st.track.kind,
        sanitize(st.track.title),
        st.posMs.toString(),
        st.durMs.toString(),
        (if (st.playing) "1" else "0"),
        st.rate.toString(),
        st.hostWallMs.toString(),
        if (allow) "1" else "0",
    ).joinToString(SEP)

    /** 解析失败返回 null：宁可不更新，也不要拿半条脏数据把观众端带偏。 */
    fun parseState(f: String): State? {
        val p = f.split(SEP)
        // 必须恰好 10 段：多出来的只可能来自没转义的 `|`（旧版本房主），放行等于
        // 拿错位的字段当真值（< 10 放行 11+ 时 p[4] 其实是标题）。
        if (p.size != 10) return null
        val url = p[1]
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        return runCatching {
            State(
                track = Track(
                    url = url,
                    kind = p[2].ifEmpty { "unknown" },
                    title = p[3],
                    durationMs = p[5].toLong(),
                ),
                version = p[0].toLong(),
                posMs = p[4].toLong(),
                durMs = p[5].toLong(),
                playing = p[6] == "1",
                rate = p[7].toDoubleOrNull() ?: 1.0,
                hostWallMs = p[8].toLong(),
            )
        }.getOrNull()
    }

    fun allowsControl(f: String): Boolean = f.split(SEP).getOrNull(9) == "1"

    /** 观众 → 房主。 */
    fun cmdFields(c: Cmd): String = when (c) {
        Cmd.Play -> "play"
        Cmd.Pause -> "pause"
        is Cmd.Seek -> "seek$SEP${c.ms.coerceAtLeast(0L)}"
        is Cmd.Step -> "step$SEP${c.deltaMs.coerceIn(-MAX_STEP_MS, MAX_STEP_MS)}"
    }

    fun parseCmd(f: String): Cmd? {
        val a = f.split(SEP)
        return when (a.firstOrNull()) {
            "play" -> Cmd.Play
            "pause" -> Cmd.Pause
            "seek" -> a.getOrNull(1)?.toLongOrNull()?.let { Cmd.Seek(it.coerceAtLeast(0L)) }
            "step" -> a.getOrNull(1)?.toLongOrNull()
                ?.let { Cmd.Step(it.coerceIn(-MAX_STEP_MS, MAX_STEP_MS)) }
            else -> null
        }
    }

    /**
     * 房主是否接受这条指令。
     *
     * 单独抽出来是因为这是个**安全边界**：`allow` 关掉之后，任何播放控制都不该生效。
     * 这条判断如果散在 UI 里，迟早有一处漏掉。
     */
    fun accept(allow: Boolean, cmd: Cmd?): Cmd? = SyncProto.accept(allow, cmd)

    /** 步进的目标位置，钳在 [0, dur] 里（实现见 [SyncProto.stepTarget]）。 */
    fun stepTarget(posMs: Long, durMs: Long, deltaMs: Long): Long =
        SyncProto.stepTarget(posMs, durMs, deltaMs)

    /**
     * 把房主那一刻的状态投影到"观众此刻应该在的第几毫秒"。
     *
     * 关键在 `ageMs`：状态到达观众手上已经过了 RTT/2 + 处理时间，
     * 播放中这段时间又走过去了 —— 不减掉它，观众会**系统性落后半秒到一秒**，
     * 而且网络越差落后越多，看起来就像"我这边总是慢半拍"。
     */
    fun projectedPos(st: State, ageMs: Long): Long {
        val age = ageMs.coerceAtLeast(0L)
        return if (st.playing) st.posMs + (age * st.rate).toLong() else st.posMs
    }

    /** 偏差 = 该在哪 - 实际在哪。正数表示观众落后了，要往前追。 */
    fun drift(st: State, localPosMs: Long, ageMs: Long): Long =
        projectedPos(st, ageMs) - localPosMs

    /** 要不要 seek。带符号进来，按绝对值判。 */
    fun shouldSeek(driftMs: Long): Boolean = kotlin.math.abs(driftMs) > SEEK_THRESHOLD_MS

    /**
     * 小偏差用倍速追。返回 1.0 表示什么都不做。
     *
     * 系数 0.07 是照抄 SyncWatch：落后 1 秒 → 1.07 倍速，约 15 秒内悄悄补完，
     * 观众察觉不到，但比"每两秒跳一次"舒服得多。
     */
    fun rateWarp(driftMs: Long): Double {
        if (kotlin.math.abs(driftMs) <= DEADBAND_MS) return 1.0
        return (1.0 + driftMs / 1000.0 * 0.07).coerceIn(MIN_RATE, MAX_RATE)
    }

    /**
     * 回声抑制器。
     *
     * 问题场景：观众按 +10 → 房主执行 → 房主把新位置广播回来 → 观众 seek →
     * 观众的 seek 又触发一条"我动了"的上报 → 房主以为有新事实……
     * couple-cinema 用一个 `applying` 计数器 + 700ms 衰减，synctv 用 `_isSyncing` 800ms
     * 加 `clientOperationId`。这里取前者那种最小实现：**窗口内的一律当回声**。
     */
    class EchoGuard(private val windowMs: Long = ECHO_WINDOW_MS) {
        private var untilMs = 0L
        private var applied = 0

        /** 刚执行了一条由对端引起的动作，开一个窗口。 */
        fun markApplied(nowMs: Long) {
            untilMs = nowMs + windowMs
            applied++
        }

        fun inEcho(nowMs: Long): Boolean = nowMs < untilMs

        /** 窗口过期就归零计数，免得"我一共抑制过多少次"变成只增不减的误导数字。 */
        fun resetIfStale(nowMs: Long) {
            if (nowMs >= untilMs) applied = 0
        }

        fun appliedCount(): Int = applied
    }

    /**
     * 观众对"这条我这边放不放得出来"的回执。
     *
     * 为什么单独建一个类型而不是把字符串原样存下来：命中率要能算。
     * `ok` 只有一条来源（首帧真的出来了），其余全按失败分类计数 ——
     * 嗅到地址不等于对方能看到，这个差别就是 S 档和 A 档的分界线。
     *
     * 为什么带 [version]：回执是异步的，房主可能在等回执的时候换了片。
     * 不带版本号就会把"上一条的失败"显示在新的一条上面，那比不报更糟。
     */
    data class PlaybackAck(
        val version: Long,
        val ok: Boolean,
        val code: String,      // ok / badurl / unsupported / fetch / stream / start / timeout / other
        val detail: String,    // 给人看的那句，可能为空
    )

    /**
     * `2|ok|ok|1920x1080`、`5|fail|timeout|这条流 8 秒没出画面`。
     *
     * 首字段不是数字、或者头一个词不是 ok/fail，一律返回 null 当没收到 ——
     * 回执是锦上添花的通道，不能因为它格式不对就把信令流带崩。
     */
    fun parseAck(f: String?): PlaybackAck? {
        if (f.isNullOrBlank()) return null
        val parts = f.split('|')
        val ver = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
        val head = parts.getOrNull(1)?.trim()?.lowercase() ?: return null
        if (head != "ok" && head != "fail") return null
        return PlaybackAck(
            version = ver,
            ok = head == "ok",
            code = parts.getOrNull(2)?.trim()?.ifEmpty { "other" } ?: "other",
            detail = parts.drop(3).joinToString("|"),
        )
    }

    /**
     * 这条回执是不是还能采信：未知版本（-1）一律采信，其余必须和当前放映的那条对得上。
     */
    fun isFreshAck(ack: PlaybackAck, currentVersion: Long?): Boolean =
        ack.version < 0 || currentVersion == null || ack.version == currentVersion

    /** 卡片上那行话的语气（颜色由 UI 决定，这里只管措辞和轻重）。 */
    enum class AckTone { Neutral, Waiting, Live, Warn, Bad }

    /**
     * 卡片上的回执那一行：[head] 永远只占一行，长话放 [detail] 另起一行。
     *
     * 为什么拆开：合成一句时，App 观众那句"App 内不放原画，厅里只有语音…"会
     * 在标题行里换行，把"正在放映"挤到第二行的位置上（截图实测到的重叠）。
     * 标题行的长度必须由我们控制，长内容一律下沉到第二行。
     */
    data class AckLine(val head: String, val detail: String?, val tone: AckTone)

    /**
     * 把"对方到底看得怎么样"翻成房主界面上的一行话。
     *
     * 单独成一个函数是为了能被单测打到：这行字是整个放映厅里最容易骗人的一行 ——
     * 五种情况（没人、在等、播起来了、用 App 看、放不出来）必须各有各的说法，
     * 少一种就会出现"对方一片黑、房主一脸笃定"。
     */
    fun describeAck(
        ack: PlaybackAck?,
        viewerOnline: Boolean,
        timedOut: Boolean,
    ): AckLine = when {
        !viewerOnline -> AckLine("对方还没进厅", null, AckTone.Neutral)
        ack == null && timedOut ->
            AckLine("没等到对方的回执", "可以收厅改共享屏幕", AckTone.Warn)
        ack == null -> AckLine("等对方那边出画面…", null, AckTone.Waiting)
        // 只报对方**真的**播到了多少像素，不写"原生画质"：
        // hls.js 起播会从最低档往上爬，实测出现过"对方已播起来 · 原生画质 224x100"
        // 这种自相矛盾的一行 —— 数字才是证据，形容词不是。
        ack.ok -> AckLine("对方已播起来 · ${ack.detail.ifBlank { "首帧已到" }}", null, AckTone.Live)
        // App 内的观众不是"放不出来"，是这条路他没走 —— 别报成故障。
        // 他到底在看什么由他自己报（有没有画面进来只有他知道）：厅先开那条路
        // 根本不投屏，这时写死"走的是屏幕分享"就是第二句假话。
        ack.code == "appviewer" -> AckLine(
            "对方在 App 里",
            ack.detail.ifBlank { "看的是屏幕分享" },
            AckTone.Neutral,
        )
        /* 观众那边只是等一次点击（手机浏览器不许网页自己放出声音）。
           这既不是"放不出"也不是"没回执" —— 报成红色会把人支去查网络，
           而正确的做法就一个字：点。手机上实测到第一条片源必然走这条路。 */
        ack.code == "gesture" -> AckLine(
            "对方点一下就开始播",
            ack.detail.takeIf { it.isNotBlank() },
            AckTone.Waiting,
        )
        else -> AckLine(
            "对方放不出这条",
            ack.detail.ifBlank { ack.code },
            AckTone.Bad,
        )
    }

    /** mm:ss / h:mm:ss，与同看那边同一个实现（恒 ASCII），见 [SyncProto.formatTime]。 */
    fun formatTime(ms: Long): String = SyncProto.formatTime(ms)
}
