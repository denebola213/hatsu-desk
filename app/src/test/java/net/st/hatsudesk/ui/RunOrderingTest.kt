package net.st.hatsudesk.ui

import net.st.hatsudesk.core.store.RunIndexEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class RunOrderingTest {

    private fun entry(
        runId: String,
        rankingId: String = "ranking-1",
        rank: Int? = null,
        evalPoint: Int? = null,
    ) = RunIndexEntry(
        runId = runId,
        rankingId = rankingId,
        rank = rank,
        evalPoint = evalPoint,
    )

    @Test
    fun ordersDescendingByEvalPointWithUnknownLast() {
        val ordered = orderRunsForList(
            listOf(
                entry("run-low", evalPoint = 100),
                entry("run-unknown", evalPoint = null),
                entry("run-high", evalPoint = 300),
            ),
        ).single().runs

        assertEquals(listOf("run-high", "run-low", "run-unknown"), ordered.map { it.runId })
    }

    @Test
    fun breaksTiesByRank() {
        val ordered = orderRunsForList(
            listOf(
                entry("run-b", rank = 5, evalPoint = 200),
                entry("run-a", rank = 2, evalPoint = 200),
            ),
        ).single().runs

        assertEquals(listOf("run-a", "run-b"), ordered.map { it.runId })
    }

    @Test
    fun ordersRankingsByBestEvalPoint() {
        val ordered = orderRunsForList(
            listOf(
                entry("run-b1", rankingId = "ranking-b", evalPoint = 200),
                entry("run-a1", rankingId = "ranking-a", evalPoint = 300),
                entry("run-a2", rankingId = "ranking-a", evalPoint = 150),
            ),
        )

        assertEquals(listOf("ranking-a", "ranking-b"), ordered.map { it.rankingId })
        assertEquals(listOf("run-a1", "run-a2"), ordered.first().runs.map { it.runId })
    }

    @Test
    fun unknownRankingIsOneGroupAtTheEndEvenWithTheHighestEvalPoint() {
        val ordered = orderRunsForList(
            listOf(
                entry("run-unknown-high", rankingId = "", evalPoint = 9999),
                entry("run-known", rankingId = "ranking-a", evalPoint = 100),
            ),
        )

        assertEquals(listOf("ranking-a", null), ordered.map { it.rankingId })
        assertEquals(listOf("run-unknown-high"), ordered.last().runs.map { it.runId })
    }

    @Test
    fun blankAndWhitespaceRankingAreGroupedAsUnknown() {
        val ordered = orderRunsForList(
            listOf(
                entry("run-blank", rankingId = "", evalPoint = 10),
                entry("run-spaces", rankingId = "   ", evalPoint = 20),
                entry("run-known", rankingId = "ranking-a", evalPoint = 30),
            ),
        )

        assertEquals(listOf("ranking-a", null), ordered.map { it.rankingId })
        // 不明グループの中でも評価点の降順は保つ。
        assertEquals(listOf("run-spaces", "run-blank"), ordered.last().runs.map { it.runId })
    }
}
