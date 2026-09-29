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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ticketfortwo.app.MainActivity
import com.ticketfortwo.app.R

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
    private var playerView: PlayerView? = null
    private var titleView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 进度回传：界面与"指挥权"判断都靠它。500ms 足够顺，又不至于空转。 */
    private val ticker = object : Runnable {
        override fun run() {
            publish()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        FloatPlayer.service = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_STOP -> { teardown(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> start(
                url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY,
                title = intent.getStringExtra(EXTRA_TITLE) ?: "",
                referer = intent.getStringExtra(EXTRA_REFERER),
                cookie = intent.getStringExtra(EXTRA_COOKIE),
                ua = intent.getStringExtra(EXTRA_UA),
                posMs = intent.getLongExtra(EXTRA_POS, 0L),
            )
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

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        /* 播不起来就老实认错并交还指挥权：最坏情况退回"网页里照播"，
                           不会把放映搞坏（这是设计里对防盗链站点的兜底）。 */
                        FloatPlayer.update {
                            it.copy(ready = false, playing = false, error = error.message)
                        }
                        android.util.Log.w("FloatPlay", "浮窗起播失败: ${error.message}")
                    }
                })
                setMediaItem(MediaItem.fromUri(url))
                // 从网页那个播放器接着播，不从头来
                if (posMs > 0) seekTo(posMs)
                playWhenReady = true
                prepare()
            }
        player = exo

        FloatPlayer.update {
            it.copy(
                active = true, url = url, title = title,
                playing = false, posMs = posMs, durMs = 0L, ready = false, error = null,
            )
        }
        addOverlay(exo, title)
        startForeground(NOTIF_ID, buildNotification(title))
        handler.post(ticker)
    }

    private fun publish() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            FloatPlayer.update { it.copy(playing = false) }
            return
        }
        val dur = p.duration.takeIf { it > 0 } ?: 0L
        FloatPlayer.update {
            it.copy(
                playing = p.isPlaying,
                posMs = p.currentPosition.coerceAtLeast(0L),
                durMs = dur,
                /* ready = 真的在播。刚 prepare() 完 isPlaying 常常还是 false
                   （HLS 要缓冲），拿它当交接判据会出现"两头都没播"的空白。 */
                ready = p.isPlaying && p.currentPosition > 0L,
            )
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
        val w = dp(WIN_W_DP)
        val h = dp(WIN_H_DP) + dp(BAR_H_DP)

        val radius = dp(14).toFloat()
        val root = FrameLayout(this).apply {
            /* 圆角（2026-09-30 用户反馈：直角四角不好看）。
               两层都要：背景画圆角色块 + 轮廓裁剪把里面方角的视频一起切圆，
               只做背景的话 PlayerView 的直角会从圆角底下探出来。 */
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
        val pv = PlayerView(this).apply {
            player = exo
            useController = false          // 自己画一条小控制条，不用默认那一大块
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(WIN_H_DP),
            )
        }
        playerView = pv
        root.addView(pv)

        val bar = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(BAR_H_DP), Gravity.BOTTOM,
            )
            setBackgroundColor(0xCC000000.toInt())
        }
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
        // 拖动：按住窗任意处挪动（吸附到屏幕边缘内的 clamp 由 motion 事件算）
        var lastX = 0f
        var lastY = 0f
        var downX = 0
        var downY = 0
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX; lastY = e.rawY; downX = lp.x; downY = lp.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (downX + (e.rawX - lastX)).toInt().coerceAtLeast(0)
                    lp.y = (downY + (e.rawY - lastY)).toInt().coerceAtLeast(0)
                    runCatching { m.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }

        overlay = root
        runCatching { m.addView(root, lp) }
    }

    private fun teardown() {
        handler.removeCallbacks(ticker)
        runCatching { overlay?.let { wm?.removeView(it) } }
        overlay = null
        playerView?.player = null
        playerView = null
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

        private const val NOTIF_ID = 1007
        private const val CHANNEL_ID = "t2_float"
        private const val WIN_W_DP = 170
        private const val WIN_H_DP = 96
        private const val BAR_H_DP = 30
    }
}
