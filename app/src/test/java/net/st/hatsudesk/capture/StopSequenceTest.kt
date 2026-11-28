package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 停止時の後片付け（PR #22 の major 指摘）。
 *
 * 1回目の停止で detach に失敗しても、2回目の `stop()` で外し直せなければならない。
 * `ProjectionSession.requestStop()` は停止済みで false を返すので、そこで return すると
 * 二度と外せない（停止中は `capture()` も先に return する）。
 *
 * [StopSequence] は本番の `CaptureController.stop()` と同じ組み立てで動かす:
 * 停止が成立したらセッションを解放し（その中で先にボタンを外す）、停止済みならボタンだけを外す。
 */
class StopSequenceTest {

    /** 本番（`CaptureController`）と同じ順序で停止を組み立てた小さな台。 */
    private class Harness {
        val session = ProjectionSession()
        val tries = mutableListOf<String>()
        var detachSucceeds = false
        var releases = 0

        val overlay = OverlayCleanup<String> { name ->
            tries += name
            detachSucceeds
        }

        val stop = StopSequence(session, overlay) {
            releases += 1
            // CaptureController.releaseLocked と同じで、投影より先にボタンを外す。
            overlay.release()
        }

        fun running() {
            session.requestConsent()
            session.consentGranted()
        }
    }

    @Test
    fun firstStopDetachesButtonAndReleasesSession() {
        val harness = Harness().apply {
            running()
            overlay.hold("button")
            detachSucceeds = true
        }

        assertTrue(harness.stop.stop())
        assertEquals(1, harness.releases)
        assertFalse(harness.overlay.isHeld)
        assertEquals(listOf("button"), harness.tries)
        assertFalse(harness.session.isRunning)
    }

    @Test
    fun secondStopRemovesButtonWhenFirstDetachFailed() {
        val harness = Harness().apply {
            running()
            overlay.hold("button")
            detachSucceeds = false
        }

        // 1回目: detach に失敗。セッションは停止し、ボタンの参照だけが残る。
        assertTrue(harness.stop.stop())
        assertEquals(1, harness.releases)
        assertTrue(harness.overlay.isHeld)
        assertEquals(listOf("button"), harness.tries)
        assertFalse(harness.session.isRunning)

        // 2回目: 停止済みなので requestStop は false。それでもボタンは外しにいく。
        harness.detachSucceeds = true
        assertFalse(harness.stop.stop())
        assertEquals(1, harness.releases)
        assertFalse(harness.overlay.isHeld)
        assertEquals(listOf("button", "button"), harness.tries)
    }

    @Test
    fun repeatedStopKeepsTryingWhileDetachFails() {
        val harness = Harness().apply {
            running()
            overlay.hold("button")
        }

        harness.stop.stop()
        harness.stop.stop()
        harness.stop.stop()

        // 1回目は解放、2回目以降は停止済み。外れるまで毎回試す。
        assertEquals(1, harness.releases)
        assertEquals(3, harness.tries.size)
        assertTrue(harness.overlay.isHeld)
    }

    @Test
    fun stopWhileIdleWithoutButtonDoesNotTouchWindow() {
        val harness = Harness()

        assertFalse(harness.stop.stop())
        assertEquals(0, harness.releases)
        assertTrue(harness.tries.isEmpty())
    }

    @Test
    fun screenShownAgainRemovesButtonLeftBehind() {
        // 停止で外せなかったボタンを、画面に戻ってきたとき（ON_RESUME）に外し直す。
        val harness = Harness().apply {
            running()
            overlay.hold("button")
        }
        harness.stop.stop()
        assertTrue(harness.overlay.isHeld)

        harness.detachSucceeds = true
        harness.stop.sweepIfStopped()

        assertFalse(harness.overlay.isHeld)
        assertEquals(listOf("button", "button"), harness.tries)
    }

    @Test
    fun screenShownWhileRunningLeavesButtonAlone() {
        // 投影中に画面へ戻ってきても、押そうとしているボタンを消さない。
        val harness = Harness().apply {
            running()
            overlay.hold("button")
            detachSucceeds = true
        }

        harness.stop.sweepIfStopped()

        assertTrue(harness.overlay.isHeld)
        assertTrue(harness.tries.isEmpty())
    }
}
