package com.ticketfortwo.app.cinema

/**
 * 放映厅给 debug 广播留的钩子（**只有 debug 变体的 receiver 会调它**；
 * release 包里没人赋值，也就没人能触发）。
 *
 * 为什么要有：统计"真实站点的 S 档命中率"时，要递出去的是**App 自己嗅到的那条地址**，
 * 而不是我从 adb 里塞进去的 URL —— 后者测的只是传输，前者测的才是
 * "嗅探 + 选路 + 递地址"这一整条真实链路。而用 uiautomator 去点候选行已经证明不可靠
 * （软键盘遮挡、地址栏文字与候选行相似、加载耗时让固定 sleep 误报成功）。
 *
 * 和 [com.ticketfortwo.app.CallSession.onCinemaCommand] 一样：**注册必须成对摘除**，
 * 否则放映厅那一屏关掉后钩子还指着已销毁的界面状态。
 */
object CinemaDebug {

    /** 递出当前嗅到的最佳候选；返回那条 URL（没候选就返回 null）。 */
    @Volatile
    var screenBest: (() -> String?)? = null

    /** 当前候选数，用来说明"没递出去"到底是没嗅到还是别的。 */
    @Volatile
    var candidateCount: (() -> Int)? = null
}
