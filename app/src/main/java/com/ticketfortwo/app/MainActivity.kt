package com.ticketfortwo.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import com.ticketfortwo.app.ui.app.CallScreen
import com.ticketfortwo.app.ui.app.ConsentGuideScreen
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
}

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
    var showSettings by remember { mutableStateOf(false) }
    var viewerIntent by remember { mutableStateOf(false) }
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

    // 观众入口：本 App 不再自己收流，直接把链接交给系统浏览器。
    //
    // 为什么这么改：浏览器是这套方案里观众端**唯一**的实现，也是唯一被真正测过的路径。
    // 若再在 App 里用 Kotlin 写一遍 WebSocket 客户端，等于凭空多一份要维护、
    // 且必须单独验证的实现 —— 收益为零，风险翻倍。
    fun submitViewerLink() {
        val url = paste.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            viewerError = "这不像一条邀请链接。请把房主发来的整条网址原样粘进来（以 https:// 开头）。"
            return
        }
        viewerError = null
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        }.onFailure { viewerError = "没能唤起浏览器：${it.message}" }
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
            onStart = {
                // 仅语音不需要投屏指引（那两步都是给投屏授权准备的），直接进权限链。
                if (quality.videoEnabled) showConsent = true else startHostFlow()
            },
            onJoinViewer = { viewerIntent = true; paste = ""; viewerError = null },
            onSettings = { showSettings = true },
            quality = quality,
            lastSummary = lastConnected,
        )
    }

    Box(Modifier.fillMaxSize()) {
        when {
            // 分享设置：纯 UI 意图，和授权指引一样排在最前面。
            showSettings -> QualitySettingsScreen(
                backdrop = backdrop,
                quality = quality,
                onChange = { q ->
                    quality = q
                    scope.launch(Dispatchers.IO) { ShareQuality.save(context, q) }
                },
                onBack = { showSettings = false },
            )

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
                // 房间码属于"链接里塞 SDP"那个时代的产物：那时靠它防两个人撞车。
                // 现在链接里只有一个随机凭证，展示房间码没有意义，直接说明状态即可。
                peerLabel = "已直连",
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

            // 门牌就绪：把这条链接发出去就完事，剩下的双方自己会走完。
            state is CallSession.State.WaitingViewer -> {
                val url = (state as CallSession.State.WaitingViewer).inviteUrl
                InviteScreen(
                    backdrop = backdrop,
                    inviteUrl = url,
                    onCopy = { context.copy("邀请链接", url) },
                    onStop = stop,
                )
            }

            state is CallSession.State.Preparing -> PreparingScreen(
                backdrop = backdrop,
                title = "正在准备接入口",
                note = (state as CallSession.State.Preparing).note,
                hint = "采集已经开始了；要等的是临时地址，通常几秒",
                onStop = stop,
            )

            state is CallSession.State.Connecting -> PreparingScreen(
                backdrop = backdrop,
                title = "正在建立直连",
                note = "逐对尝试候选地址（打洞），通常几秒内出结果",
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
