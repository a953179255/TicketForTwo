package com.ticketfortwo.app.ui.app

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.watch.WatchState
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import kotlin.math.roundToInt
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
    // `AndroidView(factory=…)` 只跑一次，注册给 libwebrtc 的监听器会**一直握着第一次组合的
    // lambda**。直接用它写外部状态，写的可能是已经被重建掉的旧 state（实测就是
    // "首帧到了、界面还写着等待对方画面"）。rememberUpdatedState 让回调读到的永远是当前这份。
    val frameCb by rememberUpdatedState(onFirstFrame)
    val resolutionCb by rememberUpdatedState(onResolution)
    var ready by remember { mutableStateOf(false) }
    // 帧的真实宽高（px）。首帧之前是 Zero ⇒ 视频区先铺满，尺寸到了再收成正确比例。
    var frameSize by remember { mutableStateOf(IntSize.Zero) }

    /* 把这块 Surface **摆成和帧一样的比例**，而不是铺满整屏再让渲染器去缩放。
       原因（2400x1080 实测，.dev/fit-portrait.png）：房主那屏是 540x1200 的竖帧，
       铺满横屏后仍按"填满"处理，上下各切掉四成以上 —— 观众看到的是中间一条，
       而"他看到的就是你现在这一屏"是这个产品的立身之本，切掉就等于说谎。
       自己按等比适中算出视图尺寸之后，视图比例与帧一致 ⇒ 无论渲染器内部默认是
       "铺满"还是"适中"（实测 setScalingType 在这里并不足以扭转结果），两种缩放同解。 */
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val f = frameSize
        val videoModifier = if (f.width <= 0 || f.height <= 0) {
            Modifier.fillMaxSize()
        } else {
            val s = minOf(
                constraints.maxWidth.toFloat() / f.width,
                constraints.maxHeight.toFloat() / f.height,
            )
            with(density) {
                Modifier.size((f.width * s).roundToInt().toDp(), (f.height * s).roundToInt().toDp())
            }
        }
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
                            renderer.post { frameCb(true) }
                        }

                        override fun onFrameResolutionChanged(w: Int, h: Int, rot: Int) {
                            Log.i("VideoLayer", "$onLabel resolution ${w}x$h rot=$rot")
                            frameSize = IntSize(w, h)
                            // 帧尺寸是"对方横没横屏"唯一的证据。上一版这里只打日志就完了，
                            // 于是内容变成横的、观众屏还竖着，画面缩成中间一条。
                            renderer.post { resolutionCb(w, h) }
                        }
                    },
                )
                ready = true
                // **不能裁**：不给缩放类型时，这块 Surface 实测是"铺满并切掉多出来的部分"。
                // 竖屏看竖屏时两者比例几乎一样，看不出问题；观众一点「横屏」就露馅 ——
                // 实测 2400x1080 的横屏里，房主那 1080x2400 的一屏被放大 2.22 倍后
                // 只剩中间 1080/5333 ≈ 20% 高的一条，上下全被切掉（.dev/ls-land3.png）。
                // 这个产品的立身之本是"他看到的就是你现在这一屏"，切掉就等于说谎，
                // 所以一律等比适中：比例不合就留边，一帧都不少。
                // （常量名带 SCALE_ 前缀，是这个 webrtc-sdk 分支的写法，别照抄上游）
                renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                renderer
            },
            onRelease = { renderer.release() },
            modifier = videoModifier,
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
    /** ICE 正在抖但**没掉线**：state 不降级（见 ViewerSession.jitter），
        只在画面上亮一条提示，说明"卡一下，正在自愈"（REVIEW-2026-09-27 P2）。 */
    jitter: Boolean = false,
    /** 只给观众侧用：帧尺寸变化上报，用来做"跟随对方横竖屏"。 */
    onContentResolution: (Int, Int) -> Unit = { _, _ -> },
    /** 非空才画那颗方向按钮（房主侧没有"跟随对方"这回事）。 */
    orientationLabel: String? = null,
    onCycleOrientation: () -> Unit = {},
    /**
     * 房主侧：顶栏左上角的"返回首页"。会话是前台服务撑着的，返回只是"人回首页"，
     * 分享不断 —— 首页那颗变身圆钮管"回去/停止"（见 HomeSession）。
     * null = 不画（观众侧；观众的返回语义是结束观看，见 MainActivity 的 BackHandler）。
     */
    onBack: (() -> Unit)? = null,
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
    /**
     * 房主侧：**此刻到底有没有在投屏**。
     *
     * 厅先开那条路根本不投屏（只起信令 + 语音），而这一屏原来无论什么状态都写着
     * "正在分享你的手机 / 对方看到的就是你现在这一屏"。实测（t2test 17:06）房主从放映厅
     * 按返回回到这一屏，系统投屏授权一次都没弹过，屏幕上却说他正在分享 ——
     * 这句既让他白担心"别切应用"，也让他以为不用再按分享那颗钮了。
     */
    screenSharing: Boolean = false,
    /** 非投屏状态下那颗「让他看我的屏幕」：走和首页一样的授权链。 */
    onStartShare: () -> Unit = {},
    /**
     * 房主侧：这一场的声音档，以及"你听不听得到对方"。
     *
     * 「只有视频声」会把房主侧的下行静音（观众的声音不放出来，顺手断掉一条回声路径）。
     * 那时房主看到的是"对方一直没说话"，真相是"他说了你听不见" —— 这两句话用户会做出
     * 完全不同的动作，所以必须写在屏幕上，不能留在实现里。
     */
    voiceLabel: String? = null,
    canHearViewer: Boolean = true,
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
    /* 放映**从有到无**的那一下要说一句。
     *
     * 实测（.dev/tx-b-1.png）：房主按「收厅」之后观众端的放映条整个消失、画面还在，
     * 屏幕上不留任何解释 —— 观众看到的是"我刚有的进度条没了"，很容易读成
     * "我是不是被踢了 / 是不是网断了"。条本身消失是对的（它跟着状态走），
     * 缺的是那一句。只在"曾经有过"时提醒，进厅就没片的人不该看到它。 */
    var hadCinema by remember { mutableStateOf(false) }
    var cinemaGoneNote by remember { mutableStateOf(false) }
    LaunchedEffect(cinema) {
        if (cinema != null) {
            hadCinema = true
            cinemaGoneNote = false
        } else if (hadCinema) {
            hadCinema = false
            cinemaGoneNote = true
            delay(4_500)
            cinemaGoneNote = false
        }
    }
    if (!isHost) {
        /* 单一收起倒计时 —— 原来是两个 effect（chromeTick / remoteTrack）各写
           chromeVisible，互相抢写、时机重叠（审查已知项第 1 条）。合并后三条规则：
           ① 远端轨首次到达 → 显示 4 秒（最想看清画面的时刻）；
           ② 用户唤出（chromeTick 变化）→ 显示 3 秒；
           ③ **暂停期间不收**：放映/同看暂停后所有控件一起消失，满屏只剩一帧静止
              画面，极易被读成"卡死了"——网页端同一条判据（pl.paused 就不收），
              这里用 paused 进 key：暂停立刻常驻，恢复后重新计时。
              纯屏幕分享没有"暂停"概念，行为不变（审查 P1-3）。 */
        val paused = cinema?.playing == false || watch?.playing == false
        LaunchedEffect(chromeTick, remoteTrack, paused) {
            if (paused) {
                chromeVisible = true
                return@LaunchedEffect
            }
            if (chromeTick == 0 && remoteTrack == null) return@LaunchedEffect
            chromeVisible = true
            delay(if (chromeTick == 0 && remoteTrack != null) 4_000L else 3_000L)
            chromeVisible = false
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
            HostStage(
                backdrop, peerLabel, onOpenWatch, onOpenCinema, screenSharing, onStartShare,
                voiceLabel = voiceLabel, canHearViewer = canHearViewer, micOn = micOn,
            )
        } else {
            // 首帧没到之前这块区域是纯黑 —— 用户分不清"对方画面全黑"和"卡住了"，所以必须有等待提示。
            // 不能拿 remoteTrack 当 key：轨道一到，state 就被重建成 false，
            // 而首帧事件可能已经在那之前发过了（配合 VideoLayer 的 rememberUpdatedState 才成立）。
            var firstFrame by remember { mutableStateOf(false) }
            LaunchedEffect(remoteTrack) {
                if (remoteTrack == null) firstFrame = false
            }
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
            // 放映中时**这句让位给下面那条放映条**：条上已经写着"厅里现在只有语音…
            // 用浏览器打开链接可以本地播原画"，中间再叠一句同义的话，横屏实测两句正好
            // 压在同一个带上（.dev/cinebar-capped.png："正在放映…"那行和"他在放片…"
            // 那行直接重叠）。但控件收起时条也没了，那时中间这句必须回来当唯一的说明。
            when {
                // && !pipMode：小窗里 chrome 和手势层都已经让路，这两句居中大字
                // 也不该往几百像素的小窗上压（审查 P3：浮条统一让路）。
                voiceMode && !pipMode && !(cinema != null && chromeShown) ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                // 只有**真的有视频轨**才谈得上"等画面"。厅先开那条路房主根本不投屏
                // （remoteTrack 为 null），这句却会永远挂在屏幕正中 —— 实测它还压在
                // 放映条那行字上（.dev/cinebar-final.png："…的那条"与"等待对方画面…"重叠）。
                !firstFrame && remoteTrack != null && !pipMode ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                // 横滑 ±10（与网页端同阈值 96、同语义）：只在"有条可按、且对方允许"时
                // 生效 —— 权限收回头时手势和镜像条按钮同一标准（REVIEW P3-15）。
                onStep = { delta ->
                    when {
                        cinema != null ->
                            if (cinemaAllowed) onCinemaCmd(com.ticketfortwo.app.cinema.CinemaSync.Cmd.Step(delta))
                        watch != null ->
                            if (watchAllowed) onWatchCmd("step", delta)
                    }
                },
            )
        }

        // 顶部状态条（观众侧随控件一起收起 —— 全屏看画面时不留横幅）
        // remoteTrack != null：厅先开全程没投屏，收厅后这句"现在看的是他的屏幕"
        // 是假话，还和正中"语音对话中（对方未分享画面）"直接打架（REVIEW-2026-09-27 P1）。
        if (!isHost && cinemaGoneNote && remoteTrack != null && !pipMode) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(bottom = 130.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0xCC000000),
                        RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("他停了放映，现在看的是他的屏幕", fontSize = 13.sp, color = Ink.TextHi)
            }
        }

        if (isHost || chromeShown) GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                // 状态栏安全区：原来固定 top=36dp 是按普通状态栏猜的，
                // 大状态栏/挖孔屏上顶栏会顶进图标堆里（REVIEW-2026-09-27 P3）
                .statusBarsPadding()
                .padding(top = 6.dp, start = 12.dp, end = 12.dp),
            radius = GlassDimens.radiusIsland,
            surfaceAlpha = 0.72f,
            refract = isHost,
            content = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 返回首页（房主侧）：分享不断，首页圆钮管"回去/停止"。点击目标 44dp 是触控下限。
                    if (onBack != null) {
                        androidx.compose.material3.IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(44.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回首页",
                                tint = Ink.TextHi,
                            )
                        }
                    }
                    // 红点 = "你的屏幕正在被别人看"。只有语音时它是绿的：该报警的时候别贬值。
                    Box(
                        Modifier
                            .width(7.dp)
                            .height(7.dp)
                            .background(
                                if (isHost && !screenSharing) Ink.Live else Ink.Error,
                                CircleShape,
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        // 房主侧这句跟着**有没有视频轨**走：厅先开时没投屏，写"正在分享"就是谎话。
                        if (isHost) { if (screenSharing) "正在分享" else "语音连麦中" } else "正在观看",
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
                    // 三键导航时岛整体上移，横条必须跟着加同样的 inset，层间距才不变
                    .navigationBarsPadding()
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
                    .navigationBarsPadding()
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
            stopDesc = if (isHost) { if (screenSharing) "停止分享" else "结束连麦" } else "停止观看",
            refract = isHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 三键导航（48dp）会把整颗岛压进导航栏里点不到（REVIEW-2026-09-27 P3）
                .navigationBarsPadding()
                .padding(bottom = GlassDimens.islandBottom),
        )

        /* ICE 抖动提示：state 不降级，用这条药丸说明"卡一下，正在自愈"——
           放在顶栏下方、和收厅浮条（屏幕中部）错开，两条同时出现也不叠字。 */
        if (!isHost && jitter && !pipMode) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 96.dp)
                    .background(
                        androidx.compose.ui.graphics.Color(0xCC000000),
                        RoundedCornerShape(percent = 50),
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text("连接抖动，正在自愈…", fontSize = 13.sp, color = Ink.TextHi)
            }
        }
    }
}

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
    /* 走秒与投影（REVIEW-2026-09-27 P2-8）：广播 2 秒一条，按下 ±10 后数字最长
       2 秒纹丝不动，看着像没生效。记住收包时刻，播放中显示 pos + 已流逝 ——
       projectedPos（协议里现成的、全仓原本零调用）就是干这个的；暂停时冻结。 */
    val receivedAt = remember(state) { SystemClock.elapsedRealtime() }
    var shownPosMs by remember(state) { mutableStateOf(state.posMs) }
    LaunchedEffect(state) {
        while (true) {
            shownPosMs = if (state.playing) {
                cs.projectedPos(state, (SystemClock.elapsedRealtime() - receivedAt).coerceAtLeast(0L))
                    .let { if (state.durMs > 0) it.coerceAtMost(state.durMs) else it }
            } else {
                state.posMs
            }
            delay(500)
        }
    }
    Box(
        modifier
            // 横屏实测（2400x1080，.dev/viewer-cinebar-land3.png）：不封顶时这张条被
            // 里面那行 weight(1f) 撑到整屏宽 —— "正在放映"贴最左、"可控制"和三颗按钮贴最右，
            // 一句话被拉到 2300px 两头，读起来要来回扫，而且中间空成一片。
            // 竖屏 411dp 减掉左右 12dp 还是 387dp，封顶 560dp 对竖屏毫无影响。
            .widthIn(max = 560.dp)
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
                    cs.formatTime(shownPosMs) + " / " + cs.formatTime(state.durMs),
                    fontSize = 11.sp,
                    color = Ink.TextMid,
                    modifier = Modifier.weight(1f),
                )
                // allowed（房主的"方向盘"开关）没开时三颗键置灰 —— 原来全亮着，
                // 按下去被 ViewerSession 的门禁静默吞掉，零反馈（审查 P1）。
                // 同看条早就有 enabled = allowed，两端标准在这里对齐。
                CircleControl(onClick = { onCmd(com.ticketfortwo.app.cinema.CinemaSync.Cmd.Step(-10_000)) }, backdrop = backdrop, enabled = allowed) {
                    Text("-10", fontSize = 13.sp, color = if (allowed) Ink.TextHi else Ink.TextLow)
                }
                Spacer(Modifier.width(8.dp))
                CircleControl(
                    onClick = { onCmd(if (state.playing) com.ticketfortwo.app.cinema.CinemaSync.Cmd.Pause else com.ticketfortwo.app.cinema.CinemaSync.Cmd.Play) },
                    backdrop = backdrop,
                    enabled = allowed,
                ) {
                    Text(if (state.playing) "❚❚" else "▶", fontSize = 12.sp, color = if (allowed) Ink.TextHi else Ink.TextLow)
                }
                Spacer(Modifier.width(8.dp))
                CircleControl(onClick = { onCmd(com.ticketfortwo.app.cinema.CinemaSync.Cmd.Step(10_000)) }, backdrop = backdrop, enabled = allowed) {
                    Text("+10", fontSize = 13.sp, color = if (allowed) Ink.TextHi else Ink.TextLow)
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

/**
 * 房主舞台：不画视频，只说清"现在正在播什么"。
 *
 * 卡片文案只留用户用得上的事实：**分享跟着你跨应用**。这是这类工具最容易
 * 翻车的地方 —— 有人以为"退出 App 就停了"，结果相册、聊天窗口全被对方看到了。
 * 至于"为什么不放实时预览"，那是我们内部的设计取舍，不该出现在用户界面上。
 */
@Composable
private fun HostStage(
    backdrop: LayerBackdrop,
    peerLabel: String,
    onOpenWatch: () -> Unit,
    onOpenCinema: () -> Unit,
    screenSharing: Boolean,
    onStartShare: () -> Unit,
    voiceLabel: String?,
    canHearViewer: Boolean,
    micOn: Boolean,
) {
    /* 谁听得到谁 —— 这一行必须自己说，不能让房主去猜。
     *
     * 「只有视频声」这一档同时关掉了两样东西：对方本地播原声时房主的麦克风（防两份声音
     * 叠成回声），以及房主侧的下行播放（听不见观众）。少了这行字，房主看到的就是
     * "我麦克风图标是亮的、对方一直没吭声"，然后去怀疑网络。 */
    val voiceLine = voiceLabel?.let { v ->
        when {
            !canHearViewer && !micOn -> "$v · 你的麦克风已关，你也听不到他"
            /* canHearViewer 管的是**下行**（房主听不听得到观众）。原句写成
               "你出声他听不到"方向反了 —— 这一档房主麦克风多半是开着的，
               观众听得见他说话（外放灌麦就是那条通道）。默认档最常见，
               一行假话顶着警示色挂在卡片上（REVIEW-2026-09-27 P1）。 */
            !canHearViewer -> "$v · 你听不到他说话，要双向就改成连麦"
            else -> v
        }
    }
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
                Text(
                    if (screenSharing) "正在分享你的手机" else "厅里现在只有语音",
                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi,
                )
                Text(
                    if (screenSharing)
                        "对方看到的就是你现在这一屏，而且跟着你走：切到别的应用、打开相册，" +
                            "他那边也同步换画面。想停就点下面的停止。"
                    else
                        // 原来写死"他听得到你"—— 只有视频声 + 对方本地播原声时麦克风是关着的，
                        // 那句就成了假话。谁听得到谁交给下面那行说实话。
                        "他现在只有声音、没有画面 —— 系统还没问过投屏授权。" +
                            "要让他看你这屏就按下面那颗，想放片就进放映厅让他自己播原画。",
                    fontSize = 12.5.sp,
                    color = Ink.TextMid,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(GlassDimens.sp1))
                Text(peerLabel, fontSize = 11.5.sp, color = Ink.TextLow)
                // 分两行而不是拼一行：320dp 宽的机器上"还没有人加入 · 只有视频声 ·
                // 你的麦克风已关，你也听不到他"整句必溢出（Compose 里溢出是静默截断）。
                if (voiceLine != null) {
                    Text(
                        voiceLine,
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp,
                        color = if (canHearViewer) Ink.TextLow else Ink.Warn,
                    )
                }
                // 没在投屏时这颗排最前，而且是这一屏唯一的实心按钮：此刻的主操作就是它。
                if (!screenSharing) {
                    PrimaryPill(
                        text = "让他看我的屏幕",
                        onClick = onStartShare,
                        backdrop = backdrop,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 同屏放映（原"一起看片"）：播放器在我们手里，对方才可能真的动得到进度。
                // 放在这张卡里而不是控制岛上 —— 控制岛要留给"通话级"的三个动作，
                // 而这一颗是"接下来要干什么"，和卡片说的是同一件事。
                PrimaryPill(
                    text = "同屏放映",
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

