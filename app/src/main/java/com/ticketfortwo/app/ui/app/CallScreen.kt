package com.ticketfortwo.app.ui.app

import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.watch.WatchState
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 视频层。
 *
 * libwebrtc 落在 SurfaceView 上，这决定了一条 UI 事实：**SurfaceView 的内容抓不到**
 * （backdrop 维护者在 issue #98 亲口确认，haze 同结论），所以浮在它上面的顶部条与控制岛
 * 只能用 scrim，不能是采样玻璃 —— 见下面两处 `refract = false`。
 */
@Composable
fun VideoLayer(
    track: VideoTrack?,
    modifier: Modifier = Modifier,
    onLabel: String = "video",
    onFirstFrame: (Boolean) -> Unit = {},
    /** 帧尺寸变化（含首次）。观众端靠它判断"对方是不是横屏了"。 */
    onResolution: (Int, Int) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    var ready by remember { mutableStateOf(false) }

    Box(modifier) {
        AndroidView(
            factory = {
                RtcEngine.init(context)
                // 传 RendererEvents 而不是 null：首帧到底有没有落到这块 Surface 上，
                // 是"黑屏"与"还没来帧"唯一的区分依据。没有它，视频区出任何问题都只能靠猜。
                renderer.init(
                    RtcEngine.eglBase.eglBaseContext,
                    object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            Log.i("VideoLayer", "$onLabel first frame rendered")
                            // 回调在渲染线程上，Compose 状态必须回主线程改
                            renderer.post { onFirstFrame(true) }
                        }

                        override fun onFrameResolutionChanged(w: Int, h: Int, rot: Int) {
                            Log.i("VideoLayer", "$onLabel resolution ${w}x$h rot=$rot")
                            // 帧尺寸是"对方横没横屏"唯一的证据。上一版这里只打日志就完了，
                            // 于是内容变成横的、观众屏还竖着，画面缩成中间一条。
                            renderer.post { onResolution(w, h) }
                        }
                    },
                )
                ready = true
                renderer
            },
            onRelease = { renderer.release() },
            modifier = Modifier.fillMaxSize(),
        )
    }
    DisposableEffect(track, ready) {
        val t = track
        if (t != null && ready) t.addSink(renderer)
        onDispose { if (t != null && ready) t.removeSink(renderer) }
    }
}

/**
 * 分享中 / 观看中：顶部状态条 + 底部控制岛，中间按角色分两种内容。
 *
 * **房主不给实时自预览** —— 这不是省事的决定，是实测出来的结论：
 * 采集源就是当前这一块屏，把它的画面再画回这块屏上，画的是"上一帧的自己"，
 * 递归下去只会得到一片黑，或者一层层错位衰减的残影（两种都在模拟器上截到了）。
 * 更糟的是它白占一路解码 + 一块 SurfaceView，而编码器正在抢同一块 GPU。
 * 所以房主中间给的是"对方看到的就是你现在这屏"的确认信息；
 * 真要"确认到底在播什么"，正确做法是抽**一帧静图**显示，那属于 M4 的诊断页。
 *
 * 观众侧才是真视频：远端轨 → SurfaceView。
 */
@Composable
fun CallScreen(
    backdrop: LayerBackdrop,
    /** 只用于观众侧。房主不给实时自预览，理由见下面的注释。 */
    remoteTrack: VideoTrack?,
    isHost: Boolean,
    peerLabel: String,
    micOn: Boolean,
    onToggleMic: () -> Unit,
    latencyMs: Int?,
    netLabel: String,
    /** 只给观众侧用：帧尺寸变化上报，用来做"跟随对方横竖屏"。 */
    onContentResolution: (Int, Int) -> Unit = { _, _ -> },
    /** 非空才画那颗方向按钮（房主侧没有"跟随对方"这回事）。 */
    orientationLabel: String? = null,
    onCycleOrientation: () -> Unit = {},
    /** 观众侧右半屏滑动调音量（0..1）。房主侧手势层不挂，这个回调不会被调用。 */
    onViewerVolume: (Float) -> Unit = {},
    /** 观众侧：房主播放器的状态镜像；没开同看时是 null，整条同看 UI 就不画。 */
    watch: WatchState? = null,
    watchAllowed: Boolean = false,
    /** 放映厅：房主选了片。App 内不本地播，这张卡只做"告诉你他在放什么 + 让你能按"。 */
    cinema: com.ticketfortwo.app.cinema.CinemaSync.State? = null,
    cinemaAllowed: Boolean = false,
    onCinemaCmd: (com.ticketfortwo.app.cinema.CinemaSync.Cmd) -> Unit = {},
    onWatchCmd: (String, Long) -> Unit = { _, _ -> },
    /** 房主侧：打开内置浏览器一起看。 */
    onOpenWatch: () -> Unit = {},
    onOpenCinema: () -> Unit = {},
    /** 观众侧正在画中画：玻璃控件与手势层全部让路，小窗里只留画面。 */
    pipMode: Boolean = false,
    onStop: () -> Unit,
) {
    // 播放器的惯例：控件几秒后自己收起，点一下再出来。
    // 之所以只给观众侧做：房主那屏中间是提示卡不是视频，收掉控件就等于没有内容可看。
    // chromeTick 而不是直接改 chromeVisible —— 手势层唤出时要让计时重新开始，
    // 直接把 true 赋给已经是 true 的状态不会触发重组，倒计时就永远不再走。
    var chromeVisible by remember { mutableStateOf(true) }
    var chromeTick by remember { mutableIntStateOf(0) }
    if (!isHost) {
        LaunchedEffect(chromeTick) {
            if (chromeTick == 0) return@LaunchedEffect
            chromeVisible = true
            delay(3_000)
            chromeVisible = false
        }
        // 首帧到达 = 用户最想看"对方那屏长什么样"的时刻，此时控件必须在；
        // 停留 4 秒后收起，让画面独占屏幕。
        LaunchedEffect(remoteTrack) {
            if (remoteTrack != null) {
                chromeVisible = true
                delay(4_000)
                chromeVisible = false
            }
        }
    }
    // 画中画里只留画面：小窗拢共几百像素宽，两条玻璃横幅压上去就把画面糊成一团，
    // 而且小窗上的点击该由系统接管（点一下回到 App），不该再被手势层吃掉。
    val chromeShown = !pipMode && chromeVisible

    // 这里**不能**给 Box 铺不透明底色：SurfaceView 的合成面在窗口之下，靠"挖洞"显示，
    // 而 Compose 里父节点的不透明 background 会把那块洞重新填平 ——
    // 实测现象就是"日志说 first frame rendered、分辨率 810x1800，屏幕上一片纯黑"。
    // 视频区背后由根层的 AmbientBackground 兜底，首帧到达前显示等待文案。
    Box(Modifier.fillMaxSize()) {
        // 房主这屏没有 SurfaceView，玻璃就能正常采样环境底；
        // 观众那屏视频压在最下面，SurfaceView 的内容抓不到（backdrop issue #98），只能退化成 scrim。
        if (isHost) {
            HostStage(backdrop, peerLabel, onOpenWatch, onOpenCinema)
        } else {
            // 首帧没到之前这块区域是纯黑 —— 用户分不清"对方画面全黑"和"卡住了"，所以必须有等待提示。
            var firstFrame by remember(remoteTrack) { mutableStateOf(false) }
            // 语音模式：连上 2.5 秒还没有视频轨 → 对方开的是"仅语音"。
            // 轨一到就立刻撤掉这个判断（effect 以 remoteTrack 为 key 重启）。
            var voiceMode by remember { mutableStateOf(false) }
            LaunchedEffect(remoteTrack) {
                if (remoteTrack == null) {
                    delay(2_500)
                    voiceMode = true
                } else {
                    voiceMode = false
                }
            }
            VideoLayer(
                track = remoteTrack,
                modifier = Modifier.fillMaxSize(),
                onLabel = "remote",
                onFirstFrame = { firstFrame = true },
                onResolution = onContentResolution,
            )
            when {
                voiceMode -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        /* 厅先开 + 观众用 App：他这边一块黑，底部条却写着"正在放映"，
                           两句都对、合起来却读不出"该干什么"。放映中时直接把下一步说出来。 */
                        if (cinema != null)
                            "他在放片，但 App 里播不了这条流\n用浏览器打开那条链接就能看原画"
                        else
                            "语音对话中（对方未分享画面）",
                        fontSize = 13.sp,
                        color = Ink.TextMid,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                !firstFrame -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("等待对方画面…", fontSize = 13.sp, color = Ink.TextMid)
                }
            }
            // 手势层铺在视频之上、控件之下：它自己是全透明的，只吃指针事件。
            // 画中画时不挂：那颗小窗的点击归系统，我们不该拦。
            if (!pipMode) ViewerGestureLayer(
                chromeVisible = chromeVisible,
                // 真"切换"而不是只唤出：控件已经在了还点一下，播放器惯例是立刻收回去，
                // 而不是"再等 3 秒才消失"——那样用户会觉得点了没反应。
                onToggleChrome = { if (chromeVisible) chromeVisible = false else chromeTick++ },
                onVolume = onViewerVolume,
            )
        }

        // 顶部状态条（观众侧随控件一起收起 —— 全屏看画面时不留横幅）
        if (isHost || chromeShown) GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 36.dp, start = 12.dp, end = 12.dp),
            radius = GlassDimens.radiusIsland,
            surfaceAlpha = 0.72f,
            refract = isHost,
            content = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(7.dp)
                            .height(7.dp)
                            .background(Ink.Error, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isHost) "正在分享" else "正在观看",
                        fontSize = 11.5.sp,
                        color = Ink.TextHi,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(peerLabel, fontSize = 11.5.sp, color = Ink.TextMid)
                }
            },
        )

        // 同看条：只有观众侧、且房主真的开了同看时才画；和控件一起收起，
        // 不然全屏看片时等于第三条横幅永久压在对方画面上。
        if (!isHost && watch != null && cinema == null && chromeShown) {
            WatchMirrorBar(
                backdrop = backdrop,
                state = watch,
                allowed = watchAllowed,
                onCmd = onWatchCmd,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = GlassDimens.islandBottom + 84.dp),
            )
        }

        // 放映厅条：App 内不本地播（这条流多半绑 Referer/Cookie，这里也没带 hls.js），
        // 所以这张卡要**说实话**：你在看的还是他的屏幕，但你能按。
        // 和同看条互斥 —— 两条一起出现就是三层 UI 里的第三层。
        if (!isHost && cinema != null && chromeShown) {
            CinemaMirrorBar(
                backdrop = backdrop,
                state = cinema,
                allowed = cinemaAllowed,
                hasVideo = remoteTrack != null,
                onCmd = onCinemaCmd,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = GlassDimens.islandBottom + 84.dp),
            )
        }

        if (isHost || chromeShown) ControlIsland(
            backdrop = backdrop,
            micOn = micOn,
            onToggleMic = onToggleMic,
            onStop = onStop,
            latencyLabel = latencyMs?.let { "$it" } ?: "—",
            netLabel = netLabel,
            orientationLabel = orientationLabel,
            onCycleOrientation = onCycleOrientation,
            stopDesc = if (isHost) "停止分享" else "停止观看",
            refract = isHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = GlassDimens.islandBottom),
        )
    }
}

/**
 * 房主舞台：不画视频，只说清"现在正在播什么"。
 *
 * 卡片文案只留用户用得上的事实：**分享跟着你跨应用**。这是这类工具最容易
 * 翻车的地方 —— 有人以为"退出 App 就停了"，结果相册、聊天窗口全被对方看到了。
 * 至于"为什么不放实时预览"，那是我们内部的设计取舍，不该出现在用户界面上。
 */
/**
 * 观众侧的放映厅条。
 *
 * **视频区之上只用 scrim，不用玻璃** —— 这个仓库里已经踩过：backdrop/haze 抓不到
 * SurfaceView 的内容，玻璃盖上去会糊成一块纯黑（见 compose-surfaceview-glass-limit）。
 *
 * 卡片上那句"你看到的还是他的屏幕"是故意写的：App 内没有本地播放这条路，
 * 不写清楚就等于让用户以为放映厅没生效。
 */
@Composable
private fun CinemaMirrorBar(
    backdrop: LayerBackdrop,
    state: com.ticketfortwo.app.cinema.CinemaSync.State,
    allowed: Boolean,
    /** 现在到底有没有画面进来（厅先开那条路是不投屏的，那时这句得换）。 */
    hasVideo: Boolean,
    onCmd: (com.ticketfortwo.app.cinema.CinemaSync.Cmd) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = com.ticketfortwo.app.cinema.CinemaSync
    Box(
        modifier
            .padding(horizontal = 12.dp)
            .background(androidx.compose.ui.graphics.Color(0xA0000000), RoundedCornerShape(20.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("正在放映", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink.Live)
                Spacer(Modifier.width(8.dp))
                Text(
                    state.track.title.ifBlank { "对方选的那条" },
                    fontSize = 12.sp,
                    color = Ink.TextHi,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (allowed) "可控制" else "仅观看",
                    fontSize = 10.sp,
                    color = if (allowed) Ink.Live else Ink.TextLow,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cs.formatTime(state.posMs) + " / " + cs.formatTime(state.durMs),
                    fontSize = 11.sp,
                    color = Ink.TextMid,
                    modifier = Modifier.weight(1f),
                )
                CircleControl(onClick = { onCmd(com.ticketfortwo.app.cinema.CinemaSync.Cmd.Step(-10_000)) }, backdrop = backdrop) {
                    Text("-10", fontSize = 13.sp, color = Ink.TextHi)
                }
                Spacer(Modifier.width(8.dp))
                CircleControl(
                    onClick = { onCmd(if (state.playing) com.ticketfortwo.app.cinema.CinemaSync.Cmd.Pause else com.ticketfortwo.app.cinema.CinemaSync.Cmd.Play) },
                    backdrop = backdrop,
                ) {
                    Text(if (state.playing) "❚❚" else "▶", fontSize = 12.sp, color = Ink.TextHi)
                }
                Spacer(Modifier.width(8.dp))
                CircleControl(onClick = { onCmd(com.ticketfortwo.app.cinema.CinemaSync.Cmd.Step(10_000)) }, backdrop = backdrop) {
                    Text("+10", fontSize = 13.sp, color = Ink.TextHi)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                /* 原来这句写死"看到的还是他的屏幕"。可厅先开那条路**根本不投屏**，
                   观众这边一块黑 + 一条语音，看到的是什么屏幕都没有。
                   措辞跟着有没有画面走，别拿假设当事实。 */
                if (hasVideo)
                    "这台手机上看到的还是他的屏幕；用浏览器打开链接可以本地播原画"
                else
                    "厅里现在只有语音，他还没把屏幕分享出来；用浏览器打开链接可以本地播原画",
                fontSize = 9.5.sp,
                color = Ink.TextLow,
                lineHeight = 13.sp,
            )
        }
    }
}

@Composable
private fun HostStage(
    backdrop: LayerBackdrop,
    peerLabel: String,
    onOpenWatch: () -> Unit,
    onOpenCinema: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = GlassDimens.screenH),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        GlassCardPanel(backdrop, Modifier.fillMaxWidth(), floating = true) {
            Column(
                Modifier.padding(GlassDimens.sp5),
                verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
            ) {
                Text("正在分享你的手机", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                Text(
                    "对方看到的就是你现在这一屏，而且跟着你走：切到别的应用、打开相册，" +
                        "他那边也同步换画面。想停就点下面的停止。",
                    fontSize = 12.5.sp,
                    color = Ink.TextMid,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(GlassDimens.sp1))
                Text(peerLabel, fontSize = 11.5.sp, color = Ink.TextLow)
                // 一起看：播放器在我们手里，对方才可能真的动得到进度。
                // 放在这张卡里而不是控制岛上 —— 控制岛要留给"通话级"的三个动作，
                // 而这一颗是"接下来要干什么"，和卡片说的是同一件事。
                PrimaryPill(
                    text = "一起看片",
                    onClick = onOpenWatch,
                    backdrop = backdrop,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 放映厅（S 档）：对方本地播同一条片源，画质原生、也没有两层 UI。
                // 会话进行中必须能进 —— 用户就是"先连上人、再决定看什么"，
                // 入口只放在首页等于逼人退回首页，那会打断正在放的画面。
                PrimaryPill(
                    text = "放映厅",
                    onClick = onOpenCinema,
                    backdrop = backdrop,
                    filled = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

