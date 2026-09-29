package com.ticketfortwo.app.cinema

import android.media.MediaMetadataRetriever
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 嗅探候选的**时长**探测。
 *
 * 为什么需要：一个网页里常有正片、预告、片头广告好几个视频，光看地址分不清谁是谁 ——
 * 而**时长是最直观的分辨依据**（广告通常十几秒，正片一个多小时）。雨见也是这么给的。
 *
 * 三种拿法（拿不到就返回 null，调用方显示"未知"，**不编数字**）：
 *  - HLS：播放列表是纯文本，把每段 `#EXTINF` 加起来。主清单再往下取一路变体。
 *  - 单文件（mp4 等）：读元数据（MediaMetadataRetriever 支持带请求头）。
 *  - DASH：多数 MPD 自带 `mediaPresentationDuration`。
 *
 * 请求头必须照抄嗅探时记下的 Referer/Cookie/UA —— 防盗链站点不给就是不给，
 * 那时老实返回 null。
 */
object MediaDuration {

    /** 探测超时：宁可慢一点也别把列表卡住（每条独立、后台并发）。 */
    private const val TIMEOUT_MS = 8_000
    private const val MAX_TEXT = 512 * 1024

    /**
     * 返回毫秒；null = 拿不到。
     * 只做网络/元数据读取，**不解码**，所以每条最多几百毫秒到几秒。
     */
    fun probe(hit: MediaSniffer.Hit): Long? = runCatching {
        when (hit.kind) {
            MediaSniffer.Kind.Master -> hls(hit)
            MediaSniffer.Kind.Progressive, MediaSniffer.Kind.Audio -> metadata(hit)
            MediaSniffer.Kind.Dash -> dash(hit)
            else -> null
        }
    }.onFailure {
        Log.w("Cinema", "DUR fail ${hit.kind}: ${it.message}")
    }.getOrNull()

    /** HLS：主清单 → 取一路变体 → 累加 EXTINF。 */
    private fun hls(hit: MediaSniffer.Hit): Long? {
        val first = fetchText(hit) ?: return null
        var text = first
        // 主清单（多码率）里没有分片时长，要进到某一路变体清单里才有
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = firstVariantUrl(hit.url, text) ?: return null
            text = fetchText(hit.copy(url = variant)) ?: return null
        }
        val sum = EXTINF.findAll(text).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        // HLS 的分片时长经常是整数取整，误差几秒很正常；小于 1 秒就当没拿到
        return if (sum >= 1.0) (sum * 1000).toLong() else null
    }

    private val EXTINF = Regex("""#EXTINF:\s*([0-9]+(?:\.[0-9]+)?)\s*[,]?""")

    private fun firstVariantUrl(baseUrl: String, master: String): String? {
        val lines = master.lineSequence().map { it.trim() }.toList()
        for (i in lines.indices) {
            if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                // 清单里下一行非注释行就是变体地址（可能还有 #EXT-X-MEDIA 音频轨，跳掉）
                val next = lines.getOrNull(i + 1)
                if (!next.isNullOrBlank() && !next.startsWith("#")) {
                    return resolve(baseUrl, next)
                }
            }
        }
        return null
    }

    private fun resolve(base: String, maybeRelative: String): String =
        runCatching { URL(URL(base), maybeRelative).toString() }.getOrDefault(maybeRelative)

    /** 单文件：读元数据（mp4 的 moov 里就带时长，不必整段下载）。 */
    private fun metadata(hit: MediaSniffer.Hit): Long? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(hit.url, headers(hit))
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.takeIf { it > 0 }
        } finally {
            runCatching { r.release() }
        }
    }

    /** DASH：MPD 里通常直接写了总时长（PT1H52M30S 这种 ISO 8601 时段）。 */
    private fun dash(hit: MediaSniffer.Hit): Long? {
        val text = fetchText(hit) ?: return null
        val raw = Regex("""mediaPresentationDuration\s*=\s*"([^"]+)"""").find(text)
            ?.groupValues?.get(1) ?: return null
        return parseIsoDuration(raw)
    }

    /** PT(#H)?(#M)?(#S)? → 毫秒。 */
    private fun parseIsoDuration(v: String): Long? {
        val h = Regex("""([0-9.]+)H""").find(v)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val m = Regex("""([0-9.]+)M""").find(v)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val s = Regex("""([0-9.]+)S""").find(v)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val total = h * 3600 + m * 60 + s
        return if (total >= 1.0) (total * 1000).toLong() else null
    }

    private fun headers(hit: MediaSniffer.Hit): Map<String, String> = buildMap {
        hit.referer?.takeIf { it.isNotBlank() }?.let { put("Referer", it) }
        hit.cookie?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
        hit.userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
    }

    /** 清单/MPD 都是几 KB 文本；限制读取量，别被异常响应拖死。 */
    private fun fetchText(hit: MediaSniffer.Hit): String? {
        val conn = (URL(hit.url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.instanceFollowRedirects = true
            headers(hit).forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.bufferedReader().use { r ->
                val buf = CharArray(MAX_TEXT)
                val n = r.read(buf)
                if (n <= 0) null else String(buf, 0, n)
            }
        } finally {
            conn.disconnect()
        }
    }

    /** 给时长配一句"这像什么"，帮房主分辨。null = 未知，不猜。 */
    fun hint(durMs: Long?): String = when {
        durMs == null -> "未知"
        durMs < 60_000 -> "疑似广告/片段"
        durMs < 15 * 60_000 -> "疑似预告/短片"
        else -> "像正片"
    }
}
