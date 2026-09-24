package com.ticketfortwo.app.ui.app

import android.content.Context
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.BuildConfig
import com.ticketfortwo.app.ShareQuality
import com.ticketfortwo.app.ui.glass.GlassCard
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import java.util.Locale

/**
 * 各屏。全部走 Kit 里的玻璃原子，不再出现 Material 默认卡片。
 *
 * 排版纪律（沿用 HaoAI 的验收口径）：
 * - 绝对定位的浮层**必须写纵向锚点**，否则 absolute 会退化到 static position 压住状态栏
 *   （效果图就是这么翻车的）；这里一律用 Column/Box 的 alignment，不用裸 offset。
 * - 视频区之上只有 scrim，不放玻璃。
 */

// ─────────────────────────── 房主 · 首页 ───────────────────────────

@Composable
fun HomeScreen(
    backdrop: LayerBackdrop,
    onStart: () -> Unit,
    onJoinViewer: () -> Unit,
    onSettings: () -> Unit,
    quality: ShareQuality,
    lastSummary: String?,
) {
    PageScaffold {
        Headline("双人票", "把你的屏幕，变成你和朋友的私人影院。")

        // 圆形双入口（效果图 home-orbs-pastel3.html 方案 2：丁香紫 × 樱花粉）。
        // 按用户要求：只改这两个圆的效果，页面其余部分保持原样。
        //
        // 尺寸自适应：直径以 160dp（实验室调定值）为上限，但不超过可用高度的 44%。
        // 两个 160dp 的圆在矮屏上会把底部"分享设置"挤出屏幕（实测被裁掉半截），
        // 所以这里按可用空间收缩 —— 高屏手机上依然显示完整的 160dp。
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val orbSize = minOf(160.dp, maxHeight * 0.44f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassOrbEntry(
                    onClick = onStart,
                    backdrop = backdrop,
                    tint = OrbTintViolet,
                    diameter = orbSize,
                    icon = {
                        Icon(
                            OrbShareIcon,
                            contentDescription = null,
                            tint = OrbInk,
                            modifier = Modifier.size(34.dp),
                        )
                    },
                label = "分享屏幕",
                // 这句要说的是"**对方**那边零门槛"，不是"我自己也免安装"（旧文案"浏览器免安装"
                // 就是在这儿误导的：分享端必须装 App）。
                // 现在观看有两条路（App 内看 / 浏览器看），所以不再点名浏览器，只说结果。
                sub = "发条链接，朋友就能看",
                )
                Spacer(Modifier.height(16.dp))
                GlassOrbEntry(
                    onClick = onJoinViewer,
                    backdrop = backdrop,
                    tint = OrbTintPink,
                    diameter = orbSize,
                    icon = {
                        Icon(
                            OrbViewIcon,
                            contentDescription = null,
                            tint = OrbInk,
                            modifier = Modifier.size(34.dp),
                        )
                    },
                    label = "进入观看",
                    sub = "粘上链接就能看",
                )
            }
        }

        // 信息卡（原样保留：画质 / 流量 / 麦克风 / 上次连接）。
        // 圆放大到 160dp 后垂直空间变紧，这里的内边距与行距各收一档。
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp3), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                InfoRow("分享画质", quality.summary(rememberScreenWidthPx()))
                if (quality.videoEnabled) {
                    InfoRow("流量上限", "约 ${quality.estMbPerMinute()} MB/分钟")
                }
                InfoRow("麦克风", "开")
                if (lastSummary != null) InfoRow("上次连接", lastSummary)
            }
        }

        // 分享设置（原样保留：玻璃胶囊按钮）。
        // 外面包一层 Column：这样它是"最后一个子项"，不再额外产生 PageScaffold 的元素间距，
        // 否则 160dp 的大圆会把这一屏整体撑出屏幕（实测底部胶囊会被裁掉半截）。
        Column {
            PrimaryPill("分享设置", onSettings, backdrop, Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ─────────────────────────── 首页 · 圆形磨砂入口 ───────────────────────────

/**
 * 圆钮雾色 —— 用户在「圆钮颜色实验室」实机调定（2026-09-23）。
 * 改色只需改这两行（或在实验室里调完把 hex 发过来）。
 */
val OrbTintViolet = Color(0xFF1FE91F)
val OrbTintPink = Color(0xFFFF62AB)

/** 浅玻璃上的深色文字：高明度底上深字比白字清晰，也更年轻。 */
val OrbInk = Color(0xFF0E1524)

/**
 * 首页圆形磨砂入口（效果图 home-orbs-pastel3.html 方案 2 的实现）：
 * 126dp 玻璃圆，图标 + 名称 + 一句副标。
 *
 * · 磨砂 blur 15（用户指定）—— blurRadius 参数是本次为 GlassCard 新增的
 * · tint 淡涂：壁纸透过圆仍隐约可见（v4 的教训：blur 太大 + tint 太浓 = 看不到透明效果）
 * · 无外光晕；厚度由库默认的 Highlight/Shadow 承担
 * · 圆内深色文字 —— 高明度浅底上深字比白字清晰，也更年轻
 */
@Composable
fun GlassOrbEntry(
    onClick: () -> Unit,
    backdrop: LayerBackdrop,
    tint: Color,
    icon: @Composable () -> Unit,
    label: String,
    sub: String,
    // 以下默认值 = 用户在玻璃参数实验室实机调定的配方（2026-09-23）。
    // 实验室里再调出新的，改这里的默认值即可（或把参数发过来）。
    diameter: Dp = 160.dp,
    blurRadius: Dp = 10.dp,
    lensRadius: Dp = 22.dp,
    lensAmountMul: Float = 2.495f,
    tintAlpha: Float = 0.80f,
    brightAlpha: Float = 0.20f,
) {
    GlassCard(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier.size(diameter),
        shape = CircleShape,
        // surfaceAlpha = 0：不画那层近黑的磨砂底。
        // 官方示例组件 LiquidButton 的 surfaceColor 默认是 Unspecified（不画）——
        // 玻璃亮度全靠 backdrop + vibrancy。我们此前叠的黑雾是"整体偏暗"的主因。
        surfaceAlpha = 0f,
        tint = tint.copy(alpha = tintAlpha),
        lensRadius = lensRadius,
        lensAmountMul = lensAmountMul,
        blurRadius = blurRadius,
        contentAlignment = Alignment.Center,
    ) {
        // 白雾提亮层：在通透玻璃上加一层柔白光（浓度由实验室调定）。
        // 圆内深字也没有它会更清晰。
        Box(
            Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = brightAlpha), CircleShape)
        )
        Column(
            modifier = Modifier.padding(top = 6.dp),   // 内容视觉重心微下移（实测偏上）
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            icon()
            Text(
                label,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = OrbInk,
            )
            Text(sub, fontSize = 10.sp, color = OrbInk.copy(alpha = 0.60f))
        }
    }
}

/**
 * 设置页的入口行。
 *
 * 刻意不用 ripple（在玻璃上是一块方形光晕，视觉脏）—— 按压反馈改成整行轻微内缩，
 * 和玻璃按钮的手感一致。
 */
@Composable
internal fun LabEntry(title: String, sub: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 520f),
        label = "labEntryScale",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
            Text(sub, fontSize = 11.5.sp, color = Ink.TextLow)
        }
        Text("›", fontSize = 18.sp, color = Ink.TextLow)
    }
}

/**
 * 数字输入行 —— 「自定义码率 / 自定义帧率」共用。
 *
 * 为什么不用 Material 的 TextField：这一页整体是玻璃风格，Material 输入框的
 * 填充/描边/下划线都跟周围的玻璃胶囊对不上，视觉上会像一个外来控件。
 */
@Composable
internal fun NumberInputRow(
    label: String,
    value: String,
    suffix: String,
    placeholder: String,
    onChange: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
    ) {
        Text(label, fontSize = 12.5.sp, color = Ink.TextMid)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = TextStyle(color = Ink.TextHi, fontSize = 14.sp),
            modifier = Modifier
                .weight(1f)
                .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(percent = 50))
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) { inner ->
            if (value.isEmpty()) {
                Text(placeholder, fontSize = 14.sp, color = Ink.TextLow)
            }
            inner()
        }
        Text(suffix, fontSize = 12.5.sp, color = Ink.TextMid)
    }
}

@Composable
internal fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.5.sp, color = Ink.TextMid)
        Text(v, fontSize = 13.sp, color = Ink.TextHi)
    }
}

@Composable
internal fun StepRow(no: String, text: String, chip: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
    ) {
        Text(text, fontSize = 12.5.sp, color = Ink.TextHi, modifier = Modifier.weight(1f))
        StatusChip(chip)
    }
}

// ─────────────────────── 房主 · 分享设置 ───────────────────────

/**
 * 本机屏幕的**像素**宽度。
 *
 * 刻意和采集侧取同一个来源（`ScreenShareController.displayGeometry` 读的也是
 * `WindowManager.currentWindowMetrics.bounds`）。标签要回答的是"这一档实际会采出多少宽"，
 * 两边量尺寸的口径必须一致 —— 换用 LocalWindowInfo / DisplayMetrics 之类，
 * 分屏和折叠态下就会给出和实际采集不同的数，文案当场变谎话。
 *
 * minSdk 33，currentWindowMetrics（API 30+）可直接用，无需版本兜底。
 */
@Composable
private fun rememberScreenWidthPx(): Int {
    val context = LocalContext.current
    return remember(context) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.currentWindowMetrics.bounds.width()
    }
}

/**
 * 分享设置：分辨率 / 帧率 / 码率 / 是否带画面。
 *
 * 全部是"开始分享时生效"的档位（见 [ShareQuality] 的注释：零服务器没法中途重协商）。
 * 麦克风开关刻意不在这里 —— 它是通话中的实时动作，已在控制岛上，
 * 重复语义只留一处（沿用 HaoAI/效果图的约定）。
 */
@Composable
fun QualitySettingsScreen(
    backdrop: LayerBackdrop,
    quality: ShareQuality,
    onChange: (ShareQuality) -> Unit,
    onOpenColorLab: () -> Unit,
    onOpenGlassLab: () -> Unit,
    onBack: () -> Unit,
) {
    fun indexOfOr(list: List<*>, value: Any?, default: Int): Int =
        list.indexOf(value).let { if (it >= 0) it else default }

    // 整页可滚：内容高于一屏（真机 3200px 下「完成」会掉出屏幕外，实测够不到）。
    // 滚动列里不能用 SpacerWeight（weight 在无限高约束下直接崩），所以这里只垫小间距。
    PageScaffold(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("分享设置", "这些是上限不是保证值：网络差或发热时会自动再降。开始分享时生效，本场通话内不可改。")

        // 实验室是**开发期的调参工具**：在 App 里拖滑杆调出满意配方 → 复制参数 → 写回代码。
        // 它不是给用户的功能，正式版不该出现（用户打开分享设置，要看的是画质，不是磨砂半径）。
        // 只在 debug 包暴露；正式版连入口都没有 ⇒ 两款滑杆页也就不可能被路由到。
        if (BuildConfig.DEBUG) {
            SectionTitle("实验室")
            GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                    LabEntry("圆钮颜色实验室", "调两个圆的雾色，复制参数发给 AI", onOpenColorLab)
                    LabEntry("玻璃参数实验室", "调磨砂/透镜/透明度，复制参数发给 AI", onOpenGlassLab)
                }
            }
        }

        SectionTitle("画质")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                SectionTitle("分辨率")
                // 标签按屏幕实际像素算 —— 采集分辨率 = 屏幕宽 × scale，而且**只能缩不能放**。
                // 写死"1080p"会让 2K 机型永远看不到 2K 档，而那本来就是它的能力；
                // 这一行刻意不做"自定义"：可选范围天然被屏幕卡死，填了也没用。
                // 标签算法放在 ShareQuality 里，和「当前组合」那行共用同一个实现 ——
                // 以前这页里有两套算法，2K 机上两行文案会互相打架。
                val screenW = rememberScreenWidthPx()
                SegmentRow(
                    options = ShareQuality.SCALES.map {
                        ShareQuality.resolutionLabelFor(it, screenW)
                    },
                    selected = indexOfOr(ShareQuality.SCALES, quality.scale, default = 1),
                ) { onChange(quality.copy(scale = ShareQuality.SCALES[it])) }
                StatusChip("本机屏幕宽 ${screenW}px，最高档就是原生分辨率", ChipTone.Neutral)

                SectionTitle("帧率")
                // 输入框的内容**由 quality 派生**，而不是"记住初值"：quality 是异步读盘的，
                // 用无 key 的 remember 会在读盘完成后残留默认值（本该空着的自定义格里写着 30），
                // 看着像用户自己设过自定义档。预设档一律把格子清空。
                var fpsFocused by remember { mutableStateOf(false) }
                var fpsText by remember {
                    mutableStateOf(
                        if (quality.fps in ShareQuality.FPSES) "" else quality.fps.toString()
                    )
                }
                // 用户正在打字时**不**覆盖（否则刚敲完 60 就被外部同步抹成空串）；
                // 焦点一离开就重新以 quality 为准，读盘完成、点了预设档都能反映过来。
                LaunchedEffect(quality.fps, fpsFocused) {
                    if (!fpsFocused) {
                        fpsText = if (quality.fps in ShareQuality.FPSES) "" else quality.fps.toString()
                    }
                }
                SegmentRow(
                    options = ShareQuality.FPSES.map { "$it" },
                    selected = indexOfOr(ShareQuality.FPSES, quality.fps, default = -1),
                    input = SegmentInputSpec(
                        placeholder = "自定义",
                        value = fpsText,
                        onValueChange = { raw ->
                            // 只收数字、最多 3 位。**在范围内才落盘**，范围外的半截数字
                            // 先留在框里 —— 想输 60 时先敲出来的那个 6 不该被立刻改写成 8。
                            val filtered = raw.filter { it.isDigit() }.take(3)
                            fpsText = filtered
                            filtered.toIntOrNull()
                                ?.takeIf { it in ShareQuality.FPS_MIN..ShareQuality.FPS_MAX }
                                ?.let { onChange(quality.copy(fps = it)) }
                        },
                        onFocusChange = { focused ->
                            if (focused) {
                                fpsFocused = true
                            } else {
                                // onFocusChanged 在首次组合时也会回调 false，所以整段必须幂等。
                                val n = fpsText.toIntOrNull()
                                    ?.coerceIn(ShareQuality.FPS_MIN, ShareQuality.FPS_MAX)
                                if (n != null && n != quality.fps) onChange(quality.copy(fps = n))
                                fpsText =
                                    if (n != null && n !in ShareQuality.FPSES) n.toString() else ""
                                fpsFocused = false
                            }
                        },
                    ),
                ) { onChange(quality.copy(fps = ShareQuality.FPSES[it])) }
                Text(
                    "单位 fps，可填 ${ShareQuality.FPS_MIN}–${ShareQuality.FPS_MAX}",
                    // TextLow(#7A7A7A) 是为深色实底准备的，压不住现在这张壁纸透出来的亮块
                    fontSize = 11.sp, color = Ink.TextMid,
                )

                SectionTitle("码率上限")
                val bpsToText = { bps: Int -> String.format(Locale.US, "%.1f", bps / 1_000_000f) }
                var bpsFocused by remember { mutableStateOf(false) }
                var bpsText by remember {
                    mutableStateOf(
                        if (quality.maxVideoBps in ShareQuality.BPS_LIST) ""
                        else bpsToText(quality.maxVideoBps)
                    )
                }
                LaunchedEffect(quality.maxVideoBps, bpsFocused) {
                    if (!bpsFocused) {
                        bpsText = if (quality.maxVideoBps in ShareQuality.BPS_LIST) ""
                        else bpsToText(quality.maxVideoBps)
                    }
                }
                SegmentRow(
                    options = ShareQuality.BPS_LIST.map { "${it / 1_000_000}M" },
                    selected = indexOfOr(ShareQuality.BPS_LIST, quality.maxVideoBps, default = -1),
                    input = SegmentInputSpec(
                        placeholder = "自定义",
                        value = bpsText,
                        onValueChange = { raw ->
                            val filtered = raw.filter { it.isDigit() || it == '.' }.take(5)
                            bpsText = filtered
                            filtered.toFloatOrNull()?.let { mbps ->
                                val bps = (mbps * 1_000_000).toInt()
                                if (bps in ShareQuality.BPS_MIN..ShareQuality.BPS_MAX) {
                                    onChange(quality.copy(maxVideoBps = bps))
                                }
                            }
                        },
                        onFocusChange = { focused ->
                            if (focused) {
                                bpsFocused = true
                            } else {
                                val bps = bpsText.toFloatOrNull()
                                    ?.let { (it * 1_000_000).toInt() }
                                    ?.coerceIn(ShareQuality.BPS_MIN, ShareQuality.BPS_MAX)
                                if (bps != null && bps != quality.maxVideoBps) {
                                    onChange(quality.copy(maxVideoBps = bps))
                                }
                                bpsText =
                                    if (bps != null && bps !in ShareQuality.BPS_LIST) bpsToText(bps)
                                    else ""
                                bpsFocused = false
                            }
                        },
                    ),
                ) { onChange(quality.copy(maxVideoBps = ShareQuality.BPS_LIST[it])) }
                Text("单位 Mbps，可填 0.1–50", fontSize = 11.sp, color = Ink.TextMid)

                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.TextLow.copy(alpha = 0.25f)))
                InfoRow("当前组合", quality.summary(screenW))
                InfoRow("流量上限估算", "约 ${quality.estMbPerMinute()} MB/分钟")
                // 这两句提到具体档位，所以必须跟着本机档位取名 —— 写死 "540p"/"1080p"
                // 会在 2K 机上指向根本不存在的选项（本机最低档叫 720p、最高档叫 2K）。
                StatusChip(
                    "省流量建议：${ShareQuality.lowestResolutionLabel(screenW)} · 30 帧 · 1M（约 8 MB/分钟）",
                    ChipTone.Ok,
                )
                StatusChip(
                    "${ShareQuality.highestResolutionLabel(screenW)} 或高码率在多数机型上会发热降帧",
                    ChipTone.Warn,
                )
            }
        }

        SectionTitle("分享内容")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                SegmentRow(
                    options = listOf("画面 + 语音", "仅语音"),
                    selected = if (quality.videoEnabled) 0 else 1,
                ) { onChange(quality.copy(videoEnabled = it == 0)) }
                if (quality.videoEnabled) {
                    StatusChip("开始分享时会弹系统投屏授权；对方看到的就是你的屏幕", ChipTone.Neutral)
                } else {
                    StatusChip("仅语音：不弹投屏授权，几乎不耗流量（约 0.3 MB/分钟），适合纯连麦", ChipTone.Ok)
                }
            }
        }

        SectionTitle("麦克风")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1)) {
                InfoRow("开关位置", "通话中控制岛上的麦克风按钮")
                Text(
                    "麦克风随时可静音/取消，不在这里设置 —— 这页只管「开始分享前」定下的画质。",
                    fontSize = 12.sp,
                    color = Ink.TextMid,
                    lineHeight = 17.sp,
                )
            }
        }

        // 没有「完成」按钮：这里的每一项都是**改了立即保存**（onChange 里就写盘了），
        // 系统返回键或左上角返回都能走。多一个确认键只会让人以为"不点就不生效"。
        StatusChip("改动立即生效，直接返回即可", ChipTone.Ok)
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

// ─────────────────────── 房主 · 系统授权指引 ───────────────────────

@Composable
fun ConsentGuideScreen(backdrop: LayerBackdrop, onContinue: () -> Unit, onBack: () -> Unit) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp4))
        Headline("接下来系统会问你两件事", "这两步决定朋友能不能看到、能不能听到。")

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                GuideStep(
                    "1", "选「整个屏幕」",
                    "Android 14 起弹窗默认落在「单个应用」，而且会先显示 Next。" +
                        "选错了，朋友就只能看到你当前那一个窗口 —— 这是最常见的「他看不到我画面」原因。" +
                        "（实测：改成整屏后确认按钮文案会变成「Share screen」）"
                )
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.TextLow.copy(alpha = 0.25f)))
                GuideStep(
                    "2", "允许麦克风",
                    "不开麦克风就只能分享画面，不能连麦。"
                )
            }
        }

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                Text("每次分享都要重新授权一次", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                Text(
                    "这是系统规则，不是本 App 的设置项，也无法绕过。中途锁屏也会自动停止分享（Android 15 QPR1+）。",
                    fontSize = 12.5.sp, color = Ink.TextMid,
                )
                StatusChip("请保持亮屏", ChipTone.Warn)
            }
        }

        SpacerWeight()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
            PrimaryPill("返回", onBack, backdrop, Modifier.weight(1f), filled = false)
            PrimaryPill("我知道了，继续", onContinue, backdrop, Modifier.weight(2f))
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

@Composable
private fun GuideStep(no: String, title: String, body: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp3),
    ) {
        Box(
            Modifier.size(GlassDimens.sp6),
            contentAlignment = Alignment.Center,
        ) {
            Text(no, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ink.AccentOnDark)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
            Text(body, fontSize = 12.5.sp, color = Ink.TextMid, lineHeight = 18.sp)
        }
    }
}

// ─────────────────── 通用过渡态（两个角色共用）───────────────────

/**
 * 一切"正在忙、用户没有别的事可做"的中间态都画在这一屏。
 *
 * 单独抽出来是因为房主和观众都会用到它：房主在等 ICE 收集、等打洞，
 * 观众在等生成应答、等房主点开回传链接。两边只需要换文案，
 * 不该为了一个标题把整屏布局抄第二遍。
 */
@Composable
fun PreparingScreen(
    backdrop: LayerBackdrop,
    title: String,
    note: String,
    hint: String? = null,
    onStop: () -> Unit,
) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Column(
            Modifier.fillMaxWidth().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
            ) {
                Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink.TextHi)
                Text(note, fontSize = 12.5.sp, color = Ink.TextMid)
                if (hint != null) {
                    Text(
                        hint,
                        fontSize = 11.5.sp,
                        color = Ink.TextLow,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        PrimaryPill("取消", onStop, backdrop, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

// ─────────────────────── 房主 · 邀请 / 等待回传 ───────────────────────

@Composable
fun InviteScreen(
    backdrop: LayerBackdrop,
    inviteUrl: String,
    onCopy: () -> Unit,
    onStop: () -> Unit,
) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("把这条发给朋友", "他点开就能看，不用装东西、也不用回传任何东西给你。")

        GlassCardPanel(backdrop, Modifier.fillMaxWidth(), floating = true) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                Text(
                    inviteUrl,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Ink.TextMid,
                    maxLines = 4,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                    PrimaryPill("复制邀请", onCopy, backdrop, Modifier.weight(1f))
                }
            }
        }

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                SectionTitle("朋友那边会发生什么")
                StepRow("①", "打开这条链接", "浏览器，免安装")
                StepRow("②", "点一下开始播放", "浏览器拦自动播放时才需要")
                StepRow("③", "画面和声音就过来了", "两端直连，不经中转")
            }
        }

        StatusChip("链接里有接入凭证，别转发给不想让看的人", ChipTone.Warn)
        StatusChip("你可以一直开着，他随时点开都能进", ChipTone.Ok)

        SpacerWeight()
        PrimaryPill("停止分享", onStop, backdrop, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

/**
 * 链接粘贴区。
 *
 * 空白时必须显示提示文字，两个理由：
 * - 一个纯黑的空框没人知道要往这里放什么；
 * - 脚本化验证（uiautomator）需要一个可寻址的文本节点，否则只能靠像素坐标点，
 *   换分辨率就废了。
 */
@Composable
fun PasteField(value: String, onChange: (String) -> Unit, hint: String = "长按粘贴") {
    Box(
        Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(Color.Black.copy(alpha = 0.32f), RoundedCornerShape(GlassDimens.radiusMd))
            .padding(GlassDimens.sp3),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onChange,
            maxLines = 4,
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ink.TextHi,
            ),
            modifier = Modifier.fillMaxSize(),
        )
        if (value.isEmpty()) {
            Text(hint, fontSize = 11.sp, color = Ink.TextLow, fontFamily = FontFamily.Monospace)
        }
    }
}

// ─────────────────────────── 失败 / 诊断 ───────────────────────────

/**
 * 失败 / 诊断屏。
 *
 * 这里以前把结论写死成"你们两家的网络类型对不上（其中一方在对称 NAT 之后）"——
 * 那是猜的：只要 ICE 失败就这一句。现在结论来自 [Verdict]，由双方候选类型与状态轨迹推出来，
 * 并把**证据本身**摊开显示：用户看得见"我拿到公网地址了、对方没拿到"，
 * 就知道该让对方换网络，而不是照着模板话瞎试。
 */
@Composable
fun FailedScreen(
    backdrop: LayerBackdrop,
    reason: String,
    verdict: com.ticketfortwo.app.rtc.Verdict? = null,
    onRetry: () -> Unit,
) {
    // 这一屏**必须能滚**：加了证据卡与"只有中继能救"那张之后，内容已经占满一屏，
    // 而 nextStep 的长短是运行时才知道的。原先用两个 SpacerWeight 居中，
    // 内容一超就双双塌成 0（截图上标题直接顶到状态栏），底部按钮随时会被裁掉。
    PageScaffold {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = GlassDimens.sp4),
                verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
            ) {
                Text(
                    verdict?.headline ?: reason,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ink.TextHi,
                )
                if (verdict != null) {
                    Text(
                        verdict.nextStep,
                        fontSize = 12.5.sp, color = Ink.TextMid, lineHeight = 18.sp,
                    )
                }
            }

            if (verdict != null) {
                GlassCardPanel(backdrop, Modifier.fillMaxWidth(), floating = true) {
                    Column(
                        Modifier.padding(GlassDimens.sp4),
                        verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
                    ) {
                        SectionTitle("根据什么这么判断")
                        verdict.evidence.forEach {
                            Text(it, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Ink.TextMid)
                        }
                    }
                }
            }

            // 只有证据指向"两边都有公网地址仍敲不通"时才提中继 ——
            // 否则等于把三种不同的病都推给同一个药，用户白折腾。
            if (verdict?.onlyRelayHelps == true) {
                FixCard(
                    backdrop, "这种情况只有中继能救", "无解于 P2P",
                    "两台手机都躲在运营商的地址转换后面，而且互相不认对方问到的地址。" +
                        "加一个中转服务器（TURN）让画面走第三方转发，是唯一稳的办法。"
                )
            }

            SectionTitle("按这个顺序试")
            // 原先第一条是「让他连你的热点」（同网必成）。删掉的理由是用户指出的场景事实：
            // 这个产品就是**异地**用的，"连同一个网络"根本做不到，摆在那儿等于让人白试。
            // 换成 VPN 这条 —— 它同样是"一改就好"，而且异地完全可执行。
            FixCard(backdrop, "① 让他先关掉 VPN 或代理", "开了就必然不通", "VPN 会让他问到的公网地址失效。先关掉再点链接，比换网络更值得先试。")
            FixCard(backdrop, "② 他换成 WiFi 或换 5G", "一半情况有效", "公司/校园网防火墙是主因；运营商 4G/5G 走 CGNAT，失败率明显更高。换一种网络等于换一个 NAT 类型。")
            FixCard(backdrop, "③ 重试一次", "候选顺序会变", "重发邀请会重新收集候选，偶尔就能通。")
            Spacer(Modifier.height(GlassDimens.sp2))
        }
        PrimaryPill("重试", onRetry, backdrop, Modifier.fillMaxWidth())
        Spacer(Modifier.height(GlassDimens.sp4))
    }
}

@Composable
private fun FixCard(backdrop: LayerBackdrop, title: String, chip: String, body: String) {
    GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                StatusChip(chip, if (chip == "几乎必成") ChipTone.Ok else ChipTone.Warn)
            }
            Text(body, fontSize = 12.sp, color = Ink.TextMid, lineHeight = 17.sp)
        }
    }
}
