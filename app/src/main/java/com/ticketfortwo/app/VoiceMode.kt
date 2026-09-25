package com.ticketfortwo.app

/**
 * 声音怎么传。**默认只有视频声**，连麦和只连麦都要用户额外开启。
 *
 * 为什么要单独立一个枚举，而不是继续用"画面 + 语音 / 仅语音"两个按钮：
 * 老文案里的"语音"其实指的是**房主的麦克风**，而观众听到的影片声也正是从这支麦克风
 * 收进来的外放。于是"语音"同时背着两件事 —— 传影片声、以及连麦说话 —— 用户没法只留一件。
 * 拆开之后每一档只做一件事，回声也就有了可以关掉的入口。
 *
 * 三档的准确语义（"手机声/视频声"能不能真做到，取决于下面这两条事实，不取决于文案）：
 *
 * ① **媒体音无法直接注入 libwebrtc 的上行。** 本机 AAR 的 `JavaAudioDeviceModule` 只有
 *    读样本的回调（`setSamplesReadyCallback` / `setAudioBufferCallback`），没有写样本的入口
 *    （javap 过：没有 `createAudioRecordInputChannel` 之类），`AudioSource` 也没有
 *    自定义处理器。所以"把手机里正在放的声音原样传过去"这条路在这套 SDK 上不存在。
 * ② 于是**分享屏幕时**，影片声唯一的通道就是房主外放 → 麦克风。这一档必须留着麦克风。
 * ③ 而**放映厅里**（观众本地播同一条流，S 档），观众听到的是原生那份声音，
 *    房主的麦克风再传一遍外放只会晚半拍地叠上去 —— 那正是用户听到的"回声"。
 *    所以 [VideoOnly] 在观众确认本地播放之后就该把麦克风关掉（见 [hostMicNeeded]）。
 */
enum class VoiceMode {

    /** 只有视频声：观众听画面那边的声音，但不说话。默认档。 */
    VideoOnly,

    /** 视频声 + 连麦：两边都能说。 */
    VideoPlusCall,

    /** 只连麦：不传画面，纯语音通话。 */
    CallOnly,
    ;

    companion object {

        /** 这一档要不要传画面。只有「只连麦」不传。 */
        fun sendsVideo(mode: VoiceMode): Boolean = mode != CallOnly

        /**
         * 房主的麦克风该不该开着。
         *
         * 只有「只有视频声」**且**对方确实在本地播这条流时才关 —— 那时麦克风是纯噪音
         * （观众已经有原生音轨了）。对方用的是 App 内收看或屏幕分享（B 档）时不能关：
         * 麦克风一关，观众那边就成了哑巴，那是比回声严重得多的故障（见上面事实 ②）。
         */
        fun hostMicNeeded(mode: VoiceMode, viewerPlaysLocally: Boolean): Boolean = when (mode) {
            VideoOnly -> !viewerPlaysLocally
            VideoPlusCall, CallOnly -> true
        }

        /**
         * 房主该不该听到对方的声音。
         *
         * 「只有视频声」下把房主侧的下行播放静音（`setSpeakerMute`，只静音 libwebrtc
         * 自己放的那条 AudioTrack，不影响房主外放的影片声）。这一条同时断掉一条回声路径：
         * 观众的声音从房主喇叭出来、再被房主麦克风收回去，就是最典型的那圈回声。
         */
        fun hostHearsViewer(mode: VoiceMode): Boolean = mode != VideoOnly

        /** 一句话名字，给设置页和首页摘要用。 */
        fun label(mode: VoiceMode): String = when (mode) {
            VideoOnly -> "只有视频声"
            VideoPlusCall -> "视频声 + 连麦"
            CallOnly -> "只连麦"
        }
    }
}
