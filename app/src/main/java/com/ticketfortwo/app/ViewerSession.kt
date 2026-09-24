package com.ticketfortwo.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.ticketfortwo.app.rtc.Peer
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.signaling.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack
import java.net.URI

/**
 * 观众端会话：在 **App 内**收看别人的分享（不跳浏览器）。
 *
 * 与 [CallSession]（房主）共用同一套底层：信令走 WebSocket（[WsClient] 连房主的
 * 传话员），媒体走 [Peer] 的 Answerer 角色。
 *
 * 为什么补这一条路：网页观众端一直能用，但**各家手机浏览器的播放器差异太大**
 * （自动播放被拦、全屏/PiP 行为不一、断线要手动刷新）。App 内收看把这层不确定性
 * 收掉，还能显示实时延迟。网页路径保留为备用（不想装 App 的朋友仍可点链接看）。
 */
object ViewerSession {

    sealed interface State {
        data object Idle : State

        data class Connecting(val note: String) : State

        data object Connected : State

        data class Failed(val reason: String, val verdict: com.ticketfortwo.app.rtc.Verdict? = null) : State
    }

    private const val TAG = "ViewerSession"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _micMuted = MutableStateFlow(true)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ws: WsClient? = null
    private var peer: Peer? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    /** 远端描述就绪前先攒着的候选（顺序错了 addIceCandidate 会抛）。 */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    val isActive: Boolean get() = ws != null || peer != null

    // ---- 生命周期 --------------------------------------------------------

    fun start(context: Context, inviteUrl: String) {
        stop()
        RtcEngine.init(context)

        val wsUrl = wsUrlOf(inviteUrl)
        if (wsUrl == null) {
            fail("链接看不懂：需要形如 https://xxx/?k=凭证 的邀请链接")
            return
        }

        _state.value = State.Connecting("正在连接房主的手机…")

        val client = WsClient(
            url = wsUrl,
            onOpen = {
                note("通道已建立，正在向房主打招呼")
                send(JSONObject().apply { put("t", "hello"); put("role", "viewer") }.toString())
            },
            onText = { text -> scope.launch { onMessage(context, text) } },
            onClosed = { reason ->
                scope.launch {
                    if (_state.value is State.Connected) {
                        fail("与房主的连接断了：${reason ?: "对方可能已停止分享"}")
                    } else if (_state.value !is State.Failed) {
                        fail(reason ?: "连不上房主的手机（可能分享已结束，或网络不允许）")
                    }
                }
            },
        )
        ws = client
        client.connect()
    }

    fun stop() {
        runCatching { ws?.close() }
        ws = null
        runCatching { peer?.close() }
        peer = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        pendingRemoteCandidates.clear()
        _remoteVideo.value = null
        _micMuted.value = true
        _state.value = State.Idle
    }

    fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
        audioTrack?.setEnabled(!muted)
    }

    // ---- 消息 ------------------------------------------------------------

    private suspend fun onMessage(context: Context, text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("t")) {
            "offer" -> {
                val sdp = obj.optString("sdp")
                if (sdp.isEmpty()) return
                note("收到画面信息，准备回话")
                val p = ensurePeer(context)
                p.acceptOffer(sdp)
                if (pendingRemoteCandidates.isNotEmpty()) {
                    // 这些候选比 offer 先到（罕见）。setRemoteDescription 是异步的，
                    // 立刻补会因"远端描述未设"被丢弃，所以稍等它落地再补。
                    val pending = pendingRemoteCandidates.toList()
                    pendingRemoteCandidates.clear()
                    scope.launch {
                        delay(600)
                        pending.forEach { p.addRemoteCandidate(it) }
                    }
                }
            }

            "cand" -> {
                val cand = obj.optString("cand")
                if (cand.isEmpty()) return
                val c = IceCandidate(
                    obj.optString("mid").ifEmpty { null },
                    obj.optInt("mline", 0),
                    cand,
                )
                val p = peer
                if (p == null) pendingRemoteCandidates += c else p.addRemoteCandidate(c)
            }

            "bye" -> fail("房主停止了分享")
        }
    }

    private fun ensurePeer(context: Context): Peer {
        peer?.let { return it }
        val p = Peer(
            role = Peer.Role.Answerer,
            room = "t2",
            listener = object : Peer.Listener {
                override fun onLocalDescription(kind: Peer.Kind, sdp: String) {
                    send(
                        JSONObject().apply {
                            put("t", if (kind == Peer.Kind.Offer) "offer" else "answer")
                            put("sdp", sdp)
                        }.toString()
                    )
                    note("已回话（${kind.name}，${sdp.length} 字符）")
                }

                override fun onLocalCandidate(candidate: IceCandidate) {
                    send(
                        JSONObject().apply {
                            put("t", "cand")
                            put("cand", candidate.sdp)
                            put("mid", candidate.sdpMid ?: "")
                            put("mline", candidate.sdpMLineIndex)
                        }.toString()
                    )
                }

                override fun onIceState(s: PeerConnection.IceConnectionState) {
                    note("ice=$s")
                    when (s) {
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED ->
                            _state.value = State.Connected

                        PeerConnection.IceConnectionState.FAILED -> {
                            val v = peer?.probe?.verdict()
                            note("ICE 判定：${v?.headline}｜${peer?.probe?.summary()}")
                            fail(v?.headline ?: "直连失败", v)
                        }

                        PeerConnection.IceConnectionState.DISCONNECTED ->
                            _state.value = State.Connecting("连接抖动，正在自愈…")

                        else -> Unit
                    }
                }

                override fun onSignalingState(s: PeerConnection.SignalingState) = Unit

                override fun onRemoteVideo(track: VideoTrack?) {
                    _remoteVideo.value = track
                    note("画面已到")
                }

                override fun onRemoteAudio(track: AudioTrack?) {
                    track?.setEnabled(true)
                    note("收到房主的声音")
                }

                override fun onControlMessage(text: String) = Unit

                override fun onFailure(reason: String) = fail(reason)
            },
        )
        p.open()
        // 观众的麦克风：连接时就带上轨道（静音），这样 answer 里的方向是 sendrecv，
        // 房主才收得到 —— 与房主侧同一个道理，中途加轨需要重新协商，做不到。
        val mic = ensureMicTrack(context)
        p.addLocalTracks(null, mic)
        peer = p
        return p
    }

    private fun ensureMicTrack(context: Context): AudioTrack? {
        audioTrack?.let { return it }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            note("未授予麦克风权限：只看画面，不能连麦")
            return null
        }
        val src = RtcEngine.factory.createAudioSource(MediaConstraints()) ?: return null
        val track = RtcEngine.factory.createAudioTrack("mic", src)
        track.setEnabled(false)   // 观众默认静音，点麦克风按钮才发言
        audioSource = src
        audioTrack = track
        return track
    }

    // ---- 工具 ------------------------------------------------------------

    private fun send(text: String) {
        ws?.send(text)
    }

    private fun fail(reason: String, verdict: com.ticketfortwo.app.rtc.Verdict? = null) {
        note(reason)
        runCatching { ws?.close() }
        ws = null
        _state.value = State.Failed(reason, verdict)
    }

    private fun note(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + msg).takeLast(40)
    }

    /**
     * 邀请链接 → 传话员的 WebSocket 地址。
     * `https://host/?k=xxx` → `wss://host/ws?k=xxx`（http 则对应 ws）。
     */
    fun wsUrlOf(invite: String): String? = runCatching {
        val u = URI(invite.trim())
        val host = u.host ?: return null
        val scheme = if (u.scheme.equals("https", true)) "wss" else "ws"
        val key = (u.query ?: "")
            .split("&")
            .firstOrNull { it.startsWith("k=") }
            ?.removePrefix("k=")
            ?.takeIf { it.isNotEmpty() }
            ?: return null
        "$scheme://$host/ws?k=$key"
    }.getOrNull()
}
