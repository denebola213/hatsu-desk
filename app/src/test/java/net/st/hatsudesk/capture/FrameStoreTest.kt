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
 *
 * 保存に使うのは「常に最新の1枚」で、フレームの時刻では選ばない。`Image.getTimestamp()` は
 * 合成時刻ではなく queueBuffer の時刻なので、時刻の比較では隠す前の絵を落とせない。
 */
class FrameStoreTest {

    private val closed = mutableListOf<String>()
    private val store = FrameStore<String>(close = { frame -> closed += frame })

    @Test
    fun oldGenerationFramesAreNotUsed() {
        val old = store.begin()
        store.offer(old, "古い世代")

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

        // 捨てたフレームは閉じる（持ち主は FrameStore だけ）。
        assertEquals(listOf("古い世代", "遅れて届いた古いフレーム"), closed)
    }

    @Test
    fun framesAfterStopAreDropped() {
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
    fun onlyLatestFrameOfGenerationIsKept() {
        val session = store.begin()
        assertEquals(FrameOffer.FIRST, store.offer(session, "最初の1枚"))
        assertEquals(FrameOffer.NEXT, store.offer(session, "2枚目"))
        assertEquals(FrameOffer.NEXT, store.offer(session, "最新の1枚"))

        // 置き換えた古い2枚は閉じ、最新だけが残る。
        assertEquals(listOf("最初の1枚", "2枚目"), closed)
        assertEquals("最新の1枚", store.withLatest(session) { it })
    }

    @Test
    fun replacedFrameIsClosed() {
        val session = store.begin()
        assertEquals(FrameOffer.FIRST, store.offer(session, "1枚目"))
        assertEquals(FrameOffer.NEXT, store.offer(session, "2枚目"))
        assertEquals(listOf("1枚目"), closed)
        assertEquals("2枚目", store.withLatest(session) { it })
    }

    @Test
    fun takingDoesNotConsume() {
        val session = store.begin()
        store.offer(session, "フレーム")
        assertEquals("フレーム", store.withLatest(session) { it })
        assertEquals("フレーム", store.withLatest(session) { it })
        assertTrue(store.hasFrame())
    }

    @Test
    fun beginClosesPreviousGenerationFrame() {
        val first = store.begin()
        store.offer(first, "最初の世代の1枚")

        // 世代を捨てたので、保持していたフレームは閉じる。
        store.begin()

        assertEquals(listOf("最初の世代の1枚"), closed)
        assertFalse(store.hasFrame(first))
    }
}
