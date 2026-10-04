package com.ticketfortwo.app.cinema

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放器手势的两个可自定义参数：**快进步长**与**倍速档位**（2026-10-01 用户要求
 * "倍速档位、快进步长这些感觉可以在设置里面自定义会好一点"）。
 *
 * 持久化用 SharedPreferences（键前缀与首页 "t2" 分开，避免互相挤占）；
 * 读数是进程级 Compose state —— 设置页改完，放映画面里的手势立刻用上新值。
 * [init] 要在 IO 线程调（首次访问会同步读磁盘，工作记忆里的老坑）。
 */
object PlayerPrefs {

    /** 双击快进/快退的秒数。 */
    var stepSec by mutableStateOf(10)
        private set

    /** 倍速档位预设的下标（长按上滑按当前预设的顺序逐档切换）。 */
    var ladderIndex by mutableStateOf(1)
        private set

    /** 可选的快进步长（秒）。 */
    val STEPS = listOf(5, 10, 15, 30)

    /**
     * 倍速档位预设：名字 + 升档顺序（长按起步一律 2x，上滑按此序列逐档升，
     * 下滑反向降，可降到 0.5x —— 0.5/1 两档不在序列里，靠"降到底"进入）。
     */
    val LADDERS: List<Pair<String, List<Double>>> = listOf(
        "简洁 2·4·8" to listOf(2.0, 4.0, 8.0),
        "标准 2·3·4·6·8" to listOf(2.0, 3.0, 4.0, 6.0, 8.0),
        "细腻 1.5 起步" to listOf(1.5, 2.0, 2.5, 3.0, 4.0, 6.0, 8.0),
    )

    /** 当前倍速档位序列。 */
    fun ladder(): List<Double> =
        LADDERS.getOrElse(ladderIndex) { LADDERS[1] }.second

    /** 读盘（IO 线程）。默认值先顶着，读完自动触发重组。 */
    fun init(context: Context) {
        val sp = context.applicationContext
            .getSharedPreferences("t2_player", Context.MODE_PRIVATE)
        stepSec = sp.getInt("step_sec", 10).coerceIn(5, 120)
        ladderIndex = sp.getInt("ladder", 1).coerceIn(0, LADDERS.size - 1)
    }

    /**
     * 同步读盘拿快进步长：**给浮窗服务这类非 Compose 场景用**（2026-10-04）。
     * [stepSec] 是进程级 state，要有人调过 [init] 才准 —— 而浮窗服务不一定赶在
     * 放映页之后起来（进程冷启动 + 小窗先复活时它可能更早），直接读盘才稳。
     */
    fun stepSecOf(context: Context): Int =
        context.applicationContext
            .getSharedPreferences("t2_player", Context.MODE_PRIVATE)
            .getInt("step_sec", 10).coerceIn(5, 120)

    fun setStep(sec: Int, context: Context) {
        val v = sec.coerceIn(5, 120)
        stepSec = v
        context.applicationContext.getSharedPreferences("t2_player", Context.MODE_PRIVATE)
            .edit().putInt("step_sec", v).apply()
    }

    fun setLadder(index: Int, context: Context) {
        val v = index.coerceIn(0, LADDERS.size - 1)
        ladderIndex = v
        context.applicationContext.getSharedPreferences("t2_player", Context.MODE_PRIVATE)
            .edit().putInt("ladder", v).apply()
    }
}
