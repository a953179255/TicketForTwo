package com.ticketfortwo.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.rtc.SignalingCodec
import com.ticketfortwo.app.ui.theme.TicketForTwoTheme
import kotlinx.coroutines.launch
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * M0 可行性闸门的驱动界面 —— 故意用朴素 Material，玻璃套件是任务 2 的事。
 *
 * 这一屏只回答一个问题：**手机采集 → 直连 → 浏览器出图 + 双向能说话**，
 * 这条最细的通路能不能跑穿。跑不穿，后面所有 UI 打磨都没有意义。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TicketForTwoTheme {
                Scaffold { pad -> M0Screen(Modifier.padding(pad)) }
            }
        }
        consumeIncomingSignal()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIncomingSignal()
    }

    /**
     * 消费朋友回传的应答链接。两种入口：
     *  1) 真实路径 —— 他在聊天里点开我们的链接，走 intent-filter 的 data；
     *  2) 调试路径 —— adb 用 --es answer 直接塞 extras（脚本化 M0 时不必模拟点击）。
     *
     * 只有房主在等应答时才生效；观众点开自己的应答链接不该自连。
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
            CallSession.logEvent("收到的不是应答，是 ${env.kind}")
            return
        }
        CallSession.logEvent("收到对方应答，开始建立直连")
        CallSession.acceptPeerSignal(env)
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
    }
}

/** 一期链接由 App 内的假基址生成；真上线时换成实际部署的静态页地址。 */
private const val INVITE_BASE = "https://share.local/"

private enum class Mode { Host, Viewer }

@Composable
private fun M0Screen(modifier: Modifier) {
    val context = LocalContext.current
    val state by CallSession.state.collectAsState()
    val localVideo by CallSession.localVideo.collectAsState()
    val remoteVideo by CallSession.remoteVideo.collectAsState()
    val log by CallSession.log.collectAsState()

    var mode by remember { mutableStateOf(Mode.Host) }
    var pasteInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val projection = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // 投屏授权结果 → 起前台服务 → 才开始采集。顺序不能换：Android 14+ 要求
    // "先授权 → 再起 mediaProjection 前台服务 → 才能 getMediaProjection()"。
    val screenGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        CallSession.logEvent("投屏授权返回 resultCode=${res.resultCode} data=${data != null}")
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            scope.launch {
                ShareService.start(context)
                // 必须等服务进入前台之后再取 MediaProjection，否则 Android 14+ 抛
                // SecurityException。超时给出明确失败，而不是崩给系统。
                if (!ShareService.awaitReady()) {
                    CallSession.logEvent("前台服务未在 3 秒内就绪，放弃本次分享")
                    toast(context, "没能进入分享状态，请重试")
                    return@launch
                }
                CallSession.startHost(context, data, roomCode = "K7M2")
            }
        } else {
            toast(context, "授权被取消；注意弹窗里要选「整个屏幕」")
        }
    }

    val micGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 拒绝麦克风也继续：只是不能连麦，画面仍可分享
        screenGrant.launch(projection.createScreenCaptureIntent())
    }
    val notifGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { micGrant.launch(Manifest.permission.RECORD_AUDIO) }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("双人票 · M0 闸门", style = MaterialTheme.typography.titleLarge)
        Text(stateText(state), style = MaterialTheme.typography.bodyMedium)

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == Mode.Host,
                onClick = { mode = Mode.Host },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("房主") }
            SegmentedButton(
                selected = mode == Mode.Viewer,
                onClick = { mode = Mode.Viewer },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("观众") }
        }

        if (mode == Mode.Host) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { notifGrant.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    enabled = state is CallSession.State.Idle,
                ) { Text("开始分享") }
                OutlinedButton(
                    onClick = { CallSession.stop(context) },
                    enabled = state !is CallSession.State.Idle,
                ) { Text("停止") }
            }
        } else {
            OutlinedTextField(
                value = pasteInput,
                onValueChange = { pasteInput = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text("粘贴房主发来的邀请链接") },
            )
            Button(
                onClick = {
                    val env = SignalingCodec.fromUrl(pasteInput)
                    if (env == null) {
                        toast(context, "这不是双人票的链接，或者内容被聊天软件截断了")
                    } else {
                        CallSession.startViewer(context, env)
                    }
                },
                enabled = pasteInput.isNotBlank() && state is CallSession.State.Idle,
            ) { Text("以观众进入") }
        }

        // 房主：粘贴观众回传的应答链接完成握手
        if (mode == Mode.Host && state is CallSession.State.SignalReady) {
            OutlinedTextField(
                value = pasteInput,
                onValueChange = { pasteInput = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                label = { Text("粘贴对方回传的应答链接") },
            )
            OutlinedButton(onClick = {
                val env = SignalingCodec.fromUrl(pasteInput)
                if (env == null) toast(context, "解不开这条链接") else CallSession.acceptPeerSignal(env)
            }) { Text("连接") }
        }

        // 信令就绪：把链接显示出来给人复制，并标出真实链长（M0 要量的数）
        (state as? CallSession.State.SignalReady)?.let { s ->
            val url = CallSession.inviteUrl(INVITE_BASE, s.env)
            Text(
                "token ${s.wireChars} 字符 · 完整链接 ${url.length} 字符",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5,
                readOnly = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            TextButton(onClick = { copy(context, url) }) { Text("复制") }
        }

        val track: VideoTrack? = remoteVideo ?: localVideo
        if (track != null) {
            Text(
                if (remoteVideo != null) "对方画面" else "我的采集预览",
                style = MaterialTheme.typography.labelMedium,
            )
            VideoSurface(track)
        }

        Text("日志", style = MaterialTheme.typography.labelMedium)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            log.takeLast(16).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * libwebrtc 的画面落在 SurfaceView 上。
 *
 * 注意这条限制会一直影响 UI 设计：**SurfaceView 的内容抓不到**，
 * 所以后面做液态玻璃时，玻璃只能盖在非视频区域，视频之上只能用 scrim。
 */
@Composable
private fun VideoSurface(track: VideoTrack) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        AndroidView(
            factory = {
                RtcEngine.init(context)
                renderer.init(RtcEngine.eglBase.eglBaseContext, null)
                renderer
            },
            onRelease = { renderer.release() },
            modifier = Modifier.fillMaxSize(),
        )
    }
    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose { track.removeSink(renderer) }
    }
}

private fun stateText(s: CallSession.State): String = when (s) {
    CallSession.State.Idle -> "未开始"
    is CallSession.State.Preparing -> "准备中：${s.note}"
    is CallSession.State.SignalReady ->
        if (s.env.kind == SignalingCodec.Kind.Offer) "邀请已生成，把它发给朋友"
        else "应答已生成，把它回传给房主"
    is CallSession.State.WaitingPeer -> "等待对方：${s.note}"
    CallSession.State.Connecting -> "正在建立直连…"
    CallSession.State.Connected -> "已直连 ✅"
    is CallSession.State.Failed -> "失败：${s.reason}"
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("invite", text))
    toast(context, "已复制")
}

private fun toast(context: Context, msg: String) =
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
