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
import android.provider.Settings
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.cinema.CinemaIntents
import com.ticketfortwo.app.cinema.extractSharedUrl
import com.ticketfortwo.app.ui.app.CallScreen
import com.ticketfortwo.app.ui.app.CinemaScreen
import com.ticketfortwo.app.ui.app.cinemaLastPageUrl
import com.ticketfortwo.app.ui.app.resetActivityBrightness
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import com.ticketfortwo.app.ui.app.WatchTogetherScreen
import com.ticketfortwo.app.ui.app.ConsentGuideScreen
import com.ticketfortwo.app.rtc.Verdict
import com.ticketfortwo.app.signaling.SignalHub
import com.ticketfortwo.app.ui.app.EndedScreen
import com.ticketfortwo.app.ui.app.FailedScreen
import com.ticketfortwo.app.ui.app.HomeScreen
import com.ticketfortwo.app.ui.app.HomeSession
import com.ticketfortwo.app.ui.app.InviteScreen
import com.ticketfortwo.app.ui.app.PreparingScreen
import com.ticketfortwo.app.ui.app.QualitySettingsScreen
import com.ticketfortwo.app.ui.app.ShareKindScreen
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

/**
 * 「已从放映厅退回首页」这个 UI 意图，**进程级**（见 AppRouter 里 leftCinema 的用法）。
 *
 * 为什么不放 rememberSaveable：会话（CallSession）是进程级单例，而 rememberSaveable
 * 随 Activity finish 一起没 —— 用户在首页按返回退出 App、再点图标进来，标志丢了、
 * 会话还在，路由又会掉进 WaitingViewer 那条分支，把人扔回「把这条发给朋友」，
 * 正是这次修掉的那个体验（实测复现）。跟会话同生命周期才是一致的；
 * 进程真死了会话也死了，标志自然作废。
 */
private val leftCinemaFlag = MutableStateFlow(false)

/**
 * 「已从会话屏（分享/连麦/邀请）按返回退回首页」这个 UI 意图，**进程级**（理由同
 * [leftCinemaFlag]）。会话是前台服务撑着的，返回只是"人回首页"，不是"结束分享" ——
 * 首页那颗变身圆钮负责"回去"和"停止"（见 HomeScreen 的 session 参数）。
 */
private val leftCallFlag = MutableStateFlow(false)

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
    object Watch : Page

    /** 放映厅：厅先开、人先进来、片子后选。从「分享画面」那一屏进来。 */
    object Cinema : Page

    /** 「分享画面」的第二步：给对方看屏幕，还是同步放映一部片。 */
    object ShareKind : Page
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
    val hostMicLive by CallSession.micLive.collectAsState()
    val hostHearsViewer by CallSession.hostHearsViewer.collectAsState()
    val stats by CallSession.netStats.collectAsState()

    // 观众端（App 内收看）的状态 —— 与房主的 CallSession 并行、互斥使用
    val viewerState by ViewerSession.state.collectAsState()
    val viewerVideo by ViewerSession.remoteVideo.collectAsState()
    val viewerMicMuted by ViewerSession.micMuted.collectAsState()
    val viewerLandscape by ViewerSession.contentLandscape.collectAsState()
    val viewerOrient by ViewerSession.orientationMode.collectAsState()
    val viewerOnline by SignalHub.viewerConnected.collectAsState()
    /** 门牌链接取会话里那份，不取 state 那份：观众一进来 state 就变了，链接不该跟着消失。 */
    val sessionInvite by CallSession.inviteUrl.collectAsState()
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
    // ICE 抖动提示 + 控制岛延迟格的真实 RTT（都是观众侧，见 ViewerSession）
    val viewerJitter by ViewerSession.jitter.collectAsState()
    val viewerRtt by ViewerSession.netRtt.collectAsState()

    // 观众侧的麦克风权限。以前整条观众链路从没申请过它 —— 于是 ensureMicTrack
    // 永远返回 null，点「开麦」只翻图标。默认不申请是对的（只看画面不该开麦），
    // 但点了就必须把权限要下来，所以这一条挂在按钮上，而不是挂在进入观看时。
    val viewerMicGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) ViewerSession.enableMic(context)
        else context.toast("没给麦克风权限：只能看画面，说不了话")
    }
    /* 观众屏方向偏好**不再从盘上恢复**（以前这一行是 `setOrientationMode(prefs.orientationMode())`）。
     *
     * 起因是用户报"App 一打开就是横屏，手机明明是竖着拿的"。查下来是这么回事：
     * 观看界面上那颗「方向：跟随/竖屏/横屏」按钮的选择被写进了 SharedPreferences，
     * 于是某一次看片时顺手点的"锁横屏"会一直留着 —— 它不属于任何一场通话，
     * 而 `ViewerSession` 是进程级单例、状态能活过 Activity 重建，所以只要那场观看还没散，
     * 回到首页、甚至杀掉重开，屏幕都被锁在横屏上，而**唯一能改它的按钮只在观看界面里**：
     * 用户看到的是"App 坏了"，不是一个他自己设过的开关。
     * 方向是"这一次怎么看"，不是"这个 App 长什么样"，所以让它跟着这一场走
     * （散场时 ViewerSession.stop() 把它退回"跟随"）。这里顺手把那个已经没人读的键清掉，
     * 免得将来谁再加持久化，把老用户当年那一下点击原地复活。 */
    remember { context.prefs().clearOrientationLock(); Unit }

    var showConsent by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var viewerIntent by remember { mutableStateOf(false) }
    /** 房主打开内置浏览器"一起看"。分享期间的一个覆盖层，不是独立会话。 */
    var showWatch by rememberSaveable { mutableStateOf(false) }
    /** 放映厅（内测入口）：不依赖是否正在分享，所以是一个独立的页面意图。
     *  用 saveable 而不是 remember：改字体、切深色、系统"显示大小"这类**配置变化**
     *  会重建 Activity，`remember` 一丢就把人从厅里踢回首页（实测：`wm density`
     *  一改，正在放映的厅就没了，而会话其实还活着）。 */
    var showCinema by rememberSaveable { mutableStateOf(false) }
    /**
     * 从放映厅按了返回、但厅（会话）还开着。
     *
     * 为什么要有这个意图：路由是**从会话状态推导**的，而厅开起来就必然停在
     * `WaitingViewer` —— 于是"放映厅返回"会掉进房主等人那条分支，画出「把这条发给朋友」，
     * 底下还挂着一颗"结束"钮（用户实测反馈：没选连麦却看到"结束连麦"，
     * 而且"在放映厅点返回以后，不应该结束分享"）。那一屏属于"发链接等人"的流程，
     * 不属于"我在厅里逛了一圈要出去"。置上这个标志，WaitingViewer + 已离开放映厅
     * 就改画首页（顶上带一张"厅还开着"的卡），分享照常进行。
     * 会话散场或新会话开起来时清掉（见下面 LaunchedEffect(state)）。
     */
    /** 进程级（见 [leftCinemaFlag]）：不能用 rememberSaveable —— Activity finish 后重进会丢。 */
    val leftCinema by leftCinemaFlag.collectAsState()
    /** 从会话屏（分享/连麦/邀请）按返回退回了首页 —— 会话没断，首页圆钮管"回去/停止"。 */
    val leftCall by leftCallFlag.collectAsState()
    /** 首页绿色那颗圆点开的"给什么"选择页。用 saveable：配置变化不该把它甩回首页。 */
    var showShareKind by rememberSaveable { mutableStateOf(false) }
    /** 从「分享 → 双人票」递进来的链接；非空就直接开厅放这一页。 */
    val sharedUrl by CinemaIntents.pending.collectAsState()
    var cinemaUrl by remember { mutableStateOf<String?>(null) }
    /* 第几次递链接。放映厅那边拿它当 LaunchedEffect 的 key 之一。
     *
     * 为什么要有：key 只有 URL 时，**同一条链接第二次递进来什么都不会发生** ——
     * 状态值没变，effect 不重跑。而人重复分享，多半正是因为第一次没成
     * （页面报错、被挡、想重看），最该响应的时候反而没反应。
     * CinemaScreen 里那段 `handledShare == u -> 重新加载这一页` 本来就是为了这一刻写的，
     * 可它永远进不去：effect 压根没再跑一次。 */
    var cinemaSeq by remember { mutableStateOf(0L) }
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
        /* 「已离开放映厅」只属于**开厅那一次**：会话散场、或者新会话开起来
           （Preparing = 只连麦/分享屏幕那两条流程的开头），这个意图就作废。
           不清的后果：下一场等人时也会绕过「把这条发给朋友」——而那一屏在
           那些流程里是对的（尤其"只连麦"，它的停止钮就该叫「结束连麦」）。 */
        if (state is CallSession.State.Idle || state is CallSession.State.Preparing) {
            leftCinemaFlag.value = false
            leftCallFlag.value = false
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

    /**
     * 打开放映厅。
     *
     * [restoreAddress] = 这次要不要接管地址。WebView 离屏即毁、重进是全新实例，
     * 不把地址接回去它会掉回默认测试页而放映状态还挂着（实测：放映中退出再进变
     * test.html）。接回优先级：离开时浏览的那一页（[cinemaLastPageUrl]，由加载 effect
     * 和 onPageStarted 持续记录 —— 含站内点链接；只记程序化加载那一下会接回旧页，
     * 页面重载、两端进度清零，2026-09-29 用户实测）> 嗅探到的片源地址
     * （m3u8 直开可能被 CORS/UA 拒）。
     *
     * [enterCinema] 递新链接进来时传 false：那条链接才是刚要打开的，老页面不能抢；
     * 其余入口（ShareKind、圆钮、会话屏卡片）都按"回到离开时那一页"接管。
     */
    fun openCinema(restoreAddress: Boolean = true) {
        if (restoreAddress) {
            val target = when {
                cinemaLastPageUrl != null -> cinemaLastPageUrl
                CallSession.isActive && cinemaUrl == null -> CallSession.cinema.value?.track?.url
                else -> null
            }
            android.util.Log.i(
                "Cinema",
                "回厅接片：lastPage=$cinemaLastPageUrl cinemaUrl=$cinemaUrl target=$target",
            )
            if (target != null && cinemaUrl != target) {
                cinemaUrl = target
                cinemaSeq += 1
            }
        }
        if (CallSession.isActive) {
            showCinema = true
            return
        }
        if (!granted(context, Manifest.permission.RECORD_AUDIO)) {
            cinemaMicGrant.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startAudioOnlyHost()
        showCinema = true
    }

    /**
     * 离开放映厅。**只关这一屏，不动会话** —— 返回是"人出去了"，不是"厅关了"。
     *
     * 同时立「回首页」的意图（[leftCallFlag]）：出门该落在首页，不是弹回会话屏 ——
     * 分享中从厅里点顶栏返回，原来直接撞见"正在分享"那张卡（2026-09-29 用户反馈，
     * 卡上的两个入口还和首页语义重复）。首页圆钮管"回去/停止"，回去的落点按
     * [leftCinema] 优先回厅。
     */
    fun leaveCinema() {
        showCinema = false
        leftCinemaFlag.value = true
        leftCallFlag.value = true
    }

    /**
     * 「分享 → 双人票」递进来一条链接时：**开厅**，再进这一屏。
     *
     * 原来这两处只写了 `showCinema = true`，于是链接是打开了，**厅却没开** ——
     * 没有邀请链接、没有信令，卡片上"复制邀请"那一行根本不出现，而顶栏还写着
     * "厅已开 · 等对方进来"。实测（.dev/title-01-host.png）：从分享入口进来时
     * 嗅到了 1 条可播地址，可观众那边连门都没有。上面那句"非空就直接开厅放这一页"
     * 才是本意，这里把它补成事实。
     *
     * `showCinema = true` 在 openCinema 之后无条件执行：麦克风权限没给时
     * openCinema 会停在授权那一步，但用户递进来的那一页不该因此被吞掉。
     */
    fun enterCinema(url: String) {
        cinemaUrl = url
        cinemaSeq += 1
        // restoreAddress=false：新链接刚写进 cinemaUrl，接回逻辑不能把老页面盖回去
        openCinema(restoreAddress = false)
        showCinema = true
    }

    LaunchedEffect(Unit) {
        // 冷启动时 onNewIntent 不会走，只能从 Activity 手里那份 intent 捞
        CinemaIntents.fromActivity(context as? android.app.Activity)?.let { enterCinema(it) }
    }
    LaunchedEffect(sharedUrl) {
        val u = sharedUrl ?: return@LaunchedEffect
        enterCinema(u)
        CinemaIntents.consume()
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
            // "交还给用户"不能只写 UNSPECIFIED，见下面 handBackOrientation 的注释 ——
            // 那是这一屏最容易踩的一个坑：写了等于没还。
            OrientationTarget.Keep -> handBackOrientation(a)
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
        viewerIntent -> UiRole.Viewer
        else -> UiRole.None
    }
    val isHost = role == UiRole.Host
    val stop = { CallSession.stop(context); viewerIntent = false; paste = "" }

    /** 从放映厅回来了、厅还开着、正停在"等人加入" —— 这一格该回首页，见 [leftCinema]。 */
    val roomBehind = leftCinema && isHost && state is CallSession.State.WaitingViewer

    /**
     * 会话进行中按返回退到了首页 —— 分享/连麦/邀请屏都算（[leftCallFlag]）。
     * 此时已在家，系统返回键要能照常退出 App，所以返回处理器在这里必须让位。
     */
    val sessionBehind = leftCall && isHost && (state is CallSession.State.WaitingViewer || state is CallSession.State.Connected)

    // 系统返回键：二级页面返回上一级，而不是把 App 整个退出去。
    // BackHandler 后注册的优先级更高，所以从最深的页面向浅注册。
    BackHandler(enabled = viewerState !is ViewerSession.State.Idle) { ViewerSession.stop() }
    BackHandler(enabled = viewerIntent && state is CallSession.State.Idle) {
        viewerIntent = false; paste = ""; viewerError = null
    }
    /* 会话进行中按返回：回首页（会话不断，前台服务撑着），首页圆钮管"回去/停止"。
       注册在 showXxx 之前 —— 设置/放映厅/同看这些更深的页打开时，它们的处理器
       （注册在后面）优先级更高，返回仍归它们；已经在家（leftCall 或 roomBehind）
       时 enabled=false，返回照常退出 App —— 不然返回会被吞，用户困在首页。 */
    BackHandler(
        enabled = isHost && !sessionBehind && !roomBehind &&
            (state is CallSession.State.WaitingViewer || state is CallSession.State.Connected),
    ) { leftCallFlag.value = true }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showWatch) { showWatch = false }
    BackHandler(enabled = showCinema) { leaveCinema() }
    // 授权指引比放映厅更深（路由里它盖在厅上面），返回也要先关指引再谈离厅。
    BackHandler(enabled = showConsent) { showConsent = false }
    BackHandler(enabled = showShareKind) { showShareKind = false }

    // 首页在两个分支里都要画（角色未定 / 兜底）。写成一处，避免以后改了其一忘了其二。
    // 房主此刻有没有视频轨（= 真的在投屏）。停止投屏时 CallSession 会把它置回 null，
    // 所以这一屏的措辞跟着系统授权的真实状态走，而不是"进了这一屏就算在分享"。
    val hostVideo by CallSession.localVideo.collectAsState()

    /**
     * 选择页里点了「分享我的屏幕」。
     *
     * 顺手把「只连麦」改回带画面的档位：那一档根本不建视频轨，
     * 用户既然明确要"分享屏幕"，点完却什么都没发生（还是只有声音）是最坏的结果。
     * 改了要说出来 —— 悄悄改用户的设置比不改更糟。
     */
    fun startScreenShare() {
        showShareKind = false
        if (!quality.videoEnabled) {
            val q = quality.copy(voiceMode = VoiceMode.VideoOnly)
            quality = q
            scope.launch(Dispatchers.IO) { ShareQuality.save(context, q) }
            context.toast("「只连麦」不传画面：已改成「只有视频声」")
        }
        showConsent = true
    }

    val home: @Composable () -> Unit = {
        /* 首页圆钮的"会话进行中"态（方案二，见 HomeSession）。优先级：正在分享 >
           放映厅已开 > 语音会话 —— 屏幕正在被看是最要紧的事实（红点同一原则）。
           点圆回到对应的会话屏；圆下小字钮结束整场（与 CallSession.stop 同一落点）。 */

        /* 圆钮"回去"的落点：**从哪离开的回哪去** —— 厅开着（leftCinema）就回厅，
           否则回会话屏。分享中从厅里退首页再点圆，原来落点是会话屏那张"正在分享"卡，
           要再点一次卡上的钮才进厅（2026-09-29 用户反馈的绕路）；厅是更深的意图，
           开着就该直接回去。leftCinema 的清理由厅内授权指引（leftCinema 在
           consent onContinue 清）和会话散场接管，这里不顺手清 —— 回厅的路上它还有效。 */
        val backToBehind: () -> Unit = {
            leftCallFlag.value = false
            if (leftCinema) openCinema() else leftCinemaFlag.value = false
        }
        val homeSession: HomeSession? = when {
            !isHost || !(leftCall || roomBehind) -> null
            // 屏幕真的在被看 —— 最要紧的事实优先（与顶栏红点同一原则）。
            hostVideo != null -> HomeSession(
                label = "正在分享",
                status = when {
                    viewerOnline -> "1 人正在观看 · 已直连"
                    state is CallSession.State.WaitingViewer -> "等对方进来"
                    else -> "还没有人加入"
                },
                stopLabel = "停止分享",
                onReturn = backToBehind,
                onStop = stop,
            )
            state is CallSession.State.Connected -> HomeSession(
                label = "语音连麦中",
                status = if (viewerOnline) "1 人正在观看 · 已直连" else "还没有人加入",
                stopLabel = "结束连麦",
                onReturn = backToBehind,
                onStop = stop,
            )
            roomBehind -> HomeSession(
                label = "放映厅已开",
                status = if (viewerOnline) "对方已在厅里" else "等对方进来",
                stopLabel = "关闭放映厅",
                onReturn = { leftCallFlag.value = false; openCinema() },
                onStop = stop,
            )
            leftCall && state is CallSession.State.WaitingViewer -> HomeSession(
                label = "等对方加入",
                status = "邀请已就绪，发给他就能进",
                stopLabel = "结束连麦",
                onReturn = { leftCallFlag.value = false },
                onStop = stop,
            )
            else -> null
        }
        HomeScreen(
            backdrop = backdrop,
            // 绿色那颗圆不再直接开投屏：先进"给对方看什么"那一屏（放映厅和屏幕分享
            // 是同一件事的两条路，并列在首页会让人点错）。
            // 会话进行中它变身成状态（方案二），点圆回到对应的会话屏。
            onStart = { showShareKind = true },
            onJoinViewer = { viewerIntent = true; paste = ""; viewerError = null },
            onSettings = { showSettings = true },
            quality = quality,
            lastSummary = lastConnected,
            session = homeSession,
        )
    }

    // ── 当前该画哪一屏 ────────────────────────────────────────────────────
    // 这个 when 的**顺序就是优先级**（越靠前的意图越"临时"，越该盖在上面），别重排。
    val page: Page = when {
        // 分享设置：纯 UI 意图，和授权指引一样排在最前面。
        showSettings -> Page.Settings

        // 「分享画面」的选择页：和投屏指引同级（都还没开会话），排在它们前面。
        showShareKind -> Page.ShareKind

        // 投屏授权指引：**临时覆盖层**，要能盖在放映厅上 —— 厅里"分享我的屏幕"
        // 这条入口（v2.1 主路径）授权时人还停在厅里，指引必须压住它。
        showConsent -> Page.Consent

        // 放映厅：独立的页面意图，不要求"正在分享"，所以排在 Watch 前面。
        showCinema -> Page.Cinema

        // 一起看：分享期间的覆盖层，盖在会话屏之上（它成立的前提就是"我还在分享"）
        showWatch -> Page.Watch

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

        // 分享/连麦中按返回退回了首页（leftCall）：会话在前台服务里继续跑，
        // 首页那颗变身圆钮管"回去/停止"（见 HomeScreen 的 session 参数）。
        // 必须排在 Connected/Invite 之前，否则回首页立刻被弹回会话屏。
        // Failed 不在此列：失败页有自己的"重试/停止"，用户得看见它。
        sessionBehind -> Page.Home

        state is CallSession.State.Connected -> Page.Call(isHost)

        state is CallSession.State.Failed -> Page.Failed(
            (state as CallSession.State.Failed).reason,
            (state as CallSession.State.Failed).verdict,
        )

        // 从放映厅退回首页、厅还开着（见 leftCinema）：不落「把这条发给朋友」，
        // 更不在"返回"这个动作里结束分享 —— 首页那颗变身圆钮管"回厅/关厅"（方案二）。
        roomBehind -> Page.Home

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
            Page.Cinema -> CinemaScreen(
                backdrop = backdrop,
                initialUrl = cinemaUrl,
                jumpSeq = cinemaSeq,
                inviteUrl = sessionInvite,
                viewerOnline = viewerOnline,
                voiceMode = quality.voiceMode,
                // 厅里切投屏：同一条授权链（指引 → 系统弹窗 → attachScreenCapture），
                // 授权完人还留在厅里（showCinema 不动，路由里指引已让位）。
                onStartShare = { startScreenShare() },
                onBack = { leaveCinema() },
                // 覆盖层（授权指引/同看/设置）会把本屏整屏卸载，回来是全新实例 ——
                // 卸载前把实时地址交回来，重建时才不会掉回旧地址（见 CinemaScreen.onDispose）
                onPageLeave = { u -> if (u.isNotBlank()) cinemaUrl = u },
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
                    val old = quality
                    quality = q
                    scope.launch(Dispatchers.IO) { ShareQuality.save(context, q) }
                    // 分享进行中：声音档/码率/帧率/分辨率立刻热改（CallSession.updateQuality）。
                    // 返回 true = 切到了"带画面"但本场没有视频轨 —— 开画面必须拿新的
                    // 投屏授权（Android 规则），直接带用户去授权指引；设置页在这里让位。
                    if (CallSession.updateQuality(q)) {
                        showSettings = false
                        startScreenShare()
                    }
                },
                onBack = { showSettings = false },
            )

            Page.ShareKind -> ShareKindScreen(
                backdrop = backdrop,
                quality = quality,
                onPickScreen = { startScreenShare() },
                onPickCinema = { showShareKind = false; openCinema() },
                onSettings = { showShareKind = false; showSettings = true },
                onBack = { showShareKind = false },
            )

            Page.Consent -> ConsentGuideScreen(
                backdrop = backdrop,
                onBack = { showConsent = false },
                /* 投屏这条路一提交，"已离开放映厅/已从会话屏退回"就都作废：厅先开的会话此时
                   会**就地接上投屏**（CallSession.startHost 的 isActive 分支），状态仍在
                   WaitingViewer —— 不清标志，用户按流程走到的还是首页那张厅卡/圆钮，
                   而不是"发链接"屏。授权完就该看见会话本身。 */
                onContinue = {
                    showConsent = false
                    leftCinemaFlag.value = false
                    leftCallFlag.value = false
                    startHostFlow()
                },
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
                latencyMs = viewerRtt,
                netLabel = "直连",
                jitter = viewerJitter,
                onContentResolution = { w, h -> ViewerSession.onContentResolution(w, h) },
                orientationLabel = when (viewerOrient) {
                    OrientationMode.Follow -> "跟随"
                    OrientationMode.Portrait -> "竖屏"
                    OrientationMode.Landscape -> "横屏"
                },
                onCycleOrientation = {
                    // 只改内存里这一场的状态，不落盘 —— 理由见上面"不再从盘上恢复"那段。
                    ViewerSession.cycleOrientationMode()
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
                // 房主：顶栏左上角"返回首页"。返回只是人回首页，分享不断 ——
                // 首页那颗变身圆钮管"回去/停止"。观众侧不画（返回=结束观看）。
                onBack = if (p.host) ({ leftCallFlag.value = true }) else null,
                // 以前这里是写死的「已直连」—— 没人看的时候也说"已直连"，
                // 等于把用户问的"到底有没有人在观看"用一个假答案糊过去了。
                peerLabel = if (viewerOnline) "1 人正在观看 · 已直连" else "还没有人加入",
                // 图标跟着**实际**开没开走，不跟着 micMuted：「只有视频声」下麦克风是被
                // 声音档关掉的，那时 micMuted 还是 false，照旧写就会画出一个骗人的"说话中"。
                micOn = hostMicLive,
                onToggleMic = { CallSession.toggleMic() },
                voiceLabel = VoiceMode.label(quality.voiceMode),
                // 只有视频声时房主那边把对方的上行静音了（setSpeakerMute），
                // 这一屏必须说实话，否则他会以为"他没说话"，其实是他听不见。
                canHearViewer = hostHearsViewer,
                latencyMs = stats?.rttMs,
                netLabel = stats?.viaLabel ?: "直连",
                onOpenWatch = { showWatch = true },
                onOpenCinema = { openCinema() },
                // 厅先开不投屏 ⇒ 这一屏不能再写"正在分享你的手机"（见 CallScreen 的注释）。
                screenSharing = hostVideo != null,
                onStartShare = {
                    if (quality.videoEnabled) {
                        showConsent = true
                    } else {
                        // 只连麦这一档没有画面可给，按钮不该再承诺"让他看我的屏幕"。
                        context.toast("现在是「只连麦」：要去分享设置里改成带画面的档位")
                    }
                },
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
                screenSharing = hostVideo != null,
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

/**
 * 不观看的时候，把屏幕方向**真正**交还给用户。
 *
 * 直觉写法是 `SCREEN_ORIENTATION_UNSPECIFIED`（"我不表态了，系统你看着办"），
 * 但在关掉自动旋转的手机上这句话不成立：那时系统已经不听加速度传感器，
 * 用的是"最后一次生效的方向"，于是我们为了看横屏片强行转过的那一转会一直留在屏幕上 ——
 * 用户退出观看、甚至杀掉重开，看到的都是横屏，而手机明明竖着拿在手里。
 * 这正是"App 一打开就是横屏"的第二条来路（第一条是方向偏好被持久化，已单独修掉）。
 *
 * 所以交还时必须把用户自己锁的那个方向**说出来**：`Settings.System.user_rotation`
 * 是可读的（0=竖 / 1=横 / 2=反向竖 / 3=反向横），自动旋转开着时不用管，传感器会定。
 */
private fun handBackOrientation(a: Activity): Int {
    val resolver = a.contentResolver
    fun sys(key: String, def: Int) = runCatching { Settings.System.getInt(resolver, key, def) }.getOrDefault(def)
    if (sys(Settings.System.ACCELEROMETER_ROTATION, 0) == 1) {
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    return when (sys(Settings.System.USER_ROTATION, 0)) {
        1 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        2 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        3 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
        else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }
}

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

/** 已废弃：观众屏方向改成"只管这一场"之后没人再读它，只在启动时清一次，见 Prefs.clearOrientationLock。 */
private const val KEY_ORIENT = "viewer_orientation"

private class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markConnected() = sp.edit().putLong(KEY_LAST_CONNECTED, System.currentTimeMillis()).apply()

    /**
     * 观众屏方向那个键已经废弃（见 AppRouter 里不再恢复它的那段注释）。
     * 这里只负责把老装机留下的值抹掉 —— 不读、不写，所以也不会再影响任何人。
     */
    fun clearOrientationLock() = sp.edit().remove(KEY_ORIENT).apply()

    fun lastSummary(): String? {
        val at = sp.getLong(KEY_LAST_CONNECTED, 0L)
        if (at == 0L) return null
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(at))
    }
}

private fun Context.prefs() = Prefs(this)
