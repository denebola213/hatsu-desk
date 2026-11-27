package net.st.hatsudesk.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影画面の停止ボタンの有効条件。
 *
 * 通知を拒否すると前面サービスの通知がドロワーに出ないため、停止手段を通知に
 * 依存させないことがこの修正の要件。ここでは「投影中だけ押せる」ことと、
 * 「フレームが1枚も届いていなくても投影中なら押せる（＝通知の有無に依らない）」ことを見る。
 */
class CaptureStateTest {

    @Test
    fun `投影中は停止できる`() {
        val state = CaptureState(status = ProjectionSession.State.RUNNING, hasFrame = false)
        assertTrue(state.canStop)
    }

    @Test
    fun `停止中は停止できない`() {
        assertFalse(CaptureState(status = ProjectionSession.State.IDLE).canStop)
    }

    @Test
    fun `同意待ちは停止できない`() {
        assertFalse(CaptureState(status = ProjectionSession.State.AWAITING_CONSENT).canStop)
    }

    @Test
    fun `停止できるかはフレームの有無に依らない`() {
        assertTrue(CaptureState(status = ProjectionSession.State.RUNNING, hasFrame = true).canStop)
        assertTrue(CaptureState(status = ProjectionSession.State.RUNNING, hasFrame = false).canStop)
    }
}
