package com.ticketfortwo.app

import org.webrtc.PeerConnection.IceConnectionState

/**
 * 信令通道断了之后，是去重连还是直接收场。
 *
 * 单独成一个顶层函数，是为了能被单测钉住 —— 这条判断决定的是"观众会不会在
 * 房主根本没停的情况下，看到一句骗人的『对方可能已停止分享』"，
 * 而它藏在协程 + socket 回调里，不抽出来就只能靠真注入故障去验，
 * 而这套模拟器上注入 TCP 故障并不可靠（REJECT 只掐住了空闲连接，
 * 7 秒内没包要发就什么都不断；`ss -K` 在这个镜像里直接段错误）。
 *
 * 判据只有三条，都是"别骗人"：
 *  · **从没连上过**（everOpened=false）→ 那是连不上，不是断了，走 Failed 给重试；
 *  · **没有可重连的地址** → 无从重连；
 *  · **媒体已经死了**（ICE 不是 CONNECTED/CHECKING）→ 别假装救得回来，如实结束。
 * 反过来，画面还在动（ICE 健康）时信令断了，就值得退避重连几次：
 * 媒体是 P2P 的，跟那条经 Cloudflare 隧道走的 WS 没关系，
 * 而隧道回收空闲连接是出了名的（实测 100~120 秒）。
 */
fun shouldRescue(everOpened: Boolean, hasInvite: Boolean, ice: IceConnectionState?): Boolean =
    everOpened && hasInvite && (ice == IceConnectionState.CONNECTED || ice == IceConnectionState.CHECKING)
