package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ボタンが残っていた候補を捨て、次のフレームでやり直す（PR #22 の blocking 指摘）。
 *
 * `CaptureController.capture()` の繰り返し（待つ → 配置と画素で確かめる → 駄目なら次）を、
 * 同じ順序の小さな台で固定する。時刻の一次フィルタだけでは、隠す前に合成された
 * ボタン入りのフレームを落とせない。
 */
class VerifiedFrameGateTest {

    private val rect = PixelRect(964, 819, 8, 8)
    private val imageWidth = 1080
    private val imageHeight = 2400
    private val generation = 3L
    private val background = 0xFF3A6EA5.toInt()
    private val burned = IntArray(rect.pixelCount) { 0xFF000000.toInt() }
    private val clean = IntArray(rect.pixelCount) { background }

    private fun geometry() = CaptureGeometry(rect, generation, imageWidth, imageHeight)

    /** Controller と同じ順序で「待つ → 配置と画素で確かめる → 駄目なら次」を回す台。 */
    private class Loop(plan: CapturePlan, geometry: CaptureGeometry?) {
        private val gate = VerifiedFrameGate(plan, geometry)

        var chosen: Long? = null
        var discarded = 0
            private set
        var moved = 0
            private set

        /** フレームが1枚届いた（保持は最新1枚だけ）。保存してよければ [chosen] が埋まる。 */
        fun deliver(
            stamp: Long,
            pixels: IntArray?,
            layoutGeneration: Long = 3L,
            width: Int = 1080,
            height: Int = 2400,
        ) {
            if (chosen != null || moved > 0) return
            if (!gate.wants(stamp)) return
            when (gate.verdict(layoutGeneration, width, height, pixels)) {
                CandidateVerdict.SAMPLED -> chosen = stamp
                CandidateVerdict.BURNED -> {
                    gate.discard(stamp)
                    discarded += 1
                }
                CandidateVerdict.MOVED -> moved += 1
            }
        }

        val verifiesPixels: Boolean get() = gate.verifiesPixels
        val sinceNanos: Long get() = gate.sinceNanos
        fun wants(stamp: Long): Boolean = gate.wants(stamp)
    }

    @Test
    fun burnedCandidateIsDiscardedAndNextFrameIsUsed() {
        val plan = CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000)
        val loop = Loop(plan, geometry())

        // 隠す前に合成されたボタン入りのフレームが、隠した後の時刻で届いた。
        loop.deliver(1_050_000_000, burned)
        assertEquals(1, loop.discarded)
        assertNull(loop.chosen)

        // 次のフレームではボタンが消えている。ここで保存する。
        loop.deliver(1_070_000_000, clean)
        assertEquals(1, loop.discarded)
        assertEquals(1_070_000_000L, loop.chosen)
    }

    @Test
    fun burnedFrameIsNotRetried() {
        val plan = CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000)
        val loop = Loop(plan, geometry())
        val stamp = 1_050_000_000L

        loop.deliver(stamp, burned)
        // 同じ1枚を何度も見直さない。次はこれより新しいフレームだけを見る。
        assertFalse(loop.wants(stamp))
        assertEquals(stamp + 1, loop.sinceNanos)
    }

    @Test
    fun onlyBurnedCandidatesNeverProduceCapture() {
        val plan = CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000)
        val loop = Loop(plan, geometry())

        // パイプラインが遅れていて、ボタン入りのフレームが続けて届いた。
        loop.deliver(1_010_000_000, burned)
        loop.deliver(1_030_000_000, burned)
        loop.deliver(1_050_000_000, burned)

        assertNull("焼き付いた1枚は保存しない", loop.chosen)
        assertEquals(3, loop.discarded)
    }

    @Test
    fun frameComposedBeforeHideIsNotEvenChecked() {
        val plan = CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000)
        val loop = Loop(plan, geometry())

        // hide 完了より前の時刻。画素を見るまでもなく候補にしない。
        loop.deliver(999_000_000, clean)

        assertNull(loop.chosen)
        assertEquals(0, loop.discarded)
        assertFalse(loop.wants(999_000_000))
    }

    @Test
    fun nonBlackCandidatesProduceCapture() {
        val colors = listOf(0xFFFFFFFF.toInt(), background, 0xFF101010.toInt())
        colors.forEach { color ->
            val plan = CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000)
            val loop = Loop(plan, geometry())

            loop.deliver(1_050_000_000, IntArray(rect.pixelCount) { color })

            assertEquals(Integer.toHexString(color), 1_050_000_000L, loop.chosen)
            assertEquals(0, loop.discarded)
        }
    }

    @Test
    fun withoutOverlayEveryFrameIsKept() {
        // 隠すボタンが無い（オーバーレイ未許可）。画素で比べる相手が無いので待たない。
        val plan = CapturePlan.forPress(hideCompletedAtNanos = null)
        val loop = Loop(plan, geometry = null)

        assertFalse(loop.verifiesPixels)
        loop.deliver(5_000_000_000, burned)

        assertEquals(5_000_000_000, loop.chosen)
        assertEquals(0, loop.discarded)
    }
}
