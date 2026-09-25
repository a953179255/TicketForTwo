package com.ticketfortwo.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

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
        }
    }

    companion object {
        const val ACTION_DROP_WS = "com.ticketfortwo.app.DEBUG_DROP_WS"
    }
}
