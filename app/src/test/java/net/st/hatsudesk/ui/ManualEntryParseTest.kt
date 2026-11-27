package net.st.hatsudesk.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手入力のパース（設計 §8.6「フィールドは任意、空は不明」）。
 *
 * 空文字・空白のみ・null 相当（未入力）・0・不正な文字列を、項目ごとに確かめる。
 */
class ManualEntryParseTest {

    private fun parse(input: ManualEntryInput = ManualEntryInput()) =
        parseManualEntry(input, runId = "run-test")

    @Test
    fun allFieldsBlankSaveAsUnknown() {
        val parsed = parse()

        val document = parsed.document
        assertNotNull(document)
        assertEquals(emptyList<ManualEntryProblem>(), parsed.problems)
        val run = document!!.run
        assertEquals("run-test", run.id)
        assertEquals("", run.rankingId)
        assertNull(run.rank)
        assertNull(run.evalPoint)
        assertNull(run.paramTotal)
        assertNull(run.plv)
        assertNull(run.playedOn)
        assertNull(run.grade)
        assertNull(run.note)
        assertNull(document.idol)
    }

    @Test
    fun whitespaceOnlyIsTreatedAsUnknown() {
        val parsed = parse(
            ManualEntryInput(
                playedOn = "   ",
                rankingId = "\t ",
                rank = " ",
                evalPoint = "  ",
                paramTotal = "\n",
                plv = " ",
                grade = "  ",
                idolCardId = " ",
                note = "   ",
            ),
        )

        val run = parsed.document!!.run
        assertNull(run.rank)
        assertNull(run.evalPoint)
        assertNull(run.paramTotal)
        assertNull(run.plv)
        assertNull(run.playedOn)
        assertNull(run.grade)
        assertNull(run.note)
        assertEquals("", run.rankingId)
        assertNull(parsed.document.idol)
    }

    @Test
    fun zeroIsKeptAsZeroAndNotTreatedAsUnknown() {
        val parsed = parse(ManualEntryInput(evalPoint = "0", paramTotal = "0", plv = "0"))

        val run = parsed.document!!.run
        assertEquals(0, run.evalPoint)
        assertEquals(0, run.paramTotal)
        assertEquals(0, run.plv)
    }

    @Test
    fun rankZeroIsRejectedBecauseRankStartsAtOne() {
        val parsed = parse(ManualEntryInput(rank = "0"))

        assertNull(parsed.document)
        assertEquals(
            listOf(ManualEntryField.RANK),
            parsed.problems.map { it.field },
        )
    }

    @Test
    fun nonNumericTextIsReportedForThatFieldOnly() {
        val parsed = parse(
            ManualEntryInput(
                rank = "三位",
                evalPoint = "abc",
                paramTotal = "3,120",
                plv = "-1",
            ),
        )

        assertNull(parsed.document)
        assertEquals(
            listOf(
                ManualEntryField.RANK,
                ManualEntryField.EVAL_POINT,
                ManualEntryField.PARAM_TOTAL,
                ManualEntryField.PLV,
            ),
            parsed.problems.map { it.field },
        )
        // エラーは日本語で、例外型名や英語の OS メッセージを含まない。
        // （"PLv" のような項目名のラテン文字はそのまま使う）
        parsed.problems.forEach { problem ->
            assertTrue(
                "日本語のメッセージであること: ${problem.message}",
                Regex("(Exception|Error|java\\.|kotlin\\.)").containsMatchIn(problem.message).not(),
            )
            assertTrue(problem.message, problem.message.contains("整数"))
        }
    }

    @Test
    fun invalidDateIsReportedForTheDateField() {
        val parsed = parse(ManualEntryInput(playedOn = "2026-13-01"))

        assertNull(parsed.document)
        assertEquals(listOf(ManualEntryField.PLAYED_ON), parsed.problems.map { it.field })
    }

    @Test
    fun validValuesAreKeptAndTextIsTrimmed() {
        val parsed = parse(
            ManualEntryInput(
                playedOn = " 2026-11-27 ",
                rankingId = " ranking-a ",
                rank = "3",
                evalPoint = "12345",
                paramTotal = "3120",
                plv = "60",
                grade = " S ",
                idolCardId = " idol-1 ",
                note = " メモ ",
            ),
        )

        val document = parsed.document!!
        val run = document.run
        assertEquals("ranking-a", run.rankingId)
        assertEquals(3, run.rank)
        assertEquals(12345, run.evalPoint)
        assertEquals(3120, run.paramTotal)
        assertEquals(60, run.plv)
        assertEquals("2026-11-27", run.playedOn)
        assertEquals("S", run.grade)
        assertEquals("メモ", run.note)
        assertEquals("idol-1", document.idol?.cardId)
    }

    @Test
    fun blankRankWithFilledEvalPointIsAccepted() {
        val parsed = parse(ManualEntryInput(evalPoint = "15000", rankingId = "ranking-a"))

        val run = parsed.document!!.run
        assertNull(run.rank)
        assertEquals(15000, run.evalPoint)
    }
}
