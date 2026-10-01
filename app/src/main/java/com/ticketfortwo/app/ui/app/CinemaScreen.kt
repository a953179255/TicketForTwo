package com.ticketfortwo.app.ui.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.ticketfortwo.app.CallSession
import com.ticketfortwo.app.VoiceMode
import com.ticketfortwo.app.bpsLabel
import com.ticketfortwo.app.cinema.CinemaDebug
import com.ticketfortwo.app.cinema.CinemaProbe
import com.ticketfortwo.app.cinema.CinemaSync
import com.ticketfortwo.app.cinema.FloatPlayer
import com.ticketfortwo.app.cinema.FloatRequest
import com.ticketfortwo.app.cinema.FloatWarmer
import com.ticketfortwo.app.cinema.MediaDuration
import com.ticketfortwo.app.cinema.MediaSniffer
import com.ticketfortwo.app.cinema.TheaterPlayer
import com.ticketfortwo.app.cinema.SRC_PAGE
import com.ticketfortwo.app.cinema.SnifferState
import com.ticketfortwo.app.watch.WatchCmd
import com.ticketfortwo.app.watch.WatchSync
import com.ticketfortwo.app.ui.glass.CompactGlassField
import com.ticketfortwo.app.ui.glass.GlassPageBar
import com.ticketfortwo.app.ui.glass.GlassPanel
import com.ticketfortwo.app.ui.glass.GlassTextButton
import com.ticketfortwo.app.ui.glass.LiquidGlassButton
import com.ticketfortwo.app.ui.glass.LiquidToggle
import com.ticketfortwo.app.ui.theme.GlassDimens
import com.ticketfortwo.app.ui.theme.Ink
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 放映/浏览模式开关（方案B）—— **进程级**：授权指引/同看/设置这些覆盖层会把本屏
 * 整屏卸载，回来时若按 `cinema != null` 重算，人会从浏览被拽进放映
 * （2026-09-29 审查：授权回来顶栏已变放映态短文案）。收厅置 false；进场时若厅里
 * 还没有片也兜底复位（见屏内 LaunchedEffect）。
 */
private val theaterMode = androidx.compose.runtime.mutableStateOf(false)

/* 当前页地址与地址栏输入 —— **进程级**（2026-10-01 真机实测定案）：
 * HaoMirror 这类 scrcpy 式投屏在手机上建虚拟显示器，连接/断开（display removed）
 * 会触发一次界面重建 —— 普通 remember 的 pageUrl 丢成空串：WebView 不再挂载
 * （画面黑掉）、放映 tab 显示"还没选片"，而 theater 是进程级还活着，甲板
 * "正在放映 0:11" 照样画 —— 两句自相矛盾的话同屏。theater 早就进程级了，
 * pageUrl 跟上；WebView 是进程级单例，重建后重新挂回同一实例、loadedUrl 相同
 * 不重载，页面和进度都不丢。 */
private val cinemaPageUrl = androidx.compose.runtime.mutableStateOf("")
private val cinemaInputUrl = androidx.compose.runtime.mutableStateOf("")

/* 放映全屏（2026-10-01 用户反馈"放映界面不能全屏"）：甲板收起、画面吃满；
   轻点画面唤回甲板。进程级 —— 重建/覆盖层往返不丢。退出放映自动复位。 */
private val deckHidden = androidx.compose.runtime.mutableStateOf(false)


/**
 * 等多久就算"对方没给回执"。
 *
 * 观众侧自己有个 8 秒看门狗（没首帧就退回屏幕流并回一条 fail），所以正常路径上
 * 回执最迟 8 秒 + 一个来回就到；这里取 12 秒，是给"对方页面是旧版、根本不会发回执"
 * 留的余量 —— 那种情况下房主看到的应该是"没等到回执"，而不是无限期挂着"等对方出画面"。
 */
private const val ACK_WAIT_MS = 12_000L

/**
 * 放映厅 · 房主侧第一版：**厅先开，人先进来，片子后选**。
 *
 * 这一屏现在承担两件事：
 * 1. **P0 量具** —— 把"能不能从真实站点嗅到观众也能播的地址"变成屏幕上看得见的数字。
 *    S 档整条路线押在这件事上，所以先测再写播放链路。
 * 2. 厅的骨架 —— 地址栏 / 内置播放器 / 候选列表，后面"开始放映"就接在这里。
 *
 * 注入只用 `evaluateJavascript`，**不挂 addJavascriptInterface**：
 * 房主打开的是陌生网站，把 Kotlin 对象挂到 window 上等于给它一个回调我们的后门。
 */
@Composable
fun CinemaScreen(
    backdrop: LayerBackdrop,
    /** 「分享 → 双人票」递进来的链接；非空就一进来就打开它，而不是停在本地测试页。 */
    initialUrl: String? = null,
    /** 第几次递链接：同一条链接再分享一次也要能重新加载这一页，见 MainActivity 的 cinemaSeq。 */
    jumpSeq: Long = 0L,
    /** 厅已开但对方还没进来时，这一条就是邀请链接 —— 厅的入口动作是"发链接"。 */
    inviteUrl: String? = null,
    viewerOnline: Boolean = false,
    /** 这一场的声音档，只为在状态卡上说实话（"只有视频声"时麦克风可能是关着的）。 */
    voiceMode: VoiceMode = VoiceMode.VideoOnly,
    /**
     * 厅里也能开投屏：首页圆钮在厅开着时是"回到放映厅"，ShareKind 进不去 ——
     * 厅先开（只语音）再切投屏这条 v2.1 主路径的入口就落在这里
     * （"分享我的屏幕" → 授权指引 → attachScreenCapture，授权后留在厅里接着选片）。
     */
    onStartShare: () -> Unit = {},
    onBack: () -> Unit,
    /** 这一屏卸载时把“此刻在哪一页”交回去（见 onDispose 里的调用）。 */
    onPageLeave: (String) -> Unit = {},
    /** 放映模式甲板的统计行：直连往返（没连上为 null）、采集帧率、码率上限。 */
    latencyMs: Long? = null,
    videoFps: Int = 0,
    videoBps: Int = 0,
) {
    val context = LocalContext.current
    /* 空哨兵 "" = 厅里还没打开过网页。以前这里默认塞一条 file:///android_asset
       彩条测试页 —— 正式 App 里用户第一眼看到的是工程测试卡（2026-09-29 用户反馈）。
       现在没页就是没页：一行快捷芯片（粘贴/上次/收藏夹）+ 深色待放屏（方案A），
       WebView 什么都不加载。 */
    var pageUrl by cinemaPageUrl
    var inputUrl by cinemaInputUrl
    /* 深链/递链接进来的地址：进程级状态不能只在组合时取一次初值 —— 重进厅带新链接
       （initialUrl 变了）要覆盖，同一进程里旧场的地址不许冒充这一场的。 */
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank() && initialUrl != pageUrl) {
            inputUrl = initialUrl
            pageUrl = initialUrl
        }
    }
    /**
     * 同一地址重复点「打开」= 真刷新。pageUrl 是加载 effect 的 key，值不变 effect
     * 不重跑 —— 页内跳走后想"回到这条链接"点了没反应（同 MainActivity「同一条链接
     * 第二次递进来」的坑，REVIEW-2026-09-27 P2）。自增计数并进 key 把原地刷新补上。
     */
    var reloadSeq by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf<List<MediaSniffer.Hit>>(emptyList()) }
    var probe by remember { mutableStateOf<MediaSniffer.PageProbe?>(null) }
    var eme by remember { mutableStateOf<CinemaProbe.EmeReport?>(null) }
    /**
     * 量具（嗅探候选 / 页面读数 / EME）默认**收着**。
     *
     * 这张卡是房主全程盯着的那一块，而放映时他真正要看的只有三件事：
     * 对方在不在放、放到哪、方向盘给不给。把 P0 的读数常驻在上面，
     * 等于让量具抢了界面的位置（实测：放映中的卡有六成行数是嗅探日志）。
     * 需要挑候选、查为什么嗅不到时，点「展开嗅探」即可 —— 它没有消失，只是不再常驻。
     */
    var showPanel by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("把这一页当成浏览器用；嗅到的地址在「展开嗅探」里面") }
    var fullScreenView by remember { mutableStateOf<View?>(null) }
    /* callback 必须存下来并调用：WebView 文档要求 App 主动退出全屏时调
       onCustomViewHidden()，丢了它页面侧的全屏状态出不来（REVIEW-2026-09-27 P1）。 */
    var fullScreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    /* 渲染进程崩了要整只重建（WebView 此后不可复用）；onRenderProcessGone 不 override
       的话系统默认返回 false = 直接杀掉整个 App 进程 —— 正在分享的会话会一起没掉
       （REVIEW-2026-09-27 P1）。 */
    var webGen by remember { mutableStateOf(0) }
    /** 动作行「后退」按钮的可用性。WebView 内部导航不触发重组，由 onPageStarted 显式刷新。 */
    var canGoBack by remember { mutableStateOf(false) }
    /** 放映状态（会话里那份的本地镜像，只为画 UI）。 */
    val cinema by CallSession.cinema.collectAsState()
    val sessState by CallSession.state.collectAsState()
    var sawLiveSession by remember { mutableStateOf(false) }
    /* 会话散场（Idle）：页面连同媒体一起收掉 —— 不然离厅后电影的声音还在首页外放，
       WebView 也白占内存。**必须见过非 Idle 才收**：进厅那一刻会话是异步启动的，
       首次组合看到的 Idle 是"还没开始"而不是"结束了" —— 不加这道门，首次组合
       就把刚领出来的 WebView 销毁掉（实测：页面永远加载不出、探针全部停摆）。
       下方所有摸 webView 的循环都带身份护栏（CinemaBrowser.webView === webView），
       销毁后自然停手，不会往死实例上发 JS。 */
    LaunchedEffect(sessState) {
        if (sessState !is CallSession.State.Idle) {
            sawLiveSession = true
        } else if (sawLiveSession) {
            sawLiveSession = false
            CinemaBrowser.destroy()
            /* pageUrl 已是进程级（投屏断开重建也不丢）—— 散场必须显式清账，
               不然下一场厅一进来就"自动弹回"上一场的页面。 */
            cinemaPageUrl.value = ""
            cinemaInputUrl.value = ""
        }
    }
    /** 现在到底有没有在投屏 —— 决定"分享我的屏幕"这颗入口还画不画。 */
    val screenShared = CallSession.localVideo.collectAsState().value != null
    /** 对方那边到底播出来了没有 —— 没有这条回执时，"正在放映"三个字是半真半假的。 */
    val playback by CallSession.viewerPlayback.collectAsState()
    /* 厅回来接片：进厅那一刻记下放映状态（位置/是否在播）。WebView 离屏即毁、
       重进是全新实例，页面从头加载 —— 站点自己复播（记忆播放追平了离开时的位置）
       就不动它，明显落后才拨回去；片源未就绪（dur=0）就等下一轮探针。
       换片/换页由"本页 == 片源地址"门禁拦住，不会污染用户后来打开的别的页面；
       播放中的话拨完接着放（WebView 已关手势门，程序化 play 放行）。 */
    var pendingRestore by remember {
        mutableStateOf(
            cinema?.let {
                CinemaResume(url = pageUrl, posMs = it.posMs, playing = it.playing, durMs = it.durMs)
            },
        )
    }
    /* 自家访问记录 / 后退劫持状态：**住在 CinemaBrowser 里**（进程级，跟 WebView
       同寿）。原来它们是组合态 —— 重进厅组合重建，栈就丢了。那组状态描述的是
       "这只浏览器的访问史"，理应跟页面实例一样跨重进存活；换成进程级 mutableStateOf
       后，回调里写、组合里读（后退按钮可用性）都照旧订阅。 */
    /* 「上次一起看」（方案A）：上一次程序化打开的完整地址，落盘持久化 ——
       比收藏夹更常走的一条：上次看了一半的站，进厅点一下就回去。 */
    var recentUrl by remember { mutableStateOf<String?>(null) }

    /* 聚焦地址栏并弹键盘（方案A"进厅即输"就靠这一下）。
       聚焦必须等地址行真的上屏：拨了 seq 之后要过一帧组合才有那个节点。 */
    val addrFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var addrFocusSeq by remember { mutableStateOf(0) }
    LaunchedEffect(addrFocusSeq) {
        if (addrFocusSeq == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { addrFocus.requestFocus() }
        keyboard?.show()
    }
    /* 退回落地时刻 vs 网页内最后一次触摸：longArray 也在 CinemaBrowser（非状态，
       只被回调读写，不驱动 UI）—— 同样要跨重进存活。 */
    /* 放映模式（方案B，2026-09-29 用户选定）：画面钉顶 + 自家控制甲板；
       false = 原来的浏览布局（地址行/动作行/底卡）。进厅时厅里已有片就直接落在
       放映模式，开始放映时打开、收厅时退出；顶栏「放映|浏览」随时切。 */
    var theater by theaterMode
    LaunchedEffect(Unit) {
        // 新的一场还没片：别把上一场的放映模式带进来
        if (CallSession.cinema.value == null) theaterMode.value = false
    }
    /** 视频宽高比（探针读页面 video 的真实尺寸）；拿不到按 16:9 兜底。 */
    fun videoRatio(): Float {
        val w = probe?.videoWidth?.takeIf { it > 0 }
        val h = probe?.videoHeight?.takeIf { it > 0 }
        return if (w != null && h != null) w.toFloat() / h else 16f / 9f
    }
    /** 方向交还系统（退出全屏/退出放映时）。 */
    fun restoreOrientation() {
        (context as? android.app.Activity)?.requestedOrientation =
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    LaunchedEffect(deckHidden.value) {
        /* **任何路径退出全屏都交还方向**（2026-10-01 用户复测"返回后卡横屏"）：
            系统返回键、轻点唤回、收厅……只要 deckHidden 变 false 就 UNSPECIFIED。 */
        if (!deckHidden.value) restoreOrientation()
    }
    LaunchedEffect(theater) {
        val wv = CinemaBrowser.webView
        if (theater) {
            /* **View 层禁网页触摸**（2026-10-01 用户复测"点击画面仍跳广告"）：
               自播 TextureView/VideoLayer 不消费 touch，touch 会穿到底下的 WebView
              （AndroidView 分发顺序），JS 拦截层拦不住 View 层的这条路。
               OnTouchListener 返回 true 从根上掐 —— 放映中网页一个 touch 都收不到，
               控制全走甲板（hostCmd 走 evaluateJavascript，不依赖 touch）。 */
            wv?.setOnTouchListener { _, _ -> true }
        } else {
            // 退出放映/收厅：全屏复位（甲板回来）+ 方向交还系统 + 网页触摸恢复
            deckHidden.value = false
            restoreOrientation()
            wv?.setOnTouchListener(null)
            /* **统一拆钉屏**（2026-10-01 问题 1）：原来只有手动切「浏览」那一条路
               会 pinVideo(false)；投屏断开触发界面复位走的是 theaterMode 复位，
               钉屏留在网页上 —— 视频居中 + 黑底垫层盖住页面，"网页其他内容变黑
               只剩视频"。这里不管从哪条路退出放映都拆干净。 */
            wv?.post {
                wv?.evaluateJavascript(UNPIN_VIDEO_JS, null)
            }
        }
    }
    /* 进厅即输（方案A，2026-09-29 用户拍板）：引导页撤掉，空厅落地就是地址栏聚焦 +
       键盘弹起 —— 跟浏览器点开新标签页一个感觉。只拨一次：带着片回厅（pageUrl 非空）、
       递链接进来（initialUrl 非空）、已落在放映模式（地址行不在屏上）都不弹键盘。 */
    LaunchedEffect(Unit) {
        if (pageUrl.isEmpty() && !theater) addrFocusSeq++
    }
    /** 房主这一侧播放器的位置/时长/标题 —— 直接复用 watch 那套探针，形状一样。 */
    var player by remember { mutableStateOf<com.ticketfortwo.app.watch.WatchState?>(null) }
    /** 方向盘给不给对方。会话里那份是真值，这里只是本地即时反馈（点下去先亮起来）。 */
    val mayControl by CallSession.viewerMayControl.collectAsState()
    var allowControl by remember { mutableStateOf(mayControl) }
    LaunchedEffect(mayControl) { allowControl = mayControl }

    /* ── 收藏夹（参考雨见「书签收藏」；本 App 没有服务器，落本地 SharedPreferences）──
       条目 = "url␟标题" 的 StringSet；★ 是当前页的收藏开关，动作行的「收藏夹」打开列表。 */
    val favPrefs = remember {
        context.getSharedPreferences("t2_cinema_fav", Context.MODE_PRIVATE)
    }
    var favs by remember { mutableStateOf<List<String>>(emptyList()) }
    /* 首次读收藏必须挪到 IO：SharedPreferences 首次访问会在调用线程同步等磁盘，
       组合发生在主线程 —— 本仓 lastConnected/quality 已经踩过同款（双人票 ANR 注释）。 */
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            favPrefs.getStringSet("set", emptySet())!!.toList() to favPrefs.getString("recent", null)
        }
        favs = loaded.first
        recentUrl = loaded.second
    }
    var showFavs by remember { mutableStateOf(false) }
    /* 「收厅」的二次确认。它是这一屏**唯一会中断放映**的动作，原来跟「收藏夹」
       这类无害按钮平铺在同一排、一点就生效 —— 误触的代价是把对方直接踢回等候屏
       （2026-09-29 用户拍板要加确认）。 */
    var askCloseRoom by remember { mutableStateOf(false) }
    /** 「换片」候选列表（浮窗上点换片、或手挑候选时弹出）。 */
    var askPickList by remember { mutableStateOf(false) }
    /** 顶替询问：浮窗正在播，又选了另一条 —— 换不换由房主定（2026-09-30 定案）。 */
    var pickTarget by remember { mutableStateOf<MediaSniffer.Hit?>(null) }
    fun persistFavs() {
        favPrefs.edit().putStringSet("set", favs.toSet()).apply()
    }
    fun toggleFav() {
        val u = cinemaLastPageUrl ?: pageUrl
        if (u.isBlank()) {
            note = "还没有打开网页，没什么可收藏的"
            return
        }
        val exist = favs.firstOrNull { it.substringBefore('␟') == u }
        if (exist != null) {
            favs = favs - exist
            note = "已取消收藏"
        } else {
            val t = player?.title?.takeIf { it.isNotBlank() && !it.startsWith("http", true) } ?: u
            favs = favs + "$u␟$t"
            note = "已收藏：$t"
        }
        persistFavs()
    }
    fun openFav(u: String) {
        showFavs = false
        val norm = normalizeUrl(u)
        if (norm != pageUrl) {
            inputUrl = norm
            pageUrl = norm
        } else {
            reloadSeq++
        }
        note = "打开收藏"
    }
    fun openRecent(u: String) {
        val norm = normalizeUrl(u)
        if (norm != pageUrl) {
            inputUrl = norm
            pageUrl = norm
        } else {
            reloadSeq++
        }
        note = "打开上次一起看的站"
    }
    /* 方案A 快捷芯片「粘贴」：把剪贴板里的链接填进地址栏 —— 不自动打开，
       让人过目一眼再按「打开」，猜错比多按一下更贵。没有链接就明说。 */
    fun pasteFromClip() {
        val text = runCatching {
            val cm = context.getSystemService(android.content.ClipboardManager::class.java)
            cm?.primaryClip?.let { c ->
                (0 until c.itemCount).joinToString("\n") { c.getItemAt(it)?.text?.toString().orEmpty() }
            }
        }.getOrNull().orEmpty()
        val found = clipUrlRegex.find(text)?.value?.trimEnd('，', ',', ')', '）', '》', '>', '。')
        if (found == null) {
            note = "剪贴板里没有链接"
        } else {
            inputUrl = found
            note = "已填入剪贴板的链接，点「打开」"
        }
    }

    val sniffer = remember { SnifferState() }

    /* ── 浮窗播放器（跨页 + 跨 App）──
       「单一指挥官」：任何时刻只有一个进度源说了算。网页里的 <video> 与浮窗里的播放器
       同时播会出现两个声音、两套进度，观众端就是被来回拽（跟 2026-09-29 修过的
       "进度循环"同源）。所以这里显式记一个 commander，广播只读它的读数。 */
    val float by FloatPlayer.state.collectAsState()
    var commander by remember { mutableStateOf(Commander.Page) }
    /* 画面自播（2B）状态：放映态画面由 TheaterPlayer 接管（网页退居幕后交地址）。 */
    val tp by TheaterPlayer.state.collectAsState()
    /* B 方案「我播他看」转播中：本地画面 = 自己视频轨的回显 ——
       "你看到的正是观众看到的"（帧已经过一次编码，比自播纹理略慢半拍，换来零差异观感）。 */
    val localVt by CallSession.localVideo.collectAsState()
    /** 麦克风实际开着没（放映面板的麦键跟它走 —— 不跟手动静音位，防"图标骗人"）。 */
    val micLive by CallSession.micLive.collectAsState()
    /** 点画面弹出的自播控制条（3 秒自动隐）。 */
    var showTctl by remember { mutableStateOf(false) }
    /* 候选时长（URL → 毫秒）：异步回填，先出条目、时长后填。 */
    var durations by remember { mutableStateOf(emptyMap<String, Long>()) }

    /**
     * 起浮窗：先立新、后废旧 —— 网页那个照旧播着，等浮窗**真的播起来**才停它。
     * [h] 为空时自动挑最优候选（CinemaProbe.bestOf）。
     */
    fun openFloat(h: MediaSniffer.Hit?, startPos: Long? = null) {
        // 预热窗已在（放映中建的 1×1 窗 + 静音在播）→ 直接拉起，省掉整段起播
        if (FloatPlayer.show(context, startPos)) {
            note = "浮窗继续播（预热秒开）"
            return
        }
        val hit = h ?: CinemaProbe.bestOf(hits)
            /* 嗅探候选没有可播地址（blob/MSE 站点：流在页面里合成，浮窗拿不到直链）
               时，退而用**递给观众的片源地址** —— 只要开过放映它就在（会话里那份）。
               没了 referer/cookie 可能被防盗链拦，但拦了有失败回退，总比"点了没反应"
               好（2026-10-01 用户实测：浏览态点浮窗纹丝不动）。 */
            ?: cinema?.track?.url?.takeIf { it.startsWith("http", true) }?.let { url ->
                MediaSniffer.Hit(
                    url = url,
                    kind = MediaSniffer.Kind.Master,
                    referer = pageUrl.takeIf { it.isNotBlank() },
                    userAgent = null,
                    cookie = null,
                    firstSeenMs = System.currentTimeMillis(),
                )
            }
        if (hit == null) {
            note = "还没嗅到能播的地址 —— 先在这页把视频点成播放"
            return
        }
        val startAt = startPos ?: (player?.posMs ?: 0L)
        note = "浮窗起播中…"
        FloatPlayer.start(
            context,
            FloatRequest(
                url = hit.url,
                title = MediaSniffer.hostLabel(hit.url),
                referer = hit.referer,
                cookie = hit.cookie,
                userAgent = hit.userAgent,
                startPosMs = startAt,
                // 全部候选一起带上：让服务端有机会找到 master 并挑低档
                candidates = hits
                    .filter { MediaSniffer.playable(it.kind) && it.url.startsWith("http", true) }
                    .map { it.url },
            ),
        )
    }

    /** 从候选列表选一条：浮窗已在播就要**先问一句**（顶替），否则直接起窗。 */
    fun requestPlay(h: MediaSniffer.Hit) {
        if (float.active) pickTarget = h else openFloat(h)
    }

    /* WebView 从 CinemaBrowser 领取（进程级存活），但 **clients 每次进屏都重装**：
       它们闭包引用的这一屏组合状态（inputUrl/hits/player/…）是组合态 —— 重进后
       还挂着上一屏的闭包就会写进死状态。宿主对象内联创建（不 remember），
       闭包随本次组合走 —— 与原先内联 clients 同语义。 */
    val webView = remember(webGen) {
        val host = object : CinemaBrowserHost {
            override fun onPageStarted(url: String?, backAvailable: Boolean) {
                canGoBack = backAvailable
                if (url != null) {
                    inputUrl = url
                    cinemaLastPageUrl = url
                }
                sniffer.clear()
                hits = emptyList()
                probe = null
                player = null
                eme = null
            }
            override fun onSniffChanged() {
                hits = sniffer.snapshot()
            }
            override fun onFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
                fullScreenView = view
                fullScreenCallback = callback
            }
            override fun onRendererGone(view: WebView?) {
                fullScreenView = null
                fullScreenCallback = null
                note = "这个网页崩了，已重新打开"
                val cur = cinemaLastPageUrl
                if (cur != null && cur != pageUrl) {
                    inputUrl = cur
                    pageUrl = cur
                }
                val c = cinema
                if (c != null && cur != null) {
                    pendingRestore = CinemaResume(
                        url = cur,
                        posMs = c.posMs,
                        playing = c.playing,
                        durMs = c.durMs,
                    )
                }
                webGen += 1
            }
            override fun interceptRequest(request: WebResourceRequest): Boolean {
                val isNew = sniffer.observe(request)
                if (isNew) {
                    val snap = sniffer.snapshot().firstOrNull { it.url == request.url.toString() }
                    if (snap != null && snap.kind != MediaSniffer.Kind.Segment) {
                        Log.i("Cinema", "SNIFF kind=${snap.kind} url=${snap.url}")
                    }
                }
                return isNew
            }
        }
        CinemaBrowser.obtain(context).also { CinemaBrowser.install(it, host) }
    }

    /**
     * 自播接管的起播位置：网页探针读数与会话广播里的进度**取较大值**。
     * 只信探针（player）有个坑：探针 2 秒一轮，刚进厅/快速切换时还是 0 或旧值 ——
     * 接管就从 0 起播，观众端跟着被拽回开头（2026-10-01 用户实测"切换后进度归零"）。
     * 广播值（cinema.posMs）最多旧半秒，比归零强得多。
     */
    fun resumePosMs(): Long =
        maxOf(player?.posMs ?: 0L, cinema?.posMs ?: 0L)

    /** 起画面自播（2B）：挑档按放映预算 1920，从 posMs 接。幂等（在播就忽略）。 */
    fun startTheater(h: MediaSniffer.Hit, posMs: Long = resumePosMs()) {
        val headers = buildMap {
            h.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
            h.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        }
        TheaterPlayer.start(
            context = context,
            url = h.url,
            candidates = hits
                .filter { MediaSniffer.playable(it.kind) && it.url.startsWith("http", true) }
                .map { it.url },
            headers = headers,
            userAgent = h.userAgent,
            posMs = posMs,
        )
    }

    /** 换片时的自播切换：先停旧流，再按新片从头起（stop 后 start 才不会被幂等挡掉）。 */
    fun replaceTheater(h: MediaSniffer.Hit) {
        TheaterPlayer.stop()
        startTheater(h, posMs = 0L)
    }

    /** 关浮窗：反向交接 —— 把浮窗的进度拨回网页播放器，然后才停它（同样先立新后废旧）。 */
    fun closeFloat() {
        val at = float.posMs
        if (at > 0) {
            webView.post {
                webView.evaluateJavascript(
                    WatchSync.jsFor(WatchCmd.Seek(at), at, float.durMs), null,
                )
            }
        }
        commander = Commander.Page
        FloatPlayer.stop(context)
        note = "已收起浮窗，回到网页里播"
    }

    /**
     * 一次「后退」的完整语义：全屏 → 原生历史 → 自家访问记录 → 才离厅。
     *
     * 原生历史优先：SPA 的 pushState 跳转不触发 onPageStarted，自家记录看不见，
     * 那些站只有原生历史退得动。自家记录兜底：location.replace() 型的广告跳转会
     * 把原生历史吃掉（canGoBack=false），此时按钮灰死、系统返回直接离厅 ——
     * 用户 2026-09-29 反馈的正是这条：广告劫持后只能重输链接。
     */
    fun browserBack(): Boolean {
        if (fullScreenView != null) {
            fullScreenView = null
            fullScreenCallback?.onCustomViewHidden()
            fullScreenCallback = null
            return true
        }
        /* 自家栈优先（审查 A3-1）：原生历史在 replace 型广告跳转里会被吃掉中间页，
           goBack 会"隔山打牛"跳过被吃的那一页；而且系统没有暴露"原生的上一页是谁"
           （BackForwardList 无 currentIndex），没法与栈对照 —— 栈≥2 时目的地与
           原生等价、又不受 replace 污染，直接按访问顺序退。
           栈不够（只有一页）时才回原生：SPA 的 pushState 不触发 onPageStarted，
           那些内部历史只有原生认得。（栈运算在 CinemaBrowser.stackStepBack 里。） */
        val stackPrev = CinemaBrowser.stackStepBack()
        return when {
            stackPrev != null -> {
                webView.loadUrl(stackPrev)
                note = "退回上一页"
                true
            }
            webView.canGoBack() -> {
                webView.goBack()
                true
            }
            // 退回途中、记录已到底：这 2.5 秒内按返回不离厅（甩出去一次就够难受了）
            CinemaBrowser.backTarget != null -> true
            else -> false
        }
    }

    /** 放映模式的画面钉定/还原（探针每 2 秒重放一次，换页后自动重新钉上）。 */
    fun pinVideo(pin: Boolean) {
        webView.post {
            webView.evaluateJavascript(if (pin) PIN_VIDEO_JS else UNPIN_VIDEO_JS) { r ->
                // 钉没钉成不能靠猜：结果直接落日志（'ok' / 'none' / JS 异常文本）
                Log.i("Cinema", "pinVideo($pin) -> $r")
            }
        }
    }

    /* 开演/切到放映的第二道保险：screen()/分段开关里的那次 pin 可能跑在布局切换
       之前（用旧视口高度，contain 居中后顶上出黑边 —— 用户实测"错位一两秒"）。
       resize 监听是主保险；这里等两帧（布局已落定）再补钉一次，把最坏窗口压到
       ~32ms 肉眼不可见。 */
    LaunchedEffect(theater) {
        if (theater) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            pinVideo(true)
        }
    }

    /**
     * 甲板按键打到**本机这个 WebView**（房主自己就是播放器）：-10/+10/暂停/继续
     * 和观众端指令走同一条 jsFor 路 —— 探针下一轮把新位置广播出去，观众自然跟上。
     */
    fun hostCmd(cmd: WatchCmd) {
        // 自播在播时，一切控制直接给本地播放器（网页不再收指令）
        if (TheaterPlayer.handle(cmd)) return
        val p = player?.posMs ?: 0L
        val d = player?.durMs ?: 0L
        /* Seek 的 JS 自带 v.play()（观众指令与恢复路径共用同一段），暂停中拖进度
           会被"顺手播起来" —— 暂停态就补一条 Pause 把它按回去（审查 A3-3）。 */
        val wasPaused = player?.playing == false
        webView.post {
            webView.evaluateJavascript(WatchSync.jsFor(cmd, p, d), null)
            if (cmd is WatchCmd.Seek && wasPaused) {
                webView.evaluateJavascript(WatchSync.jsFor(WatchCmd.Pause, p, d), null)
            }
        }
    }

    /* 系统返回 = 浏览器后退，全屏永远优先，历史到头才离开放映厅
       （MainActivity 的 showCinema handler 在本屏之后注册不上，由 else 分支的
       onBack() 直接离场，行为等价）。用户反馈的场景：网页被广告/自动跳转带走后
       只能重新打开原链接 —— 现在按返回一步步退回看电影那一页，
       动作行另有常驻「后退」按钮，两种走法都有。 */
    BackHandler {
        if (!browserBack()) onBack()
    }
    // 弹层比页面更"深"：后注册优先级更高 —— 收藏夹开着时返回先关它，
    // 否则会隔着遮罩退网页/离厅（审查 A3-5）
    BackHandler(enabled = showFavs) { showFavs = false }
    BackHandler(enabled = askCloseRoom) { askCloseRoom = false }
    BackHandler(enabled = askPickList) { askPickList = false }
    BackHandler(enabled = pickTarget != null) { pickTarget = null }
    /* 全屏态的系统返回 = 先退出全屏（方向随 deckHidden effect 交还），不放行到
       浏览器后退。**必须注册在这批 BackHandler 之后**：Compose 的返回栈后注册
       优先 —— 放在前面会被"浏览器后退"抢走（实测：返回键离开放映厅回首页，
       App 卡在横屏）。 */
    BackHandler(enabled = deckHidden.value) {
        deckHidden.value = false
    }

    /* 退回目标的安顿窗口：onPageStarted 落到目标 ≠ 站点安顿了 —— 页面可能紧接着
       又把自己 replace 成广告。窗口内出现新导航即视为"被弹走"（见 onPageStarted
       的 bounce 分支），窗口到期没人闹就结案。 */
    LaunchedEffect(CinemaBrowser.backTarget) {
        val t = CinemaBrowser.backTarget ?: return@LaunchedEffect
        delay(2_500)
        if (CinemaBrowser.backTarget == t) {
            CinemaBrowser.backTarget = null
            CinemaBrowser.backBounces = 0
            CinemaBrowser.backSawTarget = false
        }
    }

    LaunchedEffect(pageUrl, webGen, reloadSeq) {
        // 实例换代/会话散场：holder 里的已经不是这一屏领的那只，别往死实例上 loadUrl
        if (CinemaBrowser.webView !== webView) return@LaunchedEffect
        // 程序化换页/刷新/渲染重建：退回窗口作废 —— 否则这些入口发出的新导航
        // 会被 onPageStarted 的"被弹走"分支吃掉（审查 A3-4：点刷新、开收藏、
        // 崩溃重建都试过会被连退吞掉）。browserBack 的 loadUrl 不改 pageUrl，
        // 不会走到这里，退回窗口照常存活。
        CinemaBrowser.resetBackWindow()
        sniffer.clear()
        hits = emptyList()
        probe = null
        // 空哨兵 "" = 还没有网页：什么都不加载，连"离页记忆"都不许留 ——
        // cinemaLastPageUrl 是跨场次的全局量，上一场残留的旧地址会冒充"当前页"，
        // ★收藏/回厅接片全被污染。空厅进门先清账。
        if (pageUrl.isEmpty()) {
            cinemaLastPageUrl = null
            return@LaunchedEffect
        }
        /* 同一页还活着就**别再 loadUrl**：WebView 进程级存活后，重进厅/覆盖层回来
           都会走到这里 —— 一次 loadUrl 就是一次"从头再来"，电影正在放的时候它就是
           观众看到的"播放中断、进度循环"。reloadSeq>0 = 用户在地址栏对同一条显式
           点了「打开」（真刷新语义），照常重载。 */
        if (reloadSeq == 0 && CinemaBrowser.loadedUrl == pageUrl) {
            cinemaLastPageUrl = pageUrl
            return@LaunchedEffect
        }
        webView.loadUrl(pageUrl)
        CinemaBrowser.loadedUrl = pageUrl
        cinemaLastPageUrl = pageUrl   // 给 openCinema 的"回厅接片"留导航记忆
        // 「上次一起看」落盘（方案A 的"上次"芯片）：进程重启也在。只记程序化打开
        // 这一下（地址栏/芯片/递链接）—— 站内点跳不算，用户语义是"我上次开的站"。
        favPrefs.edit().putString("recent", pageUrl).apply()
    }

    // 厅已经开着的时候又来了一条分享（singleTop + onNewIntent）：换片，不重开 Activity。
    //
    // 三种情况必须分开：冷启动首帧（这一屏本来就是为这条链接开的，别再刷一遍）、
    // 换一条新链接（正常换页）、**同一条链接第二次递进来**。
    // 最后一种原来什么都不做，于是"我明明分享了，屏幕却没反应"——
    // 而人重复分享，多半是因为第一次没成（页面报错、被挡、想重看），
    // 所以正确的响应是重新加载这一页，并让人看见我们在动。
    var handledShare by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialUrl, jumpSeq) {
        val u = initialUrl?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val norm = normalizeUrl(u)
        /* 首拍守卫：进屏地址 ≠ 上次离开的页 = 分享递了**新**链接（enterCinema 路径，
           此刻加载 effect 已发起但 onPageStarted 还没回来，cinemaLastPageUrl 还是旧页）
           —— 位置恢复属于旧页面，留着会把旧片的位置拨到新片上；同长只差 15 秒的两部片
           连时长门都拦不住（审查 A3-2）。openCinema 的回厅接片目标恒等于离开页，
           不会命中这条。 */
        if (handledShare == null && cinemaLastPageUrl != null && norm != cinemaLastPageUrl) {
            pendingRestore = null
        }
        when {
            handledShare == u -> {
                /* 地址栏/测试胶囊翻去过别的页时，pageUrl 已经不等于这条分享 ——
                   原来无条件 `loadUrl(pageUrl)` 重载的是**当前页**，分享的那条被
                   静默吞掉（REVIEW-2026-09-27 P1）。pageUrl 对得上才是"真的再来一遍"。 */
                if (pageUrl != norm) {
                    inputUrl = u
                    pageUrl = norm   // 换页 effect 负责清表 + 加载
                    note = "又是这一条，回到这一页"
                } else {
                    webView.loadUrl(norm)
                    note = "又是这一条，重新加载这一页"
                }
            }
            norm != pageUrl -> {
                inputUrl = u
                pageUrl = norm
                // 地址被换掉了：位置恢复属于旧页面 —— 留着会把旧片的位置拨到新片上
                pendingRestore = null
                note = "收到递进来的链接，换片中…"
            }
        }
        handledShare = u
    }

    // EME 探测：先发起（结果写到 window 上），再轮询读 —— 不赌 WebView 会不会 await Promise
    // showPanel 进 key：量具默认收着，面板没开就别探 —— 换页即注入 + 最长 16 秒轮询
    // 而读数只在「展开嗅探」里才显示，从没打开过也白付这笔开销（REVIEW P3）。
    LaunchedEffect(pageUrl, webGen, showPanel) {
        if (!showPanel) return@LaunchedEffect
        webView.post { webView.evaluateJavascript(MediaSniffer.emeStartJs(), null) }
        var tries = 0
        while (tries < 20) {
            delay(800)
            if (CinemaBrowser.webView !== webView) return@LaunchedEffect   // 换代/散场护栏
            webView.evaluateJavascript(MediaSniffer.emeReadJs()) { raw ->
                val r = CinemaProbe.parseEme(raw)
                // 换页会把 window 上的暂存结果一起冲掉（state=missing）——
                // 这时要重新发起探测，而不是把 "missing" 当成最终结论显示给房主。
                if (r.state == "missing") {
                    webView.post { webView.evaluateJavascript(MediaSniffer.emeStartJs(), null) }
                } else {
                    eme = r
                    if (r.state != "running") {
                        Log.i("Cinema", "EME ${r.state} api=${r.api} keys=${r.createKeys} err=${r.detail}")
                    }
                }
            }
            if (eme?.state?.let { it != "running" && it != "pending" } == true) break
            tries++
        }
    }

    // 每 2 秒问一次页面：currentSrc / 时长 / 尺寸 + Resource Timing 里的媒体 URL
    LaunchedEffect(pageUrl, webGen) {
        while (true) {
            delay(2_000)
            // 实例换代/会话散场：holder 里的已经不是这一屏领的那只，别往死实例上发 JS
            if (CinemaBrowser.webView !== webView) return@LaunchedEffect
            webView.evaluateJavascript(MediaSniffer.probeJs()) { raw ->
                val p = CinemaProbe.parsePageProbe(raw) ?: return@evaluateJavascript
                probe = p
                sniffer.observePageProbe(p, pageUrl)
                hits = sniffer.snapshot()
                /* 网页自己报的时长是最准的（就是正在播的那条）—— 候选列表里正在播的
                   那条直接用它，不用等清单解析（清单有时只给一个时间窗，会偏短）。 */
                if (p.durationSec > 1.0 && !p.isBlob && p.currentSrc.isNotBlank() &&
                    !durations.containsKey(p.currentSrc)
                ) {
                    durations = durations + (p.currentSrc to (p.durationSec * 1000).toLong())
                }
                if (p.resources.isNotEmpty() || p.currentSrc.isNotBlank()) {
                    Log.i(
                        "Cinema",
                        "PAGEPROBE blob=${p.isBlob} dur=${p.durationSec.toInt()}s " +
                            "size=${p.videoWidth}x${p.videoHeight} res=${p.resources.size}",
                    )
                }
            }
            // 位置/时长/标题：复用 watch 的探针（它已经在算"页面上最大那个 <video>"），
            // 不再另写一份，免得两套探测逻辑以后各改各的。
            webView.evaluateJavascript(WatchSync.probeJs()) { raw ->
                val w = WatchSync.parseProbe(raw, pageUrl) ?: return@evaluateJavascript
                /* 找不到 <video> 时探针固定回 (0,0,暂停)（WatchSyncTest 钉过这个形状），
                   而这不是"权威进度"：直开 .m3u8/.mp4 走 Chromium 内置播放器、放映中
                   换页、退出再进厅的头几秒都查不到 <video>。照发会把观众的时间轴
                   拽回 0 并暂停（REVIEW-2026-09-27 P1）；本地 player 也保留上一条好值。 */
                if (!w.found) return@evaluateJavascript
                player = w
                /* 指挥权不在网页时（自播/浮窗接管）：网页的进度恢复与广播都不许走 ——
                   resume 会 seek 网页、publish 会把观众时间轴拽回网页读数。 */
                if (commander != Commander.Page) return@evaluateJavascript
                /* 预热跟随（内部节流）：把预热的浮窗播放器起播点挪到页面当前进度 ——
                   HOME 那一刻目标分片已在缓冲里，首帧只剩解码时间（否则 seek 后
                   目标位置的分片是现下的，起播→首帧仍要 3 秒）。 */
                FloatPlayer.reseek(w.posMs)
                // 放映模式：探针顺路确保画面还钉着（换页/崩溃重建后 JS 标记没了，
                // 这里每轮都会重新钉一次；PIN_VIDEO_JS 内部按 dataset 去重）
                if (theater) pinVideo(true)
                /* 厅回来接片必须**先于广播**判断：刚加载完的页面停在 0s，先照常
                   publish 会把观众的时间轴拽回 0（分享端画面还在恢复中，看着就是
                   两端一起被重置 —— 2026-09-29 用户实测）。要拨回就这轮不广播，
                   拨完的下一轮探针（2 秒后）再把新位置发出去。 */
                val resume = pendingRestore
                if (resume != null && cinema != null && pageUrl == resume.url &&
                    cinemaLastPageUrl == resume.url
                ) {
                    val sameFilm = resume.durMs <= 0 || (w.durMs - resume.durMs) in -15_000..15_000
                    if (!sameFilm) {
                        pendingRestore = null   // 不是同一部片：恢复点作废，别一直悬着
                    } else if (w.durMs <= 0) {
                        /* 元数据还没到 —— 先把"该播"拨起来。HLS 这类懒加载页面
                           **暂停着就永远拿不到 dur**：原来的 durMs>0 门禁等不到人按
                           播放，重进厅就卡死 0:00（2026-09-29 模拟器复现，24 秒
                           dur=0s 不恢复）。页面存活时这条路根本不走（同页免重载），
                           它兜的是覆盖层/崩溃重建这类 WebView 真被换掉的场景。 */
                        if (resume.playing && !w.playing && w.posMs <= 0) {
                            webView.evaluateJavascript(WatchSync.jsFor(WatchCmd.Play, 0, 0), null)
                        }
                        return@evaluateJavascript   // 别把 pos=0 广播出去，把观众拽回零
                    } else {
                        pendingRestore = null
                        if (w.posMs < resume.posMs - 3_000) {
                            // 站点自己的记忆播放没追上离开时的位置才拨，免得两套恢复打架
                            val target = resume.posMs.coerceAtMost(w.durMs - 1_000).coerceAtLeast(0L)
                            webView.evaluateJavascript(
                                WatchSync.jsFor(WatchCmd.Seek(target), w.posMs, w.durMs), null,
                            )
                            if (resume.playing) {
                                webView.evaluateJavascript(
                                    WatchSync.jsFor(WatchCmd.Play, target, w.durMs), null,
                                )
                            } else {
                                // Seek 的 JS 自带 v.play()：暂停着离开的，拨回去也得按住暂停
                                // ——否则"暂停恢复"会被顺手变成播放并广播出去（审查 A3-3）
                                webView.evaluateJavascript(
                                    WatchSync.jsFor(WatchCmd.Pause, target, w.durMs), null,
                                )
                            }
                            Log.i(
                                "Cinema",
                                "厅回来接片：${w.posMs / 1000}s -> ${target / 1000}s" +
                                    if (resume.playing) " 并继续播放" else "",
                            )
                            return@evaluateJavascript
                        }
                    }
                }
                /* 指挥权已交给浮窗/自播：网页这个播放器已停，它的读数是陈旧的，
                   再照发会把观众的时间轴拽回旧位置（两套进度打架的根源）。 */
                if (commander != Commander.Page) return@evaluateJavascript
                CallSession.publishCinemaProgress(w.posMs, w.durMs, w.playing)
            }
        }
    }

    /* 浮窗交接 —— **先立新、后废旧**：网页那个照旧播着，等浮窗**真的播起来**才停它。
       判据用 float.ready（isPlaying 且位置已走），不能刚调 play() 就交权：
       HLS 常常要缓冲一两秒，那一两秒里两边都没在播，观众会收到一次假暂停。 */
    LaunchedEffect(float.active, float.ready) {
        if (float.active && float.ready && commander == Commander.Page) {
            webView.post {
                webView.evaluateJavascript(
                    WatchSync.jsFor(WatchCmd.Pause, float.posMs, float.durMs), null,
                )
            }
            commander = Commander.Float
            note = "浮窗接手了 —— 网页那边先停，进度接着走"
            Log.i("Cinema", "FLOAT handoff @ ${float.posMs / 1000}s")
        }
    }

    /* 指挥官是浮窗时，进度改由浮窗读数广播（网页那个已停，不再更新）。 */
    LaunchedEffect(commander, float.active) {
        if (commander != Commander.Float || !float.active) return@LaunchedEffect
        while (true) {
            delay(500)
            val f = FloatPlayer.state.value
            if (!f.active) break
            if (cinema != null) CallSession.publishCinemaProgress(f.posMs, f.durMs, f.playing)
        }
    }

    /* 浮窗播不起来（防盗链之类的站点）：把指挥权还给网页，最坏就是退回现在的样子。 */
    LaunchedEffect(float.error) {
        if (float.error != null && commander == Commander.Float) {
            commander = Commander.Page
            webView.post {
                webView.evaluateJavascript(WatchSync.jsFor(WatchCmd.Play, float.posMs, 0), null)
            }
            note = "浮窗播不了这个站（可能是防盗链），已退回网页里播"
        }
    }

    /* 正在分享屏幕：浮窗**只藏视图**，播放与进度照旧 ——
       藏掉是因为它会被采集进分享画面（还可能是个黑框）；
       但声音和进度必须继续，否则观众那边会以为房主暂停了。 */
    LaunchedEffect(screenShared, float.active) {
        if (float.active) FloatPlayer.setHidden(screenShared)
    }

    /* 放映模式（钉满全屏）时浮窗**不该再挂着**：同一个片，屏上已经有一份全屏的，
       角落再浮一个小的纯占地方。收浮窗（反向交接，进度拨回网页），观众端不受影响。
       **只在 App 在前台时收** —— 切后台那条规则会自动开浮窗，两条规则曾在这里
       打架：浮窗刚被后台规则拉起，就被这条前台规则立刻杀了（实测 ExoPlayer
       创建 0.5 秒即 Release，浮窗根本活不过 ON_STOP）。 */
    var appResumed by remember { mutableStateOf(true) }
    LaunchedEffect(theater, float.active, appResumed) {
        if (theater && float.active && appResumed) {
            closeFloat()
            note = "放映时画面已经全屏，浮窗收起来了"
        }
    }

    /* ── 画面自播（2B）──：进放映态就把画面从网页切到自播 —— 广告/跳转没有 DOM 可跳，
       控制语言也与面板统一。起播失败（error）自动回退网页画面（见下方回退 effect）。
       换片/退出放映/离场由 key 变化驱动；start 自身幂等。 */
    LaunchedEffect(theater, cinema?.version, hits.size) {
        if (!theater || cinema == null) {
            if (TheaterPlayer.state.value.active) {
                TheaterPlayer.stop()
                if (commander == Commander.Theater) {
                    commander = Commander.Page
                    // 网页此前被交棒暂停了 —— 退出放映要把它放回来
                    webView.post {
                        webView.evaluateJavascript(
                            WatchSync.jsFor(
                                WatchCmd.Play, player?.posMs ?: 0L, player?.durMs ?: 0L,
                            ),
                            null,
                        )
                    }
                }
            }
            return@LaunchedEffect
        }
        if (TheaterPlayer.state.value.active) return@LaunchedEffect
        val h = CinemaProbe.bestOf(hits) ?: return@LaunchedEffect
        startTheater(h)
    }

    /* 自播交接：**先立后废旧** —— 自播首帧在走才暂停网页，指挥权交给 Theater。 */
    LaunchedEffect(tp.active, tp.ready) {
        if (tp.active && tp.ready && commander == Commander.Page) {
            webView.post {
                webView.evaluateJavascript(
                    WatchSync.jsFor(
                        WatchCmd.Pause, player?.posMs ?: tp.posMs, player?.durMs ?: 0L,
                    ),
                    null,
                )
            }
            commander = Commander.Theater
            note = "画面已切到自播 —— 广告/点击跳转够不着了"
        }
    }

    /* 自播起不来（防盗链/DRM…）：停自播、交还网页并让它继续播 —— 最坏就是现状。 */
    LaunchedEffect(tp.error) {
        if (tp.error != null) {
            TheaterPlayer.stop()
            if (commander == Commander.Theater) {
                commander = Commander.Page
            }
            webView.post {
                webView.evaluateJavascript(
                    WatchSync.jsFor(WatchCmd.Play, player?.posMs ?: 0L, player?.durMs ?: 0L),
                    null,
                )
            }
            note = "这个站的画面自播起不来（防盗链/DRM），已回退网页画面继续放"
        }
    }

    /* 控制条 3 秒不碰自动收起。 */
    LaunchedEffect(showTctl) {
        if (showTctl) {
            delay(3_000)
            showTctl = false
        }
    }

    /* 自播指挥官的广播循环（与浮窗同款）。 */
    LaunchedEffect(commander, tp.active) {
        if (commander != Commander.Theater || !tp.active) return@LaunchedEffect
        while (true) {
            delay(500)
            val t = TheaterPlayer.state.value
            if (!t.active) break
            if (cinema != null) CallSession.publishCinemaProgress(t.posMs, t.durMs, t.playing)
        }
    }

    /* ── 浮窗预热（雨见给的启发，见 yjllq-float-window.md §2/§10）──
       雨见弹窗早（页面还在播就弹），加载和页面播放**并行**，所以"用着挺不错"；
       我们按 HOME 才起窗，3 秒网络等待全在等待路径上。改法：放映中就把播放器建好、
       低档挑好、清单备好 —— HOME 一按服务直接上屏（打点里会看到"接预热播放器"）。
       预热的播放器不播（playWhenReady=false）：不出声、不抢画面，只干活。
       换片/收厅/退出放映/离屏都要释放，防泄漏。 */
    val warmTarget = if (theater && cinema != null) {
        CinemaProbe.bestOf(hits)?.url
    } else {
        null
    }
    LaunchedEffect(warmTarget) {
        val target = warmTarget
        if (target == null) {
            // 退出放映/收厅：把预热窗停掉（没预热时空跑一次 startService 无副作用，但先省）
            if (FloatPlayer.state.value.prewarm || FloatPlayer.state.value.active) {
                FloatPlayer.stop(context)
            }
            return@LaunchedEffect
        }
        val h = hits.firstOrNull { it.url == target } ?: return@LaunchedEffect
        // 幂等：服务端已有 session 会忽略这次请求
        FloatPlayer.prewarm(
            context,
            FloatRequest(
                url = h.url,
                title = MediaSniffer.hostLabel(h.url),
                referer = h.referer,
                cookie = h.cookie,
                userAgent = h.userAgent,
                candidates = hits
                    .filter { MediaSniffer.playable(it.kind) && it.url.startsWith("http", true) }
                    .map { it.url },
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            val st = FloatPlayer.state.value
            if (st.prewarm) FloatPlayer.stop(context)
            TheaterPlayer.stop()   // 离场兜底：自播别留着出声
        }
    }

    /* 放映中把 App 退到后台（HOME / 切别的 App / 锁屏）：**自动切成悬浮窗继续播** ——
       不然想边干别的边一起看，就只能干瞪着这个 App（2026-09-30 用户要求）。
       回到前台且还停在放映态：反向交接，画面回到全屏。
       （切后台后网页里的播放器本来还能出声，但浮窗 ready 后"指挥官"逻辑会把它停掉 ——
       两套声音只活一个，这正是交接流程的职责。正在分享屏幕时不掺和：那时画面本来就
       在往外送，浮窗反而会被采集进去。） */
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            android.util.Log.i(
                "Cinema",
                "LIFECYCLE $event theater=$theater cinema!=null=${cinema != null} " +
                    "floatActive=${float.active} shared=$screenShared",
            )
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    appResumed = false
                    if (theater && cinema != null && !float.active && !screenShared) {
                        if (commander == Commander.Theater) {
                            /* 自播在放：浮窗按**自播位置**接棒（show 支持 seekMs），
                               然后停自播 —— 声音只活一个，指挥权归浮窗。 */
                            val at = tp.posMs
                            runCatching { openFloat(null, at) }
                            commander = Commander.Float
                            TheaterPlayer.stop()
                            note = "自播已交棒悬浮窗（按当前位置接着播）"
                        } else {
                            runCatching {
                                openFloat(null)
                                note = "已切到悬浮窗继续播"
                            }.onFailure { android.util.Log.w("Cinema", "autofloat fail: ${it.message}") }
                        }
                    }
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    appResumed = true
                    if (theater && float.active && commander == Commander.Float) {
                        val at = float.posMs
                        closeFloat()   // 浮窗进度拨回网页 + 停浮窗 + commander=Page
                        commander = Commander.Page
                        note = "回到全屏放映（自播接回中）"
                        // 自播从同位置接回：冷起 1-3s 期间网页在放，自播 ready 后自动暂停网页
                        if (theater && cinema != null) {
                            CinemaProbe.bestOf(hits)?.let { startTheater(it, posMs = at) }
                        }
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    /* 候选时长：异步回填。
       一页里常有正片/预告/广告好几条，光看地址分不清谁是谁 —— **时长是最直观的分辨依据**
       （2026-09-30 用户要求，对标雨见的候选列表）。探测要联网，所以：
        - 每条只探一次，结果按 URL 记住（状态在上面与 float 一起声明），重进不重复问；
        - 拿不到就是拿不到，界面显示"未知"，不编数字。 */
    LaunchedEffect(showPanel, hits.size) {
        if (!showPanel) return@LaunchedEffect
        val todo = hits
            .filter { MediaSniffer.playable(it.kind) && it.url.startsWith("http", true) }
            .filter { !durations.containsKey(it.url) }
            .take(8)                       // 一屏够用就行，别为几十条埋点也去联网
        if (todo.isEmpty()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            todo.forEach { h ->
                val ms = MediaDuration.probe(h)
                if (ms != null) {
                    withContext(Dispatchers.Main.immediate) {
                        durations = durations + (h.url to ms)
                    }
                }
            }
        }
    }

    /* 浮窗上点「换片」：把候选列表弹出来（同一个列表，选中即走交接/顶替流程）。 */
    DisposableEffect(Unit) {
        FloatPlayer.onPickRequest = { askPickList = true }
        onDispose { FloatPlayer.onPickRequest = null }
    }

    // 观众的放映请求落到这个 WebView 上（它才是播放器）。
    // 注册/摘除成对：这一屏卸载后还挂着回调，指令就会打到已销毁的 WebView 上。
    DisposableEffect(webView) {
        CallSession.onCinemaCommand = cinemaCmd@{ cmd ->
            if (CinemaBrowser.webView !== webView) return@cinemaCmd   // 换代/散场护栏
            // 自播在播时，观众指令也直接进本地播放器
            if (TheaterPlayer.handle(cmd.toWatchCmd())) return@cinemaCmd
            val p = player?.posMs ?: 0L
            val d = player?.durMs ?: 0L
            // 观众的 Seek 同样自带 play()：房主暂停时被观众拖一下进度不该变成播放
            val wasPaused = player?.playing == false
            webView.post {
                webView.evaluateJavascript(WatchSync.jsFor(cmd.toWatchCmd(), p, d), null)
                if (cmd is com.ticketfortwo.app.cinema.CinemaSync.Cmd.Seek && wasPaused) {
                    webView.evaluateJavascript(WatchSync.jsFor(WatchCmd.Pause, p, d), null)
                }
            }
        }
        onDispose {
            CallSession.onCinemaCommand = null
            /* 把“此刻在哪一页”交回 MainActivity：授权指引、同看、设置这些覆盖层在
               路由里是独立 Page —— 它们一出现就把本屏整屏卸载，回来时是全新实例。
               cinemaUrl 只在地址栏「打开」/递链接时更新，站内点链接它看不见 ——
               不回写的话，厅里点「分享我的屏幕」走完授权回来就被弹回上一个地址
               （E2E 实测：b.html → 授权 → 回来变 a.html，回厅接片也跟着接错）。 */
            onPageLeave(cinemaLastPageUrl ?: pageUrl)
            /* WebView **不再随屏销毁**：进程级持有（CinemaBrowser）。原来这里的
               destroy 就是"重进厅必重载"的根源 —— 恢复接力（等元数据→拨进度→续播）
               一旦掉棒，观众看到的就是进度循环/卡 0:00（2026-09-29 用户实测）。
               离厅只把视图从窗口摘下来，电影在后台继续放 —— 放映不该因为房主
               回首页看一眼而中断。clients 等下次进屏重装（见 remember(webGen)）。 */
            runCatching { (webView.parent as? ViewGroup)?.removeView(webView) }
        }
    }

    /**
     * 把一条候选递给对方（或换成它）。
     *
     * 放映中再点另一条 = **换片**，不需要先收厅再放：收厅那一步在观众那边
     * 会真的退回屏幕流，用它当中转等于白闪一下。
     */
    fun screen(h: MediaSniffer.Hit) {
        val wasScreening = cinema != null
        CallSession.setCinemaTrack(
            CinemaSync.Track(
                url = h.url,
                kind = h.kind.name.lowercase(),
                title = player?.title?.takeIf { t -> t.isNotBlank() && !t.startsWith("http", true) }
                    ?: MediaSniffer.hostLabel(h.url),
                durationMs = player?.durMs ?: 0L,
            ),
        )
        note = if (wasScreening) "已换片：${h.kind.name.lowercase()}"
        else "已把这条递给对方：${h.kind.name.lowercase()}"
        // 第一次递片 = 正式开演：切进放映模式（方案B），顶上钉画面、下面出甲板
        if (!wasScreening) {
            theater = true
            pinVideo(true)
        }
    }

    /* debug 钩子：把"递出 App 自己嗅到的那条地址"暴露给 adb 广播。
       测真实站点命中率时必须走这条，而不是我手写一个 URL 递出去 ——
       那样测的是传输通道，测不到嗅探与选路。 */
    /* 换代（webGen++）后钩子必须打到**当前** WebView：下面的 effect 只挂一次，
       直接闭包永远抓第一代（已 destroy 的实例）—— 递片照发、画面却没钉上，
       还把旧实例挂到本屏卸载（审查 A3-6）。rememberUpdatedState 读最新一代。
       （声明必须在组合作用域 —— 它是 @Composable，进不了 effect 的 lambda。） */
    val screenLatest = rememberUpdatedState<(MediaSniffer.Hit) -> Unit>({ screen(it) })
    DisposableEffect(Unit) {
        CinemaDebug.screenBest = {
            val h = CinemaProbe.bestOf(hits)
            if (h == null) null else { screenLatest.value(h); h.url }
        }
        CinemaDebug.candidateCount = { hits.count { MediaSniffer.playable(it.kind) } }
        onDispose {
            CinemaDebug.screenBest = null
            CinemaDebug.candidateCount = null
        }
    }

    /* ── 横屏是另一种排法 ─────────────────────────────────────────────────
     *
     * 用户直接指出："横屏状态的 UI 排版不太对，占用的位置太多了，能看到的有效信息很少。"
     * 量一下就明白他指的是什么：2400x1080 的横屏上，纵向只有约 390dp，而竖屏那套
     * 顶栏(56) + 地址行(56) + 胶囊行(48) + 底部卡(100~236) 一层层摞下来，
     * 留给画面的权重只剩一两百 dp —— 一块横屏手机放不了一个横屏视频，本末倒置。
     *
     * 横屏改成左右分栏：画面在左、吃掉尽可能多的宽度；地址、动作、状态卡挤进右边
     * 一条固定宽度的控制栏。这样画面拿到的是"整屏高度 × (屏宽 - 320dp)"，
     * 16:9 的片子在横屏上第一次是铺得开的。
     *
     * 顺带把三颗**测试用**的胶囊（换一条流 / 本地测试页 / HLS 测试流）挪进「展开嗅探」
     * 里面：它们是量具，不是给用户看的，而它们正好占了主操作那一行的一半宽度。
     * 依赖它们的脚本改成先点「展开嗅探」（scripts/drive_cinema_*.py 已同步）。 */
    val wide = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

    /** 收起/唤出浮窗：无权限先把用户领去系统设置（安卓规定只能他自己在设置里开）。 */
    fun toggleFloat() {
        when {
            float.active -> closeFloat()
            !FloatPlayer.canDrawOverlays(context) -> {
                runCatching { context.startActivity(FloatPlayer.openOverlaySettings(context)) }
                note = "要在桌面/别的应用上也看到小窗，得先在设置里允许「显示在其他应用上层」—— 打开后回来再点一次「浮窗播」"
            }
            else -> openFloat(null)
        }
    }

    /**
     * 开始放映 / 请求收厅。
     *
     * 放映/收厅：房主确认才切 —— 嗅探有认错的时候（广告分片、预告片），
     * 自动切等于把误判直接端给对方。
     */
    fun startOrAskClose() {
        if (cinema != null) {
            askCloseRoom = true     // 二次确认（唯一会中断放映的动作）
            return
        }
        val h = CinemaProbe.bestOf(hits)
        if (h != null) {
            screen(h)
        } else {
            val pv = player
            // 分开说三种"没候选"，每种都给出下一步：
            // ① 嗅到的只是本机文件地址；② 页面有播放器但没在放；③ 真的什么都没有。
            // ② 是最常见的一种（11 站样本里"没嗅到"的四站中两站如此：B 站、Vimeo
            // 都是按下播放才去取流，没有请求就没有可嗅的地址）—— 那就替他点上。
            note = when {
                CinemaProbe.localOnly(hits) != null ->
                    "嗅到的是本机文件地址（file://），对方播不了 —— 打开一个网页里的播放器再试"
                pv != null && !pv.playing -> {
                    webView.post {
                        webView.evaluateJavascript(
                            WatchSync.jsFor(WatchCmd.Play, pv.posMs, pv.durMs),
                            null,
                        )
                    }
                    "这页还没播 —— 先替你点上播放，等它开始取流再按一次「开始放映」"
                }
                else ->
                    "还没嗅到地址 —— 先在这页把视频点成播放（多数站点是按了播放才去取流），" +
                        "再按开始放映"
            }
        }
    }

    val addressRow: @Composable (Modifier) -> Unit = { rowModifier -> Row(
        rowModifier.fillMaxWidth().padding(horizontal = GlassDimens.screenH, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompactGlassField(
            value = inputUrl,
            onValueChange = { inputUrl = it },
            label = "地址",
            // 空厅（引导页撤下、还没输入）不再是冷冰冰的白框：告诉用户这里打网址
            placeholder = "输入网址，一起看",
            // 「打开网站」入口把焦点拨进来 —— 句柄挂在这一行唯一的输入框上
            focusRequester = addrFocus,
            // 权重给到 1f 之外还要留缝：不加 weight 时限宽的行为是"文字压在按钮下面"，
            // 实测长 URL 会一路顶到「打开」按钮底下，看着像按钮粘在字上。
            //
            // 框与按钮**必须同高**（274dp 窄屏实测：框 40、按钮 52，居中之后按钮
            // 上下各探出 6dp，这一行看着像两个没对齐的零件）。44dp 是触控下限，
            // 所以把框抬到 44、按钮压到 44，而不是反过来迁就 40。
            modifier = Modifier.weight(1f).padding(end = 2.dp),
            boxHeight = 44.dp,
        )
        // 刷新：真浏览器语义 —— 重载**当前**这一页（站内点跳走后也对），不是地址栏那条
        GlassIconBtn("⟳", backdrop) {
            webView.reload()
            note = "重新加载这一页"
        }
        // 收藏开关：★ = 当前页已在收藏夹；再点一次取消。列表入口在动作行「收藏夹」
        GlassIconBtn(
            if ((cinemaLastPageUrl ?: pageUrl).let { u -> favs.any { it.substringBefore('␟') == u } }) "★" else "☆",
            backdrop,
            accent = (cinemaLastPageUrl ?: pageUrl).let { u -> favs.any { it.substringBefore('␟') == u } },
        ) { toggleFav() }
        // 打开：圆角矩形 —— PrimaryPill 的 percent=50 在两字按钮上糊成一颗圆球，
        // 和方框不同高不同形（2026-09-29 用户反馈）；改用液态玻璃圆角矩形
        LiquidGlassButton(
            onClick = {
                val u = normalizeUrl(inputUrl)
                if (u == pageUrl) reloadSeq++ else pageUrl = u
                note = "正在打开，嗅探中…"
            },
            backdrop = backdrop,
            modifier = Modifier.height(44.dp).width(64.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = inputUrl.isNotBlank(),
            surfaceColor = Ink.AccentSolid,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "打开",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
    }

    val actionRow: @Composable () -> Unit = { Row(
        /* 这一排**只放浏览器自己的动作**（后退/收藏夹/嗅探）——
           「开始放映」「分享我的屏幕」「浮窗播」是厅的动作，按 2026-09-30 定稿挪进
           底部控制卡（CinemaPanel 顶部）……
           原来这排是五颗不滚动的胶囊，屏宽不够时最后一颗被切出屏外（2026-09-29
           实测点不到），改成横滑后主操作看得见了，但"两类动作混在一排"依旧 ——
           这次按"谁拥有它"分家，浏览器行不再滚也放得下。 */
        Modifier.fillMaxWidth().padding(start = GlassDimens.screenH, bottom = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 浏览器后退：广告/自动跳转后一步步退回上一页（与系统返回键同一行为，
        // 没有这颗按钮时用户只能重开链接 —— 2026-09-28 用户反馈）
        // 可用性 = 原生历史 或 自家记录（广告 replace 吃掉原生历史时按钮不许灰死 ——
        // 那正是"回不去上一页"的直接原因，2026-09-29 用户反馈）
        GlassTextButton("后退", onClick = { browserBack() }, backdrop,
            enabled = canGoBack || CinemaBrowser.navStack.size >= 2)
        // 收藏夹：存过的网站一键回来（参考雨见「书签收藏」；本地存储，无服务器）
        GlassTextButton("收藏夹", onClick = { showFavs = true }, backdrop)
        GlassTextButton(if (showPanel) "收起嗅探" else "展开嗅探", onClick = {
            showPanel = !showPanel
        }, backdrop)
    }
    }

    /** 复制邀请：顶栏按钮和面板邀请行共用这一个动作（含 note 反馈与 Toast）。 */
    val copyInvite: () -> Unit = {
        inviteUrl?.let {
            context.copy("邀请链接", it)
            note = "邀请链接已复制，发给对方就能进厅"
        }
    }

    /** 系统分享面板发邀请（放映中拉人进厅的直达通道，与复制并列）。 */
    val shareInviteAction: () -> Unit = {
        inviteUrl?.let { context.shareInvite(it) }
    }

    val panel: @Composable (Modifier) -> Unit = { panelModifier -> CinemaPanel(
        modifier = panelModifier,
        wide = wide,
        backdrop = backdrop,
        cinema = cinema,
        player = player,
        allowControl = allowControl,
        onAllowChange = {
            allowControl = it
            CallSession.setViewerMayControl(it)
        },
        hits = hits,
        note = note,
        showSniffer = showPanel,
        probe = probe,
        eme = eme,
        inviteUrl = inviteUrl,
        viewerOnline = viewerOnline,
        playback = playback,
        voiceLine = VoiceMode.label(voiceMode),
        onCopyInvite = copyInvite,
        onShare = shareInviteAction,
        onPick = { h -> screen(h) },
        durations = durations,
        playingUrl = probe?.currentSrc,
        onFloatPick = { h -> requestPlay(h) },
        onStartScreening = { startOrAskClose() },
        onStartShare = onStartShare,
        floatActive = float.active,
        onToggleFloat = { toggleFloat() },
        onTestUrl = { u ->
            inputUrl = u
            if (u == pageUrl) reloadSeq++ else pageUrl = u
        },
    )
    }

    Column(
        Modifier
            .fillMaxSize()
            /* edge-to-edge 下底部卡片直接压到手势条上（2026-10-01 反馈）——
               整个放映厅让开导航栏：浏览底卡与放映甲板一起收进来。 */
            .navigationBarsPadding(),
    ) {
        GlassPageBar(backdrop, title = "放映厅", onBack = onBack) {
            /* 放映模式下顶栏要挤下「放映|浏览」，状态句换短版 + 让位（weight），
               否则标题被挤到换行（真机截图实测：放映/厅 断成两行）。 */
            Text(
                when {
                    theater && viewerOnline -> "1 人已直连"
                    theater -> "等对方进来"
                    viewerOnline -> "对方已在厅里"
                    else -> "厅已开 · 等对方进来"
                },
                fontSize = 11.5.sp,
                color = if (viewerOnline) Ink.Live else Ink.TextLow,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            /* 放映/浏览 模式开关（方案B）：**横竖屏都显示** —— 原来横屏隐藏这颗开关，
               而横屏布局又不认 theater 状态，于是"横过来就回到浏览界面"且无从切回
               （2026-10-01 用户反馈）。配合下方横屏放映形态一起修。 */
            Box(Modifier.width(6.dp))
            ModeSeg(
                theater = theater,
                backdrop = backdrop,
                onTheater = { theater = true; pinVideo(true) },
                onBrowse = { theater = false; pinVideo(false) },
            )
            /* 顶栏不再放「复制邀请」：竖屏浏览有底卡邀请行、放映模式有甲板时间行胶囊、
               横屏右栏也有 —— 顶栏那颗和地址下方那颗重复（2026-09-29 用户反馈）。 */
        }

        if (wide) {
            /* 横屏放映形态（2026-10-01）：画面铺满整行（竖屏同款钉屏逻辑把 video
               钉满 WebView 视口），控制交给网页播放器自己的控制条（轻点即出）；
               浏览态仍是左右分栏。原来横屏不认 theater，切了开关界面纹丝不动。
               画面优先级与竖屏**同一套**：转播回显 → 自播首帧 → 网页钉屏 ——
               原来只有 WebView，自播交棒后横屏画面倒退回网页的旧进度（实测）。 */
            if (theater && pageUrl.isNotEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 6.dp),
                ) {
                    key(webGen) {
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    }
                    if (TheaterPlayer.relaying && localVt != null) {
                        VideoLayer(
                            track = localVt,
                            modifier = Modifier.fillMaxSize(),
                            onLabel = "theater-relay-land",
                        )
                    } else if (tp.active && tp.ready) {
                        AndroidView(
                            factory = { android.view.TextureView(it).also { tv ->
                                TheaterPlayer.attach(tv)
                                tv.setOnTouchListener { _, _ -> true }   // 横屏自播画面同样拦 touch
                            } },
                            update = { tv -> TheaterPlayer.attach(tv) },
                            onRelease = { TheaterPlayer.detach() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    if (deckHidden.value) {
                        val tapIndL = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                        Box(
                            Modifier
                                .matchParentSize()
                                .clickable(interactionSource = tapIndL, indication = null) {
                                    deckHidden.value = false
                                    restoreOrientation()   // 唤回甲板 = 退出全屏，方向交还系统
                                },
                        )
                    }
                }
            } else {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(start = GlassDimens.screenH, bottom = 6.dp),
                ) {
                    // key(webGen)：AndroidView 的 factory 只在节点入组合时跑一次，
                    // 换代后不换 key 的话它抓着的还是旧（已 destroy）实例。
                    if (pageUrl.isEmpty()) EmptyStage("在右侧地址栏输入网址，一起看；下面有粘贴 / 上次 / 收藏夹", Modifier.fillMaxSize())
                    else key(webGen) {
                        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.width(320.dp).fillMaxHeight()) {
                    addressRow(Modifier)
                    /* 横屏同样给快捷芯片（方案A）：右栏进厅即输，芯片就在地址行下面。 */
                    if (pageUrl.isEmpty()) {
                        RoomShortcuts(
                            backdrop = backdrop,
                            recentUrl = recentUrl,
                            onPaste = { pasteFromClip() },
                            onRecent = { openRecent(it) },
                            onFavorites = { showFavs = true },
                        )
                    }
                    actionRow()
                    /* weight(1f, fill = false)：让卡片**贴着内容长**，但最多只到栏底。
                       给满 weight(1f) 的实测结果是下面一大块空黑玻璃
                       （.dev/land-01-cinema.png），内容只有三四行却被拉去填满剩余高度；
                       完全不给 weight 又会在展开嗅探时把最后一行顶出屏幕外没得滚。
                       fill=false 同时满足两条：短的时候不撑，长的时候封顶并可滚。 */
                    panel(Modifier.weight(1f, fill = false))
                }
            }
            } /* 横屏浏览分支结束 */
        } else {
            /* 竖屏的浏览/放映**共用同一个 WebView 节点** —— 分支里各挂一个的话，
               每次切换 AndroidView 都整棵 detach/reattach，Chromium 重挂黑闪一两帧、
               再叠加页面重排 = 用户实测的"切换闪烁"。切模式只改这一份节点的布局
               约束（300dp ↔ 填满），视口变化由页内 resize 重钉兜底；节点不动，画面就不闪。 */
            if (!theater) {
                addressRow(Modifier)
                actionRow()
                /* 方案A：空厅的快捷芯片行 —— 粘贴 / 上次一起看 / 收藏夹。
                   引导页删掉后，原来那两张操作卡的目的全压进这一行：
                   每颗都是一下就到位，不存在"先撤引导再聚焦"的中间步。 */
                if (pageUrl.isEmpty()) {
                    RoomShortcuts(
                        backdrop = backdrop,
                        recentUrl = recentUrl,
                        onPaste = { pasteFromClip() },
                        onRecent = { openRecent(it) },
                        onFavorites = { showFavs = true },
                    )
                }
            }
            /* 2026-09-30 结构修正（三案公共前提）：视频区**吃剩余**（weight），
               下面的甲板**贴内容**（不给 weight）。以前视频钉死 300dp、甲板 weight 拉满，
               内容三四组却有一屏高 → 卡片底部一大块空玻璃（用户截图实测）。
               现在面板矮多少、视频就长高多少；面板在后测量、视频按剩余分配，空白恒为 0。 */
            Box(Modifier.weight(1f)) {
                // 没打开网页就不挂 WebView：空厅是一块深色的"待放"屏，不是白板
                if (pageUrl.isEmpty()) EmptyStage(
                    if (theater) "还没选片 —— 去「浏览」打开一个视频页，或直接分享你的屏幕"
                    else "输入网址就能一起看；上面有「粘贴 / 上次 / 收藏夹」",
                    Modifier.fillMaxSize(),
                )
                else if (TheaterPlayer.relaying && localVt != null) {
                    /* B 方案转播中：自播的帧已交给帧桥推给观众，本地看这条轨的回显。
                       VideoLayer 与观众端同款渲染 —— 你看到的正是观众看到的。 */
                    VideoLayer(
                        track = localVt,
                        modifier = Modifier.fillMaxSize(),
                        onLabel = "theater-relay",
                    )
                } else if (tp.active && tp.ready) {
                    /* 画面自播视图（2B）：画面是 TextureView —— 点击**进不到网页**，
                       广告/整块画面跳转没有 DOM 可跳；轻点弹玻璃控制条（3 秒自动隐）。
                       **ready 才接管画面**（2026-10-01）：起播要拉流 2-3 秒，原来
                       active 就把黑 TextureView 盖上来 —— 浏览切放映"卡 3 秒黑屏"
                       就是它。未 ready 时落到底下的网页钉屏分支，网页还在播（暂停
                       交棒本来就发生在 ready），首帧一到无缝切换。 */
                    val tapInd = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable(interactionSource = tapInd, indication = null) {
                                showTctl = true
                            },
                    ) {
                        AndroidView(
                            factory = {
                                android.view.TextureView(it).also { tv ->
                                    TheaterPlayer.attach(tv)
                                    /* 自播画面自己消费 touch（2026-10-01 复测穿透修复）：
                                       TextureView 默认不消费，touch 穿到底下 WebView →
                                       网页收点击 → 跳广告。拦下来并顺手弹自播控制条。 */
                                    tv.setOnTouchListener { _, _ -> showTctl = true; true }
                                }
                            },
                            update = { tv -> TheaterPlayer.attach(tv) },
                            onRelease = { TheaterPlayer.detach() },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (showTctl) {
                            val barInd = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                            fun tap(click: () -> Unit): Modifier = Modifier
                                .clickable(interactionSource = barInd, indication = null) { click() }
                            Row(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 16.dp)
                                    .clip(RoundedCornerShape(32.dp))
                                    .background(Color(0x73000000))
                                    .padding(horizontal = 22.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.spacedBy(26.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "−10",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = tap { hostCmd(WatchCmd.Step(-10_000)) },
                                )
                                Text(
                                    if (tp.playing) "❚❚" else "▶",
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = tap {
                                        hostCmd(if (tp.playing) WatchCmd.Pause else WatchCmd.Play)
                                    },
                                )
                                Text(
                                    "+10",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = tap { hostCmd(WatchCmd.Step(10_000)) },
                                )
                            }
                        }
                    }
                } else key(webGen) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
                /* 全屏态的唤回层：甲板收起后整块画面都可轻点，一下唤回控制甲板。
                   只在全屏态出现，浏览态（网页要收点击）不受影响。 */
                if (theater && deckHidden.value) {
                    val tapInd = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    Box(
                        Modifier
                            .matchParentSize()
                            .clickable(interactionSource = tapInd, indication = null) {
                                deckHidden.value = false
                                restoreOrientation()   // 唤回甲板 = 退出全屏，方向交还系统
                            },
                    )
                }
            }
            /* 甲板装进玻璃卡：裸文本浮在壁纸上读不清（真机截图实测），
               和厅里其他卡片同一套玻璃语言。
               注意 if/else 是配对的：theater 画甲板、否则画浏览底卡 —— 全屏态
               （deckHidden）必须**只藏甲板**，不能把条件写进 if 让 else 的浏览底卡
               顶出来（2026-10-01 实测踩过：全屏一点，底下蹦出"开始放映"卡）。 */
            if (theater) {
                if (!deckHidden.value) GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    // 不给 weight —— 贴内容长；上限护栏防止极端小屏把视频挤没
                    .heightIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(horizontal = GlassDimens.screenH, vertical = 8.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        player?.title?.takeIf { it.isNotBlank() }
                            ?: cinema?.track?.title?.takeIf { it.isNotBlank() }
                            ?: "厅里还没选片",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextHi,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    if (cinema != null) {
                        Box(Modifier.width(8.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(if (mayControl) Ink.Live.copy(alpha = 0.15f) else Color(0x16FFFFFF))
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        ) {
                            Text(
                                if (mayControl) "对方可控制" else "仅观看",
                                fontSize = 11.sp,
                                color = if (mayControl) Ink.Live else Ink.TextLow,
                            )
                        }
                    }
                }
                Box(Modifier.height(12.dp))
                /* 自播接管时进度/时长/播放态读 TheaterPlayer（网页的读数是陈旧的）。 */
                val durMs = (if (tp.active) tp.durMs else 0L).takeIf { it > 0 }
                    ?: player?.durMs?.takeIf { it > 0 } ?: cinema?.durMs ?: 0L
                val posMs = if (tp.active) tp.posMs else (player?.posMs ?: 0L)
                val selfPlaying = if (tp.active) tp.playing else player?.playing == true
                TheaterTrack(
                    frac = if (durMs > 0) posMs.toFloat() / durMs.toFloat() else 0f,
                    enabled = cinema != null && durMs > 0,
                    onSeek = { f -> hostCmd(WatchCmd.Seek((f * durMs).toLong().coerceAtLeast(0L))) },
                )
                Box(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (player != null) {
                            CinemaSync.formatTime(posMs) + " / " + CinemaSync.formatTime(durMs)
                        } else "还没接到本页播放器",
                        fontSize = 12.5.sp,
                        color = Ink.TextMid,
                        modifier = Modifier.weight(1f),
                    )
                    if (cinema != null) {
                        Box(
                            Modifier.clip(RoundedCornerShape(9.dp))
                                .background(Color(0x14FFFFFF))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                if (selfPlaying) "正在放映" else "已暂停",
                                fontSize = 11.sp,
                                color = if (player?.playing == true) Ink.Live else Ink.TextLow,
                            )
                        }
                    }
                    /* 「复制邀请」胶囊已删（2026-10-01 用户反馈）：进度条下这颗和
                       甲板四格里的「邀请」完全重复 —— 统一入口收进四格。 */
                }
                Box(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckCircle("-10", backdrop) { hostCmd(WatchCmd.Step(-10_000)) }
                    Box(Modifier.width(34.dp))
                    DeckCircle(if (selfPlaying) "❚❚" else "▶", backdrop, big = true) {
                        hostCmd(if (selfPlaying) WatchCmd.Pause else WatchCmd.Play)
                    }
                    Box(Modifier.width(34.dp))
                    DeckCircle("+10", backdrop) { hostCmd(WatchCmd.Step(10_000)) }
                }
                Box(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (cinema != null) {
                        DockBtn("收厅", backdrop, Modifier.weight(1f), hot = true) {
                            askCloseRoom = true     // 与动作行同一个二次确认
                        }
                    }
                    if (!screenShared) {
                        DockBtn("分享我的屏幕", backdrop, Modifier.weight(1f)) { onStartShare() }
                    }
                    // 放映模式里也有后退 —— 广告页一键退回，不必先切「浏览」
                    DockBtn("后退", backdrop, Modifier.weight(1f)) { browserBack() }
                }
                Box(Modifier.height(14.dp))
                /* 对方状态条（方案二）：一起看的核心是「两个人」—— 对方在哪一步必须像
                   播放键一样显眼，而不是藏在一行灰字里（面板/提示位此前都没有）。 */
                val ackLine = CinemaSync.describeAck(playback, viewerOnline, timedOut = false)
                val ackColor = when (ackLine.tone) {
                    CinemaSync.AckTone.Live -> Ink.Live
                    CinemaSync.AckTone.Bad -> Ink.Error
                    CinemaSync.AckTone.Warn -> Ink.Warn
                    else -> Ink.TextMid
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ackColor.copy(alpha = 0.10f))
                        .border(1.dp, ackColor.copy(alpha = 0.30f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 11.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(9.dp)
                            .clip(CircleShape)
                            .background(ackColor),
                    )
                    Box(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ackLine.head,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.TextHi,
                        )
                        ackLine.detail?.let {
                            Text(it, fontSize = 11.sp, color = Ink.TextMid, lineHeight = 15.sp)
                        }
                    }
                }
                Box(Modifier.height(12.dp))
                /* 快捷工具（方案二）：放映态补上原本只有浏览态才有的四个入口 ——
                   浮窗（已做）、换片（候选列表）、邀请、画质。 */
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DockBtn(
                        if (float.active || float.prewarm) "收起浮窗" else "浮窗",
                        backdrop,
                        Modifier.weight(1f),
                    ) {
                        /* 放映中点「浮窗」要先退出放映（画面还给网页）再起浮窗 ——
                           否则浮窗刚起就被"放映时收浮窗"的规则立刻杀掉，
                           用户看到的就是点了没反应（2026-10-01 反馈）。 */
                        if (theater) { theater = false; pinVideo(false) }
                        toggleFloat()
                    }
                    DockBtn("换片", backdrop, Modifier.weight(1f)) { askPickList = true }
                    DockBtn("邀请", backdrop, Modifier.weight(1f)) { copyInvite() }
                    /* 全屏（2026-10-01 用户反馈"放映界面不能全屏"）：甲板整卡收起，
                       画面吃满；轻点画面唤回。**横视频（16:9/宽屏）顺带把屏幕转成
                       横屏 —— 真全屏**（用户手机自动旋转不常开）；竖屏视频（9:16）
                       转横屏反而画面变小，保持竖屏吃满。方向在唤回/退出放映时
                       交还系统（restoreOrientation）。 */
                    DockBtn("全屏", backdrop, Modifier.weight(1f)) {
                        deckHidden.value = true
                        if (videoRatio() >= 1f) {
                            (context as? android.app.Activity)?.requestedOrientation =
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        note = "全屏中 · 轻点画面唤出控制"
                    }
                    /* 麦键（2026-10-01 用户计划：放映厅点麦克风就能连麦）。
                       文案直接说**状态**（原来说的是动作"关麦"，开/关样式又一模一样，
                       用户分不清现在到底是开还是关 —— 2026-10-01 实测反馈）。
                       开麦中加绿色强调（与"对方已播起来"的绿点同一套语言）：
                       绿 + 已开麦 = 正在传声；灰 + 已关麦 = 没在传。
                       点击后的动作提示（回声/画面声）由 toggleMic 自带。 */
                    DockBtn(
                        if (micLive) "已开麦" else "已关麦",
                        backdrop,
                        Modifier.weight(1f),
                        hot = micLive,
                    ) { CallSession.toggleMic() }
                }
                Box(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "直连 ${latencyMs?.let { "${it}ms" } ?: "—"}",
                        fontSize = 12.sp,
                        color = Ink.TextLow,
                    )
                    Text(
                        if (videoFps > 0) "$videoFps fps" else "— fps",
                        fontSize = 12.sp,
                        color = Ink.TextLow,
                    )
                    Text(
                        if (videoBps > 0) String.format("%.1f Mbps", videoBps / 1_000_000f) else "码率自动",
                        fontSize = 12.sp,
                        color = Ink.TextLow,
                    )
                }
                if (note.isNotBlank()) {
                    Box(Modifier.height(8.dp))
                    Text(note, fontSize = 11.5.sp, color = Ink.TextMid, maxLines = 2)
                }
                }
            }
            } else {
                /* 这张卡**一直在**：它是厅的控制面（邀请、放映状态、方向盘开关），
                   「收起嗅探」收的只是量具那几行，不是整张卡。
                   原来写成 `if (showPanel || cinema != null)`，于是"默认收着量具 + 还没选片"
                   这两个条件一叠加，整张卡直接消失，屏幕上只剩一块黑 —— 量具默认收起之后
                   第一时间就踩到了（截图实测）。 */
                panel(Modifier)
            }
        }
    }

    /* 顶替询问：浮窗正在播，又点了另一条 —— 换不换由房主定（2026-09-30 定案，
       不静默顶替）。同一个窗换源，不开第二个窗。 */
    if (pickTarget != null) {
        val h = pickTarget!!
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable { pickTarget = null },
            contentAlignment = Alignment.Center,
        ) {
            GlassCardPanel(
                backdrop,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clickable { },
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "换到这个视频？",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextHi,
                    )
                    Box(Modifier.height(6.dp))
                    Text(
                        "浮窗现在播的是「${float.title.ifBlank { "当前这条" }}」",
                        fontSize = 12.5.sp,
                        color = Ink.TextMid,
                        lineHeight = 18.sp,
                    )
                    Text(
                        "${fmtDuration(durations[h.url])} · ${MediaSniffer.shorten(h.url, 40)}",
                        fontSize = 11.5.sp,
                        color = Ink.TextLow,
                        lineHeight = 17.sp,
                    )
                    Box(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidGlassButton(
                            onClick = { pickTarget = null },
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("继续播旧的", fontSize = 14.sp, color = Ink.TextHi) }
                        LiquidGlassButton(
                            onClick = {
                                pickTarget = null
                                if (TheaterPlayer.state.value.active) {
                                    // 自播在放：换到自播（停旧流、新片从头起）
                                    replaceTheater(h)
                                } else {
                                FloatPlayer.replace(
                                    FloatRequest(
                                        url = h.url,
                                        title = MediaSniffer.hostLabel(h.url),
                                        referer = h.referer,
                                        cookie = h.cookie,
                                        userAgent = h.userAgent,
                                    ),
                                )
                                note = "浮窗换片了"
                                }
                            },
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            surfaceColor = Ink.Live.copy(alpha = 0.20f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "换它",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink.Live,
                            )
                        }
                    }
                }
            }
        }
    }

    /* 「换片」候选列表：全列 + 标时长（浮窗上点换片、或主动手挑时弹）。 */
    if (askPickList) {
        val ranked = rankedCandidates(hits, probe?.currentSrc, durations)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable { askPickList = false },
            contentAlignment = Alignment.Center,
        ) {
            GlassCardPanel(
                backdrop,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clickable { },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("换到哪个？", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink.TextHi)
                    Box(Modifier.height(4.dp))
                    Text(
                        "最长的多半是正片，十几秒的基本是广告",
                        fontSize = 11.sp,
                        color = Ink.TextLow,
                    )
                    Box(Modifier.height(10.dp))
                    if (ranked.isEmpty()) {
                        Text(
                            "还没嗅到能播的地址 —— 回到网页把视频点成播放再试",
                            fontSize = 12.5.sp,
                            color = Ink.TextMid,
                            lineHeight = 18.sp,
                        )
                    }
                    ranked.forEach { h ->
                        val dur = durations[h.url]
                        val cur = h.url == probe?.currentSrc
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { askPickList = false; requestPlay(h) }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                fmtDuration(dur),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (cur) Ink.Live else Ink.TextHi,
                            )
                            Box(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    MediaSniffer.shorten(h.url, 44),
                                    fontSize = 11.sp,
                                    color = Ink.TextMid,
                                    maxLines = 1,
                                )
                                Text(
                                    "${h.kind.name.take(4)} · ${MediaDuration.hint(dur)}" +
                                        if (cur) " · 网页正在播" else "",
                                    fontSize = 10.sp,
                                    color = Ink.TextLow,
                                )
                            }
                            Text(
                                if (float.active) "换它" else "播它",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink.Live,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    Box(Modifier.height(8.dp))
                    Text(
                        "关闭",
                        fontSize = 12.5.sp,
                        color = Ink.TextMid,
                        modifier = Modifier
                            .align(Alignment.End)
                            .clickable { askPickList = false }
                            .padding(6.dp),
                    )
                }
            }
        }
    }

    /* 收厅二次确认：动作行与甲板底坞两处入口共用一个。
       文案说清后果（对方会退回等候屏）+ 给退路（你这边还能重新放映）。 */
    if (askCloseRoom) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable { askCloseRoom = false },
            contentAlignment = Alignment.Center,
        ) {
            GlassCardPanel(
                backdrop,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clickable { /* 卡内点击吞掉，别把遮罩点穿 */ },
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "确定收厅？",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextHi,
                    )
                    Box(Modifier.height(6.dp))
                    Text(
                        "对方那边会立刻退回等候屏",
                        fontSize = 12.5.sp,
                        color = Ink.TextMid,
                        lineHeight = 18.sp,
                    )
                    Text(
                        "你这边网页和进度都还在，可以重新放映",
                        fontSize = 11.5.sp,
                        color = Ink.TextLow,
                        lineHeight = 17.sp,
                    )
                    Box(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        LiquidGlassButton(
                            onClick = { askCloseRoom = false },
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("再想想", fontSize = 14.sp, color = Ink.TextHi)
                        }
                        LiquidGlassButton(
                            onClick = {
                                askCloseRoom = false
                                CallSession.setCinemaTrack(null)
                                note = "已收厅，对方那边退回等候屏"
                                theater = false
                                pinVideo(false)
                            },
                            backdrop = backdrop,
                            modifier = Modifier.weight(1f).height(46.dp),
                            shape = RoundedCornerShape(14.dp),
                            surfaceColor = Ink.Warn.copy(alpha = 0.18f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "收厅",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Ink.Warn,
                            )
                        }
                    }
                }
            }
        }
    }

    /* 收藏夹列表：整屏遮罩 + 居中玻璃卡（动作行「收藏夹」打开）。点条目/「打开」进站，
       「删除」移除；点遮罩或「关闭」收起。 */
    if (showFavs) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xA6000000))
                .clickable { showFavs = false },
            contentAlignment = Alignment.Center,
        ) {
            GlassCardPanel(
                backdrop,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clickable { /* 卡内点击吞掉，别把遮罩点穿 */ },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "收藏夹",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.TextHi,
                            modifier = Modifier.weight(1f),
                        )
                        /* 原"关闭"是裸文本，浮在玻璃上没有按钮的样子（2026-10-01 反馈）；
                           换成与「打开 / 删除」同一颗玻璃键。 */
                        GlassTextButton("关闭", onClick = { showFavs = false }, backdrop)
                    }
                    Box(Modifier.height(8.dp))
                    if (favs.isEmpty()) {
                        Text(
                            "还没有收藏。打开想存的网站，点地址栏右边的 ☆ 就行。",
                            fontSize = 12.5.sp,
                            color = Ink.TextMid,
                            lineHeight = 18.sp,
                        )
                    } else {
                        Column(
                            Modifier
                                .heightIn(max = 400.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            favs.forEach { entry ->
                                val u = entry.substringBefore('␟')
                                val t = entry.substringAfter('␟', u)
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                ) {
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .clickable { openFav(u) },
                                    ) {
                                        Text(t, fontSize = 13.5.sp, color = Ink.TextHi, maxLines = 1)
                                        Text(u, fontSize = 11.sp, color = Ink.TextMid, maxLines = 1)
                                    }
                                    GlassTextButton("打开", onClick = { openFav(u) }, backdrop)
                                    Box(Modifier.width(6.dp))
                                    GlassTextButton("删除", onClick = {
                                        favs = favs - entry
                                        persistFavs()
                                    }, backdrop)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (fullScreenView != null) {
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { fullScreenView!! }, modifier = Modifier.fillMaxSize())
            FullscreenHint("按返回键回到放映厅")
        }
    }
}

/**
 * 厅的底部卡。**放映中**和**没选片**是两副样子 ——
 * 房主按下"开始放映"之后，最想知道的是"对方在不在看、放到哪了、我能不能把方向盘收回来"，
 * 而不是那串嗅探日志。嗅探列表退到"展开嗅探"后面（它是量具，不是日常界面）。
 */
@Composable
private fun CinemaPanel(
    modifier: Modifier = Modifier,
    /** 横屏：卡片挤在右边一条 320dp 的栏里，长说明文案必须让位给事实行。 */
    wide: Boolean = false,
    backdrop: LayerBackdrop,
    cinema: CinemaSync.State?,
    player: com.ticketfortwo.app.watch.WatchState?,
    allowControl: Boolean,
    onAllowChange: (Boolean) -> Unit,
    hits: List<MediaSniffer.Hit>,
    note: String,
    showSniffer: Boolean,
    probe: MediaSniffer.PageProbe?,
    eme: CinemaProbe.EmeReport?,
    inviteUrl: String?,
    /** 厅里有没有人。没人的时候不该写"等对方回执"。 */
    viewerOnline: Boolean,
    /** 对方那边这条到底播没播起来；null = 还没回执。 */
    playback: CinemaSync.PlaybackAck?,
    /** 这一场的声音档（"只有视频声"时要说明麦克风为什么是关的）。 */
    voiceLine: String?,
    onCopyInvite: () -> Unit,
    /** 系统分享面板（与复制并列的第二条邀请通道）。 */
    onShare: () -> Unit,
    onPick: (MediaSniffer.Hit) -> Unit,
    /** 候选时长（异步回填；没回来的条目显示"未知"）。 */
    durations: Map<String, Long>,
    /** 网页里当前正在播的那条地址（用于把"正在播"置顶并标出来）。 */
    playingUrl: String?,
    /** 把这一条挂到浮窗里播（自己看；与 onPick 的"放给对方"是两条出路）。 */
    onFloatPick: (MediaSniffer.Hit) -> Unit,
    /** 厅的动作（2026-09-30 定稿挪到这里）：开始放映/请求收厅（含二次确认路由）。 */
    onStartScreening: () -> Unit,
    /** 「分享我的屏幕」入口（v2.1 主路径：厅先开再切投屏）。 */
    onStartShare: () -> Unit,
    /** 浮窗是否在播（决定按钮是「浮窗播」还是「收起浮窗」）。 */
    floatActive: Boolean,
    onToggleFloat: () -> Unit,
    /** 三颗测试用胶囊的目标地址。它们从主操作行挪进「展开嗅探」，见 CinemaScreen 的排布注释。 */
    onTestUrl: (String) -> Unit,
) {
    // 计数也只数"对方真能播的"：把 file:// 算进"1 条可播地址"是骗房主。
    val playable = hits.filter {
        MediaSniffer.playable(it.kind) && it.url.startsWith("http", ignoreCase = true)
    }
    /** 现在到底有没有在分享画面 —— 厅先开那条路是不投屏的，措辞要跟着这个走。 */
    val localVideo by CallSession.localVideo.collectAsState()
    val screenShared = localVideo != null
    /** 对方是不是已经在这条流上本地播起来了 —— 决定"只有视频声"时麦克风该不该关着。 */
    val viewerLocalPlays = playback?.ok == true
    /* 换片之后重新开始等回执。跟着 version 走而不是跟 cinema 走：
       进度每秒都在更新 cinema，那样这个定时器会被无限续期，永远不超时。 */
    var ackWaited by remember { mutableStateOf(false) }
    LaunchedEffect(cinema?.version) {
        ackWaited = false
        if (cinema == null) return@LaunchedEffect
        delay(ACK_WAIT_MS)
        ackWaited = true
    }
    /* "正在放映"后面那句副标题，只能由对方的回执决定。
     *
     * 原来这里写死"对方本地播 · 原生画质"：对方那片黑着，房主这边照样一脸笃定。
     * 措辞放在 CinemaSync.describeAck 里（能被单测打），这里只管配颜色。 */
    val ackLine = CinemaSync.describeAck(playback, viewerOnline, ackWaited)
    val ackColor = when (ackLine.tone) {
        CinemaSync.AckTone.Live -> Ink.Live
        CinemaSync.AckTone.Bad -> Ink.Error
        CinemaSync.AckTone.Warn -> Ink.Warn
        CinemaSync.AckTone.Waiting -> Ink.TextMid
        CinemaSync.AckTone.Neutral -> Ink.TextLow
    }
    GlassPanel(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        /* 这张卡是多行的，不能用 radiusIsland —— 那个 token 是 9999dp 的**胶囊**，
           Compose 会把圆角钳到短边一半，于是卡的两端变成两个半圆，
           第一行标题正好落在半圆里，看着就像"字被玻璃边缘切掉"（截图实测过）。
           胶囊留给单行条（顶栏、控制条），多行卡用卡片圆角。
           折射也关掉：底下压着的是 WebView 的 SurfaceView，玻璃抓不到画面，
           折射环只会把一圈黑色扭着糊到文字上（同 CallScreen / 观众镜像条的结论）。 */
        radius = GlassDimens.radiusCard,
        refract = false,
        surfaceAlpha = 0.78f,
        content = {
            Column(
                Modifier
                    .fillMaxWidth()
                    // 用 heightIn 而不是固定 height：固定高度会把卡片自己的内容切掉
                    // （实测：标题被截在上缘、最后一行 URL 被切一半），
                    // 内容短时又该收起来，不该撑着一块空玻璃。
                    //
                    // 横屏时这张卡拿到的是分栏剩下的那点高度，所以**不管收没收量具都要能滚**，
                    // 否则最后一行被栏底切掉（竖屏沿用原来的规则：只有展开量具才限高）。
                    .then(
                        if (wide) Modifier.verticalScroll(rememberScrollState())
                        else if (showSniffer) {
                            Modifier.heightIn(max = 236.dp).verticalScroll(rememberScrollState())
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                /* 厅的动作行（2026-09-30 定稿）：开始放映 / 分享我的屏幕 / 浮窗播。
                   原来它们跟浏览器动作（后退/收藏夹）混在地址栏下面那一排 —— 两类
                   身份挤一起，而且"收厅"这种破坏性动作跟"收藏夹"平铺。按"谁拥有它"
                   分家：浏览器动作跟地址栏，厅的动作收进这张底部控制卡。
                   允许横滑：窄屏时保证第一颗（主操作）永远看得见。 */
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GlassTextButton(
                        if (cinema == null) "开始放映" else "收厅",
                        onClick = onStartScreening,
                        backdrop,
                    )
                    if (!screenShared) {
                        GlassTextButton("分享我的屏幕", onClick = onStartShare, backdrop)
                    }
                    GlassTextButton(
                        if (floatActive) "收起浮窗" else "浮窗播",
                        onClick = onToggleFloat,
                        backdrop,
                    )
                }
                /* 邀请行放在两个分支**之外**、面板最顶上。原来它写在"还没选片"分支里：
                   按下「开始放映」后整个分支被换掉，复制入口随之消失 —— 中途拉人
                   只能先收厅（用户实测反馈）。参考三家开源项目的共同做法：邀请入口
                   常驻、和播放状态绑在不同的显隐逻辑上。URL 文本也可点（同三家的
                   "chip 点击即复制"双触发点）。 */
                if (!inviteUrl.isNullOrBlank()) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            MediaSniffer.shorten(inviteUrl, 44),
                            fontSize = 10.5.sp,
                            color = Ink.TextMid,
                            maxLines = 1,
                            modifier = Modifier.weight(1f).clickable { onCopyInvite() },
                        )
                        Box(Modifier.width(8.dp))
                        GlassTextButton("复制邀请", onClick = onCopyInvite, backdrop = backdrop)
                        Box(Modifier.width(6.dp))
                        GlassTextButton("分享", onClick = onShare, backdrop = backdrop)
                    }
                }
                if (cinema != null) {
                    // 放映中
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "正在放映",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink.Live,
                        )
                        Text(
                            "  ${ackLine.head}",
                            fontSize = 11.sp,
                            color = ackColor,
                            // 标题行必须锁一行：App 观众那句长话原来在这里换行，
                            // 第二行正好压在"正在放映"下面，两个字叠在一起（截图实测）。
                            maxLines = 1,
                        )
                    }
                    // 长话另起一行；没有长话就不占位（不留一行空白）
                    ackLine.detail?.let {
                        Text(
                            it,
                            fontSize = 10.5.sp,
                            color = ackColor,
                            lineHeight = 14.sp,
                            maxLines = 2,
                        )
                    }
                    Text(
                        cinema.track.title.ifBlank { CinemaSync.sanitize(cinema.track.url) },
                        fontSize = 12.sp,
                        color = Ink.TextHi,
                        maxLines = 1,
                    )
                    Text(
                        "${CinemaSync.formatTime(player?.posMs ?: cinema.posMs)} / " +
                            "${CinemaSync.formatTime(player?.durMs ?: cinema.durMs)} · " +
                            (if (player?.playing == true) "播放中" else "暂停"),
                        fontSize = 11.sp,
                        color = Ink.TextLow,
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LiquidToggle(allowControl, { onAllowChange(it) }, backdrop)
                        Box(Modifier.width(8.dp))
                        Text(
                            if (allowControl) "对方可以控制进度" else "进度只由我这边动",
                            fontSize = 11.sp,
                            color = if (allowControl) Ink.Live else Ink.TextMid,
                        )
                    }
                    /* 声音档在放映中这一屏特别要说：这一档下麦克风可能是**我们替他关的**
                       （对方本地播原声，房主再外放一遍就是回声）。不写出来，房主会以为
                       自己麦克风图标亮着对方就该听见他。 */
                    if (voiceLine != null) {
                        Text(
                            voiceLine + if (viewerLocalPlays) " · 对方自己播原声，你的麦克风已关" else "",
                            fontSize = 10.5.sp,
                            color = if (viewerLocalPlays) Ink.Warn else Ink.TextLow,
                            lineHeight = 14.sp,
                        )
                    }
                } else {
                    // 还没选片
                    Text(
                        if (playable.isEmpty()) "厅里还没选片" else "嗅到 ${playable.size} 条可播地址",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ink.TextHi,
                    )
                    Text(
                        /* 这句话原来写死"对方现在看到的是你的屏幕" —— 可厅先开这条路
                           **根本不投屏**（只起信令 + 语音），观众看到的是一块等候屏。
                           措辞跟着事实走：有没有在分享画面，是问出来的不是假设的。
                           横屏时压成一句：这张卡在分栏里只有几百 dp 高，
                           四行教学文案会把"放映状态"那几行挤出卡外（用户说的"有效信息太少"）。 */
                        if (wide) {
                            if (screenShared) "按「开始放映」他就改成自己播这条流（原生画质）"
                            else "按「开始放映」，他那边本地播这条流；你的屏幕不用分享出去"
                        } else if (screenShared)
                            "对方现在看到的是你的屏幕。按「开始放映」，他就改成自己播这条流 " +
                                "—— 画质原生，也不再压两层控件。要手挑候选就点「展开嗅探」。"
                        else
                            "厅里现在只有语音：对方看到的是一块等候屏。按「开始放映」，" +
                                "他那边就本地播这条流 —— 画质原生，你的屏幕也不用分享出去。" +
                                "要手挑候选就点「展开嗅探」。",
                        fontSize = 11.sp,
                        color = Ink.TextMid,
                        lineHeight = 16.sp,
                    )
                }
                if (showSniffer) {
                    /* 三颗测试胶囊从主操作行搬到这里（见 CinemaScreen 的排布注释）：
                       它们是量具，不该和「开始放映」抢同一行。 */
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        GlassTextButton("换一条流", onClick = { onTestUrl(CINEMA_TEST_HLS_2) }, backdrop)
                        GlassTextButton("本地测试页", onClick = { onTestUrl(CINEMA_TEST_LOCAL) }, backdrop)
                        GlassTextButton("HLS 测试流", onClick = { onTestUrl(CINEMA_TEST_HLS) }, backdrop)
                    }
                    Text(
                        "点一条就放给对方（放映中点另一条 = 换片，不用先收厅）",
                        fontSize = 10.sp,
                        color = Ink.TextLow,
                    )
                    // 下面是 P0 那两条量具读数：平时收着，出问题时要一眼能看到。
                    Text(
                        probe?.let {
                            val sz = if (it.videoWidth > 0) "${it.videoWidth}×${it.videoHeight}" else "未出画面"
                            "页面 <video>：$sz · ${it.durationSec.toInt()}s · " +
                                (if (it.isBlob) "blob:（MSE）" else "直链")
                        } ?: "还没问到页面",
                        fontSize = 10.5.sp,
                        color = if (probe?.isBlob == true) Ink.Warn else Ink.TextLow,
                    )
                    Text(
                        CinemaProbe.describeEme(eme),
                        fontSize = 10.5.sp,
                        color = Ink.TextLow,
                    )
                    /* 候选列表：全列 + 标时长 —— 一页里常有正片/预告/广告好几条，
                       光看地址分不清谁是谁，时长是最直观的分辨依据（2026-09-30 用户要求）。
                       正在播的置顶、其余按时长降序；时长异步回填，没回来前显示"未知"。 */
                    val ranked = rankedCandidates(hits, playingUrl, durations)
                    if (ranked.isNotEmpty()) {
                        Text(
                            "嗅到 ${ranked.size} 条 · 最长的多半是正片，十几秒的基本是广告",
                            fontSize = 10.sp,
                            color = Ink.TextLow,
                        )
                    }
                    ranked.forEach { h ->
                        val dur = durations[h.url]
                        val cur = h.url == playingUrl
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                fmtDuration(dur),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (cur) Ink.Live else Ink.TextHi,
                            )
                            Box(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    MediaSniffer.shorten(h.url, 52),
                                    fontSize = 10.5.sp,
                                    color = Ink.TextMid,
                                    maxLines = 1,
                                )
                                Text(
                                    "${h.kind.name.take(4)} · ${MediaDuration.hint(dur)}" +
                                        if (cur) " · 网页正在播" else "",
                                    fontSize = 9.5.sp,
                                    color = Ink.TextLow,
                                )
                            }
                            // 两条出路：放给对方（放映）/ 自己挂浮窗看
                            Text(
                                "放映",
                                fontSize = 11.sp,
                                color = Ink.TextHi,
                                modifier = Modifier
                                    .clickable { onPick(h) }
                                    .padding(horizontal = 5.dp),
                            )
                            Text(
                                "浮窗",
                                fontSize = 11.sp,
                                color = Ink.Live,
                                modifier = Modifier
                                    .clickable { onFloatPick(h) }
                                    .padding(horizontal = 5.dp),
                            )
                        }
                    }
                }
                Text(note, fontSize = 10.5.sp, color = Ink.TextLow)
            }
        }
    )
}

/**
 * 放映指令最终要落到房主这个 WebView 上，而"怎么往页面里注脚本"只有 watch 那一套（已测）。
 * 这里做一层映射，不再抄第二份 jsFor —— 两处各写一遍"怎么跳 10 秒"，以后一定只改得动一处。
 */
private fun CinemaSync.Cmd.toWatchCmd(): WatchCmd = when (this) {
    CinemaSync.Cmd.Play -> WatchCmd.Play
    CinemaSync.Cmd.Pause -> WatchCmd.Pause
    is CinemaSync.Cmd.Seek -> WatchCmd.Seek(ms)
    is CinemaSync.Cmd.Step -> WatchCmd.Step(deltaMs)
}

private fun Context.copy(label: String, text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    // 即时反馈：面板底部的 note 在横屏窄栏里可能要滚动才看得见，Toast 不挑位置
    // （参考 couple-cinema 复制成功后的 toast 提示）。
    android.widget.Toast.makeText(this, "邀请链接已复制", android.widget.Toast.LENGTH_SHORT).show()
}

/**
 * 网页自己进了全屏（`onShowCustomView`）之后，App 的顶栏、地址栏、放映卡片
 * 全部被盖住 —— 房主看到的是"我的 App 没了"，而出路只有系统返回键。
 * 实测：横屏一转，`watch/test.html` 的 video 就走到这条路上（.dev/cinema-host-land2.png）。
 * 所以浮一条会自己消失的提示，说清"这不是坏了，按返回就回来"；3.5 秒后收起，
 * 不挡画面，也不吃掉落在它下面的点击（没有 pointerInput 的节点不消费事件）。
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FullscreenHint(text: String) {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(3_500)
        shown = false
    }
    if (!shown) return
    Box(
        Modifier
            .align(androidx.compose.ui.Alignment.BottomCenter)
            .padding(bottom = 30.dp)
            .background(androidx.compose.ui.graphics.Color(0xB0000000), RoundedCornerShape(percent = 50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, fontSize = 12.sp, color = Ink.TextHi)
    }
}

/**
 * 进度由谁说了算 —— 「单一指挥官」。
 *
 * 网页里的 `<video>` 与浮窗里的播放器**不能同时说了算**：两套进度会互相拽，
 * 观众端表现为进度来回跳（2026-09-29 修过的"进度循环"同源问题）。
 * 所以任一时刻只有一方是指挥官，广播只读它的读数，另一方必须停。
 */
private enum class Commander { Page, Float, Theater }

/** 毫秒 → "1:52:30" / "1:24" / "未知"。拿不到就老实说未知，不编数字。 */
private fun fmtDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return "未知"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

/**
 * 候选排序：**正在网页里播的置顶**，其余按时长从长到短。
 *
 * 为什么按时长：正片通常最长，广告通常十几秒 —— 用户要靠时长分辨谁是谁，
 * 那列表就该把"最像正片的"排在最上面（2026-09-30 用户要求）。
 * 时长还没回填完的排在已确定的后面（值为 -1），回填后列表自己会重排。
 */
private fun rankedCandidates(
    hits: List<MediaSniffer.Hit>,
    playingUrl: String?,
    durations: Map<String, Long>,
): List<MediaSniffer.Hit> = hits
    .filter { MediaSniffer.playable(it.kind) && it.url.startsWith("http", ignoreCase = true) }
    .sortedWith(
        compareByDescending<MediaSniffer.Hit> { it.url == playingUrl }
            .thenByDescending { durations[it.url] ?: -1L }
            .thenByDescending { MediaSniffer.score(it) },
    )
    .take(8)

/**
 * 厅回来接片用的恢复点（见 CinemaScreen 的 pendingRestore）。
 *
 * [durMs] 是"是不是同一部片"的门：重进接回的那一页可能已经不是离开时那部
 * （广告页、换了列表页），时长对不上就不拿旧位置去拨，免得拨错页面的播放器。
 */
private data class CinemaResume(
    val url: String,
    val posMs: Long,
    val playing: Boolean,
    val durMs: Long,
)

/* ── 放映模式（方案B）的四块小件 ──────────────────────────────── */

/**
 * 顶栏「浏览 | 放映」模式开关 —— 玻璃胶囊 + 滑块。
 *
 * 样式跟**顶栏**走（2026-09-30 用户反馈）：之前套的是设置页分段控件那套深色底，
 * 放在液态玻璃顶栏里像贴了块别的东西。现在整个开关本身就是一块小玻璃
 * （GlassPanel 同款折射/描边），滑块是玻璃上的一粒高亮胶囊 —— 放映态染绿。
 */
@Composable
private fun ModeSeg(theater: Boolean, backdrop: LayerBackdrop, onTheater: () -> Unit, onBrowse: () -> Unit) {
    /* 几何要对准字心：胶囊 128 宽、内衬 2dp → 内区 124，两格各 62（字心在 31 / 93）。
       滑块 58 宽 → x = 2 时中心 31（盖住"浏览"），x = 64 时中心 93（盖住"放映"），
       右缘 122 也不出界 —— 之前算成 66，滑块右缘冒出胶囊 2dp、字也不居中
       （2026-09-30 用户截图实测）。 */
    val knobX by animateDpAsState(if (theater) 64.dp else 2.dp, label = "modeKnob")
    val knobShape = RoundedCornerShape(24.dp)
    GlassPanel(
        backdrop = backdrop,
        radius = 17.dp,
        surfaceAlpha = 0.16f,
        shape = RoundedCornerShape(50),
        modifier = Modifier.height(34.dp).width(128.dp),
    ) {
        Box(
            Modifier.fillMaxSize().padding(2.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .offset(x = knobX)
                    .size(width = 58.dp, height = 28.dp)
                    .clip(knobShape)
                    .background(
                        if (theater) Ink.Live.copy(alpha = 0.34f)
                        else Color.White.copy(alpha = 0.20f),
                    )
                    .border(
                        1.dp,
                        if (theater) Ink.Live.copy(alpha = 0.55f)
                        else Color.White.copy(alpha = 0.30f),
                        knobShape,
                    ),
            )
            Row(Modifier.fillMaxSize()) {
                SegLabel("浏览", theater, Modifier.weight(1f).clickable { onBrowse() })
                SegLabel("放映", !theater, Modifier.weight(1f).clickable { onTheater() })
            }
        }
    }
}

/** 开关的两格标签：选中白加粗，未选中中灰；整格可点（不只字可点）。 */
@Composable
private fun SegLabel(label: String, on: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) Color.White else Ink.TextMid,
        )
    }
}

@Composable
private fun SegCell(label: String, on: Boolean, backdrop: LayerBackdrop, click: () -> Unit) {
    LiquidGlassButton(
        onClick = click,
        backdrop = backdrop,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(11.dp),
        surfaceColor = if (on) Ink.Live.copy(alpha = 0.22f) else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = if (on) Ink.Live else Ink.TextLow,
            modifier = Modifier.padding(horizontal = 9.dp),
        )
    }
}

/**
 * 甲板圆形传输键 —— 液态玻璃（原先是 background 半透平涂，看着只有透明没有玻璃感，
 * 2026-09-29 用户反馈；与仓库其他按钮统一走 LiquidGlassButton 的折射+按压液感）。
 * 大绿那颗是播放/暂停，两侧 ±10。
 */
@Composable
private fun DeckCircle(label: String, backdrop: LayerBackdrop, big: Boolean = false, onClick: () -> Unit) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier
            .height(if (big) 60.dp else 52.dp)
            .width(if (big) 60.dp else 52.dp),
        shape = CircleShape,
        surfaceColor = if (big) Ink.Live else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = if (big) 21.sp else 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (big) Color.White else Ink.TextHi,
        )
    }
}

/** 甲板底坞的一格（收厅 / 分享我的屏幕 / 后退）：液态玻璃；hot = 收厅的绿染。 */
@Composable
private fun DockBtn(
    label: String,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    hot: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(15.dp),
        surfaceColor = if (hot) Ink.Live.copy(alpha = 0.18f) else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = if (hot) FontWeight.SemiBold else FontWeight.Medium,
            color = if (hot) Ink.Live else Ink.TextHi,
        )
    }
}

/** 地址行小键（⟳ 刷新 / ☆★ 收藏）：44dp 触控下限的液态玻璃圆角方键。 */
@Composable
private fun GlassIconBtn(
    icon: String,
    backdrop: LayerBackdrop,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier.height(44.dp).width(44.dp),
        shape = RoundedCornerShape(13.dp),
        surfaceColor = if (accent) Ink.Live.copy(alpha = 0.20f) else null,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            icon,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (accent) Ink.Live else Ink.TextHi,
        )
    }
}

/**
 * 放映模式的进度轨：按住拖动 —— 按下与松手各发一次 Seek（拖动中只画预览，
 * 不刷屏），落点交回 hostCmd → 本页播放器 → 探针广播给观众。
 * 手势 lambda 用 rememberUpdatedState 持有：`drag` 状态每帧都触发重组，
 * 若 key 进 pointerInput 会把手势整个重启掉（拖到一半就断）。
 */
@Composable
private fun TheaterTrack(frac: Float, enabled: Boolean, onSeek: (Float) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val f = drag ?: frac
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectDragGestures(
                    onDragStart = { p ->
                        drag = (p.x / size.width).coerceIn(0f, 1f)
                        seek(drag!!)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        seek(drag ?: frac)
                        drag = null
                    },
                    onDragCancel = { drag = null },
                )
            },
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x29FFFFFF)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .fillMaxHeight()
                    .background(Ink.Live),
            )
        }
        if (enabled) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = maxWidth * f - 7.dp)
                    .height(14.dp)
                    .width(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White),
            )
        }
    }
}

/* ── 引导首页（浏览模式的"新标签页"）与空态屏 ─────────────────────────── */

/**
 * 空厅的"待放"屏：还没打开任何网页时替住 WebView 的位置 —— 深色底 + 一句去哪儿的指引。
 * 以前这里默认加载 file:///android_asset 彩条测试页，正式 App 第一眼是工程测试卡
 * （2026-09-29 用户反馈）；现在空就是空，只有这一块安静的深色屏。
 */
@Composable
private fun EmptyStage(hint: String, modifier: Modifier = Modifier) {
    Box(
        modifier.background(Color(0xFF0B0E12)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            hint,
            fontSize = 12.sp,
            color = Ink.TextLow,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
    }
}

/* ── 方案A：空厅快捷芯片 ──
   引导页（原 CinemaLobby）删掉的依据：它存在的意义只有"递一次地址栏焦点"，
   而这件事现在进厅落地就发生（进厅即聚焦+键盘）。原来那两张操作卡的目的
   （打开网站 / 回收藏）压成一行芯片贴着地址栏 —— 每颗一下到位，不再有中间步。 */

/** 空厅的快捷芯片行：粘贴 / 上次一起看 / 收藏夹。放不下就横滑（动作行同一策略）。 */
@Composable
private fun RoomShortcuts(
    backdrop: LayerBackdrop,
    recentUrl: String?,
    onPaste: () -> Unit,
    onRecent: (String) -> Unit,
    onFavorites: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = GlassDimens.screenH, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ShortcutChip("📋 粘贴", backdrop, onClick = onPaste)
        if (recentUrl != null) {
            ShortcutChip("🕐 上次 · " + shortSite(recentUrl), backdrop) { onRecent(recentUrl) }
        }
        ShortcutChip("⭐ 收藏夹", backdrop, onClick = onFavorites)
    }
}

/** 一颗快捷芯片：LiquidGlassButton（与甲板/动作行同一按压液感，禁裸 clickable+ripple）。
 *  尺寸对齐动作行的 GlassTextButton（约 38dp 高、14sp 加粗）——
 *  之前 34dp/12sp 明显小一号，同一屏里像两代人（2026-09-30 用户反馈）。 */
@Composable
private fun ShortcutChip(
    text: String,
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier.height(38.dp),
        shape = RoundedCornerShape(percent = 50),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ink.TextHi,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
    }
}
