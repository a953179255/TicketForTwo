package com.ticketfortwo.app.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 玻璃与动效的调参 —— 数值直接取自 HaoAI 已调定的那套（`ui/theme/HaoTokens.kt`、
 * `Motion.kt`），不重新发明。
 *
 * HaoAI 里这些值有「是否铺了动态壁纸」两条分支（它是 Xposed 主题模块，能读系统壁纸）。
 * 双人票没有壁纸概念，所以 `LocalOnWallpaper` 恒为 false，helper 全部塌成常量；
 * 但**保留这个 local 与分支形状**，将来若要做壁纸取色自适应不用改调用点。
 */
val LocalOnWallpaper = staticCompositionLocalOf { false }

/** 深色判定统一口径：背景亮度 < 0.5。全项目只用这一把尺子。 */
@Composable
fun isDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

object GlassDimens {
    /** 内容卡表面不透明度（素色底）。 */
    const val CARD_SURFACE_ALPHA = 0.58f

    /** 页面顶栏表面不透明度与模糊半径。 */
    const val PAGE_BAR_SURFACE_ALPHA = 0.30f
    val PAGE_BAR_BLUR: Dp = 12.dp

    /** 无壁纸时不做顶栏折射。 */
    val PAGE_BAR_LENS: Dp = 0.dp

    // ── 布局 token：取值出处见 docs/PLAN.md §6.2 的对照表 ──
    /** 圆形图标按钮直径 = Apple button-icon-circular 44px，同时是触控命中下限。 */
    val controlSize: Dp = 44.dp
    val controlSizeLarge: Dp = 56.dp
    val iconSize: Dp = 22.dp

    /** 圆角 scale（Apple rounded：xl 16 / pill 9999；中间档为自定）。 */
    val radiusSm: Dp = 8.dp
    val radiusMd: Dp = 12.dp
    val radiusLg: Dp = 16.dp
    val radiusCard: Dp = 26.dp
    val radiusIsland: Dp = 9999.dp

    /** 间距 scale（Apple 4px 基准）。 */
    val sp1: Dp = 4.dp
    val sp2: Dp = 8.dp
    val sp3: Dp = 12.dp
    val sp4: Dp = 16.dp
    val sp5: Dp = 20.dp
    val sp6: Dp = 24.dp
    val sp8: Dp = 32.dp

    /** 屏幕左右安全边距；控制岛距底、以及浮层卡片与岛之间的间距。 */
    val screenH: Dp = 20.dp
    val islandBottom: Dp = 24.dp
    val overlayGap: Dp = 16.dp
}

/**
 * 动效参数。
 *
 * 注意：**awesome-design-md 那 74 份 DESIGN.md 里没有任何 motion/duration token**，
 * 这些值不是"有出处的设计规范"，而是 HaoAI 真机调下来的经验值，沿用是因为它已被验证
 * 按感不迟钝（见 HaoAI 记忆里"手写高亮连修五版失败"那一章）。
 */
object MotionTheme {
    /** 弹性按压：damping 0.55 / stiffness 600 —— 压下去有回弹但不晃。 */
    val pressSpec: AnimationSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 600f)

    /** 弹层进出场。 */
    val popupSpec: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 340f)
    val popupFromScale: Float = 0.92f
    val popupSlideDp: Float = 10f
    val fadeMs: Int = 160

    /** 淡入淡出用，和 fadeMs 同一口径。 */
    fun fade(): AnimationSpec<Float> = tween(fadeMs)
}

/** 内容卡表面不透明度。保留函数形式以对齐 HaoAI 的调用点。 */
@Composable
fun cardSurfaceAlpha(): Float = GlassDimens.CARD_SURFACE_ALPHA

@Composable
fun pageBarSurfaceAlpha(): Float = GlassDimens.PAGE_BAR_SURFACE_ALPHA

@Composable
fun pageBarBlurRadius(): Dp = GlassDimens.PAGE_BAR_BLUR

@Composable
fun pageBarLensRadius(): Dp = GlassDimens.PAGE_BAR_LENS
