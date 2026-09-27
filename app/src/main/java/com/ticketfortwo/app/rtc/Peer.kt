package com.ticketfortwo.app.rtc

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.RTCStats
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import kotlin.math.roundToInt

/**
 * 一条 1v1 的 RTCPeerConnection，角色可翻转 —— 同一个 APK 既能当房主也能当观众。
 *
 * 与上一版的根本差别：**信令不再是"人工搬运的链接"，而是一条实时通道**
 * （房主手机里的传话员 + 出站隧道，见 signaling/SignalHub.kt）。这解锁了两件以前做不到的事：
 *
 * 1. **trickle ICE**：候选一收集到就发走，不再等整个收集窗口结束。
 *    直接效果是"点开链接更快出画面" —— 握手不再被那 2–3 秒的等待卡住。
 * 2. **候选能随网络变化补发**（配合 GATHER_CONTINUALLY），WiFi 与蜂窝切换后
 *    连接有机会自己续上，而不是只能让用户重新分享一次。
 *
 * 每条路仍然以"有界成功"或"清晰失败"结束：连接状态全部回调给上层，不允许无限转圈。
 */
class Peer(
    private val role: Role,
    private val room: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val listener: Listener,
) : PeerConnection.Observer {

    enum class Role { Offerer, Answerer }

    /** 本地描述的种类，告诉信令通道该按 offer 还是 answer 发。 */
    enum class Kind { Offer, Answer }

    interface Listener {
        /** 本地 SDP 就绪 —— 立刻交给信令通道发走，**不要**等候选。 */
        fun onLocalDescription(kind: Kind, sdp: String)

        /** 新收集到的本地候选，随收随发。 */
        fun onLocalCandidate(candidate: IceCandidate)

        fun onIceState(state: PeerConnection.IceConnectionState)
        fun onSignalingState(state: PeerConnection.SignalingState)
        fun onRemoteVideo(track: VideoTrack?)
        fun onRemoteAudio(track: AudioTrack?)
        fun onControlMessage(text: String)
        fun onFailure(reason: String)
    }

    private var pc: PeerConnection? = null
    private var controlChannel: DataChannel? = null

    /**
     * 远端描述（setRemote）落地**之前**收到的候选，自己缓冲，落地后补交。
     *
     * 原来注释以为"libwebrtc 会自行排队"——错了：AddIceCandidateInternal 在没有
     * remote_description 时直接返回 kAddIceCandidateFailNoRemoteDescription，候选被
     * **静默丢弃**，Java 层连异常都不抛（本项目依赖 150.7871.01 源码核对过）。
     * 首连时 offer/answer 与其后毫秒级到达的候选在同一条 WS 上 FIFO，接受方
     * setRemote 还是异步的，这个窗口必然存在；首批里的 srflx 丢了，GATHER_CONTINUALLY
     * 不会重发同一批，跨网直连可能就再也敲不通（见 REVIEW-2026-09-27）。
     * 提交可能来自信令线程、flush 来自 setRemote 回调线程，所以要加锁。
     */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()

    /** 全程取证，失败时由上层取 [IceProbe.verdict] 拼出"根据什么判断"。 */
    val probe = IceProbe()

    /** 断连后的自愈宽限期：先等它自己回来，超时再报可行动的失败。 */
    private val graceMs = 8_000L

    private var graceJob: Runnable? = null

    val connectionState: PeerConnection.IceConnectionState
        get() = pc?.iceConnectionState() ?: PeerConnection.IceConnectionState.NEW

    // ---- 生命周期 ------------------------------------------------------

    fun open() {
        if (pc != null) return
        probe.reset()
        val created = RtcEngine.factory.createPeerConnection(RtcEngine.configuration(), this)
        pc = created
        if (created == null) {
            listener.onFailure("createPeerConnection 返回 null")
            return
        }
        if (role == Role.Offerer) {
            // 只有 offerer 建 DataChannel，避免两端重复协商同名通道
            val init = DataChannel.Init().apply { ordered = true }
            controlChannel = created.createDataChannel("control", init)
        }
        Log.i(TAG, "peer opened role=$role room=$room")
    }

    fun close() {
        cancelGrace()
        synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.clear() }
        runCatching { controlChannel?.close() }
        runCatching { controlChannel?.dispose() }
        controlChannel = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
    }

    /**
     * 加入本地轨道。必须在 [startOffer] / [acceptOffer] 之前调用。
     *
     * 房主传（屏幕视频轨, 麦克风轨）；观众传（null, 麦克风轨）。
     * 两边都始终挂上麦克风轨（静音用 setEnabled(false)，不移除轨道），
     * 这样 addTrack 产生的 transceiver 是 **sendrecv**，房主才收得到观众的声音 —— 连麦成立。
     * 若改成"不发言就不加轨道"，房主侧就只能 sendonly，连麦会直接失效。
     *
     * 返回 sender 是为了让上层能改码率上限：RtpSender 只能从 addTrack 的返回值拿到。
     */
    data class Senders(val video: org.webrtc.RtpSender?, val audio: org.webrtc.RtpSender?)

    fun addLocalTracks(video: VideoTrack?, audio: AudioTrack?): Senders {
        if (pc == null) open()
        val p = pc ?: return Senders(null, null)
        return Senders(
            video = video?.let { p.addTrack(it) },
            audio = audio?.let { p.addTrack(it) },
        )
    }

    /** 房主侧：发起 offer。 */
    fun startOffer() {
        if (pc == null) open()
        val p = pc ?: return
        // Unified Plan 下 OfferToReceive* 这类 mandatory 约束已被忽略，方向由 transceiver 决定，
        // 所以这里用空约束，不写那些会误导后人的假开关。
        p.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                p.setLocalDescription(object : SimpleSdpObserver("setLocal(offer)") {
                    override fun onSetSuccess() {
                        // 立刻发走，不等候选：候选由 onIceCandidate 单独 trickle。
                        val local = p.localDescription
                        if (local == null) {
                            listener.onFailure("offer 就绪但读不到 localDescription")
                        } else {
                            logThread("localDescription(${Kind.Offer})")
                            listener.onLocalDescription(Kind.Offer, local.description)
                        }
                    }
                }, sdp)
            }
        }, MediaConstraints())
    }

    /** 观众侧：吃进房主的 offer，产出 answer 并立刻发走。 */
    fun acceptOffer(sdp: String) = setRemote(SessionDescription.Type.OFFER, sdp)

    /** 房主侧：吃进观众的 answer，握手完成。 */
    fun acceptAnswer(sdp: String) = setRemote(SessionDescription.Type.ANSWER, sdp)

    private fun setRemote(type: SessionDescription.Type, sdp: String) {
        if (pc == null) open()
        val p = pc ?: return
        p.setRemoteDescription(object : SimpleSdpObserver("setRemote($type)") {
            override fun onSetSuccess() {
                flushPendingRemoteCandidates(p)
                if (type != SessionDescription.Type.OFFER) {
                    Log.i(TAG, "remote answer applied; handshake complete, waiting for ICE")
                    return
                }
                p.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        p.setLocalDescription(object : SimpleSdpObserver("setLocal(answer)") {
                            override fun onSetSuccess() {
                                val local = p.localDescription
                                if (local == null) {
                                    listener.onFailure("answer 就绪但读不到 localDescription")
                                } else {
                                    logThread("localDescription(${Kind.Answer})")
                                    listener.onLocalDescription(Kind.Answer, local.description)
                                }
                            }
                        }, answer)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(type, sdp))
    }

    /** 收到对方的 ICE 候选。远端描述还没设好时先自己排上，见 [pendingRemoteCandidates]。 */
    fun addRemoteCandidate(candidate: IceCandidate) {
        val p = pc ?: return
        probe.onRemoteCandidate(candidate.sdp)
        if (p.remoteDescription == null) {
            synchronized(pendingRemoteCandidates) { pendingRemoteCandidates.add(candidate) }
            return
        }
        submitRemoteCandidate(p, candidate)
    }

    /** setRemote 成功后补交排队中的候选。 */
    private fun flushPendingRemoteCandidates(p: PeerConnection) {
        val queued = synchronized(pendingRemoteCandidates) {
            pendingRemoteCandidates.toList().also { pendingRemoteCandidates.clear() }
        }
        queued.forEach { submitRemoteCandidate(p, it) }
    }

    private fun submitRemoteCandidate(p: PeerConnection, candidate: IceCandidate) {
        val ok = runCatching { p.addIceCandidate(candidate) }.getOrElse {
            Log.w(TAG, "addIceCandidate 异常：${it.message}")
            false
        }
        // 返回 false 也是丢弃（无远端描述之外也可能发生）——只接异常的老写法对它毫无反应。
        if (!ok) Log.w(TAG, "addIceCandidate 被拒：${candidate.sdp?.take(60)}")
    }

    fun sendControl(text: String) {
        val ch = controlChannel ?: return
        // DataChannel 没有 isOpen()，只有 state()
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(text.toByteArray()).apply { rewind() }, false))
    }

    // ---- 连接质量观测 ---------------------------------------------------

    /**
     * 当前这一条通路的实测快照。
     *
     * 只报**能从 stats 里真读出来的东西**：RTT 取选中 candidate-pair 的
     * `currentRoundTripTime`，通路类型取两端 candidate 的 `candidateType`。
     * 拿不到就是 null，UI 画 "—"，不用估计数填空。
     */
    data class NetStats(val rttMs: Int?, val viaLabel: String?)

    /**
     * 异步取一次 stats。回调在 libwebrtc 的采集线程上，用 [handler] 转回主线程。
     *
     * 用新版 `getStats(RTCStatsCollectorCallback)`（给 `RTCStatsReport`），
     * 不是那个已经废弃、只返回总流量快照的 `getStats(StatsObserver, track)`。
     */
    fun collectStats(onResult: (NetStats) -> Unit) {
        val p = pc ?: run { handler.post { onResult(NetStats(null, null)) }; return }
        p.getStats(RTCStatsCollectorCallback { report ->
            val parsed = runCatching { parseStats(report) }.getOrElse {
                Log.w(TAG, "stats 解析失败：${it.message}")
                NetStats(null, null)
            }
            handler.post { onResult(parsed) }
        })
    }

    // ---- PeerConnection.Observer --------------------------------------

    override fun onSignalingChange(state: PeerConnection.SignalingState) {
        listener.onSignalingState(state)
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        Log.i(TAG, "ice=$state")
        probe.onIceState(state.name)
        listener.onIceState(state)
        // DISCONNECTED 不等于死亡：真实网络切换（WiFi↔蜂窝、NAT 映射过期）后，
        // 已协商好的 candidate pair 常常几十秒内自己恢复。先给它宽限期。
        if (state == PeerConnection.IceConnectionState.DISCONNECTED) {
            scheduleGrace()
            return
        }
        if (state == PeerConnection.IceConnectionState.CONNECTED ||
            state == PeerConnection.IceConnectionState.COMPLETED
        ) {
            cancelGrace()
        }
    }

    private fun scheduleGrace() {
        if (graceJob != null) return
        val job = Runnable {
            graceJob = null
            val now = pc?.iceConnectionState()
            if (now != PeerConnection.IceConnectionState.CONNECTED &&
                now != PeerConnection.IceConnectionState.COMPLETED
            ) {
                Log.w(TAG, "ICE 未在 ${graceMs}ms 内自愈")
                listener.onFailure("直连中断且未能自愈。请让朋友重新点一次邀请链接。")
            }
        }
        graceJob = job
        handler.postDelayed(job, graceMs)
    }

    private fun cancelGrace() {
        graceJob?.let { handler.removeCallbacks(it) }
        graceJob = null
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
        Log.d(TAG, "ice gathering=$state")
    }

    /**
     * trickle：候选随收随发。
     *
     * 收到空候选代表收集结束，这里不用做任何事 —— 只是日志上能看出阶段。
     */
    override fun onIceCandidate(candidate: IceCandidate) {
        if (candidate.sdp.isNullOrEmpty()) {
            Log.i(TAG, "候选收集结束 ${probe.summary()}")
            return
        }
        probe.onLocalCandidate(candidate.sdp)
        handler.post { listener.onLocalCandidate(candidate) }
    }

    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit

    override fun onDataChannel(channel: DataChannel) {
        if (controlChannel == null) controlChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                Log.d(TAG, "datachannel ${channel.label()} -> ${channel.state()}")
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                handler.post { listener.onControlMessage(String(bytes)) }
            }
        })
    }

    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
        when (val track = receiver.track()) {
            is VideoTrack -> {
                Log.i(TAG, "remote video track")
                listener.onRemoteVideo(track)
            }
            is AudioTrack -> {
                Log.i(TAG, "remote audio track")
                listener.onRemoteAudio(track)
            }
            else -> Unit
        }
    }

    override fun onRenegotiationNeeded() = Unit

    // ---- helpers -------------------------------------------------------

    /**
     * 记下本地描述落在哪条线程。
     *
     * 加它的直接收益是**推翻了我自己对 libwebrtc 的一条假设**：本来以为
     * `SdpObserver` 会回到"创建 PeerConnection 的那条线程"（`ViewerSession.ensurePeer()`
     * 跑在 `Dispatchers.Main.immediate`，那样 answer 就得在主线程写 socket）。
     * 2026-09-25 实测两局，日志是 `localDescription(Offer) 回调线程=signaling_threa` ——
     * 它走的是 signaling 线程，所以 answer 那一路本来就是安全的。
     *
     * 真正会落到主线程的只有候选：[onIceCandidate] 里那句 `handler.post` 是显式投回
     * 主线程的，这是 8127d12 的根因（房主侧），也是观众侧原先的同一个坑。
     * 线程这种东西不要靠推断，打出来看。
     */
    private fun logThread(what: String) =
        Log.d(TAG, "$what 回调线程=${Thread.currentThread().name}")

    private open inner class SimpleSdpObserver(private val label: String) : SdpObserver {
        override fun onCreateFailure(error: String) = fail(label, "create", error)
        override fun onCreateSuccess(sdp: SessionDescription) = Unit
        override fun onSetFailure(error: String) = fail(label, "set", error)
        override fun onSetSuccess() = Unit

        private fun fail(label: String, op: String, error: String) {
            Log.e(TAG, "$label $op failed: $error")
            listener.onFailure("$label $op 失败：$error")
        }
    }

    companion object {
        private const val TAG = "Peer"
    }
}

// ---- stats 解析（纯函数，可在 JVM 上单测）------------------------------

/**
 * 从一次 stats 报告里取出"当前在用的那条 candidate-pair"。
 *
 * 为什么不能直接取第一条 `candidate-pair`：ICE 会为每组合格都留下**探测过**的
 * pair，而且 `currentRoundTripTime` 在**所有** succeeded 的 pair 上都有值 ——
 * 所以"有 RTT"根本不能当作"在用"的证据（第一版就是把它当证据，单测直接把
 * 这个不可达分支打了出来）。真正的优先级：
 * ① `selected=true`（旧实现叫 `currentPair`）；
 * ② 退而求其次取 `bytesSent` 最大的一条 —— 只有它在实际跑流量；
 * ③ 全为 0（刚连上、还没发出去东西）才退回"任意有 RTT 的一条"。
 */
internal fun selectedPair(report: RTCStatsReport?): RTCStats? {
    val pairs = report?.statsMap?.values?.filter { it.type == "candidate-pair" }.orEmpty()
    if (pairs.isEmpty()) return null
    fun bytes(s: RTCStats) = (s.members["bytesSent"] as? Number)?.toDouble() ?: 0.0
    pairs.firstOrNull { it.members["selected"] == true }?.let { return it }
    val busiest = pairs.maxByOrNull { bytes(it) }
    if (busiest != null && bytes(busiest) > 0.0) return busiest
    // 刚连上、一个字节都还没发出去：这时"有 RTT"是唯一还能用的线索。
    return pairs.firstOrNull { it.members["currentRoundTripTime"] != null }
}

/** 一次报告的实测快照；字段缺失一律返回 null，不猜。 */
internal fun parseStats(report: RTCStatsReport?): Peer.NetStats {
    val pair = selectedPair(report) ?: return Peer.NetStats(null, null)
    val rttSec = (pair.members["currentRoundTripTime"] as? Number)?.toDouble()
    val rttMs = rttSec?.let { (it * 1000).roundToInt() }

    val all = report?.statsMap
    val localType = candidateType(pair, "localCandidateId", all)
    val remoteType = candidateType(pair, "remoteCandidateId", all)

    return Peer.NetStats(rttMs, viaLabel(localType, remoteType))
}

/** candidate-pair 只给 candidateId，类型要回到同一份报告里按 id 查那条 candidate。 */
private fun candidateType(pair: RTCStats, key: String, all: Map<String, RTCStats>?): String? {
    val id = pair.members[key] as? String ?: return null
    return all?.get(id)?.members?.get("candidateType") as? String
}

/**
 * 通路类型文案。
 *
 * host↔host 才是"同一网络"（只用本机地址就通了，没出网关）；
 * 任何一端是 srflx 说明经过了公网地址反射，属于跨 NAT 直连。
 * relay 这一版不会出现 —— 架构里没有 TURN 中继，所以也不写进文案里骗人。
 */
internal fun viaLabel(local: String?, remote: String?): String? = when {
    local == null && remote == null -> null
    local == "relay" || remote == "relay" -> "中继"
    local == "host" && remote == "host" -> "同一网络"
    local == "srflx" || remote == "srflx" -> "跨网直连"
    else -> "直连"
}
