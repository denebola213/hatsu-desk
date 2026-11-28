package net.st.hatsudesk.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `FLAG_SECURE` が投影フレームに残す黒い矩形を、ボタンがまだ写っている印として判定する。
 *
 * 実機ではボタン窓の矩形が完全な黒 (0, 0, 0) になる。下の画面との平均絶対差は使わず、
 * 保存候補そのものの明るさだけで決める。したがって下の絵が動いても誤って保存しない。
 */
class OverlayPixelCheckTest {

    private val rect = PixelRect(964, 819, 116, 116)

    private fun plain(argb: Int): IntArray = IntArray(rect.pixelCount) { argb }

    private fun gray(value: Int): Int = (0xFF shl 24) or (value shl 16) or (value shl 8) or value

    @Test
    fun blackCandidateIsRejected() {
        // 押下時の矩形が何色でも、候補が黒なら「まだ写っている」と判断する。
        val check = OverlayPixelCheck(rect, plain(0xFFFFFFFF.toInt()))

        assertFalse(check.isButtonGone(plain(0xFF000000.toInt())))
    }

    @Test
    fun nearBlackCandidateBelowThresholdIsRejected() {
        val check = OverlayPixelCheck(rect, plain(0xFFFFFFFF.toInt()))

        assertFalse(check.isButtonGone(plain(gray(2))))
    }

    @Test
    fun whiteCandidateIsAccepted() {
        // 押下時と同じ白でも保存する。前フレームとの差には依存しない。
        val check = OverlayPixelCheck(rect, plain(0xFFFFFFFF.toInt()))

        assertTrue(check.isButtonGone(plain(0xFFFFFFFF.toInt())))
    }

    @Test
    fun colorCandidateIsAccepted() {
        val color = 0xFF3A6EA5.toInt()
        val check = OverlayPixelCheck(rect, plain(color))

        assertTrue(check.isButtonGone(plain(color)))
    }

    @Test
    fun darkGrayCandidateIsAccepted() {
        // 真っ黒ではない暗い背景を、黒い印と混同しない。
        val check = OverlayPixelCheck(rect, plain(gray(16)))

        assertTrue(check.isButtonGone(plain(gray(16))))
    }

    @Test
    fun thresholdBoundaryIsThree() {
        // 平均輝度 2 は黒い印、3 は非黒。PNG は可逆なので圧縮による補正は入れない。
        assertFalse(
            OverlayPixelCheck(rect, plain(0xFFFFFFFF.toInt()))
                .isButtonGone(plain(gray(2))),
        )
        assertTrue(
            OverlayPixelCheck(rect, plain(gray(3)))
                .isButtonGone(plain(gray(3))),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedPixelCountIsRejected() {
        // 矩形がずれた候補を黙って「消えた」と判断しない。
        OverlayPixelCheck(rect, plain(gray(32))).isButtonGone(IntArray(10))
    }

    @Test(expected = IllegalArgumentException::class)
    fun baselineMustMatchRect() {
        OverlayPixelCheck(rect, IntArray(4))
    }
}
