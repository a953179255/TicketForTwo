package com.ticketfortwo.app.ui.app

import android.util.Log
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
    onWatchCmd: (String, Long) -> Unit = { _, _ -> },
    /** 房主侧：打开内置浏览器一起看。 */
    onOpenWatch: () -> Unit = {},
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
            HostStage(backdrop, peerLabel, onOpenWatch)
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
                    Text("语音对话中（对方未分享画面）", fontSize = 13.sp, color = Ink.TextMid)
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
        if (!isHost && watch != null && chromeShown) {
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
@Composable
private fun HostStage(backdrop: LayerBackdrop, peerLabel: String, onOpenWatch: () -> Unit) {
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
            }
        }
    }
}
