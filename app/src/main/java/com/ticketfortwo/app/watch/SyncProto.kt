package com.ticketfortwo.app.watch

/**
 * watch 与 cinema 两套同步协议**共用的那几个原语**：[sanitize] / [stepTarget] /
 * [accept] / [MAX_STEP_MS] / [formatTime]。
 *
 * 为什么抽出来：这两套协议其实是在解同一件事（同看 = 观众遥控房主的播放器；
 * 放映厅 = 观众本地播同一条片源，只对时间轴），字段编解码各留一份是对的——
 * 线上格式不一样——但这几个"标题怎么洗、步进落在哪、方向盘给不给、进度写成什么样"
 * 的函数原本逐字双写，而且**已经悄悄分叉**：`formatTime` 两边各写一版（一边
 * `"%02d".format` 跟着默认 locale 走，一边打算拼字符串），`stepTarget` 一边先钳
 * 步进上限一边不钳。两端算法一漂，同一步进在两个界面就会显示成两个时间，
 * 这类偏差最难查。`Cmd.label()` 的**措辞**不在此列 —— 那是给人看的展示文案，
 * 两边按各自场景写，不动。
 *
 * 两端（[WatchSync] 与 `cinema.CinemaSync`）的公开签名**一个都没变**，只是改成
 * 薄委托到这一个实现上，所以 WatchSyncTest / CinemaSyncTest 一行都不用改。
 *
 * 这里只管"值怎么算"，**不碰线上格式**（分隔符、字段序、URL 的 `%7C` 转义）——
 * 那些仍然各自待在两套协议的 `stateFields` / `fields` 里。
 */
object SyncProto {

    /** 单次步进的天花板：观众手滑连点也不该一下跳到片尾。 */
    const val MAX_STEP_MS = 120_000L

    /** 标题是网页里抓的，可能带换行、竖线、引号，必须先洗再拼（两端同一套字符）。 */
    fun sanitize(s: String?): String =
        (s ?: "").replace(Regex("[|\"'\\\\\r\n\t]"), " ").trim().take(60)

    /**
     * 步进的落点：先钳 ±[MAX_STEP_MS]，再夹进 `[0, dur]`。
     *
     * 先钳 delta 不只是"砍掉误触"——`posMs + deltaMs` 直接相加的话，
     * 观众手滑发个 `Long.MAX_VALUE` 级的步进会溢出成负数，落点反而跳到片头。
     * 时长未知（直播 / 还没读到 metadata）时只保证不为负。
     */
    fun stepTarget(posMs: Long, durMs: Long, deltaMs: Long): Long =
        (posMs + deltaMs.coerceIn(-MAX_STEP_MS, MAX_STEP_MS)).coerceAtLeast(0L)
            .let { if (durMs > 0) it.coerceAtMost(durMs) else it }

    /**
     * 权限闸门：`allow` 关掉之后，任何播放控制都不该生效 —— 这是"观众能不能动
     * 房主手机"唯一的判定点，散在各处迟早漏一处，所以只写这一遍。
     *
     * 泛型是因为两端的指令类型不同（`WatchCmd` / `CinemaSync.Cmd`），
     * 而判定本身跟类型无关。两端各自的 `accept` 保持原来的具名签名再委托进来。
     */
    fun <T> accept(allow: Boolean, cmd: T?): T? = if (allow && cmd != null) cmd else null

    /**
     * mm:ss / h:mm:ss —— 两端进度显示共用，避免同一秒在两边写成不同样子。
     *
     * **恒 ASCII 手拼，不用 `"%02d".format`**：`String.format` 跟着 JVM 默认 locale
     * 走，阿拉伯语/波斯语环境会把 `1:05` 写成本地数字 —— 同一条进度在两台手机上
     * 就成了两种写法，而这行字的意义恰恰是"两边得一模一样"。
     */
    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "0:00"
        val total = ms / 1000
        val s = total % 60
        val m = (total / 60) % 60
        val h = total / 3600
        fun two(n: Long): String = if (n < 10) "0$n" else n.toString()
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }
}
