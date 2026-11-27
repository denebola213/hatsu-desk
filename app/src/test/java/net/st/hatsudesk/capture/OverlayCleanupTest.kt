package net.st.hatsudesk.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画面に残ったボタンの持ち主（PR #22 の major 指摘の土台）。
 *
 * `removeViewImmediate()` が外れないことがあるので、外れたと確認できるまで参照を捨てない。
 * 捨てると、画面に残ったボタンを二度と消せない。
 */
class OverlayCleanupTest {

    private val tried = mutableListOf<String>()
    private var detachResult = false
    private var detachThrows = false
    private val overlay = OverlayCleanup<String> { name ->
        tried += name
        if (detachThrows) throw IllegalStateException("外せない") else detachResult
    }

    @Test
    fun releaseWithoutButtonDoesNothing() {
        assertTrue(overlay.release())
        assertTrue(tried.isEmpty())
        assertFalse(overlay.isHeld)
    }

    @Test
    fun detachFailureKeepsReference() {
        overlay.hold("ボタン")
        detachResult = false

        assertFalse(overlay.release())
        assertTrue(overlay.isHeld)
        assertTrue(overlay.peek() == "ボタン")
    }

    @Test
    fun detachExceptionCountsAsFailure() {
        overlay.hold("ボタン")
        detachThrows = true

        assertFalse(overlay.release())
        assertTrue(overlay.isHeld)
    }

    @Test
    fun successfulDetachClearsReference() {
        overlay.hold("ボタン")
        detachResult = true

        assertTrue(overlay.release())
        assertFalse(overlay.isHeld)

        // 参照を手放した後は、外しにいかない。
        assertTrue(overlay.release())
        assertTrue(tried == listOf("ボタン"))
    }

    @Test
    fun releaseCanBeRetriedAfterFailure() {
        overlay.hold("ボタン")
        detachResult = false
        assertFalse(overlay.release())

        detachResult = true
        assertTrue(overlay.release())
        assertFalse(overlay.isHeld)
        assertTrue(tried == listOf("ボタン", "ボタン"))
    }
}
