package com.ticketfortwo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.ticketfortwo.app.capture.ScreenShareController
import com.ticketfortwo.app.PlayMode
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

    enum class Role { Host }

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

    /** 默认关麦（2026-10-01 计划）：进厅不传环境音，想说在放映厅点麦克风按钮。 */
    private val _micMuted = MutableStateFlow(true)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    /**
     * 麦克风**实际**开没开。和 [micMuted] 分开是必要的：声音档是「只有视频声」而对方
     * 已经在本地播原声时，麦克风会被我们主动关掉（[VoiceMode.hostMicNeeded]），
     * 但用户并没有按静音。界面如果只看 micMuted，就会在麦克风其实闭着的时候画一个
     * "说话中"的图标，而对方那边一点声音都没有。
     */
    private val _micLive = MutableStateFlow(false)
    val micLive: StateFlow<Boolean> = _micLive.asStateFlow()

    /** 这一场房主听不听得到对方说话（「只有视频声」= 听不到）。给 UI 说实话用。 */
    private val _hostHearsViewer = MutableStateFlow(true)
    val hostHearsViewer: StateFlow<Boolean> = _hostHearsViewer.asStateFlow()

    /** 当前这一端在分享里的角色。放这里而不是 Activity 里：Activity 会被系统回收，
     *  分享不会 —— 重建后 UI 要能从会话本身恢复出正确的分支。 */
    private val _role = MutableStateFlow<Role?>(null)
    val role: StateFlow<Role?> = _role.asStateFlow()

    private val _netStats = MutableStateFlow<Peer.NetStats?>(null)
    val netStats: StateFlow<Peer.NetStats?> = _netStats.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    // ---- 同看（同屏放映）---------------------------------------------------
    //
    // 房主端的旧"同屏放映"界面（WatchTogetherScreen）已下线（2026-10-04，功能被
    // 放映厅覆盖），但**协议保留**：watch 状态广播与指令通道还在 —— 对方设备若还
    // 装着旧版 App 开同看，新版的 CallScreen 仍能显示镜像条（WatchMirrorBar）。
    // 会话这边只做两件事：把播放器状态广播给观众、把观众发来的指令交给那一屏。
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
        /* 放映条的权限位也要**立刻**到：现在它只搭下一条进度广播的车，而房主
           探不到 <video> 时那条广播根本不发 —— 权限收回可能永远到不了观众端，
           他那边三颗键还是"可按"的样子（审查 P1）。 */
        broadcastCinema()
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
            /* **诊断日志（2026-10-01）**：投屏连接/断开时 cinema 被无日志清空
               （state 还是 WaitingViewer = 不是 stop 也不是 UI 收厅）—— 打调用栈
               直接看调用者。定位后可删。 */
            android.util.Log.i(
                TAG,
                "CINEMA CLEAR 调用来源：" +
                    Throwable().stackTrace.take(6).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName },
            )
            _cinema.value = null
            _viewerPlayback.value = null
            broadcastCinema()
            applyVoicePolicy("收厅")
            // B 方案：收厅后自播会停（帧桥断），转播轨跟着释放 ——
            // 本地视频轨一并清掉，免得屏幕分享那边误判"画面已经在传"。
            runCatching { theaterCapture?.release() }
            theaterCapture = null
            if (_localVideo.value != null && capture == null) {
                runCatching { localVideoTrack?.dispose() }
                localVideoTrack = null
                _localVideo.value = null
            }
            note("已收厅，回到整屏分享")
            return
        }
        cinemaVersion++
        // 换了一条片，上一条的回执就不该继续显示在卡片上（否则"对方放不了"会跟着人走）
        _viewerPlayback.value = null
        // 换片之后要按声音档重判一次麦克风：上一条对方是本地播（麦已关），这一条
        // 可能换成一条他放不出来的地址 —— 那时观众只能靠房主外放灌麦听声，麦必须开回来。
        applyVoicePolicy("递片")
        // 换片和首次递出去要在日志里分得开，否则回看时看不出中途换过片
        val wasScreening = _cinema.value != null
        // B 方案触发：**必须在构造 State 之前**建轨 —— 首发这一帧就要把地址藏住，
        // 否则观众先收到地址（自己播起来）、轨随后才到 = 双画面。
        if (quality.playMode == PlayMode.Relayed && theaterCapture == null && capture == null) {
            sessionContext?.let { attachTheaterPlayback(it, quality.fps) }
        }
        /* B 方案（我播他看）：转播轨活着时**地址不下发** —— 观众端拿到空地址就不
           自己播（网页/App 都判空），画面全靠那条视频轨；直连模式照常发地址。
           轨没建成（theaterCapture==null，如失败回退）时照发地址，自动退回直连。 */
        val relayed = quality.playMode == PlayMode.Relayed && theaterCapture != null
        _cinema.value = CinemaSync.State(
            track = if (relayed) track.copy(url = "") else track,
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

    /**
     * 放映厅那一屏每轮询到一次播放器状态就调它。
     *
     * [rate] 是房主当前倍速（手势长按/上滑设的）——协议字段本来就有，
     * 观众端拿它既投影时间轴、也把本地播放器拨到同速（同速才能同帧）。
     */
    fun publishCinemaProgress(posMs: Long, durMs: Long, playing: Boolean, rate: Double = 1.0) {
        val cur = _cinema.value ?: return
        _cinema.value = cur.copy(
            posMs = posMs,
            durMs = durMs,
            playing = playing,
            rate = rate,
            hostWallMs = System.currentTimeMillis(),
        )
        broadcastCinema()
    }

    private fun broadcastCinema() {
        if (!SignalHub.viewerConnected.value) return

        val st = _cinema.value
        Log.i(TAG, "BROADCAST pos=" + (st?.posMs ?: -1) + " dur=" + (st?.durMs ?: -1) +
            " urlEmpty=" + (st?.track?.url?.isEmpty() ?: true))
        try {
            val json = JSONObject().apply {
                put("t", "cinema")
                put("f", st?.let { CinemaSync.fields(it, _viewerMayControl.value) } ?: "")
            }
            val sent = SignalHub.sendToViewer(json.toString())
            Log.i(TAG, "BROADCAST sent=" + sent + " len=" + json.toString().length)
        } catch (t: Throwable) {
            Log.e(TAG, "BROADCAST 组装/发送失败", t)
        }
    }

    /** 收到观众的放映请求。权限判定放在收消息这一侧，不给 UI 留"忘了判"的机会。 */
    private fun onCinemaCommandFromViewer(f: String) {
        val st = _cinema.value
        val cmd = CinemaSync.accept(_viewerMayControl.value, CinemaSync.parseCmd(f)) ?: return
        if (st == null) {
            note("收到播放请求，但厅里还没选片")
            return
        }
        // 不在这里换算绝对落点：st.posMs 是 2 秒前的广播快照，按它算会让连点两次
        // +10 第二次落在同一处（REVIEW-2026-09-27 P2）。Step 原样传下去，
        // 由注入的 JS 在页面里做相对位移 —— 权威的 currentTime 只在页面里。
        // 也不做"回声抑制"：指令只有 viewer→host 一个方向、WS 有序可靠，
        // 800ms 窗口吞掉的只会是用户手快的第二次真按键，日志却写着"先不重复处理"
        // （原来是只改文案的空壳）。协议入口 cmdFields 已钳过 |delta| ≤ MAX_STEP_MS。
        note("对方在控制放映：${cmd.label()}")
        val cb = onCinemaCommand
        if (cb == null) note("收到播放请求，但放映厅页面已经关了") else cb(cmd)
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
        // 对方"能不能自己出声"直接决定房主要不要开着麦克风（只有视频声 + 他本地播 = 关掉），
        // 所以这条回执是声音档的一个输入，不能只拿去画界面。
        applyVoicePolicy("回执 ${if (ack.ok) "ok" else "fail"}")
        // 这行是给统计脚本读的，措辞可以改，前缀和字段顺序不能改
        Log.i(TAG, "CINEMA_ACK ${ack.version}|${if (ack.ok) "ok" else "fail"}|${ack.code}|${ack.detail}")
        /* 网页观众回"放不出"（2026-10-04）：直连模式下片源常带防盗链（Referer/UA），
           观众浏览器自己拉流 403 —— 和 App 内观众播不了是同一堵墙。自动切转播
           （与 needrelay 同一条路）：房主"我播他看"，观众不再需要能访问片源。
           护栏在 onViewerNeedRelay 里（已在传画面/没有片源时直接返回）。 */
        if (!ack.ok && ack.code != "appviewer" && ack.code != "gesture") {
            Log.i(TAG, "网页观众放不出直连片源（${ack.code}）—— 自动切换转播")
            onViewerNeedRelay()
        }
        note(
            when {
                ack.ok -> "对方已经播起来了（${ack.detail.ifBlank { "首帧已到" }}）"
                // App 里的观众不是"放不出来"，是这条路他没走 —— 别把他说成故障
                ack.code == "appviewer" -> "对方在用 App 看：走的是屏幕分享，不是本地播放"
                // 手机浏览器不许网页自己放出声音：这属于"等他点一下"，不是失败
                ack.code == "gesture" -> "对方浏览器要先点一下才开始播（手机上很常见，等他点）"
                else -> "对方放不出这条：${ack.detail.ifBlank { ack.code }}。可以收厅改共享屏幕"
            },
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statsJob: Job? = null
    /** 观众信令断了之后的"最多等你这么久"兜底。 */
    private var viewerGoneJob: Job? = null

    /**
     * 当前观众的会话 id（hello.sid，每次开页面/开 App 各生成一次）。
     * 只有**同 sid** 的 hello 才允许走"信令重连、画面不断"捷径；换了 sid
     * （页面刷新、同链接被第二台设备打开）必须拆掉旧 peer 重新发 offer ——
     * 否则新页面永远等不到 offer 卡在等待，而旧 peer 还"健康"着
     * （REVIEW-2026-09-27 P1：互踢循环与刷新卡死同根）。
     */
    private var viewerSid: String? = null

    /**
     * startHost 的启动协程（信令 → 隧道 → 出链接那几秒）。
     * 不存 Job 的后果：用户在"申请临时地址"这几秒里点了停止，stop() 完成后
     * 旧协程照常醒来，把 Idle 改成 WaitingViewer（链接其实是死的）或误报失败
     * （REVIEW-2026-09-27 P1）。stop() 第一时间取消它。
     */
    private var hostJob: Job? = null
    private var peer: Peer? = null
    /**
     * 这条 PeerConnection **有没有真的连上过**。
     *
     * 用来挡住一类假状态：中途加视频轨会重新协商，libwebrtc 随之再报一次 CHECKING，
     * 而传输本身从没断过 ⇒ 不会再有一次 CONNECTED 回调。原来 CHECKING 一律画成
     * "正在建立直连"，于是房主接上画面之后永远卡在转圈那一屏（实测 17:30：观众均值 39.4、
     * 画面早就到了，房主却写着"逐对尝试候选地址"）。
     */
    private var iceWasConnected = false
    private var capture: ScreenShareController? = null
    /** B 方案「我播他看」的转播轨控制器（与屏幕分享互斥共用 localVideoTrack）。 */
    private var theaterCapture: com.ticketfortwo.app.capture.PlayerShareController? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSender: RtpSender? = null

    /** 采集出来的本地视频轨 —— 观众加入时才 addTrack，所以要先留着。 */
    private var localVideoTrack: VideoTrack? = null
    private var quality: ShareQuality = ShareQuality()

    /**
     * 用户这一场明确按过"要说话"。为 true 时声音档不再自动关麦克风
     * （自动关是为了防回声，不是禁令；他要说话就得让他说）。
     */
    private var micOverride = false
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
        /* 门牌（隧道）中途断了必须有人听：pump 的 finally 会写
           `State.Failed("门牌失效…")`，但全仓没有 collect —— 房主界面毫无反应，
           发出去的链接悄悄 502（REVIEW-2026-09-27 P1）。只认 **Ready→Failed**：
           启动阶段的 Failed 由 startHost 按返回值自己处理（failAndStop），
           这里再插手会双写同一场失败。 */
        scope.launch {
            var wasReady = false
            TunnelManager.state.collect { st ->
                when (st) {
                    is TunnelManager.State.Starting -> wasReady = false
                    is TunnelManager.State.Ready -> wasReady = true
                    is TunnelManager.State.Failed -> {
                        if (wasReady) {
                            wasReady = false
                            val live = _role.value == Role.Host &&
                                _state.value !is State.Idle &&
                                _state.value !is State.Failed
                            if (live) sessionContext?.let { failAndStop(it, st.reason) }
                        }
                    }
                    TunnelManager.State.Idle -> Unit
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
            // **不能一律丢掉**。厅先开（只起信令 + 语音）之后再按「分享屏幕」是 v2.1 的主路径：
            // 原来这道闸门把刚拿到的投屏授权一起扔了 —— 实测（t2test 17:18）观众已在厅里、
            // 房主按「让他看我的屏幕」、系统授权返回 resultCode=-1，日志却是
            // "已有进行中的分享，忽略本次请求"，于是观众那边永远只有语音。
            if (permissionIntent != null && _role.value == Role.Host) {
                attachScreenCapture(context, permissionIntent, quality)
            } else {
                note("已有进行中的会话，忽略本次请求")
            }
            return
        }
        RtcEngine.init(context)
        _role.value = Role.Host
        this.quality = quality
        this.sessionContext = context.applicationContext
        // 麦克风默认关（2026-10-01 计划）；「只连麦」档的全部意义就是说话 —— 进厅即开
        _micMuted.value = quality.voiceMode != VoiceMode.CallOnly
        _state.value = State.Preparing(
            if (permissionIntent != null) "正在启动屏幕采集" else "正在准备语音通话"
        )

        val cap = if (permissionIntent != null) {
            ScreenShareController(context.applicationContext, permissionIntent).also {
                /* 系统中途收回授权（锁屏、通知栏点停止）：与 attachScreenCapture
                   **同一条策略** —— 厅还在、语音还在，把整通电话拆掉比退回
                   "只有语音"严重得多。原来初次路径直接 stop(整个会话)、厅后接
                   路径只停画面，同一个系统动作两种结局（REVIEW-2026-09-27 P2），
                   现已统一为"只停画面、会话继续"。 */
                it.onStoppedBySystem = { reason ->
                    note("系统停止画面：$reason（厅里继续语音）")
                    runCatching { it.stopCapture() }
                    /* 共享引用只在"停的就是当前这一路"时清 —— 旧控制器的回调
                       晚到会把**新轨**的引用踩掉（审查 A1-3 附带项）。 */
                    if (capture === it) {
                        capture = null
                        localVideoTrack = null
                        _localVideo.value = null
                    }
                }
                capture = it
            }
        } else null

        val vt = cap?.start(fps = quality.fps, scale = quality.scale)
        localVideoTrack = vt
        _localVideo.value = vt
        if (vt != null) ensureMicForSoundRelay("屏幕分享")
        val at = ensureMicTrack(context)
        if (vt == null && at == null) {
            // 一条媒体都没有：连上也没有任何可传的东西 —— 直接失败好过转圈。
            stop(context)
            _state.value = State.Failed("没有麦克风权限，语音模式无法开始；请授予麦克风权限后重试")
            return
        }
        // 只连麦这一档不该去要投屏授权；反过来说，没拿到授权 Intent 才一定是用户选的档位
        // （采集失败是另一回事，ScreenShareController 自己会报）。
        if (permissionIntent == null) note("只连麦：不采集画面")

        // 传话员与门牌都不需要用户操作，但都不是瞬间完成，所以状态机要把这几秒画出来。
        hostJob = scope.launch {
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
     * B 方案「我播他看」：给自播画面建一条视频轨并推给观众（2026-09-30 用户拍板）。
     *
     * 与 [attachScreenCapture] 同构，差别只有采集源：屏幕 → PlayerShareController
     * （自播播放器的帧桥）。与屏幕分享**互斥**共用 localVideoTrack —— 已有轨就不重建。
     */
    fun attachTheaterPlayback(context: Context, fps: Int) {
        if (localVideoTrack != null) {
            note("画面已经在传了，不重复接")
            return
        }
        RtcEngine.init(context)
        val cap = com.ticketfortwo.app.capture.PlayerShareController(context.applicationContext).also {
            theaterCapture = it
        }
        val vt = cap.start(fps = fps)
        if (vt == null) {
            note("自播画面没能接上转播轨 —— 本场退回发地址（对方自己播）")
            return
        }
        localVideoTrack = vt
        _localVideo.value = vt
        // B 档观众拿不到地址、也没有音频轨 —— 房主的麦克风是他唯一的声源
        ensureMicForSoundRelay("我播他看")
        val p = peer
        if (p == null) {
            note("转播轨已备好，等对方进厅就发过去")
            return
        }
        val senders = runCatching { p.addLocalTracks(vt, null) }.getOrNull()
        if (senders?.video == null) {
            note("转播轨接不进这条连接 —— 让对方重新点一次链接")
            return
        }
        videoSender = senders.video
        applyVideoBitrateCap(quality.maxVideoBps)
        p.startOffer()
        if (p.connectionState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED) {
            _state.value = State.Connected
        }
        note("画面已接上，正在转给对方（他不用挂代理）")
    }

    /**
     * 会话已经活着（厅先开 / 观众已进厅）时，把**刚授权的屏幕采集**接上去并重新协商。
     *
     * 走"加轨 + 再发一次 offer"而不是重建 peer：重建等于把一通正在连麦的通话拆掉重来，
     * 观众会黑屏一下甚至直接失败（`onViewerJoined` 里为信令重连写过同一条理由）。
     * 观众侧的 `"offer"` 分支不挑次数，收到就 setRemote + 回 answer，所以这条能走通。
     */
    private fun attachScreenCapture(context: Context, intent: Intent, q: ShareQuality) {
        if (localVideoTrack != null) {
            note("画面已经在传了，不重复接")
            return
        }
        RtcEngine.init(context)
        quality = q
        val cap = ScreenShareController(context.applicationContext, intent).also { c ->
            // 系统中途收回授权（锁屏、用户在通知栏点停止）：这里**不能** stop(整个会话) ——
            // 厅还在、语音还在，把整通电话拆掉比退回"只有语音"严重得多。
            c.onStoppedBySystem = { reason ->
                note("系统停止画面：$reason（厅里继续语音）")
                runCatching { c.stopCapture() }
                // 同上：晚到的旧回调不许踩掉新轨的引用（审查 A1-3 附带项）
                if (capture === c) {
                    capture = null
                    localVideoTrack = null
                    _localVideo.value = null
                }
            }
            capture = c
        }
        val vt = cap.start(fps = q.fps, scale = q.scale)
        if (vt == null) {
            note("屏幕采集没起来，观众那边还是只有语音")
            return
        }
        localVideoTrack = vt
        _localVideo.value = vt
        ensureMicForSoundRelay("屏幕分享")
        val p = peer
        if (p == null) {
            // 观众还没进来：轨先备着，等人进来时 onViewerJoined 会把它加进 offer。
            note("画面已备好，等对方进厅就发过去")
            return
        }
        // **只加视频轨**：音频轨在观众进厅那次已经 addTrack 过了，再交一遍给同一条
        // PeerConnection 会抛 `IllegalStateException: C++ addTrack failed`
        // （实测 17:27 直接把 App 崩在主线程上）。包一层 runCatching：这条路径失败
        // 不该让整通电话没了，说清楚就好。
        val senders = runCatching { p.addLocalTracks(vt, null) }.getOrNull()
        if (senders?.video == null) {
            note("画面采到了，但接不进这条连接 —— 让对方重新点一次链接就能看到")
            return
        }
        videoSender = senders.video
        applyVideoBitrateCap(q.maxVideoBps)
        p.startOffer()
        // 这条连接本来就是通的（只是重新协商），所以状态立刻摆回"已连上"：
        // 等回调是等不到的，房主会一直看到"正在建立直连"。
        if (p.connectionState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED) {
            _state.value = State.Connected
        }
        note("画面已接上，正在推给对方")
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
            "hello" -> {
                // 观众那边的"能不能放"三要素。它只进日志不进界面：
                // 现场出问题时，这一行是唯一能区分"流坏了"和"对方内核没播放器"的证据。
                val env = obj.optString("env")
                if (env.isNotEmpty()) Log.i(TAG, "VIEWER_ENV $env")
                onViewerJoined(obj.optString("sid").ifEmpty { null })
            }

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
                    // 只有**第一次**握手才需要画"正在建立直连"。中途加视频轨（房主先开厅、
                    // 之后才按分享）也会走到这一行，而那时传输一直是通的、libwebrtc 不会再报
                    // 一次 CONNECTED —— 于是房主永远停在转圈屏（实测 17:38：观众均值 39.4
                    // 画面早就到了，房主还写着"逐对尝试候选地址"）。
                    if (!iceWasConnected) _state.value = State.Connecting
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

            // App 内观众报告"这条片源我播不了"（App 没有 hls.js，直连模式发给它的
            // URL 放不出来）→ 当场自动切「我播他看」，别让两边对着黑屏互相等。
            "needrelay" -> onViewerNeedRelay()
        }
    }

    /**
     * 观众（App 内观看）请求转播。直连模式 + App 内观众是一个产品逻辑洞：
     * 房主把片源地址发过去，观众端根本没有本地播放器，永远黑屏"语音对话中"。
     * 收到这条就本场切换 playMode（**只改内存，不动用户设置**）→ 建转播轨
     * （[attachTheaterPlayback] 内部会处理"观众已在连"的重协商）→ 把 cinema
     * 状态的地址清空再广播一次（观众端拿到空地址就知道等轨、不再报放不了）。
     */
    private fun onViewerNeedRelay() {
        if (_role.value != Role.Host) return
        if (theaterCapture != null || capture != null) return   // 画面已经在传
        if (_cinema.value == null) return                        // 厅里还没片
        val context = sessionContext ?: return
        note("对方是 App 内观看，播不了直连片源 —— 自动切换为「我播他看」")
        quality = quality.copy(playMode = PlayMode.Relayed)
        attachTheaterPlayback(context, quality.fps)
        if (theaterCapture != null) {
            val cur = _cinema.value ?: return
            _cinema.value = cur.copy(track = cur.track.copy(url = ""))
            broadcastCinema()
        }
    }

    private fun onViewerJoined(sid: String?) {
        if (_role.value != Role.Host) return
        val context = sessionContext ?: return

        // 同一条会话的信令重连（hello 带同一个 sid）才走"画面不断"捷径。
        // 换 sid（观众刷新页面/第二台设备）必须拆掉重建重发 offer：旧 peer
        // "健康"恰恰是陷阱，新页面拿不到 offer 会永远停在等待（REVIEW-2026-09-27 P1）。
        // sid 缺失（旧版网页）一律按新会话处理 —— 多做一次 offer 无害，卡死才要命。
        val sameSession = sid != null && sid == viewerSid
        viewerSid = sid ?: viewerSid
        val live = peer
        if (live != null && sameSession &&
            live.connectionState == org.webrtc.PeerConnection.IceConnectionState.CONNECTED
        ) {
            note("观众信令重连，画面不断")
            viewerGoneJob?.cancel(); viewerGoneJob = null
            _state.value = State.Connected
            return
        }

        note("观众已加入，开始建立直连")
        iceWasConnected = false
        _state.value = State.Connecting
        // 这一条路**不需要**在这里补发放映状态：init 里那个 `viewerConnected.collect { on ->
        // if (on) … }` 已经在"观众连上"这一刻推过 current 了（22ab325 修的就是晚进厅）。
        // 上一版在这里也加了一次，理由是"实测观众 30 秒拿不到状态" —— 那个实测是假的：
        // 脚本读的是 CDP 的第一个 page target，而 Edge 会插一个
        // edge://sync-confirmation-dialog/ 的同步推广页排在前面，量的一直是那个弹窗。
        // 判据修对之后（认 URL 不认位置）这条本来就好的路一次都没再失败过。

        teardownPeer()
        val p = newPeer(context, Peer.Role.Offerer)

        val senders = p.addLocalTracks(localVideoTrack, audioTrack)
        videoSender = senders.video
        if (senders.video != null) {
            applyVideoBitrateCap(quality.maxVideoBps)
        } else {
            note("只连麦：不发送视频")
        }
        p.startOffer()
    }

    // ---- 公共 ----------------------------------------------------------

    /**
     * **画面声音靠外放→麦克风**的两种模式（屏幕分享 / B 方案「我播他看」）：
     * 麦关着 = 对方只看没声，所以这两种模式一激活就自动开麦 + 说明；
     * 用户之后仍可手动关（关时 toggleMic 会给"对方会听不到画面声"的警告，不硬拦）。
     */
    private fun ensureMicForSoundRelay(what: String) {
        if (_micMuted.value) {
            _micMuted.value = false
            note("${what}的声音靠你的麦克风传 —— 已为你开麦；点麦克风可关（对方会听不到画面声）")
        }
        applyVoicePolicy(what)
    }

    fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
        applyVoicePolicy("手动静音")
    }

    /**
     * 界面上那颗麦克风按钮的唯一入口。
     *
     * 翻的是**实际开没开**（[micLive]），不是 `micMuted` 这个布尔。区别在「只有视频声」
     * 这一档：那时麦克风可能已经被声音档关掉了，而 `micMuted` 仍是 false —— 按钮若照旧写
     * `setMicMuted(!micMuted)`，点下去只是把 false 变成 true，图标翻了、状态没变，
     * 正是观众侧修过的同一个"图标骗人"毛病。
     *
     * 用户明确要开麦时给它一个覆盖位：这一场不再自动关（代价是对方那边会和自己的
     * 原声叠成两份，所以提示里要说清楚，别让人以为开了没生效）。
     */
    fun toggleMic() {
        micOverride = !_micLive.value
        /* 用"当前实际开着"置位，**不能**写 `!_micLive.value`（原实现）：
           代入 applyVoicePolicy 的 `micOn = (override || needed) && !muted` 是个不动点 ——
           默认档点静音写进去的还是 false（关不掉），被声音档自动关麦后点开麦反而写死 true
           （永远开不了，还照样 note"已开麦"）。用户报的就是这个（REVIEW-2026-09-27 P1）。 */
        _micMuted.value = _micLive.value
        applyVoicePolicy("用户切麦")
        /* 提示按场景分（2026-10-01 计划）：画面声音类模式先说"它在传画面声"；
           放映中（观众本地播）开麦要提示回声；其余给轻量确认。 */
        val soundRelay = capture != null || theaterCapture != null
        val viewerLocal = _cinema.value != null && _viewerPlayback.value?.ok == true
        if (_micLive.value) {
            when {
                soundRelay -> note("已开麦 —— 画面的声音也靠它传给对方")
                viewerLocal ->
                    note("已开麦。对方在本地播原声，你出声会和他那份叠在一起 —— 说完点一下关掉")
                else -> note("已开麦")
            }
        } else {
            if (soundRelay) note("已关麦 —— 对方会听不到画面的声音（不只是你的话）")
            else note("已关麦")
        }
    }

    /**
     * 麦克风到底开不开、房主听不听得到对方 —— 声音档唯一落地的地方。
     *
     * 三处会调它：会话起/加轨、收到观众的放映回执、用户点静音。之所以集中成一个函数，
     * 是因为这三个地方都想"顺手 setEnabled 一下"，而条件有两个（用户静音 + 声音档），
     * 分散着写一定会出现"某条路径把该关的开着"。
     */
    private fun applyVoicePolicy(reason: String) {
        val mode = quality.voiceMode
        // "对方在本地播"必须是**这条厅的回执**说的 ok，不能只看有没有回执：
        // 收厅之后回执清空，此时麦克风要按 B 档（外放灌麦）的规则重新开回来。
        val viewerLocal = _cinema.value != null && _viewerPlayback.value?.ok == true
        val micOn = (micOverride || VoiceMode.hostMicNeeded(mode, viewerLocal)) && !_micMuted.value
        audioTrack?.setEnabled(micOn)
        _micLive.value = micOn
        val hears = VoiceMode.hostHearsViewer(mode)
        RtcEngine.setDownlinkMuted(!hears)
        _hostHearsViewer.value = hears
        Log.i(TAG, "声音档[$reason] ${mode.name} 对方本地播=$viewerLocal 手动静音=${_micMuted.value} " +
            "覆盖=$micOverride → 麦克风=$micOn 房主听对方=$hears")
    }

    fun stop(context: Context) {
        /* **诊断日志（2026-10-01）**：投屏连接/断开时会话被静默停掉（state=Idle 但
           全程无停止日志）—— 打调用栈直接看是谁在调（ScreenShareController 撤销 /
           ShareService ACTION_STOP / UI 收厅……）。定位后可删。 */
        android.util.Log.i(
            TAG,
            "SESSION STOP 调用来源：" +
                Throwable().stackTrace.take(6).joinToString(" <- ") { it.className.substringAfterLast('.') + "." + it.methodName },
        )
        // 第一件事必须是道别，而且必须在拆 peer / 拆隧道 / 拆传话员之前做完：
        // SignalHub.stop() 会清掉待发队列，TunnelManager.stop() 直接杀 cloudflared 进程，
        // 任何一件先发生，这句"结束"就永远留在队列里。不发出去的后果不是"少一句提示"，
        // 而是观众只能从"媒体突然没了"反推，于是把一次正常结束报成"连不上/对称 NAT"，
        // 还附三条换网络建议（用户照做就是白折腾）。
        // 这条路径同时也是系统撤销投屏（锁屏、下拉里点停止）的出口 ——
        // ScreenShareController.onStoppedBySystem 最终也调这里，所以那一类也会被通知到。
        SignalHub.sayGoodbye()
        stopStatsPump()
        /* 先取消两个"会迟到"的协程，再拆会话：
           - hostJob：启动流程还没走完就停止，别让它醒来把 Idle 改成 WaitingViewer/Failed；
           - viewerGoneJob：20 秒宽限到期会 teardownPeer + backToWaiting，把新会话的状态踩掉
             （REVIEW-2026-09-27 P1/P2）。bye/gone 分支里已有取消，这里补的是 stop 这条路。 */
        hostJob?.cancel(); hostJob = null
        viewerGoneJob?.cancel(); viewerGoneJob = null
        teardownPeer()
        /* 自播播放器是"放映"这一整条线的源头，停会必须跟着停（2026-10-05）：
           它的停止原本只挂在放映厅页面里（页面在，才有人管）—— 人离场后
           关厅就没人收了，表现为"厅关了、人也回首页了，电影声音还在放"
           （用户实测）。这里是所有结束路径的汇聚点（主动收厅 / 失败 /
           被系统撤销），放在这里才不会漏某一条。回显浮窗同理：ticker 查
           isActive 会自己散场，但那要等半拍 —— 直接收掉更干脆。 */
        com.ticketfortwo.app.cinema.TheaterPlayer.stop()
        val fs = com.ticketfortwo.app.cinema.FloatPlayer.state.value
        if (fs.active || fs.prewarm || fs.relay) {
            com.ticketfortwo.app.cinema.FloatPlayer.stop(context)
        }
        runCatching { capture?.release() }
        capture = null
        runCatching { theaterCapture?.release() }
        theaterCapture = null
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
        _micMuted.value = true          // 下一场默认关麦（启动时再按档校正，只连麦例外）
        _micLive.value = false
        _hostHearsViewer.value = true
        micOverride = false
        // 下行静音是**设备级**的开关（整个进程共用一个音频设备模块）：这一场为了"只有视频声"
        // 把它关了，下一场如果没人复位，同一台机器上改当观众时就听不见对方 —— 而且现象
        // 看起来像"他的麦克风坏了"。所以停会时第一件事就是把它交还。
        RtcEngine.setDownlinkMuted(false)
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
        viewerSid = null
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
                        iceWasConnected = true
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

                    // 已经连上过 ⇒ 这次 CHECKING 只是重新协商的回查，别把会话画回"正在建立直连"
                    PeerConnection.IceConnectionState.CHECKING ->
                        if (iceWasConnected) _state.value else State.Connecting
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
     * 麦克风轨。约束由 [RtcEngine.audioSourceConstraints] 给 —— 回声消除开在哪一档、
     * 为什么不能写空，理由全在那一处。
     *
     * 关键约束：**这条轨存在时**，addTrack 产生的 transceiver 是 sendrecv，
     * 双向才都有声音（开合只 setEnabled(false)，不移除轨道）。
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
        val src = RtcEngine.factory.createAudioSource(RtcEngine.audioSourceConstraints()) ?: return null
        val track = RtcEngine.factory.createAudioTrack("mic", src)
        audioSource = src
        audioTrack = track
        RtcEngine.logAudioProcessingState("host")
        applyVoicePolicy("建轨")
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

    /**
     * 设置页在分享进行中改档 —— 能热改的立刻生效，热不了的由调用方走授权链。
     *
     * 能热改的三样：声音档（applyVoicePolicy 重算麦克风/下行）、码率上限
     * （RtpSender.setParameters）、帧率/分辨率（changeCaptureFormat，转向监听同一条路）。
     * 它们都不动 m-line，不需要重新协商。
     * 唯独「带画面 ⇄ 只连麦」是结构变化：**开**画面必须拿新的投屏授权（Android 规则，
     * 复用旧 Intent 会抛 SecurityException），本地给不了 —— 返回 true 让 MainActivity
     * 去开授权指引；**关**画面走与系统收回授权同一条已验证的路径：只停画面，会话继续。
     *
     * @return true = 这次切到了"带画面"但本场还没有视频轨，需要走一次系统授权。
     */
    fun updateQuality(q: ShareQuality): Boolean {
        val old = quality
        quality = q
        if (_role.value != Role.Host || !isActive) return false
        if (q.voiceMode != old.voiceMode) applyVoicePolicy("设置变更")
        if (localVideoTrack != null) {
            if (q.maxVideoBps != old.maxVideoBps) applyVideoBitrateCap(q.maxVideoBps)
            if (q.fps != old.fps || q.scale != old.scale) capture?.applyQuality(q.scale, q.fps)
        }
        if (old.videoEnabled && !q.videoEnabled && localVideoTrack != null) {
            runCatching { capture?.stopCapture() }
            /* 与系统收回路径同款收尾（审查 A1-3）：
               不 release 的话转向监听 / SurfaceTexture 线程永远留在进程里，
               而 capture=null 让 stop() 里的 release 永远够不着；
               旧 sender 不从连接上摘掉，下次开画面 addTrack 会开出**第二条**视频 m-line。 */
            runCatching { capture?.release() }
            videoSender?.let { s -> peer?.removeLocalVideoSender(s) }
            videoSender = null
            capture = null
            localVideoTrack = null
            _localVideo.value = null
            note("已切到「只连麦」：画面已停，会话继续")
        }
        return q.videoEnabled && !old.videoEnabled && localVideoTrack == null
    }
}
