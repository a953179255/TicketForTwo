package com.ticketfortwo.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink

/**
 * 观众端界面。
 *
 * 这一版把观众路径整体让给了浏览器：App 不再自己收流，只负责把链接交出去。
 * 理由很实际 —— 浏览器是观众端的唯一实现，也是唯一被真正测过的路径；
 * 再在 App 里用 Kotlin 写一遍 WebSocket 客户端，等于凭空多一份要维护、
 * 要单独验证的实现，而收益是零。
 */

@Composable
fun ViewerJoinScreen(
    backdrop: LayerBackdrop,
    value: String,
    onChange: (String) -> Unit,
    /** 主路径：在 App 内直接收看（不跳浏览器）。 */
    onSubmit: () -> Unit,
    /** 备用路径：交给系统浏览器 —— 不想装 App 的朋友仍然点链接就能看。 */
    onOpenInBrowser: () -> Unit,
    onBack: () -> Unit,
    error: String?,
) {
    // 横屏（411dp 高）这一屏的**固定**部分本来就装不下：标题 + 输入卡(86dp) +
    // 三颗按钮(52+52) + 间距 ≈ 439dp，比可用高度多 ~70dp —— 于是最后那排被屏幕底边
    // 切掉半截（实测「返回」只剩 8px 高）。滚动区只能吸收"多余"，救不了"本来就超"，
    // 所以横屏把输入框压到 56dp、按钮压到 44/48dp（44dp 仍是触控下限）。
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp > cfg.screenHeightDp

    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("进入朋友的房间", "把房主发来的链接粘到下面，直接在这个 App 里看。")

        // 输入框这张卡**永远不滚**：它是这一屏唯一能让用户提交链接的地方，
        // 滚出可视区等于这一屏没有入口。
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                SectionTitle("邀请链接")
                PasteField(
                    value,
                    onChange,
                    hint = "长按粘贴房主发来的邀请链接",
                    boxHeight = if (wide) 56.dp else 86.dp,
                )
                if (error != null) {
                    Text(error, fontSize = 11.5.sp, color = Ink.Error, fontFamily = FontFamily.Monospace)
                }
            }
        }

        /* 「怎么用」那张卡 + 提示条放进一个**自己可滚**的区域：
           横屏（411dp 高）实测两张卡 + 提示条会把「在 App 内观看」整颗主按钮挤到屏幕外，
           而这一屏没有第二入口 —— 观众连链接都提交不了（.dev/join-landscape2.png：
           说明行被底边切成 3px 高的一条）。矮屏滚说明，高屏看不出任何变化。
           注意不能把上面的输入框一起放进来：滚动容器给子项的是"无限高"约束，
           PasteField 那个 86dp 的框在里面会塌成 2px（同一轮实测）。 */
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
        ) {
            GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                    SectionTitle("怎么用")
                    StepRow("①", "粘上房主发来的链接", "直接在这个 App 里看")
                    StepRow("②", "想说话就先允许麦克风", "只看不点也行")
                    StepRow("③", "画面直接过来", "两端直连，不经服务器")
                }
            }

            // 这句原本是房主视角的"我的声音会回到他手机"，放在观众屏上人称就反了。
            // 另外 StatusChip 是单行胶囊（maxLines=1），长句会被横向裁掉 —— 截图里就是
            // 裁在"耳机"上，半句话比没有更让人困惑。所以这里必须短。
            StatusChip("建议戴耳机：外放会让他的声音回到你的麦克风", ChipTone.Warn)
        }
        PrimaryPill(
            "在 App 内观看", onSubmit, backdrop, Modifier.fillMaxWidth(),
            enabled = value.isNotBlank(), height = if (wide) 48.dp else 52.dp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
            PrimaryPill("返回", onBack, backdrop, Modifier.weight(1f), filled = false, height = if (wide) 44.dp else 52.dp)
            PrimaryPill(
                "用浏览器打开", onOpenInBrowser, backdrop, Modifier.weight(1f),
                filled = false, enabled = value.isNotBlank(), height = if (wide) 44.dp else 52.dp,
            )
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}
