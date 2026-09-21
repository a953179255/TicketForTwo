package com.ticket2.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 颜色 token。取值出处见 PLAN.md §6.2 —— 抽自 awesome-design-md 的
 * Apple / Spotify / Figma 三份 DESIGN.md；凡标「自定」的在那些文件里没有出处。
 *
 * 注意：专有字体（SF Pro / SpotifyMixUI / figmaSans）不可打进 APK，
 * 这里只用系统默认，中文也走系统字体。
 */
internal object Ink {
    // 三层暗面 —— Spotify
    val Surface0 = Color(0xFF121212)
    val Surface1 = Color(0xFF181818)
    val Surface2 = Color(0xFF1F1F1F)

    // 视频区专用纯黑 —— Apple: surface-black 明确"专用于 video player 背景"
    val Video = Color(0xFF000000)

    // 文本 —— Spotify
    val TextHi = Color(0xFFFFFFFF)
    val TextMid = Color(0xFFB3B3B3)
    val TextLow = Color(0xFF7A7A7A)

    // 强调：暗底上必须用 #2997FF，#0066CC 在暗底会消失 —— Apple
    val AccentOnDark = Color(0xFF2997FF)
    val AccentSolid = Color(0xFF0071E3)

    // 语义色 —— Spotify（mute 语义库里没有，见 Dimens 注释）
    val Live = Color(0xFF1ED760)   // 发言中 / 活跃
    val Error = Color(0xFFF3727F)  // 错误 / 断连
    val Warn = Color(0xFFFFA42B)   // 弱网警告
    val Info = Color(0xFF539DF5)   // 信息

    // 浮层遮罩 —— Figma: "Black used at ~60% opacity behind video-overlay surfaces"
    val VideoScrim = Color(0x99000000)
    val StrongScrim = Color(0xC7000000)
}

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Ink.AccentOnDark,
    onPrimary = Color.White,
    secondary = Ink.Info,
    background = Ink.Surface0,
    onBackground = Ink.TextHi,
    surface = Ink.Surface1,
    onSurface = Ink.TextHi,
    surfaceVariant = Ink.Surface2,
    onSurfaceVariant = Ink.TextMid,
    outline = Ink.TextLow,
    error = Ink.Error,
    onError = Color.Black,
)

private val LightColors: ColorScheme = lightColorScheme(
    primary = Ink.AccentSolid,
    secondary = Ink.Info,
    background = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECECEE),
    onSurface = Color(0xFF1D1D1F),
    onBackground = Color(0xFF1D1D1F),
    onSurfaceVariant = Color(0xFF4B4B50),
    error = Color(0xFFB3261E),
)

/**
 * 尺寸 token。44dp 圆形控件来自 Apple `button-icon-circular 44×44px`，
 * 它同时也是触控命中下限；圆角与间距取自 Apple 的 scale。
 */
@Immutable
data class Dimens(
    /** 圆形图标按钮直径，同时是命中区下限 */
    val controlSize: Dp = 44.dp,
    val controlSizeLarge: Dp = 56.dp,
    val iconSize: Dp = 22.dp,
    /** 控制岛：底部留白 */
    val islandBottom: Dp = 24.dp,
    /** 视频上浮层卡片与控制岛之间的间距 */
    val overlayGap: Dp = 16.dp,
    val radiusSm: Dp = 8.dp,
    val radiusMd: Dp = 12.dp,
    val radiusLg: Dp = 16.dp,
    val radiusCard: Dp = 26.dp,
    val sp1: Dp = 4.dp,
    val sp2: Dp = 8.dp,
    val sp3: Dp = 12.dp,
    val sp4: Dp = 16.dp,
    val sp5: Dp = 20.dp,
    val sp6: Dp = 24.dp,
    val sp8: Dp = 32.dp,
)

/**
 * 动效时长：**awesome-design-md 全库 74 份 DESIGN.md 里没有 motion/duration token**
 * （只有 Starbucks 给过一条 cubic-bezier），所以这几个值是自定的，别当有出处。
 * 弹性按压的曲线借用那唯一一条 spring。
 */
@Immutable
data class Motion(
    val fastMs: Int = 120,
    val midMs: Int = 220,
    val slowMs: Int = 380,
)

val LocalDimens = staticCompositionLocalOf { Dimens() }
val LocalMotion = staticCompositionLocalOf { Motion() }

@Composable
fun Ticket2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDimens provides Dimens(),
                LocalMotion provides Motion(),
                content = content,
            )
        },
    )
}
