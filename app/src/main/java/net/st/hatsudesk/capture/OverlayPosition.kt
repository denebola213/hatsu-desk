package net.st.hatsudesk.capture

/**
 * オーバーレイのボタンの左上の位置（px、画面の左上が原点）。
 *
 * `android.*` を使わないので JVM の単体テストで回せる（[ProjectionSession] と同じ扱い）。
 * 「回転しても画面の外に出ない」「端に寄る」という2つの約束を、ここで型として持つ。
 */
data class OverlayPosition(val x: Int, val y: Int) {

    /** 画面の中に収める。ボタンが画面より大きい場合は左上に寄せる。 */
    fun clamped(
        buttonWidth: Int,
        buttonHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): OverlayPosition = OverlayPosition(
        x.coerceIn(0, (screenWidth - buttonWidth).coerceAtLeast(0)),
        y.coerceIn(0, (screenHeight - buttonHeight).coerceAtLeast(0)),
    )

    /**
     * 左右の近い端へ寄せる。上下は寄せない（上下の帯はゲームの操作に使われるので、
     * 利用者が置いた高さを尊重する）。
     */
    fun snapped(buttonWidth: Int, screenWidth: Int): OverlayPosition {
        val right = (screenWidth - buttonWidth).coerceAtLeast(0)
        val distanceToLeft = x
        val distanceToRight = right - x
        return copy(x = if (distanceToRight < distanceToLeft) right else 0)
    }

    /**
     * 画面の大きさが変わった（回転）ときに追従する。中心の相対位置を保ち、元が端に
     * 付いていたなら新しい画面の同じ端へ付けたままにする。結果は必ず画面の中。
     */
    fun reflow(
        oldScreenWidth: Int,
        oldScreenHeight: Int,
        newScreenWidth: Int,
        newScreenHeight: Int,
        buttonWidth: Int,
        buttonHeight: Int,
    ): OverlayPosition {
        if (oldScreenWidth <= 0 || oldScreenHeight <= 0) {
            return clamped(buttonWidth, buttonHeight, newScreenWidth, newScreenHeight)
        }
        val atLeft = x <= 0
        val atRight = x >= oldScreenWidth - buttonWidth
        val centerX = (x + buttonWidth / 2) * newScreenWidth / oldScreenWidth
        val centerY = (y + buttonHeight / 2) * newScreenHeight / oldScreenHeight
        val moved = OverlayPosition(centerX - buttonWidth / 2, centerY - buttonHeight / 2)
            .clamped(buttonWidth, buttonHeight, newScreenWidth, newScreenHeight)
        val right = (newScreenWidth - buttonWidth).coerceAtLeast(0)
        return when {
            atLeft -> moved.copy(x = 0)
            atRight -> moved.copy(x = right)
            else -> moved
        }
    }
}
