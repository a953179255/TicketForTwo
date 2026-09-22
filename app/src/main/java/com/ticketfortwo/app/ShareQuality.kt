package com.ticketfortwo.app

import android.content.Context

/**
 * 分享画质设置：**上限，不是保证值**（网络差、发热时会自动再降）。
 *
 * 所有字段在"开始分享"那一刻生效、固定本次会话 —— 一期零服务器没有信令通道，
 * 中途改分辨率或开关画面都需要重新协商（改 m-line / 换采集格式），做不到就不假装。
 * 码率上限理论上可热改（RtpSender.setParameters），但设置页统一"开始时生效"，
 * 避免出现"有的项立即生效、有的项下次生效"的混合语义。
 *
 * 持久化复用首页那份 "t2" SharedPreferences，只是键前缀不同。
 */
data class ShareQuality(
    /** 采集缩放：0.5 → 约 540 宽、0.75 → 约 810、1.0 → 原生机（1080 机型标 1080p）。 */
    val scale: Float = CallSession.DEFAULT_CAPTURE_SCALE,
    val fps: Int = CallSession.DEFAULT_VIDEO_FPS,
    val maxVideoBps: Int = CallSession.DEFAULT_MAX_VIDEO_BPS,
    /** false = 纯语音：不申请投屏、不建视频轨，offer 里没有视频 m-line。 */
    val videoEnabled: Boolean = true,
) {

    /** 分辨率档的用户文案。0.75 是既有默认，沿用效果图/首页一直说的 "720p"。 */
    fun resolutionLabel(): String = when {
        scale <= 0.5f -> "540p"
        scale >= 1f -> "1080p"
        else -> "720p"
    }

    /** 首页与设置页一行摘要。 */
    fun summary(): String = if (!videoEnabled) {
        "仅语音"
    } else {
        "${resolutionLabel()} · $fps 帧 · ${bpsLabel(maxVideoBps)}"
    }

    /**
     * 流量上限估算（MB/分钟）。1 Mbps = 7.5 MB/分钟，按码率上限算 —— 实际通常更低，
     * 所以文案必须写"上限"。仅语音按 Opus ~32 kbps 算，约 0.25 → 报 0.3。
     */
    fun estMbPerMinute(): String = if (!videoEnabled) {
        "0.3"
    } else {
        ((maxVideoBps.toLong() * 60 / 8 + 999_999) / 1_000_000).toString()
    }

    companion object {
        // 档位就是选项列表本身 —— UI 的分段按钮与持久化校验共用同一份枚举。
        val SCALES = listOf(0.5f, 0.75f, 1.0f)
        val FPSES = listOf(10, 15, 30)
        val BPS_LIST = listOf(300_000, 800_000, 2_000_000, 5_000_000)

        private const val PREFS = "t2"
        private const val K_SCALE = "q_scale"
        private const val K_FPS = "q_fps"
        private const val K_BPS = "q_bps"
        private const val K_VIDEO = "q_video"

        fun load(context: Context): ShareQuality {
            val d = ShareQuality()
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // 每个值都做"在档位列表里"的校验：将来档位改了，旧值不能把 UI 卡在 indexOf=-1 上。
            return ShareQuality(
                scale = sp.getFloat(K_SCALE, d.scale).takeIf { it in SCALES } ?: d.scale,
                fps = sp.getInt(K_FPS, d.fps).takeIf { it in FPSES } ?: d.fps,
                maxVideoBps = sp.getInt(K_BPS, d.maxVideoBps).takeIf { it in BPS_LIST } ?: d.maxVideoBps,
                videoEnabled = sp.getBoolean(K_VIDEO, d.videoEnabled),
            )
        }

        fun save(context: Context, q: ShareQuality) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(K_SCALE, q.scale)
                .putInt(K_FPS, q.fps)
                .putInt(K_BPS, q.maxVideoBps)
                .putBoolean(K_VIDEO, q.videoEnabled)
                .apply()
        }
    }
}

/** "2.0 Mbps" / "300 kbps" —— 只给设置页的码率行用。 */
internal fun bpsLabel(bps: Int): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000f)
    else -> "${bps / 1000} kbps"
}
