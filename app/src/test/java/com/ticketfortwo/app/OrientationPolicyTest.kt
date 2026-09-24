package com.ticketfortwo.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 观众屏方向策略。
 *
 * 这条逻辑的代价是"用户的手机突然自己转"，所以每一档都要钉死：
 * 尤其是 `Keep` 那两个分支 —— 还没来帧、或者对方开的是仅语音时**不该动屏幕方向**，
 * 写错的话用户一点开观看手机就被锁成横屏，而画面根本没有横不横可言。
 */
class OrientationPolicyTest {

    private val L = OrientationTarget.Landscape
    private val P = OrientationTarget.Portrait
    private val K = OrientationTarget.Keep

    @Test
    fun `跟随模式下 内容横就横 内容竖就竖`() {
        assertEquals(L, decideOrientation(OrientationMode.Follow, true, hasVideo = true))
        assertEquals(P, decideOrientation(OrientationMode.Follow, false, hasVideo = true))
    }

    @Test
    fun `首帧还没到就不动方向`() {
        assertEquals(K, decideOrientation(OrientationMode.Follow, null, hasVideo = true))
    }

    @Test
    fun `仅语音没有画面方向可言`() {
        // 就算上一帧还留着"横"的记忆，没有视频轨也不该锁横屏
        assertEquals(K, decideOrientation(OrientationMode.Follow, true, hasVideo = false))
        assertEquals(K, decideOrientation(OrientationMode.Follow, null, hasVideo = false))
    }

    @Test
    fun `手动锁竖锁横压过内容方向`() {
        assertEquals(P, decideOrientation(OrientationMode.Portrait, true, hasVideo = true))
        assertEquals(L, decideOrientation(OrientationMode.Landscape, false, hasVideo = true))
        // 锁了方向之后，即使还没来帧也照锁 —— 这是用户明确的意图，不是我们的猜测
        assertEquals(P, decideOrientation(OrientationMode.Portrait, null, hasVideo = false))
    }

    @Test
    fun `按钮循环是 跟随-竖-横-跟随`() {
        // 顺序写错，用户点一下就越过他要的那一档
        assertEquals(OrientationMode.Portrait, nextOrientationMode(OrientationMode.Follow))
        assertEquals(OrientationMode.Landscape, nextOrientationMode(OrientationMode.Portrait))
        assertEquals(OrientationMode.Follow, nextOrientationMode(OrientationMode.Landscape))
    }
}
