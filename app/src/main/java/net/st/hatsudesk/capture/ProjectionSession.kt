package net.st.hatsudesk.capture

/**
 * 投影セッションの状態だけを持つ。`android.*` を一切使わないので JVM の単体テストで回せる。
 *
 * ```
 * IDLE --requestConsent--> AWAITING_CONSENT --consentGranted--> RUNNING
 *                            |                                    |
 *                       consentDenied                        requestStop / onSystemStop
 *                            v                                    v
 *                          IDLE                                 IDLE
 * ```
 *
 * **どの遷移も、受け付けない要求は false を返して状態を変えない。** 二重 start、二重 stop、
 * 同意ダイアログを閉じた後に遅れて届いた結果、OS 側の失効と利用者の停止が重なった場合で
 * 落ちないようにするため。状態を1つのクラスに閉じ、リソースの解放は true が返ったときだけ
 * 1回行う、という約束で呼出側（[CaptureController]）と合わせている。
 */
class ProjectionSession {

    enum class State {
        /** 投影していない。リソースは持っていない。 */
        IDLE,

        /** 同意ダイアログを出した。まだ投影は始まっていない。 */
        AWAITING_CONSENT,

        /** 投影中。VirtualDisplay と ImageReader が動いている。 */
        RUNNING,
    }

    var state: State = State.IDLE
        private set

    val isRunning: Boolean get() = state == State.RUNNING

    /**
     * 投影の開始要求。同意ダイアログを出してよいときだけ true。
     * すでに投影中・同意待ちなら false（二重 start でダイアログを重ねない）。
     */
    fun requestConsent(): Boolean = move(from = State.IDLE, to = State.AWAITING_CONSENT)

    /** 同意が得られた。投影を始めてよいときだけ true。 */
    fun consentGranted(): Boolean = move(from = State.AWAITING_CONSENT, to = State.RUNNING)

    /** 同意が拒否された。待機に戻れたときだけ true。 */
    fun consentDenied(): Boolean = move(from = State.AWAITING_CONSENT, to = State.IDLE)

    /**
     * 利用者（またはアプリ）からの停止。セッションが動いていたら true を返す。
     * 停止済みなら false（二重 stop で解放処理を二度走らせない）。
     */
    fun requestStop(): Boolean {
        if (state == State.IDLE) return false
        state = State.IDLE
        return true
    }

    /**
     * `MediaProjection.Callback.onStop`（OS 側の失効、またはこちらの `stop()` に伴う通知）。
     * 解放すべきものがあったときだけ true。
     */
    fun onSystemStop(): Boolean = requestStop()

    private fun move(from: State, to: State): Boolean {
        if (state != from) return false
        state = to
        return true
    }
}
