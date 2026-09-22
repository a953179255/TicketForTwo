package com.ticketfortwo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ticketfortwo.app.capture.ScreenShareController
import com.ticketfortwo.app.rtc.Peer
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.signaling.SignalHub
import com.ticketfortwo.app.tunnel.TunnelManager
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
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.VideoTrack

/**
 * 一次分享的编排：采集 → 传话员 → 门牌 → Peer → 连接。
 *
 * 进程内单例。前台服务只负责保活与"停止"通知，真正的媒体状态在这里，
 * 这样 Activity 被系统回收后分享不会跟着断（断的是 UI，不是流）。
 *
 * ## 与上一版最大的不同
 *
 * 上一版是"零服务器"：房主把 SDP 塞进链接，观众生成 answer 后**还得人肉回传**，
 * 一来一回 20–40 秒，而且断了没法重连。
 *
 * 现在房主这边有两样东西：
 *  - [SignalHub] —— 一个跑在 127.0.0.1 上的极小 HTTP + WebSocket 服务（"传话员"）；
 *  - [TunnelManager] —— 一条**出站**隧道，把它挂到一个临时公网 HTTPS 地址（"门牌"）。
 *
 * 于是观众点开链接、连上那条 WebSocket 之后，双方的 SDP 与 ICE 候选自己就交换完了 ——
 * 房主侧零操作，也不再需要"回传应答"那一屏。
 * 媒体仍然走两端直连（SRTP），隧道只承载控制信息。
 */
object CallSession {

    enum class Role { Host, Viewer }

    sealed interface State {
        data object Idle : State
        data class Preparing(val note: String) : State

        /** 门牌已就绪，等朋友加入。[inviteUrl] 就是要发出去的那条链接。 */
        data class WaitingViewer(val inviteUrl: String) : State

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

    /** 当前这一端在分享里的角色。放这里而不是 Activity 里：Activity 会被系统回收，
     *  分享不会 —— 重建后 UI 要能从会话本身恢复出正确的分支。 */
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

    /** 采集出来的本地视频轨 —— 观众加入时才 addTrack，所以要先留着。 */
    private var localVideoTrack: VideoTrack? = null
    private var quality: ShareQuality = ShareQuality()
    private var sessionContext: Context? = null

    val isActive: Boolean get() = capture != null || peer != null || SignalHub.port != 0

    init {
        // 观众侧的一切消息都由这条流驱动。
        scope.launch {
            SignalHub.incoming.collect { onViewerMessage(it) }
        }
    }

    // ---- 房主 ----------------------------------------------------------

    /**
     * 房主开始分享。[permissionIntent] 必须是**本次**授权拿到的 Intent ——
     * Android 14 起复用旧 Intent 再次 getMediaProjection 会抛 SecurityException。
     *
     * [permissionIntent] 为 null = 仅语音模式：不采集屏幕、不建视频轨，
     * 但前台服务照常起（保后台麦克风），[quality] 决定分辨率/帧率/码率上限。
     */
    fun startHost(
        context: Context,
        permissionIntent: Intent?,
        quality: ShareQuality = ShareQuality(),
    ) {
        if (isActive) {
            note("已有进行中的分享，忽略本次请求")
            return
        }
        RtcEngine.init(context)
        _role.value = Role.Host
        this.quality = quality
        this.sessionContext = context.applicationContext
        _state.value = State.Preparing(
            if (permissionIntent != null) "正在启动屏幕采集" else "正在准备语音通话"
        )

        val cap = if (permissionIntent != null) {
            ScreenShareController(context.applicationContext, permissionIntent).also {
                it.onStoppedBySystem = { reason ->
                    note("系统停止：$reason")
                    stop(context)
                }
                capture = it
            }
        } else null

        val vt = cap?.start(fps = quality.fps, scale = quality.scale)
        localVideoTrack = vt
        _localVideo.value = vt
        val at = ensureMicTrack(context)
        if (vt == null && at == null) {
            // 一条媒体都没有：连上也没有任何可传的东西 —— 直接失败好过转圈。
            stop(context)
            _state.value = State.Failed("没有麦克风权限，语音模式无法开始；请授予麦克风权限后重试")
            return
        }

        // 传话员与门牌都不需要用户操作，但都不是瞬间完成，所以状态机要把这几秒画出来。
        scope.launch {
            _state.value = State.Preparing("正在准备接入口")
            if (!SignalHub.start(context.applicationContext)) {
                failAndStop(context, "接入口启动失败，请重试")
                return@launch
            }

            _state.value = State.Preparing("正在申请临时地址（通常几秒）")
            TunnelManager.resetState()
            val ok = TunnelManager.start(context.applicationContext, SignalHub.port)
            if (!ok) {
                val reason = (TunnelManager.state.value as? TunnelManager.State.Failed)?.reason
                    ?: "临时地址申请失败，请检查网络后重试"
                failAndStop(context, reason)
                return@launch
            }

            val origin = TunnelManager.origin
            if (origin == null) {
                failAndStop(context, "临时地址不可用，请重试")
                return@launch
            }

            val url = "$origin/?k=${SignalHub.accessKey}"
            // 把完整链接打进日志：脚本化验收要从 logcat 直接取它 ——
            // 让人手动点复制再粘贴，在无人值守的验收里根本做不到。
            note("邀请链接已就绪：$url")
            _state.value = State.WaitingViewer(url)
        }
    }

    private fun failAndStop(context: Context, reason: String) {
        note(reason)
        stop(context)
        _state.value = State.Failed(reason)
    }

    // ---- 观众消息处理（全部来自 SignalHub）------------------------------

    private fun onViewerMessage(text: String) {
        if (_role.value != Role.Host) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("t")) {
            "hello" -> onViewerJoined()

            "answer" -> {
                val sdp = obj.optString("sdp")
                if (sdp.isNotEmpty()) {
                    note("收到对方应答，完成握手")
                    _state.value = State.Connecting
                    peer?.acceptAnswer(sdp)
                }
            }

            "cand" -> {
                val cand = obj.optString("cand")
                if (cand.isNotEmpty()) {
                    peer?.addRemoteCandidate(
                        IceCandidate(
                            obj.optString("mid").ifEmpty { null },
                            obj.optInt("mline", 0),
                            cand,
                        )
                    )
                }
            }

            "bye" -> {
                note("观众已离开；链接仍然有效，让他再点一次即可")
                teardownPeer()
                val url = (_state.value as? State.WaitingViewer)?.inviteUrl
                _state.value = if (url != null) State.WaitingViewer(url) else State.Idle
            }
        }
    }

    private fun onViewerJoined() {
        if (_role.value != Role.Host) return
        val context = sessionContext ?: return
        note("观众已加入，开始建立直连")
        _state.value = State.Connecting

        teardownPeer()
        val p = newPeer(context, Peer.Role.Offerer)

        val senders = p.addLocalTracks(localVideoTrack, audioTrack)
        videoSender = senders.video
        if (senders.video != null) {
            applyVideoBitrateCap(quality.maxVideoBps)
        } else {
            note("仅语音模式：不发送视频")
        }
        p.startOffer()
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
        teardownPeer()
        runCatching { capture?.release() }
        capture = null
        runCatching { localVideoTrack?.dispose() }
        localVideoTrack = null
        runCatching { videoSender?.dispose() }
        videoSender = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        _localVideo.value = null
        _remoteVideo.value = null
        _role.value = null
        _micMuted.value = false
        _state.value = State.Idle
        sessionContext = null

        TunnelManager.stop()
        SignalHub.stop()

        context.stopService(Intent(context, ShareService::class.java))
    }

    private fun teardownPeer() {
        stopStatsPump()
        runCatching { peer?.close() }
        peer = null
        _remoteVideo.value = null
    }

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

    /** 给 UI 层记一条事件。授权被取消这类分支必须有痕迹，否则排障只能靠猜。 */
    fun logEvent(msg: String) = note(msg)

    private fun note(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + msg).takeLast(60)
    }

    private fun newPeer(context: Context, role: Peer.Role): Peer {
        val p = Peer(role, "t2", listener = object : Peer.Listener {
            override fun onLocalDescription(kind: Peer.Kind, sdp: String) {
                // 有了通道就不再等候选收集完成 —— 立刻发走，候选随后 trickle。
                val t = if (kind == Peer.Kind.Offer) "offer" else "answer"
                val sent = SignalHub.sendToViewer(
                    JSONObject().apply {
                        put("t", t)
                        put("sdp", sdp)
                    }.toString()
                )
                note("已发送 $t（${sdp.length} 字符，送出=$sent）")
            }

            override fun onLocalCandidate(candidate: IceCandidate) {
                SignalHub.sendToViewer(
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
                // DISCONNECTED 不在这里判死：Peer 会先等自愈，最终失败通过 onFailure 上来。
                _state.value = when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        startStatsPump()
                        State.Connected
                    }

                    PeerConnection.IceConnectionState.FAILED ->
                        State.Failed("直连失败：一方可能在对称 NAT 之后")

                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        stopStatsPump()
                        State.Connecting
                    }

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
    private fun applyVideoBitrateCap(bps: Int) {
        val sender = videoSender ?: run { note("拿不到视频 sender，码率上限未生效"); return }
        runCatching {
            val params: RtpParameters = sender.parameters ?: return
            params.encodings.firstOrNull()?.maxBitrateBps = bps
            sender.parameters = params
            note("码率上限 -> ${bps / 1000} kbps")
        }.onFailure { note("设置码率失败：${it.message}") }
    }
}
