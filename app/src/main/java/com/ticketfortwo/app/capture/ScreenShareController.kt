package com.ticketfortwo.app.capture

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import android.view.WindowManager
import com.ticketfortwo.app.rtc.RtcEngine
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * 屏幕采集：MediaProjection → libwebrtc 的 [ScreenCapturerAndroid] → 一条视频轨。
 *
 * 用 [ScreenCapturerAndroid] 而不是自己搭 VirtualDisplay，因为它内部已经处理了
 * SurfaceTextureHelper / EGL / 帧回调，而且它的构造要求传 [MediaProjection.Callback]，
 * 天然满足 Android 14 "必须注册 callback 否则 createVirtualDisplay 抛 IllegalStateException"。
 *
 * 分辨率取真实显示尺寸按 [scale] 缩放：直接采 1080p 再软编会跟游戏抢 CPU，
 * 而观众端多半是小屏，降一档更划算。
 */
class ScreenShareController(
    private val context: Context,
    private val permissionResultData: Intent,
) {
    private val tag = "ScreenShare"

    var videoSource: VideoSource? = null
        private set
    var videoTrack: VideoTrack? = null
        private set
    private var capturer: ScreenCapturerAndroid? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    /** 系统因用户从通知/状态栏 chip 停止共享、或锁屏时回调到这里。 */
    var onStoppedBySystem: (reason: String) -> Unit = {}

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.w(tag, "MediaProjection stopped by system")
            // Android 15 QPR1+：锁屏就会走到这里。这不是 bug，是系统规则。
            onStoppedBySystem("系统已停止共享（可能是锁屏或从状态栏点了停止）")
            release()
        }
    }

    data class CaptureGeometry(val width: Int, val height: Int, val dpi: Int)

    /** 读真实显示尺寸，按比例缩放并取偶数（编码器要求宽高为偶数）。 */
    fun displayGeometry(scale: Float = 0.75f, fps: Int = 30): CaptureGeometry {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = wm.currentWindowMetrics.bounds
        val w = even((m.width() * scale).toInt())
        val h = even((m.height() * scale).toInt())
        // WindowMetrics 上没有 densityDpi，DPI 只能从 resources 的 DisplayMetrics 取
        val dpi = context.resources.displayMetrics.densityDpi
        Log.i(tag, "display=${m.width()}x${m.height()} -> capture=${w}x$h @${fps}fps dpi=$dpi")
        return CaptureGeometry(w, h, dpi)
    }

    /**
     * 启动采集。[degradationPreference] 决定拥塞时先丢帧还是先降分辨率：
     * 看文档/文字要 MAINTAIN_RESOLUTION，看动作快的游戏要 MAINTAIN_FRAMERATE。
     */
    fun start(fps: Int = 30, scale: Float = 0.75f): VideoTrack? {
        if (videoTrack != null) return videoTrack
        val g = displayGeometry(scale, fps)

        // enableCpuOveruseDetection=true：让 libwebrtc 自己按 CPU 压力降档，
        // 这正是"边玩游戏边分享不卡手"的关键。
        val source = RtcEngine.factory.createVideoSource(
            /* enableCpuOveruseDetection = */ true,
            /* satisfiesContentHint = */ false,
        )
        val helper = SurfaceTextureHelper.create("CaptureThread", RtcEngine.eglBase.eglBaseContext)
        val cap = ScreenCapturerAndroid(permissionResultData, projectionCallback)

        cap.initialize(helper, context, source.capturerObserver)
        cap.startCapture(g.width, g.height, fps)

        val track = RtcEngine.factory.createVideoTrack("screen", source)
        videoSource = source
        videoTrack = track
        capturer = cap
        surfaceHelper = helper
        Log.i(tag, "capture started ${g.width}x${g.height}@${fps}")
        return track
    }

    fun changeFormat(width: Int, height: Int, fps: Int) {
        capturer?.changeCaptureFormat(even(width), even(height), fps)
    }

    fun stopCapture() {
        runCatching { capturer?.stopCapture() }
    }

    fun release() {
        stopCapture()
        runCatching { capturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { videoTrack?.dispose() }
        runCatching { videoSource?.dispose() }
        capturer = null
        surfaceHelper = null
        videoTrack = null
        videoSource = null
    }

    /** 编码器要求宽高为偶数，奇数会直接 startCapture 失败。 */
    private fun even(v: Int): Int = if (v % 2 == 0) v else v - 1
}
