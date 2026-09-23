package com.ticketfortwo.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("进入朋友的房间", "把房主发来的链接粘到下面，直接在这个 App 里看。")

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                SectionTitle("邀请链接")
                PasteField(value, onChange, hint = "长按粘贴房主发来的邀请链接")
                if (error != null) {
                    Text(error, fontSize = 11.5.sp, color = Ink.Error, fontFamily = FontFamily.Monospace)
                }
            }
        }

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

        SpacerWeight()
        PrimaryPill("在 App 内观看", onSubmit, backdrop, Modifier.fillMaxWidth(), enabled = value.isNotBlank())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
            PrimaryPill("返回", onBack, backdrop, Modifier.weight(1f), filled = false)
            PrimaryPill("用浏览器打开", onOpenInBrowser, backdrop, Modifier.weight(1f), filled = false, enabled = value.isNotBlank())
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}
