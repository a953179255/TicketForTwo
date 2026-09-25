package com.ticketfortwo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ticketfortwo.app.cinema.CinemaSync

/**
 * 只存在于 debug 变体的调试入口：从 adb 打进来的「掐断观众侧信令」广播。
 *
 * 为什么要专门开这个口：`ViewerSession.rescueSignaling()`（信令断了但画面还在 →
 * 有界重连）这条路径，在模拟器上用网络手段注入不可靠 —— 实测
 * `iptables -A OUTPUT -p tcp -j REJECT` 掐了 7 秒什么都没断（那条连接本来就空闲，
 * 没包要发就不会触发失败），`ss -K` 在这个镜像里直接段错误。
 * 与其让这条路径永远停在"只有单测覆盖判定"，不如给一个**只掐信令、不碰媒体**的开关。
 *
 * 它复用的正是 rescueSignaling 里同一句 `ws.close()`，所以测的是真代码路径，
 * 不是为测试特制的假路径。release 包里这个 receiver 根本不存在（清单在 src/debug/）。
 */
class DebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DROP_WS -> {
                val before = ViewerSession.state.value
                Log.i("DebugReceiver", "按指示掐断观众侧信令（媒体不动）。当前状态：$before")
                ViewerSession.debugDropSignaling()
            }

            /* 直接递一条片源给观众，绕开"在模拟器里点中那一行"。
             *
             * 为什么开这个口：验证"晚进厅的观众能不能立刻拿到当前放映状态"时，
             * 靠 uiautomator 点候选行一直翻车 —— 软键盘盖住卡片、地址栏里的 URL
             * 和候选行文字相似导致点错、页面加载耗时让固定 sleep 误判成功。
             * 那些是**量具的噪声**，不是被测逻辑。这里复用 setCinemaTrack 这个
             * 真实入口（不是为测试特制的假路径），把"点界面"从链路里摘掉。
             * release 包里这个 receiver 不存在（清单在 src/debug/）。 */
            ACTION_SCREEN -> {
                val url = intent.getStringExtra("url")
                    ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                val kind = intent.getStringExtra("kind")
                    ?: if (url.contains(".m3u8")) "master" else "progressive"
                val title = intent.getStringExtra("title") ?: "调试片源"
                Log.i("DebugReceiver", "按指示递片：$kind $url")
                CallSession.setCinemaTrack(CinemaSync.Track(url, kind, title, 0L))
            }

            ACTION_UNSCREEN -> {
                Log.i("DebugReceiver", "按指示收厅")
                CallSession.setCinemaTrack(null)
            }
        }
    }

    companion object {
        const val ACTION_DROP_WS = "com.ticketfortwo.app.DEBUG_DROP_WS"
        const val ACTION_SCREEN = "com.ticketfortwo.app.DEBUG_SCREEN"
        const val ACTION_UNSCREEN = "com.ticketfortwo.app.DEBUG_UNSCREEN"
    }
}
