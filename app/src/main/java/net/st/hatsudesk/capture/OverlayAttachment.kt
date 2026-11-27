package net.st.hatsudesk.capture

/**
 * 取得ボタンをウィンドウに出し入れする段取り。**失敗しても View を残さない。**
 *
 * `WindowManager.addView` の後に `DisplayListener` の登録が失敗すると、View だけが画面に
 * 残ったまま呼出側が参照を失う（停止しても消せない）。`removeView` 自体も失敗し得るので、
 * 成否を返し、呼出側（取得）が「隠せていない絵は保存しない」と決められるようにする。
 *
 * Android の型を持たないのは、失敗の順序を単体テストで再現するため。実際の操作は
 * [Window] の実装（`CaptureOverlayButton` の `WindowManager` 呼び出し）が行う。
 */
internal class OverlayAttachment(private val window: Window) {

    /** ウィンドウ操作。失敗は例外で知らせ、[removeView] は実際に外れたかを返す。 */
    interface Window {
        /** 出せなければ例外。 */
        fun addView()

        /** 聞き手を登録できなければ例外。 */
        fun register()

        /** 登録を外す。失敗しても投げない（外れていなくても害が小さい）。 */
        fun unregister()

        /** 画面から外せたら true。例外も「外れていない」に倒す。 */
        fun removeView(): Boolean
    }

    /** 出ているか。出ていると信じているだけなので、判断は [Window.removeView] の戻り値で行う。 */
    private var attached = false

    /** 出ているつもりか。実際に載っているかは [Window.removeView] で確かめる。 */
    val isAttached: Boolean get() = attached

    /** 出せたら true。失敗したら、出した View を必ず外してから false。 */
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
            if (added) detach()
            false
        }
    }

    /**
     * 外せたら true。何度呼んでも安全。
     *
     * **外れたかを確かめて返す。** 失敗を握りつぶすと、呼出側は「隠せた」と信じて
     * ボタン入りの1枚を保存してしまう。
     */
    fun detach(): Boolean {
        attached = false
        window.unregister()
        return window.removeView()
    }
}
