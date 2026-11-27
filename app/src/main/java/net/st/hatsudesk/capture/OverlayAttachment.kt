package net.st.hatsudesk.capture

/**
 * 取得ボタンをウィンドウに出し入れする段取り。**失敗しても参照を失わない。**
 *
 * `WindowManager.addView` の後に `DisplayListener` の登録が失敗すると、View だけが画面に
 * 残ったまま呼出側が参照を失う（停止しても消せない）。`removeView` 自体も失敗し得るので、
 * 成否を返し、呼出側（取得）が「隠せていない絵は保存しない」と決められるようにする。
 *
 * **外れたかは窓の状態で確かめる。** `removeViewImmediate()` が例外を投げずに戻っても、
 * View が実際に外れたとは限らない。外れていなければ短い間隔で数回試し、それでも外れなければ
 * [attached] を true のまま残す（＝呼出側は参照を捨てず、停止時や次の取得時に再試行できる）。
 *
 * Android の型を持たないのは、失敗の順序を単体テストで再現するため。実際の操作は
 * [Window] の実装（`CaptureOverlayButton` の `WindowManager` 呼び出し）が行う。
 */
internal class OverlayAttachment(
    private val window: Window,
    /** 外れなかったときに試す回数。 */
    private val attempts: Int = REMOVE_ATTEMPTS,
    /** 試行の間の待ち。本番は短く sleep し、テストは数えるだけ。 */
    private val pause: () -> Unit = {},
) {

    /** ウィンドウ操作。失敗は例外で知らせ、[removeView] は実際に外れたかを返す。 */
    interface Window {
        /** 出せなければ例外。 */
        fun addView()

        /** 聞き手を登録できなければ例外。 */
        fun register()

        /** 登録を外す。失敗しても投げない（外れていなくても害が小さい）。 */
        fun unregister()

        /** 画面に載っているか。View の親・`isAttachedToWindow`・`windowToken` で見る。 */
        fun isOnScreen(): Boolean

        /**
         * 1回外しにいく。**実際に外れたかを返す。** 例外は「外れていない」に倒す。
         * 外れていなければ [isOnScreen] は true のままになる。
         */
        fun removeView(): Boolean
    }

    /** 出ているか。実際に外れたことを確かめたときだけ false になる。 */
    private var attached = false

    /** 出ているつもりか。外せなかった場合は true のまま残る。 */
    val isAttached: Boolean get() = attached

    /** 出せたら true。失敗したら、出した View を必ず外しにいってから false。 */
    fun attach(): Boolean {
        if (attached) return true
        var added = false
        return try {
            window.addView()
            added = true
            window.register()
            attached = true
            true
        } catch (error: Throwable) {
            // 登録に失敗しただけでも、出した View は必ず回収する。参照を失うと二度と消せない。
            // 回収できなかった場合は「出たまま」として残し、呼出側に再試行の機会を渡す。
            if (added) attached = !detach()
            false
        }
    }

    /**
     * 外せたら true。何度呼んでも安全。外れなければ試行を繰り返し、
     * それでも駄目なら false を返して [isAttached] を true のまま残す。
     *
     * **外れたかを確かめて返す。** 失敗を握りつぶすと、呼出側は「隠せた」と信じて
     * ボタン入りの1枚を保存してしまう。
     */
    fun detach(): Boolean {
        window.unregister()
        if (!window.isOnScreen()) {
            attached = false
            return true
        }
        for (attempt in 1..attempts) {
            val removed = try {
                window.removeView()
            } catch (error: Throwable) {
                false
            }
            if (removed || !window.isOnScreen()) {
                attached = false
                return true
            }
            if (attempt < attempts) pause()
        }
        attached = true
        return false
    }

    private companion object {
        /** 外れないまま残る端末でも、取得の待ち時間を大きくしない回数。 */
        const val REMOVE_ATTEMPTS = 3
    }
}
