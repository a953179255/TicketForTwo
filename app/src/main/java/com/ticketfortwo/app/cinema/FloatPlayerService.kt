package com.ticketfortwo.app.cinema

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
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
    private var titleView: TextView? = null
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

    /**
     * 从多个候选里找出 master 并挑低档变体；全是变体/拉不到就用第一条原样返回。
     * 页面嗅探常常给的是**变体清单**（看不出码率），master 才有完整的档位表 —— 所以逐个试。
     */
    private fun pickVariant(urls: List<String>, headers: Map<String, String>): String {
        var fallback = urls.firstOrNull() ?: return urls.firstOrNull().orEmpty()
        for (u in urls.distinct()) {
            val r = resolveVariant(u, headers)
            if (r != u) {           // 找到了 master 并成功挑档
                mark("挑档成功 ${u.substringAfterLast('/')} -> ${r.substringAfterLast('/')}")
                return r
            }
            // r == u：u 本身是变体或拉取失败；先把第一条能直接用的记作兜底
            if (fallback == urls.first()) fallback = u
        }
        mark("挑档失败，用原地址 ${fallback.substringAfterLast('/')}")
        return fallback
    }

    /**
     * 从 master 清单里挑一个**适合浮窗**的低档变体清单地址；不是 master / 拉不到就原样返回。
     *
     * 选档规则（窗最大 276dp≈725px）：
     *  1. 宽 ≤960 里带宽最高的（给放大留 1.3 倍余量，实测 mux 这条流会选到 848x480/0.84Mbps）；
     *  2. 没有就退 ≤1280 里带宽最高的（720p/2.1Mbps）；
     *  3. 再没有就用原地址（下一档兜底：播放器自己选，行为同以前）。
     */
    private fun resolveVariant(url: String, headers: Map<String, String>): String {
        if (!url.contains(".m3u8", ignoreCase = true)) return url
        val text = runCatching { fetchText(url, headers) }.getOrNull() ?: return url
        if (!text.contains("#EXT-X-STREAM-INF")) return url   // 已经是变体清单
        val lines = text.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<Triple<Int, Int, String>>()  // width, bandwidth, url
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)
            if (next.isNullOrBlank() || next.startsWith("#")) continue
            val w = Regex("""RESOLUTION=(\d+)x""").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val abs = runCatching { URL(URL(url), next).toString() }.getOrDefault(next)
            variants += Triple(w, bw, abs)
        }
        if (variants.isEmpty()) return url
        val chosen = variants.filter { it.first <= 960 }.maxByOrNull { it.second }
            ?: variants.filter { it.first <= 1280 }.maxByOrNull { it.second }
            ?: return url
        return chosen.third
    }

    /** 简单拉文本（限 64KB，够一份清单）。headers 用于防盗链站。 */
    private fun fetchText(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) return url
            conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(64 * 1024)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
        } finally {
            conn.disconnect()
        }
    }

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
            ACTION_START -> { mark("收到启动指令"); start(
                url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY,
                title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                referer = intent.getStringExtra(EXTRA_REFERER),
                cookie = intent.getStringExtra(EXTRA_COOKIE),
                ua = intent.getStringExtra(EXTRA_UA),
                posMs = intent.getLongExtra(EXTRA_POS, 0L),
                candidates = intent.getStringArrayExtra(EXTRA_CANDIDATES)?.toList()
                    ?: emptyList(),
            ) }
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
    ) {
        val headers = buildMap {
            referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
        /* 请求头必须和网页那次请求一致 —— 防盗链站点只认这个组合。
           UA 单独给（DefaultHttpDataSource 的 UA 走另一个开关）。 */
        val ds = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        if (!ua.isNullOrBlank()) ds.setUserAgent(ua)

        /* 限档位到 720p —— 浮窗最宽 276dp（≈725px），下 HD/1080p 分片纯属浪费。
           打点实测瓶颈：网速仅 ~1.5MB/s 时，一个 HD 分片要下 4.2 秒（6.5MB），
           首帧被拖到 +10s。720p 以内字节量降一个量级，首帧显著提前。 */
        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = parameters.buildUpon().setMaxVideoSize(1280, 720).build()
        }
        val exo = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(
                // media3 1.8 合并成 setBufferDurationsMs(min, max, forPlayback, afterRebuffer)。
                // 后两项 2500/5000 → 500/500：ExoPlayer 默认要攒够 2.5 秒缓冲才肯开播，
                // 这是首帧慢的一大截来源（打点实测：窗口 +493ms 就挂上了，首帧却 +9.6s）
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50_000, 50_000, 500, 500)
                    .build(),
            )
            .build()
            .apply {
                addListener(object : Player.Listener {
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
                        FloatPlayer.update {
                            it.copy(ready = false, playing = false, error = error.message)
                        }
                        handler.post {
                            placeholder?.let {
                                it.text = "画面加载失败，稍后自动重试"
                                it.visibility = View.VISIBLE
                                it.alpha = 1f
                            }
                        }
                        android.util.Log.w("FloatPlay", "浮窗起播失败: ${error.message}")
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                // 从网页那个播放器接着播，不从头来
                if (posMs > 0) seekTo(posMs)
                addAnalyticsListener(object : AnalyticsListener {
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
                // 先不喂源：要先挑低档变体（见下方 resolveVariant），挑完再 setMediaItem+prepare
                playWhenReady = true
            }
        player = exo

        FloatPlayer.update {
            it.copy(
                active = true, url = url, title = title,
                playing = false, posMs = posMs, durMs = 0L, ready = false, error = null,
            )
        }
        mark(
            "约束生效检查 maxVideo=" +
                "${trackSelector.parameters.maxVideoWidth}x${trackSelector.parameters.maxVideoHeight}",
        )
        mark("播放器已建并 prepare")
        addOverlay(exo, title)
        mark("窗口已挂上（浮窗可见）")
        startForeground(NOTIF_ID, buildNotification(title))
        handler.post(ticker)

        /* 后台拉 master 清单挑低档变体，主线程不碰网络。
           为什么必须自己挑：media3 的 maxVideoSize 约束**实测对 HLS 初始变体选择无效**
           （约束 1280x720 已进选择器、日志可见，但仍然选了 url_8=1080p —— 13.5MB/片
           在 1.5MB/s 的网速下要下 8 秒，首帧被拖到 +7.5s）。
           浮窗最宽 276dp≈725px，480p(848px) 已完全够用，分片从 6.5~13.5MB 降到 <1MB。 */
        scope.launch {
            val target = withContext(Dispatchers.IO) { pickVariant(listOf(url) + candidates, headers) }
            withContext(Dispatchers.Main) {
                if (player !== exo) return@withContext        // 期间被收掉
                mark("起播(挑档后) ${target.substringAfterLast('/')}")
                exo.setMediaItem(MediaItem.fromUri(target))
                exo.prepare()
            }
        }
    }

    private fun publish() {
        val p = player ?: return
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

    private fun addOverlay(exo: ExoPlayer, title: String) {
        val m = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = m
        /* 窗高 = 画面高，**不再给控制条留独立空间**（2026-09-30 用户反馈：
           控制条藏了但占位还在）。控制条改为覆盖在画面内部（见下方 bar）。 */
        val w = dp(SIZES[sizeIdx].first)
        val h = dp(SIZES[sizeIdx].second)

        val radius = dp(14).toFloat()
        val root = FrameLayout(this).apply {
            /* 圆角要真正切到视频画面，**必须用 TextureView**：
               默认的 SurfaceView 是独立合成图层，视图的轮廓裁剪切不到它 ——
               上一版画面四角依旧是方的就是这个原因（用户 2026-09-30 实测）。
               背景画圆角色块 + 轮廓裁剪把里面（现在是 TextureView）一起切圆。 */
            val bg = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE6101116.toInt())
                cornerRadius = radius
                // 一圈极淡的描边，浮在亮壁纸上时才看得出边界
                setStroke(1, 0x33FFFFFF)
            }
            background = bg
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            clipToOutline = true
            elevation = dp(8).toFloat()
        }
        val video = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        root.addView(video)
        exo.setVideoTextureView(video)

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
           轻点窗身从画面里浮出来，3 秒自动收（2026-09-30 用户要求）。 */
        val bar = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(BAR_H_DP), Gravity.BOTTOM,
            )
            // 半透明压在画面上：看得清按钮，又不把画面整体遮掉一条
            setBackgroundColor(0xB3000000.toInt())
            visibility = View.GONE
        }
        this.bar = bar
        val t = TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL,
            ).apply { marginStart = dp(8) }
        }
        titleView = t
        bar.addView(t)

        /* 换片：回到放映厅里挑 —— 列表在 App 内（浮窗只有 170dp 宽，塞不下一张列表），
           所以这里只是发一个请求，由界面弹出来。 */
        val pick = TextView(this).apply {
            text = "换片"
            setTextColor(0xFF9EE8B8.toInt())
            textSize = 11f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(62) }
            setOnClickListener { FloatPlayer.onPickRequest?.invoke() }
        }
        bar.addView(pick)
        val close = TextView(this).apply {
            text = "×"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            layoutParams = FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(6) }
            setOnClickListener { teardown(); stopSelf() }
        }
        bar.addView(close)
        val toggle = TextView(this).apply {
            text = "❚❚"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            layoutParams = FrameLayout.LayoutParams(
                dp(28), dp(28), Gravity.END or Gravity.CENTER_VERTICAL,
            ).apply { marginEnd = dp(34) }
            setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (p.isPlaying) { p.pause(); text = "▶" } else { p.play(); text = "❚❚" }
            }
        }
        bar.addView(toggle)
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

    /** 控制条自动收起：轻点唤出，3 秒不碰就藏（见 addOverlay 的轻点分支）。 */
    private val hideBarRun = Runnable {
        bar?.animate()?.alpha(0f)?.setDuration(200)?.withEndAction {
            bar?.visibility = View.GONE
        }
    }

    private fun teardown() {
        handler.removeCallbacks(ticker)
        runCatching { overlay?.let { wm?.removeView(it) } }
        overlay = null
        bar = null
        placeholder = null
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
        const val ACTION_STOP = "com.ticketfortwo.app.float.STOP"
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
        private const val BAR_H_DP = 30
    }
}
