package com.ticketfortwo.app.cinema

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ticketfortwo.app.MainActivity
import com.ticketfortwo.app.R
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 悬浮窗播放器：播"嗅探到的地址"，与网页里的 `<video>` 无关。
 *
 * 为什么必须独立成一个服务 + 系统级浮窗：
 *  - **跨页**：网页里的播放器随页面卸载消失，换网址就没了；播地址的播放器不受影响。
 *  - **跨 App**：`TYPE_APPLICATION_OVERLAY` 的窗口不属于任何 Activity ——
 *    回主界面、回桌面、切到别的 App，它都盖在最上层（需要 SYSTEM_ALERT_WINDOW）。
 *  - **保活**：前台服务，切后台不会被系统回收。
 *
 * 参照雨见浏览器的"嗅探即浮窗"（分析见 docs/references/yjllq-float-window.md）。
 */
class FloatPlayerService : android.app.Service() {

    private var wm: WindowManager? = null
    private var overlay: View? = null
    private var player: ExoPlayer? = null
    private var bar: View? = null
    private var videoView: TextureView? = null
    /** 本轮 start 的令牌：teardown / 重入时 ++ ，异步回调据此作废并自释放。 */
    private var startToken = 0
    /** 预热态（1×1 窗 + 静音播）；showPrewarmed 后转 false。 */
    private var inPrewarm = false
    private var windowLp: WindowManager.LayoutParams? = null
    private var lastSeekMs = -1L
    private var titleView: TextView? = null
    /** 播放/暂停键（两种窗共用一个引用：relayTicker 要跟着放映态翻图标）。 */
    private var toggle: TextView? = null
    /** 控制条进度线 + 时间（2026-10-01 用户要求：浮窗也要能看进度、拖进度）。 */
    private var seekBar: android.widget.SeekBar? = null
    private var timeView: TextView? = null
    /** 用户正拖着进度条 —— 拖动期间 publish 别把进度拽回去（和滑块打架）。 */
    private var seekTracking = false
    private val handler = Handler(Looper.getMainLooper())

    /** 进度回传：界面与"指挥权"判断都靠它。1000ms 一拍 ——
        之前 500ms 一拍，叠加状态回灌界面（整屏重组），用户实测"开浮窗手机有点卡"：
        一半的功夫就砍在这。进度广播本来就按秒级算，1s 足够顺。 */
    private val ticker = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, 1_000)
        }
    }

    /** 服务内的后台作用域（挑档网络请求用）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 打点：距 FloatPlayer.startMs（请求启动那一瞬）过了多久。
        用来把"浮窗出现慢/画面慢"拆成服务启动 / 窗口挂载 / 首帧三段。 */
    private fun mark(tag: String) {
        android.util.Log.i(
            "FloatPlay",
            "+${android.os.SystemClock.uptimeMillis() - FloatPlayer.startMs}ms $tag",
        )
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        FloatPlayer.service = this
        mark("服务 onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_STOP -> { teardown(); stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW -> {
                mark("SHOW 拉起预热窗")
                showPrewarmed(intent.getLongExtra(EXTRA_POS, -1L))
                return START_NOT_STICKY
            }
            /* 正式起浮窗（非预热）：原来这里**没有分支** —— startForegroundService
               收到后什么都不做就 return，服务 30 秒不调 startForeground，
               系统直接 ForegroundServiceDidNotStartInTimeException 把 App 崩掉
               （2026-10-01 用户实测"浏览态点浮窗没反应"：其实一点就崩）。
               以前没炸是因为有候选的站走 PREWARM→SHOW，这条路径从没被踩到。 */
            ACTION_START -> {
                mark("START 拉起浮窗")
                start(
                    url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY,
                    title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                    referer = intent.getStringExtra(EXTRA_REFERER),
                    cookie = intent.getStringExtra(EXTRA_COOKIE),
                    ua = intent.getStringExtra(EXTRA_UA),
                    posMs = intent.getLongExtra(EXTRA_POS, 0L),
                    candidates = intent.getStringArrayExtra(EXTRA_CANDIDATES)?.toList()
                        ?: emptyList(),
                )
            }
            ACTION_PREWARM -> { mark("收到预热指令"); prewarm(
                url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY,
                title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                referer = intent.getStringExtra(EXTRA_REFERER),
                cookie = intent.getStringExtra(EXTRA_COOKIE),
                ua = intent.getStringExtra(EXTRA_UA),
                posMs = intent.getLongExtra(EXTRA_POS, 0L),
                candidates = intent.getStringArrayExtra(EXTRA_CANDIDATES)?.toList()
                    ?: emptyList(),
            ) }
            /* 回显浮窗（2026-10-04 放映离场）：不建第二个播放器，把正在放映的画面
               （TheaterPlayer 直连输出 / 转播轨回显）装进系统级窗 —— 播放、进度、
               观众那条流全程不动。与播放窗互斥：进门先 teardown 清场。 */
            ACTION_RELAY_START -> {
                mark("RELAY 拉起回显浮窗")
                startRelay()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        FloatPlayer.service = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ───────────────────────── 播放 ─────────────────────────

    private fun start(
        url: String,
        title: String,
        referer: String?,
        cookie: String?,
        ua: String?,
        posMs: Long,
        candidates: List<String>,
    ) = begin(url, title, referer, cookie, ua, posMs, candidates, prewarm = false)

    /** 预热：同一条起播链路，但窗口 1×1 透明、静音播、不点亮 active。 */
    private fun prewarm(
        url: String,
        title: String,
        referer: String?,
        cookie: String?,
        ua: String?,
        posMs: Long,
        candidates: List<String>,
    ) = begin(url, title, referer, cookie, ua, 0L, candidates, prewarm = true)

    private fun begin(
        url: String,
        title: String,
        referer: String?,
        cookie: String?,
        ua: String?,
        posMs: Long,
        candidates: List<String>,
        prewarm: Boolean,
    ) {
        /* 请求头必须和网页那次请求一致 —— 防盗链站点只认这个组合。 */
        val headers = buildMap {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
        val token = ++startToken
        // 幂等：已有活着的 session（预热/正式/**回显**）就不再起新的 ——
        // 回显窗挂着时再点"浮窗播"不该叠出第二个窗（先收回显窗才能播新的）
        val st = FloatPlayer.state.value
        if ((player != null || relayMode) && (st.active || st.prewarm)) {
            mark("已有浮窗 session（relay=$relayMode），忽略本次${if (prewarm) "预热" else "启动"}")
            return
        }
        inPrewarm = prewarm
        FloatPlayer.update {
            it.copy(
                active = !prewarm, prewarm = prewarm,
                url = url, title = title,
                playing = false, posMs = posMs, durMs = 0L,
                ready = false, error = null,
            )
        }
        /* 窗口先挂：预热时是 **1×1 透明窗**（屏上不可见），SHOW 时放大到真尺寸 ——
           这就是雨见形态的"浮窗对象常驻"：播放器的画面从头就流进**真实 TextureView**
           （系统天然消费，绝不会像裸离屏 Surface 那样把解码器憋死）。 */
        addOverlay(title, tiny = prewarm)
        mark(if (prewarm) "窗口已挂（1×1 预热态）" else "窗口已挂上（浮窗可见）")
        startForeground(NOTIF_ID, buildNotification(title))
        handler.post(ticker)

        scope.launch {
            val target = withContext(Dispatchers.IO) {
                FloatWarmer.pickVariant(listOf(url) + candidates, headers)
            }
            withContext(Dispatchers.Main) {
                if (token != startToken) return@withContext
                val exo = FloatWarmer.buildConfigured(
                    this@FloatPlayerService, target, headers, ua, muted = prewarm,
                )
                if (activate(exo, posMs, token)) {
                    mark(
                        (if (prewarm) "预热起播 " else "起播(挑档后) ") +
                            target.substringAfterLast('/'),
                    )
                }
            }
        }
    }

    /** SHOW：把 1×1 预热窗放大成真浮窗，恢复声音、点亮 active。 */
    private fun showPrewarmed(seekMs: Long = -1L) {
        val ov = overlay ?: return mark("SHOW 无窗口，忽略")
        val lp = windowLp ?: return
        if (!inPrewarm) return mark("SHOW 无预热 session，忽略")
        inPrewarm = false
        lp.width = dp(SIZES[sizeIdx].first)
        lp.height = dp(SIZES[sizeIdx].second)
        (ov as? FrameLayout)?.let { applyGlassLook(it, dp(14).toFloat()) }
        runCatching { wm?.updateViewLayout(ov, lp) }
        player?.volume = 1f
        // 自播交棒：从调用方给的位置接（预热窗平时跟随网页 pos，交棒时要按自播位置来）
        if (seekMs > 0) runCatching { player?.seekTo(seekMs) }
        FloatPlayer.update { it.copy(active = true, prewarm = false) }
        mark("预热窗已拉起 size=${SIZES[sizeIdx]}")
    }

    /** 预热跟随页面进度（内部节流）：目标分片永远在缓冲覆盖内，SHOW 后秒出画面。 */
    fun reseek(posMs: Long) {
        if (!inPrewarm) return
        val p = player ?: return
        if (posMs <= 0) return
        val now = android.os.SystemClock.uptimeMillis()
        if (lastSeekMs >= 0 && now - lastSeekMs < 3_000) return
        if (lastSeekMs >= 0 && kotlin.math.abs(posMs - lastSeekMs) < 2_500) return
        lastSeekMs = posMs
        p.seekTo(posMs)
        Log.i("FloatPlay", "预热跟随进度 -> ${posMs / 1000}s")
    }

    /**
     * 播放器就位三步：挂仪表（打点/占位隐藏/失败反馈）→ 绑画面 → 接着页面的进度播。
     * 预热与冷路径**共用**，保证两条路行为一致。
     * token 不符 = 期间被 teardown/重入：自释放（预热 take 出来的也归这条管）。
     */
    private fun activate(exo: ExoPlayer, posMs: Long, token: Int): Boolean {
        if (token != startToken) {
            runCatching { exo.release() }
            return false
        }
        player = exo
        attachInstrumentation(exo)          // 绑到浮窗真实 TextureView（预热时即已 1×1 挂好）
        if (posMs > 0) exo.seekTo(posMs)   // 接着页面的进度，不从头来
        exo.playWhenReady = true
        return true
    }

    /** 打点 + 占位控制 + 失败反馈 + 绑定浮窗画面。 */
    private fun attachInstrumentation(exo: ExoPlayer) {
        videoView?.let { exo.setVideoTextureView(it) }
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                mark("首帧画面（画面出现）")
                handler.post {
                    placeholder?.animate()?.alpha(0f)?.setDuration(160)
                        ?.withEndAction { placeholder?.visibility = View.GONE }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) mark("READY 可播放")
            }

            override fun onPlayerError(error: PlaybackException) {
                /* 播不起来就老实认错并交还指挥权：最坏情况退回"网页里照播"，
                   不会把放映搞坏（这是设计里对防盗链站点的兜底）。 */
                FloatPlayer.update { it.copy(ready = false, playing = false, error = error.message) }
                handler.post {
                    placeholder?.let {
                        it.text = "画面加载失败，稍后自动重试"
                        it.visibility = View.VISIBLE
                        it.alpha = 1f
                    }
                }
                Log.w("FloatPlay", "浮窗起播失败: ${error.message}")
            }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                mark("LOAD开始 dataType=${mediaLoadData.dataType} uri=${loadEventInfo.uri}")
            }

            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                mark(
                    "LOAD完成 dataType=${mediaLoadData.dataType} " +
                        "${loadEventInfo.loadDurationMs}ms ${loadEventInfo.bytesLoaded}B " +
                        "uri=${loadEventInfo.uri}",
                )
            }

            override fun onTimelineChanged(
                eventTime: AnalyticsListener.EventTime,
                reason: Int,
            ) {
                mark("清单就位 reason=$reason")
            }
        })
    }

    private fun publish() {
        val p = player ?: return
        /* DIAG：每秒一条播放器状态 —— 首帧不来时靠它定位卡在哪个环节 */
        Log.i(
            "FloatPlay",
            "STATE state=${p.playbackState} ready=${p.playbackState == 3} " +
                "playWhenReady=${p.playWhenReady} suppressed=${p.playbackSuppressionReason} " +
                "pos=${p.currentPosition} buf=${p.bufferedPosition} " +
                "size=${p.videoSize.width}x${p.videoSize.height}",
        )
        if (p.playbackState == Player.STATE_ENDED) {
            FloatPlayer.update { it.copy(playing = false) }
            return
        }
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        val pos = p.currentPosition.coerceAtLeast(0L)
        /* 只在有意义的变化时回灌状态：位置挪了 ≥400ms / 时长变了 / 播放态变了。
           不加这道门的话每拍都发一遍 → 界面每秒白重组一次（卡的另一半）。 */
        val s = FloatPlayer.state.value
        val changed = kotlin.math.abs(pos - s.posMs) >= 400 ||
            dur != s.durMs || p.isPlaying != s.playing || (p.isPlaying && !s.ready)
        if (changed) {
            FloatPlayer.update {
                it.copy(
                    playing = p.isPlaying,
                    posMs = pos,
                    durMs = dur,
                    /* ready = 真的在播。刚 prepare() 完 isPlaying 常常还是 false
                       （HLS 要缓冲），拿它当交接判据会出现"两头都没播"的空白。 */
                    ready = p.isPlaying && pos > 0L,
                )
            }
        }
        // 控制条进度线 + 时间每拍跟进（拖动中让位，别把用户的滑块拽回来）
        if (!seekTracking) {
            seekBar?.let { sb ->
                if (dur > 0) {
                    sb.max = 1000
                    sb.progress = ((pos * 1000) / dur).toInt().coerceIn(0, 1000)
                    sb.isEnabled = true
                } else {
                    sb.progress = 0
                    sb.isEnabled = false   // 时长未知（直播/未探到）：线留着、拖不了
                }
            }
            timeView?.text = com.ticketfortwo.app.cinema.CinemaSync.formatTime(pos) +
                " / " + com.ticketfortwo.app.cinema.CinemaSync.formatTime(dur)
        }
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun seek(ms: Long) { player?.seekTo(ms) }

    fun replace(req: FloatRequest) {
        val p = player ?: return
        p.setMediaItem(MediaItem.fromUri(req.url))
        if (req.startPosMs > 0) p.seekTo(req.startPosMs)
        p.playWhenReady = true
        p.prepare()
        FloatPlayer.update {
            it.copy(url = req.url, title = req.title, ready = false, error = null, durMs = 0L)
        }
        titleView?.text = req.title
    }

    /** 只摘视图，播放器不动 —— 分享屏幕时用（观众还在跟这条进度）。 */
    fun setHidden(hidden: Boolean) {
        val v = overlay ?: return
        val m = wm ?: return
        if (hidden) {
            runCatching { m.removeView(v) }
        } else if (v.parent == null) {
            runCatching { m.addView(v, v.layoutParams) }
        }
        FloatPlayer.update { it.copy(hidden = hidden) }
    }

    // ───────────────────────── 浮窗视图 ─────────────────────────

    /** 玻璃外观（圆角底+轮廓裁剪）：预热态先不挂（1×1 透明），SHOW 时补上。
        TextureView 是真视图，轮廓裁剪切得动；SurfaceView 切不动（四角会方）。 */
    private fun applyGlassLook(root: FrameLayout, radius: Float) {
        root.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xE6101116.toInt())
            cornerRadius = radius
            setStroke(1, 0x33FFFFFF)   // 一圈极淡描边，亮壁纸上才看得出边界
        }
        root.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        root.clipToOutline = true
        root.elevation = dp(8).toFloat()
    }

    private fun addOverlay(title: String, tiny: Boolean = false) {
        val m = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = m
        /* 窗高 = 画面高，**不再给控制条留独立空间**（2026-09-30 用户反馈：
           控制条藏了但占位还在）。控制条改为覆盖在画面内部（见下方 bar）。
           预热态窗口 1×1 透明（不可见），SHOW 时放大到 SIZES。 */
        val w = if (tiny) dp(1) else dp(SIZES[sizeIdx].first)
        val h = if (tiny) dp(1) else dp(SIZES[sizeIdx].second)

        val radius = dp(14).toFloat()
        val root = FrameLayout(this).apply {
            if (!tiny) applyGlassLook(this, radius)
        }
        val video = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(video)
        videoView = video

        // 占位：首帧没到之前窗口是空的（深底压深壁纸 = 几乎不可见），
        // 立刻显示这句，用户就知道窗已经出来了、画面在路上
        val ph = TextView(this).apply {
            text = "正在接入画面…"
            setTextColor(0xFF9AA6B8.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isClickable = false
            isFocusable = false
        }
        placeholder = ph
        root.addView(ph)

        /* 控制条**覆盖在画面内部**（不再占独立空间）：默认藏起来，
           轻点窗身从画面里浮出来，3 秒自动收（2026-09-30 用户要求）。
           2026-10-01 加高为两行：上 = 可拖进度线（能看放哪了、能拖），
           下 = 标题 · 时间 · 暂停 · 关闭（用户反馈"看不到进度也拖不了"）。 */
        val bar = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(BAR_H_DP), Gravity.BOTTOM,
            )
            // 半透明压在画面上：看得清按钮，又不把画面整体遮掉一条
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }
        this.bar = bar

        // 进度线：拖动 seek（松手才提交，拖动中 publish 让位）
        val sb = android.widget.SeekBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(22),
                Gravity.TOP,
            ).apply { topMargin = dp(2); marginStart = dp(8); marginEnd = dp(70) }
            max = 1000
            splitTrack = false
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF7AD8C3.toInt())
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                    seekTracking = true
                }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    val d = player?.duration ?: 0L
                    if (d > 0) player?.seekTo(d * sb.progress / 1000L)
                    seekTracking = false
                }
            })
        }
        seekBar = sb
        bar.addView(sb)

        val t = TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM,
            ).apply { marginStart = dp(8); bottomMargin = dp(7) }
        }
        // 底行左侧空间 = 窗宽 - 时间 - 两颗键（约 130dp）—— 不限宽会顶到时间上
        t.maxWidth = (w * 45) / 100
        titleView = t
        bar.addView(t)

        // 时间：底行右侧、按钮左边
        val tm = TextView(this).apply {
            text = "0:00 / 0:00"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 9.5f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM,
            ).apply { marginEnd = dp(66); bottomMargin = dp(9) }
        }
        timeView = tm
        bar.addView(tm)

        /* 「换片」键已从浮窗控制条移除（2026-10-01 用户拍板：浮窗就是拿来播放的，
           暂停 + 关闭就够；换片走放映厅甲板的「换片」键）。onPickRequest 回调
           与顶替询问逻辑保留 —— 以后要恢复一颗键就能用。 */
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            layoutParams = FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.END or Gravity.BOTTOM,
            ).apply { marginEnd = dp(6); bottomMargin = dp(4) }
            setOnClickListener { teardown(); stopSelf() }
        }
        bar.addView(close)
        val toggle = TextView(this).apply {
            text = "❚❚"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            layoutParams = FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.END or Gravity.BOTTOM,
            ).apply { marginEnd = dp(34); bottomMargin = dp(4) }
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) { p.pause(); text = "▶" } else { p.play(); text = "❚❚" }
            }
        }
        bar.addView(toggle)
        this.toggle = toggle
        root.addView(bar)

        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        /* 拖动 + 轻点 + 双击：按住挪动 = 拖窗；**原地轻点** = 控制条现一下身；
           **双击** = 循环调窗大小（小 → 中 → 大 → 小，2026-09-30 用户要求，
           与主流视频 App 的画中画一致）。单击动作延时 290ms 确认 —— 等等看
           第二下会不会来，来了就取消单击、改执行双击。 */
        var lastX = 0f
        var lastY = 0f
        var downX = 0
        var downY = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var lastTapAt = 0L
        val singleTapRun = Runnable {
            bar?.let { b ->
                b.removeCallbacks(hideBarRun)
                b.visibility = View.VISIBLE
                b.alpha = 1f
                b.postDelayed(hideBarRun, 3_000)
            }
        }
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                    downX = lp.x; downY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        lp.x = (downX + dx).toInt().coerceAtLeast(0)
                        lp.y = (downY + dy).toInt().coerceAtLeast(0)
                        runCatching { m.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.action == MotionEvent.ACTION_UP && !moved) {
                        val now = android.os.SystemClock.uptimeMillis()
                        // 380ms：比 GestureDetector 的双击超时（300ms）稍宽 —— 触屏手上
                        // 二连击常落在 300~350ms，宁可偶尔把两次慢单击当双击（代价只是换档）
                        if (now - lastTapAt < 380) {
                            // 双击：调窗大小（取消还没执行的单击）
                            v.removeCallbacks(singleTapRun)
                            lastTapAt = 0
                            cycleSize(v)
                        } else {
                            lastTapAt = now
                            v.postDelayed(singleTapRun, 290)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        overlay = root
        windowLp = lp
        runCatching { m.addView(root, lp) }
    }

    /** 三档窗大小（宽 dp to 高 dp），高随宽保持 16:9 附近的比例。 */
    private val SIZES = arrayOf(170 to 96, 222 to 125, 276 to 155)
    private var sizeIdx = 0

    /** 双击换挡：小 → 中 → 大 → 回到小。同一次会话记住当前档，拖动不重置。 */
    private fun cycleSize(view: View) {
        sizeIdx = (sizeIdx + 1) % SIZES.size
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.width = dp(SIZES[sizeIdx].first)
        lp.height = dp(SIZES[sizeIdx].second)
        runCatching { wm?.updateViewLayout(view, lp) }
        android.util.Log.i("FloatPlay", "size -> ${SIZES[sizeIdx]}")
    }

    /** 控制条自动收起：轻点唤出，3 秒不碰就藏（见 addOverlay 的轻点分支）。
     *  顶部角键行（回显窗的 ✕/⤢）与它**同进同出** —— 藏的时候一起藏。 */
    private val hideBarRun = Runnable {
        bar?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            bar?.visibility = View.GONE
        }
        relayTop?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            relayTop?.visibility = View.GONE
        }
    }

    // ───────────────────────── 回显浮窗（放映离场） ─────────────────────────

    /** relay 模式标记：teardown / 幂等 / 播放窗互斥都看它。 */
    private var relayMode = false
    /** 转播中的画面源：轨回显渲染器（relaying=true 时用，直连自播时为 null）。 */
    private var relayRenderer: org.webrtc.SurfaceViewRenderer? = null
    private var relayTrack: org.webrtc.VideoTrack? = null
    /** 当前快进步长（秒）：与设置页「快进步长」同一份配置，开回显窗时读一次。 */
    private var stepSec: Int = 10
    /** 快进快退的落点提示（画面中央闪一下）。 */
    private var hud: TextView? = null
    /** 回显窗顶部角键行（✕ / ⤢）：与控制层同显同隐（2026-10-05 用户拍板：不再常显）。 */
    private var relayTop: android.view.ViewGroup? = null

    /** 圆底半透明的键（角键 / 三键同语言：深底 + 极淡白描边 + 白字）。 */
    private fun roundKey(sizeDp: Int, text: String, fs: Float): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = fs
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x8A101216.toInt())
                setStroke(1, 0x2AFFFFFF)
            }
            isClickable = true
        }

    private fun relayKey(sizeDp: Int, text: String, fs: Float): TextView =
        roundKey(sizeDp, text, fs).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                leftMargin = dp(7)
                rightMargin = dp(7)
            }
        }

    private fun cornerKey(glyph: String, fs: Float): TextView = roundKey(26, glyph, fs)

    /**
     * 整条贴底的细进度条：轨道 3dp 半透明白、已播 3dp 品牌青（与全屏播放器同色），
     * 垂直居中于 18dp 的触摸热区里。
     * 两层**必须带 id**（android.R.id.background / progress）—— 不给 id 的话
     * SeekBar 认不出哪层是进度，整条会被当成背景一次拉满。
     */
    private fun buildSeekDrawable(): android.graphics.drawable.LayerDrawable {
        val barH = dp(3)
        val inset = (dp(18) - barH) / 2
        val track = android.graphics.drawable.GradientDrawable().apply {
            setColor(0x47FFFFFF)
            cornerRadius = dp(2).toFloat()
        }
        val prog = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF7AD8C3.toInt())
            cornerRadius = dp(2).toFloat()
        }
        return android.graphics.drawable.LayerDrawable(
            arrayOf(
                android.graphics.drawable.InsetDrawable(track, 0, inset, 0, inset),
                android.graphics.drawable.ClipDrawable(
                    android.graphics.drawable.InsetDrawable(prog, 0, inset, 0, inset),
                    Gravity.START,
                    android.graphics.drawable.ClipDrawable.HORIZONTAL,
                ),
            ),
        ).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
        }
    }

    /** 落点提示：闪一下就散（与全屏手势的 flash 同感觉）。 */
    private fun flash(text: String) {
        val v = hud ?: return
        v.text = text
        v.alpha = 1f
        v.visibility = View.VISIBLE
        v.removeCallbacks(hudHideRun)
        v.postDelayed(hudHideRun, 800)
    }

    private val hudHideRun = Runnable {
        hud?.animate()?.alpha(0f)?.setDuration(150)?.withEndAction {
            hud?.visibility = View.GONE
        }
    }

    /** 点了控制层里的键：重新计时 3 秒再收（否则操作到一半控制层消失了）。 */
    private fun parkCtrl() {
        bar?.let { b ->
            b.removeCallbacks(hideBarRun)
            b.postDelayed(hideBarRun, 3_000)
        }
    }

    /**
     * 起回显窗（2026-10-04 放映离场）：**不建第二个播放器**。
     * 画面源二选一：
     *  - 转播中（relaying）：播放器输出在帧桥上 → 浮窗渲**轨回显**（观众看到的同一条轨）；
     *  - 直连自播：播放器输出空闲 → 直接把画面切给浮窗的 TextureView。
     * 与播放窗互斥：进门先 teardown 清场（预热窗/播放窗都收掉）。
     */
    private fun startRelay() {
        teardown()
        val t = TheaterPlayer.state.value
        if (!t.active) { mark("放映没在跑，回显窗不开"); stopSelf(); return }
        /* 权限闸（2026-10-04）：TYPE_APPLICATION_OVERLAY 没授权时 addView 会抛
           BadTokenException —— 播放窗路径有"浮窗播失败退网页"兜底，回显窗没有
           可退的东西，这里显式拒绝并把原因打进日志（界面侧离场前也会先查）。 */
        if (!FloatPlayer.canDrawOverlays(this)) {
            mark("无悬浮窗权限，回显窗开不了（放映继续，只是没小窗）")
            FloatPlayer.update { FloatState() }
            stopSelf()
            return
        }
        relayMode = true
        FloatPlayer.update {
            FloatState(active = true, relay = true, title = "放映中", ready = true)
        }
        addRelayOverlay()
        startForeground(NOTIF_ID, buildNotification("放映中 · 小窗"))
        handler.post(relayTicker)
        mark("回显窗已挂 relaying=${TheaterPlayer.relaying}")
    }

    /** 回显窗的进展拍：刷时间/进度线/播放键，顺带把状态回灌界面。500ms 一拍。 */
    private val relayTicker = object : Runnable {
        override fun run() {
            /* 收厅了（首页"关闭放映厅"）→ 窗跟着散场。不判 t.active：换片有
               stop→start 的瞬时空档，误判会把换片时的窗杀掉（换片只发生在厅内，
               那时回显窗本来就不在 —— 这里真正的信号只有"会话没了"）。 */
            if (!com.ticketfortwo.app.CallSession.isActive) {
                teardown(); stopSelf(); return
            }
            val t = TheaterPlayer.state.value
            if (!seekTracking) {
                seekBar?.let { sb ->
                    if (t.durMs > 0) {
                        sb.max = 1000
                        sb.progress = ((t.posMs * 1000) / t.durMs).toInt().coerceIn(0, 1000)
                        sb.isEnabled = true
                    } else {
                        sb.progress = 0
                        sb.isEnabled = false
                    }
                }
                toggle?.text = if (t.playing) "❚❚" else "▶"
            }
            /* 时间小字按用户拍板取消（2026-10-04）：小窗不显示 4:57 / 10:34，
               位置回放映页看 —— 省出来的地方全留给画面。 */
            FloatPlayer.update {
                it.copy(playing = t.playing, posMs = t.posMs, durMs = t.durMs, ready = t.active)
            }
            handler.postDelayed(this, 500)
        }
    }

    /** 回显窗视图：骨架照播放窗（拖动/单击唤控制条/双击调大小），控制键指 TheaterPlayer。 */
    private fun addRelayOverlay() {
        val m = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = m
        val w = dp(SIZES[sizeIdx].first)
        val h = dp(SIZES[sizeIdx].second)

        val radius = dp(14).toFloat()
        val root = FrameLayout(this).apply { applyGlassLook(this, radius) }

        if (TheaterPlayer.relaying) {
            /* 转播中：SurfaceViewRenderer 渲轨回显（观众看到的同一条）。
               这个 webrtc-sdk（150.7871.01）只有 SurfaceViewRenderer 没有 TextureView
               版 —— 代价是画面四角切不出圆角（挖洞层不吃 outline 裁剪），方角压在
               圆角窗上，轻微瑕疵先接受。init 必须给 RendererEvents（CallScreen 的
               结论：没有它"黑屏"和"还没来帧"分不开）；缩放等比留边，不裁内容。 */
            val renderer = org.webrtc.SurfaceViewRenderer(this)
            runCatching {
                com.ticketfortwo.app.rtc.RtcEngine.init(this)
                renderer.init(
                    com.ticketfortwo.app.rtc.RtcEngine.eglBase.eglBaseContext,
                    object : org.webrtc.RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            handler.post { placeholder?.visibility = View.GONE }
                        }

                        override fun onFrameResolutionChanged(w: Int, h: Int, rot: Int) = Unit
                    },
                )
                renderer.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                com.ticketfortwo.app.CallSession.localVideo.value?.let { track ->
                    track.addSink(renderer)
                    relayTrack = track
                }
            }.onFailure { mark("回显渲染器初始化失败: ${it.message}") }
            relayRenderer = renderer
            root.addView(
                renderer,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        } else {
            /* 直连自播：播放器输出空闲，临时切给浮窗（不记 surface —— UI 的位置留着）。 */
            val tv = TextureView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            }
            root.addView(tv)
            TheaterPlayer.attachRelayView(tv)
        }

        // 占位：转播首帧没到之前是黑的，给一句话撑着
        val ph = TextView(this).apply {
            text = "放映画面回显中…"
            setTextColor(0xFF9AA6B8.toInt())
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            isClickable = false
            isFocusable = false
        }
        placeholder = ph
        root.addView(ph)
        // 转播轨有首帧就撤占位；直连自播切过去就是画面，直接撤
        if (!TheaterPlayer.relaying) {
            ph.visibility = View.GONE
        } else {
            handler.postDelayed({ placeholder?.visibility = View.GONE }, 2_500)
        }

        /* 控制层（2026-10-04 定稿，对标哔哩哔哩悬浮窗 + 2026-10-05 二次拍板）：
           ①角键 ✕/⤢ **收起时藏起来**，随控制层一起出现 —— 常显太抢画面（用户反馈）；
           ②三键 + 整条进度条**轻点唤出**：再点一下画面可**主动收起**，不点则 3 秒自收
             （singleTapRun 做成开关，hideBarRun 管收，所以 ctrl 直接挂到 this.bar 上）；
           ③**不要时间小字**——小窗只管"看得见 + 能控制"，时间回放映页看。
           三键的步长跟设置里「快进步长」同一份配置（PlayerPrefs）。 */
        val step = PlayerPrefs.stepSecOf(this)
        stepSec = step

        val ctrl = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            )
            // 底部向上的黑渐变：键和条压在画面上也看得清（B 站同款，不糊黑底条）
            background = android.graphics.drawable.GradientDrawable().apply {
                colors = intArrayOf(0x00000000, 0xA6000000.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            }
            visibility = View.GONE
        }
        this.bar = ctrl

        val keys = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(40),
            )
        }

        /* 三键：小窗最窄才 170dp，所以侧键 28 / 主键 36、间距 7dp ——
           塞进去还剩两边留白（实测 170dp 档下排布不挤）。形状与全屏播放器
           的 CtlCircle 同语言（圆底半透明白描边）。 */
        val btnBack = relayKey(28, "-$step", 11.5f).apply {
            setOnClickListener {
                TheaterPlayer.step(-step * 1000L)
                flash("⟲ $step 秒")
                parkCtrl()
            }
        }
        val btnPlay = relayKey(36, "❚❚", 13f).apply {
            setOnClickListener {
                val st = TheaterPlayer.state.value
                if (st.playing) TheaterPlayer.pause() else TheaterPlayer.play()
                parkCtrl()
            }
        }
        val btnFwd = relayKey(28, "+$step", 11.5f).apply {
            setOnClickListener {
                TheaterPlayer.step(step * 1000L)
                flash("⟳ $step 秒")
                parkCtrl()
            }
        }
        keys.addView(btnBack)
        keys.addView(btnPlay)
        keys.addView(btnFwd)
        ctrl.addView(keys)
        toggle = btnPlay

        /* 整条贴底的进度条：轨道 3dp 半透明白、已播 3dp 品牌青（与全屏同色）。
           两层必须带 id（background/progress），否则 SeekBar 不认、会整条被拉满。 */
        val sb = android.widget.SeekBar(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(18),
            )
            max = 1000
            min = 0
            splitTrack = false
            setPadding(0, 0, 0, 0)
            thumbOffset = 0
            background = null
            progressDrawable = buildSeekDrawable()
            thumb = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setSize(dp(9), dp(9))
            }
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(s: android.widget.SeekBar) {
                    seekTracking = true
                    s.removeCallbacks(hideBarRun)      // 拖的时候别把控制层收了
                }
                override fun onStopTrackingTouch(s: android.widget.SeekBar) {
                    val d = TheaterPlayer.state.value.durMs
                    if (d > 0) TheaterPlayer.seek(d * s.progress / 1000L)
                    seekTracking = false
                    parkCtrl()
                }
            })
        }
        seekBar = sb
        ctrl.addView(sb)
        root.addView(ctrl)

        /* 角键：✕ 左上 / ⤢ 右上，**跟控制层同进同出**（2026-10-05 用户拍板：
           常显太抢画面 —— 收着时只有干净的画面，轻点唤出控制层时才一起出现，
           再点一下画面就收）。放进顶部 topRow（自带一层极淡的顶渐变，
           亮画面上键也看得清），topRow 的显隐由 hideBarRun 统一管。
           ✕ = **只收小窗**（2026-10-04 拍板 B）：放映照旧在后台播，不碰播放器、
           不跳页面 —— 给一句提示，免得人收完找不着画面入口。
           ⤢ = 收小窗 + 跳回放映页（竖屏，画面回全屏）。
           两颗键同尺寸同字号：原来 ⤢ 用 13f、× 用 16f，右上那颗在真机上
           明显小一圈（用户截图反馈"大小异常"）。 */
        val closeKey = cornerKey("×", 16f).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.START)
                .apply { leftMargin = dp(7); topMargin = dp(7) }
            setOnClickListener {
                teardown()
                stopSelf()
                android.widget.Toast.makeText(
                    this@FloatPlayerService,
                    "小窗已收起，放映继续 —— 首页圆钮回放映厅",
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val expandKey = cornerKey("⤢", 16f).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.TOP or Gravity.END)
                .apply { rightMargin = dp(7); topMargin = dp(7) }
            setOnClickListener { teardown(); stopSelf(); openCinema() }
        }
        val topRow = android.widget.FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(40), Gravity.TOP,
            )
            // 顶部向下的淡黑渐变：键压在亮画面上也看得清（与底部 ctrl 的渐变镜像）
            background = android.graphics.drawable.GradientDrawable().apply {
                colors = intArrayOf(0x7A000000, 0x00000000)
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            }
            addView(closeKey)
            addView(expandKey)
            visibility = View.GONE
        }
        relayTop = topRow
        root.addView(topRow)

        /* HUD 闪现：快进快退给个"⟲ 15 秒"的落点反馈（与全屏播放器手势同语言）。 */
        val hudTv = TextView(this).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12.5f
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(6), dp(13), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x99101216.toInt())
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            isClickable = false
            isFocusable = false
        }
        hud = hudTv
        root.addView(hudTv)

        // 手势：照播放窗 —— 拖动挪窗、单击唤控制条（3s 自收）、双击调大小
        val lp = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        var lastX = 0f
        var lastY = 0f
        var downX = 0
        var downY = 0
        var moved = false
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var lastTapAt = 0L
        val singleTapRun = Runnable {
            val b = bar ?: return@Runnable
            b.removeCallbacks(hideBarRun)
            if (b.visibility == View.VISIBLE) {
                // 再点一下画面 = **主动收起**（2026-10-05 用户拍板；3 秒自收仍保留）
                hideBarRun.run()
            } else {
                b.visibility = View.VISIBLE
                b.alpha = 1f
                relayTop?.let { it.visibility = View.VISIBLE; it.alpha = 1f }
                b.postDelayed(hideBarRun, 3_000)
            }
        }
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY
                    downX = lp.x; downY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - lastX
                    val dy = e.rawY - lastY
                    if (moved || abs(dx) > slop || abs(dy) > slop) {
                        moved = true
                        lp.x = (downX + dx).toInt().coerceAtLeast(0)
                        lp.y = (downY + dy).toInt().coerceAtLeast(0)
                        runCatching { m.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (e.action == MotionEvent.ACTION_UP && !moved) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastTapAt < 380) {
                            v.removeCallbacks(singleTapRun)
                            lastTapAt = 0
                            cycleSize(v)
                        } else {
                            lastTapAt = now
                            v.postDelayed(singleTapRun, 290)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        overlay = root
        windowLp = lp
        runCatching { m.addView(root, lp) }
            .onFailure { mark("回显窗 addView 失败: ${it.javaClass.simpleName} ${it.message}") }
    }

    /** 收窗并跳回放映厅（关闭键）：MainActivity 收到 action 就 openCinema。 */
    private fun openCinema() {
        val i = Intent(this, com.ticketfortwo.app.MainActivity::class.java)
            .setAction(com.ticketfortwo.app.MainActivity.ACTION_OPEN_CINEMA)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure { mark("回放映厅跳转失败: ${it.message}") }
    }

    private fun teardown() {
        startToken++               // 让未完成的异步起播作废并自释放
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(relayTicker)
        runCatching { overlay?.let { wm?.removeView(it) } }
        overlay = null
        windowLp = null
        inPrewarm = false
        bar = null
        seekBar = null
        timeView = null
        titleView = null
        toggle = null
        hud = null
        relayTop = null
        seekTracking = false
        videoView = null
        placeholder = null
        /* 回显窗的清理（2026-10-04）：渲染器摘 sink 并释放、归还播放器画面。
           只在 relay 模式做 —— 播放窗模式这些引用本来就是空的。 */
        if (relayMode) {
            relayTrack?.let { tr -> relayRenderer?.let { r -> runCatching { tr.removeSink(r) } } }
            relayRenderer?.let { r -> runCatching { r.release() } }
            relayRenderer = null
            relayTrack = null
            relayMode = false
            runCatching { TheaterPlayer.restoreFromRelay() }
        }
        runCatching { player?.release() }
        player = null
        FloatPlayer.update { FloatState() }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
        ).toInt()

    // ───────────────────────── 通知 ─────────────────────────

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "浮窗播放", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(title: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, FloatPlayerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("浮窗播放中")
            .setContentText(title.ifBlank { "点一下回到放映厅" })
            .setContentIntent(open)
            .addAction(0, "关闭浮窗", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.ticketfortwo.app.float.START"
        const val ACTION_PREWARM = "com.ticketfortwo.app.float.PREWARM"
        const val ACTION_SHOW = "com.ticketfortwo.app.float.SHOW"
        const val ACTION_STOP = "com.ticketfortwo.app.float.STOP"
        const val ACTION_RELAY_START = "com.ticketfortwo.app.float.RELAY_START"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REFERER = "referer"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_UA = "ua"
        const val EXTRA_POS = "pos"
        const val EXTRA_CANDIDATES = "candidates"

    /** 占位提示：首帧没到之前浮窗是空的，深底在深壁纸上几乎不可见
        （用户感知成"浮窗出现很慢"，其实窗口 0.5 秒就挂上了，慢的是画面）。 */
    private var placeholder: TextView? = null

        private const val NOTIF_ID = 1007
        private const val CHANNEL_ID = "t2_float"
        private const val BAR_H_DP = 46
    }
}
