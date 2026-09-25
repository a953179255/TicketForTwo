package com.ticketfortwo.app.cinema

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

    /** 单次步进的天花板：观众手滑连点也不该一下跳到片尾。 */
    const val MAX_STEP_MS = 120_000L

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

    /** 标题是网页里抓的，可能带换行、竖线、引号，必须先洗再拼。 */
    fun sanitize(s: String?): String =
        (s ?: "").replace(Regex("[|\"'\\\\\r\n\t]"), " ").trim().take(60)

    /**
     * 房主 → 观众。
     *
     * `allow` 是"方向盘给不给对方"，跟着状态一起发而不是单独一条 ——
     * 观众端任何一次刷新都要能立刻知道当前权限，少一条消息就少一个不一致的窗口。
     */
    fun fields(st: State, allow: Boolean): String = listOf(
        st.version.toString(),
        st.track.url,
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
        if (p.size < 10) return null
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
    fun accept(allow: Boolean, cmd: Cmd?): Cmd? = if (allow && cmd != null) cmd else null

    /** 步进的目标位置，钳在 [0, dur] 里。 */
    fun stepTarget(posMs: Long, durMs: Long, deltaMs: Long): Long =
        (posMs + deltaMs.coerceIn(-MAX_STEP_MS, MAX_STEP_MS)).coerceAtLeast(0L)
            .let { if (durMs > 0) it.coerceAtMost(durMs) else it }

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

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "0:00"
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}
