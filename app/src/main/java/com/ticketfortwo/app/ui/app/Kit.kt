package com.ticketfortwo.app.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.glass.LiquidGlassButton
import com.ticketfortwo.app.ui.glass.appLayer
import com.ticketfortwo.app.ui.glass.rememberAppBackdrop
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import com.ticketfortwo.app.ui.theme.TicketForTwoTheme
import com.kyant.backdrop.backdrops.LayerBackdrop

/**
 * 应用根：采样宿主 + 玻璃浮层。
 *
 * 结构照 HaoAI 的 `BrowserScreen.kt:102` —— **采样层里只放被采样的内容，
 * 玻璃浮层留在它外面**。玻璃若进采样层会形成 RenderNode 自引用（HaoAI 那边
 * 表现为弹窗内 drawBackdrop 直接崩，以及"整屏蒙一层白纱"）。
 *
 * 深色优先：这是个看视频/看屏幕的 App，亮底会在暗环境里刺眼，
 * 且 Apple 的 DESIGN.md 明确 surface-black 专用于视频背景。
 */
@Composable
fun TicketForTwoAppRoot(content: @Composable (LayerBackdrop) -> Unit) {
    TicketForTwoTheme(darkTheme = true) {
        val backdrop = rememberAppBackdrop(dark = true)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF0B0E14))
        ) {
            Box(Modifier.matchParentSize().appLayer(backdrop)) {
                AmbientBackground(Modifier.matchParentSize())
            }
            content(backdrop)
        }
    }
}

// ───────────────────────────── 原子组件 ─────────────────────────────

/**
 * 主行动按钮（pill）。
 *
 * 注意 `refract = false` 的用法：库的 lens 与 Highlight **需要 API 33**，
 * 本项目 minSdk 已定 33 所以默认开；但低端机上同屏玻璃块要 ≤ 3，
 * 分享进行中（编码器在抢 GPU）时控制岛会往下收档。
 */
@Composable
fun PrimaryPill(
    text: String,
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = true,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(percent = 50),
        enabled = enabled,
        surfaceColor = if (filled) Ink.AccentSolid else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) Color.White else Ink.TextHi,
        )
    }
}

/** 圆形图标按钮：44dp 是 Apple 的 button-icon-circular，同时也是触控命中下限。 */
@Composable
fun CircleControl(
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.size(GlassDimens.controlSize),
        shape = CircleShape,
        content = { content() },
    )
}

/** 玻璃卡片：内容承载面板。floating=true 走库原生三件套而不是手画描边。 */
@Composable
fun GlassCardPanel(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    content: @Composable () -> Unit,
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier,
        radius = GlassDimens.radiusCard,
        surfaceAlpha = GlassDimens.CARD_SURFACE_ALPHA,
        floating = floating,
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Ink.TextLow,
    )
}

@Composable
fun StatusChip(
    text: String,
    tone: ChipTone = ChipTone.Neutral,
    modifier: Modifier = Modifier,
) {
    val fg = when (tone) {
        ChipTone.Ok -> Color(0xFF9BE0AC)
        ChipTone.Warn -> Color(0xFFFFD79A)
        ChipTone.Bad -> Color(0xFFFFB4BB)
        ChipTone.Neutral -> Ink.TextMid
    }
    val bg = when (tone) {
        ChipTone.Ok -> Ink.Live.copy(alpha = 0.13f)
        ChipTone.Warn -> Ink.Warn.copy(alpha = 0.13f)
        ChipTone.Bad -> Ink.Error.copy(alpha = 0.13f)
        ChipTone.Neutral -> Color.White.copy(alpha = 0.10f)
    }
    Box(
        modifier
            .background(bg, RoundedCornerShape(percent = 50))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 11.sp, color = fg, maxLines = 1)
    }
}

enum class ChipTone { Neutral, Ok, Warn, Bad }

/**
 * 悬浮控制岛。
 *
 * 它浮在视频之上 —— 而 **SurfaceView 的内容抓不到**（backdrop 维护者在 issue #98
 * 亲口确认，haze 同结论），所以这里不能用 backdrop 采样，只能退化成 scrim。
 * GlassPanel 传 `refract = false` 走本地磨砂路径，视觉上就是"半透明暗底 + 边缘光"。
 */
@Composable
fun ControlIsland(
    backdrop: LayerBackdrop,
    micOn: Boolean,
    onToggleMic: () -> Unit,
    onStop: () -> Unit,
    latencyLabel: String,
    netLabel: String,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier,
        radius = GlassDimens.radiusIsland,
        surfaceAlpha = 0.72f,
        refract = false,
        content = {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircleControl(onClick = onToggleMic, backdrop = backdrop) {
                    Icon(
                        if (micOn) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (micOn) "静音" else "取消静音",
                        tint = if (micOn) Color(0xFF04160A) else Ink.TextHi,
                        modifier = Modifier.size(GlassDimens.iconSize),
                    )
                }
                VDivider()
                Metric(label = latencyLabel, sub = netLabel)
                VDivider()
                CircleControl(onClick = onStop, backdrop = backdrop) {
                    Icon(
                        Icons.Filled.Stop, contentDescription = "停止分享",
                        tint = Color.White, modifier = Modifier.size(GlassDimens.iconSize),
                    )
                }
            }
        },
    )
}

@Composable
private fun VDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 26.dp)
            .background(Color.White.copy(alpha = 0.18f))
    )
}

@Composable
private fun Metric(label: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
        Text(sub, fontSize = 10.5.sp, color = Ink.TextMid)
    }
}

/** 页面骨架：顶部留状态栏，内容自己排。 */
@Composable
fun PageScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = GlassDimens.screenH),
        verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
    ) {
        content()
    }
}

@Composable
fun Headline(text: String, sub: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1)) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.TextHi)
        if (sub != null) {
            Text(sub, fontSize = 13.5.sp, color = Ink.TextMid, textAlign = TextAlign.Start)
        }
    }
}

/** 撑开剩余空间。weight 是 ColumnScope 的扩展，所以本函数也必须挂在 ColumnScope 上。 */
@Composable
fun ColumnScope.SpacerWeight(weight: Float = 1f) {
    Spacer(Modifier.height(0.dp).weight(weight))
}
