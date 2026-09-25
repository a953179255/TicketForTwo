package com.ticketfortwo.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.cinema.CinemaIntents
import com.ticketfortwo.app.cinema.extractSharedUrl
import com.ticketfortwo.app.ui.app.CallScreen
import com.ticketfortwo.app.ui.app.CinemaScreen
import com.ticketfortwo.app.ui.app.resetActivityBrightness
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import com.ticketfortwo.app.ui.app.ColorLabScreen
import com.ticketfortwo.app.ui.app.WatchTogetherScreen
import com.ticketfortwo.app.ui.app.GlassLabScreen
import com.ticketfortwo.app.ui.app.ConsentGuideScreen
import com.ticketfortwo.app.rtc.Verdict
import com.ticketfortwo.app.signaling.SignalHub
import com.ticketfortwo.app.ui.app.EndedScreen
import com.ticketfortwo.app.ui.app.FailedScreen
import com.ticketfortwo.app.ui.app.HomeScreen
import com.ticketfortwo.app.ui.app.InviteScreen
import com.ticketfortwo.app.ui.app.PreparingScreen
import com.ticketfortwo.app.ui.app.QualitySettingsScreen
import com.ticketfortwo.app.ui.app.TicketForTwoAppRoot
import com.ticketfortwo.app.ui.app.ViewerJoinScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主流程路由。
 *
 * 路由**只看 [CallSession] 的状态，不建导航栈**。两个理由：
 * 1. Activity 会被系统回收，通话不会（媒体在前台服务与 libwebrtc 线程里）——
 *    若"该显示哪一屏"存在返回栈里，重建后就会停在错误的屏幕上；
 * 2. 一期信令靠人工复制粘贴，用户必然中途切去聊天软件再回来，
 *    界面要能从回来那一刻的会话状态重新推导，而不是指望返回栈还记着。
 *
 * 只有"要不要看授权指引""要不要停在观众粘贴页"这两件事是纯 UI 意图，留在本地。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TicketForTwoAppRoot { backdrop -> AppRouter(backdrop) }
        }
    }

    // ---- 画中画（只给观众侧）----------------------------------------------
    //
    // 触发时机选"用户按 HOME / 切走"（onUserLeaveHint），而不是在控制岛上再加一颗按钮：
    // 看片中途去回消息，回来希望画面还在，这正是小窗的用途；系统本来就在这个时机
    // 给应用一次机会，不需要用户先学会一颗新图标。
    //
    // 房主侧刻意不做：他这块屏正在被分享，小窗里放实时画面就是上一轮判定过的套娃，
    // 放 App 界面又挡住"分享跟着你走"这件事 —— 两个选择都是错的，所以不选。
    /**
     * 从别的 App「分享 → 双人票」递进来的链接。
     *
     * `launchMode=singleTop` 下第二次分享只会走 [onNewIntent]，不会重走 onCreate，
     * 所以这里必须 `setIntent` —— 否则 Compose 侧读 `activity.intent` 永远读到第一次那条，
     * 表现为"第二次分享没反应，但链接确实发出去了"。
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        CinemaIntents.push(extractSharedUrl(intent))
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val watching = ViewerSession.state.value is ViewerSession.State.Connected
        if (watching && !PipState.inPip.value && !isInPictureInPictureMode) {
            val ok = runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
            Log.i("MainActivity", "观看中切走 → 画中画${if (ok) "已开" else "被系统拒"}")
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipState.inPip.value = isInPictureInPictureMode
    }

    /**
     * 31+ 才有：能拿到"正在切入"和"被用户甩到一边(stash)"这两个中间态。
     *
     * 注意 `PictureInPictureUiState` 上**没有** "在不在小窗里" 这个判断
     * （javap 查过 android.jar，只有 isTransitioningToPip / isStashed），
     * 所以那位仍以 Activity 自己的 isInPictureInPictureMode 为准。
     */
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onPictureInPictureUiStateChanged(state: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(state)
        PipState.inPip.value = state.isTransitioningToPip || isInPictureInPictureMode
    }

    private fun pipParams(): PictureInPictureParams {
        val b = PictureInPictureParams.Builder()
        // 比例跟**内容**走，不跟屏幕走：对方横屏时还给竖着的比例，小窗里就裁掉一块画面。
        // 系统只接受 1:2.39 ~ 2.39:1，超了 enterPictureInPictureMode 会直接抛，所以先夹。
        val (w, h) = ViewerSession.contentSize.value ?: (16 to 9)
        val r = (w.toFloat() / h.toFloat()).coerceIn(1f / 2.39f, 2.39f)
        runCatching { b.setAspectRatio(Rational((r * 1000).toInt(), 1000)) }
        return b.build()
    }
}

/**
 * 画中画状态。放在文件级而不是 Activity 字段上：路由是 Composable，
 * 读一个普通字段不会重组，小窗进出时界面就停在旧的样子。
 */
private object PipState {
    val inPip = MutableStateFlow(false)
}

private enum class UiRole { None, Host, Viewer }

/**
 * 当前该画哪一屏 —— **携带这一屏要显示的数据**，而不是一个光秃秃的枚举 key。
 *
 * 这个区别决定了转场动画好不好看：AnimatedContent 会让旧页在退场期间继续参与组合，
 * 如果旧页此时去读"最新状态"（例如观众页的标题来自 `viewerState.note`），
 * 状态一变它就立刻跟着变，退场的那一帧会闪成新页面的样子。
 * 把 payload 钉进页面描述里，每个页面渲染的都是**属于它自己的那份快照**。
 */
private sealed interface Page {
    object Home : Page
    object Settings : Page
    object ColorLab : Page
    object GlassLab : Page
    object Watch : Page

    /** 放映厅：厅先开、人先进来、片子后选。开发期从首页胶囊进入。 */
    object Cinema : Page
    object Consent : Page
    data class ViewerJoin(val error: String?) : Page
    data class ViewerPreparing(val note: String) : Page
    data class ViewerFailed(val reason: String, val verdict: Verdict? = null) : Page

    /** 房主结束分享（或信令通道断了）—— 中性收场，不算失败。 */
    data class ViewerEnded(val reason: String) : Page
    object ViewerCall : Page
    data class Invite(val url: String) : Page
    data class Preparing(val title: String, val note: String, val hint: String?) : Page
    data class Call(val host: Boolean) : Page
    data class Failed(val reason: String, val verdict: Verdict? = null) : Page
}

@Composable
private fun AppRouter(backdrop: LayerBackdrop) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by CallSession.state.collectAsState()
    val sessionRole by CallSession.role.collectAsState()
    val remoteVideo by CallSession.remoteVideo.collectAsState()
    val micMuted by CallSession.micMuted.collectAsState()
    val stats by CallSession.netStats.collectAsState()

    // 观众端（App 内收看）的状态 —— 与房主的 CallSession 并行、互斥使用
    val viewerState by ViewerSession.state.collectAsState()
    val viewerVideo by ViewerSession.remoteVideo.collectAsState()
    val viewerMicMuted by ViewerSession.micMuted.collectAsState()
    val viewerLandscape by ViewerSession.contentLandscape.collectAsState()
    val viewerOrient by ViewerSession.orientationMode.collectAsState()
    val viewerOnline by SignalHub.viewerConnected.collectAsState()
    // 同看：观众侧只需要房主广播回来的播放器状态，加上"他允不允许我控制"这一个布尔。
    val viewerWatch by ViewerSession.watch.collectAsState()
    val viewerWatchAllowed by ViewerSession.watchAllowed.collectAsState()
    // 放映厅：App 内观众端不本地播，但要知道"他在放什么"并且能按 ±10。
    val viewerCinema by ViewerSession.cinema.collectAsState()
    val viewerCinemaAllowed by ViewerSession.cinemaAllowed.collectAsState()
    // 小窗里只留画面，所以这一位要一路传到 CallScreen 去压掉控件。
    val inPip by PipState.inPip.collectAsState()
    val viewerMicPending by ViewerSession.micPending.collectAsState()
    val viewerMicLive by ViewerSession.micLive.collectAsState()

    // 观众侧的麦克风权限。以前整条观众链路从没申请过它 —— 于是 ensureMicTrack
    // 永远返回 null，点「开麦」只翻图标。默认不申请是对的（只看画面不该开麦），
    // 但点了就必须把权限要下来，所以这一条挂在按钮上，而不是挂在进入观看时。
    val viewerMicGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ViewerSession.enableMic(context)
        else context.toast("没给麦克风权限：只能看画面，说不了话")
    }
    remember { ViewerSession.setOrientationMode(context.prefs().orientationMode()); Unit }

    var showConsent by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showColorLab by remember { mutableStateOf(false) }
    var showGlassLab by remember { mutableStateOf(false) }
    var viewerIntent by remember { mutableStateOf(false) }
    /** 房主打开内置浏览器"一起看"。分享期间的一个覆盖层，不是独立会话。 */
    var showWatch by remember { mutableStateOf(false) }
    /** 放映厅（内测入口）：不依赖是否正在分享，所以是一个独立的页面意图。 */
    var showCinema by remember { mutableStateOf(false) }
    /** 从「分享 → 双人票」递进来的链接；非空就直接开厅放这一页。 */
    val sharedUrl by CinemaIntents.pending.collectAsState()
    var cinemaUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // 冷启动时 onNewIntent 不会走，只能从 Activity 手里那份 intent 捞
        CinemaIntents.fromActivity(context as? android.app.Activity)?.let {
            cinemaUrl = it
            showCinema = true
        }
    }
    LaunchedEffect(sharedUrl) {
        val u = sharedUrl ?: return@LaunchedEffect
        cinemaUrl = u
        showCinema = true
        CinemaIntents.consume()
    }
    var paste by remember { mutableStateOf("") }
    var viewerError by remember { mutableStateOf<String?>(null) }

    // 「上次连接」从磁盘读，**不能在组合期读**：getSharedPreferences() 第一次访问会在
    // 调用线程上同步等磁盘加载完，而组合发生在主线程。这是它自己就该改的理由。
    // （今天那次「双人票 isn't responding」经排查**不是**它引起的 —— 当时模拟器
    //  system_server 占了 118% CPU、内存只剩 400MB，是环境病了。别把两件事混成一条因果。）
    var lastConnected by remember { mutableStateOf<String?>(null) }
    // 分享画质设置同理：IO 线程读盘，默认值先顶着；用户第一次开分享前肯定已加载完。
    var quality by remember { mutableStateOf(ShareQuality()) }
    LaunchedEffect(Unit) {
        lastConnected = withContext(Dispatchers.IO) { context.prefs().lastSummary() }
        quality = withContext(Dispatchers.IO) { ShareQuality.load(context) }
    }

    // 连上才记"上次连接"。失败不留痕迹 —— 否则首页会显示一堆没发生的连接。
    LaunchedEffect(state) {
        if (state is CallSession.State.Connected) {
            withContext(Dispatchers.IO) { context.prefs().markConnected() }
        }
    }

    // ── 权限链 ────────────────────────────────────────────────────────────
    //
    // 三条独立的链，每条**单向走完、不回头重判**：
    // 若写成"回调里再检查一遍缺什么"，用户点了拒绝就会查到仍缺同一项，无限弹框。
    // 已授予的项由入口处的 granted() 跳过，不会重复骚扰。
    //
    // 拒绝麦克风不拦路：只分享画面、不能连麦，仍是有效用法（见 CallSession.ensureMicTrack）。
    val projection = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val captureIntent = { projection.createScreenCaptureIntent() }

    // 房主链终点：拿到本次授权 Intent → 起前台服务 → 等服务真进前台 → 才开始采集。
    val screenGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        CallSession.logEvent("投屏授权返回 resultCode=${res.resultCode} data=${data != null}")
        if (res.resultCode != Activity.RESULT_OK || data == null) {
            context.toast("授权被取消；注意弹窗里要选「整个屏幕」")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            ShareService.start(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            // Android 14+：startForegroundService() 是异步的，抢在 startForeground()
            // 之前取 MediaProjection 会抛 SecurityException（实测崩过）。
            if (!ShareService.awaitReady()) {
                CallSession.logEvent("前台服务未在 3 秒内就绪，放弃本次分享")
                context.toast("没能进入分享状态，请重试")
                return@launch
            }
            CallSession.startHost(context, data, quality)
        }
    }

    // 仅语音：跳过投屏授权，前台服务走 microphone 类型。仍需要前台服务 ——
    // 否则切到后台时系统会掐掉麦克风（Android 14+ 后台录音必须挂 mic 类型 FGS）。
    fun startAudioOnlyHost() {
        if (!granted(context, Manifest.permission.RECORD_AUDIO)) {
            context.toast("语音模式需要麦克风权限")
            return
        }
        scope.launch {
            ShareService.start(context, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            if (!ShareService.awaitReady()) {
                CallSession.logEvent("前台服务未在 3 秒内就绪，放弃本次分享")
                context.toast("没能进入分享状态，请重试")
                return@launch
            }
            CallSession.startHost(context, null, quality)
        }
    }

    val hostMicGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 权限链的终点按**当前**画质设置分叉：回调执行时读的是用户此刻的 quality。
        if (quality.videoEnabled) screenGrant.launch(captureIntent())
        else startAudioOnlyHost()
    }

    /**
     * 开放映厅用的权限链。和分享那条分开，是因为厅**不该要求先投屏**：
     * 厅是"我先开、对方先进来等着"的容器，一上来就弹投屏授权等于把顺序做反了。
     */
    val cinemaMicGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (it) {
            startAudioOnlyHost()
            showCinema = true
        } else {
            context.toast("厅里要先通语音，得给麦克风权限")
        }
    }

    fun openCinema() {
        if (CallSession.isActive) { showCinema = true; return }
        if (!granted(context, Manifest.permission.RECORD_AUDIO)) {
            cinemaMicGrant.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startAudioOnlyHost()
        showCinema = true
    }

    val hostNotifGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (!granted(context, Manifest.permission.RECORD_AUDIO)) hostMicGrant.launch(Manifest.permission.RECORD_AUDIO)
        else if (quality.videoEnabled) screenGrant.launch(captureIntent())
        else startAudioOnlyHost()
    }

    fun startHostFlow() = when {
        !granted(context, Manifest.permission.POST_NOTIFICATIONS) ->
            hostNotifGrant.launch(Manifest.permission.POST_NOTIFICATIONS)
        !granted(context, Manifest.permission.RECORD_AUDIO) ->
            hostMicGrant.launch(Manifest.permission.RECORD_AUDIO)
        quality.videoEnabled -> screenGrant.launch(captureIntent())
        else -> startAudioOnlyHost()
    }

    // 观众入口（主路径）：在 App 内直接收看。
    fun submitViewerLink() {
        val url = paste.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            viewerError = "这不像一条邀请链接。请把房主发来的整条网址原样粘进来（以 https:// 开头）。"
            return
        }
        viewerError = null
        ViewerSession.start(context, url)
    }

    // 观众入口（备用路径）：交给系统浏览器 —— 不想装 App 的朋友用这条。
    fun openInBrowser() {
        val url = paste.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            viewerError = "这不像一条邀请链接。"
            return
        }
        viewerError = null
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { viewerError = "没能唤起浏览器：${it.message}" }
    }

    // ── 观众屏方向 ────────────────────────────────────────────────────────
    // 实测过：房主横屏后观众收到的帧变成 1800x810，但观众屏不会自己转，
    // 于是内容横、屏幕竖，画面缩成中间一条。观众的物理朝向管不着，
    // 想两边一致就只能主动改观众屏的方向 —— 没有第二条路。
    //
    // 三条边界：① 只在观看期间生效，退出观看必须交还给系统（否则用户出了 App
    // 还发现手机转不动）；② 还没来帧 / 仅语音时不动（decideOrientation 返回 Keep）；
    // ③ 那颗按钮能一键改成"锁竖/锁横"，因为一定有人不喜欢屏幕自己转。
    val activity = context as? Activity
    LaunchedEffect(viewerState, viewerLandscape, viewerOrient, viewerVideo) {
        val a = activity ?: return@LaunchedEffect
        val watching = viewerState is ViewerSession.State.Connected ||
            viewerState is ViewerSession.State.Connecting
        val target = if (watching) {
            decideOrientation(viewerOrient, viewerLandscape, viewerVideo != null)
        } else {
            OrientationTarget.Keep
        }
        // 用不带 SENSOR_ 的常量。真机实测：手机关掉自动旋转时（accelerometer_rotation=0），
        // 系统会**忽略** SCREEN_ORIENTATION_SENSOR_LANDSCAPE —— 于是模拟器上一切正常
        // （t2view 的自动旋转是开的），到手机上完全不转。
        // 而"跟随对方"本来就是要盖过用户的锁定才有意义：对方横屏了，这边就该横过来。
        val want = when (target) {
            OrientationTarget.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationTarget.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationTarget.Keep -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (a.requestedOrientation != want) {
            Log.i("MainActivity", "观众屏方向 → $target（模式 ${viewerOrient.name}，内容横=$viewerLandscape）")
            a.requestedOrientation = want
        }
    }

    // ── 会话期间保持屏幕常亮 ──────────────────────────────────────────────
    // 真机实测踩到的：手机 screen_off_timeout = 120 秒，观众看到第 2 分钟屏幕一锁，
    // WebSocket 随之被系统掐掉，房主那边先"观众已离开"、8 秒后报"直连中断且未能自愈"。
    // 看着像网络不稳，其实是我们没声明"我正在用这台设备"——看别人屏幕看到一半
    // 手机自己锁屏，这个产品就没法用了。房主侧同理（锁屏还会被系统撤走投屏）。
    val sharing = state !is CallSession.State.Idle
    val watching = viewerState !is ViewerSession.State.Idle
    DisposableEffect(sharing || watching) {
        val on = sharing || watching
        if (on) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 观众侧手势可以把本窗口亮度压低（见 ViewerGestureLayer）。
    // 退出观看时必须交还给系统，否则用户回到桌面会发现"手机亮度被这个 App 改坏了"。
    LaunchedEffect(watching) {
        if (!watching) resetActivityBrightness(activity)
    }

    // ── 路由 ──────────────────────────────────────────────────────────────
    val role = when {
        sessionRole == CallSession.Role.Host -> UiRole.Host
        sessionRole == CallSession.Role.Viewer -> UiRole.Viewer
        viewerIntent -> UiRole.Viewer
        else -> UiRole.None
    }
    val isHost = role == UiRole.Host
    val stop = { CallSession.stop(context); viewerIntent = false; paste = "" }

    // 系统返回键：二级页面返回上一级，而不是把 App 整个退出去。
    // BackHandler 后注册的优先级更高，所以从最深的页面向浅注册。
    BackHandler(enabled = viewerState !is ViewerSession.State.Idle) { ViewerSession.stop() }
    BackHandler(enabled = viewerIntent && state is CallSession.State.Idle) {
        viewerIntent = false; paste = ""; viewerError = null
    }
    BackHandler(enabled = showConsent) { showConsent = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showGlassLab) { showGlassLab = false; showSettings = true }
    BackHandler(enabled = showWatch) { showWatch = false }
    BackHandler(enabled = showCinema) { showCinema = false }
    BackHandler(enabled = showColorLab) { showColorLab = false; showSettings = true }

    // 首页在两个分支里都要画（角色未定 / 兜底）。写成一处，避免以后改了其一忘了其二。
    val home: @Composable () -> Unit = {
        HomeScreen(
            backdrop = backdrop,
            onStart = {
                // 仅语音不需要投屏指引（那两步都是给投屏授权准备的），直接进权限链。
                if (quality.videoEnabled) showConsent = true else startHostFlow()
            },
            onJoinViewer = { viewerIntent = true; paste = ""; viewerError = null },
            onSettings = { showSettings = true },
            onOpenCinema = { openCinema() },
            quality = quality,
            lastSummary = lastConnected,
        )
    }

    // ── 当前该画哪一屏 ────────────────────────────────────────────────────
    // 这个 when 的**顺序就是优先级**（越靠前的意图越"临时"，越该盖在上面），别重排。
    val page: Page = when {
        showColorLab -> Page.ColorLab
        showGlassLab -> Page.GlassLab

        // 分享设置：纯 UI 意图，和授权指引一样排在最前面。
        showSettings -> Page.Settings

        // 放映厅：独立的页面意图，不要求"正在分享"，所以排在 Watch 前面。
        showCinema -> Page.Cinema

        // 一起看：分享期间的覆盖层，盖在会话屏之上（它成立的前提就是"我还在分享"）
        showWatch -> Page.Watch
        showConsent -> Page.Consent

        // ── 观众：App 内收看（与房主的 CallSession 互斥）──
        viewerState is ViewerSession.State.Connected -> Page.ViewerCall
        viewerState is ViewerSession.State.Connecting ->
            Page.ViewerPreparing((viewerState as ViewerSession.State.Connecting).note)
        viewerState is ViewerSession.State.Failed ->
            Page.ViewerFailed(
                (viewerState as ViewerSession.State.Failed).reason,
                (viewerState as ViewerSession.State.Failed).verdict,
            )
        viewerState is ViewerSession.State.Ended ->
            Page.ViewerEnded((viewerState as ViewerSession.State.Ended).reason)

        // 会话已结束且没有待提交的意图 —— 首页
        role == UiRole.None -> Page.Home

        // 观众还没进入会话 —— 粘贴邀请
        role == UiRole.Viewer && state is CallSession.State.Idle -> Page.ViewerJoin(viewerError)

        state is CallSession.State.Connected -> Page.Call(isHost)

        state is CallSession.State.Failed -> Page.Failed(
            (state as CallSession.State.Failed).reason,
            (state as CallSession.State.Failed).verdict,
        )

        // 门牌就绪：把这条链接发出去就完事，剩下的双方自己会走完。
        state is CallSession.State.WaitingViewer ->
            Page.Invite((state as CallSession.State.WaitingViewer).inviteUrl)

        state is CallSession.State.Preparing -> Page.Preparing(
            title = "正在准备接入口",
            note = (state as CallSession.State.Preparing).note,
            hint = "采集已经开始了；要等的是临时地址，通常几秒",
        )

        state is CallSession.State.Connecting -> Page.Preparing(
            title = "正在建立直连",
            note = "逐对尝试候选地址（打洞），通常几秒内出结果",
            hint = null,
        )

        // 理论上不可达的组合（例如角色已清但状态未回到 Idle）落回首页，
        // 宁可少一屏也不能白屏。
        else -> Page.Home
    }

    // 转场：新页淡入并轻微上浮（从下方 1/20 屏高处升起），旧页快速淡出。
    // 进出时长不对称（220/140）是故意的 —— 退场慢了会和新页叠出"两张脸"。
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 20 })
                .togetherWith(fadeOut(tween(140, easing = FastOutLinearInEasing)))
        },
        modifier = Modifier.fillMaxSize(),
        label = "page",
    ) { p ->
        when (p) {
            Page.ColorLab -> ColorLabScreen(backdrop = backdrop, onBack = { showColorLab = false })
            Page.GlassLab -> GlassLabScreen(backdrop = backdrop, onBack = { showGlassLab = false })

            Page.Cinema -> CinemaScreen(
                backdrop = backdrop,
                initialUrl = cinemaUrl,
                inviteUrl = (state as? CallSession.State.WaitingViewer)?.inviteUrl,
                viewerOnline = viewerOnline,
                onBack = { showCinema = false },
            )

            Page.Watch -> WatchTogetherScreen(
                backdrop = backdrop,
                viewerOnline = viewerOnline,
                onClose = { showWatch = false },
            )

            Page.Settings -> QualitySettingsScreen(
                backdrop = backdrop,
                quality = quality,
                onChange = { q ->
                    quality = q
                    scope.launch(Dispatchers.IO) { ShareQuality.save(context, q) }
                },
                onOpenColorLab = { showSettings = false; showColorLab = true },
                onOpenGlassLab = { showSettings = false; showGlassLab = true },
                onBack = { showSettings = false },
            )

            Page.Consent -> ConsentGuideScreen(
                backdrop = backdrop,
                onBack = { showConsent = false },
                onContinue = { showConsent = false; startHostFlow() },
            )

            Page.ViewerCall -> CallScreen(
                backdrop = backdrop,
                remoteTrack = viewerVideo,
                isHost = false,
                peerLabel = "已直连",
                micOn = viewerMicLive && !viewerMicMuted,
                onToggleMic = {
                    if (!viewerMicLive && !granted(context, Manifest.permission.RECORD_AUDIO)) {
                        viewerMicGrant.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        ViewerSession.toggleMic(context)
                        if (!viewerMicLive) context.toast("开麦中，约一秒…")
                    }
                },
                latencyMs = null,
                netLabel = "直连",
                onContentResolution = { w, h -> ViewerSession.onContentResolution(w, h) },
                orientationLabel = when (viewerOrient) {
                    OrientationMode.Follow -> "跟随"
                    OrientationMode.Portrait -> "竖屏"
                    OrientationMode.Landscape -> "横屏"
                },
                onCycleOrientation = {
                    val next = ViewerSession.cycleOrientationMode()
                    context.prefs().setOrientationMode(next.name)
                },
                onViewerVolume = { ViewerSession.setVolume(it) },
                watch = viewerWatch,
                watchAllowed = viewerWatchAllowed,
                onWatchCmd = { act, arg -> ViewerSession.sendWatchCmd(act, arg) },
                cinema = viewerCinema,
                cinemaAllowed = viewerCinemaAllowed,
                onCinemaCmd = { c -> ViewerSession.sendCinemaCmd(c) },
                pipMode = inPip,
                onStop = { ViewerSession.stop() },
            )

            is Page.ViewerPreparing -> PreparingScreen(
                backdrop = backdrop,
                title = "正在连接房主的手机",
                note = p.note,
                hint = "画面和声音直接在两台设备之间传，不经服务器",
                onStop = { ViewerSession.stop() },
            )

            is Page.ViewerFailed -> FailedScreen(
                backdrop = backdrop,
                reason = p.reason,
                verdict = p.verdict,
                onRetry = { ViewerSession.stop() },
            )

            is Page.ViewerEnded -> EndedScreen(
                backdrop = backdrop,
                reason = p.reason,
                onBack = { ViewerSession.stop() },
            )

            Page.Home -> home()

            is Page.ViewerJoin -> ViewerJoinScreen(
                backdrop = backdrop,
                value = paste,
                onChange = { paste = it; viewerError = null },
                onSubmit = { submitViewerLink() },
                onOpenInBrowser = { openInBrowser() },
                onBack = { viewerIntent = false; paste = "" },
                error = p.error,
            )

            is Page.Call -> CallScreen(
                backdrop = backdrop,
                // 房主不给实时自预览（采集源就是这块屏，预览只会映出残影）；
                // 这条轨只服务观众侧。
                remoteTrack = remoteVideo,
                isHost = p.host,
                // 以前这里是写死的「已直连」—— 没人看的时候也说"已直连"，
                // 等于把用户问的"到底有没有人在观看"用一个假答案糊过去了。
                peerLabel = if (viewerOnline) "1 人正在观看 · 已直连" else "还没有人加入",
                micOn = !micMuted,
                onToggleMic = { CallSession.setMicMuted(!micMuted) },
                latencyMs = stats?.rttMs,
                netLabel = stats?.viaLabel ?: "直连",
                onOpenWatch = { showWatch = true },
                onOpenCinema = { openCinema() },
                onStop = stop,
            )

            is Page.Failed -> FailedScreen(
                backdrop = backdrop,
                reason = p.reason,
                verdict = p.verdict,
                onRetry = stop,
            )

            is Page.Invite -> InviteScreen(
                backdrop = backdrop,
                inviteUrl = p.url,
                onCopy = { context.copy("邀请链接", p.url) },
                onStop = stop,
            )

            is Page.Preparing -> PreparingScreen(
                backdrop = backdrop,
                title = p.title,
                note = p.note,
                hint = p.hint,
                onStop = stop,
            )
        }
    }
}

// ───────────────────────────── 小工具 ─────────────────────────────

private fun granted(context: Context, permission: String): Boolean =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

private fun Context.copy(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    toast("已复制")
}

private fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

private const val PREFS = "t2"
private const val KEY_LAST_CONNECTED = "last_connected"
private const val KEY_ORIENT = "viewer_orientation"

private class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markConnected() = sp.edit().putLong(KEY_LAST_CONNECTED, System.currentTimeMillis()).apply()

    /** 观众屏方向偏好。存的是枚举名，取值失败一律退回 Follow（宁可跟随，不可乱锁）。 */
    fun orientationMode(): OrientationMode =
        runCatching { OrientationMode.valueOf(sp.getString(KEY_ORIENT, null) ?: "Follow") }
            .getOrDefault(OrientationMode.Follow)

    fun setOrientationMode(name: String) = sp.edit().putString(KEY_ORIENT, name).apply()

    fun lastSummary(): String? {
        val at = sp.getLong(KEY_LAST_CONNECTED, 0L)
        if (at == 0L) return null
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(at))
    }
}

private fun Context.prefs() = Prefs(this)
