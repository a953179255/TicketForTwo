package com.ticketfortwo.app.cinema

import org.json.JSONObject

/**
 * 解析 [MediaSniffer.probeJs] / [MediaSniffer.emeReadJs] 回来的 JSON。
 *
 * 为什么单独一个文件、为什么用 org.json 而不是手撸正则：
 * 这两个结构是**我们自己注入的 JS 生成的**，形状可控，用标准解析器最不容易出错。
 * 代价是 JVM 单测里 org.json 是桩（`isReturnDefaultValues=true` 时构造直接返回 null），
 * 所以这里把它包成一层：[parsePageProbeSafe] 在任何异常/桩环境下都返回 null，
 * 单测只测不依赖 org.json 的那部分（[describeEme]、[bestOf]）。
 */
object CinemaProbe {

    fun parsePageProbe(raw: String?): MediaSniffer.PageProbe? = try {
        val o = JSONObject(unquote(raw))
        val videos = o.optJSONArray("videos")
        val first = if (videos != null && videos.length() > 0) videos.optJSONObject(0) else null
        val src = first?.optString("src").orEmpty()
        val res = ArrayList<String>()
        o.optJSONArray("resources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) res.add(s)
            }
        }
        MediaSniffer.PageProbe(
            currentSrc = src,
            isBlob = first?.optBoolean("blob") ?: src.startsWith("blob:"),
            durationSec = first?.optDouble("dur") ?: 0.0,
            videoWidth = first?.optInt("w") ?: 0,
            videoHeight = first?.optInt("h") ?: 0,
            readyState = first?.optInt("rs") ?: 0,
            resources = res,
        )
    } catch (t: Throwable) {
        null
    }

    fun parseEme(raw: String?): EmeReport = try {
        EmeReport.from(unquote(raw))
    } catch (t: Throwable) {
        EmeReport(state = "parse-error", detail = t.javaClass.simpleName)
    }

    /**
     * `evaluateJavascript` 回调给的是 **JS 值的 JSON 字面量** ——
     * 我们的脚本返回的是 `JSON.stringify(...)` 得到的字符串，所以外面还套了一层引号与转义。
     * 不剥这层，`JSONObject("\"{...}\"")` 会直接抛。
     */
    private fun unquote(raw: String?): String {
        var s = raw?.trim().orEmpty()
        if (s == "null" || s.isEmpty()) return "{}"
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\/", "/")
        }
        return s
    }

    /** 一条候选里最该给房主看的那条。 */
    fun bestOf(hits: List<MediaSniffer.Hit>): MediaSniffer.Hit? =
        hits.firstOrNull { MediaSniffer.playable(it.kind) && it.sources and SRC_PAGE != 0 }
            ?: hits.firstOrNull { MediaSniffer.playable(it.kind) }

    data class EmeReport(
        val state: String,
        val api: Boolean = false,
        val keys: Boolean = false,
        val keySystem: String? = null,
        val createKeys: String? = null,
        val detail: String? = null,
    ) {
        companion object {
            fun from(json: String): EmeReport {
                val o = JSONObject(json)
                return EmeReport(
                    state = o.optString("state"),
                    api = o.optBoolean("api"),
                    keys = o.optBoolean("keys"),
                    keySystem = o.optString("name").ifBlank { null },
                    createKeys = o.optString("createKeys").ifBlank { null },
                    detail = o.optString("err").ifBlank { null },
                )
            }
        }
    }

    /**
     * 把 EME 探测结果翻译成人话。
     *
     * 这条是给"WebView 到底能不能放 DRM 内容"这个**我印象与 MDN 冲突**的问题用的，
     * 所以宁可写得啰嗦，也不要让读的人再猜。
     */
    fun describeEme(r: EmeReport?): String = when {
        r == null -> "EME：还没探到结果"
        r.state == "ok" -> "EME：可用（${r.keySystem ?: "?"}，createMediaKeys=${r.createKeys ?: "-"}）" +
            " —— 加密站点的清单能拿到，但密钥绑设备，观众那边照样放不出来"
        r.state == "fail" -> "EME：握手失败（${r.detail ?: "-"}）—— 与我的印象一致，WebView 不放 DRM"
        r.state == "no-api" -> "EME：navigator.requestMediaKeySystemAccess 不存在 —— WebView 确实没带 DRM 栈"
        r.state == "running" -> "EME：探测中…"
        else -> "EME：${r.state}（${r.detail ?: "-"}）"
    }
}
