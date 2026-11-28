package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Test

/** 黒い候補だけが来る・新しいフレームが来ない取得を、600ms で打ち切る時間判定。 */
class CaptureDeadlineTest {

    @Test
    fun captureStopsAt600ms() {
        val deadline = CaptureDeadline(startedAtMillis = 1_000L)

        assertEquals(600L, deadline.remainingMillis(nowMillis = 1_000L))
        assertEquals(1L, deadline.remainingMillis(nowMillis = 1_599L))
        assertEquals("600ms に達したら待ちを終える", 0L, deadline.remainingMillis(nowMillis = 1_600L))
    }

    @Test
    fun captureDoesNotWaitAgainAfterDeadline() {
        val deadline = CaptureDeadline(startedAtMillis = 1_000L)

        assertEquals(0L, deadline.remainingMillis(nowMillis = 1_601L))
    }
}
