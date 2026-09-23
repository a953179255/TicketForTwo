package com.ticketfortwo.app.ui.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.ui.glass.GlassCard
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import java.io.File

/**
 * 两个「实验室」页面：色盘（调圆钮雾色）与玻璃参数（对齐 AndroidLiquidGlass
 * 官方 demo 实验室的参数集：模糊/折射高度/折射强度/色差/深度效果）。
 * 另提供自定义壁纸 —— 换一张纹理更丰富的图，磨砂和折射的对比会明显得多。
 *
 * 设计目的：用户说"具体怎么好看我说不出来，让我自己调" —— 所以这些页面
 * 只做三件事：**实时预览、滑杆调参、一键复制参数**。调出来的参数复制后发给
 * AI，由 AI 写死进代码（首页不读这里的值，避免状态跨页同步的复杂度）。
 */

// ─────────────────────────── 工具 ───────────────────────────

/** HSL 颜色（h 0-360；s/l 0-1）。浅色系调色用 HSL 比 RGB 直观得多。 */
data class HslColor(val h: Float, val s: Float, val l: Float) {
    fun toColor(): Color = hslToColor(h, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    fun hex(): String {
        val c = toColor()
        return "#%02X%02X%02X".format(
            (c.red * 255).toInt(),
            (c.green * 255).toInt(),
            (c.blue * 255).toInt(),
        )
    }
}

fun hslToColor(h: Float, s: Float, l: Float): Color {
    val c = (1 - Math.abs(2f * l - 1f)) * s
    val hp = ((h % 360f) + 360f) % 360f / 60f
    val x = c * (1 - Math.abs(hp % 2f - 1f))
    val r1: Float; val g1: Float; val b1: Float
    when {
        hp < 1f -> { r1 = c; g1 = x; b1 = 0f }
        hp < 2f -> { r1 = x; g1 = c; b1 = 0f }
        hp < 3f -> { r1 = 0f; g1 = c; b1 = x }
        hp < 4f -> { r1 = 0f; g1 = x; b1 = c }
        hp < 5f -> { r1 = x; g1 = 0f; b1 = c }
        else -> { r1 = c; g1 = 0f; b1 = x }
    }
    val m = l - c / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
    )
}

/** 玻璃实验室的参数集。默认值 = 当前首页圆钮的落地值（结构参数）。 */
data class LabGlassState(
    /** 默认值 = 用户实机调定的配方（2026-09-23），「恢复默认」回到它。 */
    val diameter: Float = 160f,
    val blur: Float = 10f,
    val lensHeight: Float = 22f,
    val lensAmount: Float = 2.495f,
    val chromatic: Boolean = false,
    val depthEffect: Boolean = true,
    val bright: Float = 0.20f,
    /** 雾色浓度。玻璃实验室用默认值；颜色实验室的预览浓度可调。 */
    val tintAlpha: Float = 0.80f,
)

/** 玻璃实验室的固定示例雾色（颜色本身去「圆钮颜色实验室」调）。OrbInk 深字色在 Screens.kt。 */
val LabTintDefault = Color(0xFFC4B0FF)

/** 实验室通用的「恢复默认参数」按钮。 */
@Composable
private fun LabResetRow(onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            "↺ 恢复默认参数",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.AccentSolid,
            modifier = Modifier
                .clickable(onClick = onReset)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 实验室页面的返回行。 */
@Composable
private fun LabBackRow(onBack: () -> Unit) {
    Row(
        Modifier
            .clickable(onClick = onBack)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) { Text("‹ 返回", fontSize = 14.sp, color = Ink.TextMid) }
}

/** 实验室滑杆行：名称 + 当前值 + 滑杆。 */
@Composable
private fun LabSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 12.5.sp, color = Ink.TextMid)
            Text(valueText, fontSize = 12.sp, color = Ink.TextHi, fontFamily = FontFamily.Monospace)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun LabCopyRow(text: String) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 11.5.sp,
            fontFamily = FontFamily.Monospace,
            color = Ink.TextHi,
            modifier = Modifier.weight(1f),
        )
        Text(
            "复制",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.AccentSolid,
            modifier = Modifier
                .clickable { clipboard.setText(AnnotatedString(text)) }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}

/** 磨砂圆实时预览：渲染路径与首页 GlassOrbEntry 完全一致，参数全部来自实验室滑杆。 */
@Composable
private fun LabOrbPreview(
    backdrop: LayerBackdrop,
    tint: Color,
    st: LabGlassState,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlassCard(
                onClick = {},
                backdrop = backdrop,
                modifier = Modifier.size(st.diameter.dp),
                shape = CircleShape,
                surfaceAlpha = 0f,   // 对齐官方 LiquidButton：不画磨砂底（那层会压暗）
                tint = tint.copy(alpha = st.tintAlpha),
                lensRadius = st.lensHeight.dp,
                lensAmountMul = st.lensAmount,
                chromaticAberration = st.chromatic,
                depthEffect = st.depthEffect,
                blurRadius = st.blur.dp,
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.White.copy(alpha = st.bright), CircleShape)
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("分享屏幕", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = OrbInk, letterSpacing = 1.4.sp)
                    Text("浏览器免安装", fontSize = 10.sp, color = OrbInk.copy(alpha = 0.60f))
                }
            }
            if (tint.alpha > 0.01f) {
                Spacer(Modifier.height(8.dp))
                Text("tint 色块", fontSize = 10.5.sp, color = Ink.TextLow)
                Box(
                    Modifier
                        .size(44.dp)
                        .background(tint.copy(alpha = st.tintAlpha), CircleShape)
                        .background(Color.White.copy(alpha = st.bright * 0.5f), CircleShape)
                )
            }
        }
    }
}

// ─────────────────────────── 色盘实验室 ───────────────────────────

@Composable
fun ColorLabScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    // 起点 = 用户实机调定的两个色（#1FE91F 亮绿 / #FF62AB 亮粉，2026-09-23）
    var shareHsl by remember { mutableStateOf(HslColor(120f, 0.76f, 0.52f)) }
    var viewHsl by remember { mutableStateOf(HslColor(332f, 0.45f, 0.69f)) }
    var tintAlpha by remember { mutableFloatStateOf(0.80f) }
    val clipboard = LocalClipboardManager.current

    PageScaffold(modifier = Modifier.verticalScroll(rememberScrollState())) {
        LabBackRow(onBack)
        Headline("圆钮颜色实验室", "调两个圆的雾色；满意后点「复制参数」，把参数发给 AI 写死进代码。")
        LabResetRow {
            shareHsl = HslColor(120f, 0.76f, 0.52f)
            viewHsl = HslColor(332f, 0.45f, 0.69f)
            tintAlpha = 0.80f
        }

        SectionTitle("分享圆（色块 = 当前纯色）")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(shareHsl.toColor(), CircleShape))
                    LabSlider("色相", "${shareHsl.h.toInt()}°", shareHsl.h, 0f..360f) { shareHsl = shareHsl.copy(h = it) }
                }
                LabSlider("饱和", "${(shareHsl.s * 100).toInt()}%", shareHsl.s, 0f..1f) { shareHsl = shareHsl.copy(s = it) }
                LabSlider("亮度", "${(shareHsl.l * 100).toInt()}%", shareHsl.l, 0.35f..0.95f) { shareHsl = shareHsl.copy(l = it) }
            }
        }

        SectionTitle("观看圆（色块 = 当前纯色）")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).background(viewHsl.toColor(), CircleShape))
                    LabSlider("色相", "${viewHsl.h.toInt()}°", viewHsl.h, 0f..360f) { viewHsl = viewHsl.copy(h = it) }
                }
                LabSlider("饱和", "${(viewHsl.s * 100).toInt()}%", viewHsl.s, 0f..1f) { viewHsl = viewHsl.copy(s = it) }
                LabSlider("亮度", "${(viewHsl.l * 100).toInt()}%", viewHsl.l, 0.35f..0.95f) { viewHsl = viewHsl.copy(l = it) }
            }
        }

        // 雾色浓度在这里调（玻璃实验室不管颜色），磨砂圆的预览用它
        SectionTitle("雾色浓度（磨砂预览用）")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4)) {
                LabSlider("浓度", "${(tintAlpha * 100).toInt()}%", tintAlpha, 0f..0.80f) { tintAlpha = it }
            }
        }

        SectionTitle("磨砂圆实时预览（blur 15）")
        LabOrbPreview(backdrop, shareHsl.toColor(), LabGlassState(tintAlpha = tintAlpha))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlassCard(
                    onClick = {},
                    backdrop = backdrop,
                    modifier = Modifier.size(126.dp),
                    shape = CircleShape,
                    surfaceAlpha = 0f,
                    tint = viewHsl.toColor().copy(alpha = tintAlpha),
                    lensRadius = 22.dp,
                    lensAmountMul = 2f,
                    blurRadius = 15.dp,
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.matchParentSize().background(Color.White.copy(alpha = 0.30f), CircleShape))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("进入观看", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = OrbInk, letterSpacing = 1.4.sp)
                        Text("粘上链接就能看", fontSize = 10.sp, color = OrbInk.copy(alpha = 0.60f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("↑ 观看圆", fontSize = 10.5.sp, color = Ink.TextLow)
            }
        }

        SectionTitle("参数（复制发给 AI）")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabCopyRow("OrbTintViolet = Color(0xFF${shareHsl.hex().removePrefix("#")})")
                LabCopyRow("OrbTintPink = Color(0xFF${viewHsl.hex().removePrefix("#")})")
                LabCopyRow("雾色浓度（预览）= ${(tintAlpha * 100).toInt()}%")
                Text(
                    "复制后发给 AI，会替换 Screens.kt 里这两个常量；浓度滑杆帮助你判断在真实磨砂下的观感。",
                    fontSize = 11.sp, color = Ink.TextLow,
                )
            }
        }
        Spacer(Modifier.height(GlassDimens.sp6))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "复制两行参数",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ink.AccentSolid,
                modifier = Modifier
                    .clickable {
                        clipboard.setText(
                            AnnotatedString(
                                "OrbTintViolet = Color(0xFF${shareHsl.hex().removePrefix("#")})\n" +
                                    "OrbTintPink = Color(0xFF${viewHsl.hex().removePrefix("#")})",
                            ),
                        )
                    }
                    .padding(10.dp),
            )
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

// ─────────────── 玻璃参数实验室（对齐 AndroidLiquidGlass demo）───────────────

@Composable
fun GlassLabScreen(backdrop: LayerBackdrop, onBack: () -> Unit) {
    var st by remember { mutableStateOf(LabGlassState()) }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    // 自定义壁纸：从相册/文件选一张，复制到私有目录并设为全局壁纸
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            runCatching {
                val dst = File(ctx.filesDir, "custom-wallpaper.img")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                AppWallpaper.set(ctx, dst.absolutePath)
            }
        }
    }

    val paramsText = buildString {
        appendLine("size = ${st.diameter.toInt()}.dp")
        appendLine("blurRadius = ${st.blur.toInt()}.dp")
        appendLine("折射高度 lensHeight = ${st.lensHeight.toInt()}.dp")
        appendLine("折射强度 lensAmountMul = ${st.lensAmount}")
        appendLine("色差 chromaticAberration = ${st.chromatic}")
        appendLine("深度效果 depthEffect = ${st.depthEffect}")
        appendLine("白雾提亮 brightAlpha = 0.%02df".format((st.bright * 100).toInt()))
        append("（雾色去「圆钮颜色实验室」调）")
    }

    PageScaffold(modifier = Modifier.verticalScroll(rememberScrollState())) {
        LabBackRow(onBack)
        Headline("玻璃参数实验室", "对齐 AndroidLiquidGlass demo 的参数集：实时预览，调好后复制参数发给 AI。")
        LabResetRow { st = LabGlassState() }

        SectionTitle("壁纸")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "换一张纹理丰富的图（照片/图案都行），磨砂和折射的对比会明显得多。",
                    fontSize = 11.5.sp, color = Ink.TextLow,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "选择壁纸",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.AccentSolid,
                        modifier = Modifier
                            .clickable { pickWallpaper.launch("image/*") }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                    Text(
                        "恢复默认壁纸",
                        fontSize = 13.sp,
                        color = Ink.TextMid,
                        modifier = Modifier
                            .clickable {
                                File(ctx.filesDir, "custom-wallpaper.img").delete()
                                AppWallpaper.set(ctx, null)
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }

        SectionTitle("玻璃参数")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabSlider("圆直径", "${st.diameter.toInt()}dp", st.diameter, 100f..160f) { st = st.copy(diameter = it) }
                LabSlider("模糊半径", "${st.blur.toInt()}dp", st.blur, 0f..30f) { st = st.copy(blur = it) }
                LabSlider("折射高度", "${st.lensHeight.toInt()}dp", st.lensHeight, 0f..30f) { st = st.copy(lensHeight = it) }
                LabSlider("折射强度", "×${"%.1f".format(st.lensAmount)}", st.lensAmount, 0.5f..4f) { st = st.copy(lensAmount = it) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("色差（红蓝分离）", fontSize = 12.5.sp, color = Ink.TextMid)
                    Switch(checked = st.chromatic, onCheckedChange = { st = st.copy(chromatic = it) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("深度效果", fontSize = 12.5.sp, color = Ink.TextMid)
                    Switch(checked = st.depthEffect, onCheckedChange = { st = st.copy(depthEffect = it) })
                }
                LabSlider("白雾提亮", "${(st.bright * 100).toInt()}%", st.bright, 0f..0.60f) { st = st.copy(bright = it) }
            }
        }

        SectionTitle("实时预览（纯玻璃 · 无染色）")
        LabOrbPreview(backdrop, Color.Transparent, st)

        SectionTitle("参数（复制发给 AI）")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    paramsText,
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Ink.TextHi,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text(
                        "复制参数",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.AccentSolid,
                        modifier = Modifier
                            .clickable { clipboard.setText(AnnotatedString(paramsText)) }
                            .padding(10.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}
