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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink

/**
 * 观众端界面。同一个 APK 的第二个角色 —— 不另开一套设计，
 * 复用 HomeScreen 的骨架与同一批玻璃原子，避免两条路径长歪。
 */

@Composable
fun ViewerJoinScreen(
    backdrop: LayerBackdrop,
    value: String,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    error: String?,
) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("进入朋友的房间", "把他发给你的完整链接粘到下面。链接后半截就是接入信息，不需要服务器。")

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
                SectionTitle("进入之后还要做一件事")
                StepRow("①", "允许麦克风", "用于连麦")
                StepRow("②", "把生成的应答链接发回给他", "一次往返")
                StepRow("③", "他点开，画面就通了", "之后不经服务器")
            }
        }

        // 这句原本是房主视角的"我的声音会回到他手机"，放在观众屏上人称就反了。
        // 另外 StatusChip 是单行胶囊（maxLines=1），长句会被横向裁掉 —— 截图里就是
        // 裁在"耳机"上，半句话比没有更让人困惑。所以这里必须短。
        StatusChip("建议戴耳机：外放会让他的声音回到你的麦克风", ChipTone.Warn)

        SpacerWeight()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
            PrimaryPill("返回", onBack, backdrop, Modifier.weight(1f), filled = false)
            PrimaryPill("进入房间", onSubmit, backdrop, Modifier.weight(2f), enabled = value.isNotBlank())
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

/** 观众生成应答后：把链接发回房主。这一屏是"零服务器"的直接代价。 */
@Composable
fun ViewerAnswerScreen(
    backdrop: LayerBackdrop,
    answerUrl: String,
    wireChars: Int,
    onCopy: () -> Unit,
    onWaiting: Boolean,
    onStop: () -> Unit,
) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("把这条发回给他", "一期没有服务器，所以靠你把这条原样发回聊天窗口。他点开后就连上了，之后画面不再经过任何中转。")

        GlassCardPanel(backdrop, Modifier.fillMaxWidth(), floating = true) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                Text(
                    answerUrl,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Ink.TextMid,
                    maxLines = 6,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                    PrimaryPill("复制并回传", onCopy, backdrop, Modifier.weight(1f))
                    StatusChip("$wireChars 字符")
                }
            }
        }

        if (onWaiting) {
            StatusChip("等他点开… 期间可以先确认自己的麦克风没静音", ChipTone.Ok)
        }

        SpacerWeight()
        PrimaryPill("取消", onStop, backdrop, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}


