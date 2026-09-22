package com.ticketfortwo.app

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ticketfortwo.app.capture.ScreenShareController
import com.ticketfortwo.app.rtc.Peer
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.rtc.SignalingCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.VideoTrack

/**
 * 一次通话的编排：采集 → 轨道 → Peer → 信令 → 状态。
 *
 * 进程内单例。前台服务只负责保活与"停止"通知，真正的媒体状态在这里，
 * 这样 Activity 被系统回收后通话不会跟着断（断的是 UI，不是流）。
 */
object CallSession {

    enum class Role { Host, Viewer }

    sealed interface State {
        data object Idle : State
        data class Preparing(val note: String) : State
        /** 信令已就绪，等待用户把链接发出去 / 等对方回传。 */
        data class SignalReady(val env: SignalingCodec.Envelope, val wireChars: Int) : State
        data class WaitingPeer(val note: String) : State
        data object Connecting : State
        data object Connected : State
        data class Failed(val reason: String) : State
    }

    private const val TAG = "CallSession"

    /** 一期保守默认：720p30 / 2.0 Mbps。1080p60 在多数机型会因发热降帧。 */
    const val DEFAULT_VIDEO_FPS = 30
    const val DEFAULT_CAPTURE_SCALE = 0.75f
    const val DEFAULT_MAX_VIDEO_BPS = 2_000_000

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var peer: Peer? = null
    private var capture: ScreenShareController? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null

    val isActive: Boolean get() = peer != null

    // ---- 房主 ----------------------------------------------------------

    /**
     * 房主开始分享。[permissionIntent] 必须是**本次**授权拿到的 Intent ——
     * Android 14 起复用旧 Intent 再次 getMediaProjection 会抛 SecurityException。
     */
    fun startHost(context: Context, permissionIntent: Intent, roomCode: String) {
        if (isActive) { note("已有进行中的分享，忽略本次请求"); return }
        RtcEngine.init(context)
        _state.value = State.Preparing("正在启动屏幕采集")

        val cap = ScreenShareController(context.applicationContext, permissionIntent)
        cap.onStoppedBySystem = { reason ->
            note("系统停止：$reason")
            stop(context)
        }
        capture = cap

        val p = newPeer(context, Peer.Role.Offerer, roomCode)
        p.open()
        val vt = cap.start(fps = DEFAULT_VIDEO_FPS, scale = DEFAULT_CAPTURE_SCALE)
        _localVideo.value = vt
        val at = ensureMicTrack()
        val senders = p.addLocalTracks(vt, at)
        videoSender = senders.video
        applyVideoBitrateCap()
        p.startOffer()
        _state.value = State.Preparing("正在收集网络候选（约 2–3 秒）")
    }

    /** 观众点开房主发来的链接后，把房主的 answer 交回给房主用；房主侧调用这个。 */
    fun acceptPeerSignal(env: SignalingCodec.Envelope) {
        val p = peer ?: run { note("收到信令但没有进行中的分享"); return }
        _state.value = State.WaitingPeer("正在建立直连")
        p.acceptRemote(env)
    }

    // ---- 观众 ----------------------------------------------------------

    /** 观众：吃进房主的 offer，产出 answer（这条 answer 要回传给房主才算连上）。 */
    fun startViewer(context: Context, offer: SignalingCodec.Envelope) {
        if (isActive) { note("已在通话中"); return }
        RtcEngine.init(context)
        _state.value = State.Preparing("正在准备应答")
        val p = newPeer(context, Peer.Role.Answerer, offer.room)
        p.open()
        ensureMicTrack()?.let { p.addLocalTracks(null, it) }
        p.acceptRemote(offer)
    }

    // ---- 公共 ----------------------------------------------------------

    fun setMicMuted(muted: Boolean) {        _micMuted.value = muted
        audioTrack?.setEnabled(!muted)
    }

    /**
     * 设置视频码率上限。用"读出现有参数、只改 maxBitrateBps、再写回"的方式，
     * 避免从零构造 RtpParameters（那样会丢掉 codec 与 ssrc，直接断流）。
     */
    fun setMaxVideoBitrate(bps: Int) {
        val sender = videoSender ?: return
        runCatching {
            val params = sender.parameters ?: return
            params.encodings.forEach { it.maxBitrateBps = bps }
            sender.parameters = params
            note("码率上限 -> ${bps / 1000} kbps")
        }.onFailure { note("设置码率失败：${it.message}") }
    }

    fun stop(context: Context) {
        runCatching { peer?.close() }
        peer = null
        runCatching { capture?.release() }
        capture = null
        runCatching { videoSender?.dispose() }
        videoSender = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        _localVideo.value = null
        _remoteVideo.value = null
        _state.value = State.Idle
        context.stopService(Intent(context, ShareService::class.java))
    }

    fun inviteUrl(base: String, env: SignalingCodec.Envelope): String =
        SignalingCodec.toUrl(base, env)

    /** 给 UI 层记一条事件。授权被取消这类分支必须有痕迹，否则排障只能靠猜。 */
    fun logEvent(msg: String) = note(msg)

    private fun note(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + msg).takeLast(60)
    }

    private fun newPeer(context: Context, role: Peer.Role, roomCode: String): Peer {
        val p = Peer(role, roomCode, listener = object : Peer.Listener {
            override fun onSignalReady(env: SignalingCodec.Envelope) {
                val wire = SignalingCodec.wireLength(env)
                note("信令就绪 kind=${env.kind} 链长=${wire}ch")
                _state.value = State.SignalReady(env, wire)
            }

            override fun onIceState(s: PeerConnection.IceConnectionState) {
                note("ice=$s")
                _state.value = when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> State.Connected
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        State.Failed("直连失败：$s（一方可能在对称 NAT 之后）")
                    else -> _state.value
                }
            }

            override fun onSignalingState(s: PeerConnection.SignalingState) = Unit

            override fun onRemoteVideo(track: VideoTrack?) {
                _remoteVideo.value = track
            }

            override fun onRemoteAudio(track: AudioTrack?) {
                // 下行必须交给 libwebrtc 自己的 AudioTrack 播放，AEC 才有参考信号。
                // 这里不额外用 MediaPlayer 播，否则连麦会啸叫。
                track?.setEnabled(true)
                note("收到对方音频轨")
            }

            override fun onControlMessage(text: String) { note("对方：$text") }

            override fun onFailure(reason: String) {
                Log.e(TAG, reason)
                _state.value = State.Failed(reason)
            }
        })
        return p.also { peer = it }
    }

    /**
     * 麦克风轨。用空的 [MediaConstraints] —— 默认就会启用 AEC / NS / AGC。
     * 关键约束：这条轨必须存在（静音只 setEnabled(false)），
     * 因为 addTrack 产生的 transceiver 是 sendrecv，房主才收得到观众声音。
     */
    private fun ensureMicTrack(): AudioTrack? {
        audioTrack?.let { return it }
        val src = RtcEngine.factory.createAudioSource(MediaConstraints()) ?: return null
        val track = RtcEngine.factory.createAudioTrack("mic", src)
        track.setEnabled(!_micMuted.value)
        audioSource = src
        audioTrack = track
        return track
    }

    /**
     * 用"读出现有参数、只改 maxBitrateBps、再写回"的方式设上限。
     * 从零构造 RtpParameters 会丢掉 codec 与 ssrc，直接断流。
     */
    private fun applyVideoBitrateCap() {
        val sender = videoSender ?: run { note("拿不到视频 sender，码率上限未生效"); return }
        runCatching {
            val params: RtpParameters = sender.parameters ?: return
            params.encodings.firstOrNull()?.maxBitrateBps = DEFAULT_MAX_VIDEO_BPS
            sender.parameters = params
            note("码率上限 -> ${DEFAULT_MAX_VIDEO_BPS / 1000} kbps")
        }.onFailure { note("设置码率失败：${it.message}") }
    }
}
