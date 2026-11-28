package net.st.hatsudesk.capture

/**
 * 取得ボタンの窓（`WindowManager.LayoutParams`）に渡すフラグ。
 *
 * **android の定数をここで読まない。** 値は android の公開定数と同じだが、自前で持つ。
 * JVM の単体テストから `android.*` のクラスを触ると、mockable jar のスタブが初期化の
 * 途中で `RuntimeException: Stub!` を投げる（`CaptureOverlayButton` の `Color.argb` と
 * 同じ理由）。窓のフラグ自体は実機でしか意味を持たないが、「`FLAG_SECURE` を足した・
 * 既存のフラグを落としていない」はテストで固定しておきたい。値だけを android 非依存の
 * 場所に置けば、両方立つ。
 *
 * 値の出どころは `android.view.WindowManager.LayoutParams`（API 37.2 の android.jar を
 * `javap -constants` で読んだ実値）。
 *
 * ```
 * FLAG_NOT_FOCUSABLE    = 0x00000008
 * FLAG_NOT_TOUCH_MODAL  = 0x00000020
 * FLAG_LAYOUT_IN_SCREEN = 0x00000100
 * FLAG_SECURE           = 0x00002000
 * ```
 *
 * ここを変えるときは、android 側の値と意味を必ず突き合わせること。
 * `FLAG_SECURE` を落とすと保存した PNG にボタンが写る（実機で判定中）。
 */
internal object OverlayWindowFlags {

    /** `WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE`。キー入力もフォーカスも奪わない。 */
    const val NOT_FOCUSABLE: Int = 0x00000008

    /** `WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL`。ボタンの外のタッチは下へ抜ける。 */
    const val NOT_TOUCH_MODAL: Int = 0x00000020

    /** `WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN`。座標を画面基準にする。 */
    const val LAYOUT_IN_SCREEN: Int = 0x00000100

    /**
     * `WindowManager.LayoutParams.FLAG_SECURE`。この窓をスクリーンショットと
     * `MediaProjection` の出力から外す。効けば投影の絵にボタンは写らない。
     */
    const val SECURE: Int = 0x00002000

    /**
     * 窓に渡すフラグ。**既存の3つを消さずに `SECURE` を足す。**
     * ここを丸ごと差し替えると、フォーカスやタッチの扱いが変わってゲームの操作を壊す。
     */
    val WINDOW_FLAGS: Int = NOT_FOCUSABLE or NOT_TOUCH_MODAL or LAYOUT_IN_SCREEN or SECURE
}
