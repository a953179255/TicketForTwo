package com.ticketfortwo.app.ui.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import com.ticketfortwo.app.ui.theme.cardSurfaceAlpha
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
    // 自定义壁纸的持久化加载（App 启动时读一次；写入方是早先的调参工具，已下线，这里只读保留）
    val rootContext = LocalContext.current
    LaunchedEffect(Unit) { AppWallpaper.load(rootContext) }
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
    /** 默认 52dp（主操作的手感值）。和输入框并排时按框的高度传，别让按钮比框高一截。 */
    height: androidx.compose.ui.unit.Dp = 52.dp,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.height(height),
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
        // 深色主题下必须降档，否则黑雾 0.58×1.8 会封顶成 0.94 —— 玻璃变黑板，
        // 卡内点阵底纹与折射全部消失。取值出处见 cardSurfaceAlpha() 的注释。
        surfaceAlpha = cardSurfaceAlpha(),
        floating = floating,
        content = content,
    )
}

/**
 * 段落标题。
 *
 * 字色用 `TextMid`（#B3B3B3）而不是 `TextLow`（#7A7A7A）——
 * `TextLow` 的亮度只有 0.19，**即使压在纯白底上对比度也只有 4.4:1**，它是给"深色实底"用的。
 * 换成默认壁纸之后，这类小标题一半在壁纸亮块上、一半在几乎全透的玻璃卡里，
 * 实测压在黄色大字上的那一个只有 0.6:1（基本看不见）→ 整档提到 TextMid 才够。
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = Ink.TextMid,
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
        // maxLines=1 会把超长文案**静默裁掉**（观众屏那句就裁在了半个词上，
        // 半句话比没有这句话更让人困惑）。宁可撑成两行也不截断。
        Text(text, fontSize = 11.sp, color = fg, maxLines = 2, textAlign = TextAlign.Center)
    }
}

enum class ChipTone { Neutral, Ok, Warn, Bad }

/**
 * 分段行末尾的「自定义」输入格。
 *
 * [onFocusChange] 在输入框焦点变化时回调，外面靠它做两件事：
 *  - **失焦时校正**"还没填完"的中间值：想输 60 时先敲出来的是 6，不该立刻被改写成 8；
 *    但也不能就那么留着 —— 否则会出现"框里写着 6、实际用着 30"的错位。
 *  - **有焦点期间别用外部状态覆盖框里的字**：否则刚敲完 60、quality 跟着变成 60，
 *    外部同步逻辑一跑就把用户刚输入的内容抹成空串。
 */
data class SegmentInputSpec(
    val placeholder: String,
    val value: String,
    val onValueChange: (String) -> Unit,
    val onFocusChange: (Boolean) -> Unit = {},
)

/**
 * 峰值速度 → 形变量的换算系数。见 [SegmentRow] 里对形变的说明。
 *
 * 库写的是 `v = 速度/10`，它假设的是**手指拖拽**的速度量级（约 1–4 格/秒）。
 * 我们是点击驱动，位置由一条刚度 1000 的临界阻尼弹簧给出，它的峰值速度是解析的：
 *     |v| = 行程 · √刚度 · e⁻¹
 * 而 [SegmentRow] 里已经用行程 `span` 归一过，于是归一化峰值速度
 *     √1000 · e⁻¹ ≈ 11.6 格/秒
 * 是个**与行里有几格无关的常数**（1 格的行、4 格的行都一样）。
 *
 * 照搬 /10，这个 11.6 会变成 1.16 —— 是库那条 `coerceIn(-0.2, 0.2)` 上限（对应 v=0.267）
 * 的 4 倍多，于是整段飞行都顶死在上限上（实测一次跨两格的切换，20 帧里有 7 帧 scaleX
 * 卡在 1.25 不动）。观感就从"滑块被拉长"变成了"滑块变胖"。
 * 44 这个数就是为了让 11.6 正好落到 clamp 边界：**只有最快的那一两帧够得到库的最大形变**，
 * 其余时间沿着弹簧的速度曲线平滑衰减 —— 这才是库那条曲线的形状。
 */
private const val SEGMENT_VELOCITY_SCALE = 44f

/**
 * 液态分段控件。
 *
 * 动效对齐 AndroidLiquidGlass 的 `DampedDragAnimation` + `LiquidBottomTabs`，两处照搬：
 *
 *  1. **位置**用 `spring(dampingRatio = 1f, stiffness = 1000f)` —— 阻尼比 1 是**临界阻尼，不过冲**，
 *     滑块"滑过去停住"，不会像普通弹簧那样回弹一下。（上一版用了 0.55 的阻尼比，
 *     画面上平白多出一次回弹，这就是"和库不一样"最直观的一处。）
 *  2. **形变**公式逐字取自库的 `layerBlock`：
 *       `scaleX /= 1 - (v*0.75)`、`scaleY *= 1 - (v*0.25)`
 *     —— 横向拉长、纵向压扁，这是"液态"观感的真正来源，只做颜色渐变是看不出液态的。
 *     只有速度的换算系数按本场景重新标定过，理由见 [SEGMENT_VELOCITY_SCALE]。
 *
 * 与库的两处分歧都是场景差异逼出来的，不是口味问题：
 *
 *  · **速度从哪来**。库的位置由**手指拖拽**驱动（`updateValue` 每帧跳目标），弹簧算不出
 *    有意义的速度，所以它另开 `VelocityTracker` + 一条速度弹簧去测手指。我们是**点击**驱动，
 *    位置本身就是一条光滑的临界阻尼弹簧，它的瞬时速度天然和位移同相位；照搬那层滤波器
 *    只会让形变**迟到**（实测：位置已经飞到 1.96/2.0 时形变才刚开始爬升，
 *    峰值落在动画结束之后 —— 表现为"飞过去时扁扁的、停稳了才鼓一下"，与液态完全相反）。
 *
 *  · **形变是有方向的**：`velocity` 带符号，所以向左切换时 scaleX 变小（横向收紧、纵向变高），
 *    向右切换才横向拉长。看着不对称，但这**是库自己的行为** ——
 *    `LiquidBottomTabs` / 液态滑杆 / `LiquidToggle` 三个组件写的都是同一个不带 abs 的公式，
 *    所以这里照抄，不做"自作聪明"的取绝对值。哪天想改成双向都拉长，改这一行即可。
 *
 * 有意略去的一项：库里指示器按下会鼓到 `78/56 ≈ 1.39` 倍（"液滴鼓出栏外"）。
 * 那需要 56dp 高的指示器加 64dp 的栏才撑得住；我们的滑块只有 36dp、外套一层 3dp 内边距的
 * 胶囊容器，放大 1.39 倍会直接冲出容器，在设置页里只会像画错了。
 *
 * [input] 非空时末尾追加一个可输入的格子（占位文字写在框内），
 * 这样"自定义"不单独占一行，几行控件的高度才一致。
 * [selected] 传 -1 表示"当前值不属于任何预设档"—— 指示器会落到输入格上。
 */
@Composable
fun SegmentRow(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    input: SegmentInputSpec? = null,
    onSelect: (Int) -> Unit,
) {
    val cellCount = options.size + if (input != null) 1 else 0
    val target = if (selected < 0) (cellCount - 1).toFloat() else selected.toFloat()

    val position = remember { Animatable(target) }
    // 归一化分母：总跨度（格数 - 1）。除以它，速度就和"每秒移动几格"同量纲，
    // 这样 3 格的分辨率行和 5 格的码率行，拉长幅度不会因为格数不同而差一截。
    val span = (cellCount - 1).coerceAtLeast(1).toFloat()

    LaunchedEffect(target) {
        position.animateTo(
            targetValue = target,
            // 临界阻尼 + 高刚度 = 快而不弹，与库的 valueAnimationSpec 一致
            animationSpec = spring(dampingRatio = 1f, stiffness = 1000f, visibilityThreshold = 0.001f),
        )
    }

    // 形变驱动 = 位置弹簧的**瞬时速度**。
    //
    // 库里这里还有一层"速度动画 + VelocityTracker"，那是因为它的位置由**手指拖拽**驱动：
    // 每一帧目标值都在跳，弹簧算不出有意义的速度，只能另开一条跟踪器去测手指。
    // 我们是点击驱动 —— 位置本身就是一条光滑的临界阻尼弹簧，它的瞬时速度天然和位移同相位，
    // 再套一层弹簧滤波只会让形变**迟到**。
    // （实测过：套滤波器时，位置已飞到 1.96/2.0 时形变才开始爬升，峰值落在动画结束之后，
    //   表现为"飞过去时扁扁的、停稳了才鼓一下" —— 和要的液态完全相反。）
    val v = position.velocity / span / SEGMENT_VELOCITY_SCALE
    val deformX = 1f / (1f - (v * 0.75f).coerceIn(-0.2f, 0.2f))
    val deformY = 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(percent = 50))
            .padding(3.dp),
    ) {
        val gap = 4.dp
        val cellWidth = (maxWidth - gap * (cellCount - 1)) / cellCount

        // 指示器：独立滑块。
        // graphicsLayer 放在 background **之前**：这样圆角胶囊是画在缩放层"里面"的，
        // 拉长时形状跟着一起变形 —— 和库把形变写在 drawBackdrop 的 layerBlock 里是一回事。
        // 若把 graphicsLayer 放到 background 后面，缩放的是已经画好的圆角矩形，
        // 圆角会被等比拉宽，看起来像个橄榄球。
        Box(
            Modifier
                .offset(x = (cellWidth + gap) * position.value)
                .width(cellWidth)
                .height(36.dp)
                .graphicsLayer {
                    scaleX = deformX
                    scaleY = deformY
                }
                .background(Ink.AccentSolid, RoundedCornerShape(percent = 50)),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                val fg by animateColorAsState(
                    targetValue = if (on) Color.White else Ink.TextMid,
                    animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                    label = "segFg",
                )
                Box(
                    Modifier
                        .width(cellWidth)
                        .height(36.dp)
                        .clickable(interactionSource = null, indication = null) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        fontSize = 12.5.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = fg,
                    )
                }
            }

            if (input != null) {
                val filled = input.value.isNotEmpty()
                Box(
                    Modifier.width(cellWidth).height(36.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicTextField(
                        value = input.value,
                        onValueChange = input.onValueChange,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = TextStyle(
                            color = if (filled) Color.White else Ink.TextLow,
                            fontSize = 12.5.sp,
                            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { input.onFocusChange(it.isFocused) },
                    ) { inner ->
                        if (!filled) {
                            Text(
                                input.placeholder,
                                fontSize = 12.5.sp,
                                color = Ink.TextLow,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        inner()
                    }
                }
            }
        }
    }
}

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
    /** 底下压着 SurfaceView 时必须 false（抓不到），没有视频层时可以采样环境底。 */
    refract: Boolean = false,
    /**
     * 观众侧的"方向"按钮：非空才画。房主没有"跟随对方"这回事，所以房主侧传 null。
     * 用**两个字**（跟随/竖屏/横屏）而不是图标 —— 一来 core 图标集里没有
     * `ScreenRotation`（那是 extended 的，为一颗按钮引整个扩展包不值），
     * 二来旋转类图标语义太容易和"重试/刷新"混，写文字反而一眼懂。
     */
    orientationLabel: String? = null,
    onCycleOrientation: () -> Unit = {},
    /** 同一颗按钮在两端语义不同：房主是"停止分享"，观众是"停止观看"。 */
    stopDesc: String = "停止分享",
) {
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier,
        radius = GlassDimens.radiusIsland,
        surfaceAlpha = 0.72f,
        refract = refract,
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
                if (orientationLabel != null) {
                    VDivider()
                    CircleControl(onClick = onCycleOrientation, backdrop = backdrop) {
                        Text(
                            orientationLabel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.TextHi,
                        )
                    }
                }
                VDivider()
                CircleControl(onClick = onStop, backdrop = backdrop) {
                    Icon(
                        Icons.Filled.Stop, contentDescription = stopDesc,
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
            // 底部安全区：手势导航条会盖住内容 —— 首页的设置胶囊实测就与横杠重叠了。
            .navigationBarsPadding()
            // 键盘：观众粘贴链接那一屏的主按钮贴在底部，键盘一弹出来就把它整个盖住，
            // 用户看到的是"粘好了但『在 App 内观看』点不到"（t2view 实测截图里就是这样，
            // 当时脚本连点三次没反应，因为点的全落在键盘上）。imePadding 让内容抬到
            // 键盘之上；没弹键盘时它是 0，不影响其它屏。
            .imePadding()
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
