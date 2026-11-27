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
        ).single().second

        assertEquals(listOf("run-high", "run-low", "run-unknown"), ordered.map { it.runId })
    }

    @Test
    fun breaksTiesByRank() {
        val ordered = orderRunsForList(
            listOf(
                entry("run-b", rank = 5, evalPoint = 200),
                entry("run-a", rank = 2, evalPoint = 200),
            ),
        ).single().second

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

        assertEquals(listOf("ranking-a", "ranking-b"), ordered.map { it.first })
        assertEquals(listOf("run-a1", "run-a2"), ordered.first().second.map { it.runId })
    }
}
