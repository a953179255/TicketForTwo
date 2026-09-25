package com.ticketfortwo.app

import android.content.Context
import kotlin.math.roundToInt

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
    /** 声音怎么传（[VoiceMode]）。默认只有视频声，连麦要额外开启。 */
    val voiceMode: VoiceMode = VoiceMode.VideoOnly,
) {

    /**
     * 这一档要不要传画面。
     *
     * 以前它是一个存进 SharedPreferences 的布尔字段（`q_video`），和"语音"混在一起表达
     * 三件事；现在由 [voiceMode] 派生 —— 只连麦才没有画面。留这个名字是因为调用方
     * 关心的就是"要不要去要投屏授权"，没必要为了改名去动六处判断。
     */
    val videoEnabled: Boolean get() = VoiceMode.sendsVideo(voiceMode)

    /** 分辨率档的用户文案。**必须带屏宽** —— 见 [resolutionLabelFor]。 */
    fun resolutionLabel(screenWidthPx: Int): String = resolutionLabelFor(scale, screenWidthPx)

    /** 首页与设置页一行摘要。 */
    fun summary(screenWidthPx: Int): String = if (!videoEnabled) {
        VoiceMode.label(voiceMode)
    } else {
        "${VoiceMode.label(voiceMode)} · ${resolutionLabel(screenWidthPx)} · $fps 帧 · ${bpsLabel(maxVideoBps)}"
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

        /**
         * 采集分辨率档的显示名。**必须传屏宽**：
         * 采集出来的宽度 = 屏幕宽 × scale，所以同一个 0.5 档，在 1080 宽的机上产 540 宽、
         * 在 1440 宽的 2K 机上产 720 宽。早先按 scale 写死映射（0.5→540p / 1.0→1080p），
         * 结果 2K 机型把"其实是 720 宽"的那档标成了 540p，而设置页里同一屏的
         * 「当前组合」又是另一套算法 —— 两行文案当场对不上。
         */
        fun resolutionLabelFor(scale: Float, screenWidthPx: Int): String {
            val w = (screenWidthPx * scale).roundToInt()
            return when {
                w >= 1300 -> "2K"
                w >= 1000 -> "1080p"
                w >= 700 -> "720p"
                else -> "540p"
            }
        }

        /**
         * 最低档 / 最高档的显示名。
         *
         * 设置页有两句**提到具体档位**的说明文案（省流量建议、发热提醒）。这两句以前把
         * "540p" / "1080p" 写死在字符串里 —— 在 2K 机上最低档其实叫 720p、最高档叫 2K，
         * 于是同一屏里出现"分辨率行没有 540p，下面的建议却让选 540p"的矛盾。
         * 凡是"提到某一档"的文案，都必须用这两个助手取名字，不能写字面量。
         */
        fun lowestResolutionLabel(screenWidthPx: Int): String =
            resolutionLabelFor(SCALES.first(), screenWidthPx)

        fun highestResolutionLabel(screenWidthPx: Int): String =
            resolutionLabelFor(SCALES.last(), screenWidthPx)

        /** 帧率档：只留 30 / 60（10、15 帧没有存在意义）；更高或更低的用「自定义」。 */
        val FPSES = listOf(30, 60)
        /** 码率预设档。12M 那一档由「自定义输入」取代 —— 同一件事不留两个入口。 */
        val BPS_LIST = listOf(1_000_000, 2_000_000, 4_000_000, 8_000_000)

        /** 自定义码率允许范围（100 kbps – 50 Mbps）。 */
        const val BPS_MIN = 100_000
        const val BPS_MAX = 50_000_000

        /** 自定义帧率允许范围（8 – 120 fps）。 */
        const val FPS_MIN = 8
        const val FPS_MAX = 120

        private const val PREFS = "t2"
        private const val K_SCALE = "q_scale"
        private const val K_FPS = "q_fps"
        private const val K_BPS = "q_bps"
        private const val K_VIDEO = "q_video"
        private const val K_VOICE = "q_voice"

        fun load(context: Context): ShareQuality {
            val d = ShareQuality()
            val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // 每个值都做"在档位列表里"的校验：将来档位改了，旧值不能把 UI 卡在 indexOf=-1 上。
            return ShareQuality(
                scale = sp.getFloat(K_SCALE, d.scale).takeIf { it in SCALES } ?: d.scale,
                // 帧率也可能是「自定义」值，同样按范围校验而不是按档位成员
                fps = sp.getInt(K_FPS, d.fps).takeIf { it in FPS_MIN..FPS_MAX } ?: d.fps,
                // 码率可能是「自定义」值（不在预设档里），所以按范围校验而不是按档位成员。
                maxVideoBps = sp.getInt(K_BPS, d.maxVideoBps).takeIf { it in BPS_MIN..BPS_MAX } ?: d.maxVideoBps,
                voiceMode = voiceModeOf(sp),
            )
        }

        /**
         * 读声音档，并且把**旧版那份布尔**翻译过来。
         *
         * 旧版只有 `q_video`（true=画面+语音 / false=仅语音）。直接丢掉它会让老用户的
         * "仅语音"在升级后变成"有画面"——那是行为突变；所以：新键没写过时，
         * `q_video=false` 认作 [VoiceMode.CallOnly]，其余一律落到默认档
         * [VoiceMode.VideoOnly]（旧档里"画面+语音"和"连麦"本来就是同一件事，
         * 而默认只有视频声正是这次要改的东西）。
         */
        private fun voiceModeOf(sp: android.content.SharedPreferences): VoiceMode {
            sp.getString(K_VOICE, null)?.let { raw ->
                return runCatching { VoiceMode.valueOf(raw) }.getOrDefault(VoiceMode.VideoOnly)
            }
            return if (sp.getBoolean(K_VIDEO, true)) VoiceMode.VideoOnly else VoiceMode.CallOnly
        }

        fun save(context: Context, q: ShareQuality) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat(K_SCALE, q.scale)
                .putInt(K_FPS, q.fps)
                .putInt(K_BPS, q.maxVideoBps)
                // 两个键一起写：`q_video` 现在是派生值，但留着它，
                // 万一回滚到旧版本，至少画面/仅语音这一层语义还在。
                .putBoolean(K_VIDEO, q.videoEnabled)
                .putString(K_VOICE, q.voiceMode.name)
                .apply()
        }
    }
}

/** "2.0 Mbps" / "300 kbps" —— 只给设置页的码率行用。 */
internal fun bpsLabel(bps: Int): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000f)
    else -> "${bps / 1000} kbps"
}
