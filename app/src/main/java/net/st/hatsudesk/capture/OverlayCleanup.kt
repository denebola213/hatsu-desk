package net.st.hatsudesk.capture

/**
 * 画面に残ったオーバーレイの持ち主。**外せるまで参照を捨てない。**
 *
 * 停止時に `WindowManager.removeViewImmediate()` が外れないことがある。そこで参照を捨てると、
 * 画面に残ったボタンを二度と消せない。ここは「外れた」と確認できたときだけ参照を手放し、
 * 外せなければ保持して次の [release] で試し直す。
 *
 * Android の型を持たないのは、初回失敗→2回目成功の順序を JVM の単体テストで固定するため
 * （[CaptureController.stop] は停止済みでも [release] を呼ぶ。詳細は [StopSequence]）。
 */
internal class OverlayCleanup<T : Any>(
    /** 1回外しにいく。実際に外れたかを返す。例外は「外れていない」に倒す。 */
    private val detach: (T) -> Boolean,
) {

    private var held: T? = null

    /** 画面に残っている（かもしれない）ボタンを持つ。 */
    val isHeld: Boolean get() = held != null

    /** いまのボタン。位置の追従など、外さずに触りたいときだけ使う。 */
    fun peek(): T? = held

    /** 出すことに成功したボタンを預ける。 */
    fun hold(button: T) {
        held = button
    }

    /**
     * 外しにいく。外れたら true（参照を手放す）。外れなければ false（参照は残す）。
     * 持っていなければ何もせず true。何度呼んでも安全。
     */
    fun release(): Boolean {
        val button = held ?: return true
        val removed = try {
            detach(button)
        } catch (error: Throwable) {
            false
        }
        if (removed) held = null
        return removed
    }
}
