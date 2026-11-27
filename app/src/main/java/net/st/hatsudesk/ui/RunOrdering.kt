package net.st.hatsudesk.ui

import net.st.hatsudesk.core.store.RunIndexEntry

/**
 * 一覧のランキング1グループ。[rankingId] が null のものは「不明」のグループで、
 * 必ず末尾に置く。
 */
data class RankingGroup(
    val rankingId: String?,
    val runs: List<RunIndexEntry>,
)

/**
 * 一覧の並び。ranking ごとにまとめ、その中を評価点の降順にする（設計 §8.6）。
 *
 * - ranking の並び: 判明している ranking を最高評価点の降順で。同点なら ranking ID 順。
 *   ranking が空欄（不明）の run はひとつの「不明」グループにまとめ、評価点に関係なく末尾
 * - run の並び: 評価点の降順。不明（null）は末尾。同点は順位、次に run ID
 *
 * 純関数にして、索引だけで並びが決まることを単体テストで確かめられるようにしてある。
 */
fun orderRunsForList(entries: List<RunIndexEntry>): List<RankingGroup> =
    entries
        .groupBy { it.rankingId.trim().ifEmpty { null } }
        .map { (rankingId, runs) ->
            RankingGroup(
                rankingId = rankingId,
                runs = runs.sortedWith(
                    compareByDescending<RunIndexEntry> { it.evalPoint ?: Int.MIN_VALUE }
                        .thenBy { it.rank ?: Int.MAX_VALUE }
                        .thenBy { it.runId },
                ),
            )
        }
        .sortedWith(
            // 不明（null）を後ろに回す。比較に rankingId を使わないので null でも落ちない。
            compareBy<RankingGroup> { it.rankingId == null }
                .thenByDescending { group ->
                    group.runs.maxOfOrNull { it.evalPoint ?: Int.MIN_VALUE } ?: Int.MIN_VALUE
                }
                .thenBy { it.rankingId.orEmpty() },
        )
