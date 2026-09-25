package com.ticketfortwo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ticketfortwo.app.capture.ScreenShareController
import com.ticketfortwo.app.cinema.CinemaSync
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
import com.ticketfortwo.app.watch.WatchCmd
import com.ticketfortwo.app.watch.WatchState
import com.ticketfortwo.app.watch.WatchSync
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

        /**
         * [reason] 给人看的一句话，[verdict] 是**从候选与状态轨迹推出来的**判定。
         * 没有 verdict 的失败（比如隧道起不来）跟打洞无关，界面就不该显示 ICE 证据。
         */
        data class Failed(val reason: String, val verdict: com.ticketfortwo.app.rtc.Verdict? = null) : State
    }

    private const val TAG = "CallSession"

    /** 一期保守默认：720p30 / 2.0 Mbps。1080p60 在多数机型会因发热降帧。 */
    const val DEFAULT_VIDEO_FPS = 30
    const val DEFAULT_CAPTURE_SCALE = 0.75f
    const val DEFAULT_MAX_VIDEO_BPS = 2_000_000

    /** 控制岛上 RTT / 通路类型的刷新间隔。 */
    private const val STATS_INTERVAL_MS = 2_000L

    /**
     * 观众信令断了之后，最多保留这条直连多久。
     *
     * 观众侧的重连是"最多 4 次、每次约 4.5 秒"，也就是十几秒的量；
     * 这边给 20 秒，比它长一点，免得观众还在努力我们就先把通话收了。
     */
    private const val VIEWER_RECONNECT_GRACE_MS = 20_000L

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

    // ---- 同看（一起看片）---------------------------------------------------
    //
    // 播放器在房主自己的 WebView 里（见 WatchTogetherScreen）。会话这边只做两件事：
    // 把播放器状态广播给观众、把观众发来的指令交给那一屏。
    // 之所以不在这里直接持有 WebView：会话比 Activity 活得久，View 跟着会话活
    // 就变成"泄漏的窗口"，所以用一条注册进来的回调，那一屏卸载时必须摘掉。
    private val _watch = MutableStateFlow<WatchState?>(null)
    val watch: StateFlow<WatchState?> = _watch.asStateFlow()

    /** 方向盘交不交给对方。默认给 —— 这个功能存在的意义就是让对方能快进。 */
    private val _viewerMayControl = MutableStateFlow(true)
    val viewerMayControl: StateFlow<Boolean> = _viewerMayControl.asStateFlow()

    @Volatile
    var onWatchCommand: ((WatchCmd) -> Unit)? = null

    /** 房主侧每轮询到一次播放器状态就调它：更新本地 + 广播给观众。 */
    fun publishWatchState(s: WatchState) {
        _watch.value = s
        broadcastWatch()
    }

    fun setViewerMayControl(on: Boolean) {
        _viewerMayControl.value = on
        broadcastWatch()
        note(if (on) "已允许对方控制播放" else "已收回播放控制")
    }

    private fun broadcastWatch() {
        val s = _watch.value ?: return
        // 没人看就别白发：这条链路是隧道里的 WebSocket，每次发送都有实打实的开销。
        if (!SignalHub.viewerConnected.value) return
        val json = JSONObject().apply {
            put("t", "watch")
            put("f", WatchSync.stateFields(s, _viewerMayControl.value))
        }
        SignalHub.sendToViewer(json.toString())
    }

    // ---- 放映厅（S 档：观众本地播同一条片源）--------------------------------
    //
    // 与上面 `watch` 那套的区别：watch 是"观众看房主的屏幕 + 遥控房主的播放器"，
    // cinema 是"观众自己播同一条流，两边只对时间轴"。前者画质受房主屏幕限制、
    // 后者是原生画质。两条并存，因为 DRM 站点只剩 watch/B 档能走。
    //
    // 权威在房主：房主周期性报 (位置, 那一刻的墙钟, 播放中否, 倍速, 版本)，
    // 观众据此投影出"我现在该在第几毫秒"，小偏差用倍速追、大偏差才 seek。

    private val _cinema = MutableStateFlow<CinemaSync.State?>(null)
    val cinema: StateFlow<CinemaSync.State?> = _cinema.asStateFlow()

    private var cinemaVersion = 0L
    private val cinemaEcho = CinemaSync.EchoGuard()

    /**
     * 最近一条"观众那边到底播出来了没有"的回执；null = 还没收到。
     *
     * 补这个口的理由很直接：房主按下开始放映之后，界面上就写着"正在放映"，
     * 可观众那边如果取不到流是一片黑，房主完全看不出来 —— 这是最容易骗人的一种状态。
     * 第二个理由是统计口径：S 档命中率不是"嗅到了地址"而是"对方真的播出来了"，
     * 这个数决定 A 档（手机做 Range 中继）到底要不要做。测量脚本读 logcat 里的
     * `CINEMA_ACK` 行，不读界面。
     */
    private val _viewerPlayback = MutableStateFlow<CinemaSync.PlaybackAck?>(null)
    val viewerPlayback: StateFlow<CinemaSync.PlaybackAck?> = _viewerPlayback.asStateFlow()

    /** 观众的播放请求落到放映厅那一屏（它持有 WebView）。同 [onWatchCommand] 一样必须成对摘除。 */
    @Volatile
    var onCinemaCommand: ((CinemaSync.Cmd) -> Unit)? = null

    /** 房主选定片源（或收厅）。传 null 表示回到"厅里还没片"的状态。 */
    fun setCinemaTrack(track: CinemaSync.Track?) {
        if (track == null) {
            _cinema.value = null
            _viewerPlayback.value = null
            broadcastCinema()
            note("已收厅，回到整屏分享")
            return
        }
        cinemaVersion++
        // 换了一条片，上一条的回执就不该继续显示在卡片上（否则"对方放不了"会跟着人走）
        _viewerPlayback.value = null
        // 换片和首次递出去要在日志里分得开，否则回看时看不出中途换过片
        val wasScreening = _cinema.value != null
        _cinema.value = CinemaSync.State(
            track = track,
            posMs = 0L,
            durMs = track.durationMs,
            playing = false,
            version = cinemaVersion,
        )
        broadcastCinema()
        val what = CinemaSync.sanitize(track.title)
        // 版本 → 片源的对应关系只有这一刻知道，统计脚本要靠它把回执归到正确的站点上
        Log.i(TAG, "CINEMA_SCREEN $cinemaVersion|${track.url}")
        note(if (wasScreening) "换片了：$what" else "片源已递给对方：$what")
    }

    /** 放映厅那一屏每轮询到一次播放器状态就调它。 */
    fun publishCinemaProgress(posMs: Long, durMs: Long, playing: Boolean) {
        val cur = _cinema.value ?: return
        _cinema.value = cur.copy(
            posMs = posMs,
            durMs = durMs,
            playing = playing,
            hostWallMs = System.currentTimeMillis(),
        )
        broadcastCinema()
    }

    private fun broadcastCinema() {
        if (!SignalHub.viewerConnected.value) return
        val st = _cinema.value
        val json = JSONObject().apply {
            put("t", "cinema")
            // 收厅要能传达：没有状态时发一条空 f，观众端据此退回"厅里还没片"
            put("f", st?.let { CinemaSync.fields(it, _viewerMayControl.value) } ?: "")
        }
        SignalHub.sendToViewer(json.toString())
    }

    /** 收到观众的放映请求。权限判定放在收消息这一侧，不给 UI 留"忘了判"的机会。 */
    private fun onCinemaCommandFromViewer(f: String) {
        val st = _cinema.value
        val cmd = CinemaSync.accept(_viewerMayControl.value, CinemaSync.parseCmd(f)) ?: return
        if (st == null) {
            note("收到播放请求，但厅里还没选片")
            return
        }
        // 步进要在房主这边换算成绝对位置：观众报的是意图，权威值只有房主有
        val real: CinemaSync.Cmd = when (cmd) {
            is CinemaSync.Cmd.Step ->
                CinemaSync.Cmd.Seek(CinemaSync.stepTarget(st.posMs, st.durMs, cmd.deltaMs))
            else -> cmd
        }
        val now = System.currentTimeMillis()
        if (cinemaEcho.inEcho(now)) {
            note("（回声）刚动过，先不重复处理：${real.label()}")
        } else {
            cinemaEcho.markApplied(now)
            note("对方在控制放映：${real.label()}")
        }
        val cb = onCinemaCommand
        if (cb == null) note("收到播放请求，但放映厅页面已经关了") else cb(real)
    }

    /**
     * 收到观众侧的播放回执。
     *
     * 一条失败回执要让房主看见，还要告诉他下一步该干什么：S 档放不出来时，
     * 退路是「收厅改共享屏幕」，不是让他以为自己网络坏了。
     */
    private fun onCinemaAckFromViewer(f: String) {
        val ack = CinemaSync.parseAck(f) ?: return
        val st = _cinema.value
        if (!CinemaSync.isFreshAck(ack, st?.version)) {
            note("收到一条旧片源的回执（v${ack.version}），已忽略")
            return
        }
        _viewerPlayback.value = ack
        // 这行是给统计脚本读的，措辞可以改，前缀和字段顺序不能改
        Log.i(TAG, "CINEMA_ACK ${ack.version}|${if (ack.ok) "ok" else "fail"}|${ack.code}|${ack.detail}")
        note(
            when {
                ack.ok -> "对方已经播起来了（${ack.detail.ifBlank { "首帧已到" }}）"
                // App 里的观众不是"放不出来"，是这条路他没走 —— 别把他说成故障
                ack.code == "appviewer" -> "对方在用 App 看：走的是屏幕分享，不是本地播放"
                else -> "对方放不出这条：${ack.detail.ifBlank { ack.code }}。可以收厅改共享屏幕"
            },
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statsJob: Job? = null
    /** 观众信令断了之后的"最多等你这么久"兜底。 */
    private var viewerGoneJob: Job? = null
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
        /* 后来者要能立刻拿到当前状态。
         *
         * 原来只靠"放映厅那一屏每 2 秒重发一次"，可那条循环的前提是**探测得到房主的页面**：
         * 直接在 WebView 里打开一个 .m3u8 时，Chromium 用的是内置播放器，DOM 里没有 `<video>`，
         * 探针返回空 → 不重发 → 晚进厅的观众永远停在"对方还没选片"，
         * 而房主那边明明写着"正在放映"。实测就是这么翻的。
         * 所以在"观众连上"这一刻主动把当前状态推一次（synctv 也是这个做法：进场先推 current）。 */
        scope.launch {
            SignalHub.viewerConnected.collect { on ->
                if (on) {
                    // 新进来的观众什么都没报过，上一条回执再像是别人的结论
                    _viewerPlayback.value = null
                    broadcastCinema()
                    broadcastWatch()
                }
            }
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
            _inviteUrl.value = url
            _state.value = State.WaitingViewer(url)
        }
    }

    /**
     * 门牌链接单独存一份，**不跟着 [state] 走**。
     *
     * 原来它只挂在 `State.WaitingViewer` 上，于是观众一进来（状态变 Connecting）
     * 房主就再也复制不到邀请链接了 —— 而"上一个观众走了、想再发给别人"
     * 恰恰是最需要它的时刻；`backToWaiting()` 也因此拿不到链接，只能退回 Idle。
     * 放映厅那张卡的"复制邀请"就是被这个坑到的（实测：观众在厅里时那一行整个消失）。
     */
    private val _inviteUrl = MutableStateFlow<String?>(null)
    val inviteUrl: StateFlow<String?> = _inviteUrl.asStateFlow()

    private fun failAndStop(context: Context, reason: String) {
        note(reason)
        stop(context)
        _state.value = State.Failed(reason)
    }

    /** 回到"等人加入"那一步，邀请链接本身不变。 */
    private fun backToWaiting() {
        _state.value = _inviteUrl.value?.let { State.WaitingViewer(it) } ?: State.Idle
    }

    // ---- 观众消息处理（全部来自 SignalHub）------------------------------

    private fun onViewerMessage(text: String) {
        if (_role.value != Role.Host) return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (obj.optString("t")) {
            "hello" -> onViewerJoined()

            // 观众开麦克风时会主动发一轮 offer（谁改媒体谁发起）。
            // 这里的 acceptOffer 会顺着 createAnswer → onLocalDescription(Answer)
            // 把应答发回去，用的还是同一条传输，画面不会中断。
            "offer" -> {
                val sdp = obj.optString("sdp")
                if (sdp.isEmpty()) return
                note("观众要加麦克风，重新协商中")
                peer?.acceptOffer(sdp)
            }

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

            // 观众**主动**告别：立刻收，不用等。
            "bye" -> {
                note("观众已离开；链接仍然有效，让他再点一次即可")
                viewerGoneJob?.cancel(); viewerGoneJob = null
                teardownPeer()
                backToWaiting()
            }

            // 信令通道无声无息地关了：可能是隧道掐线、也可能是观众被系统回收。
            // 媒体是 P2P 的，信令断了画面照样能传 —— 先看 ICE 还健康吗：
            // 健康就保留通话等对方 rescueSignaling 回来；不健康才真收场。
            // 但**必须有兜底时限**：观众若是被直接杀掉（来不及发 bye），
            // 不能让它留下的 peer 永远挂着。
            "gone" -> {
                val live = peer
                if (live != null &&
                    live.connectionState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED
                ) {
                    note("观众信令断了，画面先不断，等他重连")
                    viewerGoneJob?.cancel()
                    viewerGoneJob = scope.launch {
                        delay(VIEWER_RECONNECT_GRACE_MS)
                        viewerGoneJob = null
                        if (!SignalHub.viewerConnected.value) {
                            note("观众没回来，结束这一次直连")
                            teardownPeer()
                            backToWaiting()
                        }
                    }
                } else {
                    note("观众已离开；链接仍然有效，让他再点一次即可")
                    viewerGoneJob?.cancel(); viewerGoneJob = null
                    teardownPeer()
                    backToWaiting()
                }
            }

            // 同看：对方想快进/暂停。权限判定放在**收消息这一侧**，
            // 不给 UI 留"忘了判"的机会 —— 收回开关后就算 UI 那屏还在，指令也进不去。
            "wcmd" -> {
                val cmd = WatchSync.accept(
                    _viewerMayControl.value,
                    WatchSync.parseCmd(obj.optString("f")),
                ) ?: return
                val cb = onWatchCommand
                if (cb == null) {
                    note("收到播放指令，但同看页面已经关了")
                } else {
                    note("对方在控制播放：${cmd.label()}")
                    cb(cmd)
                }
            }

            // 放映厅（S 档）：观众那边按了暂停/±10 秒
            "ccmd" -> onCinemaCommandFromViewer(obj.optString("f"))

            // 放映厅（S 档）：观众那边到底播出来了没有
            "cineack" -> onCinemaAckFromViewer(obj.optString("f"))
        }
    }

    private fun onViewerJoined() {
        if (_role.value != Role.Host) return
        val context = sessionContext ?: return

        // 信令重连：观众那边只是经隧道的 WS 断了，P2P 通道还活着。
        // 这里若无条件重建 peer，等于把一通正在放画面的通话拆掉重来 —— 观众会看到
        // 画面黑一下甚至直接失败。所以先问一句"手上这条还健康吗"，健康就只把新 socket 接上
        // （SignalHub 会把 viewer 换成新连接，消息通道立刻恢复）。
        val live = peer
        if (live != null &&
            live.connectionState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED
        ) {
            note("观众信令重连，画面不断")
            viewerGoneJob?.cancel(); viewerGoneJob = null
            _state.value = State.Connected
            return
        }

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
        // 第一件事必须是道别，而且必须在拆 peer / 拆隧道 / 拆传话员之前做完：
        // SignalHub.stop() 会清掉待发队列，TunnelManager.stop() 直接杀 cloudflared 进程，
        // 任何一件先发生，这句"结束"就永远留在队列里。不发出去的后果不是"少一句提示"，
        // 而是观众只能从"媒体突然没了"反推，于是把一次正常结束报成"连不上/对称 NAT"，
        // 还附三条换网络建议（用户照做就是白折腾）。
        // 这条路径同时也是系统撤销投屏（锁屏、下拉里点停止）的出口 ——
        // ScreenShareController.onStoppedBySystem 最终也调这里，所以那一类也会被通知到。
        SignalHub.sayGoodbye()
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
        // 隧道随进程停了，这条链接也就失效了 —— 留着会让界面继续显示一个打不开的门牌
        _inviteUrl.value = null
        _cinema.value = null
        _viewerPlayback.value = null
        _watch.value = null
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

                    PeerConnection.IceConnectionState.FAILED -> {
                        // 不再凭"失败了"就断言是对称 NAT —— 让 probe 按双方候选给结论。
                        val v = peer?.probe?.verdict()
                        note("ICE 判定：${v?.headline}｜${peer?.probe?.summary()}")
                        State.Failed(v?.headline ?: "直连失败", v)
                    }

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
                // 自愈超时这类"先连上后失败"的分支同样给证据：
                // 判定会把轨迹里的 CONNECTED 挑出来，界面就不会误报成"打洞没成功"。
                _state.value = State.Failed(reason, peer?.probe?.verdict())
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
