package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * セッション世代の分離と、フレームの時刻（PR #22 の指摘 5・1）。
 *
 * 停止してすぐ始め直すと、旧 `ImageReader` のコールバックが遅れて走ることがある。
 * 古い世代のフレームが新しい世代の保存に混ざらないことを、フレームの型を String に
 * して確かめる（[FrameStore] は `android.media.Image` を直接持たない）。
 */
class FrameStoreTest {

    private val closed = mutableListOf<String>()
    private val stamps = mutableMapOf<String, Long>()
    private val store = FrameStore<String>(
        close = { frame -> closed += frame },
        stampOf = { frame -> stamps.getValue(frame) },
    )

    /** フレームを届ける。時刻は「合成された時刻」で、届いた順とは別に指定できる。 */
    private fun deliver(session: FrameStore.Session<String>, frame: String, atNanos: Long): FrameOffer {
        stamps[frame] = atNanos
        return store.offer(session, frame)
    }

    @Test
    fun oldGenerationFramesAreNotUsed() {
        val old = store.begin()
        deliver(old, "ボタン入り", 1_000)

        // 停止してすぐ始め直した（＝新しい世代）。
        val fresh = store.begin()
        assertEquals(2L, fresh.generation)

        // 旧 ImageReader のコールバックが遅れて届いた。
        assertEquals(FrameOffer.STALE, store.offer(old, "遅れて届いた古いフレーム"))

        // 新しい世代からは読めない。混ざらない。
        assertNull(store.withLatest(old) { it })
        assertFalse(store.hasFrame(old))

        deliver(fresh, "新しいフレーム", 2_000)
        assertEquals("新しいフレーム", store.withLatest(fresh) { it })
        assertEquals(2_000L, fresh.stamp.value)

        // 捨てたフレームは閉じる（持ち主は FrameStore だけ）。
        assertEquals(listOf("ボタン入り", "遅れて届いた古いフレーム"), closed)
    }

    @Test
    fun framesAfterStopAreDropped() {
        val session = store.begin()
        deliver(session, "フレーム", 1_000)
        assertTrue(store.hasFrame())

        store.end()

        assertFalse(store.hasFrame())
        assertNull(store.current())
        assertEquals(FrameOffer.STALE, store.offer(session, "停止後に届いたフレーム"))
        assertNull(store.withLatest(session) { it })
        assertEquals(listOf("フレーム", "停止後に届いたフレーム"), closed)
    }

    @Test
    fun stampIsScopedToGeneration() {
        val first = store.begin()
        assertEquals(Long.MIN_VALUE, first.stamp.value)
        deliver(first, "a", 1_000)
        deliver(first, "b", 2_000)
        assertEquals(2_000L, first.stamp.value)

        val second = store.begin()
        assertEquals(Long.MIN_VALUE, second.stamp.value)

        // 古い世代の時刻は進まない（遅れて来ても新しい世代の待ち合わせを満たさない）。
        deliver(first, "c", 3_000)
        assertEquals(2_000L, first.stamp.value)
        assertEquals(Long.MIN_VALUE, second.stamp.value)
    }

    @Test
    fun withLatestAfterReturnsOnlyNewerFrame() {
        val session = store.begin()
        deliver(session, "隠す前", 1_000)

        // 隠し終えた時刻が 1_500。保持している1枚は隠す前なので渡さない。
        assertNull(store.withLatestAfter(session, 1_500) { it })

        deliver(session, "隠した後", 2_000)
        assertEquals("隠した後", store.withLatestAfter(session, 1_500) { it })

        // さらに後の時刻を要求すれば、まだ来ていないので渡さない。
        assertNull(store.withLatestAfter(session, 2_500) { it })
    }

    @Test
    fun replacedFrameIsClosed() {
        val session = store.begin()
        assertEquals(FrameOffer.FIRST, deliver(session, "1枚目", 1_000))
        assertEquals(FrameOffer.NEXT, deliver(session, "2枚目", 2_000))
        assertEquals(listOf("1枚目"), closed)
        assertEquals("2枚目", store.withLatest(session) { it })
    }

    @Test
    fun takingDoesNotConsume() {
        val session = store.begin()
        deliver(session, "フレーム", 1_000)
        assertEquals("フレーム", store.withLatest(session) { it })
        assertEquals("フレーム", store.withLatest(session) { it })
        assertTrue(store.hasFrame())
    }
}
