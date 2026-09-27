package com.ticketfortwo.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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

    // 文本 —— Spotify
    val TextHi = Color(0xFFFFFFFF)
    val TextMid = Color(0xFFB3B3B3)
    val TextLow = Color(0xFF7A7A7A)

    // 强调：暗底上必须用 #2997FF，#0066CC 在暗底会消失 —— Apple
    val AccentOnDark = Color(0xFF2997FF)
    val AccentSolid = Color(0xFF0071E3)

    // 语义色 —— Spotify（mute 语义库里没有）
    val Live = Color(0xFF1ED760)   // 发言中 / 活跃
    val Error = Color(0xFFF3727F)  // 错误 / 断连
    val Warn = Color(0xFFFFA42B)   // 弱网警告
    val Info = Color(0xFF539DF5)   // 信息

    /**
     * 环境底的四团色相。不属于 DESIGN.md token，是为"让玻璃有东西可折射"而配的
     * —— 见 ui/app/Ambient.kt 的注释。取色原则：色相拉开、饱和度高于表面但不与语义色撞车。
     */
    val AmbientBlue = Color(0xFF2B4D80)
    val AmbientMagenta = Color(0xFF6B2F60)
    val AmbientTeal = Color(0xFF1E5C53)
    val AmbientAmber = Color(0xFF7A5320)
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

@Composable
fun TicketForTwoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
