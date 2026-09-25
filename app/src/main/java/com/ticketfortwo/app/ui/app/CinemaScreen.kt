package com.ticketfortwo.app.ui.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.CallSession
import com.ticketfortwo.app.cinema.CinemaDebug
import com.ticketfortwo.app.cinema.CinemaProbe
import com.ticketfortwo.app.cinema.CinemaSync
import com.ticketfortwo.app.cinema.MediaSniffer
import com.ticketfortwo.app.cinema.SRC_PAGE
import com.ticketfortwo.app.cinema.SnifferState
import com.ticketfortwo.app.watch.WatchCmd
import com.ticketfortwo.app.watch.WatchSync
import com.ticketfortwo.app.ui.glass.CompactGlassField
import com.ticketfortwo.app.ui.glass.GlassPageBar
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.glass.GlassTextButton
import com.ticketfortwo.app.ui.glass.LiquidToggle
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import kotlinx.coroutines.delay

/** 试嗅探用的公开 HLS 测试流（Mux 官方测试台，无需登录、无 DRM）。 */
const val CINEMA_TEST_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

/** 试嗅探用的普通单文件页（本地资产，不依赖网络）。 */
const val CINEMA_TEST_LOCAL = WATCH_TEST_URL

/** 第二条内置测试流：URL 不同，专门用来测"放映中途换片"。 */
const val CINEMA_TEST_HLS_2 = "https://test-streams.mux.dev/pts_shift/master.m3u8"

/**
 * 等多久就算"对方没给回执"。
 *
 * 观众侧自己有个 8 秒看门狗（没首帧就退回屏幕流并回一条 fail），所以正常路径上
 * 回执最迟 8 秒 + 一个来回就到；这里取 12 秒，是给"对方页面是旧版、根本不会发回执"
 * 留的余量 —— 那种情况下房主看到的应该是"没等到回执"，而不是无限期挂着"等对方出画面"。
 */
private const val ACK_WAIT_MS = 12_000L

/**
 * 放映厅 · 房主侧第一版：**厅先开，人先进来，片子后选**。
 *
 * 这一屏现在承担两件事：
 * 1. **P0 量具** —— 把"能不能从真实站点嗅到观众也能播的地址"变成屏幕上看得见的数字。
 *    S 档整条路线押在这件事上，所以先测再写播放链路。
 * 2. 厅的骨架 —— 地址栏 / 内置播放器 / 候选列表，后面"开始放映"就接在这里。
 *
 * 注入只用 `evaluateJavascript`，**不挂 addJavascriptInterface**：
 * 房主打开的是陌生网站，把 Kotlin 对象挂到 window 上等于给它一个回调我们的后门。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CinemaScreen(
    backdrop: LayerBackdrop,
    /** 「分享 → 双人票」递进来的链接；非空就一进来就打开它，而不是停在本地测试页。 */
    initialUrl: String? = null,
    /** 厅已开但对方还没进来时，这一条就是邀请链接 —— 厅的入口动作是"发链接"。 */
    inviteUrl: String? = null,
    viewerOnline: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pageUrl by remember { mutableStateOf(initialUrl?.takeIf { it.isNotBlank() } ?: CINEMA_TEST_LOCAL) }
    var inputUrl by remember { mutableStateOf(pageUrl) }
    var hits by remember { mutableStateOf<List<MediaSniffer.Hit>>(emptyList()) }
    var probe by remember { mutableStateOf<MediaSniffer.PageProbe?>(null) }
    var eme by remember { mutableStateOf<CinemaProbe.EmeReport?>(null) }
    var seenRequests by remember { mutableStateOf(0) }
    /**
     * 量具（嗅探候选 / 页面读数 / EME）默认**收着**。
     *
     * 这张卡是房主全程盯着的那一块，而放映时他真正要看的只有三件事：
     * 对方在不在放、放到哪、方向盘给不给。把 P0 的读数常驻在上面，
     * 等于让量具抢了界面的位置（实测：放映中的卡有六成行数是嗅探日志）。
     * 需要挑候选、查为什么嗅不到时，点「展开嗅探」即可 —— 它没有消失，只是不再常驻。
     */
    var showPanel by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("把这一页当成浏览器用；嗅到的地址在「展开嗅探」里面") }
    var fullScreenView by remember { mutableStateOf<View?>(null) }
    /** 放映状态（会话里那份的本地镜像，只为画 UI）。 */
    val cinema by CallSession.cinema.collectAsState()
    /** 对方那边到底播出来了没有 —— 没有这条回执时，"正在放映"三个字是半真半假的。 */
    val playback by CallSession.viewerPlayback.collectAsState()
    /** 房主这一侧播放器的位置/时长/标题 —— 直接复用 watch 那套探针，形状一样。 */
    var player by remember { mutableStateOf<com.ticketfortwo.app.watch.WatchState?>(null) }
    /** 方向盘给不给对方。会话里那份是真值，这里只是本地即时反馈（点下去先亮起来）。 */
    val mayControl by CallSession.viewerMayControl.collectAsState()
    var allowControl by remember { mutableStateOf(mayControl) }
    LaunchedEffect(mayControl) { allowControl = mayControl }

    val sniffer = remember { SnifferState() }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 不少站点看到 UA 里的 " wv" 就拒绝服务
            runCatching { settings.userAgentString = settings.userAgentString.replace(" wv", "") }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                /**
                 * 只看不拦：记完就返回 null，让 WebView 照常去网络取。
                 *
                 * 这里刻意不做代理 —— 一旦返回自己的 WebResourceResponse，
                 * 这一页的加载就全押在我们的转发上（Range、压缩、重定向都得自己实现），
                 * 那是 A 档中继该做的事，不该在量具阶段顺手做掉。
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val r = request ?: return null
                    val isNew = sniffer.observe(r)
                    if (isNew) {
                        val snap = sniffer.snapshot().firstOrNull { it.url == r.url.toString() }
                        if (snap != null && snap.kind != MediaSniffer.Kind.Segment) {
                            Log.i("Cinema", "SNIFF kind=${snap.kind} url=${snap.url}")
                        }
                        /* 嗅到新候选就立刻刷界面。
                         *
                         * 原来 `hits` 只在每 2 秒那次页面探针里更新，而**探针不是每次都成功**：
                         * 直接在 WebView 里打开一条 .m3u8 时用的是 Chromium 自带播放器，
                         * 影子 DOM 里的 `<video>` 不一定问得到 → 探针返回空 → 列表不刷新。
                         * 结果就是"日志明明嗅到了、屏幕上却写着厅里还没选片"，
                         * 命中率测量脚本因此整轮报"没递出"（实测就是这么翻的）。
                         * 回调在 WebView 的工作线程上，所以得 post 回主线程再改状态。 */
                        view?.post { hits = sniffer.snapshot() }
                    }
                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    fullScreenView = view
                }

                override fun onHideCustomView() {
                    fullScreenView = null
                }
            }
        }
    }

    LaunchedEffect(pageUrl) {
        sniffer.clear()
        hits = emptyList()
        probe = null
        webView.loadUrl(pageUrl)
    }

    // 厅已经开着的时候又来了一条分享（singleTop + onNewIntent）：换片，不重开 Activity。
    //
    // 三种情况必须分开：冷启动首帧（这一屏本来就是为这条链接开的，别再刷一遍）、
    // 换一条新链接（正常换页）、**同一条链接第二次递进来**。
    // 最后一种原来什么都不做，于是"我明明分享了，屏幕却没反应"——
    // 而人重复分享，多半是因为第一次没成（页面报错、被挡、想重看），
    // 所以正确的响应是重新加载这一页，并让人看见我们在动。
    var handledShare by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialUrl) {
        val u = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val norm = normalizeUrl(u)
        when {
            handledShare == u -> {
                webView.loadUrl(pageUrl)
                note = "又是这一条，重新加载这一页"
            }
            norm != pageUrl -> {
                inputUrl = u
                pageUrl = norm
                note = "收到递进来的链接，换片中…"
            }
        }
        handledShare = u
    }

    // EME 探测：先发起（结果写到 window 上），再轮询读 —— 不赌 WebView 会不会 await Promise
    LaunchedEffect(pageUrl) {
        webView.post { webView.evaluateJavascript(MediaSniffer.emeStartJs(), null) }
        var tries = 0
        while (tries < 20) {
            delay(800)
            webView.evaluateJavascript(MediaSniffer.emeReadJs()) { raw ->
                val r = CinemaProbe.parseEme(raw)
                // 换页会把 window 上的暂存结果一起冲掉（state=missing）——
                // 这时要重新发起探测，而不是把 "missing" 当成最终结论显示给房主。
                if (r.state == "missing") {
                    webView.post { webView.evaluateJavascript(MediaSniffer.emeStartJs(), null) }
                } else {
                    eme = r
                    if (r.state != "running") {
                        Log.i("Cinema", "EME ${r.state} api=${r.api} keys=${r.createKeys} err=${r.detail}")
                    }
                }
            }
            if (eme?.state?.let { it != "running" && it != "pending" } == true) break
            tries++
        }
    }

    // 每 2 秒问一次页面：currentSrc / 时长 / 尺寸 + Resource Timing 里的媒体 URL
    LaunchedEffect(pageUrl) {
        while (true) {
            delay(2_000)
            webView.evaluateJavascript(MediaSniffer.probeJs()) { raw ->
                val p = CinemaProbe.parsePageProbe(raw) ?: return@evaluateJavascript
                probe = p
                sniffer.observePageProbe(p)
                hits = sniffer.snapshot()
                seenRequests = sniffer.count()
                if (p.resources.isNotEmpty() || p.currentSrc.isNotBlank()) {
                    Log.i(
                        "Cinema",
                        "PAGEPROBE blob=${p.isBlob} dur=${p.durationSec.toInt()}s " +
                            "size=${p.videoWidth}x${p.videoHeight} res=${p.resources.size}",
                    )
                }
            }
            // 位置/时长/标题：复用 watch 的探针（它已经在算"页面上最大那个 <video>"），
            // 不再另写一份，免得两套探测逻辑以后各改各的。
            webView.evaluateJavascript(WatchSync.probeJs()) { raw ->
                val w = WatchSync.parseProbe(raw, pageUrl) ?: return@evaluateJavascript
                player = w
                CallSession.publishCinemaProgress(w.posMs, w.durMs, w.playing)
            }
        }
    }

    // 观众的放映请求落到这个 WebView 上（它才是播放器）。
    // 注册/摘除成对：这一屏卸载后还挂着回调，指令就会打到已销毁的 WebView 上。
    DisposableEffect(webView) {
        CallSession.onCinemaCommand = { cmd ->
            val p = player?.posMs ?: 0L
            val d = player?.durMs ?: 0L
            webView.post { webView.evaluateJavascript(WatchSync.jsFor(cmd.toWatchCmd(), p, d), null) }
        }
        onDispose { CallSession.onCinemaCommand = null }
    }

    /**
     * 把一条候选递给对方（或换成它）。
     *
     * 放映中再点另一条 = **换片**，不需要先收厅再放：收厅那一步在观众那边
     * 会真的退回屏幕流，用它当中转等于白闪一下。
     */
    fun screen(h: MediaSniffer.Hit) {
        val wasScreening = cinema != null
        CallSession.setCinemaTrack(
            CinemaSync.Track(
                url = h.url,
                kind = h.kind.name.lowercase(),
                title = player?.title?.takeIf { t -> t.isNotBlank() && !t.startsWith("http", true) }
                    ?: MediaSniffer.hostLabel(h.url),
                durationMs = player?.durMs ?: 0L,
            ),
        )
        note = if (wasScreening) "已换片：${h.kind.name.lowercase()}"
        else "已把这条递给对方：${h.kind.name.lowercase()}"
    }

    /* debug 钩子：把"递出 App 自己嗅到的那条地址"暴露给 adb 广播。
       测真实站点命中率时必须走这条，而不是我手写一个 URL 递出去 ——
       那样测的是传输通道，测不到嗅探与选路。 */
    DisposableEffect(Unit) {
        CinemaDebug.screenBest = {
            val h = CinemaProbe.bestOf(hits)
            if (h == null) null else { screen(h); h.url }
        }
        CinemaDebug.candidateCount = { hits.count { MediaSniffer.playable(it.kind) } }
        onDispose {
            CinemaDebug.screenBest = null
            CinemaDebug.candidateCount = null
        }
    }

    Column(Modifier.fillMaxSize()) {
        GlassPageBar(backdrop, title = "放映厅", onBack = onBack) {
            Text(
                if (viewerOnline) "对方已在厅里" else "厅已开 · 等对方进来",
                fontSize = 11.5.sp,
                color = if (viewerOnline) Ink.Live else Ink.TextLow,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = GlassDimens.screenH, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactGlassField(
                value = inputUrl,
                onValueChange = { inputUrl = it },
                label = "地址",
                // 权重给到 1f 之外还要留缝：不加 weight 时限宽的行为是"文字压在按钮下面"，
                // 实测长 URL 会一路顶到「打开」按钮底下，看着像按钮粘在字上。
                modifier = Modifier.weight(1f).padding(end = 2.dp),
            )
            PrimaryPill(text = "打开", onClick = {
                pageUrl = normalizeUrl(inputUrl)
                note = "正在打开，嗅探中…"
            }, backdrop = backdrop)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = GlassDimens.screenH, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassTextButton("本地测试页", onClick = {
                inputUrl = CINEMA_TEST_LOCAL; pageUrl = CINEMA_TEST_LOCAL
            }, backdrop)
            GlassTextButton("HLS 测试流", onClick = {
                inputUrl = CINEMA_TEST_HLS; pageUrl = CINEMA_TEST_HLS
            }, backdrop)
            GlassTextButton(if (showPanel) "收起嗅探" else "展开嗅探", onClick = {
                showPanel = !showPanel
            }, backdrop)
            GlassTextButton("换一条流", onClick = {
                inputUrl = CINEMA_TEST_HLS_2; pageUrl = CINEMA_TEST_HLS_2
            }, backdrop)
            // 放映/收厅：房主确认才切 —— 嗅探有认错的时候（广告分片、预告片），
            // 自动切等于把误判直接端给对方。
            GlassTextButton(if (cinema == null) "开始放映" else "收厅", onClick = {
                if (cinema != null) {
                    CallSession.setCinemaTrack(null)
                    note = "已收厅，对方那边退回等候屏"
                } else {
                    val h = CinemaProbe.bestOf(hits)
                    if (h == null) {
                        // 分开说两种"没候选"：一种是真的没嗅到，一种是嗅到了但只有本机能播。
                        // 后者不解释的话，房主会以为按下去对方就该看到了。
                        note = if (CinemaProbe.localOnly(hits) != null) {
                            "嗅到的是本机文件地址（file://），对方播不了 —— 打开一个网页里的播放器再试"
                        } else {
                            "还没嗅到可播的地址，先让片子播起来"
                        }
                    } else {
                        screen(h)
                    }
                }
            }, backdrop)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = GlassDimens.screenH),
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }

        /* 这张卡**一直在**：它是厅的控制面（邀请、放映状态、方向盘开关），
           「收起嗅探」收的只是量具那几行，不是整张卡。
           原来写成 `if (showPanel || cinema != null)`，于是"默认收着量具 + 还没选片"
           这两个条件一叠加，整张卡直接消失，屏幕上只剩一块黑 —— 量具默认收起之后
           第一时间就踩到了（截图实测）。 */
        CinemaPanel(
            backdrop = backdrop,
            cinema = cinema,
            player = player,
            allowControl = allowControl,
            onAllowChange = {
                allowControl = it
                CallSession.setViewerMayControl(it)
            },
            hits = hits,
            note = note,
            showSniffer = showPanel,
            probe = probe,
            eme = eme,
            inviteUrl = inviteUrl,
            viewerOnline = viewerOnline,
            playback = playback,
            onCopyInvite = {
                inviteUrl?.let {
                    context.copy("邀请链接", it)
                    note = "邀请链接已复制，发给对方就能进厅"
                }
            },
            onPick = { h -> screen(h) },
        )
    }

    if (fullScreenView != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { fullScreenView!! }, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * 厅的底部卡。**放映中**和**没选片**是两副样子 ——
 * 房主按下"开始放映"之后，最想知道的是"对方在不在看、放到哪了、我能不能把方向盘收回来"，
 * 而不是那串嗅探日志。嗅探列表退到"展开嗅探"后面（它是量具，不是日常界面）。
 */
@Composable
private fun CinemaPanel(
    backdrop: LayerBackdrop,
    cinema: CinemaSync.State?,
    player: com.ticketfortwo.app.watch.WatchState?,
    allowControl: Boolean,
    onAllowChange: (Boolean) -> Unit,
    hits: List<MediaSniffer.Hit>,
    note: String,
    showSniffer: Boolean,
    probe: MediaSniffer.PageProbe?,
    eme: CinemaProbe.EmeReport?,
    inviteUrl: String?,
    /** 厅里有没有人。没人的时候不该写"等对方回执"。 */
    viewerOnline: Boolean,
    /** 对方那边这条到底播没播起来；null = 还没回执。 */
    playback: CinemaSync.PlaybackAck?,
    onCopyInvite: () -> Unit,
    onPick: (MediaSniffer.Hit) -> Unit,
) {
    // 计数也只数"对方真能播的"：把 file:// 算进"1 条可播地址"是骗房主。
    val playable = hits.filter {
        MediaSniffer.playable(it.kind) && it.url.startsWith("http", ignoreCase = true)
    }
    /** 现在到底有没有在分享画面 —— 厅先开那条路是不投屏的，措辞要跟着这个走。 */
    val localVideo by CallSession.localVideo.collectAsState()
    val screenShared = localVideo != null
    /* 换片之后重新开始等回执。跟着 version 走而不是跟 cinema 走：
       进度每秒都在更新 cinema，那样这个定时器会被无限续期，永远不超时。 */
    var ackWaited by remember { mutableStateOf(false) }
    LaunchedEffect(cinema?.version) {
        ackWaited = false
        if (cinema == null) return@LaunchedEffect
        delay(ACK_WAIT_MS)
        ackWaited = true
    }
    /* "正在放映"后面那句副标题，只能由对方的回执决定。
     *
     * 原来这里写死"对方本地播 · 原生画质"：对方那片黑着，房主这边照样一脸笃定。
     * 措辞放在 CinemaSync.describeAck 里（能被单测打），这里只管配颜色。 */
    val ackLine = CinemaSync.describeAck(playback, viewerOnline, ackWaited)
    val ackColor = when (ackLine.tone) {
        CinemaSync.AckTone.Live -> Ink.Live
        CinemaSync.AckTone.Bad -> Ink.Error
        CinemaSync.AckTone.Warn -> Ink.Warn
        CinemaSync.AckTone.Waiting -> Ink.TextMid
        CinemaSync.AckTone.Neutral -> Ink.TextLow
    }
    GlassPanel(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        /* 这张卡是多行的，不能用 radiusIsland —— 那个 token 是 9999dp 的**胶囊**，
           Compose 会把圆角钳到短边一半，于是卡的两端变成两个半圆，
           第一行标题正好落在半圆里，看着就像"字被玻璃边缘切掉"（截图实测过）。
           胶囊留给单行条（顶栏、控制条），多行卡用卡片圆角。
           折射也关掉：底下压着的是 WebView 的 SurfaceView，玻璃抓不到画面，
           折射环只会把一圈黑色扭着糊到文字上（同 CallScreen / 观众镜像条的结论）。 */
        radius = GlassDimens.radiusCard,
        refract = false,
        surfaceAlpha = 0.78f,
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 用 heightIn 而不是固定 height：固定高度会把卡片自己的内容切掉
                    // （实测：标题被截在上缘、最后一行 URL 被切一半），
                    // 内容短时又该收起来，不该撑着一块空玻璃。
                    .then(
                        if (showSniffer) {
                            Modifier.heightIn(max = 236.dp).verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (cinema != null) {
                    // 放映中
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "正在放映",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.Live,
                        )
                        Text(
                            "  ${ackLine.head}",
                            fontSize = 11.sp,
                            color = ackColor,
                            // 标题行必须锁一行：App 观众那句长话原来在这里换行，
                            // 第二行正好压在"正在放映"下面，两个字叠在一起（截图实测）。
                            maxLines = 1,
                        )
                    }
                    // 长话另起一行；没有长话就不占位（不留一行空白）
                    ackLine.detail?.let {
                        Text(
                            it,
                            fontSize = 10.5.sp,
                            color = ackColor,
                            lineHeight = 14.sp,
                            maxLines = 2,
                        )
                    }
                    Text(
                        cinema.track.title.ifBlank { CinemaSync.sanitize(cinema.track.url) },
                        fontSize = 12.sp,
                        color = Ink.TextHi,
                        maxLines = 1,
                    )
                    Text(
                        "${CinemaSync.formatTime(player?.posMs ?: cinema.posMs)} / " +
                            "${CinemaSync.formatTime(player?.durMs ?: cinema.durMs)} · " +
                            (if (player?.playing == true) "播放中" else "暂停"),
                        fontSize = 11.sp,
                        color = Ink.TextLow,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LiquidToggle(allowControl, { onAllowChange(it) }, backdrop)
                        Box(Modifier.width(8.dp))
                        Text(
                            if (allowControl) "对方可以控制进度" else "进度只由我这边动",
                            fontSize = 11.sp,
                            color = if (allowControl) Ink.Live else Ink.TextMid,
                        )
                    }
                } else {
                    // 还没选片
                    Text(
                        if (playable.isEmpty()) "厅里还没选片" else "嗅到 ${playable.size} 条可播地址",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextHi,
                    )
                    // 厅先开、对方先进来 —— 所以"把链接发出去"是这一屏的第一动作，
                    // 不是分享流程的副产品。
                    if (!inviteUrl.isNullOrBlank()) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                MediaSniffer.shorten(inviteUrl, 44),
                                fontSize = 10.5.sp,
                                color = Ink.TextMid,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Box(Modifier.width(8.dp))
                            GlassTextButton("复制邀请", onClick = onCopyInvite, backdrop = backdrop)
                        }
                    }
                    Text(
                        /* 这句话原来写死"对方现在看到的是你的屏幕" —— 可厅先开这条路
                           **根本不投屏**（只起信令 + 语音），观众看到的是一块等候屏。
                           措辞跟着事实走：有没有在分享画面，是问出来的不是假设的。 */
                        if (screenShared)
                            "对方现在看到的是你的屏幕。按「开始放映」，他就改成自己播这条流 " +
                                "—— 画质原生，也不再压两层控件。要手挑候选就点「展开嗅探」。"
                        else
                            "厅里现在只有语音：对方看到的是一块等候屏。按「开始放映」，" +
                                "他那边就本地播这条流 —— 画质原生，你的屏幕也不用分享出去。" +
                                "要手挑候选就点「展开嗅探」。",
                        fontSize = 11.sp,
                        color = Ink.TextMid,
                        lineHeight = 16.sp,
                    )
                }
                if (showSniffer) {
                    Text(
                        "点一条就放给对方（放映中点另一条 = 换片，不用先收厅）",
                        fontSize = 10.sp,
                        color = Ink.TextLow,
                    )
                    // 下面是 P0 那两条量具读数：平时收着，出问题时要一眼能看到。
                    Text(
                        probe?.let {
                            val sz = if (it.videoWidth > 0) "${it.videoWidth}×${it.videoHeight}" else "未出画面"
                            "页面 <video>：$sz · ${it.durationSec.toInt()}s · " +
                                (if (it.isBlob) "blob:（MSE）" else "直链")
                        } ?: "还没问到页面",
                        fontSize = 10.5.sp,
                        color = if (probe?.isBlob == true) Ink.Warn else Ink.TextLow,
                    )
                    Text(
                        CinemaProbe.describeEme(eme),
                        fontSize = 10.5.sp,
                        color = Ink.TextLow,
                    )
                    playable.forEach { h ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${h.kind.name.take(4)} · ${h.hits}次 · ${if (h.sources and SRC_PAGE != 0) "页面" else "请求"}",
                                fontSize = 10.sp,
                                color = Ink.TextLow,
                            )
                            Box(Modifier.width(8.dp))
                            Text(
                                MediaSniffer.shorten(h.url, 58),
                                fontSize = 10.5.sp,
                                color = Ink.TextMid,
                                maxLines = 2,
                                modifier = Modifier.weight(1f).clickable { onPick(h) },
                            )
                        }
                    }
                }
                Text(note, fontSize = 10.5.sp, color = Ink.TextLow)
            }
        }
    )
}

/** 与 cinema 包里那份是同一个常量，直接 import，不在这里另立一个 2。 */

/**
 * 放映指令最终要落到房主这个 WebView 上，而"怎么往页面里注脚本"只有 watch 那一套（已测）。
 * 这里做一层映射，不再抄第二份 jsFor —— 两处各写一遍"怎么跳 10 秒"，以后一定只改得动一处。
 */
private fun CinemaSync.Cmd.toWatchCmd(): WatchCmd = when (this) {
    CinemaSync.Cmd.Play -> WatchCmd.Play
    CinemaSync.Cmd.Pause -> WatchCmd.Pause
    is CinemaSync.Cmd.Seek -> WatchCmd.Seek(ms)
    is CinemaSync.Cmd.Step -> WatchCmd.Step(deltaMs)
}

private fun Context.copy(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
