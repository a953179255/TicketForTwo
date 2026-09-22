package com.ticketfortwo.app.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 液态玻璃滑块（对齐 Kyant0/AndroidLiquidGlass kmp 分支 Catalog 的 LiquidSlider）：
 * 玻璃胶囊轨道 + 镜面液态圆钮（按住放大、折射采样、拖动速度形变）。
 * accent 用主题 primary 与全应用按钮绿一致；轨道灰同 Catalog。
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    /** 拖动结束/点按提交后回调一次：持久化等重操作放这里，不要放 onValueChange（逐帧调用） */
    onValueChangeFinished: (() -> Unit)? = null
) {
    val isLightTheme = androidx.compose.material3.MaterialTheme.colorScheme.background.luminance() > 0.5f
    val accentColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        // 拖动映射：按下时记基准值 + 累计位移 → 绝对换算（不依赖动画 target，
        // 否则写入→snapshotFlow→launch 的两帧延迟会让自增踏步、拖动几乎不走）
        var dragStartValue by remember { mutableStateOf(0f) }
        var dragAccum by remember { mutableStateOf(0f) }
        var dragActive by remember { mutableStateOf(false) }
        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f
            )
        }
        // 拖动基准：非拖动时每次重组同步当前值（pointerInput 闭包只捕获 state 引用，
        // 读取时取最新，杜绝首帧过期值）；拖动中不再同步，保证绝对映射起点稳定
        if (!dragActive) {
            dragStartValue = value()
            // 非拖动的值变化（启动/返回页面/外部改值）也要驱动圆钮动画追上
            if (dampedDragAnimation.targetValue != value()) {
                dampedDragAnimation.updateValue(value())
            }
        }
        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(trackColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).roundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        // 手势承载层：覆盖轨道全宽（24dp 高），点按跳转 + 横向拖动跟手
        Box(
            Modifier
                .fillMaxWidth()
                .height(24f.dp)
                .pointerInput(animationScope) {
                    detectTapGestures { position ->
                        val delta = (valueRange.endInclusive - valueRange.start) * (position.x / trackWidth)
                        val targetValue =
                            (if (isLtr) valueRange.start + delta
                            else valueRange.endInclusive - delta)
                                .coerceIn(valueRange)
                        dampedDragAnimation.animateToValue(targetValue)
                        onValueChange(targetValue)
                        onValueChangeFinished?.invoke()
                    }
                }
                .pointerInput(animationScope) {
                    detectHorizontalDragGestures(
                        onDragStart = { _ ->
                            dragActive = true
                            dragAccum = 0f
                            dampedDragAnimation.press()
                        },
                        onDragEnd = {
                            dragActive = false
                            dampedDragAnimation.release()
                            onValueChangeFinished?.invoke()
                        },
                        onDragCancel = {
                            dragActive = false
                            dampedDragAnimation.release()
                        }
                    ) { change, dragAmount ->
                        change.consume()
                        dragAccum += dragAmount
                        val raw = dragStartValue + (valueRange.endInclusive - valueRange.start) *
                            (dragAccum / trackWidth).let { if (isLtr) it else -it }
                        val new = raw.coerceIn(valueRange)
                        dampedDragAnimation.updateValue(new)
                        onValueChange(new)
                    }
                }
        )

        Box(
            Modifier
                .graphicsLayer {
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, trackWidth - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { RoundedCornerShape(50) },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

/** 拷自 Catalog utils：阻尼拖动动画（弹簧跟随 + 速度追踪形变）；Clock 换 System 避免实验 API。 */
class DampedDragAnimation(
    private val animationScope: kotlinx.coroutines.CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    onDragStarted: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragStopped: () -> Unit = {},
    onDrag: (androidx.compose.ui.unit.IntSize, Float) -> Unit = { _, _ -> },
) {
    private val valueAnimationSpec =
        androidx.compose.animation.core.spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec =
        androidx.compose.animation.core.spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec =
        androidx.compose.animation.core.spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        androidx.compose.animation.core.spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        androidx.compose.animation.core.spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        androidx.compose.animation.core.Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        androidx.compose.animation.core.Animatable(0f, 5f)
    private val pressProgressAnimation =
        androidx.compose.animation.core.Animatable(0f, 0.001f)
    private val scaleXAnimation =
        androidx.compose.animation.core.Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        androidx.compose.animation.core.Animatable(initialScale, 0.001f)

    private val mutatorMutex = androidx.compose.foundation.MutatorMutex()

    private val velocityTracker = androidx.compose.ui.input.pointer.util.VelocityTracker()

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    fun updateValue(value: Float) {
        val targetValue = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) { updateVelocity() } }
        }
    }

    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val targetValue = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(targetValue, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            System.currentTimeMillis(),
            androidx.compose.ui.geometry.Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

private fun Float.fastCoerceIn(min: Float, max: Float): Float = coerceIn(min, max)

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
