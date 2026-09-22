package com.ticketfortwo.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
import com.ticketfortwo.app.rtc.SignalingCodec
import com.ticketfortwo.app.ui.app.CallScreen
import com.ticketfortwo.app.ui.app.ConsentGuideScreen
import com.ticketfortwo.app.ui.app.FailedScreen
import com.ticketfortwo.app.ui.app.HomeScreen
import com.ticketfortwo.app.ui.app.InviteScreen
import com.ticketfortwo.app.ui.app.PreparingScreen
import com.ticketfortwo.app.ui.app.TicketForTwoAppRoot
import com.ticketfortwo.app.ui.app.ViewerAnswerScreen
import com.ticketfortwo.app.ui.app.ViewerJoinScreen
import kotlinx.coroutines.launch

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
        consumeIncomingSignal()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingSignal()
    }

    /**
     * 消费对方回传的应答链接。两种入口：
     *  1) 真实路径 —— 他在聊天里点开我们的链接，走 VIEW intent 的 data；
     *  2) 调试路径 —— adb 用 --es answer 直接塞 extras（脚本化验证时不必模拟点击）。
     *
     * 只在这边确实是房主、正在等应答时生效。
     */
    private fun consumeIncomingSignal() {
        val raw = intent?.getStringExtra(EXTRA_ANSWER) ?: intent?.data?.toString() ?: return
        if (raw.isBlank()) return
        val env = SignalingCodec.fromUrl(raw)
        if (env == null) {
            CallSession.logEvent("回传链接解析失败（可能被聊天软件截断）")
            return
        }
        if (env.kind != SignalingCodec.Kind.Answer) {
            CallSession.logEvent("收到的不是应答，是一条 ${env.kind}")
            return
        }
        if (!CallSession.isActive) {
            CallSession.logEvent("收到应答，但这边已经没有进行中的分享了")
            return
        }
        CallSession.logEvent("收到对方应答，开始建立直连")
        CallSession.acceptPeerSignal(env)
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
    }
}

/**
 * 邀请链接的基址 —— 由构建输入决定（`-Pt2.inviteBase=`），默认是占位域名。
 *
 * 占位域名下朋友点开会得到"找不到服务器"，所以 [InviteScreen] 必须把这件事
 * 明写在界面上，而不是让人复制出去才发现。
 */
private val INVITE_BASE: String = BuildConfig.INVITE_BASE
private const val PLACEHOLDER_BASE = "https://share.local/"

private enum class UiRole { None, Host, Viewer }

@Composable
private fun AppRouter(backdrop: LayerBackdrop) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state by CallSession.state.collectAsState()
    val sessionRole by CallSession.role.collectAsState()
    val remoteVideo by CallSession.remoteVideo.collectAsState()
    val micMuted by CallSession.micMuted.collectAsState()
    val stats by CallSession.netStats.collectAsState()

    var showConsent by remember { mutableStateOf(false) }
    var viewerIntent by remember { mutableStateOf(false) }
    var paste by remember { mutableStateOf("") }
    var viewerError by remember { mutableStateOf<String?>(null) }

    // 连上才记"上次连接"。失败不留痕迹 —— 否则首页会显示一堆没发生的连接。
    LaunchedEffect(state) {
        if (state is CallSession.State.Connected) context.prefs().markConnected()
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
            ShareService.start(context)
            // Android 14+：startForegroundService() 是异步的，抢在 startForeground()
            // 之前取 MediaProjection 会抛 SecurityException（实测崩过）。
            if (!ShareService.awaitReady()) {
                CallSession.logEvent("前台服务未在 3 秒内就绪，放弃本次分享")
                context.toast("没能进入分享状态，请重试")
                return@launch
            }
            CallSession.startHost(context, data, CallSession.newRoomCode())
        }
    }

    val hostMicGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { screenGrant.launch(captureIntent()) }

    val hostNotifGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (granted(context, Manifest.permission.RECORD_AUDIO)) screenGrant.launch(captureIntent())
        else hostMicGrant.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun startHostFlow() = when {
        !granted(context, Manifest.permission.POST_NOTIFICATIONS) ->
            hostNotifGrant.launch(Manifest.permission.POST_NOTIFICATIONS)
        !granted(context, Manifest.permission.RECORD_AUDIO) ->
            hostMicGrant.launch(Manifest.permission.RECORD_AUDIO)
        else -> screenGrant.launch(captureIntent())
    }

    // 观众链：只需要麦克风。链接先存起来，回调里再吃进去 ——
    // 权限弹窗期间用户可能改过输入框，存下"点提交那一刻的那条"才是他确认的内容。
    val pendingOffer = remember { mutableStateOf<SignalingCodec.Envelope?>(null) }
    val consumePendingOffer: () -> Unit = {
        pendingOffer.value?.let { CallSession.startViewer(context, it) }
        pendingOffer.value = null
    }
    val viewerMicGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { consumePendingOffer() }

    fun submitViewerLink() {
        val env = SignalingCodec.fromUrl(paste)
        if (env == null) {
            viewerError = "解不开这条链接 —— 要么不是双人票的邀请，要么被聊天软件截断了。" +
                "请整条复制、原样粘贴。"
            return
        }
        if (env.kind != SignalingCodec.Kind.Offer) {
            viewerError = "这是一条应答链接（要发回给房主的那条），不是邀请链接"
            return
        }
        viewerError = null
        pendingOffer.value = env
        if (granted(context, Manifest.permission.RECORD_AUDIO)) consumePendingOffer()
        else viewerMicGrant.launch(Manifest.permission.RECORD_AUDIO)
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

    // 首页在两个分支里都要画（角色未定 / 兜底）。写成一处，避免以后改了其一忘了其二。
    val home: @Composable () -> Unit = {
        HomeScreen(
            backdrop = backdrop,
            onStart = { showConsent = true },
            onJoinViewer = { viewerIntent = true; paste = ""; viewerError = null },
            lastSummary = context.prefs().lastSummary(),
        )
    }

    Box(Modifier.fillMaxSize()) {
        when {
            showConsent -> ConsentGuideScreen(
                backdrop = backdrop,
                onBack = { showConsent = false },
                onContinue = { showConsent = false; startHostFlow() },
            )

            // 会话已结束且没有待提交的意图 —— 首页
            role == UiRole.None -> home()

            // 观众还没进入会话 —— 粘贴邀请
            role == UiRole.Viewer && state is CallSession.State.Idle -> ViewerJoinScreen(
                backdrop = backdrop,
                value = paste,
                onChange = { paste = it; viewerError = null },
                onSubmit = { submitViewerLink() },
                onBack = { viewerIntent = false; paste = "" },
                error = viewerError,
            )

            state is CallSession.State.Connected -> CallScreen(
                backdrop = backdrop,
                // 房主不给实时自预览（采集源就是这块屏，预览只会映出残影）；
                // 这条轨只服务观众侧。
                remoteTrack = remoteVideo,
                isHost = isHost,
                peerLabel = CallSession.roomCode?.let { "房间 $it" } ?: "已直连",
                micOn = !micMuted,
                onToggleMic = { CallSession.setMicMuted(!micMuted) },
                latencyMs = stats?.rttMs,
                netLabel = stats?.viaLabel ?: "直连",
                onStop = stop,
            )

            state is CallSession.State.Failed -> FailedScreen(
                backdrop = backdrop,
                reason = (state as CallSession.State.Failed).reason,
                onRetry = stop,
            )

            // 信令就绪：房主要发邀请，观众要回传应答 —— 两条链接长得像，做的事相反。
            state is CallSession.State.SignalReady && isHost -> {
                val s = state as CallSession.State.SignalReady
                val url = CallSession.inviteUrl(INVITE_BASE, s.env)
                InviteScreen(
                    backdrop = backdrop,
                    inviteUrl = url,
                    wireChars = s.wireChars,
                    linkLive = INVITE_BASE != PLACEHOLDER_BASE,
                    onCopy = { context.copy("邀请链接", url) },
                    pasteValue = paste,
                    onPasteChange = { paste = it },
                    onConnect = {
                        val env = SignalingCodec.fromUrl(paste)
                        if (env == null) context.toast("解不开这条应答链接")
                        else CallSession.acceptPeerSignal(env)
                    },
                    onStop = stop,
                )
            }

            state is CallSession.State.SignalReady -> {
                val s = state as CallSession.State.SignalReady
                val url = CallSession.inviteUrl(INVITE_BASE, s.env)
                ViewerAnswerScreen(
                    backdrop = backdrop,
                    answerUrl = url,
                    wireChars = s.wireChars,
                    onCopy = { context.copy("应答链接", url) },
                    onWaiting = true,
                    onStop = stop,
                )
            }

            state is CallSession.State.Preparing -> PreparingScreen(
                backdrop = backdrop,
                title = if (isHost) "正在准备邀请链接" else "正在准备应答",
                note = (state as CallSession.State.Preparing).note,
                // 提示只解释"为什么要等"，耗时数字由上面的 note 给 —— 两处都写
                // "约 2–3 秒"是重复排版（截图上就是这么露出来的）。
                hint = "必须等网络候选收集完才出链接，否则跨网连不通",
                onStop = stop,
            )

            state is CallSession.State.Connecting -> PreparingScreen(
                backdrop = backdrop,
                title = "正在建立直连",
                note = "逐对尝试候选地址（打洞），通常几秒内出结果",
                hint = null,
                onStop = stop,
            )

            state is CallSession.State.WaitingPeer -> PreparingScreen(
                backdrop = backdrop,
                // WaitingPeer 现在**只**由 ICE DISCONNECTED 产生（等回传应答是 SignalReady，
                // 建立中是 Connecting）。这里曾经写着"等朋友回传应答"，是照着状态名猜的 ——
                // 真跑一遍就露馅：应答早就到了，画面已经通过一次，再显示这句话自相矛盾。
                title = "连接抖动，正在尝试自愈",
                note = (state as CallSession.State.WaitingPeer).note,
                hint = null,
                onStop = stop,
            )

            // 理论上不可达的组合（例如角色已清但状态未回到 Idle）落回首页，
            // 宁可少一屏也不能白屏。
            else -> home()
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

private class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markConnected() = sp.edit().putLong(KEY_LAST_CONNECTED, System.currentTimeMillis()).apply()

    fun lastSummary(): String? {
        val at = sp.getLong(KEY_LAST_CONNECTED, 0L)
        if (at == 0L) return null
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(at))
    }
}

private fun Context.prefs() = Prefs(this)
