package com.ticketfortwo.app.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink

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
    lastSummary: String?,
) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("分享你的手机", "最多 1 位朋友实时观看，并且能和你连麦说话。")

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                InfoRow("分享画质", "720p · 30 帧")
                InfoRow("麦克风", "开")
                if (lastSummary != null) InfoRow("上次连接", lastSummary)
            }
        }

        StatusChip("全程需要保持亮屏，锁屏会自动停止", ChipTone.Warn)

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("怎么连上", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                    StatusChip("三步")
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.TextLow.copy(alpha = 0.25f)))
                StepRow("①", "复制邀请链接发给朋友", "自带接入信息")
                StepRow("②", "朋友回传一条应答链接", "一次往返")
                StepRow("③", "你点开，画面就通了", "之后不经服务器")
            }
        }

        SpacerWeight()
        PrimaryPill("开始分享", onStart, backdrop, Modifier.fillMaxWidth())
        PrimaryPill("以观众进入", onJoinViewer, backdrop, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(GlassDimens.sp6))
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
    wireChars: Int,
    /** 网页观众端是否已经部署在真实域名上。假基址时必须说清楚，别让人复制出去才发现。 */
    linkLive: Boolean,
    onCopy: () -> Unit,
    pasteValue: String,
    onPasteChange: (String) -> Unit,
    onConnect: () -> Unit,
    onStop: () -> Unit,
) {
    PageScaffold {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("把这条发给朋友", "一期零服务器：链接里自带我的接收信息。朋友打开后会回给你一条「应答链接」，你再点开就连上了。")

        GlassCardPanel(backdrop, Modifier.fillMaxWidth(), floating = true) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                Text(
                    inviteUrl,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Ink.TextMid,
                    maxLines = 5,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                    PrimaryPill("复制邀请", onCopy, backdrop, Modifier.weight(1f))
                    StatusChip("链长 $wireChars 字符")
                }
            }
        }

        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                SectionTitle("这一步在等什么")
                // 三步的措辞必须跟着"网页端到底在不在"变：
                // 下面挂着红色那条"网页版还没上线"，这里却写"浏览器免安装"，两句话互相打脸。
                if (linkLive) {
                    StepRow("①", "朋友打开链接", "浏览器免安装")
                    StepRow("②", "他点「把这条发回去」", "生成应答链接")
                    StepRow("③", "你点开他发回的链接", "连接建立")
                } else {
                    StepRow("①", "朋友装好 App，用「以观众进入」粘贴", "当前唯一通路")
                    StepRow("②", "他回给你一条应答链接", "一次往返")
                    StepRow("③", "你点开他发回的链接", "连接建立")
                }
            }
        }
        if (!linkLive) {
            // 网页观众端还没部署到真实域名 —— 这条链接现在只有"装了这个 APK 的朋友"用得动。
            // 与其让人发出去才发现打不开，不如在这里就写明，并给出可用的那条路。
            StatusChip("网页版还没上线：这条链接目前要让朋友装 App，用「以观众进入」粘贴打开", ChipTone.Bad)
        }
        StatusChip("邀请链接里就带着接入信息，别转发给不想让看的人", ChipTone.Warn)

        SpacerWeight()
        SectionTitle("粘贴朋友的应答链接")
        PasteField(pasteValue, onPasteChange, hint = "长按粘贴对方回传的应答链接")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
            PrimaryPill("停止", onStop, backdrop, Modifier.weight(1f), filled = false)
            PrimaryPill("连接", onConnect, backdrop, Modifier.weight(1f))
        }
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

@Composable
fun FailedScreen(backdrop: LayerBackdrop, reason: String, onRetry: () -> Unit) {
    PageScaffold {
        SpacerWeight(0.4f)
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3),
        ) {
            Text("直连连不通", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.TextHi)
            Text(
                "你们两家的网络类型对不上（其中一方在对称 NAT 之后）。" +
                    "纯 P2P 没有中继兜底，这是这套架构的固有代价。",
                fontSize = 12.5.sp, color = Ink.TextMid,
            )
        }
        GlassCardPanel(backdrop, Modifier.fillMaxWidth(), floating = true) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                SectionTitle("诊断")
                Text(reason, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = Ink.Error)
            }
        }
        SectionTitle("按这个顺序试")
        FixCard(backdrop, "① 让他连你的热点", "几乎必成", "两端进同一个网络时只用本机地址就能直连，绕开全部打洞问题。")
        FixCard(backdrop, "② 他换成 WiFi 或换 5G", "一半情况有效", "公司/校园网防火墙是主因；运营商 4G/5G 走 CGNAT，失败率明显更高。")
        FixCard(backdrop, "③ 重试一次", "候选顺序会变", "重发邀请会重新收集候选，偶尔就能通。")
        SpacerWeight()
        PrimaryPill("重试", onRetry, backdrop, Modifier.fillMaxWidth())
        Spacer(Modifier.height(GlassDimens.sp6))
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
