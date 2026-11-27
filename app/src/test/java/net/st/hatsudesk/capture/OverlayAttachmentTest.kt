package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取得ボタンの出し入れ（PR #22 の指摘 3）。
 *
 * `removeViewImmediate()` は例外を投げずに戻っても、View が実際に外れたとは限らない。
 * 外れたかを View の状態で確かめ、外れていなければ短い間隔で試し直す。
 * それでも外れなければ「出たまま」として参照を残し、停止時や次の取得で再試行できるようにする。
 * ここでは窓を偽物にして、その順序を機械的に再現する。
 */
class OverlayAttachmentTest {

    /** 画面に載っているかを自分で持ち、remove が何もしない状況も再現できる窓。 */
    private class FakeWindow : OverlayAttachment.Window {
        var onScreen = false
        var adds = 0
        var registers = 0
        var unregisters = 0
        var removes = 0
        var pauses = 0

        /** false にすると removeView は例外を投げずに何もしない。 */
        var removeWorks = true

        var onAdd: () -> Unit = {}
        var onRegister: () -> Unit = {}

        override fun addView() {
            adds += 1
            onAdd()
            onScreen = true
        }

        override fun register() {
            registers += 1
            onRegister()
        }

        override fun unregister() {
            unregisters += 1
        }

        override fun isOnScreen(): Boolean = onScreen

        override fun removeView(): Boolean {
            removes += 1
            if (removeWorks) onScreen = false
            return !onScreen
        }
    }

    private fun attachment(window: FakeWindow): OverlayAttachment =
        OverlayAttachment(window, attempts = 3, pause = { window.pauses += 1 })

    @Test
    fun registerFailureStillRemovesView() {
        val window = FakeWindow().apply {
            onRegister = { throw IllegalStateException("登録できない") }
        }
        val attachment = attachment(window)

        assertFalse(attachment.attach())

        // 出したまま参照を失わない。必ず回収する。
        assertEquals(1, window.adds)
        assertEquals(1, window.removes)
        assertEquals(1, window.unregisters)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun failedAddDoesNotRemove() {
        val window = FakeWindow().apply {
            onAdd = { throw IllegalStateException("出せない") }
        }
        val attachment = attachment(window)

        assertFalse(attachment.attach())

        assertEquals(1, window.adds)
        assertEquals(0, window.removes)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun detachReturnsTrueWhenRemoved() {
        val window = FakeWindow()
        val attachment = attachment(window)
        assertTrue(attachment.attach())
        assertTrue(attachment.isAttached)

        assertTrue(attachment.detach())
        assertEquals(1, window.removes)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun removeThatDoesNothingIsNotDetached() {
        // 正常に戻ったように見えるが、View は親に付いたまま。
        val window = FakeWindow().apply { removeWorks = false }
        val attachment = attachment(window)
        assertTrue(attachment.attach())

        assertFalse(attachment.detach())

        // 数回試し、試行の間だけ待つ。
        assertEquals(3, window.removes)
        assertEquals(2, window.pauses)
        // View はまだ画面にあり、参照（出ているという認識）も残る。
        assertTrue(window.onScreen)
        assertTrue(attachment.isAttached)
    }

    @Test
    fun detachCanBeRetriedUntilRemoved() {
        val window = FakeWindow().apply { removeWorks = false }
        val attachment = attachment(window)
        assertTrue(attachment.attach())
        assertFalse(attachment.detach())

        // 停止時や次の取得で呼び直す経路。ここで諦めるとボタンが残る。
        window.removeWorks = true
        assertTrue(attachment.detach())
        assertEquals(4, window.removes)
        assertFalse(window.onScreen)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun registerFailureKeepsReferenceWhenRemoveFails() {
        val window = FakeWindow().apply {
            onRegister = { throw IllegalStateException("登録できない") }
            removeWorks = false
        }
        val attachment = attachment(window)

        // 出せなかったことにはなるが、View は画面に残っている。
        assertFalse(attachment.attach())
        assertTrue(window.onScreen)
        assertTrue(attachment.isAttached)

        // 参照が残っているので、後から外せる。
        window.removeWorks = true
        assertTrue(attachment.detach())
        assertFalse(window.onScreen)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun alreadyDetachedDoesNotCallRemove() {
        val window = FakeWindow()
        val attachment = attachment(window)
        assertTrue(attachment.attach())

        // OS 側で View が外れた（画面の消失など）。
        window.onScreen = false
        assertTrue(attachment.detach())

        assertEquals(0, window.removes)
        assertEquals(1, window.unregisters)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun attachIsIdempotent() {
        val window = FakeWindow()
        val attachment = attachment(window)
        assertTrue(attachment.attach())
        assertTrue(attachment.attach())
        assertEquals(1, window.adds)
        assertEquals(1, window.registers)
    }
}
