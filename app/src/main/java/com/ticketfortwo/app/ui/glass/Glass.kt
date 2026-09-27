package com.ticketfortwo.app.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.DpOffset
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.ticketfortwo.app.ui.theme.isDarkTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 位于玻璃采样层（appLayer/layerBackdrop）内的内容层应设为 false，使内部玻璃组件
 * 退化为本地磨砂绘制（不 drawBackdrop 采样），避免自引用渲染循环崩溃。
 * 默认 true；内容层用 CompositionLocalProvider(LocalGlassRefract provides false) 包裹即可。
 */
val LocalGlassRefract = compositionLocalOf { true }

/**
 * 玻璃表面着色：浅色主题用白雾，深色主题用黑雾（白磨砂在暗色下发白发亮）。
 * 深色 alpha 需要放大补偿黑雾的低对比度。
 */
@Composable
internal fun glassSurfaceColor(alpha: Float): Color {
    val dark = isDarkTheme()
    return if (dark) Color(0xFF0A0D12).copy(alpha = (alpha * 1.8f).coerceAtMost(0.94f))
    else Color.White.copy(alpha = alpha)
}

/**
 * 玻璃描边色（**手画描边**）。
 *
 * ⚠️ 2026-09-21 结论：**手画描边不适合用在液态玻璃上**。上一次改版把它换成
 * `onSurface` 深色发丝线后，真机实测四条边深浅不一（左 186 / 右 188 / **下 225**），
 * 原因是三条结构性冲突：
 * ① 半透明色压在玻璃上，真实深浅由"身后的内容"决定（左边压聊天内容、下边压白卡片）；
 * ② `1.5dp × 2.625 = 3.94px` 落在非整数像素上，抗锯齿摊到 4~5 行，四边取整方向不同；
 * ③ 玻璃自带的 lens 折射亮边（12~20dp 弧）与描边在边缘叠加，一侧被提亮一侧被压暗。
 *
 * ⇒ **浮层面板请改用 `GlassPanel(floating = true)`**，分层由库原生三件套
 * （`highlight` / `shadow` / `innerShadow`）绘制 —— 与折射同源、天然均匀。
 * 本函数只保留给"平坦卡片"用（白描边，与既有观感一致）。
 */
@Composable
internal fun glassBorderColor(alpha: Float): Color {
    val dark = isDarkTheme()
    return if (dark) Color.White.copy(alpha = alpha * 0.35f) else Color.White.copy(alpha = alpha)
}

/** 退化路径（不采样 backdrop、拿不到库阴影）的兜底投影色：环境光。 */
@Composable
private fun glassFallbackShadowAmbient(): Color {
    val dark = isDarkTheme()
    return if (dark) Color.Black.copy(alpha = 0.30f) else Color(0xFF262E36).copy(alpha = 0.10f)
}

/** 退化路径的兜底投影色：主光（决定投影"落点"浓淡）。 */
@Composable
private fun glassFallbackShadowSpot(): Color {
    val dark = isDarkTheme()
    return if (dark) Color.Black.copy(alpha = 0.42f) else Color(0xFF262E36).copy(alpha = 0.16f)
}

@Composable
fun rememberAppBackdrop(dark: Boolean = false): LayerBackdrop {
    return rememberLayerBackdrop {
        if (dark) {
            // 默认暗色渐变（近黑深蓝，与暗色主题背景一致）。
            // 唯一调用点恒传 dark=true；壁纸不再在这里画，由 AmbientBackground
            // 画在采样层（appLayer）里给玻璃提供可折射内容。
            drawRect(Brush.verticalGradient(listOf(Color(0xFF07090D), Color(0xFF0E131B))))
        }
        drawContent()
    }
}

fun Modifier.appLayer(backdrop: LayerBackdrop): Modifier = this.layerBackdrop(backdrop)

@Composable
fun GlassPanel(
    // 放宽为 Backdrop 接口：支持 CombinedBackdrop（玻璃导出合成采样，见抽屉）
    backdrop: com.kyant.backdrop.Backdrop,
    modifier: Modifier = Modifier,
    radius: Dp = 24.dp,
    surfaceAlpha: Float = 0.16f,
    tint: Color? = null,
    shape: Shape? = null,
    lensRadius: Dp = radius,
    blurRadius: Dp = radius / 3f,
    chromaticAberration: Boolean = false,
    /** 折射强度倍数：位移量 = 折射高度 × 此值（演示 App 的比例是 2） */
    lensAmountMul: Float = 2f,
    refract: Boolean? = null,
    border: Boolean = true,
    /**
     * **浮层强化**（2026-09-21 定案）：用于"悬浮在内容之上"的面板（任务面板 / 上下文面板 / 弹层）。
     *
     * 开启后不再手画描边，改用**库原生三件套**表达分层：
     * ① `highlight` 边缘高光（玻璃边缘受光，浅色底上不可见、深色底上是那圈亮边）；
     * ② 调强的 `shadow` 外阴影（真实层级——注意会被"展开/收起"动画容器的裁剪吸收，
     *    在不受裁剪的覆盖层上才完整可见）；
     * ③ `innerShadow` **四边均匀的内暗边**（offset = 0）= 一块有厚度的玻璃板，
     *    替代"深色发丝线"，且画在形状之内、不会被任何裁剪吃掉。
     * 三者由库的着色器绘制、与折射同源 ⇒ **不会出现"四边粗细/深浅不一"**。
     *
     * 注意：库的 `drawBackdrop` 默认**已经**在画 `Highlight.Default`（白 0.5dp）与
     * `Shadow.Default`（24dp / 黑 10%）——浅色背景下白高光看不见、24dp 阴影太柔，
     * 这才是"面板与聊天内容融为一体"的根因，故浮层需要显式调强。
     */
    floating: Boolean = false,
    content: @Composable () -> Unit
) {
    val r = refract ?: LocalGlassRefract.current
    val surface = glassSurfaceColor(surfaceAlpha)
    val darkTheme = isDarkTheme()
    val border2 = glassBorderColor(0.45f)

    // ── 分层元素（库原生三件套）──────────────────────────────────────────────
    // 一律传非空实例（库的形参在不同版本间有 `(() -> X)?` 与 `() -> X` 两种写法，
    // 传非空可同时兼容）；"不画"用 alpha = 0 表达，而非传 null。
    // Highlight.Plain = 演示 App 同款的均匀边缘亮环（Default 是渐变式，玻璃上几乎看不见）
    val highlightLambda: () -> Highlight = remember { { Highlight.Plain } }
    val shadowLambda: () -> Shadow = if (floating) {
        remember(darkTheme) {
            {
                // 浅色：冷灰实投影，把浮层从近白背景上"抬起来"；深色：纯黑更浓
                if (darkTheme) Shadow.Default.copy(
                    radius = 30.dp,
                    offset = DpOffset(0.dp, 8.dp),
                    color = Color.Black.copy(alpha = 0.45f)
                ) else Shadow.Default.copy(
                    radius = 26.dp,
                    offset = DpOffset(0.dp, 7.dp),
                    color = Color(0xFF262E36).copy(alpha = 0.20f)
                )
            }
        }
    } else remember { { Shadow.Default } }
    val innerShadowLambda: () -> InnerShadow = if (floating) {
        remember(darkTheme) {
            {
                // **offset = 0 ⇒ 四条边的内暗边完全对称**，这是对"描边不均匀"的正面回答：
                // 手画描边的不均匀来自三件事——半透明色（深浅被身后内容决定）、
                // 1.5dp×2.625=3.94px 的非整数线宽（抗锯齿摊到 4~5 行）、
                // 以及与折射亮边叠边；而内阴影由库着色器绘制，四边天然对称、且**不会被
                // 展开/收起动画的裁剪吃掉**（内阴影画在形状之内）。
                // 观感 = 一块有厚度的玻璃板：边缘一圈柔和的暗内边，中间透亮。
                if (darkTheme) InnerShadow.Default.copy(
                    radius = 18.dp,
                    offset = DpOffset.Zero,
                    color = Color.Black.copy(alpha = 0.38f)
                ) else InnerShadow.Default.copy(
                    radius = 15.dp,
                    offset = DpOffset.Zero,
                    color = Color(0xFF262E36).copy(alpha = 0.30f)
                )
            }
        }
    } else remember { { InnerShadow.Default.copy(alpha = 0f) } }

    // 退化路径（不采样 backdrop）拿不到库阴影，用系统 shadow 兜底，避免浮层"贴"在内容上
    val fallbackShadowMod = if (floating) {
        Modifier.shadow(
            elevation = 8.dp,
            shape = shape ?: RoundedCornerShape(radius),
            clip = false,
            ambientColor = glassFallbackShadowAmbient(),
            spotColor = glassFallbackShadowSpot()
        )
    } else Modifier
    // 浮层由库三件套表达分层，不再手画描边（半透明描边在玻璃上会四边深浅不一）
    val drawBorder = border && !floating
    val panelModifier = if (r) {
        modifier
            // v7.1 硬裁剪：drawBackdrop 的 blur/lens 与表面填充会溢出圆角外的方形区域
            // （平色背景上呈四角灰块，GlassCard 同款修复——小尺寸圆角面板上最明显）
            //
            // ⚠️ 2026-09-21：**浮层必须跳过这一步**。库的 ShadowNode 把外阴影画在
            // 「节点四周各 radius*2」之外（radius 26dp ⇒ 单边 136px），而本 .clip() 排在
            // drawBackdrop 之前 = 在它外层，会把整圈外阴影裁光（实测：旧版 Modifier.shadow
            // 与本版库阴影在任务面板下缘外侧都是「零投影」）。浮层改用下面的
            // clipEffects 参数把裁剪交给库自身按 shape 处理。
            .then(if (floating) Modifier else Modifier.clip(shape ?: RoundedCornerShape(radius)))
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape ?: RoundedCornerShape(radius) },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    // lens 折射按统一内边距从每条边向内采样，在方角处会产生弧形高光"伪圆角"。
                    // 方角玻璃（如侧栏左缘）传 lensRadius = 0.dp 关闭它，保证角部利落。
                    if (lensRadius > 0.dp) {
                        // 窄环带：强度 = 高度 × 倍数（位移大 ⇒ 边缘弯折锐利）
                        // 对齐 Kyant0 演示 App（GlassPlayground/sheet）的配方：
                        // 折射强度 = 2 × 折射高度（demo: 16/32 与 25.6/51.2），并开 depthEffect。
                        lens(
                            refractionHeight = lensRadius.toPx(),
                            refractionAmount = (lensRadius * lensAmountMul).toPx(),
                            depthEffect = true,
                            chromaticAberration = chromaticAberration
                        )
                    }
                },
                // 库原生分层三件套：边缘高光 + 外阴影（层级）+ 内阴影（厚度）
                highlight = highlightLambda,
                shadow = shadowLambda,
                innerShadow = innerShadowLambda,
                onDrawSurface = {
                    if (!floating) {
                        drawRect(surface)
                        if (tint != null) {
                            drawRect(tint, blendMode = BlendMode.Hue)
                            drawRect(tint.copy(alpha = 0.35f))
                        }
                    } else {
                        // 浮层跳过了外层 Modifier.clip（否则库的外阴影被整圈裁光，见下），
                        // 因此表面填充改为**按 shape 画路径**，方角不再溢出色块。
                        val densityScope: androidx.compose.ui.unit.Density = this
                        val outline = (shape ?: RoundedCornerShape(radius))
                            .createOutline(size, layoutDirection, densityScope)
                        val p = Path()
                        when (outline) {
                            is Outline.Rounded -> p.addRoundRect(outline.roundRect)
                            is Outline.Rectangle -> p.addRect(outline.rect)
                            is Outline.Generic -> p.addPath(outline.path)
                        }
                        drawPath(p, surface)
                        if (tint != null) {
                            drawPath(p, tint, blendMode = BlendMode.Hue)
                            drawPath(p, tint.copy(alpha = 0.35f))
                        }
                    }
                }
            )
            // 发丝描边画在玻璃表面之上（后置 modifier 后绘制），与退化分支观感对齐
            .then(if (drawBorder) Modifier.border(1.5.dp, border2, shape ?: RoundedCornerShape(radius)) else Modifier)
    } else {
        // 位于玻璃采样层内时禁止 drawBackdrop（否则渲染自引用递归崩溃），退化为本地绘制：
        // 本分支实际只有 clip + 表面色 background + 描边 border + 可选 tint 四层叠加，
        // **没有** blur/lens —— blurRadius、lensRadius、chromaticAberration 在此分支不生效
        // （视频上方的调用点本来也采样不到内容，只需要一层 scrim）。
        modifier
            .then(fallbackShadowMod)
            .clip(shape ?: RoundedCornerShape(radius))
            .background(surface)
            .then(if (drawBorder) Modifier.border(1.5.dp, border2, shape ?: RoundedCornerShape(radius)) else Modifier)
            .then(
                if (tint != null) Modifier.background(tint) else Modifier
            )
    }
    Box(panelModifier) {
        content()
    }
}

/**
 * 液态玻璃按压高亮（对齐 Kyant0/AndroidLiquidGlass 官方 Catalog 的 InteractiveHighlight）：
 * - pressProgress：按压缩放进度（spring 回弹）
 * - offset：指尖相对按下点的位移，驱动折射层跟随流动
 * - highlightModifier：表面白色辉光，跟随指尖位置与按压力度（API 33+ 用 AGSL 径向光斑）
 * - gestureModifier：按压/拖动手势采集；不消费事件，可与 clickable 并存
 */
class LiquidPressHighlight(private val animationScope: CoroutineScope) {

    private val pressProgressSpec = com.ticketfortwo.app.ui.theme.MotionTheme.pressSpec
    // 三套动画语言（Liquid 回弹 / Snappy 干脆 / Gentle 柔缓），入口在设置-通用
    private val positionSpec =
        spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnim = Animatable(0f)
    private val positionAnim =
        Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    val pressProgress: Float get() = pressProgressAnim.value
    val offset: Offset get() = positionAnim.value - startPosition

    /** 表面辉光：按压时泛起白色光斑并跟随指尖（纯 Compose 径向渐变，全版本可用）。 */
    val highlightModifier: Modifier =
        Modifier.drawWithContent {
            val progress = pressProgress
            if (progress > 0f) {
                val center = positionAnim.value
                val radius = size.minDimension * 1.2f
                drawRect(
                    Color.White.copy(0.08f * progress),
                    blendMode = BlendMode.Plus
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(0.18f * progress),
                            Color.White.copy(0.06f * progress),
                            Color.Transparent
                        ),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus
                )
            }

            drawContent()
        }

    /** 手势采集：按下→进度弹到 1、移动→折射层跟随、松开→spring 回弹归零。 */
    val gestureModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnim.animateTo(1f, pressProgressSpec) }
                    launch { positionAnim.snapTo(startPosition) }
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    animationScope.launch { positionAnim.snapTo(change.position) }
                    if (!change.pressed) break
                }
                animationScope.launch {
                    launch { pressProgressAnim.animateTo(0f, pressProgressSpec) }
                    launch { positionAnim.animateTo(startPosition, positionSpec) }
                }
            }
        }
}

/**
 * 可交互的液态玻璃按钮：按压时玻璃整体缩放、折射随指尖方向流动（tanh 软限位），
 * 松手 spring 回弹；表面可叠加一层着色用于强调状态。
 */
@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    surfaceColor: Color? = null,
    refract: Boolean? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { LiquidPressHighlight(animationScope) }

    val r = refract ?: LocalGlassRefract.current
    val bgModifier = if (r) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                vibrancy()
                // Catalog LiquidButton 同款：blur(2.dp) + lens(12.dp, 24.dp) —
                // 适中折射强度，按压时 liquid 感最强
                blur(2.dp.toPx())
                lens(12.dp.toPx(), 24.dp.toPx())
            },
            layerBlock = {
                val progress = highlight.pressProgress
                val scale = lerp(1f, 1f + 2.dp.toPx() / size.height, progress)
                // 不做跟指平移：折射层跟随指尖在小按钮上读作「按钮可以被拖走」（两轮用户反馈）。
                // 交互反馈保留按压缩放 + 指尖辉光，位置完全静止
                scaleX = scale
                scaleY = scale
            },
            onDrawSurface = {
                if (surfaceColor != null) {
                    drawRect(surfaceColor)
                }
            }
        )
    } else {
        Modifier
            .clip(shape)
            .background(surfaceColor ?: glassSurfaceColor(com.ticketfortwo.app.ui.theme.cardSurfaceAlpha()))
            .border(1.5.dp, glassBorderColor(0.45f), shape)
    }

    Box(
        modifier
            // 禁用态整体降透明（0.45），而不是只压 surfaceColor 的 alpha：
            // 原先只把 surfaceColor alpha 降到 40%，内容文字仍是全亮，读不出不可点
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .then(bgModifier)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = onClick
            )
            .then(highlight.highlightModifier)
            .then(if (enabled) highlight.gestureModifier else Modifier),
        contentAlignment = contentAlignment
    ) {
        content()
    }
}

/**
 * 可点击的液态玻璃卡片（对齐 Catalog 的 RefractionCard / 按压缩放）：
 * - 玻璃表面采样背景折射 + 按压缩放回弹，表面可叠着色强调
 * - 卡内内容直接放在玻璃上方，卡体本身即可点击
 */
@Composable
fun GlassCard(
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    surfaceAlpha: Float = 0.16f,
    tint: Color? = null,
    lensRadius: Dp = 18.dp,
    /** 磨砂模糊半径。首页圆形入口按用户指定用 15dp（默认 4dp 是轻磨砂）。 */
    blurRadius: Dp = 4.dp,
    /** 折射强度倍数：折射位移 = 折射高度 × 此值（对齐 GlassPanel 的 lensAmountMul）。 */
    lensAmountMul: Float = 1f,
    /** 边缘色差（红蓝分离）。 */
    chromaticAberration: Boolean = false,
    /** 深度效果（折射带内的暗缘，库 demo 的 depthEffect）。 */
    depthEffect: Boolean = true,
    refract: Boolean? = null,
    pressScale: Boolean = true,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { LiquidPressHighlight(animationScope) }

    val r = refract ?: LocalGlassRefract.current
    val cardSurface = glassSurfaceColor(surfaceAlpha)
    val bgModifier = if (r) {
        Modifier
            // 硬裁剪到卡片形状：drawBackdrop 的 blur/lens 与表面填充会溢出圆角外的
            // 方形区域（平色背景上呈灰角块，实测确认）；磨砂路径本就有 clip，补齐折射路径
            .clip(shape)
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    if (lensRadius > 0.dp) {
                        // 对齐 GlassPanel 的配方：折射强度 = 折射高度 × 倍数
                        lens(
                            refractionHeight = lensRadius.toPx(),
                            refractionAmount = (lensRadius * lensAmountMul).toPx(),
                            depthEffect = depthEffect,
                            chromaticAberration = chromaticAberration,
                        )
                    }
                },
                layerBlock = {
                // 与 LiquidGlassButton 同理：不做跟指平移——卡片被按住拖动会读作「可拖拽」。
                // 折射静态呈现，按压缩放与指尖辉光由 highlight 提供
            },
            onDrawSurface = {
                drawRect(cardSurface)
                // tint 不能画在这里：onDrawSurface 画布与 shape 存在亚像素错位，
                // 圆角矩形边缘会露到 shape 外形成灰色矩形带（实测确认）；
                // 改为在下方 Box 内容层用 background(tint, shape) 精确裁剪
            }
        )
    } else {
        Modifier
            .clip(shape)
            .background(cardSurface)
            // 描边与折射路径（glassBorderColor）同源同值：refract 切换时描边不跳变。
            // 前景系描边在浅色磨砂卡上仍可辨（白卡上白描边才真的不可见）
            .border(1.5.dp, glassBorderColor(0.45f), shape)
            .then(if (tint != null) Modifier.background(tint, shape) else Modifier)
    }

    Box(
        modifier
            // 此层必须常驻（即使不做按压缩放）：它把 drawBackdrop 的折射渲染隔离在
            // 独立 RenderNode 内，否则 blur/lens 的渲染边界会直接暴露成卡片四角直角伪影
            .graphicsLayer {
                if (pressScale) {
                    val p = highlight.pressProgress
                    val s = lerp(1f, 1f - 0.015f, p)
                    scaleX = s
                    scaleY = s
                }
            }
            .then(bgModifier)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .then(highlight.highlightModifier)
            .then(highlight.gestureModifier),
        contentAlignment = contentAlignment
    ) {
        // 着色层：叠在玻璃表面之上、内容之下；background(tint, shape) 按 shape
        // 精确裁剪，无 onDrawSurface 的亚像素错位泄漏（磨砂路径已在 bgModifier 内）
        if (r && tint != null) {
            Box(Modifier.matchParentSize().background(tint, shape))
        }
        content()
    }
}

/**
 * 渲染一个液态玻璃开关胶囊；无背景采样时退化为纯绘制磨砂胶囊。
 * 仅当开关自身处于 glass 采样层内（drawBackdrop 会自引用导致渲染递归崩溃）时，
 * 应传 refract=false，用本地绘制代替背景采样。
 */
private fun DrawScope.drawCapsule(fraction: Float, press: Float, accent: Color) {
    val pad = 2.dp.toPx()
    // 胶囊圆角：磨砂回退路径（refract=false）与折射路径同形，杜绝直角矩形开关
    val corner = CornerRadius(size.height / 2f, size.height / 2f)

    // 轨道：关=雾白磨砂，开=主题色浸染（近实心，与按钮主绿饱和度一致）
    drawRoundRect(
        color = lerp(Color.White.copy(alpha = 0.18f), accent.copy(alpha = 0.95f), fraction),
        cornerRadius = corner
    )
    drawRoundRect(
        color = Color.White.copy(alpha = lerp(0.38f, 0.10f, fraction)),
        cornerRadius = corner,
        style = Stroke(1.dp.toPx())
    )

    // 滑块：镜面小球（径向高光 + 描边），按压时微微鼓起
    val r = size.height / 2f - pad
    val cx = pad + r + fraction * (size.width - 2 * pad - 2 * r)
    val cy = size.height / 2f
    val rr = r * (1f + 0.08f * press)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White,
                Color.White.copy(alpha = 0.97f),
                Color(0xFFE4EAF2).copy(alpha = 0.94f)
            ),
            center = Offset(cx, cy - rr * 0.3f),
            radius = rr * 1.4f
        ),
        radius = rr,
        center = Offset(cx, cy)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.9f),
        radius = rr,
        center = Offset(cx, cy),
        style = Stroke(0.8.dp.toPx())
    )
}

/**
 * 液态玻璃开关（对齐 AndroidLiquidGlass Catalog 的 LiquidToggle）：
 * - 玻璃胶囊轨道 + 镜面滑块，整体折射壁纸背景
 * - 点击即切换；横向拖动滑块实时跟随，过半提交，spring 回弹归位
 * - 切换与拖动提交时 CLOCK_TICK 触感反馈
 * - refract=true 时用 drawBackdrop 采样背景（玻璃元素必须位于采样层之外，否则渲染自引用崩溃）；
 *   若被放在 layerBackdrop / appLayer 采样子树内，必须传 refract=false，改用本地磨砂绘制。
 */
@Composable
fun LiquidToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedColor: Color? = null,
    refract: Boolean? = null
) {
    val animationScope = rememberCoroutineScope()
    val highlight = remember(animationScope) { LiquidPressHighlight(animationScope) }
    val view = LocalView.current
    val r = refract ?: LocalGlassRefract.current

    val width = 52.dp
    val height = 32.dp
    val travelPx = with(LocalDensity.current) { (width - height).toPx() }
    val accent = checkedColor ?: androidx.compose.material3.MaterialTheme.colorScheme.primary

    // 开关进度 0..1；拖动中用 dragFraction 覆盖显示，松手交还弹簧
    val progressAnim = remember { Animatable(if (checked) 1f else 0f) }
    var dragFraction by remember { mutableFloatStateOf(Float.NaN) }

    LaunchedEffect(checked) {
        if (dragFraction.isNaN()) {
            progressAnim.animateTo(if (checked) 1f else 0f, spring(0.55f, 380f))
        }
    }

    fun fraction(): Float =
        if (dragFraction.isNaN()) progressAnim.value else dragFraction

    val trackModifier = if (r) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { CircleShape },
            effects = {
                vibrancy()
                blur(2.dp.toPx())
                lens(8.dp.toPx(), 16.dp.toPx())
            },
            layerBlock = {
                // 折射层随进度轻微位移：内芯像液体一样滚向目标侧
                translationX = lerp(-2.dp.toPx(), 2.dp.toPx(), fraction())
            },
            onDrawSurface = {
                drawCapsule(fraction(), highlight.pressProgress, accent)
            }
        )
    } else {
        // 位于玻璃采样层内时禁止 drawBackdrop（否则渲染自引用递归崩溃），退化为本地磨砂绘制
        Modifier.drawWithContent {
            drawCapsule(fraction(), highlight.pressProgress, accent)
            drawContent()
        }
    }

    Box(
        modifier
            .size(width = width, height = height)
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(checked)
            }
            .then(trackModifier)
            .then(highlight.highlightModifier)
            .pointerInput(enabled, checked) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var dragging = false
                    var aborted = false
                    var upChange: androidx.compose.ui.input.pointer.PointerInputChange? = null
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) { upChange = change; break }
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!dragging && kotlin.math.abs(dx) > viewConfiguration.touchSlop) {
                            dragging = true
                        }
                        // 竖向滑动是在滚动外层列表：立即放弃手势，抬起时不能当成点击翻转开关
                        if (!dragging && kotlin.math.abs(dy) > viewConfiguration.touchSlop &&
                            kotlin.math.abs(dy) > kotlin.math.abs(dx)
                        ) {
                            aborted = true
                            break
                        }
                        if (dragging && enabled && onCheckedChange != null) {
                            dragFraction =
                                ((if (checked) 1f else 0f) + dx / travelPx).coerceIn(0f, 1f)
                            change.consume()
                        }
                    }
                    if (!aborted && enabled && onCheckedChange != null) {
                        if (dragging) {
                            val target = dragFraction >= 0.5f
                            dragFraction = Float.NaN
                            upChange?.consume()
                            if (target != checked) {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                                onCheckedChange(target)
                            }
                        } else if (upChange?.isConsumed != true) {
                            // 纯点击也要消费 UP，否则外层可点击卡片会再触发一次（开关净效果为零）；
                            // UP 已被父级（列表滚动）消费时不算点击
                            upChange?.consume()
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                            onCheckedChange(!checked)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
        content = {}
    )
}

/**
 * 子页面通用玻璃页头：返回键 + 标题 + 可选动作区。
 * 与聊天页 TopBar 同款通栏方角玻璃（含状态栏高度、贴边无圆角无描边），
 * 各二级页共用同一条视觉页头，仅换标题——保证全局一体性。
 */
@Composable
fun GlassPageBar(
    backdrop: LayerBackdrop,
    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    refract: Boolean? = null,
    /** null = 跟着是否铺壁纸自适应（见 [com.ticketfortwo.app.ui.theme.pageBarSurfaceAlpha]） */
    surfaceAlpha: Float? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
        radius = 0.dp,
        // 顶栏加折射环（之前 0 = 完全没折射，是"只是磨砂"最重的地方）
        lensRadius = com.ticketfortwo.app.ui.theme.pageBarLensRadius(),
        blurRadius = com.ticketfortwo.app.ui.theme.pageBarBlurRadius(),
        surfaceAlpha = surfaceAlpha ?: com.ticketfortwo.app.ui.theme.pageBarSurfaceAlpha(),
        border = false,
        refract = refract
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 玻璃含状态栏：内容排到状态栏下方，状态栏文字浮在玻璃上
                .statusBarsPadding()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = androidx.compose.material3.MaterialTheme.colorScheme.onBackground
                    )
                }
            }
            androidx.compose.material3.Text(
                title,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            actions()
        }
    }
}

/**
 * 紧凑输入框：48dp 定高（Material 默认 56dp，浮动标签上下留白占掉一大截）。
 * label 固定渲染在框内左侧（不浮动），值与标签并排——纵向密度优先：
 * 同一屏能多看一两个字段。直接基于 Foundation BasicTextField 自绘边框，
 * 不碰 Material 内部 API（版本间签名不稳）。
 */
@Composable
fun CompactGlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation =
        androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions =
        androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions =
        androidx.compose.foundation.text.KeyboardActions.Default,
    /**
     * 框自身高度。**默认 40dp 不要改**（那是弹窗密度调定值，见下面 Row 的注释）。
     * 只有一种情况需要传：这一行里还有一个更高的兄弟控件（如「打开」按钮），
     * 两个高度不一样时 CenterVertically 会把按钮顶得比框上下都高，看着像贴歪了 ——
     * 放映厅地址栏实测就是这样（框 40 / 按钮 52）。
     */
    boxHeight: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val interaction = androidx.compose.runtime.remember {
        androidx.compose.foundation.interaction.MutableInteractionSource()
    }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(11.dp)
    val borderColor = if (focused) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    // 用 onBackground 叠加做容器差（浅色下=压暗、深色下=提亮）而非提白度，聚焦时加深一档。
    // 两种环境：①花壁纸上的页面 → 中度压暗（0.20/0.30，0.04/0.07 那档压在花壁纸上
    // 等于没有框、花纹会吃掉边框）；②净色页 → 极浅压暗（0.04/0.07）
    val container = if (com.ticketfortwo.app.ui.theme.LocalOnWallpaper.current) {
        if (focused) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.20f)
    } else if (focused) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
    else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.04f)

    androidx.compose.foundation.layout.Row(
        modifier
            .fillMaxWidth()
            // 高度 40dp（原 48）：弹窗内密度优化——48dp 在三段分段布局里
            // 单屏能看的行数太少（2026-09-11 用户反馈输入框太大空余多）
            .height(boxHeight)
            .background(container, shape)
            .border(if (focused) 1.5.dp else 1.dp, borderColor, shape)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 固定标签：始终在内容左侧，与已填值并排；label 为空 = 无标签（纯占位框）
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (focused) 0.95f else 0.8f),
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f)) {
            if (value.isEmpty() && placeholder != null) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f),
                    maxLines = 1
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interaction,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onBackground
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * 弹窗内统一的次级文字按钮：磨砂容器 + 细描边（透明 TextButton 会和玻璃面板融在一起，
 * 看起来"没做样式"）。刻意不做 drawBackdrop 折射——弹窗独立于采样层，折射只会采样到
 * 弹窗底下的页面，表现为「穿透背板 + 边缘一圈光晕」，统一走本地磨砂绘制。
 * backdrop/refract 参数保留以兼容旧调用点，不再参与绘制。
 */
@Composable
fun GlassTextButton(
    text: String,
    onClick: () -> Unit,
    backdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    refract: Boolean? = null
) {
    // Catalog 同款轻量玻璃按钮：无 surfaceColor 覆盖，纯描边 + 点击液感反馈。
    // 弹窗内 drawBackdrop 会自引用渲染崩溃（背景层包含自身像素），所以放弃
    // 在弹窗内做折射，统一走本地磨砂；但**关键不贴半透明白底**——
    // 之前 0.28f 的半透明在弹窗里让背景页面元素透出来，按钮边缘形成视觉上
    // 的「大阴影」，违背 Catalog 的轻量玻璃美学
    val shape = RoundedCornerShape(percent = 50)
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // 轻点补闪（快速轻点会被按帧合批吃掉，只有按久才有反馈）
    val pressFb = rememberPressFeedback(interactionSource)

    val tintAlpha = androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressFb.pressed && enabled) 0.10f else 0f,
        animationSpec = androidx.compose.animation.core.tween(90),
        label = "glassTextBtnTint"
    ).value

    Box(
        modifier
            .graphicsLayer {
                // 按压缩放：Catalog LiquidButton 标配（scale 1.0 → 0.97），
                // 反馈感强但视觉无负担（不放大、不摇晃）
                val scale = if (pressFb.pressed && enabled) 0.965f else 1f
                scaleX = scale
                scaleY = scale
                // 禁用观感：与 LiquidGlassButton 统一处理
                alpha = if (enabled) 1f else 0.45f
            }
            .background(
                MaterialTheme.colorScheme.onBackground.copy(alpha = tintAlpha),
                shape
            )
            .border(
                1.dp,
                if (enabled) glassBorderColor(0.55f)
                else glassBorderColor(0.25f),
                shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                enabled = enabled,
                onClick = pressFb.wrap(onClick)
            )
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
        )
    }
}

/**
 * 轻点也要有按压反馈（2026-09-19 用户实测反馈）。
 *
 * 背景：`collectIsPressedAsState()` 是**按帧合批**的 —— 快速轻点的 down/up 可能落在同一帧内，
 * 收集到的最新值直接就是 false，`pressed` 从未渲染为 true。观感就是
 * 「点一下完全没反应，必须按久一点或长按才有反馈」（深度思考栏最明显）。
 *
 * 用法：按压态用 [PressFeedback.pressed]，onClick 用 [PressFeedback.wrap] 包一层：
 * ```
 * val pf = rememberPressFeedback(interactionSource)
 * Modifier.background(if (pf.pressed) color else Color.Transparent)
 *     .clickable(interactionSource = interactionSource, indication = null, onClick = pf.wrap(onClick))
 * ```
 * 动画时长建议 ≤ [holdMs]（默认 90ms），否则轻点的补闪走不满、反馈仍然弱。
 */
class PressFeedback internal constructor(
    private val flashState: androidx.compose.runtime.MutableState<Boolean>,
    /** 是否显示按压态：真实按压 **或** 轻点补的闪动 */
    val pressed: Boolean
) {
    /** 包一层 onClick：先补一次轻点闪动，再执行原回调。 */
    fun wrap(callback: () -> Unit): () -> Unit = {
        flashState.value = true
        callback()
    }
}

@androidx.compose.runtime.Composable
fun rememberPressFeedback(
    interactionSource: androidx.compose.foundation.interaction.InteractionSource,
    /** 轻点补闪持续时间（ms）：够长才看得见，够短不显拖沓 */
    holdMs: Long = 90
): PressFeedback {
    val raw by interactionSource.collectIsPressedAsState()
    val flash = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(flash.value) {
        if (flash.value) {
            kotlinx.coroutines.delay(holdMs)
            flash.value = false
        }
    }
    return PressFeedback(flash, raw || flash.value)
}
