package com.ticketfortwo.app.ui.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.ticketfortwo.app.cinema.CinemaDebug
import com.ticketfortwo.app.cinema.CinemaProbe
import com.ticketfortwo.app.cinema.CinemaSync
import com.ticketfortwo.app.cinema.MediaSniffer
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

/** 试嗅探用的公开 HLS 测试流（Mux 官方测试台，无需登录、无 DRM）。 */
const val CINEMA_TEST_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

/** 试嗅探用的普通单文件页（本地资产，不依赖网络）。 */
const val CINEMA_TEST_LOCAL = WATCH_TEST_URL

/** 第二条内置测试流：URL 不同，专门用来测"放映中途换片"。 */
const val CINEMA_TEST_HLS_2 = "https://test-streams.mux.dev/pts_shift/master.m3u8"

/**
 * 放映/浏览模式开关（方案B）—— **进程级**：授权指引/同看/设置这些覆盖层会把本屏
 * 整屏卸载，回来时若按 `cinema != null` 重算，人会从浏览被拽进放映
 * （2026-09-29 审查：授权回来顶栏已变放映态短文案）。收厅置 false；进场时若厅里
 * 还没有片也兜底复位（见屏内 LaunchedEffect）。
 */
private val theaterMode = androidx.compose.runtime.mutableStateOf(false)

/**
 * 放映模式（方案B）把页面里最大的 `<video>` 钉满 WebView 窗口 —— 页面自己怎么排
 * 都不再影响取景（b.html 那种"标题在上视频在下"的页面，钉完就是纯画面）。
 * 原样式存进 dataset，退出放映模式时原样还回去；dataset 同时是"已钉过"的标记，
 * 探针每 2 秒重复执行也只钉一次（换页后新文档没有标记，会自动重新钉上）。
 */
private const val PIN_VIDEO_JS =
    """(function(){
      function pinNow(){
        var vs=[].slice.call(document.querySelectorAll('video'));
        if(!vs.length) return 'none';
        var v=vs.sort(function(a,b){var A=a.getBoundingClientRect(),B=b.getBoundingClientRect();
          return B.width*B.height-A.width*A.height;})[0];
        if(v.dataset.t2saved!==undefined){
          var rr=v.getBoundingClientRect(), vw2=window.innerWidth, vh2=window.innerHeight;
          // 盒子尺寸跟视口对得上才算钉住；对不上（布局换过、视口变了）就重钉一次
          if(rr.height>0 && Math.abs(rr.height-vh2)<4 && Math.abs(rr.width-vw2)<4)
            return 'ok|vp='+vw2+'x'+vh2+'|rs='+(window.__t2pinResizeCount||0);
        }
        if(v.dataset.t2saved===undefined) v.dataset.t2saved=v.style.cssText;
        var vw=window.innerWidth||372, vh=window.innerHeight||0;
        v.style.setProperty('position','fixed','important');
        v.style.setProperty('left','0px','important');
        v.style.setProperty('top','0px','important');
        v.style.setProperty('width',vw+'px','important');
        v.style.setProperty('height',(vh>0?vh:300)+'px','important');
        v.style.setProperty('display','block','important');
        v.style.setProperty('object-fit','contain','important');
        v.style.setProperty('background','#000','important');
        v.style.setProperty('z-index','2147483647','important');
        var r=v.getBoundingClientRect();
        return 'fix|vp='+vw+'x'+vh+'|rect='+[Math.round(r.top),Math.round(r.width),Math.round(r.height)].join(',');
      }
      window.__t2pin=pinNow;
      /* 视口一变**当场**重钉：点「开始放映」时布局从浏览态切到300dp的画面框，
         第一次钉用的还是旧视口高度 —— contain 在旧高度里居中，顶部一条大黑边、
         画面偏下，要等2秒后的探针自检才恢复（用户实测"刚开始错位一两秒"）。
         resize 是布局切换的同帧信号；只对"已钉过"的元素重钉（dataset 标记还在），
         浏览模式与取消钉定后都不受它打扰。 */
      if(!window.__t2pinResize){
        window.__t2pinResize=1;
        window.addEventListener('resize', function(){
          var all=document.querySelectorAll('video');
          for(var i=0;i<all.length;i++){
            if(all[i].dataset && all[i].dataset.t2saved!==undefined){
              window.__t2pinResizeCount=(window.__t2pinResizeCount||0)+1;
              pinNow(); return;
            }
          }
        });
      }
      return pinNow();
    })()"""

private const val UNPIN_VIDEO_JS =
    """(function(){
      [].slice.call(document.querySelectorAll('video')).forEach(function(v){
        if(v.dataset.t2saved===undefined) return;
        v.style.cssText=v.dataset.t2saved; delete v.dataset.t2saved;});
      return 'ok';})()"""

/**
 * 放映厅里**最后加载的那一页**（进程级，CinemaScreen 的加载 effect 维护）。
 *
 * WebView 离屏即毁、重进是全新实例 —— openCinema 靠它把地址接回"离开时看的那一页"：
 * 影站的播放器最懂怎么放它自己的片（含站点的记忆播放），比接回嗅探到的裸流地址
 * （m3u8 直开在某些站点会 CORS/UA 拒播）稳得多。会话结束时不必清：门禁在
 * 恢复逻辑里（"本页 == 进厅时记录的那一页" + 放映状态还挂着），陈旧值伤不到人。
 *
 * 记录点有两处：加载 effect（程序化加载的那一下）和 WebView 的 onPageStarted
 * （**所有**导航，含站内点链接、广告重定向）。只记前者会漏掉用户点着链接看的页 ——
 * 重进厅接回旧地址，页面重载、两端进度清零（2026-09-29 用户实测）。
 */
var cinemaLastPageUrl: String? = null

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
@SuppressLint("SetJavaScriptEnabled")
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
    var pageUrl by remember { mutableStateOf(initialUrl?.takeIf { it.isNotBlank() } ?: "") }
    var inputUrl by remember { mutableStateOf(pageUrl) }
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
    /* 自家访问记录：WebView 原生历史会被 location.replace() 型的广告跳转吃掉 ——
       落在广告页上时 canGoBack()=false，「后退」按钮灰死、系统返回直接离厅，
       用户只剩"重输链接"一条路（2026-09-29 用户反馈）。这里记 onPageStarted
       见过的每一页，原生历史到头时按访问顺序退回上一页。 */
    var navStack by remember { mutableStateOf(listOf<String>()) }
    /* 正在退回哪一页。加载≠落地：页面一到手可能立刻又把自己 replace 成广告
       （meta refresh / JS 跳转）—— 落点不是目标就从记录里再退一步（最多 4 次），
       别让用户对着广告链一手一手往回爬。 */
    var backTarget by remember { mutableStateOf<String?>(null) }
    var backBounces by remember { mutableStateOf(0) }
    /* 退回目标是否已经落到过眼里 —— 落地之后再出现的导航是用户自己点的（认它），
       没落地就跳走才是"被弹走"（继续连退）。见 onPageStarted 的 when。 */
    var backSawTarget by remember { mutableStateOf(false) }
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
    /* 退回落地时刻 vs 网页内最后一次触摸（longArray 持有，不触发重组）。
       页面自己落地即跳（跳板 replace）没有触摸；用户点链接一定先摸过屏 ——
       两者靠这个时间戳区分，谁该被"连退"吃掉一目了然（审查 A3-1/A3-4 的冲突面）。 */
    val backSawAt = remember { longArrayOf(0L) }
    val webTouchAt = remember { longArrayOf(0L) }
    /* 放映模式（方案B，2026-09-29 用户选定）：画面钉顶 + 自家控制甲板；
       false = 原来的浏览布局（地址行/动作行/底卡）。进厅时厅里已有片就直接落在
       放映模式，开始放映时打开、收厅时退出；顶栏「放映|浏览」随时切。 */
    var theater by theaterMode
    LaunchedEffect(Unit) {
        // 新的一场还没片：别把上一场的放映模式带进来
        if (CallSession.cinema.value == null) theaterMode.value = false
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

    val webView = remember(webGen) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            // 只记不消费：网页内的触摸时间戳，供退回窗口判"这跳是用户点的还是页面自己跳的"
            setOnTouchListener { _, e ->
                if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    webTouchAt[0] = android.os.SystemClock.uptimeMillis()
                }
                false
            }
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // WebView 自带白底：页面真正画出第一帧之前整块是白的，深色厅里开新页
            // 就闪一记白光。改透明，让底下深色容器透上来（页面自己的底色照常生效）。
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // 不少站点看到 UA 里的 " wv" 就拒绝服务
            runCatching { settings.userAgentString = settings.userAgentString.replace(" wv", "") }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = false

                /* 站内跳转/广告跳转也算导航，这里统一复位：
                   ① 地址栏跟着走（只动 inputUrl —— pageUrl 是加载 effect 的 key，
                      改它会触发"换页 → 再加载"的死循环）；
                   ② 后退按钮可用性；
                   ③ 嗅探表清掉 —— 原来只在 pageUrl 变化时清，站内跳转永远不清，
                      上一页的候选和新页混在一起（REVIEW-2026-09-27 P2，随本次一并修）；
                   ④ 上一页的探针读数（player/eme/probe）不留着冒充新页的；
                   ⑤ **回厅接片的"离开页"也跟着走** —— 它原来只记加载 effect 那次
                      程序化加载，用户点着链接看到哪就停在哪（进站页、列表页…），
                      重进厅接回的是旧地址：页面重载、两端进度清零
                      （2026-09-29 用户实测的翻车路径）。只改 inputUrl 不够 ——
                      inputUrl 会被下一次手输覆盖，这里才是"人此刻在哪一页"的权威。 */
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    canGoBack = view?.canGoBack() == true
                    if (!url.isNullOrBlank() && url != "about:blank") {
                        inputUrl = url
                        cinemaLastPageUrl = url
                        Log.i("Cinema", "NAV -> $url")
                        val bt = backTarget
                        when {
                            bt == null -> {
                                // 回到访问过的页（原生后退会走到）：把栈**截到**那一页。
                                // 只增不减的话，退过的页在栈里复活，下一步按后退反而
                                // 跳到刚离开的页 —— 后退变"前进"（审查 A3-1）。
                                val idx = navStack.indexOfLast { it == url }
                                navStack = if (idx >= 0) navStack.take(idx + 1) else navStack + url
                            }
                            url == bt -> {
                                // 落到退回目标：记下落地时刻，等它安顿（settle 定时器结案）
                                backSawTarget = true
                                backSawAt[0] = android.os.SystemClock.uptimeMillis()
                            }
                            backSawTarget && webTouchAt[0] > backSawAt[0] -> {
                                // 目标落地**之后**又有网页内触摸 → 这一跳是用户点的链接
                                // —— 认导航、退出退回窗口（审查 A3-4：退回窗口内点链接
                                // 不能被 bounce 吃掉）。没有触摸的落地即跳（跳板页
                                // replace 成广告）走下面的 bounce 继续退（审查 A3-1）。
                                backTarget = null
                                backBounces = 0
                                backSawTarget = false
                                if (navStack.lastOrNull() != url) navStack = navStack + url
                            }
                            backBounces < 4 && navStack.size >= 2 -> {
                                // 还没落到目标就被弹走（服务端重定向/落地前的 JS 跳转）：
                                // 从记录里再退一步，最多 4 次
                                backBounces += 1
                                backSawTarget = false
                                val next = navStack[navStack.size - 2]
                                navStack = navStack.dropLast(1)
                                backTarget = next
                                Log.i("Cinema", "后退被弹走，继续退到 $next（第 $backBounces 次）")
                                view?.loadUrl(next)
                            }
                            else -> {
                                // 退无可退（目标已在记录底部）：认了，把当前页记回去 ——
                                // 不记的话"广告页"不在记录里，用户会以为后退坏了。
                                backTarget = null
                                backBounces = 0
                                backSawTarget = false
                                if (navStack.lastOrNull() != url) navStack = navStack + url
                            }
                        }
                    }
                    sniffer.clear()
                    hits = emptyList()
                    probe = null
                    player = null
                    eme = null
                }

                /**
                 * 渲染进程崩了：返回 true 系统才不杀整个 App 进程；回主线程整只重建
                 * （webGen 变化 → 新 WebView + keyed effect 重新加载当前页）。
                 *
                 * "当前页"必须是 [cinemaLastPageUrl]（onPageStarted 记的实时地址），
                 * **不能是 pageUrl** —— pageUrl 只在地址栏「打开」时更新，站内点链接
                 * 不写它。实测放映+投屏的压力下渲染进程被系统杀掉，拿 pageUrl 重载
                 * 把人从 b.html 弹回 a.html，回厅接片也跟着接错页（2026-09-29 E2E）。
                 * 恢复点也要重立：崩溃重启的页面从 0 播，不重立就照常广播 0，
                 * 观众时间轴会被拽回去 —— 那正是用户报的"进度被重置"。
                 */
                override fun onRenderProcessGone(
                    view: WebView?,
                    rendererProcess: RenderProcessGoneDetail?,
                ): Boolean {
                    view?.post {
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
                    return true
                }

                /**
                 * 只看不拦：记完就返回 null，让 WebView 照常去网络取。
                 *
                 * 这里刻意不做代理 —— 一旦返回自己的 WebResourceResponse，
                 * 这一页的加载就全押在我们的转发上（Range、压缩、重定向都得自己实现），
                 * 那是 A 档中继该做的事，不该在量具阶段顺手做掉。
                 */
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val r = request ?: return null
                    val isNew = sniffer.observe(r)
                    if (isNew) {
                        val snap = sniffer.snapshot().firstOrNull { it.url == r.url.toString() }
                        if (snap != null && snap.kind != MediaSniffer.Kind.Segment) {
                            Log.i("Cinema", "SNIFF kind=${snap.kind} url=${snap.url}")
                        }
                        /* 嗅到新候选就立刻刷界面。
                         *
                         * 原来 `hits` 只在每 2 秒那次页面探针里更新，而**探针不是每次都成功**：
                         * 直接在 WebView 里打开一条 .m3u8 时用的是 Chromium 自带播放器，
                         * 影子 DOM 里的 `<video>` 不一定问得到 → 探针返回空 → 列表不刷新。
                         * 结果就是"日志明明嗅到了、屏幕上却写着厅里还没选片"，
                         * 命中率测量脚本因此整轮报"没递出"（实测就是这么翻的）。
                         * 回调在 WebView 的工作线程上，所以得 post 回主线程再改状态。 */
                        view?.post { hits = sniffer.snapshot() }
                    }
                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    fullScreenView = view
                    fullScreenCallback = callback
                }

                override fun onHideCustomView() {
                    fullScreenView = null
                    fullScreenCallback = null
                }
            }
        }
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
        /* 用自家栈优先（审查 A3-1）：原生历史在 replace 型广告跳转里会被吃掉中间页，
           goBack 会"隔山打牛"跳过被吃的那一页；而且系统没有暴露"原生的上一页是谁"
           （BackForwardList 无 currentIndex），没法与栈对照 —— 栈≥2 时目的地与
           原生等价、又不受 replace 污染，直接按访问顺序退。
           栈不够（只有一页）时才回原生：SPA 的 pushState 不触发 onPageStarted，
           那些内部历史只有原生认得。 */
        val stackPrev = navStack.getOrNull(navStack.size - 2)
        fun stackStep(): Boolean {
            val prev = stackPrev ?: return false
            navStack = navStack.dropLast(1)
            backTarget = prev
            backBounces = 0
            backSawTarget = false
            webView.loadUrl(prev)
            note = "退回上一页"
            return true
        }
        return when {
            stackPrev != null -> stackStep()
            webView.canGoBack() -> {
                webView.goBack()
                true
            }
            // 退回途中、记录已到底：这 2.5 秒内按返回不离厅（甩出去一次就够难受了）
            backTarget != null -> true
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

    /* 退回目标的安顿窗口：onPageStarted 落到目标 ≠ 站点安顿了 —— 页面可能紧接着
       又把自己 replace 成广告。窗口内出现新导航即视为"被弹走"（见 onPageStarted
       的 bounce 分支），窗口到期没人闹就结案。 */
    LaunchedEffect(backTarget) {
        val t = backTarget ?: return@LaunchedEffect
        delay(2_500)
        if (backTarget == t) {
            backTarget = null
            backBounces = 0
            backSawTarget = false
        }
    }

    LaunchedEffect(pageUrl, webGen, reloadSeq) {
        // 程序化换页/刷新/渲染重建：退回窗口作废 —— 否则这些入口发出的新导航
        // 会被 onPageStarted 的"被弹走"分支吃掉（审查 A3-4：点刷新、开收藏、
        // 崩溃重建都试过会被连退吞掉）。browserBack 的 loadUrl 不改 pageUrl，
        // 不会走到这里，退回窗口照常存活。
        backTarget = null
        backBounces = 0
        backSawTarget = false
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
        webView.loadUrl(pageUrl)
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
            webView.evaluateJavascript(MediaSniffer.probeJs()) { raw ->
                val p = CinemaProbe.parsePageProbe(raw) ?: return@evaluateJavascript
                probe = p
                sniffer.observePageProbe(p)
                hits = sniffer.snapshot()
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
                // 放映模式：探针顺路确保画面还钉着（换页/崩溃重建后 JS 标记没了，
                // 这里每轮都会重新钉一次；PIN_VIDEO_JS 内部按 dataset 去重）
                if (theater) pinVideo(true)
                /* 厅回来接片必须**先于广播**判断：刚加载完的页面停在 0s，先照常
                   publish 会把观众的时间轴拽回 0（分享端画面还在恢复中，看着就是
                   两端一起被重置 —— 2026-09-29 用户实测）。要拨回就这轮不广播，
                   拨完的下一轮探针（2 秒后）再把新位置发出去。 */
                val resume = pendingRestore
                if (resume != null && cinema != null && pageUrl == resume.url &&
                    cinemaLastPageUrl == resume.url && w.durMs > 0
                ) {
                    pendingRestore = null
                    // 同片才拨：重进接回的那一页可能已经不是离开时那部
                    //（广告页、换到别的列表），拿旧时长去比一下，对不上就只当没接片。
                    val sameFilm = resume.durMs <= 0 || (w.durMs - resume.durMs) in -15_000..15_000
                    if (sameFilm && w.posMs < resume.posMs - 3_000) {
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
                CallSession.publishCinemaProgress(w.posMs, w.durMs, w.playing)
            }
        }
    }

    // 观众的放映请求落到这个 WebView 上（它才是播放器）。
    // 注册/摘除成对：这一屏卸载后还挂着回调，指令就会打到已销毁的 WebView 上。
    DisposableEffect(webView) {
        CallSession.onCinemaCommand = { cmd ->
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
            /* 把“此刻在哪一页”交回 MainActivity：这一屏是离屏即毁的，而**授权指引、
               同看、设置这些覆盖层在路由里是独立 Page —— 它们一出现就把本屏整屏
               卸载**，回来时是全新实例、pageUrl 从 cinemaUrl 起步。cinemaUrl 只在
               地址栏「打开」/递链接时更新，站内点链接它看不见 —— 不回写的话，
               厅里点「分享我的屏幕」走完授权回来就被弹回上一个地址
               （E2E 实测：b.html → 授权 → 回来变 a.html，回厅接片也跟着接错）。 */
            onPageLeave(cinemaLastPageUrl ?: pageUrl)
            /* 退屏/换代时把旧 WebView 收掉：只记得创建、从不 destroy，页面会继续联网、
               持着 Activity 直到 GC（平台会打 "WebView.destroy() was never called"），
               autoplay 也没人停。挂在这个 key 上：换代/整屏退出才销毁，横竖屏分支
               切换是同一个实例、不会误杀（别用 AndroidView onRelease，见 REVIEW-2026-09-27）。 */
            runCatching {
                webView.stopLoading()
                webView.webChromeClient = null
                webView.destroy()
            }
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
        /* 这一排原来是不滚动的五颗胶囊：屏宽不够时**最后一颗「开始放映」整个被切到屏外**
           （uiautomator 里根本找不到它，实测点不到 —— 主操作按钮看不见，等于这一屏没有主操作）。
           现在按重要度排序 + 允许横滑：主操作永远在最左边看得见的位置，调试用的排到最后。 */
        Modifier.fillMaxWidth().padding(start = GlassDimens.screenH, bottom = 4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 放映/收厅：房主确认才切 —— 嗅探有认错的时候（广告分片、预告片），
        // 自动切等于把误判直接端给对方。
        GlassTextButton(if (cinema == null) "开始放映" else "收厅", onClick = {
            if (cinema != null) {
                CallSession.setCinemaTrack(null)
                note = "已收厅，对方那边退回等候屏"
                theater = false
                pinVideo(false)
            } else {
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
        }, backdrop)
        // 分享我的屏幕：厅先开（只语音）再切投屏的入口。首页圆钮在厅开着时是
        // "回到放映厅"，ShareKind 进不去 —— 这条 v2.1 主路径的入口落在这里；
        // 授权后留在厅里接着选片。已经在投屏就藏起来（没有第二件事可做）。
        if (!screenShared) {
            GlassTextButton("分享我的屏幕", onClick = onStartShare, backdrop)
        }
        // 浏览器后退：广告/自动跳转后一步步退回上一页（与系统返回键同一行为，
        // 没有这颗按钮时用户只能重开链接 —— 2026-09-28 用户反馈）
        // 可用性 = 原生历史 或 自家记录（广告 replace 吃掉原生历史时按钮不许灰死 ——
        // 那正是"回不去上一页"的直接原因，2026-09-29 用户反馈）
        GlassTextButton("后退", onClick = { browserBack() }, backdrop,
            enabled = canGoBack || navStack.size >= 2)
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
        onTestUrl = { u ->
            inputUrl = u
            if (u == pageUrl) reloadSeq++ else pageUrl = u
        },
    )
    }

    Column(Modifier.fillMaxSize()) {
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
            /* 放映/浏览 模式开关（方案B）：竖屏才有意义 —— 横屏保持原分栏布局。 */
            if (!wide) {
                Box(Modifier.width(6.dp))
                ModeSeg(
                    theater = theater,
                    backdrop = backdrop,
                    onTheater = { theater = true; pinVideo(true) },
                    onBrowse = { theater = false; pinVideo(false) },
                )
            }
            /* 顶栏不再放「复制邀请」：竖屏浏览有底卡邀请行、放映模式有甲板时间行胶囊、
               横屏右栏也有 —— 顶栏那颗和地址下方那颗重复（2026-09-29 用户反馈）。 */
        }

        if (wide) {
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
            Box(
                if (theater) Modifier.height(300.dp) else Modifier.weight(1f)
            ) {
                // 没打开网页就不挂 WebView：空厅是一块深色的"待放"屏，不是白板
                if (pageUrl.isEmpty()) EmptyStage(
                    if (theater) "还没选片 —— 去「浏览」打开一个视频页，或直接分享你的屏幕"
                    else "输入网址就能一起看；上面有「粘贴 / 上次 / 收藏夹」",
                    Modifier.fillMaxSize(),
                )
                else key(webGen) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
            }
            /* 甲板装进玻璃卡：裸文本浮在壁纸上读不清（真机截图实测），
               和厅里其他卡片同一套玻璃语言。 */
            if (theater) { GlassPanel(
                backdrop = backdrop,
                modifier = Modifier
                    .weight(1f)
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
                val durMs = player?.durMs?.takeIf { it > 0 } ?: cinema?.durMs ?: 0L
                val posMs = player?.posMs ?: 0L
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
                                if (player?.playing == true) "正在放映" else "已暂停",
                                fontSize = 11.sp,
                                color = if (player?.playing == true) Ink.Live else Ink.TextLow,
                            )
                        }
                    }
                    if (theater && !inviteUrl.isNullOrBlank()) {
                        Box(Modifier.width(6.dp))
                        Box(
                            Modifier.clip(RoundedCornerShape(9.dp))
                                .background(Ink.Live.copy(alpha = 0.16f))
                                .clickable(onClick = copyInvite)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text("复制邀请", fontSize = 11.sp, color = Ink.Live)
                        }
                    }
                }
                Box(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DeckCircle("-10", backdrop) { hostCmd(WatchCmd.Step(-10_000)) }
                    Box(Modifier.width(34.dp))
                    DeckCircle(if (player?.playing == true) "❚❚" else "▶", backdrop, big = true) {
                        hostCmd(if (player?.playing == true) WatchCmd.Pause else WatchCmd.Play)
                    }
                    Box(Modifier.width(34.dp))
                    DeckCircle("+10", backdrop) { hostCmd(WatchCmd.Step(10_000)) }
                }
                Box(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (cinema != null) {
                        DockBtn("收厅", backdrop, Modifier.weight(1f), hot = true) {
                            CallSession.setCinemaTrack(null)
                            note = "已收厅，对方那边退回等候屏"
                            theater = false
                            pinVideo(false)
                        }
                    }
                    if (!screenShared) {
                        DockBtn("分享我的屏幕", backdrop, Modifier.weight(1f)) { onStartShare() }
                    }
                    // 放映模式里也有后退 —— 广告页一键退回，不必先切「浏览」
                    DockBtn("后退", backdrop, Modifier.weight(1f)) { browserBack() }
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
                        Text(
                            "关闭",
                            fontSize = 13.sp,
                            color = Ink.TextMid,
                            modifier = Modifier
                                .clickable { showFavs = false }
                                .padding(6.dp),
                        )
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
                    playable.forEach { h ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${h.kind.name.take(4)} · ${h.hits}次 · ${if (h.sources and SRC_PAGE != 0) "页面" else "请求"}",
                                fontSize = 10.sp,
                                color = Ink.TextLow,
                            )
                            Box(Modifier.width(8.dp))
                            Text(
                                MediaSniffer.shorten(h.url, 58),
                                fontSize = 10.5.sp,
                                color = Ink.TextMid,
                                maxLines = 2,
                                modifier = Modifier.weight(1f).clickable { onPick(h) },
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

/** 顶栏「放映 | 浏览」分段开关 —— 液态玻璃（AndroidLiquidGlass/LiquidGlassButton）。 */
@Composable
private fun ModeSeg(theater: Boolean, backdrop: LayerBackdrop, onTheater: () -> Unit, onBrowse: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0x14FFFFFF))
            .padding(3.dp),
    ) {
        SegCell("放映", theater, backdrop, onTheater)
        SegCell("浏览", !theater, backdrop, onBrowse)
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

/** 一颗快捷芯片：LiquidGlassButton（与甲板/动作行同一按压液感，禁裸 clickable+ripple）。 */
@Composable
private fun ShortcutChip(
    text: String,
    backdrop: LayerBackdrop,
    onClick: () -> Unit,
) {
    LiquidGlassButton(
        onClick = onClick,
        backdrop = backdrop,
        modifier = Modifier.height(34.dp),
        shape = RoundedCornerShape(percent = 50),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Ink.TextHi)
    }
}

/** 「上次」芯片的短标签：去掉 scheme 和 www，只留站点主体（360kan.com/xxx → 360kan.com）。 */
private fun shortSite(url: String): String =
    url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')

/** 从一段文本里抽出第一条 http(s) 链接（粘贴芯片 / 首页剪贴板浮卡共用）。 */
internal val clipUrlRegex = Regex("""https?://\S+""")
