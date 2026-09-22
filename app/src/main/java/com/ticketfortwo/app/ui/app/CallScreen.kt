package com.ticketfortwo.app.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.rtc.RtcEngine
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * 视频层。
 *
 * libwebrtc 落在 SurfaceView 上，这决定了一条 UI 事实：**SurfaceView 的内容抓不到**
 * （backdrop 维护者在 issue #98 亲口确认，haze 同结论），所以浮在它上面的顶部条与控制岛
 * 只能用 scrim，不能是采样玻璃 —— 见下面两处 `refract = false`。
 */
@Composable
fun VideoLayer(
    track: VideoTrack?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val renderer = remember { SurfaceViewRenderer(context) }
    var ready by remember { mutableStateOf(false) }

    Box(modifier) {
        AndroidView(
            factory = {
                RtcEngine.init(context)
                renderer.init(RtcEngine.eglBase.eglBaseContext, null)
                ready = true
                renderer
            },
            onRelease = { renderer.release() },
            modifier = Modifier.fillMaxSize(),
        )
    }
    DisposableEffect(track, ready) {
        val t = track
        if (t != null && ready) t.addSink(renderer)
        onDispose { if (t != null && ready) t.removeSink(renderer) }
    }
}

/**
 * 分享中 / 观看中：整屏视频 + 顶部状态条 + 底部控制岛。
 *
 * 房主看到的是自己的采集预览（本地零延迟，只用于"确认到底在播什么"），
 * 观众看到的是远端画面。两者布局一致，省一套 UI 也避免两边长歪。
 */
@Composable
fun CallScreen(
    backdrop: LayerBackdrop,
    track: VideoTrack?,
    isHost: Boolean,
    peerLabel: String,
    micOn: Boolean,
    onToggleMic: () -> Unit,
    latencyMs: Int?,
    netLabel: String,
    onStop: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink.Video)) {
        VideoLayer(track, Modifier.fillMaxSize())

        // 顶部状态条：浮在视频上 → scrim + 边缘光，不采样
        GlassPanel(
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 36.dp, start = 12.dp, end = 12.dp),
            radius = GlassDimens.radiusIsland,
            surfaceAlpha = 0.72f,
            refract = false,
            content = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(7.dp)
                            .height(7.dp)
                            .background(Ink.Error, CircleShape)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isHost) "正在分享" else "正在观看",
                        fontSize = 11.5.sp,
                        color = Ink.TextHi,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(peerLabel, fontSize = 11.5.sp, color = Ink.TextMid)
                }
            },
        )

        ControlIsland(
            backdrop = backdrop,
            micOn = micOn,
            onToggleMic = onToggleMic,
            onStop = onStop,
            latencyLabel = latencyMs?.let { "$it" } ?: "—",
            netLabel = netLabel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = GlassDimens.islandBottom),
        )
    }
}
