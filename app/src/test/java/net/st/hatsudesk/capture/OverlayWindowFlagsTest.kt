package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取得ボタンの窓のフラグ（[OverlayWindowFlags]）。
 *
 * `FLAG_SECURE` を足す目的は、ボタンを投影の絵（`VirtualDisplay` の複製）に入れないこと。
 * 効くかどうかは実機で判定するが、**足したことと、既存のフラグを落としていないこと**は
 * JVM で固定できる。
 *
 * テストは `android.*` を参照しない（mockable jar のスタブが初期化で例外を投げる）。
 * 期待値は android の公開定数の実値で、`android.jar` を `javap -constants` で読んだもの。
 * 値がずれていないかは、ここに直値で書いておくことで捕まえる。
 */
class OverlayWindowFlagsTest {

    /** `WindowManager.LayoutParams.FLAG_SECURE`。 */
    private val secure = 0x00002000

    /** `WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE`。 */
    private val notFocusable = 0x00000008

    /** `WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL`。 */
    private val notTouchModal = 0x00000020

    /** `WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN`。 */
    private val layoutInScreen = 0x00000100

    /** 自前の定数が android の値と一致している（ずれたら実機で別のフラグになる）。 */
    @Test
    fun constantsMatchAndroidValues() {
        assertEquals(secure, OverlayWindowFlags.SECURE)
        assertEquals(notFocusable, OverlayWindowFlags.NOT_FOCUSABLE)
        assertEquals(notTouchModal, OverlayWindowFlags.NOT_TOUCH_MODAL)
        assertEquals(layoutInScreen, OverlayWindowFlags.LAYOUT_IN_SCREEN)
    }

    /** 窓に `FLAG_SECURE` が付いている。これが実機で効けばボタンは投影に写らない。 */
    @Test
    fun windowFlagsContainSecure() {
        assertEquals(secure, OverlayWindowFlags.WINDOW_FLAGS and secure)
    }

    /**
     * 元からある3つは残っている。`FLAG_NOT_FOCUSABLE` を落とすとキー入力を奪い、
     * `FLAG_NOT_TOUCH_MODAL` を落とすとボタンの外のタッチが下のアプリへ抜けない。
     */
    @Test
    fun existingFlagsRemain() {
        assertTrue(OverlayWindowFlags.WINDOW_FLAGS and notFocusable != 0)
        assertTrue(OverlayWindowFlags.WINDOW_FLAGS and notTouchModal != 0)
        assertTrue(OverlayWindowFlags.WINDOW_FLAGS and layoutInScreen != 0)
    }
}
