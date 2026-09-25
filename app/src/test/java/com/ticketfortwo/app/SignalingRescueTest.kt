package com.ticketfortwo.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.PeerConnection.IceConnectionState as Ice

/**
 * 信令断了之后"救还是不救"的判定。
 *
 * 这条判断决定的是观众看到哪句话：救得回来就不该说"对方可能已停止分享"，
 * 救不回来才如实收场。上一版的缺陷正是**不区分**这两件事 ——
 * 隧道回收空闲 WS 时，一通画面还在动的 P2P 通话被当成"房主停了"结束掉。
 */
class SignalingRescueTest {

    @Test
    fun `画面还在动时，信令断了要救`() {
        assertTrue(shouldRescue(true, true, Ice.CONNECTED))
        // CHECKING 也算：重连期间 ICE 可能正在重新选路，别急着判死
        assertTrue(shouldRescue(true, true, Ice.CHECKING))
    }

    @Test
    fun `媒体已经死了就别假装救得回来`() {
        assertFalse(shouldRescue(true, true, Ice.DISCONNECTED))
        assertFalse(shouldRescue(true, true, Ice.FAILED))
        assertFalse(shouldRescue(true, true, Ice.CLOSED))
        assertFalse(shouldRescue(true, true, null))
    }

    @Test
    fun `从没连上过属于连不上，不属于断了`() {
        // 这条守住的是上一轮修好的分诊：隧道还没注册好时 Cloudflare 回 530，
        // 那是 Failed（该给重试），不能写成 Ended（让人干等一个不会再来的房主）。
        assertFalse(shouldRescue(false, true, Ice.CONNECTED))
    }

    @Test
    fun `没有可重连的地址就无从重连`() {
        assertFalse(shouldRescue(true, false, Ice.CONNECTED))
    }
}
