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
import com.ticketfortwo.app.watch.WatchState
import com.ticketfortwo.app.watch.WatchSync
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

/** 观众屏该往哪个方向摆。`Keep` 表示"别动，听系统/用户的"。 */
enum class OrientationTarget { Landscape, Portrait, Keep }

/**
 * 方向策略，纯函数（不碰 Android API，所以能在 JVM 上逐条测）。
 *
 * 为什么要它：实测过（t2test 分享 → t2view 收看），房主横屏后观众收到的帧确实变成
 * `1800x810`，但观众屏**不会跟着转** —— 清单里没有 screenOrientation 锁定，方向只跟随
 * 观众自己怎么拿手机，于是内容横、屏幕竖，画面缩成中间一条。观众的物理朝向我们管不着，
 * 所以想让两边一致，只能主动去改观众屏的方向，没有第二条路。
 *
 * `Keep` 这一档是刻意的：还没来帧、或者对方开的是"仅语音"时，根本没有"内容方向"这回事，
 * 这时候去锁方向就是无端抢用户的手机。
 */
internal fun decideOrientation(
    mode: OrientationMode,
    contentLandscape: Boolean?,
    hasVideo: Boolean,
): OrientationTarget = when (mode) {
    OrientationMode.Portrait -> OrientationTarget.Portrait
    OrientationMode.Landscape -> OrientationTarget.Landscape
    OrientationMode.Follow -> when {
        !hasVideo || contentLandscape == null -> OrientationTarget.Keep
        contentLandscape -> OrientationTarget.Landscape
        else -> OrientationTarget.Portrait
    }
}

/** 观众自己选的：跟随对方 / 一直竖着 / 一直横着。 */
enum class OrientationMode { Follow, Portrait, Landscape }

/**
 * 按钮的循环顺序。单独抽成纯函数是为了能测 —— 顺序写错，用户点一下就"按过头"
 * （比如从"跟随"直接跳到"横屏"，他会以为按钮坏了）。
 */
internal fun nextOrientationMode(m: OrientationMode): OrientationMode = when (m) {
    OrientationMode.Follow -> OrientationMode.Portrait
    OrientationMode.Portrait -> OrientationMode.Landscape
    OrientationMode.Landscape -> OrientationMode.Follow
}

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

        /**
         * 正常收场：房主说了再见，或者信令通道自己断了（十有八九是他停止了分享）。
         *
         * 单独立一档，而不是复用 [Failed]，因为这两件事用户该做的动作完全相反：
         * Failed 意味着"你们的网络对不上，去换网络/重试"；Ended 意味着"对方不播了，
         * 等他自己再开一次"。以前只有 Failed，于是停止分享被画成"连不上房主的手机 +
         * 三条换网络建议" —— 一个正常动作被报成了用户的网络有问题。
         */
        data class Ended(val reason: String) : State
    }

    private const val TAG = "ViewerSession"

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _micMuted = MutableStateFlow(true)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    /** 对方画面的宽高比是不是横的。null = 还不知道（首帧没到）。 */
    private val _contentLandscape = MutableStateFlow<Boolean?>(null)
    val contentLandscape: StateFlow<Boolean?> = _contentLandscape.asStateFlow()

    /** 最近一帧的真实尺寸。画中画要用它定比例，不能用屏幕比例瞎猜。 */
    private val _contentSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val contentSize: StateFlow<Pair<Int, Int>?> = _contentSize.asStateFlow()

    /** 观众选的方向模式，默认跟随对方。 */
    private val _orientationMode = MutableStateFlow(OrientationMode.Follow)
    val orientationMode: StateFlow<OrientationMode> = _orientationMode.asStateFlow()

    /**
     * 渲染层报上来的帧尺寸。[rot] 是 libwebrtc 的帧旋转标记，这里**故意不用它**：
     * 房主侧是我们自己在转向时重建采集面（见 ScreenShareController 的 DisplayListener），
     * 所以帧本来就是正过来的 1800x810，rot 恒为 0（实测）。真遇到 rot=90 的来源再说。
     */
    fun onContentResolution(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        _contentSize.value = w to h
        val land = w > h
        if (_contentLandscape.value != land) {
            Log.i(TAG, "对方画面方向：${if (land) "横" else "竖"}（$w x $h）")
            _contentLandscape.value = land
        }
    }

    /** 三态循环：跟随对方 → 锁竖 → 锁横 → 跟随对方。 */
    fun cycleOrientationMode(): OrientationMode {
        val next = nextOrientationMode(_orientationMode.value)
        _orientationMode.value = next
        Log.i(TAG, "观众方向模式 → $next")
        return next
    }

    fun setOrientationMode(mode: OrientationMode) { _orientationMode.value = mode }

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var ws: WsClient? = null
    private var peer: Peer? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    /**
     * 观众侧听到的音量。走 libwebrtc 的 `AudioTrack.setVolume`（本机 jar 里确认有这个方法），
     * 只改这一条流，不去动系统音量 —— 动系统音量会把用户正在听的音乐也一起改掉。
     */
    private val _volume = MutableStateFlow(1f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    fun setVolume(v: Float) {
        _volume.value = v.coerceIn(0f, 1f)
        runCatching { remoteAudioTrack?.setVolume(_volume.value.toDouble()) }
    }

    /**
     * 信令通道现在通不通。ICE 失败时要拿它分诊：
     * 通道还在 → 地址是真敲不通，报基于证据的失败；
     * 通道已经断了 → 媒体必然维持不住，那是"结束"而不是"连不上"，不该提对称 NAT。
     */
    @Volatile
    private var wsOpen = false

    /**
     * 这条会话有没有**曾经**连上过。收场分档全靠它：
     * 从没连上 → 是真的连不上（Failed）；连上过之后断 → 才可能是"对方结束了"（Ended）。
     * 上一版只看"当前是不是 Connecting"就判 Ended，结果隧道还没注册好时 Cloudflare 回
     * 530、握手被拒，也被写成"与房主的连接断了"——那是失败，不是结束，用户该重试而不是等人。
     */
    @Volatile
    private var everOpened = false

    /** 重连信令要用的三样东西：地址、上下文、"是不是已经在自救"。 */
    private var lastWsUrl: String? = null
    private var appContext: Context? = null

    /** 挡住 onClosed 的递归：我们自己关旧 socket 也会回调它。 */
    @Volatile
    private var reconnecting = false

    /** 信令重连的次数上限。房主真停了就不该无限重连下去。 */
    private val reconnectTries = 4

    /** 远端描述就绪前先攒着的候选（顺序错了 addIceCandidate 会抛）。 */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    val isActive: Boolean get() = ws != null || peer != null

    // ---- 生命周期 --------------------------------------------------------

    fun start(context: Context, inviteUrl: String) {
        stop()
        everOpened = false
        appContext = context.applicationContext
        RtcEngine.init(context)

        val wsUrl = wsUrlOf(inviteUrl)
        if (wsUrl == null) {
            fail("链接看不懂：需要形如 https://xxx/?k=凭证 的邀请链接")
            return
        }
        lastWsUrl = wsUrl

        _state.value = State.Connecting("正在连接房主的手机…")
        openSocket()
    }

    /**
     * 建一条信令通道；重连也走这里，所以回调里那套判断只有一份。
     *
     * `onOpen` 会重新发一次 hello —— 这正是房主那边"认回同一条通话"的信号，
     * 那边看到 peer 还健康就只是把新 socket 接上，不会重建通话。
     */
    private fun openSocket() {
        val url = lastWsUrl ?: return
        val client = WsClient(
            url = url,
            onOpen = {
                wsOpen = true
                everOpened = true
                note("通道已建立，正在向房主打招呼")
                send(JSONObject().apply { put("t", "hello"); put("role", "viewer") }.toString())
            },
            onText = { text ->
                val c = appContext
                if (c != null) scope.launch { onMessage(c, text) }
            },
            onClosed = { reason ->
                // 通道一没，wsOpen 立刻归零：之后 ICE 再失败就不该报"对称 NAT"了。
                wsOpen = false
                scope.launch {
                    when (_state.value) {
                        // 看到过画面 → 信令断了**不等于**通话该结束：媒体是 P2P 的，
                        // 跟这条经隧道走的 WS 没关系。先抢救（重连信令），救不回来才收场。
                        is State.Connected -> if (!reconnecting) rescueSignaling(reason)
                        // 曾经连上过但还没出画面 → 同样归到"结束"，别让人去查自己网络。
                        is State.Connecting ->
                            if (everOpened) end(reason ?: "连不上房主的手机（可能分享已结束）")
                            // 从没握上手：这是"连不上"，不是"结束了"。该给失败与重试，
                            // 而不是让人干等一个不会再来的房主。
                            else fail(reason ?: "连不上房主的手机（链接可能已过期，或对方已停止分享）")
                        is State.Ended, is State.Failed, State.Idle -> Unit
                    }
                }
            },
        )
        ws = client
        client.connect()
    }

    /**
     * 信令断了之后的抢救：画面还在动就别结束，退避着重连信令。
     *
     * 为什么值得做：Cloudflare quick tunnel 会回收它认为空闲的连接，
     * 实测连上后双方什么都不干，t=121s 两侧同时 `ice=CLOSED`（房主以为观众走了、
     * 观众以为房主停了），一通健康的 P2P 通话就这么被一条信令通道拖死。
     * 心跳（见 SignalHub）把空闲压掉了，但隧道偶发掉线仍然会来 —— 这里兜第二层。
     *
     * 三条边界：① 只在 ICE 还健康时重试，媒体已经断了就如实结束，不假装能救；
     * ② 有界（[RECONNECT_TRIES] 次），不做无限重连，否则房主真停了就一直挂着；
     * ③ 期间 `reconnecting` 挡住 onClosed 的递归 —— 我们自己关旧 socket 也会回调它。
     */
    private fun rescueSignaling(reason: String?) {
        val url = lastWsUrl
        if (!shouldRescue(everOpened, url != null, peer?.connectionState)) {
            end(reason ?: "与房主的连接断了")
            return
        }
        reconnecting = true
        scope.launch {
            for (i in 0 until reconnectTries) {
                if (!shouldRescue(everOpened, lastWsUrl != null, peer?.connectionState)) break
                delay(2_000)
                if (wsOpen) { reconnecting = false; note("信令自己回来了"); return@launch }
                note("信令断了，画面还在 —— 第 ${i + 1} 次重连…")
                runCatching { ws?.close() }
                openSocket()
                delay(2_500)
                if (wsOpen) {
                    reconnecting = false
                    note("信令已重连，画面继续")
                    return@launch
                }
            }
            reconnecting = false
            if (!wsOpen) end(reason ?: "与房主的信令断了")
        }
    }

    /**
     * 只给 debug 变体的调试入口用（见 `src/debug/.../DebugReceiver.kt`）：
     * 掐掉信令通道，媒体一概不碰 —— 用来验证 [rescueSignaling] 真的会去救。
     * 故意复用同一句 `ws.close()`，不另做一条"测试专用路径"。
     */
    fun debugDropSignaling() {
        runCatching { ws?.close() }
    }

    fun stop() {
        // 主动走之前先道别。不发这句的话，房主只能从"socket 关了"去猜，
        // 而它现在的策略是"信令断了先保留通话等重连"（见 CallSession 的 gone 分支），
        // 房主就会白等一整个宽限期。尽力而为：写不出去也无所谓，那边有兜底时限。
        if (wsOpen) runCatching { send("""{"t":"bye"}""") }
        runCatching { ws?.close() }
        ws = null
        wsOpen = false
        everOpened = false
        lastWsUrl = null
        reconnecting = false
        runCatching { peer?.close() }
        peer = null
        runCatching { audioTrack?.dispose() }
        audioTrack = null
        remoteAudioTrack = null
        _volume.value = 1f
        _micLive.value = false
        runCatching { audioSource?.dispose() }
        audioSource = null
        pendingRemoteCandidates.clear()
        _remoteVideo.value = null
        _contentLandscape.value = null
        _micMuted.value = true
        _state.value = State.Idle
    }

    /**
     * 界面上那颗麦克风按钮的唯一入口。
     *
     * 上一版按钮直接调 [setMicMuted]，而它做的是 `audioTrack?.setEnabled(...)` ——
     * 轨道还没建时打在 null 上，**图标翻了、什么都没发生**，观众以为自己在说话。
     * 现在：没开过麦就走 enableMic（申请权限 + 建轨 + 重新协商），开过就只是静音。
     */
    fun toggleMic(context: Context) {
        if (audioTrack == null) {
            if (_micPending.value) return      // 正在协商，别重复点
            enableMic(context)
        } else {
            setMicMuted(!_micMuted.value)      // 已经有轨：只是静音 / 取消静音
        }
    }

    fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
        audioTrack?.setEnabled(!muted)
    }

    // ---- 同看 --------------------------------------------------------------
    //
    // 观众这一侧**不播**任何内容，画面本来就是房主那块屏。所以这里只留一份状态镜像
    // 用于显示进度，外加一条把指令发回房主的路。
    // 控制指令不做本地乐观更新：房主执行完会在下一次广播里带回新进度，
    // 这样两边永远只有一个真相，不会出现"我这边看着跳了、他那边其实没动"。

    private val _watch = MutableStateFlow<WatchState?>(null)
    val watch: StateFlow<WatchState?> = _watch.asStateFlow()

    private val _watchAllowed = MutableStateFlow(false)
    val watchAllowed: StateFlow<Boolean> = _watchAllowed.asStateFlow()

    /** 放映厅状态（房主选了哪条片、放到哪、允不允许我控制）。 */
    private val _cinema = MutableStateFlow<com.ticketfortwo.app.cinema.CinemaSync.State?>(null)
    val cinema: StateFlow<com.ticketfortwo.app.cinema.CinemaSync.State?> = _cinema.asStateFlow()
    private val _cinemaAllowed = MutableStateFlow(false)
    val cinemaAllowed: StateFlow<Boolean> = _cinemaAllowed.asStateFlow()

    /** 把播放请求发回房主。权限位由房主那边说了算，这里只是提前拦一道。 */
    fun sendCinemaCmd(c: com.ticketfortwo.app.cinema.CinemaSync.Cmd) {
        if (!_cinemaAllowed.value) return
        runCatching {
            send("""{"t":"ccmd","f":"${com.ticketfortwo.app.cinema.CinemaSync.cmdFields(c)}"}""")
        }
    }

    /** 发一条播放控制指令。act 用字符串而不是对象，是为了和网页端观众共用同一套 f 字段。 */
    fun sendWatchCmd(act: String, arg: Long = 0L) {
        send(
            JSONObject().apply {
                put("t", "wcmd")
                put("f", WatchSync.cmdFields(act, arg))
            }.toString()
        )
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

            "bye" -> end("房主结束了分享")

            // 同看：房主那边播放器的状态镜像。房主每秒广播一次，这里只覆盖不判断。
            "watch" -> {
                val parsed = WatchSync.parseState(obj.optString("f").ifEmpty { null })
                if (parsed == null) {
                    _watch.value = null
                } else {
                    _watch.value = parsed.first
                    _watchAllowed.value = parsed.second
                }
            }

            // 放映厅：房主选了片。App 内观众端**不本地播**（这条流往往绑 Referer/Cookie，
            // 而且这里没有 hls.js），所以这一屏只做两件事：告诉他"房主开始放片了"，
            // 以及把他的 ±10/暂停 转成 ccmd 发回去 —— 方向盘在房主那个播放器上。
            "cinema" -> {
                val f = obj.optString("f")
                val st = com.ticketfortwo.app.cinema.CinemaSync.parseState(f)
                _cinema.value = st
                _cinemaAllowed.value = st != null &&
                    com.ticketfortwo.app.cinema.CinemaSync.allowsControl(f)
            }

            // 观众开麦后会主动发一轮 offer，房主的回信走这里。
            // 这是新支路：老版房主不认识观众发来的 offer，也就不会有这条 answer，
            // 所以 enableMic 里那 8 秒超时是必要的兜底。
            "answer" -> {
                val sdp = obj.optString("sdp")
                if (sdp.isEmpty()) return
                peer?.acceptAnswer(sdp)
                if (_micPending.value) {
                    _micPending.value = false
                    note("麦克风已接入，可以说话了")
                }
            }
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
                            // 分诊看的是信令通道还在不在。通道都断了，媒体直连必然撑不住 ——
                            // 这时候把 verdict（"两边都拿到公网地址却敲不通/对称 NAT"）甩给用户，
                            // 是拿一个真失败场景才成立的结论去解释一次正常结束，只会误导他去换网络。
                            if (wsOpen) fail(v?.headline ?: "直连失败", v)
                            else end("看不到画面了：与房主的连接先断了")
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
                    remoteAudioTrack = track
                    // 音量条可能先被拉过（手势比轨道到达早），轨道到手时补一次
                    runCatching { track?.setVolume(_volume.value.toDouble()) }
                    note("收到房主的声音")
                }

                override fun onControlMessage(text: String) = Unit

                override fun onFailure(reason: String) = fail(reason)
            },
        )
        p.open()
        // 观众的麦克风**不在连接时创建**。上一版在这里就 addTrack，注释还写着
        // "中途加轨需要重新协商，做不到" —— 那句话是错的，而代价是两个真问题：
        // ① libwebrtc 一建音频源就打开 AudioRecord，观众只是看个画面，
        //    手机状态栏的橙色麦克风灯却一直亮着（静音 ≠ 关闭）；
        // ② 观众链路从没申请过 RECORD_AUDIO，于是 ensureMicTrack 返回 null、
        //    轨道根本不存在，点「开麦」只是把图标翻了一下 —— 连麦一直是坏的。
        // 现在改成：不建轨（answer 里音频方向退成 recvonly，观众照样听得到房主），
        // 等观众真去点「开麦」时再建轨 + 由观众主动发一轮重新协商（见 enableMic）。
        peer = p
        return p
    }

    // ---- 麦克风：默认关闭，第一次点「开麦」才建轨 --------------------------

    /** 点「开麦」之后、房主应答之前的中间态。界面用它画"开麦中…"。 */
    private val _micPending = MutableStateFlow(false)
    val micPending: StateFlow<Boolean> = _micPending.asStateFlow()

    /**
     * 有没有一条真正在发的音频轨。
     *
     * 必须是 StateFlow 而不是 `get() = audioTrack != null`：实测踩过 —— 普通字段参与
     * 组合，Compose 不会为它重组，于是麦克风明明已经接入，按钮还停在「取消静音」，
     * 用户以为自己没开成功。状态就得是状态，不能让组合去猜。
     */
    private val _micLive = MutableStateFlow(false)
    val micLive: StateFlow<Boolean> = _micLive.asStateFlow()

    /**
     * 观众主动发起一轮重新协商，把自己的麦克风加进去。
     *
     * 为什么由观众发 offer：改的是观众这一侧的媒体，谁变谁发，房主只需要照着答；
     * 房主侧 [Peer] 本来就同时具备 acceptOffer 能力（角色只决定第一次是谁发起）。
     * 协商期间画面不受影响 —— 复用同一条传输，不换 ICE 候选。
     */
    fun enableMic(context: Context) {
        if (peer == null) {
            note("还没连上房主，开不了麦")
            return
        }
        if (audioTrack != null) {
            setMicMuted(false)
            return
        }
        _micPending.value = true
        if (ensureMicTrack(context) == null) {
            // 权限没给。这里必须说清楚，否则用户以为"点了没反应"是 App 坏了。
            note("未授予麦克风权限：只能看画面，说不了话")
            return
        }
        // 开关只有一处生效：setMicMuted 同时改状态与轨道，
        // 分两处写就会留下"状态说开了、轨道其实没开"的静音假连接。
        setMicMuted(false)
        peer?.addLocalTracks(null, audioTrack)
        peer?.startOffer()
        note("正在把麦克风加进这条连接…")
        // 房主版本太旧会不认识观众发来的 offer，于是永远等不到 answer。
        // 给 8 秒：到点就把状态说破，别让人对着一个亮着的麦克风图标说话。
        scope.launch {
            delay(8_000)
            if (_micPending.value) {
                _micPending.value = false
                note("房主没能接收麦克风：请让他更新 App 后重新分享")
            }
        }
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
        // 跟着 _micMuted 走，别写死 false：写死就把"要不要发声音"这个决定
        // 复制到了两个地方，调用方一改顺序就会悄悄变成常发静音（房主收得到、听不见）。
        track.setEnabled(!_micMuted.value)
        audioSource = src
        audioTrack = track
        _micLive.value = true
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
        wsOpen = false
        _state.value = State.Failed(reason, verdict)
    }

    /**
     * 正常收场。与 [fail] 的区别只在语义：不画红、不给换网络建议、不摆"重试"那条按钮，
     * 因为该做的动作是"等房主重新开一次"，不是"你回去查自己网络"。
     */
    private fun end(reason: String) {
        if (_state.value is State.Ended) return   // 再见与 socket 关闭会先后都到
        note(reason)
        wsOpen = false
        runCatching { ws?.close() }
        ws = null
        // 轨道与 PeerConnection 必须在这里放掉：留着一个已经收不到包的 pc，
        // 屏幕还停在"观看"那一层，用户会以为只是画面卡住了。
        runCatching { peer?.close() }
        peer = null
        _remoteVideo.value = null
        _state.value = State.Ended(reason)
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
