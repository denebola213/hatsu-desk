package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取得の段取り（PR #22 の指摘 1・2）。
 *
 * - 隠した後に1枚しか来なくても保存しない（2枚目が「隠した後の合成」である保証が要る）
 * - 隠すボタンが無いときは待たない（静止画面では新しいフレームが来ないため）
 */
class CapturePlanTest {

    @Test
    fun `隠した後に1枚だけでは保存しない`() {
        val gate = FreshFrameGate()
        val plan = CapturePlan.forPress(pressSerial = 10, hidesOverlay = true, gate = gate)

        assertTrue(plan.waitsForFrames)
        // 隠す前の番号、および隠した直後の1枚目。どちらも保存してはいけない。
        assertEquals(CapturePlan.Outcome.TOO_FEW, plan.outcome(10))
        assertEquals(CapturePlan.Outcome.TOO_FEW, plan.outcome(11))
        // 2枚進んで初めて保存してよい。
        assertEquals(CapturePlan.Outcome.FRESH, plan.outcome(12))
    }

    @Test
    fun `隠すものが無ければ待たずに最新1枚を使う`() {
        val gate = FreshFrameGate()
        val plan = CapturePlan.forPress(pressSerial = 10, hidesOverlay = false, gate = gate)

        assertFalse(plan.waitsForFrames)
        // 新しいフレームが1枚も来ていなくても（静止画面）、いまの1枚で保存してよい。
        assertEquals(CapturePlan.Outcome.LATEST, plan.outcome(10))
    }

    @Test
    fun `隠すものが無いときに別の番号で聞かれても最新1枚に倒す`() {
        val plan = CapturePlan.forPress(pressSerial = 10, hidesOverlay = false, gate = FreshFrameGate())
        // 待っていない以上、番号が進んでいないことを理由に失敗させない。
        assertEquals(CapturePlan.Outcome.LATEST, plan.outcome(11))
    }

    @Test
    fun `押した番号で門を数え直す`() {
        val gate = FreshFrameGate()
        CapturePlan.forPress(pressSerial = 100, hidesOverlay = true, gate = gate)
        assertFalse(gate.isReady(101))
        assertTrue(gate.isReady(102))
    }

    @Test
    fun `待ち切れなかったときの文言`() {
        assertEquals(
            "画面が止まっていて撮れませんでした。もう一度押してください",
            CapturePlan.STALLED_MESSAGE,
        )
    }
}
