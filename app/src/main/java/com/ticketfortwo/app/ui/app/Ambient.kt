package com.ticketfortwo.app.ui.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
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
 * 壁纸压暗层。
 *
 * 默认壁纸是一张细节密集、明暗跨度很大的图案（暗处近乎纯黑、亮处是浅灰黄）。
 * **不在玻璃里的文字**（页面副标题、段落标题）总会有一段落在亮块上 ——
 * 实测设置页副标题的最坏对比度只有 **2.1:1**（可读性标准 4.5:1），那几段字直接糊掉。
 * 加这一层后最坏 **5.3:1** 达标，而图案仍然看得清楚。
 *
 * 关键是**画在采样宿主内**（壁纸之上、玻璃之下）：这样玻璃采到的是压暗后的底，
 * 玻璃自身的白雾提亮不受影响 ⇒ 底变暗、玻璃不变，字就出来了。
 * 若画在宿主外，会把玻璃连同一起压暗 = 全屏调暗，观感更闷且不解决问题。
 *
 * 这个数就是"看清文字"与"看清壁纸"之间的平衡旋钮：`0f` = 完全关掉。
 */
private const val WALLPAPER_SCRIM = 0.40f

/**
 * 环境底：给玻璃提供**可折射的内容**。
 *
 * 三层来源，优先级从上到下：
 *  1. 用户自己在玻璃实验室换的图（[AppWallpaper]）—— 换过就一直用他的；
 *  2. **打包进 App 的默认壁纸**（`assets/wallpaper.jpg`）—— 没换过时用它，这是绝大多数人看到的；
 *  3. 程序化环境底 [DefaultAmbient] —— 只在上面两个都解不出来时兜底（图挂了也不会白屏）。
 *
 * 第 3 层为什么留着：早先只有它一个，是实测逼出来的 —— 换成 backdrop 的 lens 折射 + vibrancy
 * 之后，压在近乎纯平的暗底上的卡片只剩一圈青/品红色散"描边"，比不加还难看：
 * 折射没有可形变的纹理、提饱和没有可增强的色相，两个效果同时失效。所以它刻意铺了多色
 * radial 团 + 高频点阵（点阵是关键，给边缘折射提供看得见的扭曲）。
 *
 * 默认壁纸同理选了**细节很密**的一张：图案越细，磨砂和折射的对比越明显。
 * 但它毕竟是"图"，可能损坏或没打进去，所以程序化那一层不删，当兜底。
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val path by AppWallpaper.path.collectAsState()
    val bmp = remember(path, context) { decodeWallpaper(context, path) }
    Box(modifier) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = WALLPAPER_SCRIM)))
        } else {
            // 程序化底本来就是近黑的，不需要再压暗
            DefaultAmbient(Modifier.matchParentSize())
        }
    }
}

/** 打包进 App 的默认壁纸。放 assets 而不是 res/drawable：绕开密度换算，尺寸所见即所得。 */
private const val ASSET_WALLPAPER = "wallpaper.jpg"

/**
 * 解码壁纸。path = null 表示用打包的默认壁纸。
 *
 * 先只读边界（inJustDecodeBounds）再按需降采样 —— 用户可能选一张几千万像素的相机原图，
 * 直接整张解码会 OOM。
 */
private fun decodeWallpaper(context: Context, path: String?): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    decodeWallpaperStream(context, path, bounds)
    var sample = 1
    val h = bounds.outHeight
    while (h / (sample * 2) >= 2400) sample *= 2
    decodeWallpaperStream(context, path, BitmapFactory.Options().apply { inSampleSize = sample })
}.getOrNull()

private fun decodeWallpaperStream(
    context: Context,
    path: String?,
    opts: BitmapFactory.Options,
): Bitmap? = if (path != null) {
    BitmapFactory.decodeFile(path, opts)
} else {
    // assets 的文件流不能只开一次就用两次（第二次已经读到末尾），每次重新 open ——
    // 上面的 decodeWallpaper 因此会 open 两遍（一遍量边界、一遍真解）。
    context.assets.open(ASSET_WALLPAPER).use { BitmapFactory.decodeStream(it, null, opts) }
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
