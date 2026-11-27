package net.st.hatsudesk.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焼き付きを消すための「隠した後に何枚待つか」の判定。
 *
 * 設計 §4.1 の「押下時のフレームを先に確保してから隠す」では、確保した絵にすでに
 * ボタンが合成されているので消えない。ここは**隠した後に届いたフレーム**だけを
 * 数える、という前提を固定する。
 */
class FreshFrameGateTest {

    @Test
    fun `隠す前の番号では撮らない`() {
        val gate = FreshFrameGate()
        gate.after(10)
        assertFalse(gate.isReady(10))
        assertFalse(gate.hasAnyNewFrame(10))
    }

    @Test
    fun `1枚では足りない`() {
        val gate = FreshFrameGate()
        gate.after(10)
        assertFalse(gate.isReady(11))
        assertTrue(gate.hasAnyNewFrame(11))
    }

    @Test
    fun `2枚で撮ってよい`() {
        val gate = FreshFrameGate()
        gate.after(10)
        assertTrue(gate.isReady(12))
        assertTrue(gate.isReady(13))
    }

    @Test
    fun `番号は隠すたびに数え直す`() {
        val gate = FreshFrameGate()
        gate.after(10)
        gate.after(50)
        assertFalse(gate.isReady(51))
        assertTrue(gate.isReady(52))
        assertFalse(gate.hasAnyNewFrame(50))
    }

    @Test
    fun `要求枚数を変えられる`() {
        val gate = FreshFrameGate(required = 1)
        gate.after(0)
        assertTrue(gate.isReady(1))
    }
}
