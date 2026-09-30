package com.ticketfortwo.app

import android.content.Context
import kotlin.math.roundToInt

/**
 * 分享画质设置：**上限，不是保证值**（网络差、发热时会自动再降）。
 *
 * 分享进行中改档**即时生效**（CallSession.updateQuality）：声音档、码率上限、
 * 帧率/分辨率都不动 m-line，可热改；"带画面 ⇄ 只连麦"是结构变化 —— 关画面走
 * 系统收回授权的同一条路径，开画面必须拿新的投屏授权（由设置页 onChange 带
 * 用户去走）。
 *
 * 持久化复用首页那份 "t2" SharedPreferences，只是键前缀不同。
 */
/**
 * 放映方式（2026-09-30 用户拍板：两案并存，设置里手动切）。
 *
 * - [Direct] 同步直连（现有 S 档）：发地址、观众自己播 —— 画质原生，
 *   但**对方要能访问片源**（对方也得挂代理才行）。
 * - [Relayed] 我播他看（B 方案）：房主是唯一播放器，纯视频轨经 WebRTC 转发过去 ——
 *   对方不挂代理也能看、看不到你手机的 UI；代价是你的上行流量与耗电。
 */
enum class PlayMode { Direct, Relayed }

data class ShareQuality(
    /** 采集缩放：0.5 → 约 540 宽、0.75 → 约 810、1.0 → 原生机（1080 机型标 1080p）。 */
    val scale: Float = CallSession.DEFAULT_CAPTURE_SCALE,
    val fps: Int = CallSession.DEFAULT_VIDEO_FPS,
    val maxVideoBps: Int = CallSession.DEFAULT_MAX_VIDEO_BPS,
    /** 声音怎么传（[VoiceMode]）。默认「视频声+连麦」，麦克风默认关（见 load 的一次性迁移）。 */
    val voiceMode: VoiceMode = VoiceMode.VideoPlusCall,
    /** 放映方式：发地址各播一份，还是房主转视频轨过去（见 [PlayMode]）。 */
    val playMode: PlayMode = PlayMode.Direct,
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

    /** 首页与设置页一行摘要：声音档在最前，因为它才是"这场会怎样"的第一答案。 */
    fun summary(screenWidthPx: Int): String =
        if (videoEnabled) "${VoiceMode.label(voiceMode)} · ${pictureSummary(screenWidthPx)}"
        else VoiceMode.label(voiceMode)

    /**
     * 只有画质那半截（"720p · 30 帧 · 2.0 Mbps"）。
     *
     * 首页的信息卡把声音和画质拆成两行放，所以这里要一个不带声音档的版本 ——
     * 别在 UI 里再手拼一遍"分辨率 · 帧率 · 码率"，那正是设置页曾经出现两套算法的地方。
     */
    fun pictureSummary(screenWidthPx: Int): String =
        "${resolutionLabel(screenWidthPx)} · $fps 帧 · ${bpsLabel(maxVideoBps)}"

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
        private const val K_PMODE = "q_pmode"
        /** 一次性迁移标记：旧默认「只有视频声」升级为「视频声+连麦」（产品尚无外部用户）。 */
        private const val K_VOICE_MIGRATED = "q_voice_migrated"

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
                voiceMode = migrateVoiceOnce(sp),
                playMode = sp.getString(K_PMODE, null)
                    ?.let { runCatching { PlayMode.valueOf(it) }.getOrNull() }
                    ?: PlayMode.Direct,
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
        /**
         * 读声音档 + **一次性存量迁移**（2026-10-01 计划，用户拍板）：
         * 旧默认是「只有视频声」，产品还没有外部用户，把存量升级成新默认「视频声+连麦」，
         * 否则他自己设备上永远看不到新默认。打过标记后不再迁移 —— 之后主动选回
         * 「只有视频声」会被尊重（隐私档保留）。
         */
        private fun migrateVoiceOnce(sp: android.content.SharedPreferences): VoiceMode {
            val vm = voiceModeOf(sp)
            if (sp.getBoolean(K_VOICE_MIGRATED, false)) return vm
            if (vm == VoiceMode.VideoOnly) {
                // **必须回写**：只升级内存不写盘，下次启动 load 又读回旧值 = 白迁（实测踩过）
                sp.edit()
                    .putString(K_VOICE, VoiceMode.VideoPlusCall.name)
                    .putBoolean(K_VOICE_MIGRATED, true)
                    .apply()
                return VoiceMode.VideoPlusCall
            }
            sp.edit().putBoolean(K_VOICE_MIGRATED, true).apply()
            return vm
        }

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
                .putString(K_PMODE, q.playMode.name)
                .apply()
        }
    }
}

/** "2.0 Mbps" / "300 kbps" —— 只给设置页的码率行用。 */
internal fun bpsLabel(bps: Int): String = when {
    bps >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000f)
    else -> "${bps / 1000} kbps"
}
