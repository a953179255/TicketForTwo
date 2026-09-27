package com.ticketfortwo.app.rtc

import android.content.Context
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * libwebrtc 的进程级初始化与共享资源。
 *
 * 音频设备模块用 [JavaAudioDeviceModule]：采集走 `AudioSource.VOICE_COMMUNICATION`
 * （实测：Builder 里 `audioSource` 的默认值就是 7 = VOICE_COMMUNICATION，不是猜的），
 * 所以下行远端声音必须由它自己的 AudioTrack 播放，不能改用 MediaPlayer/SoundPool ——
 * 换了就没有参考信号，回声消除当场失效。
 */
object RtcEngine {
    private const val TAG = "RtcEngine"

    /**
     * STUN 列表。多目的地并发探测，谁先响应就用谁 —— 单一 STUN 被墙或被限速时仍有备选。
     *
     * 前三个是 2026-09-23 逐台实测选出来的：国内可达且延迟最低（22 / 37 / 47 ms）。
     * 原来的 Google 与 Twilio 退到兜底 —— 它们在本机实测是 59 / 104 ms，
     * 而且在部分运营商网络下根本不可达，一旦拿不到 srflx 公网候选，跨网直连就必然失败。
     *
     * 仍然没有 TURN：纯 P2P，对称 NAT 下会直接失败，这是架构的固有代价。
     */
    val stunServers: List<PeerConnection.IceServer> = listOf(
        "stun:stun.hitv.com:3478",
        "stun:stun.chat.bilibili.com:3478",
        "stun:stun.miwifi.com:3478",
        "stun:stun.l.google.com:19302",
    ).map { PeerConnection.IceServer.builder(it).createIceServer() }

    lateinit var eglBase: EglBase
        private set
    lateinit var factory: PeerConnectionFactory
        private set
    lateinit var audioDeviceModule: JavaAudioDeviceModule
        private set

    @Volatile
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()

        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .createAudioDeviceModule()

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ false,
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        audioDeviceModule = adm
        initialized = true
        Log.i(TAG, "libwebrtc initialized; hwAec=${JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()} hwNs=${JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported()}")
    }

    fun configuration(): PeerConnection.RTCConfiguration =
        PeerConnection.RTCConfiguration(stunServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // 持续收集候选。之前用 GATHER_ONCE 是因为"整份 SDP 要塞进链接，必须等收集完再出链接"，
            // 现在有了信令通道、候选可以随收随发，那个约束不存在了。改成 CONTINUALLY 之后，
            // 网络切换（WiFi ↔ 蜂窝）或 NAT 映射变化时补发的新候选能直接续上连接。
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

    /**
     * 建麦克风轨时传给 `createAudioSource` 的约束 —— **回声消除到底开没开，就看这里**。
     *
     * 以前两端传的都是空的 `MediaConstraints()`，注释写着"默认就会启用 AEC/NS/AGC"。
     * 那句话是猜的，而这套 SDK 的默认值恰好是个坑，所以把它测清楚了再改（下面每条都有出处）：
     *
     * ① 键名。本机 AAR 的 `libjingle_peerconnection_so.so` 里能找到的音频处理约束键只有
     *    `echoCancellationMode` / `noiseSuppressionMode` / `autoGainControlMode`
     *    （取值串：`unspecified` / `disabled` / `platform` / `software`）和老的
     *    `googEchoCancellation` 一族；**没有**裸的 `echoCancellation`。
     *    （`echoCancellation` 是 `echoCancellationMode` 的前缀，链接器只合并公共后缀，
     *    所以它真被引用的话一定会单独出现 —— 没出现。）
     * ② 空约束 = `unspecified`。而 `WebRtcAudioRecord.shouldUsePlatformEffect()` 反编译出来是
     *    `requested && isSupported && mode != SOFTWARE` —— 只有明确要 SOFTWARE 才让软件 AEC 上位。
     *    也就是说：**设备没有硬件 AEC 时（模拟器、一部分国产低端机），unspecified 谁都不开**，
     *    连麦就是裸的外放灌回麦克风。真机报 `hwAec=true`、模拟器报 `false`，正是这条分界线。
     * ③ 不能无脑要 software。.so 里的校验语句写着
     *    `Platform echo cancellation cannot be combined with software noise suppression`
     *    和 `Software fallback is disabled by platform mode.` —— AEC 与 NS 必须同一档，
     *    而且一旦走 platform 就没有软件兜底。
     *
     * 所以规则是**按设备二选一**，而不是写死一档：有硬件 AEC 就交给它（顺带把 NS 一起交给
     * 同一条 platform 链路，别混档），没有就明确点名软件 AEC3 + 软件 NS。AGC 一律不点名：
     * 房主的外放里是影片声，自动增益会把整段影片按"说话"来泵音量。
     */
    fun audioSourceConstraints(): MediaConstraints {
        val hwAec = JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported()
        val mc = MediaConstraints()
        if (!hwAec) {
            // 这份 fork 的 MediaConstraints 没有 addMandatory()，只有公开的 mandatory 列表
            // （javap 过），所以直接往列表里加键值对。
            mc.mandatory.add(MediaConstraints.KeyValuePair("echoCancellationMode", "software"))
            mc.mandatory.add(MediaConstraints.KeyValuePair("noiseSuppressionMode", "software"))
        }
        Log.i(TAG, "音频源约束：${if (hwAec) "交给硬件 AEC/NS（不加约束）" else "无硬件 AEC → 显式启用软件 AEC3 + NS"}")
        return mc
    }

    /**
     * 把**本端要播的对方声音**静音掉。
     *
     * 这里静音的只有 libwebrtc 自己那条下行 AudioTrack（`WebRtcAudioTrack.setSpeakerMute`
     * 在混音前把缓冲区清零），本机其他 App 的外放不受影响 —— 所以房主开了这一档之后，
     * 电影声照放，只是听不见观众说话。用它而不是"不建音频轨"，是为了让 m-line 保持
     * sendrecv：中途改成连麦只翻转这一个开关，不用再协商一次。
     */
    fun setDownlinkMuted(muted: Boolean) {
        if (!initialized) return
        runCatching { audioDeviceModule.setSpeakerMute(muted) }
            .onFailure { Log.w(TAG, "setSpeakerMute($muted) 失败：${it.message}") }
    }

    /**
     * 把回声消除的**实际**状态打进日志，好让"支持了"这三个字有据可查。
     *
     * 用户要求是"只需要支持，暂时不用测试"，而耳朵听不了的时候这套 SDK 自带的
     * `getPlatformAudioProcessingState()` 就是唯一能读到的活信号：每个分量都给
     * isAvailable / isRequested / isActive，不用猜。
     */
    fun logAudioProcessingState(from: String) {
        val s = runCatching { audioDeviceModule.platformAudioProcessingState }.getOrNull() ?: return
        fun d(c: JavaAudioDeviceModule.PlatformAudioProcessingComponentState) =
            "available=${c.isAvailable} requested=${c.isRequested} active=${c.isActive}"
        Log.i(TAG, "平台音频处理[$from] ${s.topology} AEC{${d(s.echoCancellation)}} " +
            "NS{${d(s.noiseSuppression)}} AGC{${d(s.autoGainControl)}}")
    }
}
