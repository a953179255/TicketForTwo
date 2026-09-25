package com.ticketfortwo.app.ui.app

import android.annotation.SuppressLint
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.ticketfortwo.app.ui.glass.CompactGlassField
import com.ticketfortwo.app.ui.glass.GlassPageBar
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.glass.GlassTextButton
import com.ticketfortwo.app.ui.glass.LiquidToggle
import com.ticketfortwo.app.watch.WatchCmd
import com.ticketfortwo.app.watch.WatchState
import com.ticketfortwo.app.watch.WatchSync
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import kotlinx.coroutines.delay

/** 验证用的本地页：一个真正的 <video>，用来确认探针读得到、指令改得动。 */
const val WATCH_TEST_URL = "file:///android_asset/watch/test.html"

/**
 * 房主侧的"一起看"：App 内置浏览器 + 播放状态回灌 + 接受观众的控制指令。
 *
 * 为什么是内置 WebView，而不是去控制用户自己开的浏览器：
 * 控制**别人的**播放器在 Android 上只有两条路，两条都走不通 ——
 * 注入按键事件要 `INJECT_EVENTS`（签名级权限，上架应用拿不到），
 * `MediaSessionManager.getActiveSessions` 要通知监听权限，而且只能发
 * play/pause 这类通用键，给不了"跳到 12:34"。参考过的三个开源同看项目
 * （Synctv / SyncWatch / couple-cinema）没有一家做这件事，它们都让**每台设备
 * 各播一份**再对时 —— 那需要一台服务器和一个能拿到直链的播放器，
 * 和"我分享我这块屏"的产品形态正好相反。
 *
 * 所以这里反过来：播放器在我们自己手里，进度和指令都走已经建好的那条
 * 设备间 WebSocket。观众看到的画面本来就是这台手机，控制它天经地义。
 *
 * 注入方式只用 `evaluateJavascript`（Kotlin 问 / Kotlin 命令），
 * **不用 addJavascriptInterface** —— 后者会把 Kotlin 对象挂到 window 上，
 * 页面里任意脚本都能调，是 WebView 出过最多 CVE 的那类接口。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WatchTogetherScreen(
    backdrop: LayerBackdrop,
    /** 观众是不是真的在线 —— 只用来决定要不要提示"现在没人能控制"。 */
    viewerOnline: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var pageUrl by remember { mutableStateOf(WATCH_TEST_URL) }
    var inputUrl by remember { mutableStateOf(WATCH_TEST_URL) }
    var state by remember { mutableStateOf(CallSession.watch.value) }
    var allowControl by remember { mutableStateOf(CallSession.viewerMayControl.value) }
    var lastCmdAt by remember { mutableLongStateOf(0L) }
    var lastCmdLabel by remember { mutableStateOf<String?>(null) }
    // HTML5 全屏：很多网页播放器点"全屏"是换一个 View 上来，不接住它就是点了没反应
    var fullScreenView by remember { mutableStateOf<View?>(null) }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 网页播放器要点一下才允许出声，同看场景里"点一下"是房主在手机上点的，
            // 但自动续播、观众远程按播放都不该被这个手势要求挡住。
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // 混合内容按兼容模式：只放行"同源升级"这类，不一刀切允许 http 资源。
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 去掉 " wv"：不少站点看到 WebView 标记就直接拒绝服务。
            runCatching {
                settings.userAgentString = settings.userAgentString.replace(" wv", "")
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false // 一律在内部打开，不给外部浏览器接手
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    val v = view ?: return
                    fullScreenView = v
                }

                override fun onHideCustomView() {
                    fullScreenView = null
                }
            }
        }
    }

    LaunchedEffect(pageUrl) { webView.loadUrl(pageUrl) }

    // 每秒问一次页面。太密会跟网页自己的渲染抢主线程，太疏观众那边的进度条会跳。
    LaunchedEffect(pageUrl) {
        var ticks = 0
        while (true) {
            delay(1_000)
            webView.evaluateJavascript(WatchSync.probeJs()) { raw ->
                val s = WatchSync.parseProbe(raw, pageUrl) ?: return@evaluateJavascript
                state = s
                CallSession.publishWatchState(s)
            }
            // 顺手把"多久没收到观众指令"这件事显示出来（超过 12 秒就清空）
            if (++ticks % 12 == 0 && lastCmdLabel != null) lastCmdLabel = null
        }
    }

    // 观众的指令从 WebSocket 线程过来，WebView 只能在自己线程上 evaluate。
    // 注册在 DisposableEffect 里：这一屏卸载时必须摘掉，否则指令会打到已销毁的 WebView 上。
    DisposableEffect(webView) {
        CallSession.onWatchCommand = { cmd ->
            val now = System.currentTimeMillis()
            if (now - lastCmdAt < 250) {
                // 连点保护：观众手快连点 5 次 +10s，实际只会跳一次。
                // 参考 SyncWatch 的做法（它对自动同步有 1.8s 冷却），这里对手动指令取更短的窗口。
            } else {
                lastCmdAt = now
                lastCmdLabel = cmd.label()
                val pos = state?.posMs ?: 0L
                val dur = state?.durMs ?: 0L
                webView.post { webView.evaluateJavascript(WatchSync.jsFor(cmd, pos, dur), null) }
            }
        }
        onDispose { CallSession.onWatchCommand = null }
    }

    Column(Modifier.fillMaxSize()) {
        GlassPageBar(backdrop, title = "一起看", onBack = onClose) {
            Text(
                if (viewerOnline) "对方可控制" else "还没有人加入",
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
                modifier = Modifier.weight(1f),
            )
            PrimaryPill(text = "打开", onClick = { pageUrl = normalizeUrl(inputUrl) }, backdrop = backdrop)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = GlassDimens.screenH, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassTextButton("测试片", onClick = { inputUrl = WATCH_TEST_URL; pageUrl = WATCH_TEST_URL }, backdrop)
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = GlassDimens.screenH)
        ) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            state?.let {
                if (!it.found) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("这个页面里没找到播放器", fontSize = 12.5.sp, color = Ink.TextMid)
                }
            }
        }

        WatchTransportBar(
            backdrop = backdrop,
            state = state,
            allowControl = allowControl,
            onAllowChange = {
                allowControl = it
                CallSession.setViewerMayControl(it)
            },
            cmdLabel = lastCmdLabel,
            onLocal = { cmd ->
                lastCmdLabel = cmd.label()
                webView.evaluateJavascript(WatchSync.jsFor(cmd, state?.posMs ?: 0, state?.durMs ?: 0), null)
            },
        )
    }

    // HTML5 全屏的播放器盖在最上面；退出全屏靠页面自己的按钮，这里只保证不残留。
    if (fullScreenView != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { fullScreenView!! }, modifier = Modifier.fillMaxSize())
        }
    }
}

/** 补协议：只输入 bilibili.com 时按 https 打开。 */
fun normalizeUrl(input: String): String {
    val t = input.trim()
    return when {
        t.isEmpty() -> t
        t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://") -> t
        else -> "https://$t"
    }
}

/**
 * 房主自己的播放条。
 *
 * 那颗"对方可以控制"的开关放在这里而不是设置页：同看时才用得上，
 * 而且房主要能一眼看到"现在我把方向盘交出去了"。
 */
@Composable
private fun WatchTransportBar(
    backdrop: LayerBackdrop,
    state: WatchState?,
    allowControl: Boolean,
    onAllowChange: (Boolean) -> Unit,
    cmdLabel: String?,
    onLocal: (WatchCmd) -> Unit,
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        // 两行高的卡用卡片圆角：radiusIsland 是 9999dp 胶囊，圆角会被钳到短边一半，
        // 两端变成半圆、把第一行文字吃进弧里（放映厅那张卡同一处缺陷，截图实测过）
        radius = GlassDimens.radiusCard,
        surfaceAlpha = 0.72f,
        content = {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleControl(onClick = { onLocal(WatchCmd.Step(-10_000)) }, backdrop = backdrop) {
                        Text("-10", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.TextHi)
                    }
                    Box(Modifier.width(8.dp))
                    CircleControl(
                        onClick = { onLocal(if (state?.playing == true) WatchCmd.Pause else WatchCmd.Play) },
                        backdrop = backdrop,
                    ) {
                        Text(if (state?.playing == true) "❚❚" else "▶", fontSize = 13.sp, color = Ink.TextHi)
                    }
                    Box(Modifier.width(8.dp))
                    CircleControl(onClick = { onLocal(WatchCmd.Step(10_000)) }, backdrop = backdrop) {
                        Text("+10", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.TextHi)
                    }
                    Box(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            (state?.title ?: "等待页面加载").ifEmpty { "—" },
                            fontSize = 12.sp, color = Ink.TextHi, maxLines = 1,
                        )
                        Text(
                            "${WatchSync.formatTime(state?.posMs ?: 0)} / ${WatchSync.formatTime(state?.durMs ?: 0)}",
                            fontSize = 11.sp, color = Ink.TextMid,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LiquidToggle(allowControl, { onAllowChange(it) }, backdrop)
                    Box(Modifier.width(8.dp))
                    Text(
                        if (allowControl) "对方可以控制播放" else "只有我能控制",
                        fontSize = 11.5.sp, color = Ink.TextMid,
                    )
                    Spacer(Modifier.weight(1f))
                    cmdLabel?.let {
                        Text("观众：$it", fontSize = 11.sp, color = Ink.AccentOnDark)
                    }
                }
            }
        },
    )
}

/**
 * 观众侧的同看条：房主播放器的只读镜像 + 三个控制键。
 *
 * 它压在观众自己的控制岛上方，和顶部条一样随控件一起收起 —— 全屏看片时不该有第三条横幅。
 *
 * 进度不做本地乐观更新：观众按了 +10 之后本地不动，等房主下一次广播（约 1 秒）把新进度带回来。
 * 这样两边只有一个真相，不会出现"我这边看着跳了、他那边其实没动"这种更难解释的假象。
 */
@Composable
fun WatchMirrorBar(
    backdrop: LayerBackdrop,
    state: WatchState?,
    allowed: Boolean,
    onCmd: (String, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = state ?: return
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        radius = GlassDimens.radiusIsland,
        surfaceAlpha = 0.72f,
        // 底下压着 SurfaceView 时玻璃抓不到画面，只能退成磨砂（见 CallScreen 的同一处说明）
        refract = false,
        content = {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.title.ifEmpty { "一起看" },
                        fontSize = 12.sp, color = Ink.TextHi, maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (allowed) "可控制" else "仅观看",
                        fontSize = 10.5.sp,
                        color = if (allowed) Ink.Live else Ink.TextLow,
                    )
                }
                if (!s.found) {
                    Text("对方还没打开播放器", fontSize = 11.sp, color = Ink.TextMid)
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        WatchKey("‑10", enabled = allowed) { onCmd("step", -10_000) }
                        WatchKey(if (s.playing) "❚❚" else "▶", enabled = allowed) {
                            onCmd(if (s.playing) "pause" else "play", 0)
                        }
                        WatchKey("+10", enabled = allowed) { onCmd("step", 10_000) }
                        Text(
                            "${WatchSync.formatTime(s.posMs)} / ${WatchSync.formatTime(s.durMs)}",
                            fontSize = 11.sp, color = Ink.TextMid,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun WatchKey(text: String, enabled: Boolean, onClick: () -> Unit) {
    GlassTextButton(
        text = text,
        onClick = onClick,
        backdrop = null,
        enabled = enabled,
        refract = false,
    )
}
