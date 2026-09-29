package com.ticketfortwo.app.cinema

import android.content.Context
import android.content.Intent

/**
 * 一次浮窗播放的请求。
 *
 * [referer]/[cookie]/[userAgent] 不是可选项：很多站点的视频地址带防盗链，
 * 浮窗自己拿（不带这些"身份信息"）拿到的是空数据或 403 —— 必须带上嗅探时
 * 从 WebView 请求头里记下来的同一份（见 docs/references/yjllq-float-window.md）。
 */
data class FloatRequest(
    val url: String,
    val title: String = "",
    val referer: String? = null,
    val cookie: String? = null,
    val userAgent: String? = null,
    val startPosMs: Long = 0L,
    /**
     * 其余候选地址（嗅探到的同页多条 .m3u8）。
     * 页面嗅探常常拿到的是**变体清单**（master 里的第一档）—— 从它看不出码率档位，
     * 所以把全部候选都带上：服务端逐个试，谁是 master 就挑它的低档变体
     * （浮窗最宽 725px，1080p 分片在慢网下要下 8 秒，480p 只要 2 秒）。
     */
    val candidates: List<String> = emptyList(),
)

/**
 * 浮窗的当前状态。**这是房主侧第二个可能的进度源** —— 谁在播、进度听谁的，
 * 由 CinemaScreen 的"单一指挥官"逻辑决定（见 CinemaScreen 里的 commander 注释）。
 */
data class FloatState(
    val active: Boolean = false,
    val url: String? = null,
    val title: String = "",
    val playing: Boolean = false,
    val posMs: Long = 0L,
    val durMs: Long = 0L,
    /** 视图临时藏起来（正在分享屏幕时）—— 播放与进度**照旧**，只是不显示。 */
    val hidden: Boolean = false,
    /**
     * 元数据已到且真的在播。
     * 交接的判据就是这个：不能刚调了 play() 就认为它起来了（HLS 常常要缓冲一两秒），
     * 否则会出现"网页那个已停、浮窗这个还没起"的空白，观众收到假暂停。
     */
    val ready: Boolean = false,
    val error: String? = null,
)

/**
 * 浮窗播放器的门面：Compose 只读 [state]，操作走这里转发给服务。
 *
 * 服务和界面同进程，所以直接抓服务实例调方法，不走 IPC —— 指令延迟是
 * "这一帧"，交接判断不必等跨进程回调。
 */
object FloatPlayer {

    private val _state = MutableStateFlowCompat(FloatState())
    val state get() = _state.flow

    /** 服务活着时的直连句柄；服务没起来时这些调用就是空操作（调用方先判 [state].active）。 */
    internal var service: FloatPlayerService? = null

    internal fun update(f: (FloatState) -> FloatState) {
        _state.value = f(_state.value)
    }

    /** 浮窗启动时刻（uptimeMillis）：服务侧各段耗时都相对它打点（排查"启动慢"）。 */
    var startMs: Long = 0L

    fun start(context: Context, req: FloatRequest) {
        startMs = android.os.SystemClock.uptimeMillis()
        android.util.Log.i("FloatPlay", "+0ms 请求启动服务")
        val i = Intent(context, FloatPlayerService::class.java)
            .setAction(FloatPlayerService.ACTION_START)
            .putExtra(FloatPlayerService.EXTRA_URL, req.url)
            .putExtra(FloatPlayerService.EXTRA_TITLE, req.title)
            .putExtra(FloatPlayerService.EXTRA_REFERER, req.referer)
            .putExtra(FloatPlayerService.EXTRA_COOKIE, req.cookie)
            .putExtra(FloatPlayerService.EXTRA_UA, req.userAgent)
            .putExtra(FloatPlayerService.EXTRA_POS, req.startPosMs)
            .putExtra(FloatPlayerService.EXTRA_CANDIDATES, req.candidates.toTypedArray())
        // Android 12+ 后台启动前台服务会抛；浮窗入口都在前台点击里，这里按常规起
        context.startForegroundService(i)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, FloatPlayerService::class.java)
                .setAction(FloatPlayerService.ACTION_STOP),
        )
    }

    /** 分享屏幕时把窗藏掉：只摘视图，播放器不动（观众还在跟这条进度）。 */
    fun setHidden(hidden: Boolean) = service?.setHidden(hidden)

    fun play() = service?.play()
    fun pause() = service?.pause()
    fun seek(ms: Long) = service?.seek(ms)

    /** 换片：同一个窗、同一个播放器实例换源（不做第二个窗 —— 两个窗会抢声音和焦点）。 */
    fun replace(req: FloatRequest) = service?.replace(req)

    /**
     * 浮窗控制条上点「换片」的回调。
     * 由放映厅界面挂上（把候选列表弹出来）；没挂上时（比如界面已退）什么都不做 ——
     * 浮窗不该在那种时候自己弹出列表。
     */
    var onPickRequest: (() -> Unit)? = null

    /** 系统是否允许画在其他应用之上。 */
    fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    /** 跳系统设置页让用户开权限。安卓规定这一步只能用户自己点，我们只能把路指过去。 */
    fun openOverlaySettings(context: Context): Intent =
        Intent(
            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}"),
        )
}

/** 极小的一层 StateFlow 包装：只为把 MutableStateFlow 关在模块内，不引额外依赖。 */
internal class MutableStateFlowCompat<T>(initial: T) {
    private val _m = kotlinx.coroutines.flow.MutableStateFlow(initial)
    val flow: kotlinx.coroutines.flow.StateFlow<T> = _m
    var value: T
        get() = _m.value
        set(v) { _m.value = v }
}
