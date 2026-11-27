package net.st.hatsudesk.capture

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 保存済みの枚数の読み直しが、古い一覧で新しい枚数を上書きしないこと（PR #21 の指摘）。
 *
 * [SavedCountRefresher] は `android.*` を使わないので、JVM の単体テストで実際の
 * コルーチンを走らせられる。`list()` に当たる [SavedCountRefresher] の `read` を
 * わざと止めて、読み直しの完了順を入れ替えている。呼び出し回数ではなく、最後に
 * 反映された枚数を見る。
 */
class SavedCountRefresherTest {

    @Test
    fun refreshedCountIsApplied() = runBlocking {
        var applied = -1
        val refresher = SavedCountRefresher(
            scope = this,
            read = { 7 },
            apply = { applied = it },
            onError = { throw it },
        )

        refresher.refresh().join()

        assertEquals(7, applied)
    }

    @Test
    fun olderRefreshDoesNotOverwriteNewer() = runBlocking {
        val olderReading = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        var reads = 0
        val applied = mutableListOf<Int>()
        val refresher = SavedCountRefresher(
            scope = this,
            read = {
                when (reads++) {
                    // 1回目は「5枚」を読むが、反映まで止めておく（遅い list()）。
                    0 -> {
                        olderReading.complete(Unit)
                        releaseOlder.await()
                        5
                    }
                    // 2回目（新しい ON_RESUME）は 6 枚をすぐ返す。
                    else -> 6
                }
            },
            apply = { applied += it },
            onError = { error -> throw AssertionError("読めるはずの一覧が失敗した", error) },
        )

        val older = refresher.refresh()
        olderReading.await()

        val newer = refresher.refresh()
        newer.join()
        assertEquals(listOf(6), applied)

        // ここで古い一覧が返る。新しい世代が始まっているので反映してはいけない。
        releaseOlder.complete(Unit)
        older.join()

        assertEquals(listOf(6), applied)
    }

    @Test
    fun refreshStartedBeforeSaveIsDiscarded() = runBlocking {
        val olderReading = CompletableDeferred<Unit>()
        val releaseOlder = CompletableDeferred<Unit>()
        val applied = mutableListOf<Int>()
        val refresher = SavedCountRefresher(
            scope = this,
            read = {
                olderReading.complete(Unit)
                releaseOlder.await()
                5
            },
            apply = { applied += it },
            onError = { error -> throw AssertionError("読めるはずの一覧が失敗した", error) },
        )

        val older = refresher.refresh()
        olderReading.await()

        // 読み直しの反映前に保存が終わった（実数は 6 枚）。この反映が勝たなければならない。
        refresher.record(6)
        assertEquals(listOf(6), applied)

        releaseOlder.complete(Unit)
        older.join()

        assertEquals(listOf(6), applied)
    }

    @Test
    fun failedRefreshKeepsPreviousCount() = runBlocking {
        var applied = -1
        var reported: Throwable? = null
        val refresher = SavedCountRefresher(
            scope = this,
            read = { 4 },
            apply = { applied = it },
            onError = { reported = it },
        )
        refresher.refresh().join()
        assertEquals(4, applied)

        val broken = SavedCountRefresher(
            scope = this,
            read = { throw IllegalStateException("読めない") },
            apply = { applied = it },
            onError = { reported = it },
        )
        broken.refresh().join()

        assertEquals(4, applied)
        assertEquals("読めない", reported?.message)
    }
}
