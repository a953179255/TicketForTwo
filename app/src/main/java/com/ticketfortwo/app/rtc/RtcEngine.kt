package com.ticketfortwo.app.rtc

import android.content.Context
import android.util.Log
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * libwebrtc 的进程级初始化与共享资源。
 *
 * 音频设备模块刻意用 [JavaAudioDeviceModule] 的默认配置：它在设备上报支持时会自动启用
 * 硬件 AEC / NS，采集走 `AudioSource.VOICE_COMMUNICATION`。连麦不啸叫就靠这一条，
 * 所以下行远端声音必须由它自己的 AudioTrack 播放，不能改用 MediaPlayer/SoundPool。
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

    @Synchronized
    fun shutdown() {
        if (!initialized) return
        runCatching { audioDeviceModule.release() }
        runCatching { factory.dispose() }
        runCatching { eglBase.release() }
        initialized = false
    }
}
