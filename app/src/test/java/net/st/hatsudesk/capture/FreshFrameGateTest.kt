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

    @Test
    fun `まだ隠していない番号では撮らない`() {
        // baseline 未設定。0 は「新しいフレームが来ていない」ので撮ってはいけない。
        val gate = FreshFrameGate()
        assertFalse(gate.isReady(0))
        assertFalse(gate.hasAnyNewFrame(0))
    }

    @Test
    fun `上限付近でも新しいフレームが無ければ撮らない`() {
        // baseline + required が Long の上限を越えて負数に回り込むと、新規フレームが
        // 無くても isReady が true になっていた（PR #22 の指摘 4）。
        val gate = FreshFrameGate()
        gate.after(Long.MAX_VALUE - 1)
        // 1枚しか進んでいない。上限を越える足し算で判定すると true になっていた。
        assertFalse(gate.isReady(Long.MAX_VALUE))
        assertTrue(gate.hasAnyNewFrame(Long.MAX_VALUE))
        assertFalse(gate.hasAnyNewFrame(Long.MAX_VALUE - 1))
    }

    @Test
    fun `上限ちょうどから数えても桁あふれしない`() {
        val gate = FreshFrameGate()
        gate.after(Long.MAX_VALUE)
        assertFalse(gate.isReady(Long.MAX_VALUE))
        assertFalse(gate.hasAnyNewFrame(Long.MAX_VALUE))
        // 通し番号が回り込んだ（負になった）側でも、差分は「1枚」「2枚」と数えられる。
        assertFalse(gate.isReady(Long.MIN_VALUE))
        assertTrue(gate.hasAnyNewFrame(Long.MIN_VALUE))
        assertTrue(gate.isReady(Long.MIN_VALUE + 1))
    }

    @Test
    fun `回り込んで2枚進めば撮ってよい`() {
        val gate = FreshFrameGate()
        gate.after(Long.MAX_VALUE - 1)
        // MAX-1 → MAX → MIN と進んだ。足し算で判定すると負数に回り込む位置。
        assertFalse(gate.isReady(Long.MAX_VALUE))
        assertTrue(gate.isReady(Long.MIN_VALUE))
    }

    @Test
    fun `下限付近でも新しいフレームが無ければ撮らない`() {
        val gate = FreshFrameGate()
        gate.after(Long.MIN_VALUE)
        assertFalse(gate.isReady(Long.MIN_VALUE))
        assertFalse(gate.hasAnyNewFrame(Long.MIN_VALUE))
        assertFalse(gate.isReady(Long.MIN_VALUE + 1))
        assertTrue(gate.isReady(Long.MIN_VALUE + 2))
    }
}
