package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取得ボタンの出し入れ（PR #22 の指摘 3）。
 *
 * `addView` の後に listener の登録が失敗したとき、View を外さずに諦めると、
 * 停止しても消せないボタンが画面に残る。`removeView` の失敗を握りつぶすと、
 * 呼出側は「隠せた」と信じてボタン入りの1枚を保存してしまう。
 */
class OverlayAttachmentTest {

    private class FakeWindow : OverlayAttachment.Window {
        var adds = 0
        var registers = 0
        var unregisters = 0
        var removes = 0

        var onAdd: () -> Unit = {}
        var onRegister: () -> Unit = {}
        var removeResult = true

        override fun addView() {
            adds += 1
            onAdd()
        }

        override fun register() {
            registers += 1
            onRegister()
        }

        override fun unregister() {
            unregisters += 1
        }

        override fun removeView(): Boolean {
            removes += 1
            return removeResult
        }
    }

    @Test
    fun `listener の登録に失敗しても View を外す`() {
        val window = FakeWindow().apply {
            onRegister = { throw IllegalStateException("登録できない") }
        }
        val attachment = OverlayAttachment(window)

        assertFalse(attachment.attach())

        // 出したまま参照を失わない。必ず回収する。
        assertEquals(1, window.adds)
        assertEquals(1, window.removes)
        assertEquals(1, window.unregisters)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun `出せなかった View は外さない`() {
        val window = FakeWindow().apply {
            onAdd = { throw IllegalStateException("出せない") }
        }
        val attachment = OverlayAttachment(window)

        assertFalse(attachment.attach())

        assertEquals(1, window.adds)
        assertEquals(0, window.removes)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun `外せたら true`() {
        val window = FakeWindow()
        val attachment = OverlayAttachment(window)
        assertTrue(attachment.attach())
        assertTrue(attachment.isAttached)

        assertTrue(attachment.detach())
        assertEquals(1, window.removes)
        assertFalse(attachment.isAttached)
    }

    @Test
    fun `外せなかったら false を返す`() {
        val window = FakeWindow().apply { removeResult = false }
        val attachment = OverlayAttachment(window)
        assertTrue(attachment.attach())

        // 呼出側はこれを見て保存を中止する（ボタン入りの1枚を残さない）。
        assertFalse(attachment.detach())
    }

    @Test
    fun `外せなかった後でも、外れるまで呼び直せる`() {
        val window = FakeWindow().apply { removeResult = false }
        val attachment = OverlayAttachment(window)
        assertTrue(attachment.attach())
        assertFalse(attachment.detach())

        // 停止時に呼び直す経路。ここで諦めるとボタンが残る。
        window.removeResult = true
        assertTrue(attachment.detach())
        assertEquals(2, window.removes)
    }

    @Test
    fun `二重に出さない`() {
        val window = FakeWindow()
        val attachment = OverlayAttachment(window)
        assertTrue(attachment.attach())
        assertTrue(attachment.attach())
        assertEquals(1, window.adds)
        assertEquals(1, window.registers)
    }
}
