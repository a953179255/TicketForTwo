package com.ticketfortwo.app.cinema

import android.webkit.WebResourceRequest

/**
 * 片源嗅探：从自家 WebView 的请求流里认出"这一页真正在播的那条媒体"。
 *
 * 为什么可行 —— 放映厅 S 档的前提是"拿到观众也能播的地址"，而**不需要**像
 * synctv 那样在服务端为每个站点写解析器：网页要播的东西，最终都得由这个
 * WebView 自己去下载，所以每一条分片/清单请求都会路过 `shouldInterceptRequest`。
 * 即使 `<video>.src` 是 `blob:`（MSE 播放，源码地址在 JS 里），
 * 它取分片的那些网络请求照样是真实 URL。
 *
 * 两条互补的采集路：
 * 1. [SnifferState.observe] —— 被动看请求（覆盖 MSE 分片、清单）；
 * 2. [MediaSniffer.probeJs] —— 主动问页面（`video.currentSrc` + Resource Timing），
 *    好处是能拿到"这一条确实是当前在播的那条"，坏处是 blob: 时拿不到真 URL。
 * 两路合起来判，比任何单路都准。
 */
/** 这条 URL 是从请求流看到的。 */
const val SRC_REQUEST = 1

/** 这条 URL 是问页面（currentSrc / Resource Timing）拿到的。 */
const val SRC_PAGE = 2

object MediaSniffer {

    /** 一条 URL 在"能不能给观众直接播"这件事上的角色。 */
    enum class Kind {
        /** HLS 清单（.m3u8）：观众端要 hls.js。 */
        Master,

        /** DASH 清单（.mpd）：浏览器原生不放，需要 dash.js —— 先标出来但不当候选。 */
        Dash,

        /** 单文件渐进式（.mp4/.webm/...）：观众端 `<video src>` 直接能播，最理想。 */
        Progressive,

        /** 音频（.mp3/.m4a/.aac）：能播，只是多半不是用户想一起看的东西。 */
        Audio,

        /** 字幕轨：不是片源，但值得显示出来（说明这一页确实是播放器）。 */
        Subtitle,

        /** 分片本体：数量巨大，只计数，不当候选。 */
        Segment,

        /** 与媒体无关（图片、脚本、字体、埋点）。 */
        Ignored,
    }

    /** 认媒体用的扩展名。查询串（签名参数）要先去掉再看后缀。 */
    private val RE_MASTER = Regex("""\.m3u8([?#]|$)""", RegexOption.IGNORE_CASE)

    /**
     * 有些 CDN 不把清单写成文件后缀，而是把它当成一个**路径段**：`…/playlist/m3u8?vid=8842`。
     * 只看 `.m3u8` 后缀会把这些漏掉 —— 而漏掉一条主清单，房主看到的就是"没嗅到片源"。
     *
     * 这条只对着**路径**匹配（`classify` 已经把 `?`/`#` 之后截掉了），所以不写查询串那半 ——
     * 那半原来也塞在这里，因为 `path` 里根本不可能出现 `?` 而永远匹配不上（死分支），
     * 同时 `classify` 又内联编译了一份同义正则。现在拆出去单存，只编译一次，见 [RE_MASTER_QUERY]。
     */
    private val RE_MASTER_LOOSE = Regex("""/m3u8([?#/]|$)""", RegexOption.IGNORE_CASE)

    /**
     * 同一类 CDN，但"查询参数本身就是清单"：`…/index?type=m3u8`。
     * 必须在**整条 URL** 上匹配 —— 拿截掉 `?` 的路径比永远匹配不上，拿只剩 `?` 后半截的
     * 查询串比又认不出第一个参数（`?type=m3u8` 前面没有 `&`）。文档里那条例子走的就是这条。
     */
    private val RE_MASTER_QUERY = Regex(
        """[?&](type|fmt|format|test)=m3u8""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_DASH = Regex("""\.mpd([?#]|$)""", RegexOption.IGNORE_CASE)
    private val RE_PROGRESSIVE =
        Regex("""\.(mp4|m4v|webm|mkv|mov|flv|avi|ogv)([?#]|$)""", RegexOption.IGNORE_CASE)
    private val RE_AUDIO = Regex("""\.(mp3|m4a|aac|ogg|opus|wav)([?#]|$)""", RegexOption.IGNORE_CASE)
    private val RE_SUBTITLE = Regex("""\.(vtt|srt|ass)([?#]|$)""", RegexOption.IGNORE_CASE)

    /**
     * 分片：fMP4 的 `.m4s`，以及 HLS 的 `.ts`。
     * `.ts` 要小心 —— TypeScript 源码也叫 `.ts`，所以只认"看起来在分片路径里"的那些：
     * 同目录里出现过 .m3u8 才算。这里用 URL 形态近似判断（`seg`/`chunk`/纯数字/`media-`）。
     */
    private val RE_SEGMENT = Regex(
        """(\.m4s([?#]|$))|(\.ts([?#]|$).*)""",
        RegexOption.IGNORE_CASE,
    )
    private val RE_SEGMENTISH =
        Regex("""(seg|chunk|fragment|media|hls|dash|/playlist|/\d{1,6}\.(ts|m4s))""", RegexOption.IGNORE_CASE)

    /** 明显不是片源的域名/路径特征，先剔掉，免得列表被埋点刷满。 */
    private val RE_NOISE = Regex(
        """(google-analytics|googletagmanager|doubleclick|/ads?[?/.]|sponsor|beacon|sentry|hotjar|""" +
            """cnzz|umeng|/log[/?]|/collect|/report|/stat[?/.]|/gg|gobang|/web\?0)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 带清晰度/入口字样的通常是主清单或高码率变体，优先给房主看（[score] 用）。
     * 原来写在 `score()` 里**每次调用现编一份** —— 而 `score()` 是 `snapshot()` 的
     * 排序键，400 条候选一轮比较要调几千次，等于每刷一次列表编译几千个正则。
     */
    private val RE_QUALITY = Regex(
        """(1080|720|master|index|hd|fhd|uhd|4k)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 后缀只在**路径**上看，不在整条 URL 上看。
     *
     * 实测踩到的：B 站的埋点 `https://data.bilibili.com/log/web?0011…|…mp4…`
     * 把播放信息（含 .mp4 字样）塞在查询串里，整条匹配会把它当成一条"单文件片源"
     * 摆到房主面前 —— 房主一点"就它了"，观众那边必然放不出来。
     *
     * 唯一的例外是 [RE_MASTER_QUERY]：它认的不是**后缀**而是"查询参数就等于清单"
     * 这件事本身（`?type=m3u8`），只能在整条 URL 上比，且压根不看 `.xxx` 长什么样。
     */
    fun classify(url: String): Kind {
        if (url.isBlank()) return Kind.Ignored
        if (RE_NOISE.containsMatchIn(url)) return Kind.Ignored
        val path = url.substringBefore('?').substringBefore('#')
        return when {
            RE_MASTER.containsMatchIn(path) -> Kind.Master
            RE_MASTER_LOOSE.containsMatchIn(path) ||
                RE_MASTER_QUERY.containsMatchIn(url) -> Kind.Master
            RE_DASH.containsMatchIn(path) -> Kind.Dash
            RE_SUBTITLE.containsMatchIn(path) -> Kind.Subtitle
            RE_PROGRESSIVE.containsMatchIn(path) -> Kind.Progressive
            RE_AUDIO.containsMatchIn(path) -> Kind.Audio
            RE_SEGMENT.containsMatchIn(path) && RE_SEGMENTISH.containsMatchIn(url) -> Kind.Segment
            else -> Kind.Ignored
        }
    }

    /** 观众端能不能直接播这一条。DASH 清单与分片都不算。 */
    fun playable(kind: Kind): Boolean =
        kind == Kind.Master || kind == Kind.Progressive || kind == Kind.Audio

    /**
     * 给一条 URL 打"值不值得当候选"的分。用于排序：
     * 单文件（最省事）> HLS 主清单 > 其他。分片永远排在最后。
     */
    fun score(h: Hit): Int {
        var s = when (h.kind) {
            Kind.Progressive -> 60
            Kind.Master -> 50
            Kind.Audio -> 30
            Kind.Dash -> 20
            Kind.Subtitle -> 10
            Kind.Segment -> 0
            Kind.Ignored -> -10
        }
        // 见过很多次的一般是分片或轮询；主清单通常只请求 1~2 次，反而更像"入口"
        if (h.hits in 1..3) s += 8
        // 带清晰度字样的通常是主清单或高码率变体，优先给房主看（见 RE_QUALITY）
        if (RE_QUALITY.containsMatchIn(h.url)) s += 5
        return s
    }

    /** 页面没标题时，至少告诉房主这条来自哪个站点，而不是甩一整串 URL 给他看。 */
    fun hostLabel(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val host = afterScheme.substringBefore("/").removePrefix("www.")
        /* 只报站点名不够用：房主在**同一个站点**里换一条流，观众看到的标题一个字都没变，
           只有时长跳了一下 —— 读不出"他换片了"（实测两条 mux 测试流都是
           "test-streams.mux.dev 的那条"）。把路径最后一段带上做区分。 */
        // 先切出**路径**再取最后一段：`substringAfterLast('/')` 在没有斜杠时会原样返回整串，
        // 于是 "example.com" 会被当成文件名、再被 substringBeforeLast('.') 削成 "example"，
        // 标题就成了 "example.com · example"（单测抓到的就是这个）。
        val tail = afterScheme.substringAfter('/', "")
            .substringBefore('?').substringBefore('#')
            .substringAfterLast('/').substringBeforeLast('.')
            .take(18)
        return when {
            host.isBlank() && tail.isBlank() -> "对方选的那条"
            tail.isBlank() || tail == host -> "$host 的那条"
            host.isBlank() -> tail
            else -> "$host · $tail"
        }
    }

    /** 去掉 URL 里的签名串再展示，否则一屏全是看不完的 token。 */
    fun shorten(url: String, max: Int = 96): String {
        val cut = url.substringBefore('#')
        val shown = if (cut.length > max) {
            cut.take(max - 3) + "…"
        } else {
            cut
        }
        return shown
    }

    data class Hit(
        val url: String,
        val kind: Kind,
        val referer: String?,
        val userAgent: String?,
        val firstSeenMs: Long,
        var hits: Int = 1,
        /** 来自哪一路：请求流 / 页面探测 / 两边都有。两边都有时可信度最高。 */
        var sources: Int = SRC_REQUEST,
    )

    /** 一次页面探测的结果里，一条 `<video>` 或一条资源计时项。 */
    data class PageProbe(
        val currentSrc: String,
        val isBlob: Boolean,
        val durationSec: Double,
        val videoWidth: Int,
        val videoHeight: Int,
        val readyState: Int,
        val resources: List<String>,
    )

    /**
     * 问页面："你现在到底在播什么"。
     *
     * 只用 `evaluateJavascript` 拿返回值，**不挂 addJavascriptInterface** ——
     * 后者会把 Kotlin 对象暴露到 window 上，页面里任意脚本都能调，
     * 是 WebView 出过最多 CVE 的那类接口（房主打开的可是陌生网站）。
     *
     * Resource Timing 里挑媒体 URL：`video.src` 是 `blob:` 时，真 URL 就藏在这里。
     */
    fun probeJs(): String = """
        (function(){
          var out = {page: location.href, videos: [], resources: [], mse: false, eme: 'pending'};
          try {
            var vs = Array.prototype.slice.call(document.querySelectorAll('video'));
            vs.sort(function(a,b){ return (b.clientWidth*b.clientHeight)-(a.clientWidth*a.clientHeight); });
            out.videos = vs.slice(0,4).map(function(v){
              var s = v.currentSrc || v.src || '';
              return {src: s, blob: s.indexOf('blob:')===0,
                      dur: isFinite(v.duration)?v.duration:0,
                      w: v.videoWidth||0, h: v.videoHeight||0, rs: v.readyState||0};
            });
            var re = /\.(m3u8|mpd|mp4|m4v|webm|mkv|mov|flv|m4s|ts|mp3|m4a|aac)([?#]|$)/i;
            var seen = {};
            out.resources = performance.getEntriesByType('resource')
              .filter(function(e){ return re.test(e.name); })
              .slice(-160)
              .map(function(e){ if(seen[e.name]) return null; seen[e.name]=1; return e.name; })
              .filter(function(x){ return !!x; });
            out.mse = out.videos.some(function(v){ return v.blob; });
          } catch (e) { out.err = String(e); }
          return JSON.stringify(out);
        })()
    """.trimIndent()

    /**
     * EME（加密媒体）探测。**这条是拿来证伪我自己的印象的**：
     * MDN/BCD 的兼容表写 WebView Android 自 v43 支持 `requestMediaKeySystemAccess`，
     * 而我一直以为 Android WebView 不放 DRM 内容。两者冲突，只能实测。
     *
     * 不用 `evaluateJavascript` 等 Promise（WebView 是否 await Promise 取决于版本，
     * 不能赌），改成"先发起、把结果写到 window 上、再轮询读" —— 两跳都是同步取值。
     */
    fun emeStartJs(): String = """
        (function(){
          window.__eme = {state:'running', api:!!(navigator.requestMediaKeySystemAccess), keys:!!window.MediaKeys};
          if(!navigator.requestMediaKeySystemAccess){ window.__eme.state='no-api'; return 'no-api'; }
          var cfg = [{
            initDataTypes:['cenc'],
            videoCapabilities:[{contentType:'video/mp4;codecs="avc1.42E01E"'}],
            audioCapabilities:[{contentType:'audio/mp4;codecs="mp4a.40.2"'}]
          }];
          navigator.requestMediaKeySystemAccess('com.widevine.alpha', cfg)
            .then(function(a){
              window.__eme.state='ok';
              window.__eme.name=a.keySystem||'?';
              try{ var mk=a.createMediaKeys(); window.__eme.createKeys = mk? 'yes':'null'; }
              catch(e){ window.__eme.createKeys = 'throw:'+e.name; }
            })
            .catch(function(e){ window.__eme.state='fail'; window.__eme.err = (e && (e.name+':'+e.message)) || 'unknown'; });
          return 'started';
        })()
    """.trimIndent()

    fun emeReadJs(): String =
        """(function(){ try { return JSON.stringify(window.__eme||{state:'missing'}); } catch(e){ return '{"state":"err"}'; } })()"""
}

/**
 * 一个 WebView 实例对应一个 [SnifferState]。
 *
 * 线程：`shouldInterceptRequest` 跑在 WebView 的后台网络线程上，
 * 而 UI 在 Compose 里读快照 —— 所以这里不直接暴露可变集合，
 * 只给 [snapshot]（拷贝）和 [observe]（加锁改内部表）。
 */
class SnifferState(private val nowMs: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val table = LinkedHashMap<String, MediaSniffer.Hit>()

    /** 被动看请求。返回 true 表示这是一条新记进来的、值得房主看一眼的媒体。 */
    fun observe(request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        val kind = MediaSniffer.classify(url)
        if (kind == MediaSniffer.Kind.Ignored) return false
        val headers = request.requestHeaders
        val isNew: Boolean
        synchronized(lock) {
            val old = table[url]
            if (old == null) {
                table[url] = MediaSniffer.Hit(
                    url = url,
                    kind = kind,
                    referer = headers?.get("Referer") ?: headers?.get("referer"),
                    userAgent = headers?.get("User-Agent") ?: headers?.get("user-agent"),
                    firstSeenMs = nowMs(),
                    sources = SRC_REQUEST,
                )
                isNew = true
            } else {
                old.hits++
                isNew = false
            }
            // 分片很容易刷满几百条，封顶保命：优先留可播的，其次留最新的
            if (table.size > MAX_HITS) {
                val drop = table.entries
                    .filter { !MediaSniffer.playable(it.value.kind) }
                    .sortedBy { it.value.firstSeenMs }
                    .take(table.size - MAX_HITS)
                drop.forEach { table.remove(it.key) }
            }
        }
        return isNew
    }

    /**
     * 把页面探测到的资源补进表里。
     *
     * 为什么需要这一路：有些站点的清单是 JS 用 `fetch` 拉的，`shouldInterceptRequest`
     * 在某些 WebView 版本上**不会**为 fetch/XHR 回调（它主要覆盖主框架与子资源加载），
     * 但 Resource Timing 里一定有。两路并起来才不会漏。
     */
    fun observePageProbe(probe: MediaSniffer.PageProbe) {
        synchronized(lock) {
            if (probe.currentSrc.isNotBlank() && !probe.isBlob) {
                merge(probe.currentSrc, SRC_PAGE)
            }
            probe.resources.forEach { merge(it, SRC_PAGE) }
        }
    }

    private fun merge(url: String, src: Int) {
        val kind = MediaSniffer.classify(url)
        // 页面报上来的资源后缀都不认识的话不留：埋点会把列表淹掉
        if (kind == MediaSniffer.Kind.Ignored) return
        val old = table[url]
        if (old == null) {
            table[url] = MediaSniffer.Hit(
                url = url, kind = kind, referer = null, userAgent = null,
                firstSeenMs = nowMs(), sources = src,
            )
        } else {
            old.sources = old.sources or src
        }
    }

    /** 给 UI 看的快照：按"值不值得当片源"排序。 */
    fun snapshot(): List<MediaSniffer.Hit> = synchronized(lock) {
        table.values.sortedByDescending { MediaSniffer.score(it) }
    }

    fun clear() = synchronized(lock) { table.clear() }

    companion object {
        private const val MAX_HITS = 400
    }
}
