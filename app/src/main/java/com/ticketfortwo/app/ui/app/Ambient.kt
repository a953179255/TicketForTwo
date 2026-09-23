package com.ticketfortwo.app.ui.app

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ticketfortwo.app.ui.theme.Ink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户自定义壁纸：全局状态 + SharedPreferences 持久化（选一次，重启后仍在）。
 * path = null 表示用程序默认的环境底（渐变 + 光斑 + 点阵）。
 */
object AppWallpaper {
    private const val PREF = "wallpaper"
    private const val KEY = "path"
    private val _path = MutableStateFlow<String?>(null)
    val path: StateFlow<String?> = _path.asStateFlow()

    fun load(context: Context) {
        _path.value = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
    }

    fun set(context: Context, path: String?) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, path).apply()
        _path.value = path
    }
}

/**
 * 环境底：给玻璃提供**可折射的内容**。
 *
 * 这条不是审美偏好，是实测结论 —— 效果图第一版换上 backdrop 的 lens 折射 + vibrancy 之后，
 * 卡片上只剩一圈青/品红的色散"描边"，比不加还难看。原因是背后是近乎纯平的暗底：
 * 折射没有可形变的纹理、提饱和没有可增强的色相，两个效果同时失效。
 *
 * 所以默认刻意铺多色 radial 团 + 高频点阵。**点阵是关键**，它给边缘折射提供看得见的扭曲。
 * 用户也可在玻璃实验室里换成自定义图片（[AppWallpaper]）—— 图案的细节越丰富，
 * 磨砂和折射的对比越明显。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val path by AppWallpaper.path.collectAsState()
    if (path != null) {
        CustomWallpaperImage(path!!, modifier)
        return
    }
    DefaultAmbient(modifier)
}

/** 用户自定义壁纸：按屏幕高度降采样解码，防大图 OOM。 */
@Composable
private fun CustomWallpaperImage(path: String, modifier: Modifier) {
    val bmp = remember(path) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val imgH = bounds.outHeight ?: 0
            while (imgH / (sample * 2) >= 2400) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier,
        )
    } else {
        Box(modifier.background(Color(0xFF0B0E14)))
    }
}

@Composable
private fun DefaultAmbient(modifier: Modifier) {
    val density = LocalDensity.current
    val spacingPx = with(density) { 13.dp.toPx() }
    val dotPx = with(density) { 1.1.dp.toPx() }

    Canvas(modifier) {
        val w = size.width
        val h = size.height

        // 基础竖向渐变：近黑深蓝
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B0E14), Color(0xFF12161F))))

        // 四团环境光：保证任意位置的玻璃身后都至少有一种色相可提饱和
        radialBlob(w, h, w * 0.16f, h * 0.10f, w * 0.62f, Ink.AmbientBlue.copy(alpha = 0.42f))
        radialBlob(w, h, w * 0.88f, h * 0.26f, w * 0.55f, Ink.AmbientMagenta.copy(alpha = 0.34f))
        radialBlob(w, h, w * 0.46f, h * 0.93f, w * 0.70f, Ink.AmbientTeal.copy(alpha = 0.36f))
        radialBlob(w, h, w * 0.74f, h * 0.62f, w * 0.34f, Ink.AmbientAmber.copy(alpha = 0.26f))

        // 高频点阵：折射的"刻度尺"。没有它，lens 压在纯色上等于没开。
        var y = spacingPx
        while (y < h) {
            var x = spacingPx
            while (x < w) {
                drawCircle(Color.White.copy(alpha = 0.085f), radius = dotPx, center = Offset(x, y))
                x += spacingPx
            }
            y += spacingPx
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.radialBlob(
    canvasW: Float,
    canvasH: Float,
    cx: Float,
    cy: Float,
    radius: Float,
    color: Color,
) {
    // 用矩形铺满整块画布，颜色由 radialGradient 自身衰减到透明，无需裁剪
    drawRect(
        brush = Brush.radialGradient(
            center = Offset(cx, cy),
            radius = radius,
            colorStops = arrayOf(0f to color, 1f to Color.Transparent),
        ),
        size = androidx.compose.ui.geometry.Size(canvasW, canvasH),
    )
}
