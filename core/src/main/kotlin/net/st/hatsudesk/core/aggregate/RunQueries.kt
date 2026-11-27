package net.st.hatsudesk.core.aggregate

import net.st.hatsudesk.core.model.RunDocument

/**
 * 集計の入口。M0 では画面から呼ばない（集計そのものは M5・設計 §13）。
 *
 * 文書ストアから読んだ全件を渡す純関数だけを置く。ここに Android は入らない。
 */
object RunQueries {

    /** 指定 ranking の run を評価点の降順で返す。評価点が不明なものは末尾。 */
    fun ofRanking(documents: List<RunDocument>, rankingId: String): List<RunDocument> =
        documents
            .filter { it.run.rankingId == rankingId }
            .sortedWith(
                compareByDescending<RunDocument> { it.run.evalPoint ?: Int.MIN_VALUE }
                    .thenBy { it.run.rank ?: Int.MAX_VALUE }
                    .thenBy { it.run.id },
            )

    /** 評価点が判明している run の中央値。1件も無ければ null。 */
    fun medianEvalPoint(documents: List<RunDocument>): Double? {
        val points = documents.mapNotNull { it.run.evalPoint }.sorted()
        if (points.isEmpty()) return null
        val middle = points.size / 2
        return if (points.size % 2 == 1) {
            points[middle].toDouble()
        } else {
            (points[middle - 1] + points[middle]) / 2.0
        }
    }
}
