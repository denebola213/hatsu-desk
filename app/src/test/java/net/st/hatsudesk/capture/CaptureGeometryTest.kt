package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 押した後にボタンが**同じ大きさのまま**動いたときの取り違え（PR #22 の blocking 指摘）。
 *
 * 旧い手順は「矩形を決める」→（`Dispatchers.Default` への suspend）→「隠す」の順だった。
 * その間に main 上のドラッグや回転がボタンを A から B へ動かせると、判定は A のままなので
 * A（非黒）を見て「ボタンが消えた」と判断し、**B の黒い印を残したフレームを保存**してしまう。
 * 幅・高さ・画素数は移動しても変わらないため、画素数の検査でも防げない。
 *
 * いまは実 View の座標・配置世代・画像寸法を確定し、同じ main ターンの中で隠す。確定した組と
 * 食い違う候補は保存しない。ここでは Controller と同じ順序（確定 → hide → 候補の判定）を
 * 回す台で、A→B の移動を hide の中で起こして再現する。
 */
class CaptureGeometryTest {

    private val atA = PixelRect(964, 819, 116, 116)
    private val atB = PixelRect(20, 819, 116, 116)
    private val imageWidth = 1080
    private val imageHeight = 2400
    private val background = 0xFF3A6EA5.toInt()
    private val black = 0xFF000000.toInt()

    /** 隠し終えた時刻（ns）。候補はこれ以降のものだけを見る。 */
    private val hideAt = 1_000_000_000L

    /**
     * 取得ボタンの代わり。押した瞬間に実座標と配置世代を読み、[hide] で隠れる。
     *
     * [duringHide] は「矩形を確定した後・隠し終える前」に起きること。旧い手順では hide の
     * 前の suspend の間に main のドラッグが走れたので、ここで再現する。
     */
    private class FakeButton(var rect: PixelRect) {
        var generation = 1L
        var duringHide: () -> Unit = {}
        var hidden = false
            private set

        fun geometry(width: Int, height: Int) =
            CaptureGeometry(rect, generation, width, height)

        fun moveTo(next: PixelRect) {
            rect = next
            generation += 1
        }

        fun hide(): Boolean {
            duringHide()
            hidden = true
            return true
        }
    }

    /** Controller と同じ順序を回す台。確定 → 同じターンで hide → 候補を1枚ずつ判定。 */
    private class Attempt(
        private val button: FakeButton,
        private val width: Int,
        private val height: Int,
        private val hideAt: Long,
    ) {
        /** 押した時点で確定した組。**hide の前**の値であることを確かめるのに使う。 */
        val geometry: CaptureGeometry

        private val gate: VerifiedFrameGate

        var saved: Long? = null
            private set

        var discarded = 0
            private set

        var moved = 0
            private set

        init {
            // 実座標と世代を確定する。ここから hide まで suspend しない。
            geometry = button.geometry(width, height)
            assertTrue(button.hide())
            gate = VerifiedFrameGate(CapturePlan.forPress(hideAt), geometry)
        }

        /** フレームが1枚届いた。Controller の `when` と同じ分岐で扱う。 */
        fun deliver(
            stamp: Long,
            pixels: IntArray?,
            layoutGeneration: Long = button.generation,
            imageWidth: Int = width,
            imageHeight: Int = height,
        ) {
            if (saved != null || moved > 0) return
            if (!gate.wants(stamp)) return
            when (gate.verdict(layoutGeneration, imageWidth, imageHeight, pixels)) {
                CandidateVerdict.SAMPLED -> saved = stamp
                CandidateVerdict.BURNED -> {
                    gate.discard(stamp)
                    discarded += 1
                }
                CandidateVerdict.MOVED -> moved += 1
            }
        }
    }

    /** 判定に使う矩形の画素。移動先の黒い印は矩形の外なので、この配列には現れない。 */
    private fun sampledPixels(color: Int, rect: PixelRect = atA): IntArray =
        IntArray(rect.pixelCount) { color }

    @Test
    fun movedButtonIsNeverSaved() {
        val button = FakeButton(atA)
        button.duringHide = { button.moveTo(atB) }
        val attempt = Attempt(button, imageWidth, imageHeight, hideAt)

        // 旧い手順で suspend を跨いでいた間に起きた移動。確定した組は A のまま。
        assertEquals(atA, attempt.geometry.rect)
        assertEquals(1L, attempt.geometry.layoutGeneration)

        // 候補は「A が非黒（ボタンはもう B にいる）・B に黒い印」。
        attempt.deliver(hideAt + 50_000_000, sampledPixels(background))

        assertNull("旧矩形が非黒でも、配置が変わった候補は保存しない", attempt.saved)
        assertEquals(1, attempt.moved)
        assertEquals(0, attempt.discarded)
    }

    @Test
    fun movedCandidateDoesNotRestartTheSameCapture() {
        val button = FakeButton(atA)
        button.duringHide = { button.moveTo(atB) }
        val attempt = Attempt(button, imageWidth, imageHeight, hideAt)

        attempt.deliver(hideAt + 50_000_000, sampledPixels(background))
        // 中止した後は、非黒の候補が来ても保存しない（同じ取得を続けない）。
        attempt.deliver(hideAt + 80_000_000, sampledPixels(background))

        assertNull(attempt.saved)
        assertEquals(1, attempt.moved)
    }

    @Test
    fun unchangedButtonWithCleanRectIsSaved() {
        val button = FakeButton(atA)
        val attempt = Attempt(button, imageWidth, imageHeight, hideAt)

        attempt.deliver(hideAt + 50_000_000, sampledPixels(background))

        assertEquals(hideAt + 50_000_000, attempt.saved)
        assertEquals(0, attempt.moved)
        assertEquals(0, attempt.discarded)
    }

    @Test
    fun blackMarkAtRecordedRectIsRetried() {
        val button = FakeButton(atA)
        val attempt = Attempt(button, imageWidth, imageHeight, hideAt)

        attempt.deliver(hideAt + 50_000_000, sampledPixels(black))

        assertNull("黒い印が残る候補は保存しない", attempt.saved)
        assertEquals(1, attempt.discarded)
        assertEquals(0, attempt.moved)

        // 次のフレームで消えていれば保存する。
        attempt.deliver(hideAt + 80_000_000, sampledPixels(background))
        assertEquals(hideAt + 80_000_000, attempt.saved)
    }

    @Test
    fun changedImageSizeIsNeverSaved() {
        // 回転で画像の寸法が変わった。同じ矩形でも座標の意味が変わるので保存しない。
        val button = FakeButton(atA)
        val attempt = Attempt(button, imageWidth, imageHeight, hideAt)

        attempt.deliver(hideAt + 50_000_000, sampledPixels(background), imageWidth = 2400, imageHeight = 1080)

        assertNull(attempt.saved)
        assertEquals(1, attempt.moved)
    }

    @Test
    fun rectOutsideImageIsNotSampled() {
        // 隠す前に読んだ矩形が画像の外にはみ出していたら、そもそも取得しない。
        val outside = PixelRect(1000, 2300, 116, 116)

        assertFalse(outside.fitsIn(imageWidth, imageHeight))
    }
}
