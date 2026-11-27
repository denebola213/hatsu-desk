package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * セッション世代の分離（PR #22 の指摘 5）。
 *
 * 停止してすぐ始め直すと、旧 `ImageReader` のコールバックが遅れて走ることがある。
 * 古い世代のフレームが新しい世代の保存に混ざらないことを、フレームの型を String に
 * して確かめる（[FrameStore] は `android.media.Image` を直接持たない）。
 */
class FrameStoreTest {

    private val closed = mutableListOf<String>()
    private val store = FrameStore<String> { closed += it }

    @Test
    fun `古い世代のフレームは新しい世代の保存に使われない`() {
        val old = store.begin()
        store.offer(old, "ボタン入り")

        // 停止してすぐ始め直した（＝新しい世代）。
        val fresh = store.begin()
        assertEquals(2L, fresh.generation)

        // 旧 ImageReader のコールバックが遅れて届いた。
        assertEquals(FrameOffer.STALE, store.offer(old, "遅れて届いた古いフレーム"))

        // 新しい世代からは読めない。混ざらない。
        assertNull(store.withLatest(old) { it })
        assertFalse(store.hasFrame(old))

        store.offer(fresh, "新しいフレーム")
        assertEquals("新しいフレーム", store.withLatest(fresh) { it })
        assertEquals(1L, fresh.serial.value)

        // 捨てたフレームは閉じる（持ち主は FrameStore だけ）。
        assertEquals(listOf("ボタン入り", "遅れて届いた古いフレーム"), closed)
    }

    @Test
    fun `停止後のフレームは捨てる`() {
        val session = store.begin()
        store.offer(session, "フレーム")
        assertTrue(store.hasFrame())

        store.end()

        assertFalse(store.hasFrame())
        assertNull(store.current())
        assertEquals(FrameOffer.STALE, store.offer(session, "停止後に届いたフレーム"))
        assertNull(store.withLatest(session) { it })
        assertEquals(listOf("フレーム", "停止後に届いたフレーム"), closed)
    }

    @Test
    fun `通し番号は世代ごとに0から数える`() {
        val first = store.begin()
        store.offer(first, "a")
        store.offer(first, "b")
        assertEquals(2L, first.serial.value)

        val second = store.begin()
        assertEquals(0L, second.serial.value)

        // 古い世代の番号は進まない（遅れて来ても新しい世代の待ち合わせを満たさない）。
        store.offer(first, "c")
        assertEquals(2L, first.serial.value)
        assertEquals(0L, second.serial.value)
    }

    @Test
    fun `置き換えたフレームは閉じる`() {
        val session = store.begin()
        assertEquals(FrameOffer.FIRST, store.offer(session, "1枚目"))
        assertEquals(FrameOffer.NEXT, store.offer(session, "2枚目"))
        assertEquals(listOf("1枚目"), closed)
        assertEquals("2枚目", store.withLatest(session) { it })
    }

    @Test
    fun `取り出しても消えない`() {
        val session = store.begin()
        store.offer(session, "フレーム")
        assertEquals("フレーム", store.withLatest(session) { it })
        assertEquals("フレーム", store.withLatest(session) { it })
        assertTrue(store.hasFrame())
    }
}
