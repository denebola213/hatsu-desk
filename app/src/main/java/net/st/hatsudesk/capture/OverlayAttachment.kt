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
 * **聞き手（[Window.register]）の寿命は取得の出し入れではなくセッションに結び付ける。**
 * 取得はボタンを一瞬隠す（[detach]）が、隠している間も画面の回転・再配置は起こり得る。
 * ここで `unregister()` してしまうと、隠している間だけ回転の通知が止まり、取得は古い矩形の
 * まま判定を続ける。そのため [detach] は View を外すだけで、聞き手は [release]（セッション
 * 終了）まで残す。外せなかった View は画面に残っているので、聞き手も残す（次の [release] で
 * 外し直せる）。
 *
 * Android の型を持たないのは、失敗の順序を単体テストで再現するため。実際の操作は
 * [Window] の実装（`CaptureOverlayButton` の `WindowManager` / `DisplayManager` 呼び出し）が行う。
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

    /**
     * 聞き手を登録した（または登録を試みた）か。登録の試行中に例外が出ても true にして、
     * 片付けのときに `unregister()` を呼ぶ（半分だけ通った登録を残さない）。
     */
    private var listening = false

    /** 出ているつもりか。外せなかった場合は true のまま残る。 */
    val isAttached: Boolean get() = attached

    /** 画面の変化を受け取れる状態か。取得のため一瞬隠している間も true のまま。 */
    val isListening: Boolean get() = listening

    /** 出せたら true。失敗したら、出した View を必ず外しにいってから false。 */
    fun attach(): Boolean {
        if (attached) return true
        var added = false
        return try {
            window.addView()
            added = true
            listen()
            attached = true
            true
        } catch (error: Throwable) {
            // 登録に失敗しただけでも、出した View は必ず回収する。参照を失うと二度と消せない。
            // 回収できなかった場合は「出たまま」として残し、呼出側に再試行の機会を渡す。
            // 聞き手も、登録が半分だけ通っている可能性があるので降ろす。外せなかった場合は
            // 画面に残る View の位置を追えるよう、[release] まで残す。
            if (added) {
                val removed = detach()
                attached = !removed
                if (removed) unlisten()
            }
            false
        }
    }

    /**
     * View を外す。**聞き手は残す**（隠している間の回転を取りこぼさない）。
     * 外せたら true。何度呼んでも安全。外れなければ試行を繰り返し、
     * それでも駄目なら false を返して [isAttached] を true のまま残す。
     *
     * **外れたかを確かめて返す。** 失敗を握りつぶすと、呼出側は「隠せた」と信じて
     * ボタン入りの1枚を保存してしまう。
     */
    fun detach(): Boolean {
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

    /**
     * セッション終了。View を外せたら聞き手も降ろす。外せなければ、画面に残った View の
     * 位置を追えるよう聞き手は残し、次の [release] で外し直す。外れたかを返す。
     */
    fun release(): Boolean {
        val removed = detach()
        if (removed) unlisten()
        return removed
    }

    /** 登録を1回だけ行う。2回目以降（隠した後の出し直し）は何もしない。 */
    private fun listen() {
        if (listening) return
        // 先に印を立てる。register() が途中で落ちても、片付けで unregister() を呼べるようにする。
        listening = true
        window.register()
    }

    private fun unlisten() {
        if (!listening) return
        listening = false
        window.unregister()
    }

    private companion object {
        /** 外れないまま残る端末でも、取得の待ち時間を大きくしない回数。 */
        const val REMOVE_ATTEMPTS = 3
    }
}
