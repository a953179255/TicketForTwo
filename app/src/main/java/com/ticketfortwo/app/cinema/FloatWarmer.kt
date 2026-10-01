package com.ticketfortwo.app.cinema

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 浮窗播放器**预热器** —— 把"起窗后的加载"提前到放映中悄悄做完。
 *
 * 为什么需要（雨见给的启发，见 docs/references/yjllq-float-window.md §2）：
 * 雨见是"嗅探到就弹浮窗"，**弹窗时机在页面还在播的时候** —— 加载和页面播放并行，
 * 用户真正点开时它早缓冲好了，所以"用着挺不错"。
 * 我们的浮窗是**按 HOME 那一刻才起**，起播的全部网络等待都砸在等待路径上
 * （打点实测首帧 3s，慢网更久）。预热把这段挪到"用户本来就在看页面"的时段：
 * 放映中就把播放器建好、低档挑好、清单和起播段拉好 —— HOME 一按，服务直接上屏。
 *
 * 协议：
 *  - [warm] 幂等；已有待命实例就跳过（换片由上层先 [cancel] 再 warm）。
 *  - [take] 服务取走即**所有权转移**，之后的 release 归服务（teardown）。
 *  - [cancel] 释放；上层在收厅 / 退出放映 / 界面销毁时调。
 *  - 全部状态操作在**主线程**（与 Compose effect、服务 onStartCommand 同线程），
 *    只有挑档网络在 IO。
 */
object FloatWarmer {

    /**
     * 组装一个**配置与浮窗服务完全一致**的播放器并 prepare（playWhenReady=false）。
     * 预热与冷启动共用这一份配置 —— 两处不一致会出现"预热时一个档、接窗时另一个行为"。
     */
    fun buildConfigured(
        context: Context,
        uri: String,
        headers: Map<String, String>,
        userAgent: String?,
        muted: Boolean = false,
        maxVideoW: Int = 1280,
        maxVideoH: Int = 720,
    ): ExoPlayer {
        val ds = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        if (!userAgent.isNullOrBlank()) ds.setUserAgent(userAgent)
        val trackSelector = DefaultTrackSelector(context).apply {
            // 约束保留作兜底：对 HLS 初始选择实测无效（见 §10），主力是 pickVariant 自挑
            parameters = parameters.buildUpon().setMaxVideoSize(maxVideoW, maxVideoH).build()
        }
        val exo = ExoPlayer.Builder(context)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .setLoadControl(
                // media3 1.8：setBufferDurationsMs(min, max, forPlayback, afterRebuffer)。
                // 2500/5000 → 500/500：默认要攒够 2.5 秒缓冲才肯开播，首帧被拖慢的大头之一
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50_000, 50_000, 500, 500)
                    .build(),
            )
            .build()
        /* 起播早期的错误必须有人听 —— 没有监听时错误被吞，播放器停在 STATE_IDLE，
           接窗后也永远起不来（实测卡死的样子：state=1、size=0x0、无任何事件）。 */
        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.w("FloatPlay", "预热期错误: ${error.errorCodeName} / ${error.message}")
            }
        })
        exo.setMediaItem(MediaItem.fromUri(uri))
        /* **静音预播 + 离屏画面**：playWhenReady=false 时只备 0.5 秒缓冲就停
           （实测首帧仍 2.6s）；无 Surface 时时钟不走、缓冲喂不上。
           真播但 volume=0 → 持续把 50 秒缓冲喂满，跟随 seek 永远在覆盖内。
           开销：480p 只有 0.84Mbps ≈ 0.1MB/s，比页面自己那份还小。 */
        exo.volume = if (muted) 0f else 1f
        exo.playWhenReady = true
        exo.prepare()
        return exo
    }

    // ───────────────────── 挑低档变体 ─────────────────────
    // （原先在 FloatPlayerService，预热与冷启动两边都要用，搬到这）

    /**
     * 定一条可播地址。
     *
     * 语义（2026-10-01 收紧）：**第一条就是答案**，本函数只负责"清单 → 挑合适档位"；
     * 选哪条候选是 bestOf/用户的事，这里不许越权 —— 过去会遍历全部候选，
     * 跳过第一条（哪怕是正片 mp4 / 已在播的变体清单）去选后面随便哪条
     * "能解析出档位表的 master"，广告/预览片的清单就这样顶掉了正片
     * （用户实测"默认候选不能播、手动从列表选才行"的帮凶）。
     * 只有首选是 m3u8 且**拉不到**（防盗链/下线/超时）才逐条换下一候选兜底。
     */
    fun pickVariant(
        urls: List<String>,
        headers: Map<String, String>,
        budgetPx: Int = 960,
    ): String {
        val ordered = urls.distinct().filter { it.isNotBlank() }
        val first = ordered.firstOrNull().orEmpty()
        if (first.isEmpty() || !first.contains(".m3u8", ignoreCase = true)) return first
        val text = runCatching { fetchText(first, headers) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (text != null) {
            if (!text.contains("#EXT-X-STREAM-INF")) return first   // 已是变体清单
            pickLowVariant(text, first, budgetPx)?.let {
                Log.i("FloatPlay", "挑档成功 ${first.substringAfterLast('/')} -> ${it.substringAfterLast('/')}")
                return it
            }
            Log.i("FloatPlay", "master 无档位表，用原地址 ${first.substringAfterLast('/')}")
            return first
        }
        // 首选清单拉不到：逐条试其余候选（仅此时允许换流）
        for (u in ordered.drop(1)) {
            if (!u.contains(".m3u8", ignoreCase = true)) return u
            val t = runCatching { fetchText(u, headers) }.getOrNull()?.takeIf { it.isNotBlank() } ?: continue
            if (!t.contains("#EXT-X-STREAM-INF")) return u
            pickLowVariant(t, u, budgetPx)?.let {
                Log.i("FloatPlay", "兜底挑档 ${u.substringAfterLast('/')} -> ${it.substringAfterLast('/')}")
                return it
            }
            return u
        }
        Log.i("FloatPlay", "候选全部拉不到，用原地址 ${first.substringAfterLast('/')}")
        return first
    }

    /**
     * 从 master 清单**文本**里挑一个低档变体地址；不是 master / 没解析出档位 → null
     * （调用方用原地址）。纯解析不联网，方便单独测试。
     *
     * 选档规则（窗最大 276dp≈725px）：
     *  1. 宽 ≤budget 里带宽最高的（给放大留余量，实测 mux 这条流会选到 848x480/0.84Mbps）；
     *  2. 没有就退 ≤budget×4/3 里带宽最高的（720p）；
     *  3. 再没有返回 null（播放器用原地址自己选，行为同以前）。
     *
     * 为什么必须自己挑：media3 的 maxVideoSize 约束**实测对 HLS 初始变体选择无效**
     * （约束进选择器日志可见，仍选 1080p —— 13.5MB/片在慢网下要下 8 秒）。
     */
    private fun pickLowVariant(text: String, baseUrl: String, budgetPx: Int): String? {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<Triple<Int, Int, String>>()  // width, bandwidth, url
        for (i in lines.indices) {
            if (!lines[i].startsWith("#EXT-X-STREAM-INF")) continue
            val next = lines.getOrNull(i + 1)
            if (next.isNullOrBlank() || next.startsWith("#")) continue
            val w = Regex("""RESOLUTION=(\d+)x""").find(lines[i])?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val bw = Regex("""BANDWIDTH=(\d+)""").find(lines[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val abs = runCatching { URL(URL(baseUrl), next).toString() }.getOrDefault(next)
            variants += Triple(w, bw, abs)
        }
        if (variants.isEmpty()) return null
        val chosen = variants.filter { it.first <= budgetPx }.maxByOrNull { it.second }
            ?: variants.filter { it.first <= budgetPx * 4 / 3 }.maxByOrNull { it.second }
            ?: return null
        return chosen.third
    }

    /** 简单拉文本（限 64KB，够一份清单）。headers 用于防盗链站。 */
    private fun fetchText(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 6_000
            conn.readTimeout = 6_000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) return ""
            conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(64 * 1024)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
        } finally {
            conn.disconnect()
        }
    }
}
