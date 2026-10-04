package com.ticketfortwo.app.ui.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * # 放映厅的"内置浏览器"层
 *
 * 从 CinemaScreen.kt 拆出来的一层：**WebView 的生灭、设置、clients、访问史（含
 * 广告劫持连退）、画面钉定 JS** 都住在这里；放映厅的 Composable 只实现一个
 * 四方法的回调接口（[CinemaBrowserHost]），把"浏览器发生了什么"翻译成界面状态。
 *
 * 分家的边界画在"**谁拥有浏览器的记忆**"上：
 * - 这里拥有：页面实例、实际加载地址、访问栈、退回目标、连退计数、落地/触摸时刻
 *   —— 全部**进程级**，跟 WebView 实例同寿，跨"离厅再进"存活；
 * - 宿主（CinemaScreen）拥有：地址栏输入、嗅探候选、播放器读数、全屏视图……
 *   这些是"这一次进厅"的组合状态，随屏重建，clients 每次进屏重装来重新接线。
 *
 * 历史背景：WebView 原来跟着组合生灭，重进厅必重载；重载后恢复接力在 HLS 这类
 * 懒加载页面上会掉棒（暂停着拿不到 dur ⇒ 永远等不到拨回时机）⇒ 卡死 0:00、
 * 观众进度循环（2026-09-29 用户实测）。改成进程级持有后这一切不再发生，
 * 详见 [CinemaBrowser] 的文档。
 */

/**
 * 浏览器宿主要实现的回调集 —— WebView 的 clients 需要回写的一小撮宿主状态。
 * 这份文件只认接口，不认 Composable：机器归机器，界面归界面。
 */internal interface CinemaBrowserHost {
    /**
     * 导航开始（onPageStarted，**任何**导航都算：站内点跳、广告重定向、原生后退）。
     * 宿主据此更新地址栏、复位嗅探/读数；[url] 为 null 表示无效导航（空/about:blank）。
     */
    fun onPageStarted(url: String?, backAvailable: Boolean)

    /** 工作线程上嗅到新候选：宿主刷新候选列表（install 侧已 post 回主线程）。 */
    fun onSniffChanged()

    /** 网页全屏开/关（[view] 非空 = 进入全屏；null = 退出）。 */
    fun onFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?)

    /** 渲染进程崩了：宿主重立恢复点、换代（webGen++），本屏换用新一代实例。 */
    fun onRendererGone(view: WebView?)

    /**
     * 资源请求过账（**工作线程**回调）：宿主记账嗅探候选、返回"是否有新候选"。
     * 拦截侧恒返回 null（只看不拦）—— 这里刻意不做代理：一旦返回自己的
     * WebResourceResponse，这一页的加载就全押在我们的转发上（Range、压缩、
     * 重定向都得自己实现），那是 A 档中继该做的事。
     */
    fun interceptRequest(request: WebResourceRequest): Boolean
}

/** 试嗅探用的公开 HLS 测试流（Mux 官方测试台，无需登录、无 DRM）。 */
const val CINEMA_TEST_HLS = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

/** 试嗅探用的本地验证页：一个真正的 <video>，用来确认探针读得到、指令改得动。 */
const val WATCH_TEST_URL = "file:///android_asset/watch/test.html"

/** 试嗅探用的普通单文件页（本地资产，不依赖网络）。 */
const val CINEMA_TEST_LOCAL = WATCH_TEST_URL

/** 第二条内置测试流：URL 不同，专门用来测"放映中途换片"。 */
const val CINEMA_TEST_HLS_2 = "https://test-streams.mux.dev/pts_shift/master.m3u8"

/**
 * 放映厅里**最后加载的那一页**（进程级，加载 effect 与 onPageStarted 共同维护）。
 *
 * openCinema 靠它把地址接回"离开时看的那一页"：影站的播放器最懂怎么放它自己的片
 * （含站点的记忆播放），比接回嗅探到的裸流地址（m3u8 直开在某些站点会 CORS/UA 拒播）
 * 稳得多。会话结束时不必清：门禁在恢复逻辑里（"本页 == 进厅时记录的那一页" +
 * 放映状态还挂着），陈旧值伤不到人。
 *
 * 记录点有两处：加载 effect（程序化加载的那一下）和 WebView 的 onPageStarted
 * （**所有**导航，含站内点链接、广告重定向）。只记前者会漏掉用户点着链接看的页 ——
 * 重进厅接回旧地址，页面重载、两端进度清零（2026-09-29 用户实测）。
 */
var cinemaLastPageUrl: String? = null

/**
 * 进程级 WebView 持有者：放映厅的浏览器**整场只活一只**，离厅/覆盖层卸载都不销毁。
 *
 * 为什么必须持有：这屏是"离屏即毁"的（路由独立 Page、覆盖层整屏卸载），WebView
 * 跟着组合生灭 ⇒ 重进厅必重载。重载本身不致命，致命的是**恢复接力**：页面回到 0，
 * 要靠"站点记忆/恢复点"把进度拨回来 —— 而 HLS 这类懒加载页面**暂停着就没有元数据**
 * （dur=0），恢复点"等 durMs>0 才拨"永远等不到 ⇒ 重进厅卡死 0:00、观众进度跟着
 * 循环（2026-09-29 用户实测，模拟器复现：24 秒 dur=0s 不恢复）。页面不销毁，
 * 这一切都不发生：离厅时电影在后台继续放（观众那边本来就该继续看），回来原地还在。
 *
 * 后退劫持的那组状态（访问栈、退回目标、连退计数、落地标记）也住在这里：它们描述
 * "这只浏览器的访问史"，跟页面实例同寿 —— 用组合态的话重进就丢栈。用 mutableStateOf
 * （进程级组合状态）：onPageStarted/browserBack 在回调里写、按钮可用性在组合里读，都能订阅。
 *
 * Activity 配了 configChanges（orientation|screenSize|…，见 Manifest），进程存活期
 * Activity 不会重建 ⇒ 持有 Activity context 是安全的；仍用 MutableContextWrapper
 * 包一层按需换绑 base，防将来有未覆盖的配置变化换掉 Activity。
 */
internal object CinemaBrowser {
    var webView: WebView? = null

    /** 页面**实际**加载到的地址（loadUrl 与 onPageStarted 都写）—— 供"同页免重载"判断。 */
    var loadedUrl: String? = null

    var navStack by mutableStateOf(listOf<String>())
    var backTarget by mutableStateOf<String?>(null)
    var backBounces by mutableStateOf(0)
    var backSawTarget by mutableStateOf(false)
    val backSawAt = longArrayOf(0L)
    val webTouchAt = longArrayOf(0L)

    /** 取当前实例；没有就用 [context]（包一层可换绑的 wrapper）造一只。
     * 换绑用 setBaseContext（MutableContextWrapper 自带的公开方法）——
     * 基类的 attachBaseContext 是 protected 且只能调一次，二次调用直接抛
     * "Base context already set"（实测重进厅即崩，2026-09-29）。 */
    fun obtain(context: Context): WebView {
        val kept = webView
        if (kept != null) {
            (kept.context as? MutableContextWrapper)?.setBaseContext(context)
            return kept
        }
        return WebView(MutableContextWrapper(context)).also { webView = it }
    }

    /** 装上设置与 clients。宿主回调里只动宿主状态；历史/劫持全在本对象内部运算。
     * **每次进屏都会重装一遍** —— clients 闭包绑的是宿主的组合态，重挂旧闭包会写进死状态。 */
    @SuppressLint("SetJavaScriptEnabled")
    fun install(webView: WebView, host: CinemaBrowserHost) {
        webView.apply {
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
                   ① 地址栏跟着走（宿主回调，只动 inputUrl —— pageUrl 是加载 effect 的 key，
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
                    val canGoBack = view?.canGoBack() == true
                    val valid = recordNavigation(view, url)
                    host.onPageStarted(url.takeIf { valid }, canGoBack)
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
                        retireCrashed(view)
                        host.onRendererGone(view)
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
                    val isNew = host.interceptRequest(r)
                    if (isNew) {
                        /* 嗅到新候选就立刻刷界面。
                         *
                         * 原来 `hits` 只在每 2 秒那次页面探针里更新，而**探针不是每次都成功**：
                         * 直接在 WebView 里打开一条 .m3u8 时用的是 Chromium 自带播放器，
                         * 影子 DOM 里的 `<video>` 不一定问得到 → 探针返回空 → 列表不刷新。
                         * 结果就是"日志明明嗅到了、屏幕上却写着厅里还没选片"，
                         * 命中率测量脚本因此整轮报"没递出"（实测就是这么翻的）。
                         * 回调在 WebView 的工作线程上，所以得 post 回主线程再改状态。 */
                        view?.post { host.onSniffChanged() }
                    }
                    return null
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    host.onFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    host.onFullscreen(null, null)
                }
            }
        }
    }

    /** 会话散场/换代收尸：页面、媒体、访问史一起清。 */
    fun destroy() {
        webView?.let { w ->
            runCatching {
                w.stopLoading()
                w.webChromeClient = null
                (w.parent as? ViewGroup)?.removeView(w)
                w.destroy()
            }
        }
        webView = null
        loadedUrl = null
        navStack = emptyList()
        backTarget = null
        backBounces = 0
        backSawTarget = false
    }

    /** 渲染进程崩了：这只实例不可复用，从持有者摘掉并销毁、清"实际页"记忆。 */
    fun retireCrashed(view: WebView?) {
        if (webView === view) {
            runCatching { view?.destroy() }
            webView = null
            loadedUrl = null
        }
    }

    /** 导航史记账（onPageStarted 驱动）：loadedUrl / 访问栈 / 广告劫持连退。
     * 返回是否为有效页（非空、非 about:blank）—— 宿主据此更新地址栏。 */
    fun recordNavigation(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank() || url == "about:blank") return false
        loadedUrl = url
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
        return true
    }

    /** 程序化换页/刷新/渲染重建：退回窗口作废（审查 A3-4：点刷新、开收藏、
     * 崩溃重建都试过会被连退吞掉）。browserBack 的 loadUrl 不改 pageUrl，
     * 不会走到这里，退回窗口照常存活。 */
    fun resetBackWindow() {
        backTarget = null
        backBounces = 0
        backSawTarget = false
    }

    /** 自家栈退一步（后退优先走这里）：返回要退到的地址；栈不够返回 null（宿主改走原生历史）。
     * 为什么自家栈优先（审查 A3-1）：原生历史在 replace 型广告跳转里会被吃掉中间页，
     * goBack 会"隔山打牛"跳过被吃的那一页；而且系统没有暴露"原生的上一页是谁"
     * （BackForwardList 无 currentIndex），没法与栈对照 —— 栈≥2 时目的地与原生等价、
     * 又不受 replace 污染，直接按访问顺序退。 */
    fun stackStepBack(): String? {
        val prev = navStack.getOrNull(navStack.size - 2) ?: return null
        navStack = navStack.dropLast(1)
        backTarget = prev
        backBounces = 0
        backSawTarget = false
        return prev
    }
}

/**
 * 放映模式（方案B）把页面里最大的 `<video>` 钉满 WebView 窗口 —— 页面自己怎么排
 * 都不再影响取景（b.html 那种"标题在上视频在下"的页面，钉完就是纯画面）。
 * 原样式存进 dataset，退出放映模式时原样还回去；dataset 同时是"已钉过"的标记，
 * 探针每 2 秒重复执行也只钉一次（换页后新文档没有标记，会自动重新钉上）。
 */
internal const val PIN_VIDEO_JS =
    """(function(){
      /* 收集本文件与同源 iframe 里的 <video>（FoFo 这类站播放器藏 iframe：
         主文档查不到 -> pinNow 'none' -> 黑垫层盖住页面、iframe 视频露在下方
         —— 用户实测"上半黑屏、视频偏下"的一种来源。跨域 iframe 访问会抛错，
         catch 跳过（WebView 注入跨不了域，这是能力边界）。 */
      function collect(root, out){
        try{
          var list=root.querySelectorAll('video');
          for(var i=0;i<list.length;i++) out.push(list[i]);
          var fr=root.querySelectorAll('iframe,frame');
          for(var j=0;j<fr.length;j++){
            try{ var d=fr[j].contentDocument; if(d) collect(d,out); }catch(e){}
          }
        }catch(e){}
        return out;
      }
      function pinNow(){
        var vs=collect(document, []);
        if(!vs.length) return 'none';
        var v=vs.sort(function(a,b){var A=a.getBoundingClientRect(),B=b.getBoundingClientRect();
          return B.width*B.height-A.width*A.height;})[0];
        if(v.dataset.t2saved!==undefined){
          var rr=v.getBoundingClientRect(), vw2=window.innerWidth, vh2=window.innerHeight;
          // 盒子尺寸**且位置**都对才算钉住 —— 只查 height/width 会漏掉
          // "尺寸拉满但整体被 transform 祖先推偏"的假 ok（fixed 在 transform
          // 祖先里会相对祖先定位），偏移后每次 2 秒重放都早退、错位永远不自愈
          // （2026-10-02 用户实测"上半黑屏视频偏下持续不恢复"）。
          if(rr.height>0 && Math.abs(rr.height-vh2)<4 && Math.abs(rr.width-vw2)<4 &&
             rr.top<6 && rr.left<6)
            return 'ok|vp='+vw2+'x'+vh2+'|rs='+(window.__t2pinResizeCount||0);
        }
        if(v.dataset.t2saved===undefined) v.dataset.t2saved=v.style.cssText;
        /* **禁用网页播放器原生控制条**（2026-10-01 用户反馈"投屏时呼出一下进度条"）：
           界面重建（投屏连/断）时 WebView 重挂，Chromium 对 video 重新合成，
           原生控制条跟着闪现一下 —— 看起来就像"点击触发了播放器"。放映中控制
           全走甲板，原生条本来就不该有。controls 是 property 不是 style，
           dataset 存不了，单独记一个标记，UNPIN 时还原。 */
        if(v.dataset.t2ctrl===undefined) v.dataset.t2ctrl=v.controls?'1':'0';
        v.controls=false;
        var vw=window.innerWidth||372, vh=window.innerHeight||0;
        /* **不能只靠 object-fit:contain**：Android WebView 的 video 走硬件合成时
           object-fit 会被无视（实测 16:9 片源被拉进 1080x935 的盒子，人全是扁的，
           2026-10-01 用户反馈"视频比例被拉伸"）。改为按视频真实宽高比**手工算
           contain 盒**：宽贴视口、高按比例，超高就反过来贴高；再居中放置。
           视频比例拿不到（metadata 未到）先按 16:9 兜底，探针 2 秒后会带着真实
           比例重算。object-fit:contain 留着不碍事（支持的内核上等价）。
           视频缩小后元素盖不满视口了，页面其它内容（页头/广告横幅）会从四周露出来
           —— 垫一层全屏黑 div 压住整个页面（z 比 video 低 1），放映就是纯画面。 */
        var bg=document.getElementById('__t2pinbg');
        if(!bg){
          bg=document.createElement('div');
          bg.id='__t2pinbg';
          bg.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;'+
            'background:#000;z-index:2147483646;pointer-events:none';
          document.documentElement.appendChild(bg);
        }
        /* **点击拦截层**：放映画面是网页里的 video（只是被钉到顶层），不拦的话
           点画面会穿透进网页 —— 触发网页播放器的控制条、甚至误点广告跳转
           （2026-10-01 用户实测"点击放映画面会触发网页的播放器点击效果"）。
           盖一层全屏透明 div（z 比 video 高）吃掉所有点击：放映中控制全走
           甲板/自播控制条，网页一个点击都不该收到。UNPIN 时一并拆掉。 */
        var trap=document.getElementById('__t2pintrap');
        if(!trap){
          trap=document.createElement('div');
          trap.id='__t2pintrap';
          trap.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;'+
            'background:transparent;z-index:2147483647;pointer-events:auto';
          document.documentElement.appendChild(trap);
        }
        var ratio=(v.videoWidth&&v.videoHeight)?(v.videoWidth/v.videoHeight):(16/9);
        if(!isFinite(ratio)||ratio<=0) ratio=16/9;
        var w=vw, h=vw/ratio;
        if(vh>0&&h>vh){ h=vh; w=vh*ratio; }
        var el=Math.max(0,(vw-w)/2), et=Math.max(0,(vh-h)/2);
        v.style.setProperty('position','fixed','important');
        v.style.setProperty('left',el+'px','important');
        v.style.setProperty('top',et+'px','important');
        v.style.setProperty('width',w+'px','important');
        v.style.setProperty('height',h+'px','important');
        v.style.setProperty('display','block','important');
        v.style.setProperty('object-fit','contain','important');
        v.style.setProperty('background','#000','important');
        v.style.setProperty('z-index','2147483647','important');
        var r=v.getBoundingClientRect();
        /* **钉完复查处点**：fixed 只有在"无 transform 祖先"时才相对视口 ——
           页面里一旦有 transform/filter/contain 容器装着这个 video，
           写进去的 top 会相对祖先生效，结果"尺寸全对、整体偏下"（假 ok 的另一半）。
           检测到偏移就把 video **拔到文档顶层**（脱离 transform 祖先），fixed
           立刻恢复视口语义。拔出只发生在放映钉屏期间，UNPIN 还原样式后页面
           本来就被黑垫盖着/会重新导航，代价可控；不拔则错位永不自愈。 */
        if(Math.abs(r.top-et)>8 || Math.abs(r.left-el)>8){
          try{ document.documentElement.appendChild(v); }catch(e){}
          r=v.getBoundingClientRect();
        }
        return 'fix|vp='+vw+'x'+vh+'|ar='+ratio.toFixed(3)+
          '|box='+Math.round(w)+'x'+Math.round(h)+
          '|rect='+[Math.round(r.top),Math.round(r.width),Math.round(r.height)].join(',');
      }
      window.__t2pin=pinNow;
      /* **video 元素被换掉（换源/动态插入）就立刻钉新的** —— 老 video 的
         t2saved 标记不在新元素上，resize/2 秒重放之外再补一路事件驱动
         （2026-10-02 加固：FoFo 换 source 重建 video 后错位）。 */
      if(!window.__t2mo){
        window.__t2mo=1;
        try{
          new MutationObserver(function(){
            if(!document.getElementById('__t2pinbg')) return;   // 只在钉屏态管
            var all=document.querySelectorAll('video');
            for(var i=0;i<all.length;i++){
              if(all[i].dataset && all[i].dataset.t2saved===undefined){ pinNow(); return; }
            }
          }).observe(document.documentElement, {childList:true, subtree:true});
        }catch(e){}
      }
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

internal const val UNPIN_VIDEO_JS =
    """(function(){
      [].slice.call(document.querySelectorAll('video')).forEach(function(v){
        if(v.dataset.t2saved===undefined) return;
        v.style.cssText=v.dataset.t2saved; delete v.dataset.t2saved;
        if(v.dataset.t2ctrl!==undefined){ v.controls=v.dataset.t2ctrl==='1'; delete v.dataset.t2ctrl; }
        /* **还原 ≠ 可见**（2026-10-02 用户实测"切浏览网页只剩黑、视频不见" +
           模拟器 probe 实锤：video paused=false 在走 t=24.6→26.6，但屏上 y400-1600
           纯黑）：页面原始样式可能本来就是隐藏/零尺寸（HLS 播放库动态控制），
           或还原后元素落在视口外 —— 钉屏期间它是全屏 fixed，一还原就"消失"。
           探测 rect，不可见就强制拉回文档流可见状态 + 滚进视口。 */
        try{
          var r=v.getBoundingClientRect();
          var invisible = !r || r.width<40 || r.height<40 ||
                          r.bottom<0 || r.top>window.innerHeight;
          if(invisible){
            v.style.setProperty('position','static','important');
            v.style.setProperty('width','100%','important');
            v.style.setProperty('height','auto','important');
            v.style.setProperty('max-height','100%','important');
            v.style.setProperty('display','block','important');
            v.style.setProperty('object-fit','contain','important');
            v.style.setProperty('margin','0 auto','important');
          }
          if(v.scrollIntoView) v.scrollIntoView({block:'center'});
        }catch(e){}
      });
      /* 黑底垫层与点击拦截层一并拆掉，页面恢复原样 */
      ['__t2pinbg','__t2pintrap'].forEach(function(id){
        var el=document.getElementById(id);
        if(el&&el.parentNode) el.parentNode.removeChild(el);
      });
      return 'ok';})()"""

/** 从一段文本里抽出第一条 http(s) 链接（粘贴芯片 / 首页剪贴板浮卡共用）。 */
internal val clipUrlRegex = Regex("""https?://\S+""")

/** 「上次」芯片的短标签：去掉 scheme 和 www，只留站点主体（360kan.com/xxx → 360kan.com）。 */
internal fun shortSite(url: String): String =
    url.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .substringBefore('/')
