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
     * 三个公共 STUN。借 Piik ADR-0009 的思路做多目的地探测：单一 STUN 被墙或被限速时
     * 仍有备选。一期没有 TURN —— 纯 P2P，对称 NAT 下会直接失败，这是架构的固有代价。
     */
    val stunServers: List<PeerConnection.IceServer> = listOf(
        "stun:stun.l.google.com:19302",
        "stun:stun1.l.google.com:19302",
        "stun:global.stun.twilio.com:3478",
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
            // 关键：一期信令要把整份 SDP 塞进链接，必须等 ICE 收集完成再出链接，
            // 所以用 GATHER_ONCE 而不是 GATHER_CONTINUALLY。
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
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
