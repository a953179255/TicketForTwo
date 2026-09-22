package com.ticketfortwo.app.rtc

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * 一条 1v1 的 RTCPeerConnection，角色可翻转 —— 同一个 APK 既能当房主也能当观众。
 *
 * 一期零服务器信令决定了它的形态：**不用 trickle ICE**。必须等 ICE 收集完成，
 * 把全部候选留在 SDP 里，一次性打包进链接；否则链接里缺 srflx 候选，跨网必不通。
 * 代价是出链接前要等 2–3 秒，UI 必须把这个等待显式画出来。
 *
 * 每条路都要以"有界成功"或"清晰失败"结束（借 Piik ADR-0001 的原则）：
 * 收集有超时兜底，连接状态全部回调给上层，不允许无限转圈。
 */
class Peer(
    private val role: Role,
    private val room: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val listener: Listener,
) : PeerConnection.Observer {

    enum class Role { Offerer, Answerer }

    interface Listener {
        /** 一份可以直接编码进链接的完整信令（已裁剪候选）。 */
        fun onSignalReady(env: SignalingCodec.Envelope)
        fun onIceState(state: PeerConnection.IceConnectionState)
        fun onSignalingState(state: PeerConnection.SignalingState)
        fun onRemoteVideo(track: VideoTrack?)
        fun onRemoteAudio(track: AudioTrack?)
        fun onControlMessage(text: String)
        fun onFailure(reason: String)
    }

    private var pc: PeerConnection? = null
    private var controlChannel: DataChannel? = null

    /** 等 ICE 收集完成的兜底时限；超时就带着已收到的候选出链接。 */
    private val gatheringTimeoutMs = 8_000L

    /** 断连后的自愈宽限期：先等它自己回来，再 restart，再等一轮才判死。 */
    private val graceMs = 8_000L
    private var gatheringTimer: Runnable? = null
    private var gatheringDone = false
    private var pendingLocalSdp: SessionDescription? = null

    val connectionState: PeerConnection.IceConnectionState
        get() = pc?.iceConnectionState() ?: PeerConnection.IceConnectionState.NEW

    // ---- 生命周期 ------------------------------------------------------

    fun open() {
        if (pc != null) return
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
        gatheringTimer?.let { handler.removeCallbacks(it) }
        gatheringTimer = null
        runCatching { controlChannel?.close() }
        runCatching { controlChannel?.dispose() }
        controlChannel = null
        runCatching { pc?.close() }
        runCatching { pc?.dispose() }
        pc = null
    }

    /**
     * 加入本地轨道。必须在 [startOffer] / [acceptRemote] 之前调用。
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
        gatheringDone = false
        // Unified Plan 下 OfferToReceive* 这类 mandatory 约束已被忽略，方向由 transceiver 决定，
        // 所以这里用空约束，不写那些会误导后人的假开关。
        p.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(sdp: SessionDescription) {
                p.setLocalDescription(object : SimpleSdpObserver("setLocal(offer)") {
                    override fun onSetSuccess() = awaitGatheringThenEmit(sdp)
                }, sdp)
            }
        }, MediaConstraints())
    }

    /** 观众侧：吃进房主的 offer，产出 answer；或房主吃进观众的 answer 完成握手。 */
    fun acceptRemote(env: SignalingCodec.Envelope) {
        if (pc == null) open()
        val p = pc ?: return
        val type = if (env.kind == SignalingCodec.Kind.Offer) SessionDescription.Type.OFFER
        else SessionDescription.Type.ANSWER
        p.setRemoteDescription(object : SimpleSdpObserver("setRemote(${env.kind})") {
            override fun onSetSuccess() {
                if (env.kind != SignalingCodec.Kind.Offer) {
                    Log.i(TAG, "remote answer applied; handshake complete, waiting for ICE")
                    return
                }
                gatheringDone = false
                p.createAnswer(object : SimpleSdpObserver("createAnswer") {
                    override fun onCreateSuccess(sdp: SessionDescription) {
                        p.setLocalDescription(object : SimpleSdpObserver("setLocal(answer)") {
                            override fun onSetSuccess() = awaitGatheringThenEmit(sdp)
                        }, sdp)
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(type, env.sdp))
    }

    // ---- ICE 收集等待 --------------------------------------------------

    private fun awaitGatheringThenEmit(localSdp: SessionDescription) {
        pendingLocalSdp = localSdp
        if (gatheringDone) {
            emit(); return
        }
        gatheringTimer?.let { handler.removeCallbacks(it) }
        val timer = Runnable {
            if (!gatheringDone) {
                Log.w(TAG, "ICE gathering timed out after ${gatheringTimeoutMs}ms; emitting what we have")
                gatheringDone = true
                emit()
            }
        }
        gatheringTimer = timer
        handler.postDelayed(timer, gatheringTimeoutMs)
    }

    private fun emit() {
        // 必须读 pc.localDescription，不能用 onCreateSuccess 给的那份 ——
        // 后者是**收集前**的裸 SDP，一条 a=candidate 都没有。
        // 实测踩过：用回调那份会产出 1554 字符但 kept=0 的空壳 offer，永远连不通。
        val sdp = pc?.localDescription ?: pendingLocalSdp
        if (sdp == null) {
            listener.onFailure("ICE 收集完成但没有 localDescription")
            return
        }
        val pruned = SignalingCodec.prune(sdp.description)
        val env = SignalingCodec.Envelope(
            room = room,
            kind = if (role == Role.Offerer) SignalingCodec.Kind.Offer else SignalingCodec.Kind.Answer,
            sdp = pruned.sdp,
        )
        Log.i(
            TAG,
            "signal ready kind=${env.kind} kept=${pruned.keptCandidates} " +
                "dropped=${pruned.droppedCandidates} had=${pruned.hadCandidates} " +
                "wire=${SignalingCodec.wireLength(env)}ch",
        )
        listener.onSignalReady(env)
    }

    fun sendControl(text: String) {
        val ch = controlChannel ?: return
        // DataChannel 没有 isOpen()，只有 state()
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(text.toByteArray()).apply { rewind() }, false))
    }

    // ---- PeerConnection.Observer --------------------------------------

    override fun onSignalingChange(state: PeerConnection.SignalingState) {
        listener.onSignalingState(state)
    }

    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
        Log.i(TAG, "ice=$state")
        listener.onIceState(state)
        // DISCONNECTED 不等于死亡：真实网络切换（WiFi↔蜂窝、NAT 映射过期）后，
        // 已协商好的 candidate pair 常常几十秒内自己恢复。先给它宽限期。
        //
        // 这里**故意不调 restartIce()**：ICE restart 会改 ufrag/pwd，必须把新 offer
        // 再送一次给对端才生效，而一期是零服务器架构、链接靠人工复制粘贴，
        // 没有通道能投递这份新 offer（DataChannel 本身就在这条将断的连接上）。
        // 调它只会假装在重连。等有了信令通道（二期 WebSocket 中继）再启用。
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

    private var graceJob: Runnable? = null

    private fun scheduleGrace() {
        if (graceJob != null) return
        val job = Runnable {
            graceJob = null
            val now = pc?.iceConnectionState()
            if (now != PeerConnection.IceConnectionState.CONNECTED &&
                now != PeerConnection.IceConnectionState.COMPLETED
            ) {
                Log.w(TAG, "ICE 未在 ${graceMs}ms 内自愈")
                listener.onFailure("直连中断且未能自愈。一期没有信令通道，无法自动重连，请重新发起分享。")
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
        if (state == PeerConnection.IceGatheringState.COMPLETE) markGatheringDone()
    }

    /** 不 trickle：候选已经在 SDP 里，这里只用来判断"收集完了没有"。 */
    override fun onIceCandidate(candidate: IceCandidate) {
        if (candidate.sdp.isNullOrEmpty()) markGatheringDone()
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

    private fun markGatheringDone() {
        if (gatheringDone) return
        gatheringDone = true
        gatheringTimer?.let { handler.removeCallbacks(it) }
        gatheringTimer = null
        if (pendingLocalSdp != null) handler.post { emit() }
    }

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
