package com.ticketfortwo.app.cinema

import android.content.Context
import android.util.Log
import android.view.TextureView
import com.ticketfortwo.app.watch.WatchCmd
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 放映态**画面自播**播放器（方案 2B 的核心）。
 *
 * 为什么要有它：放映画面直接显示网页时，网页里的**广告点击、整块画面跳转**会跟着
 * 进来（用户 2026-09-30 反馈），控制条样式也各站各样。自播 = 画面不再显示网页，
 * 由我们自己的播放器直接播嗅探到的地址（网页退居幕后只负责交地址）——
 * 广告/跳转没有 DOM 可跳，一次全消；控制语言与浮窗/面板统一。
 *
 * 基建全部复用浮窗那趟的成果：
 *  - [FloatWarmer.pickVariant]：按**放映预算**挑档（≤1920 内最高带宽 → 1080p）；
 *  - [FloatWarmer.buildConfigured]：同款起播配置（缓冲门槛 500/500、可调上限）；
 *  - 画面输出到 UI 提供的 TextureView（挂在视频区，点画面天然进不到网页）。
 *
 * 回退契约：[State.error] 非空 = 起播失败（防盗链/DRM…），
 * 界面**自动退回网页画面**并把网页播起来 —— 最坏就是回到现状，不会更差。
 */
object TheaterPlayer {

    data class State(
        val active: Boolean = false,
        val playing: Boolean = false,
        val posMs: Long = 0L,
        val durMs: Long = 0L,
        val ready: Boolean = false,
        val error: String? = null,
        /** 视频真实尺寸（**显示方向**，rotation 已折算）。0 = 还没探到。
         *  竖屏片修复（2026-10-06）：布局与转播帧桥都按它走，不再猜 16:9。 */
        val videoW: Int = 0,
        val videoH: Int = 0,
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(State())
    val state: kotlinx.coroutines.flow.StateFlow<State> get() = _state

    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var surface: TextureView? = null

    /** B 方案转播中（画面走 helper 面推给观众，本地 UI 改显示轨回显）。 */
    @Volatile var relaying: Boolean = false

    /**
     * 转播帧桥的输出面（[com.ticketfortwo.app.capture.PlayerShareController] 挂上/摘下）。
     * 非空：播放器输出切给帧桥（观众收轨）；空：恢复 UI 纹理面（回直连模式）。
     * 跨线程安全：capturer 线程调入，实际 set 派发到主线程执行。
     */
    @Volatile private var external: android.view.Surface? = null
    fun attachExternalSurface(s: android.view.Surface?) {
        external = s
        Log.i("TheaterPlay", "attachExternal s=" + (s != null) + " exoAlive=" + (exo != null))
        handler.post {
            val p = exo ?: return@post
            if (s != null) {
                p.setVideoSurface(s)
                /* 切面后强制重新出帧（2026-10-06 实测）：播放器已开播再换输出面，
                   ExoPlayer 只补 1 帧就停（解码器不自动向新 surface 续渲染）——
                   seek 到当前位置触发 flush+重新解码，帧流恢复。旧时序"先建轨后
                   起播"没这问题（首帧就投在帧桥面上），但等尺寸必须先起播，
                   这一刀躲不掉。 */
                runCatching { p.seekTo(p.currentPosition) }
            } else surface?.let { p.setVideoTextureView(it) } ?: p.setVideoSurface(null)
        }
    }
    /**
     * 视频尺寸变化出口：转播帧桥据此**跟着改缓冲尺寸**（[PlayerShareController] 挂上）。
     * 不挂的话帧桥永远是建轨时那份尺寸 —— 竖屏帧被拉伸进横屏缓冲，观众从源头收到
     * 变形画面（2026-10-06 用户实测，放映端本地回显同源同病）。
     */
    @Volatile
    var sizeSink: ((w: Int, h: Int) -> Unit)? = null

    private var startMs = 0L
    /** 暂停保帧上次重发时刻（见 ticker 内注释）。 */
    private var lastFreezeSeek = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每秒回传进度（供广播与甲板读数）。 */
    private val ticker = object : Runnable {
        override fun run() {
            val p = exo ?: return
            val pos = p.currentPosition.coerceAtLeast(0L)
            val dur = p.duration.takeIf { it > 0 } ?: 0L
            val playing = p.isPlaying
            /* 尺寸探测**双保险**（2026-10-06 实测）：onVideoSizeChanged 在
               "没挂任何 surface"时不可靠（自播起播早于画面挂载/帧桥挂载的窗口期，
               整段播完回调一次没来）—— ticker 直接轮询 videoSize，1 秒内必到。 */
            if (_state.value.videoW <= 0) {
                val vs = p.videoSize
                val rot = vs.unappliedRotationDegrees
                val w: Int
                val h: Int
                if (rot == 90 || rot == 270) { w = vs.height; h = vs.width }
                else { w = vs.width; h = vs.height }
                if (w > 0 && h > 0) {
                    Log.i("TheaterPlay", "视频尺寸(轮询) $w×$h rot=$rot")
                    _state.value = _state.value.copy(videoW = w, videoH = h)
                    sizeSink?.invoke(w, h)
                }
            }
            /* 暂停保帧（2026-10-06 用户实测）：转播中房主一暂停，解码器不再出帧
               → 帧桥零帧 → 观众端纯黑：轨到了但 play() 对无帧流悬而不决，
               "画面已到"卡点了没反应还关不掉，右上角"画面正在接转过来…"永挂。
               每 2.5 秒把播放器往当前位置重 seek 一次 —— 强制解码器重新输出
               当前帧，观众看到的就是冻结的暂停画面（与放映端所见一致）。
               只在转播中做（直连自播无观众）；播到结尾（pos==dur）不折腾。 */
            if (!playing && relaying && pos > 0 && dur > 0 && pos < dur) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastFreezeSeek > 2500) {
                    lastFreezeSeek = now
                    Log.i("TheaterPlay", "暂停保帧 seek @$pos")
                    runCatching { p.seekTo(pos) }
                }
            }
            _state.value = _state.value.copy(
                playing = playing,
                posMs = pos,
                durMs = dur,
                ready = playing && pos > 0L,
            )
            /* 进度出口（2026-10-04）：广播不放在组合作用域里 —— 那会随页面卸载而断，
               观众端对钟失联（离场暂停问题的另一半）。CinemaScreen 组合时挂上，
               离场**不摘**：只要自播活着（回显浮窗模式下它一直活着），广播一直走。 */
            progressSink?.invoke(pos, dur, playing, rate)
            handler.postDelayed(this, 1_000)
        }
    }

    /**
     * 自播进度出口：CinemaScreen 挂上（转 [CallSession.publishCinemaProgress]），
     * 页面卸载不摘 —— 见 ticker 内注释。null = 没人听，跳过即可。
     */
    @Volatile
    var progressSink: ((posMs: Long, durMs: Long, playing: Boolean, rate: Double) -> Unit)? = null

    /**
     * 当前倍速（手势层长按/上滑设置）。[exo] 为 null 时先记着 —— [start] 建好
     * 播放器后立即套用，[stop] 归 1。HUD 显示读 CinemaScreen.boostRate，这里只管执行。
     */
    @Volatile
    var rate: Double = 1.0
        private set

    fun setRate(r: Double) {
        rate = r
        runCatching {
            exo?.playbackParameters = androidx.media3.common.PlaybackParameters(r.toFloat())
        }
    }

    /** 起播（幂等：已在播就忽略）。挑档按放映预算 1920 —— 比浮窗的 960 高一档。 */
    fun start(
        context: Context,
        url: String,
        candidates: List<String>,
        headers: Map<String, String>,
        userAgent: String?,
        posMs: Long,
    ) {
        if (exo != null) return
        startMs = android.os.SystemClock.uptimeMillis()
        _state.value = State(active = true, posMs = posMs)
        Log.i("TheaterPlay", "+0ms 自播起播 ${url.substringAfterLast('/')}")
        scope.launch {
            val target = withContext(Dispatchers.IO) {
                FloatWarmer.pickVariant(listOf(url) + candidates, headers, budgetPx = 1920)
            }
            if (exo != null) return@launch          // 期间被 stop
            val p = FloatWarmer.buildConfigured(
                context, target, headers, userAgent,
                muted = false, maxVideoW = 1920, maxVideoH = 1080,
            )
            p.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    Log.w("TheaterPlay", "自播失败: ${error.errorCodeName} / ${error.message}")
                    _state.value = _state.value.copy(ready = false, playing = false, error = error.message)
                }

                override fun onRenderedFirstFrame() {
                    Log.i(
                        "TheaterPlay",
                        "+${android.os.SystemClock.uptimeMillis() - startMs}ms 首帧（画面出现）",
                    )
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    /* 记**显示方向**的尺寸：rotation 90/270 时宽高互换 —— 布局盒与
                       转播帧桥要的都是"观众看到的比例"，不是解码顺序的宽高。 */
                    val rot = videoSize.unappliedRotationDegrees
                    val w: Int
                    val h: Int
                    if (rot == 90 || rot == 270) {
                        w = videoSize.height; h = videoSize.width
                    } else {
                        w = videoSize.width; h = videoSize.height
                    }
                    if (w > 0 && h > 0) {
                        Log.i("TheaterPlay", "视频尺寸 $w×$h rot=$rot")
                        _state.value = _state.value.copy(videoW = w, videoH = h)
                        sizeSink?.invoke(w, h)
                    }
                }
            })
            if (_state.value.error != null) {       // 起播途中已出错
                runCatching { p.release() }
                return@launch
            }
            exo = p
            // 手势在起播期间设的倍速不丢：播放器就位立刻套上
            p.playbackParameters = androidx.media3.common.PlaybackParameters(rate.toFloat())
            val ext = external
            Log.i("TheaterPlay", "start ext=" + (ext != null) + " ui=" + (surface != null))
            if (ext != null) p.setVideoSurface(ext)      // 转播在先：帧桥优先
            else surface?.let { p.setVideoTextureView(it) }
            if (posMs > 0) p.seekTo(posMs)
            p.playWhenReady = true
            handler.post(ticker)
            Log.i("TheaterPlay", "自播就位 ${target.substringAfterLast('/')}")
        }
    }

    /** UI 挂上画面（每次组合/回退返回都要重接）。转播中让位给帧桥 —— 本地看回显轨。 */
    fun attach(view: TextureView) {
        surface = view
        if (external == null) exo?.setVideoTextureView(view)
    }

    fun detach() {
        surface = null
        exo?.setVideoSurface(null)
    }

    /**
     * 回显浮窗专用（2026-10-04）：把输出临时切给浮窗的视图。
     * 与 [attach] 的差别是**不记 surface** —— 那个字段永远指 UI 的视图，
     * [restoreFromRelay] 归还时才知道该还给谁。
     */
    fun attachRelayView(view: TextureView) {
        if (external == null) exo?.setVideoTextureView(view)
    }

    /** 回显窗收场：画面还给 UI（页面还活着），UI 不在（回厅路上）就先摘掉防黑帧。 */
    fun restoreFromRelay() {
        if (external != null) return
        val p = exo ?: return
        surface?.let { p.setVideoTextureView(it) } ?: p.setVideoSurface(null)
    }

    /** 停掉并复位（退出放映 / 起播失败回退 / 换片）。 */
    fun stop() {
        handler.removeCallbacks(ticker)
        runCatching { exo?.release() }
        exo = null
        surface = null
        rate = 1.0
        _state.value = State()
        Log.i("TheaterPlay", "自播已停止")
    }

    // ── 控制（hostCmd/观众指令分流到这里）──
    fun seek(posMs: Long) { exo?.seekTo(posMs.coerceAtLeast(0L)) }
    fun play() { exo?.play() }
    fun pause() { exo?.pause() }

    fun step(deltaMs: Long) {
        val p = exo ?: return
        p.seekTo((p.currentPosition + deltaMs).coerceAtLeast(0L))
    }

    /** 把一条指令喂给自播；返回 true = 处理了（界面据此不再发 JS）。 */
    fun handle(cmd: WatchCmd): Boolean {
        if (exo == null) return false
        when (cmd) {
            is WatchCmd.Seek -> seek(cmd.posMs)
            is WatchCmd.Play -> play()
            is WatchCmd.Pause -> pause()
            is WatchCmd.Step -> step(cmd.deltaMs)
            else -> return false
        }
        return true
    }
}
