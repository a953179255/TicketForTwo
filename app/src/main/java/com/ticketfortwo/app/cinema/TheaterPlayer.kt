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
    )

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(State())
    val state: kotlinx.coroutines.flow.StateFlow<State> get() = _state

    private var exo: androidx.media3.exoplayer.ExoPlayer? = null
    private var surface: TextureView? = null
    private var startMs = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 每秒回传进度（供广播与甲板读数）。 */
    private val ticker = object : Runnable {
        override fun run() {
            val p = exo ?: return
            _state.value = _state.value.copy(
                playing = p.isPlaying,
                posMs = p.currentPosition.coerceAtLeast(0L),
                durMs = p.duration.takeIf { it > 0 } ?: 0L,
                ready = p.isPlaying && p.currentPosition > 0L,
            )
            handler.postDelayed(this, 1_000)
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
            })
            if (_state.value.error != null) {       // 起播途中已出错
                runCatching { p.release() }
                return@launch
            }
            exo = p
            surface?.let { p.setVideoTextureView(it) }
            if (posMs > 0) p.seekTo(posMs)
            p.playWhenReady = true
            handler.post(ticker)
            Log.i("TheaterPlay", "自播就位 ${target.substringAfterLast('/')}")
        }
    }

    /** UI 挂上画面（每次组合/回退返回都要重接）。 */
    fun attach(view: TextureView) {
        surface = view
        exo?.setVideoTextureView(view)
    }

    fun detach() {
        surface = null
        exo?.setVideoSurface(null)
    }

    /** 停掉并复位（退出放映 / 起播失败回退 / 换片）。 */
    fun stop() {
        handler.removeCallbacks(ticker)
        runCatching { exo?.release() }
        exo = null
        surface = null
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
