package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取得の段取り（PR #22 の指摘 1）。
 *
 * 隠した後の判定は枚数ではなくフレームの時刻で行う。`ImageReader` のコールバックは
 * 隠す処理と同期しないので、「押してから2枚届いた」は「隠した後の2枚」ではない。
 * 隠す前に合成されたフレームが2枚続けて届いても、それを保存に使ってはいけない。
 */
class CapturePlanTest {

    /**
     * フレーム（時刻, 中身）を差し込める小さな台。
     * `CaptureController.capture` と同じ順序で判定する（待つ → 条件を満たす1枚を写す）。
     */
    private class Stage(hideCompletedAtNanos: Long?) {
        private val stamps = mutableMapOf<String, Long>()
        private val store = FrameStore<String>(
            close = {},
            stampOf = { frame -> stamps.getValue(frame) },
        )
        val session = store.begin()
        val plan = CapturePlan.forPress(hideCompletedAtNanos)

        fun deliver(frame: String, atNanos: Long) {
            stamps[frame] = atNanos
            store.offer(session, frame)
        }

        /** Controller の `stamp.first { plan.accepts(it) }` と同じ判定。 */
        fun ready(): Boolean = plan.accepts(session.stamp.value)

        /** Controller の `grabFrame` と同じ判定で、保存に使う1枚を取り出す。 */
        fun take(): String? = if (!ready()) {
            null
        } else {
            store.withLatestAfter(session, plan.hiddenAtNanos ?: Long.MIN_VALUE) { it }
        }
    }

    @Test
    fun framesComposedBeforeHideAreNotAccepted() {
        // 押した直後、hide が効く前に合成されたフレームが2枚届いた。
        // 通し番号を数える判定では、これで「2枚進んだ」と誤認していた。
        val stage = Stage(hideCompletedAtNanos = 1_000_000_000)
        stage.deliver("ボタン入り A", 900_000_000)
        stage.deliver("ボタン入り B", 999_000_000)

        assertFalse(stage.ready())
        assertNull(stage.take())
    }

    @Test
    fun firstFrameAfterHideIsUsed() {
        val stage = Stage(hideCompletedAtNanos = 1_000_000_000)
        stage.deliver("ボタン入り A", 900_000_000)
        stage.deliver("ボタン入り B", 999_000_000)

        // 隠した後に合成された1枚。2枚目は待たない。
        stage.deliver("ボタン無し", 1_050_000_000)

        assertTrue(stage.ready())
        assertEquals("ボタン無し", stage.take())
    }

    @Test
    fun frameAtHideCompletionIsAccepted() {
        val stage = Stage(hideCompletedAtNanos = 1_000_000_000)
        stage.deliver("1ns 前", 999_999_999)
        assertFalse(stage.ready())

        stage.deliver("ちょうど", 1_000_000_000)
        assertTrue(stage.ready())
        assertEquals("ちょうど", stage.take())
    }

    @Test
    fun lateFrameComposedBeforeHideIsRejected() {
        // 隠した後の1枚が先に届き、その後に隠す前のフレームが遅れて届いた。
        // 保持している1枚の時刻で判定するので、古い方に置き換われば保存しない。
        val stage = Stage(hideCompletedAtNanos = 1_000_000_000)
        stage.deliver("ボタン無し", 1_050_000_000)
        assertTrue(stage.ready())

        stage.deliver("ボタン入り（遅れ）", 900_000_000)
        assertFalse(stage.ready())
        assertNull(stage.take())
    }

    @Test
    fun noOverlayUsesLatestFrameWithoutWaiting() {
        val stage = Stage(hideCompletedAtNanos = null)
        assertFalse(stage.plan.waitsForFrames)

        // 新しいフレームが1枚も来ていなくても（静止画面）、いまの1枚で保存してよい。
        stage.deliver("いまの画面", 5_000_000_000)
        assertTrue(stage.ready())
        assertEquals("いまの画面", stage.take())
    }

    @Test
    fun stalledMessage() {
        assertEquals(
            "画面が止まっていて撮れませんでした。もう一度押してください",
            CapturePlan.STALLED_MESSAGE,
        )
    }
}
