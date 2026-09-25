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
import com.ticketfortwo.app.cinema.CinemaProbe
import com.ticketfortwo.app.cinema.CinemaSync
import com.ticketfortwo.app.cinema.MediaSniffer
import com.ticketfortwo.app.cinema.SnifferState
import com.ticketfortwo.app.watch.WatchCmd
import com.ticketfortwo.app.watch.WatchSync
import com.ticketfortwo.app.ui.glass.CompactGlassField
import com.ticketfortwo.app.ui.glass.GlassPageBar
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.glass.GlassTextButton
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import kotlinx.coroutines.delay

/** 试嗅探用的公开 HLS 测试流（Mux 官方测试台，无需登录、无 DRM）。 */
const val CINEMA_TEST_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

/** 试嗅探用的普通单文件页（本地资产，不依赖网络）。 */
const val CINEMA_TEST_LOCAL = WATCH_TEST_URL

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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var pageUrl by remember { mutableStateOf(initialUrl?.takeIf { it.isNotBlank() } ?: CINEMA_TEST_LOCAL) }
    var inputUrl by remember { mutableStateOf(pageUrl) }
    var hits by remember { mutableStateOf<List<MediaSniffer.Hit>>(emptyList()) }
    var probe by remember { mutableStateOf<MediaSniffer.PageProbe?>(null) }
    var eme by remember { mutableStateOf<CinemaProbe.EmeReport?>(null) }
    var seenRequests by remember { mutableStateOf(0) }
    var showPanel by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("把这一页当成浏览器用；下面会列出嗅到的片源") }
    var fullScreenView by remember { mutableStateOf<View?>(null) }
    /** 放映状态（会话里那份的本地镜像，只为画 UI）。 */
    val cinema by CallSession.cinema.collectAsState()
    /** 房主这一侧播放器的位置/时长/标题 —— 直接复用 watch 那套探针，形状一样。 */
    var player by remember { mutableStateOf<com.ticketfortwo.app.watch.WatchState?>(null) }

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
    LaunchedEffect(initialUrl) {
        val u = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (u != pageUrl) {
            inputUrl = u
            pageUrl = normalizeUrl(u)
            note = "收到递进来的链接，换片中…"
        }
    }

    // EME 探测：先发起（结果写到 window 上），再轮询读 —— 不赌 WebView 会不会 await Promise
    LaunchedEffect(pageUrl) {
        webView.post { webView.evaluateJavascript(MediaSniffer.emeStartJs(), null) }
        var tries = 0
        while (tries < 20) {
            delay(800)
            webView.evaluateJavascript(MediaSniffer.emeReadJs()) { raw ->
                val r = CinemaProbe.parseEme(raw)
                eme = r
                if (r.state != "running") {
                    Log.i("Cinema", "EME ${r.state} api=${r.api} keys=${r.createKeys} err=${r.detail}")
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

    Column(Modifier.fillMaxSize()) {
        GlassPageBar(backdrop, title = "放映厅", onBack = onBack) {
            Text(
                "厅已开 · 等对方进来",
                fontSize = 11.5.sp,
                color = Ink.Live,
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
                modifier = Modifier.weight(1f),
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
            // 放映/收厅：房主确认才切 —— 嗅探有认错的时候（广告分片、预告片），
            // 自动切等于把误判直接端给对方。
            GlassTextButton(if (cinema == null) "开始放映" else "收厅", onClick = {
                if (cinema != null) {
                    CallSession.setCinemaTrack(null)
                    note = "已收厅，对方那边退回等候屏"
                } else {
                    val h = CinemaProbe.bestOf(hits)
                    if (h == null) {
                        note = "还没嗅到可播的地址，先让片子播起来"
                    } else {
                        CallSession.setCinemaTrack(
                            CinemaSync.Track(
                                url = h.url,
                                kind = h.kind.name.lowercase(),
                                title = player?.title?.takeIf { it.isNotBlank() }
                                    ?: MediaSniffer.shorten(h.url, 40),
                                durationMs = player?.durMs ?: 0L,
                            ),
                        )
                        note = "已把这条递给对方：${h.kind}"
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

        if (showPanel) SnifferPanel(
            backdrop = backdrop,
            probe = probe,
            eme = eme,
            hits = hits,
            note = note,
            onPick = { h ->
                context.copy("片源", h.url)
                note = "已复制：${MediaSniffer.shorten(h.url, 40)}"
            },
        )
    }

    if (fullScreenView != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { fullScreenView!! }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 嗅探结果面板：一句总览 + 候选列表。 */
@Composable
private fun SnifferPanel(
    backdrop: LayerBackdrop,
    probe: MediaSniffer.PageProbe?,
    eme: CinemaProbe.EmeReport?,
    hits: List<MediaSniffer.Hit>,
    note: String,
    onPick: (MediaSniffer.Hit) -> Unit,
) {
    val playable = hits.filter { MediaSniffer.playable(it.kind) }
    val best = CinemaProbe.bestOf(playable)
    GlassPanel(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        radius = GlassDimens.radiusIsland,
        surfaceAlpha = 0.78f,
        content = {
            Column(
                Modifier
                    .height(214.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "嗅探",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextHi,
                    )
                    Text(
                        "  ${playable.size} 条候选 / 共 ${hits.size} 条媒体请求",
                        fontSize = 11.sp,
                        color = Ink.TextMid,
                    )
                }
                Text(
                    probe?.let {
                        val sz = if (it.videoWidth > 0) "${it.videoWidth}×${it.videoHeight}" else "未出画面"
                        "页面里的 <video>：$sz · ${it.durationSec.toInt()}s" +
                            " · ${if (it.isBlob) "blob:（MSE，源码地址在 JS 里）" else "直链"}"
                    } ?: "还没问到页面",
                    fontSize = 11.sp,
                    color = if (probe?.isBlob == true) Ink.Warn else Ink.TextLow,
                )
                Text(CinemaProbe.describeEme(eme), fontSize = 11.sp, color = Ink.TextLow)
                Box(Modifier.width(1.dp).height(6.dp))
                if (best != null) {
                    Text(
                        "最佳候选：${best.kind} · ${MediaSniffer.shorten(best.url, 64)}",
                        fontSize = 11.sp,
                        color = Ink.Live,
                    )
                }
                if (playable.isEmpty()) {
                    Text(
                        "还没嗅到可播的地址。打开一个真的在放片的页面看看。",
                        fontSize = 11.sp,
                        color = Ink.TextLow,
                    )
                }
                playable.forEach { h ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${h.kind.name.take(4)} · ${h.hits}次 · ${if (h.sources and 2 != 0) "页面" else "请求"}",
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
                Text(note, fontSize = 10.5.sp, color = Ink.TextLow)
            }
        }
    )
}

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

private fun Context.copy(label: String, text: String) {    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
}
