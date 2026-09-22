package com.ticketfortwo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ticketfortwo.app.capture.ScreenShareController
import com.ticketfortwo.app.rtc.Peer
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.rtc.SignalingCodec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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

    /** 控制岛上 RTT / 通路类型的刷新间隔。 */
    private const val STATS_INTERVAL_MS = 2_000L

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    /** 当前这一端在通话里的角色。放这里而不是放 Activity 里：Activity 会被系统回收，
     *  通话不会 —— 重建后 UI 要能从会话本身恢复出正确的分支。 */
    private val _role = MutableStateFlow<Role?>(null)
    val role: StateFlow<Role?> = _role.asStateFlow()

    private val _netStats = MutableStateFlow<Peer.NetStats?>(null)
    val netStats: StateFlow<Peer.NetStats?> = _netStats.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statsJob: Job? = null
    private var peer: Peer? = null
    private var capture: ScreenShareController? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null

    val isActive: Boolean get() = peer != null

    /** 本次会话的短码，显示在界面上给人肉眼核对（也编在链接里）。 */
    var roomCode: String? = null
        private set

    // ---- 房主 ----------------------------------------------------------

    /**
     * 房主开始分享。[permissionIntent] 必须是**本次**授权拿到的 Intent ——
     * Android 14 起复用旧 Intent 再次 getMediaProjection 会抛 SecurityException。
     */
    fun startHost(context: Context, permissionIntent: Intent, code: String) {
        if (isActive) { note("已有进行中的分享，忽略本次请求"); return }
        RtcEngine.init(context)
        _role.value = Role.Host
        roomCode = code
        _state.value = State.Preparing("正在启动屏幕采集")

        val cap = ScreenShareController(context.applicationContext, permissionIntent)
        cap.onStoppedBySystem = { reason ->
            note("系统停止：$reason")
            stop(context)
        }
        capture = cap

        val p = newPeer(context, Peer.Role.Offerer, code)
        p.open()
        val vt = cap.start(fps = DEFAULT_VIDEO_FPS, scale = DEFAULT_CAPTURE_SCALE)
        _localVideo.value = vt
        val at = ensureMicTrack(context)
        val senders = p.addLocalTracks(vt, at)
        videoSender = senders.video
        applyVideoBitrateCap()
        p.startOffer()
        _state.value = State.Preparing("正在收集网络候选（约 2–3 秒）")
    }

    /** 观众点开房主发来的链接后，把房主的 answer 交回给房主用；房主侧调用这个。 */
    fun acceptPeerSignal(env: SignalingCodec.Envelope) {
        val p = peer ?: run { note("收到信令但没有进行中的分享"); return }
        _state.value = State.Connecting
        p.acceptRemote(env)
    }

    // ---- 观众 ----------------------------------------------------------

    /** 观众：吃进房主的 offer，产出 answer（这条 answer 要回传给房主才算连上）。 */
    fun startViewer(context: Context, offer: SignalingCodec.Envelope) {
        if (isActive) { note("已在通话中"); return }
        RtcEngine.init(context)
        _role.value = Role.Viewer
        roomCode = offer.room
        _state.value = State.Preparing("正在准备应答")
        val p = newPeer(context, Peer.Role.Answerer, offer.room)
        p.open()
        ensureMicTrack(context)?.let { p.addLocalTracks(null, it) }
        p.acceptRemote(offer)
    }

    // ---- 公共 ----------------------------------------------------------

    fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
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
        stopStatsPump()
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
        _role.value = null
        roomCode = null
        _micMuted.value = false
        _state.value = State.Idle
        context.stopService(Intent(context, ShareService::class.java))
    }

    fun inviteUrl(base: String, env: SignalingCodec.Envelope): String =
        SignalingCodec.toUrl(base, env)

    /**
     * 每 2 秒取一次实测连接质量。
     *
     * 只在"已经连上"之后跑：没连上时 stats 里全是探测噪声，画出来的数字会比没有更误导。
     * 单次采集设 1.5 秒超时 —— libwebrtc 在 PeerConnection 关闭时可能永不回调，
     * 少了这道闸，这个 while 循环会留一个悬着的协程。
     */
    private fun startStatsPump() {
        if (statsJob?.isActive == true) return
        statsJob = scope.launch {
            while (isActive) {
                val p = peer ?: return@launch
                val shot = CompletableDeferred<Peer.NetStats>()
                p.collectStats { shot.complete(it) }
                val got = withTimeoutOrNull(1_500) { shot.await() }
                if (got != null) _netStats.value = got
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    private fun stopStatsPump() {
        statsJob?.cancel()
        statsJob = null
        _netStats.value = null
    }

    /**
     * 一次会话的短码，编进链接里当房间标识。
     *
     * 去掉 O/0/I/1 这类在聊天窗口里容易看错的字符 —— 这条码会被人肉眼核对。
     * 一期它是 4 位（约 120 万种组合）：不防恶意，只防"两个人正好同时开"，够用。
     */
    fun newRoomCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return (1..4).map { alphabet.random() }.joinToString("")
    }

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
                // DISCONNECTED 不在这里判死：Peer 会先等自愈、再 restartIce，
                // 最终失败通过 onFailure 上来。
                _state.value = when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        startStatsPump()
                        State.Connected
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        stopStatsPump()
                        State.Failed("直连失败：一方可能在对称 NAT 之后")
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        stopStatsPump()
                        // 标题由界面给（"连接抖动，正在尝试自愈"），这里只补充值信息：
                        // 宽限期多长、超时会怎样。两处文字重复是排版缺陷。
                        State.WaitingPeer("网络切换或 NAT 映射过期时常见；8 秒内未恢复即判失败")
                    }
                    // CHECKING 是"正在逐对试候选"，画成 Connecting 而不是继续转上一步的圈：
                    // 房主点了「连接」之后必须看到界面动了，否则他会重复粘贴、反复点。
                    PeerConnection.IceConnectionState.CHECKING -> State.Connecting
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
                stopStatsPump()
                _state.value = State.Failed(reason)
            }
        })
        return p.also { peer = it }
    }

    /**
     * 麦克风轨。用空的 [MediaConstraints] —— 默认就会启用 AEC / NS / AGC。
     * 关键约束：**这条轨存在时**，addTrack 产生的 transceiver 是 sendrecv，
     * 双向才都有声音（静音只 setEnabled(false)，不移除轨道）。
     *
     * 没有 RECORD_AUDIO 就**根本不建这条轨**：libwebrtc 的音频设备是在连接时才
     * 打开 AudioRecord，届时权限被拒会走 SDK 内部的 CHECK 失败路径（可能直接 abort），
     * 而不是一个能 catch 的异常。宁可不连麦，也不留一个不知道会不会崩的雷。
     */
    private fun ensureMicTrack(context: Context): AudioTrack? {
        audioTrack?.let { return it }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            note("未授予麦克风权限：本次只能分享画面，不能连麦")
            return null
        }
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
