package net.st.hatsudesk.ui

import net.st.hatsudesk.core.store.RunIndexEntry

/**
 * 一覧の並び。ranking ごとにまとめ、その中を評価点の降順にする（設計 §8.6）。
 *
 * - ranking の並び: その ranking の最高評価点の降順。同点なら ranking ID 順
 * - run の並び: 評価点の降順。不明（null）は末尾。同点は順位、次に run ID
 *
 * 純関数にして、索引だけで並びが決まることを単体テストで確かめられるようにしてある。
 */
fun orderRunsForList(entries: List<RunIndexEntry>): List<Pair<String, List<RunIndexEntry>>> =
    entries
        .groupBy { it.rankingId }
        .toList()
        .sortedWith(
            compareByDescending<Pair<String, List<RunIndexEntry>>> { (_, runs) ->
                runs.maxOfOrNull { it.evalPoint ?: Int.MIN_VALUE } ?: Int.MIN_VALUE
            }.thenBy { it.first },
        )
        .map { (rankingId, runs) ->
            rankingId to runs.sortedWith(
                compareByDescending<RunIndexEntry> { it.evalPoint ?: Int.MIN_VALUE }
                    .thenBy { it.rank ?: Int.MAX_VALUE }
                    .thenBy { it.runId },
            )
        }
