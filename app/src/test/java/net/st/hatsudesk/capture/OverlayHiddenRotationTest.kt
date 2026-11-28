package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **隠している間に画面が回ったら保存しない**（PR #22 の指摘 1）。
 *
 * 取得はボタンを一瞬隠すが、隠している間も回転・再配置は起こり得る。旧い `detach()` は
 * 先頭で `DisplayManager.unregisterDisplayListener()` を呼んでいたので、その間だけ配置の
 * 変化が届かず、取得は押した時点の矩形のまま「ボタンが消えた」と判断して保存していた。
 *
 * ここでは実物の [OverlayAttachment] に偽の窓を挿し、回転の通知が「聞き手が生きている
 * ときだけ」届くようにする（実機と同じ）。隠したまま回転させ、`layoutGeneration` が
 * 進んで [CandidateVerdict.MOVED] になることを固定する。聞き手を隠すたびに降ろす実装に
 * 戻すと、回転が届かず `SAMPLED` になってこのテストが落ちる。
 */
class OverlayHiddenRotationTest {

    private val rect = PixelRect(964, 819, 116, 116)
    private val imageWidth = 1080
    private val imageHeight = 2400
    private val clean = IntArray(rect.pixelCount) { 0xFF3A6EA5.toInt() }

    /** 実物の [OverlayAttachment] を、偽の窓と偽の表示通知に繋いだもの。 */
    private class FakeOverlay : OverlayAttachment.Window {
        private val attachment = OverlayAttachment(this, attempts = 1, pause = {})

        var listening = false
            private set
        private var onScreen = false

        /** 配置が変わるたびに増える番号。[CaptureOverlayButton] と同じ役割。 */
        var layoutGeneration = 0L
            private set

        override fun addView() {
            onScreen = true
            layoutGeneration += 1
        }

        override fun register() {
            listening = true
        }

        override fun unregister() {
            listening = false
        }

        override fun isOnScreen(): Boolean = onScreen

        override fun removeView(): Boolean {
            onScreen = false
            return true
        }

        /** 画面が回った。聞き手が降りていれば通知は来ない（実機と同じ）。 */
        fun rotate() {
            if (!listening) return
            layoutGeneration += 1
        }

        fun show() = attachment.attach()

        fun hide() = attachment.detach()

        fun release() = attachment.release()
    }

    @Test
    fun rotationWhileHiddenIsNeverSaved() {
        val overlay = FakeOverlay()
        assertTrue(overlay.show())
        // 押した瞬間に確定する組（実 View の座標と配置世代）。
        val geometry = CaptureGeometry(rect, overlay.layoutGeneration, imageWidth, imageHeight)

        // 取得のため隠す。まだ聞き手は生きている。
        assertTrue(overlay.hide())
        assertTrue("隠している間も画面の変化を受け取れること", overlay.listening)

        // 隠している間に回転した。
        overlay.rotate()
        assertTrue(geometry.layoutGeneration < overlay.layoutGeneration)

        val gate = VerifiedFrameGate(CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000), geometry)
        val verdict = gate.verdict(overlay.layoutGeneration, imageWidth, imageHeight, clean)

        // 矩形が非黒でも、配置が変わった候補は保存しない。
        assertEquals(CandidateVerdict.MOVED, verdict)
    }

    @Test
    fun rotationAfterReleaseIsNotExpected() {
        // 停止した後（セッション終了）は聞き手を降ろす。以後の回転は追わない。
        val overlay = FakeOverlay()
        assertTrue(overlay.show())
        val before = overlay.layoutGeneration

        assertTrue(overlay.release())
        assertFalse(overlay.listening)

        overlay.rotate()
        assertEquals(before, overlay.layoutGeneration)
    }

    @Test
    fun noRotationWhileHiddenKeepsTheGeometry() {
        // 対照。隠したまま回転しなければ、同じ組のままなので保存してよい。
        val overlay = FakeOverlay()
        assertTrue(overlay.show())
        val geometry = CaptureGeometry(rect, overlay.layoutGeneration, imageWidth, imageHeight)

        assertTrue(overlay.hide())

        val gate = VerifiedFrameGate(CapturePlan.forPress(hideCompletedAtNanos = 1_000_000_000), geometry)
        assertEquals(
            CandidateVerdict.SAMPLED,
            gate.verdict(overlay.layoutGeneration, imageWidth, imageHeight, clean),
        )
    }
}
