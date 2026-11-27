package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 投影セッションの状態遷移（Issue #18）。`:app` の単体テストだが、対象の
 * [ProjectionSession] は `android.*` を使わないので JVM でそのまま回る。
 *
 * 見ているのは「二重 start / 二重 stop / 遅れて届いた結果 / OS 側の失効」で落ちないこと。
 * どれも、リソースの解放を2回走らせないための前提になる。
 */
class ProjectionSessionTest {

    private val session = ProjectionSession()

    @Test
    fun `開始を二重に要求しても二回目は無視する`() {
        assertTrue(session.requestConsent())
        assertEquals(ProjectionSession.State.AWAITING_CONSENT, session.state)

        // 2回目（ダイアログが二重に出ないこと）。
        assertFalse(session.requestConsent())
        assertEquals(ProjectionSession.State.AWAITING_CONSENT, session.state)

        assertTrue(session.consentGranted())
        assertTrue(session.isRunning)

        // 投影中の start も無視する。
        assertFalse(session.requestConsent())
        assertEquals(ProjectionSession.State.RUNNING, session.state)
    }

    @Test
    fun `停止してからもう一度開始できる`() {
        assertTrue(session.requestConsent())
        assertTrue(session.consentGranted())

        assertTrue(session.requestStop())
        assertEquals(ProjectionSession.State.IDLE, session.state)

        // 停止済みの stop。
        assertFalse(session.requestStop())

        // 同意を取り直して再開する。
        assertTrue(session.requestConsent())
        assertTrue(session.consentGranted())
        assertTrue(session.isRunning)
    }

    @Test
    fun `同意を拒否すると停止中に戻り投影は始まらない`() {
        assertTrue(session.requestConsent())

        assertTrue(session.consentDenied())
        assertEquals(ProjectionSession.State.IDLE, session.state)
        assertFalse(session.isRunning)

        // 拒否の後に遅れて届いた「許可」は捨てる（状態が勝手に RUNNING にならない）。
        assertFalse(session.consentGranted())
        assertEquals(ProjectionSession.State.IDLE, session.state)
    }

    @Test
    fun `OS の onStop で解放し、もう一度呼ばれても落ちない`() {
        assertTrue(session.requestConsent())
        assertTrue(session.consentGranted())

        // MediaProjection.Callback.onStop 相当。1回目だけ「片付ける」。
        assertTrue(session.onSystemStop())
        assertEquals(ProjectionSession.State.IDLE, session.state)
        assertFalse(session.isRunning)

        // 同じ callback が重複して届いても、停止要求と重なっても、解放は1回で済む。
        assertFalse(session.onSystemStop())
        assertFalse(session.requestStop())

        // 失効後は同意を取り直せば再開できる。
        assertTrue(session.requestConsent())
        assertTrue(session.consentGranted())
        assertTrue(session.isRunning)
    }

    @Test
    fun `同意待ちのまま停止でき、遅れて届いた同意は捨てる`() {
        assertTrue(session.requestConsent())

        assertTrue(session.requestStop())
        assertEquals(ProjectionSession.State.IDLE, session.state)

        // ダイアログを閉じた後に結果が返ってきても、投影は始めない。
        assertFalse(session.consentGranted())
        assertEquals(ProjectionSession.State.IDLE, session.state)
    }

    @Test
    fun `停止中はどの要求も状態を変えない`() {
        assertEquals(ProjectionSession.State.IDLE, session.state)

        assertFalse(session.requestStop())
        assertFalse(session.onSystemStop())
        assertFalse(session.consentGranted())
        assertFalse(session.consentDenied())
        assertEquals(ProjectionSession.State.IDLE, session.state)
    }
}
