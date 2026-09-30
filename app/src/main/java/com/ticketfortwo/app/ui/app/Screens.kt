package com.ticketfortwo.app.ui.app

import android.content.Context
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.PlayMode
import com.ticketfortwo.app.ShareQuality
import com.ticketfortwo.app.VoiceMode
import com.ticketfortwo.app.ui.glass.GlassCard
import com.ticketfortwo.app.ui.glass.GlassTextButton
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

/**
 * 首页那颗「分享画面」圆的"会话进行中"形态（方案二：圆钮变身）。
 *
 * 会话是前台服务撑着的；用户按返回回到首页，**不是**结束分享 —— 可一个还在跑的
 * 会话如果首页上看不见，就只剩通知栏知道，而"分享到底还在不在"恰恰是用户在问的
 * 问题（原话："在放映厅点返回以后，不应该结束分享"）。所以圆钮变身成状态：
 * 圆内写现在在干什么，点圆回到对应的会话屏，圆下一颗小字钮负责结束整场。
 * 「进入观看」在这一态下让位隐藏 —— 自己正在放片的人不需要"进入观看"。
 */
/* ── 方案C · 链接感应 ──
   看片的真实起点往往不是"打开 App"，而是"我在别的 App 复制了一条链接"。
   进首页时瞄一眼剪贴板，有链接就浮一张卡问一句"开厅一起看？"——点一下，
   开厅+填链接+出邀请一次完成（动作走 enterCinema，见 MainActivity）。 */

/** 本进程里已对哪条剪贴板链接说了「不了」——同一条不再问，换了新链接才再问。 */
private var dismissedClipUrl: String? = null

/** 从一段文本里抽出第一条 http(s) 链接（正则与放映厅的粘贴芯片共用）。 */
private fun extractClipUrl(text: String): String? =
    clipUrlRegex.find(text)?.value?.trimEnd('，', ',', ')', '）', '》', '>', '。', '.')

data class HomeSession(
    /** 圆内标题：放映厅已开 / 正在分享 / 语音连麦中 / 等对方加入。 */
    val label: String,
    /** 一行状态：等对方进来 / 对方已在厅里 / 1 人正在观看 … */
    val status: String,
    /** 圆下方那颗小字钮：关闭放映厅 / 停止分享 / 结束连麦。 */
    val stopLabel: String,
    /** 点圆：回到对应的会话屏。 */
    val onReturn: () -> Unit,
    /** 小字钮：结束整场（会话级，与 CallSession.stop 同一动作）。 */
    val onStop: () -> Unit,
)

@Composable
fun HomeScreen(
    backdrop: LayerBackdrop,
    onStart: () -> Unit,
    onJoinViewer: () -> Unit,
    onSettings: () -> Unit,
    quality: ShareQuality,
    lastSummary: String?,
    /** 非空 = 会话进行中、人退到了首页：圆钮变身 + 圆下停止钮 + 「进入观看」隐藏。 */
    session: HomeSession? = null,
    /** 方案C：点浮卡「开厅一起看」——带这条链接去开厅（MainActivity 的 enterCinema）。 */
    onWatchUrl: (String) -> Unit = {},
) {
    // 横屏（含平板、折叠屏展开）单独一套排法，见下面 wideHome 的两处分支。
    // 判据用**屏幕**长宽比，不用某一块容器的：信息卡那边也要同一个结论，
    // 两处各算各的会出现"圆并排了、信息卡还竖着堆三行"的半吊子布局。
    val context = LocalContext.current
    val cfg = LocalConfiguration.current
    val wideHome = cfg.screenWidthDp > cfg.screenHeightDp
    // 不 remember：授权之后这一屏要立刻改口，而 remember 会把"未授权"钉在屏幕上。
    val micGranted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /* 方案C 检测：每次回到首页瞄一眼剪贴板（runCatching 兜住没有剪贴板服务的设备）。
       - 只在空闲会话下问 —— 正在分享时圆钮已变身，别再叠一张卡；
       - 自己厅的邀请（trycloudflare）不问：朋友复制着邀请链接进来，该走的是
         「进入观看」，不是开自己的厅把邀请页当影片加载；
       - 点「不了」记下这条，同一条不再骚扰；换了新链接（又一个新决定）才再问。
       安卓 12+ 读剪贴板系统会弹一次"已从剪贴板粘贴"提示，那是系统规则，不是 App 偷看。 */
    var clipCardUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(session == null) {
        // key 用"会话是否空闲"而不是 Unit：关厅回到首页这一刻（session 翻回 null）
        // 也要检测 —— 看完关掉厅、想起刚复制的链接，是常见路径。
        if (session != null) return@LaunchedEffect
        val found = runCatching {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.primaryClip?.let { c ->
                (0 until c.itemCount).asSequence()
                    .mapNotNull { c.getItemAt(it)?.text?.toString() }
                    .firstNotNullOfOrNull(::extractClipUrl)
            }
        }.getOrNull()
        if (found != null && found != dismissedClipUrl) clipCardUrl = found
    }

    PageScaffold {
        Headline("双人票", "把你的屏幕，变成你和朋友的私人影院。")

        /* 剪贴板浮卡（方案C）：放标题下面、圆钮上面 —— 它是当下最可能的意图，
           但不该盖住入口本身。点「开厅一起看」= enterCinema：厅、邀请、页面一次到位。 */
        if (clipCardUrl != null && session == null) {
            GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(GlassDimens.sp3),
                    verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.height(7.dp).width(7.dp).clip(CircleShape)
                                .background(Ink.Live),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "发现剪贴板里有一条链接",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.TextHi,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        clipCardUrl!!,
                        fontSize = 11.5.sp,
                        color = Ink.TextMid,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                        PrimaryPill(
                            "开厅一起看 →",
                            onClick = {
                                val u = clipCardUrl ?: return@PrimaryPill
                                dismissedClipUrl = u
                                clipCardUrl = null
                                onWatchUrl(u)
                            },
                            backdrop,
                            Modifier.weight(1f),
                            height = 44.dp,
                        )
                        PrimaryPill(
                            "不了",
                            onClick = {
                                dismissedClipUrl = clipCardUrl
                                clipCardUrl = null
                            },
                            backdrop,
                            Modifier.weight(0.42f),
                            filled = false,
                            height = 44.dp,
                        )
                    }
                }
            }
        }

        // 圆形双入口（效果图 home-orbs-pastel3.html 方案 2：丁香紫 × 樱花粉）。
        // 按用户要求：只改这两个圆的效果，页面其余部分保持原样。
        //
        // 尺寸自适应：直径以 160dp（实机调定值）为上限，但不超过可用高度的 44%。
        // 两个 160dp 的圆在矮屏上会把底部"分享设置"挤出屏幕（实测被裁掉半截），
        // 所以这里按可用空间收缩 —— 高屏手机上依然显示完整的 160dp。
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            /**
             * **横屏是另一种排法**（2400x1080 实测，.dev/home-landscape.png）：沿用竖排时
             * weight(1f) 只给到 ~150dp 高，圆被压到 66dp —— 圆里的标题整个被圆形裁剪吃掉
             * （dump 里连「分享屏幕」这个节点都没有），第二颗圆「进入观看」直接掉到屏外，
             * 于是首页在横屏下**只剩一个能用的入口**。横屏改成两颗圆并排：宽度有的是
             * （2400px），高度反而能按可用的 72% 给，标题也装得下了。
             */
            val wide = wideHome
            /* 下限 96dp：「放映厅已开」那张卡会挤掉 weight(1f) 的预算 —— 横屏实测圆塌到
               ~20dp、图标和标题被 CircleShape 裁没，两个主入口等于消失
               （REVIEW-2026-09-27 P1，2026-09-27 模拟器截图复现）。低于容器就靠
               这块自己的滚动看全：圆消失比"圆要滚一下"糟得多。 */
            val orbSize = minOf(160.dp, (maxHeight * if (wide) 0.72f else 0.44f).coerceAtLeast(96.dp))
            // 圆里装得下三行内容的经验下限（图标 34 + 标题 + 说明 + 间距）；
            // 低于它就把说明改画到圆下面，别让文字溢出圆外压住下一个圆。
            val subOutside = wide || orbSize < 128.dp
            /* 矮屏上这两颗圆 + 两行说明就是装不进 weight(1f) 给的那点高度（实测 594dp 高的
               机器上第二颗圆被底部信息卡压掉半截）。让**这一块自己可滚**：
               高屏内容放得下 → 看不出任何变化；矮屏 → 能滚着看完，而不是叠在一起。 */
            /* 会话进行中（session 非空）：圆钮变身成状态 —— 标题写现在在干什么，
               点圆回到对应的会话屏；「进入观看」让位隐藏（自己正在放片的人用不上）。
               空闲时才是普通的分享入口。 */
            val shareOrb = @Composable {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassOrbEntry(
                        onClick = session?.onReturn ?: onStart,
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
                        label = session?.label ?: "分享画面",
                        /* 这颗圆原来叫「分享屏幕」，而「放映厅」是首页底下另一颗胶囊 ——
                           可这两件事其实是同一件事：**把画面给出去**。分成两个入口，
                           用户就得先懂"投屏"和"放映厅"的区别才点得对（点了屏幕分享才发现
                           对方在看他翻相册）。现在收成一个入口，进去再选（见 ShareKindScreen）。
                           名字也跟着改成"分享画面"：它承诺的是结果，不是一种技术。
                           会话进行中它变身成状态（见 HomeSession），承诺不变。 */
                        sub = session?.status ?: "选屏幕，或选一部片",
                        subOutside = subOutside,
                    )
                    if (session != null) {
                        Spacer(Modifier.height(8.dp))
                        // 圆下一行小字钮：结束整场（方案二）。会话级动作，与 CallSession.stop 同一落点。
                        GlassTextButton(session.stopLabel, onClick = session.onStop, backdrop)
                    }
                }
            }
            val joinOrb = @Composable {
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
                    subOutside = subOutside,
                )
            }
            if (session != null) {
                // 会话进行中：只画变身后的圆（含停止钮），「进入观看」隐藏。
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    shareOrb()
                }
            } else if (wide) {
                // 横屏：两颗圆并排。间距按"说明那行不会压到邻圆"给（说明最长 9 个字 ≈ 120dp，
                // 圆半径 ~54dp，所以 48dp 的缝只是视觉间距，真正不重叠靠各自的宽度）。
                Row(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    shareOrb()
                    joinOrb()
                }
            } else {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    shareOrb()
                    // 说明挪到圆外之后，两颗圆之间要多留一点：原来固定 16dp，
                    // 而那行小字自己就有 ~19dp 高，会贴着下一个圆的上沿。
                    Spacer(Modifier.height(if (subOutside) 30.dp else 16.dp))
                    joinOrb()
                }
            }
        }

        // 信息卡（原样保留：画质 / 流量 / 麦克风 / 上次连接）。
        // 圆放大到 160dp 后垂直空间变紧，这里的内边距与行距各收一档。
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            val info = buildList {
                add("声音" to VoiceMode.label(quality.voiceMode))
                if (quality.videoEnabled) {
                    add("分享画质" to quality.pictureSummary(rememberScreenWidthPx()))
                    add("流量上限" to "约 ${quality.estMbPerMinute()} MB/分钟")
                }
                /* 「麦克风 开」这一行以前是**写死的**，跟真实权限、跟声音档都没关系。
                   现在多了「只有视频声」这一档（放映厅里对方本地播时麦克风会被自动关掉），
                   这句假话就更容易误导人 —— 首页也说不出"这场麦克风到底开不开"，
                   因为那要等对方的回执才知道。所以这里只说唯一一件首页能确定的事：
                   权限给没给。*/
                add("麦克风" to if (micGranted) "已授权" else "未授权（不能连麦）")
                if (lastSummary != null) add("上次连接" to lastSummary)
            }
            if (wideHome) {
                /* 横屏把这张卡**摊成一行**：竖排四行要 ~95dp，而横屏整屏只有 411dp 高，
                   两颗圆并排后剩下的空间刚好不够（实测信息卡会把第二颗圆挤出可视区）。
                   横屏宽度有 900dp，一行放得下，于是高度只花 ~24dp。 */
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = GlassDimens.sp3, vertical = GlassDimens.sp2)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    info.forEach { (k, v) -> InfoChip(k, v) }
                }
            } else {
                Column(Modifier.padding(GlassDimens.sp3), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                    info.forEach { (k, v) -> InfoRow(k, v) }
                }
            }
        }

        // 底部只留「分享设置」一颗：放映厅不再是首页的并列入口了 ——
        // 它和"分享屏幕"是同一件事（把画面给出去）的两个选项，收在绿色那颗圆后面。
        // 会话进行中仍然能直接进厅（CallScreen 上有那颗入口），这里收掉不会走进死路。
        Column {
            PrimaryPill("分享设置", onSettings, backdrop, Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ─────────────────── 首页 · 分享画面：选一种给出去 ───────────────────

/**
 * 「分享画面」的第二步：**给什么**。
 *
 * 原来首页上「分享屏幕」和「放映厅」是并列的两个入口，而这俩其实是同一件事
 * （把画面给出去）的两条路。并列的后果是：用户点「分享屏幕」之前得先想明白
 * "我要对方看我翻相册，还是看一部片子" —— 而多数人根本不知道这两者的区别，
 * 于是点了投屏、对方就跟着看他切了三回微信。
 *
 * 收成一个入口之后，这一屏负责把区别在**点之前**说清楚：
 * 各自"对方看到什么"、各自的前置条件，以及哪一条是默认的推荐。
 * 推荐放映厅不是偏好问题：它是原生画质、不占用房主的屏幕，
 * 而投屏那条路要压一层系统缩放、还把房主的隐私一起播出去。
 */
@Composable
fun ShareKindScreen(
    backdrop: LayerBackdrop,
    quality: ShareQuality,
    onPickScreen: () -> Unit,
    onPickCinema: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val cfg = LocalConfiguration.current
    val wide = cfg.screenWidthDp > cfg.screenHeightDp

    val cinemaCard = @Composable {
        KindCard(
            backdrop = backdrop,
            title = "同步放映",
            chip = "推荐",
            chipTone = ChipTone.Ok,
            body = "你挑片子，对方那台手机自己播同一条地址 —— 画质是原生的，" +
                "你的屏幕不会被播出去，回消息、切应用都不影响他看。",
            foot = "厅先开：先发链接给他，再选片",
            onClick = onPickCinema,
        )
    }
    val screenCard = @Composable {
        KindCard(
            backdrop = backdrop,
            title = "分享我的屏幕",
            chip = "要系统授权",
            chipTone = ChipTone.Neutral,
            body = "对方同步看到你手机上的一切，而且跟着你走：切应用、翻相册、打字他那边都看得见。" +
                "适合一起逛网页、演示个东西。",
            foot = "每次都要重新授权一次，这是系统规则",
            onClick = onPickScreen,
        )
    }

    /* 整页一律可滚，两张卡**不给 weight(1f)**。
     *
     * 第一版横屏给 Row 加了 weight(1f)，实测（.dev/kind-03-chooser.png）两张卡被压成
     * 170dp 高的空壳、正文只剩 2px —— 这一屏存在的意义就是那句"对方会看到什么"，
     * 它被吃掉等于没做。weight 在矮屏上分给卡片的空间比内容需要的少，
     * 而 Compose 不会因此把页面撑开，只会裁。改成"卡片按内容长、页面不够就滚"。 */
    PageScaffold {
        /* 滚动只包内容，「返回」留在滚动列**外面**、由外层 Column 的 weight(1f)
           吃掉剩余高度 —— 用户反馈"返回按钮太靠上了"就是整页滚动的后果：
           按钮跟着内容排，内容短时它悬在屏幕半空，下面一大片壁纸。
           FailedScreen 已是这个结构（滚动列 weight(1f)，按钮钉底），这里对齐它。 */
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
        ) {
            Spacer(Modifier.height(GlassDimens.sp4))
            Headline("分享画面", "先选给对方看什么。两种都可以中途换，不用重来。")
            if (wide) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp3),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) { cinemaCard() }
                    Box(Modifier.weight(1f)) { screenCard() }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                    cinemaCard()
                    screenCard()
                }
            }
            // 声音档放在这一屏说一次：它决定"对方听不听得到你说话"，
            // 而多数人是在这里才第一次意识到"原来默认不连麦"。
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "声音：${VoiceMode.label(quality.voiceMode)}",
                    fontSize = 12.sp, color = Ink.TextMid, modifier = Modifier.weight(1f),
                )
                GlassTextButton("去改", onClick = onSettings, backdrop = backdrop)
            }
        }
        PrimaryPill("返回", onBack, backdrop, Modifier.fillMaxWidth(), filled = false)
        // 底部要留够：横屏时系统那根手势白条正好压在按钮上（实测 .dev/kind-08-land.png
        // 里「返回」和白条重叠），navigationBarsPadding 在这一屏没替我们让开。
        Spacer(Modifier.height(34.dp))
    }
}

/** 选择页上的一张卡：整张可点，标题 + 一句"对方看到什么" + 前置条件。 */
@Composable
private fun KindCard(
    backdrop: LayerBackdrop,
    title: String,
    chip: String,
    chipTone: ChipTone,
    body: String,
    foot: String,
    onClick: () -> Unit,
) {
    GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(GlassDimens.sp4),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi,
                    modifier = Modifier.weight(1f),
                )
                StatusChip(chip, chipTone)
            }
            Text(body, fontSize = 12.5.sp, color = Ink.TextMid, lineHeight = 18.sp)
            Text(foot, fontSize = 11.sp, color = Ink.TextLow, lineHeight = 15.sp)
        }
    }
}

// ─────────────────────────── 首页 · 圆形磨砂入口 ───────────────────────────

/**
 * 圆钮雾色 —— 用户实机调定（2026-09-23）。改色只需改这两行。
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
    /**
     * 小字说明放圆内还是圆外。
     *
     * 圆会按可用高度收缩（矮屏上两个 160dp 的圆会把底部设置卡挤出屏幕），
     * 但里面的图标 + 标题 + 说明是**固定字号**的 —— 圆缩到 120dp 以下就装不下了，
     * 说明那行会从圆的下沿溢出去，压在下一个圆上（274dp 宽 / 594dp 高的机器上
     * 实测：圆 83dp、文字一直排到圆外 88px）。装不下就把它挪到圆下面，
     * 圆内只留图标和标题。
     */
    subOutside: Boolean = false,
    // 以下默认值 = 用户实机调定的配方（2026-09-23）。要调就直接改这里的默认值。
    diameter: Dp = 160.dp,
    blurRadius: Dp = 10.dp,
    lensRadius: Dp = 22.dp,
    lensAmountMul: Float = 2.495f,
    tintAlpha: Float = 0.80f,
    brightAlpha: Float = 0.20f,
) {
    // 自己包一层 Column：`subOutside` 那行小字**必须**紧跟在圆下面。
    // 之前它是这个函数的第二个兄弟节点，等于把"我是竖排的"这个假设交给调用方 ——
    // 首页横屏改成两颗圆并排时，说明文字就顺着 Row 跑到圆的右边去了（2400x1080 实测）。
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        // 白雾提亮层：在通透玻璃上加一层柔白光（浓度实机调定）。
        // 圆内深字也没有它会更清晰。
        Box(
            Modifier
                .matchParentSize()
                .background(Color.White.copy(alpha = brightAlpha), CircleShape)
        )
        Column(
            modifier = Modifier.padding(top = if (subOutside) 0.dp else 6.dp),
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
            if (!subOutside) Text(sub, fontSize = 10.sp, color = OrbInk.copy(alpha = 0.60f))
        }
    }
    if (subOutside) {
        Text(
            sub,
            fontSize = 10.sp,
            // 圆**外面**那行小字压在壁纸上，不是压在玻璃上：TextLow(#7A7A7A) 的亮度只有
            // 0.19，实测在花壁纸上几乎读不出来（同一件事在 SectionTitle 上记过一次）。
            // 圆里面那行仍然用 OrbInk —— 它背后是着色过的玻璃，不是壁纸。
            color = Ink.TextMid,
            modifier = Modifier.padding(top = 5.dp),
        )
    }
    }   // Column（见上面"自己包一层 Column"的注释）
}

@Composable
internal fun InfoRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, fontSize = 13.5.sp, color = Ink.TextMid)
        Text(v, fontSize = 13.sp, color = Ink.TextHi)
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun StepRow(no: String, text: String, chip: String) {
    /* 用 FlowRow 而不是 Row：窄屏上实测过，`Text(weight(1f)) + 不伸缩的 chip`
       会把 chip 的固有宽度先占掉，只剩两三个字的宽度给正文 ——
       274dp 宽的机器上"点一下开始播放"被排成"点一下 / 开始播 / 放"三行。
       流式布局下放不下就整块换到下一行，宽屏仍然是一行。 */
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
        verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1),
    ) {
        Text(no, fontSize = 12.5.sp, color = Ink.TextLow)
        Text(text, fontSize = 12.5.sp, color = Ink.TextHi)
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
    // key 带上当前配置宽度：manifest 配了 configChanges，转屏只重组不重建，
    // `remember(context)` 不失效 —— "本机屏幕宽 1080px"等标签会停在旋转前的值，
    // 和真正采集时现取的口径打架（REVIEW-2026-09-27 P2，注释自己警告过）。
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(context, widthDp) {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.currentWindowMetrics.bounds.width()
    }
}

/**
 * 分享设置：分辨率 / 帧率 / 码率 / 声音档。
 *
 * 分享进行中改档**即时生效**（见 [ShareQuality] 与 CallSession.updateQuality：
 * 声音档/码率/帧率/分辨率都不动 m-line，可热改）；唯独"带画面 ⇄ 只连麦"的
 * 结构变化要重新授权，由 onChange 里走授权指引。
 * 麦克风开关刻意不在这里 —— 它是通话中的实时动作，已在控制岛上，
 * 重复语义只留一处（沿用 HaoAI/效果图的约定）。
 */
@Composable
fun QualitySettingsScreen(
    backdrop: LayerBackdrop,
    quality: ShareQuality,
    onChange: (ShareQuality) -> Unit,
    onBack: () -> Unit,
) {
    fun indexOfOr(list: List<*>, value: Any?, default: Int): Int =
        list.indexOf(value).let { if (it >= 0) it else default }

    // 整页可滚：内容高于一屏（真机 3200px 下「完成」会掉出屏幕外，实测够不到）。
    // 滚动列里不能用 SpacerWeight（weight 在无限高约束下直接崩），所以这里只垫小间距。
    PageScaffold(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(GlassDimens.sp6))
        Headline("分享设置", "这些是上限不是保证值：网络差或发热时会自动再降。分享中改了立即生效；开画面要重新授权一次。")

        SectionTitle("放映方式")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp3)) {
                SegmentRow(
                    options = listOf("同步直连", "我播他看"),
                    selected = if (quality.playMode == PlayMode.Relayed) 1 else 0,
                    onSelect = { android.util.Log.i("PlayMode", "onSelect idx=$it"); onChange(quality.copy(playMode = if (it == 1) PlayMode.Relayed else PlayMode.Direct)) },
                )
                // 两行常显、随选中高亮 —— 让"这一档意味着什么"永远看得见
                Text(
                    "同步直连：把地址发给对方、各播一份 —— 画质原生、你几乎不耗流量；" +
                        "但对方得自己能访问片源（比如也挂同一个代理）。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (quality.playMode == PlayMode.Direct) Ink.TextHi else Ink.TextLow,
                )
                Text(
                    "我播他看：只有你在播，纯画面经 WebRTC 直接转过去 —— " +
                        "对方不用挂代理、也看不到你手机上的界面；代价是你的上行流量和耗电多一份。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = if (quality.playMode == PlayMode.Relayed) Ink.TextHi else Ink.TextLow,
                )
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
                // 聚焦瞬间是否有文本：区分「真清空」与「预设档本来就空」
                var fpsHadText by remember { mutableStateOf(false) }
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
                                fpsHadText = fpsText.isNotEmpty()
                            } else if (fpsFocused) {
                                fpsFocused = false
                                // 两层防误伤（2026-09-30 实测 bug：改 60 帧重进被改回 30）：
                                // ① 首次组合会回调一次 focus=false —— 没聚焦过就不算失焦，不处理；
                                // ② "空=回默认"只在**聚焦时有文本、走时空了**时成立 ——
                                //    预设档输入框本来就是空的（靠分段高亮显示），绝不能判成"被清空"。
                                val n = fpsText.toIntOrNull()
                                    ?.coerceIn(ShareQuality.FPS_MIN, ShareQuality.FPS_MAX)
                                if (n == null) {
                                    // 真·清空（进来时有文本、走时空了）才回默认
                                    if (fpsHadText) {
                                        val def = ShareQuality().fps
                                        if (quality.fps != def) onChange(quality.copy(fps = def))
                                    }
                                } else if (n != quality.fps) {
                                    onChange(quality.copy(fps = n))
                                }
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
                // 聚焦瞬间是否有文本：区分「真清空」与「预设档本来就空」
                var bpsHadText by remember { mutableStateOf(false) }
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
                                bpsHadText = bpsText.isNotEmpty()
                            } else if (bpsFocused) {
                                bpsFocused = false
                                // 两层防误伤（2026-09-30 bug：改 8M 重进被改回 2M，
                                // 调用栈抓到 onChange(默认) 来自首次组合的假 focus=false）——同帧率段。
                                val bps = bpsText.toFloatOrNull()
                                    ?.let { (it * 1_000_000).toInt() }
                                    ?.coerceIn(ShareQuality.BPS_MIN, ShareQuality.BPS_MAX)
                                if (bps == null) {
                                    // 真·清空（进来时有文本、走时空了）才回默认
                                    if (bpsHadText) {
                                        val def = ShareQuality().maxVideoBps
                                        if (quality.maxVideoBps != def) {
                                            onChange(quality.copy(maxVideoBps = def))
                                        }
                                    }
                                } else if (bps != quality.maxVideoBps) {
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

        SectionTitle("声音与画面")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
                SegmentRow(
                    options = VoiceMode.entries.map { VoiceMode.label(it) },
                    selected = VoiceMode.entries.indexOf(quality.voiceMode),
                ) { onChange(quality.copy(voiceMode = VoiceMode.entries[it])) }
                Text(
                    when (quality.voiceMode) {
                        /* 每一档都要说清"声音从哪儿来"，因为这三条路的物理来源不一样
                           （2026-10-01 改版：默认已是「视频声+连麦」、麦默认关）。 */
                        VoiceMode.VideoOnly ->
                            "隐私档：不收音、不说话。\n" +
                                "· 放映厅里对方自己听原声就行，用不上你的麦克风\n" +
                                "· 屏幕分享 / 我播他看时，画面的声音要靠你的麦克风 —— " +
                                "这档得手动开麦，否则对方只看没声"
                        VoiceMode.VideoPlusCall ->
                            "默认档。能听对方、随时能说 —— 麦克风默认关着，" +
                                "想说话在放映厅点麦克风按钮即可。\n" +
                                "· 放映厅：对方听他自己的原生画面声，和你的麦无关\n" +
                                "· 屏幕分享 / 我播他看：画面声靠你的麦 —— 一开就自动帮你开麦\n" +
                                "· 放映中开麦会和对方那份原声叠成回声，说完再点一下关掉"
                        VoiceMode.CallOnly ->
                            "不弹投屏授权，几乎不耗流量（约 0.3 MB/分钟），适合纯连麦；进来麦就是开的。"
                    },
                    fontSize = 11.5.sp,
                    color = Ink.TextMid,
                    lineHeight = 17.sp,
                )
            }
        }

        SectionTitle("麦克风")
        GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
            Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1)) {
                InfoRow("开关位置", "通话中控制岛上的麦克风按钮")
                Text(
                    "麦克风随时可静音/取消，不在这里设置 —— 这页只管「开始分享前」定下的画质。\n" +
                        "回声消除按机型走：机器自带硬件消回声就交给它，没有就用 WebRTC 自己的软件消回声，" +
                        "两端同一套规则。",
                    fontSize = 12.sp,
                    color = Ink.TextMid,
                    lineHeight = 17.sp,
                )
            }
        }

        // 没有「完成」按钮：这里的每一项都是**改了立即保存**（onChange 里就写盘了）。
        // 底部补一颗「返回」—— 原注释声称"左上角返回"可这屏根本没有返回控件，
        // 只能靠系统手势（REVIEW-2026-09-27 P3）。多一个确认键才会让人以为"不点就不生效"。
        StatusChip("改动立即生效，直接返回即可", ChipTone.Ok)
        PrimaryPill("返回", onBack, backdrop, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

// ─────────────────────── 房主 · 系统授权指引 ───────────────────────

@Composable
fun ConsentGuideScreen(backdrop: LayerBackdrop, onContinue: () -> Unit, onBack: () -> Unit) {
    /* 整页可滚 + 按钮钉底（FailedScreen 同款结构）：横屏可用高只有 ~359dp，而本屏内容
       约 500dp —— 不可滚的话两颗按钮整行在屏幕外，投屏流程走不下去
       （REVIEW-2026-09-27 P1；同仓 Failed/Ended/ShareKind 三屏修过一模一样的病）。 */
    PageScaffold {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
        ) {
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
                        /* 这句要说清"麦克风在分享屏幕时是干什么的"（2026-10-01 改版后
                           默认档是「视频声+连麦」且麦默认关，开屏享会自动开麦）——
                           真相仍是：屏幕分享时影片声唯一的通道就是外放→麦克风，不给就是**有画无声**。 */
                        "不给也能分享画面，但对方听不到任何声音 —— 屏幕分享时影片声只能靠你的" +
                            "外放灌进麦克风传过去。要连麦说话同样靠它。"
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
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GlassDimens.sp2)) {
            PrimaryPill("返回", onBack, backdrop, Modifier.weight(1f), filled = false)
            PrimaryPill("我知道了，继续", onContinue, backdrop, Modifier.weight(2f))
        }
        Spacer(Modifier.height(GlassDimens.sp6))
    }
}

/** 横屏首页用的"标签 值"横排单元：竖排 InfoRow 在横屏太吃高度。 */
@Composable
private fun InfoChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 12.sp, color = Ink.TextMid)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ink.TextHi)
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
    /**
     * 有没有真的在投屏。厅先开只起信令 + 语音，这时这颗钮按下去是"把厅关掉"，
     * 写「停止分享」会让人以为自己在分享（而"我没在分享"恰恰是他想知道的那件事）。
     */
    screenSharing: Boolean = false,
) {
    val context = LocalContext.current
    /* 滚动 + 按钮钉底（同 ConsentGuideScreen）：横屏内容约 560dp > 可用 359dp，
       不可滚时底部这颗唯一的停止入口在屏幕外（REVIEW-2026-09-27 P1）。 */
    PageScaffold {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
        ) {
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
                        // 直达微信/QQ 等的第二条通道：复制给会粘的人，分享给要直接发的人
                        PrimaryPill(
                            "分享",
                            { context.shareInvite(inviteUrl) },
                            backdrop,
                            Modifier.weight(1f),
                            filled = false,
                        )
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
        }
        PrimaryPill(if (screenSharing) "停止分享" else "结束连麦", onStop, backdrop, Modifier.fillMaxWidth(), filled = false)
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
fun PasteField(
    value: String,
    onChange: (String) -> Unit,
    hint: String = "长按粘贴",
    /** 86dp 是竖屏四行的手感值；横屏要省高度（见 ViewerJoinScreen 的 wide 分支）。 */
    boxHeight: Dp = 86.dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(boxHeight)
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

/**
 * 正常收场屏：房主结束了分享，或者信令通道先一步断了。
 *
 * 单独一屏而不是复用 [FailedScreen]，是因为复用的代价正好是用户投诉的那件事：
 * 房主点"停止分享"之后，观众只能从"媒体突然没了"反推，于是走进失败分支，
 * 标题变成"直连失败"、附三条换网络建议、底部一个"重试" —— 一个别人的正常动作
 * 被画成了用户的网络故障，而"重试"根本没用（链接随那次会话一起作废了）。
 *
 * 所以这里刻意**不画红、不提 NAT、不放重试**，只说清"该等他重新开一次并再发链接"。
 */
@Composable
fun EndedScreen(
    backdrop: LayerBackdrop,
    reason: String,
    onBack: () -> Unit,
) {
    PageScaffold {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GlassDimens.sp4),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = GlassDimens.sp4),
                verticalArrangement = Arrangement.spacedBy(GlassDimens.sp2),
            ) {
                Text(reason, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.TextHi)
                Text(
                    "这次分享已经停了，不是你的网络问题。",
                    fontSize = 12.5.sp, color = Ink.TextMid, lineHeight = 18.sp,
                )
            }

            GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(GlassDimens.sp4),
                    verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("想看的话该怎么做", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                        StatusChip("只能由他发起", ChipTone.Ok)
                    }
                    Text(
                        "让房主在他手机上重新点一次「分享画面」，选「分享我的屏幕」，" +
                            "再把新链接发给你。" +
                            "这条链接连同口令已经作废，刷新也不会恢复。",
                        fontSize = 12.sp, color = Ink.TextMid, lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(GlassDimens.sp2))
        }
        PrimaryPill("知道了", onBack, backdrop, Modifier.fillMaxWidth(), filled = false)
        Spacer(Modifier.height(GlassDimens.sp4))
    }
}

@Composable
private fun FixCard(backdrop: LayerBackdrop, title: String, chip: String, body: String) {
    GlassCardPanel(backdrop, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(GlassDimens.sp4), verticalArrangement = Arrangement.spacedBy(GlassDimens.sp1)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                StatusChip(chip, ChipTone.Warn)
            }
            Text(body, fontSize = 12.sp, color = Ink.TextMid, lineHeight = 17.sp)
        }
    }
}
