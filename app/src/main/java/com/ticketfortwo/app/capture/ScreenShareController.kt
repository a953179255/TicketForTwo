package com.ticketfortwo.app.capture

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
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
        startFps = fps
        startScale = scale
        val g = displayGeometry(scale, fps)
        lastGeo = g

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
        watchRotation()

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

    /**
     * 设置页中途改了分辨率/帧率：按新档重算几何、**更新转向监听的基准值**
     * （startScale/startFps 是 watchRotation 的参照，不改它转一次屏就退回旧档）、
     * 再热改采集格式 —— changeCaptureFormat 不动 m-line，无需重新协商。
     */
    fun applyQuality(scale: Float, fps: Int) {
        startScale = scale
        startFps = fps
        val g = displayGeometry(scale, fps)
        if (g == lastGeo) return
        lastGeo = g
        changeFormat(g.width, g.height, fps)
        Log.i(tag, "热改采集格式 -> ${g.width}x${g.height}@${fps}fps")
    }

    fun stopCapture() {
        runCatching { capturer?.stopCapture() }
    }

    fun release() {
        unwatchRotation()
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

    // ---- 转向 ------------------------------------------------------------

    private var startScale = 0.75f
    private var startFps = 30
    private var lastGeo: CaptureGeometry? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    /**
     * 监听显示转向，转了就把采集尺寸重设。
     *
     * 为什么必须有：`startCapture(w, h, fps)` 的宽高是**开始那一刻**读的死值
     * （[displayGeometry] 只在 [start] 里调一次）。房主中途横屏打个游戏，真实显示
     * 从 810×1800 变成 1800×810，而 VirtualDisplay 还按竖的尺寸要帧 ——
     * 结果就是观众看到画面被转了 90°、或者只截到中间一条。
     * 上一版这里连监听都没有：`changeFormat()` 定义了却全仓库无人调用。
     *
     * 用 [DisplayManager.DisplayListener] 而不是 OrientationEventListener：
     * 前者在"显示配置真的变了"时才响，后者给的是连续角度，还得自己定死区与防抖。
     * 分享跑在 Service 里，也没有 Activity.onConfigurationChanged 可用。
     */
    private fun watchRotation() {
        if (displayListener != null) return
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != android.view.Display.DEFAULT_DISPLAY) return
                val g = runCatching { displayGeometry(startScale, startFps) }.getOrNull() ?: return
                if (g == lastGeo) return   // 折叠屏展开、DPI 微调等也会触发，尺寸没变就别折腾
                Log.i(tag, "显示转向：$lastGeo -> $g，重设采集")
                lastGeo = g
                changeFormat(g.width, g.height, startFps)
            }
        }
        displayListener = l
        // 回调要落在一条有 Looper 的线程上；主线程够用（这里只做一次尺寸比较）
        dm.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        Log.i(tag, "转向监听已挂上")
    }

    private fun unwatchRotation() {
        val l = displayListener ?: return
        displayListener = null
        runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(l)
        }
    }
}
