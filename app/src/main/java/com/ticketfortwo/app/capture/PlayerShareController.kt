package com.ticketfortwo.app.capture

import android.content.Context
import android.view.Surface
import android.util.Log
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoTrack
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.cinema.TheaterPlayer

/**
 * 「我播他看」转播轨（B 方案，2026-09-30 用户拍板）—— 与 [ScreenShareController]
 * 同构，唯一区别是**采集源不是屏幕，而是自播播放器的画面**：
 *
 *  - 屏幕分享：MediaProjection → VirtualDisplay → （helper 的 SurfaceTexture）→ 轨
 *  - 本控制器：TheaterPlayer 解码 → **helper 的 SurfaceTexture** → 轨
 *
 * 房主是唯一播放器：观众只收这一条视频轨（与屏幕分享同一条显示通路，观众端零改动），
 * 画面里没有任何手机 UI；观众的控制指令回传 → TheaterPlayer 执行（指挥官已在）。
 * 本地画面 = 这条轨的回显（CinemaScreen 的 VideoLayer 分支）——"你看到的正是观众看到的"。
 *
 * 帧桥机制（照 ScreenCapturerAndroid 的路子，去掉 MediaProjection）：
 *  helper.getSurfaceTexture() 包成 Surface 给播放器 → helper.startListening 分发
 *  已包装好的 VideoFrame → capturerObserver.onFrameCaptured → 编码发给观众。
 */
class PlayerShareController(private val context: Context) {

    /** 与 ScreenShareController 同型：B 没有"系统撤销"，字段保留以兼容同一套回调位。 */
    var onStoppedBySystem: (reason: String) -> Unit = {}

    private var videoSource: org.webrtc.VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: PlayerCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    /** 当前帧桥尺寸（重建判据）与节流帧率。 */
    private var curW = 0
    private var curH = 0
    private var lastFps = 30

    /**
     * 起轨。[videoW/videoH] 是**视频真实尺寸**（调用方等 onVideoSizeChanged 探到再调，
     * 见 CallSession.attachTheaterPlayback）—— 帧桥缓冲必须一次到位：运行中改
     * SurfaceTexture 缓冲实测会把解码器投帧整个弄断（改完只剩 1 帧，2026-10-06）。
     * 之后尺寸再变（换片竖横切换）走 [rebuildBridge] 整链重建。
     */
    fun start(fps: Int = 30, videoW: Int = 1920, videoH: Int = 1080): VideoTrack? {
        if (videoTrack != null) return videoTrack
        lastFps = fps
        return runCatching {
            val source = RtcEngine.factory.createVideoSource(true, false)
            val helper = SurfaceTextureHelper.create(
                "TheaterCaptureThread", RtcEngine.eglBase.eglBaseContext,
            )
            val cap = PlayerCapturer()
            cap.initialize(helper, context, source.capturerObserver)
            curW = videoW
            curH = videoH
            cap.startCapture(videoW, videoH, fps)
            TheaterPlayer.sizeSink = { w, h ->
                if (w != curW || h != curH) rebuildBridge(w, h)
            }
            val track = RtcEngine.factory.createVideoTrack("theater", source)
            videoSource = source
            videoTrack = track
            capturer = cap
            surfaceHelper = helper
            TheaterPlayer.relaying = true
            Log.i("PlayerShare", "转播轨已建 ${videoW}×$videoH fps=$fps ${track.id()}")
            track
        }.getOrElse {
            Log.w("PlayerShare", "转播轨建失败: ${it.message}")
            null
        }
    }

    /**
     * 尺寸变化（换片竖↔横 / 换清晰度档）：**整条帧桥链重建** —— 全新
     * SurfaceTextureHelper/SurfaceTexture，播放器输出切到新面。绝不改旧
     * SurfaceTexture 的缓冲（实测断帧流）；重建瞬间画面闪一帧可接受。
     */
    private fun rebuildBridge(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        Log.i("PlayerShare", "尺寸变化 ${curW}×$curH → ${w}×$h，重建帧桥")
        runCatching { capturer?.stopCapture() }   // 播放器输出暂回 UI 面
        runCatching { surfaceHelper?.dispose() }
        val helper = SurfaceTextureHelper.create(
            "TheaterCaptureThread", RtcEngine.eglBase.eglBaseContext,
        )
        surfaceHelper = helper
        capturer?.rebind(helper)
        capturer?.startCapture(w, h, lastFps)
        curW = w
        curH = h
    }

    fun stopCapture() {
        runCatching { capturer?.stopCapture() }
    }

    /** 整体释放（收厅/会话结束）。帧停、播放器解绑、helper 释放。 */
    fun release() {
        TheaterPlayer.sizeSink = null
        stopCapture()
        TheaterPlayer.relaying = false
        runCatching { videoTrack?.dispose() }
        videoTrack = null
        runCatching { videoSource?.dispose() }
        videoSource = null
        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null
        capturer = null
        Log.i("PlayerShare", "转播轨已释放")
    }

    /**
     * 帧桥：播放器 → helper 的 SurfaceTexture → 已包装好的 VideoFrame → observer。
     *
     * frame 的所有权走 observer（Legacy 侧 onFrameCaptured 会接管 release），
     * sink 不再二次 release —— 与 ScreenCapturerAndroid 同一约定，跑起来看日志验证。
     */
    private class PlayerCapturer : VideoCapturer {
        private var helper: SurfaceTextureHelper? = null
        private var observer: CapturerObserver? = null
        private var surface: Surface? = null
        private var frames = 0L

        override fun initialize(
            surfaceTextureHelper: SurfaceTextureHelper?,
            applicationContext: Context?,
            capturerObserver: CapturerObserver?,
        ) {
            helper = surfaceTextureHelper
            observer = capturerObserver
        }

        override fun startCapture(width: Int, height: Int, fps: Int) {
            val h = helper ?: return
            // 两个缺一不可（都实测踩过）：
            // ① SurfaceTexture 默认缓冲 0×0 → MediaCodec 投帧不触发 onFrameAvailable；
            // ② helper 不 setTextureSize 就不分配输出纹理 → 分发链静默
            //    （它的异常串就写着 "Texture size has not been set."）。
            h.surfaceTexture.setDefaultBufferSize(width, height)
            runCatching { h.setTextureSize(width, height) }
            val sp = Surface(h.surfaceTexture)
            surface = sp
            h.startListening { frame ->
                frames++
                if (frames % 300 == 1L) Log.i("PlayerShare", "转播帧 #$frames")
                observer?.onFrameCaptured(frame)
            }
            // 播放器输出切到 helper（TheaterPlayer 会把 UI 纹理面让出来，本地画面改走轨回显）
            TheaterPlayer.attachExternalSurface(sp)
            Log.i("PlayerShare", "帧桥已接通 ${width}x$height@$fps")
        }

        override fun stopCapture() {
            TheaterPlayer.attachExternalSurface(null)
            runCatching { helper?.stopListening() }
            runCatching { surface?.release() }
            surface = null
            Log.i("PlayerShare", "帧桥已断（共 $frames 帧）")
        }

        override fun changeCaptureFormat(width: Int, height: Int, fps: Int) = Unit

        /** 重建帧桥后把新 helper 换进来（observer 等不变）。 */
        fun rebind(newHelper: SurfaceTextureHelper?) {
            helper = newHelper
        }

        override fun dispose() { stopCapture() }
        override fun isScreencast(): Boolean = true
    }
}
