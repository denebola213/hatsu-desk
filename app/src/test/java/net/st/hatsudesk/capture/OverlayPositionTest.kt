package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * オーバーレイのボタンの位置。受入条件のうち「回転しても画面外に出ない」と
 * 「端に寄る」をここで見る。ドラッグそのもの（タッチ）は実機で確かめる。
 */
class OverlayPositionTest {

    private val button = 120
    private val portraitWidth = 1080
    private val portraitHeight = 2410
    private val landscapeWidth = 2410
    private val landscapeHeight = 1080

    @Test
    fun `右寄りなら右端へ寄る`() {
        val snapped = OverlayPosition(800, 500).snapped(button, portraitWidth)
        assertEquals(portraitWidth - button, snapped.x)
        assertEquals(500, snapped.y)
    }

    @Test
    fun `左寄りなら左端へ寄る`() {
        val snapped = OverlayPosition(200, 500).snapped(button, portraitWidth)
        assertEquals(0, snapped.x)
    }

    @Test
    fun `端からの距離が同じなら左へ寄る`() {
        val middle = (portraitWidth - button) / 2
        assertEquals(0, OverlayPosition(middle, 500).snapped(button, portraitWidth).x)
    }

    @Test
    fun `ドラッグで画面の外へ出ても中に収まる`() {
        val pushed = OverlayPosition(-500, -900).clamped(button, button, portraitWidth, portraitHeight)
        assertEquals(0, pushed.x)
        assertEquals(0, pushed.y)

        val pulled = OverlayPosition(5000, 9000).clamped(button, button, portraitWidth, portraitHeight)
        assertEquals(portraitWidth - button, pulled.x)
        assertEquals(portraitHeight - button, pulled.y)
    }

    @Test
    fun `縦から横へ回転しても画面の中に残る`() {
        // 縦の右端・上寄り（初期位置）。
        val before = OverlayPosition(portraitWidth - button, (portraitHeight * 0.34f).toInt())
        val after = before.reflow(
            oldScreenWidth = portraitWidth,
            oldScreenHeight = portraitHeight,
            newScreenWidth = landscapeWidth,
            newScreenHeight = landscapeHeight,
            buttonWidth = button,
            buttonHeight = button,
        )
        assertTrue("x=${after.x}", after.x in 0..(landscapeWidth - button))
        assertTrue("y=${after.y}", after.y in 0..(landscapeHeight - button))
        // 端に付けていたものは、新しい画面でも同じ端に付いたまま。
        assertEquals(landscapeWidth - button, after.x)
    }

    @Test
    fun `横から縦へ戻しても右端に残る`() {
        val before = OverlayPosition(landscapeWidth - button, 300)
        val after = before.reflow(
            oldScreenWidth = landscapeWidth,
            oldScreenHeight = landscapeHeight,
            newScreenWidth = portraitWidth,
            newScreenHeight = portraitHeight,
            buttonWidth = button,
            buttonHeight = button,
        )
        assertEquals(portraitWidth - button, after.x)
        assertTrue(after.y in 0..(portraitHeight - button))
    }

    @Test
    fun `中央に置いたものは相対位置を保つ`() {
        val before = OverlayPosition((portraitWidth - button) / 2, (portraitHeight - button) / 2)
        val after = before.reflow(
            oldScreenWidth = portraitWidth,
            oldScreenHeight = portraitHeight,
            newScreenWidth = landscapeWidth,
            newScreenHeight = landscapeHeight,
            buttonWidth = button,
            buttonHeight = button,
        )
        assertEquals(landscapeWidth / 2 - button / 2, after.x)
        assertEquals(landscapeHeight / 2 - button / 2, after.y)
    }

    @Test
    fun `画面がボタンより小さくても原点に収まる`() {
        val after = OverlayPosition(400, 400).reflow(
            oldScreenWidth = portraitWidth,
            oldScreenHeight = portraitHeight,
            newScreenWidth = 60,
            newScreenHeight = 60,
            buttonWidth = button,
            buttonHeight = button,
        )
        assertEquals(0, after.x)
        assertEquals(0, after.y)
    }

    @Test
    fun `前の画面の大きさが不明でも落ちない`() {
        val after = OverlayPosition(10, 10).reflow(
            oldScreenWidth = 0,
            oldScreenHeight = 0,
            newScreenWidth = landscapeWidth,
            newScreenHeight = landscapeHeight,
            buttonWidth = button,
            buttonHeight = button,
        )
        assertEquals(10, after.x)
        assertEquals(10, after.y)
    }

    @Test
    fun `ずれていない位置は動かさない`() {
        val position = OverlayPosition(300, 700)
        val after = position.reflow(portraitWidth, portraitHeight, portraitWidth, portraitHeight, button, button)
        assertEquals(position, after)
        assertFalse(after.x == 0)
    }
}
