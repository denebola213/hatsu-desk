package net.st.hatsudesk.capture

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画素で焼き付きを捕まえる（PR #22 の blocking 指摘）。
 *
 * `Image.getTimestamp()` は合成時刻ではなく `queueBuffer()` の時刻なので、隠す前に合成された
 * フレームが「隠した後」の時刻を持つことがある。時刻では焼き付きを証明できない。
 * ここでは、押した瞬間の矩形の画素と候補の矩形の画素を比べる判定だけを固定する。
 */
class OverlayPixelCheckTest {

    private val rect = PixelRect(964, 819, 116, 116)

    private fun plain(argb: Int): IntArray = IntArray(rect.pixelCount) { argb }

    /**
     * ボタンを重ねた矩形を作る（実機の 116px のボタンと同じ比率）。
     * 円の内側を 40% の黒で塗り、輪と中の四角を白 (アルファ 0xE6) で描く。
     */
    private fun withButton(argb: Int): IntArray {
        val size = rect.width
        val pixels = IntArray(rect.pixelCount)
        val centerX = (size - 1) / 2.0
        val centerY = (size - 1) / 2.0
        val ringWidth = 2.0 * DENSITY
        val markWidth = 1.5 * DENSITY
        val radius = size / 2.0 - ringWidth
        val markHalf = radius * 0.42
        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = x - centerX
                val dy = y - centerY
                var color = argb
                if (hypot(dx, dy) <= radius) color = blend(color, 0x000000, 0.4)
                if (abs(hypot(dx, dy) - radius) <= ringWidth / 2) color = blend(color, 0xFFFFFF, RING_ALPHA)
                val ax = abs(dx)
                val ay = abs(dy)
                if (ax <= markHalf && ay <= markHalf &&
                    (abs(ax - markHalf) <= markWidth / 2 || abs(ay - markHalf) <= markWidth / 2)
                ) {
                    color = blend(color, 0xFFFFFF, RING_ALPHA)
                }
                pixels[y * size + x] = color
            }
        }
        return pixels
    }

    private fun blend(base: Int, over: Int, alpha: Double): Int {
        fun channel(shift: Int): Int {
            val under = (base shr shift) and 0xFF
            val top = (over shr shift) and 0xFF
            return (under * (1 - alpha) + top * alpha).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    @Test
    fun meanAbsDiffUsesRgbOnly() {
        // アルファだけが違う画素は「同じ絵」として扱う（画面は不透明）。
        assertEquals(0.0, OverlayPixelCheck.meanAbsDiff(intArrayOf(0x00FFFFFF.toInt()), intArrayOf(0xFFFFFFFF.toInt())), 0.0)
        assertEquals(
            (10 + 20 + 30) / 3.0,
            OverlayPixelCheck.meanAbsDiff(intArrayOf(0xFF000000.toInt()), intArrayOf(0xFF0A141E.toInt())),
            0.0,
        )
    }

    @Test
    fun identicalRectIsNotTreatedAsButtonGone() {
        val stage = withButton(0xFF3A6EA5.toInt())
        val check = OverlayPixelCheck(rect, stage)

        assertFalse(check.isButtonGone(stage.copyOf()))
    }

    @Test
    fun buttonStillInCandidateIsRejected() {
        // 隠す前に合成されたフレームが、隠した後にキューされて届いた場合。
        // 下の絵はわずかに動いているが、ボタンは残っている。時刻では「隠した後」に見える。
        val stage = withButton(0xFF3A6EA5.toInt())
        val candidate = withButton(0xFF3C6CA6.toInt())
        val check = OverlayPixelCheck(rect, stage)

        val diff = OverlayPixelCheck.meanAbsDiff(stage, candidate)
        assertTrue("下の絵の揺れだけで閾値を超えてはいけない（差=$diff）", diff < 5.0)
        assertFalse(check.isButtonGone(candidate))
    }

    @Test
    fun buttonRemovedIsAccepted() {
        // 隠した後のフレーム。ボタンが消えて下の絵が出ている。
        val stage = withButton(0xFF3A6EA5.toInt())
        val candidate = plain(0xFF3A6EA5.toInt())
        val check = OverlayPixelCheck(rect, stage)

        val diff = OverlayPixelCheck.meanAbsDiff(stage, candidate)
        assertTrue("差 $diff は閾値 ${OverlayPixelCheck.MIN_BUTTON_GONE_DIFF} を超えるはず", diff > 40.0)
        assertTrue(check.isButtonGone(candidate))
    }

    @Test
    fun darkBackgroundStillReachesThreshold() {
        // 最悪条件: 背景が真っ黒で塗り（40% の黒）が効かず、白い輪と四角だけが動く。
        val stage = withButton(0xFF000000.toInt())
        val candidate = plain(0xFF000000.toInt())
        val check = OverlayPixelCheck(rect, stage)

        val diff = OverlayPixelCheck.meanAbsDiff(stage, candidate)
        assertTrue("差 $diff は 30 以上のはず（見積もり 35.28）", diff > 30.0)
        assertTrue(
            "差 $diff は閾値 ${OverlayPixelCheck.MIN_BUTTON_GONE_DIFF} を超えるはず",
            check.isButtonGone(candidate),
        )
    }

    @Test
    fun whiteBackgroundAlsoDetected() {
        val stage = withButton(0xFFFFFFFF.toInt())
        val candidate = plain(0xFFFFFFFF.toInt())

        assertTrue(OverlayPixelCheck(rect, stage).isButtonGone(candidate))
    }

    @Test
    fun thresholdIsBelowEveryMeasuredButtonGoneDiff() {
        // 閾値そのものの根拠を数値で固定する。実測 55.36、最悪条件の見積もり 35.28。
        assertEquals(20.0, OverlayPixelCheck.MIN_BUTTON_GONE_DIFF, 0.0)
        assertTrue(OverlayPixelCheck.MIN_BUTTON_GONE_DIFF < 35.28)
        assertTrue(OverlayPixelCheck.MIN_BUTTON_GONE_DIFF < 55.36)
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPixelCountIsRejected() {
        // 矩形がずれた候補を黙って「消えた」と判断しない。
        OverlayPixelCheck(rect, withButton(0xFF202020.toInt())).isButtonGone(IntArray(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun baselineMustMatchRect() {
        OverlayPixelCheck(rect, IntArray(4))
    }

    private companion object {
        /** 実機（Pixel 10 Pro）の density。44dp のボタンが 116px になる。 */
        const val DENSITY = 2.625

        /** 輪と四角の白は `0xE6`（約 90%）。 */
        const val RING_ALPHA = 230.0 / 255.0
    }
}
