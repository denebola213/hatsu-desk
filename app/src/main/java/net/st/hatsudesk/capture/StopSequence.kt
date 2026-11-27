package net.st.hatsudesk.capture

/**
 * 停止の段取り。**「停止が成立したか」と「残ったボタンの後片付け」を切り離す。**
 *
 * `ProjectionSession.requestStop()` は停止済みなら false を返す。その false で
 * [CaptureController.stop] を早期 return させると、次の経路でボタンが画面に残り続ける。
 *
 * 1. 1回目の停止で `WindowManager.removeViewImmediate()` が外れず、参照だけが残る。
 * 2. 状態は IDLE になっているので、2回目の `stop()` は `requestStop()` の false で return。
 * 3. 停止中は `capture()` も `session.isRunning` で先に return するので、取得からも再試行できない。
 *
 * したがって [stop] は、停止済みでも残っているボタンを必ず外しにいく。Android の型を
 * 持たないので、初回失敗→2回目成功の順序を JVM の単体テストで固定できる。
 */
internal class StopSequence<T : Any>(
    private val session: ProjectionSession,
    private val overlay: OverlayCleanup<T>,
    /** セッションのリソース（VirtualDisplay・ImageReader・投影）を手放す。停止が成立したときだけ。 */
    private val releaseSession: () -> Unit,
) {

    /**
     * 停止する。停止が成立したら true。停止済みでも、残っているボタンはここで外しにいく。
     * 戻り値は「セッションを解放したか」で、ボタンを外せたかではない。
     */
    fun stop(): Boolean {
        if (session.requestStop()) {
            // セッションの解放の一部としてボタンも外す（外せなければ参照が残る）。
            releaseSession()
            return true
        }
        // 停止済み。前回の停止で外せなかったボタンだけを、状態に関係なく試し直す。
        overlay.release()
        return false
    }
}
