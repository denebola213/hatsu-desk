package net.st.hatsudesk.ui

import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunDocument
import net.st.hatsudesk.core.model.RunIdol
import java.time.LocalDate

/**
 * 手入力の素の文字列。設計 §8.6 のとおり全項目が任意で、空欄は「不明」として保存する。
 *
 * 画面から切り離した値だけの型にして、パースを純関数として単体テストできるようにしてある。
 */
data class ManualEntryInput(
    val playedOn: String = "",
    val rankingId: String = "",
    val rank: String = "",
    val evalPoint: String = "",
    val paramTotal: String = "",
    val plv: String = "",
    val grade: String = "",
    val idolCardId: String = "",
    val note: String = "",
)

/** 入力項目。エラーを項目に紐づけて、その欄の下に出すために使う。 */
enum class ManualEntryField(val label: String) {
    PLAYED_ON("日付"),
    RANK("順位"),
    EVAL_POINT("評価点"),
    PARAM_TOTAL("パラメータ合計"),
    PLV("PLv"),
}

/**
 * 1項目の解釈結果。**空欄（[Unknown]）と 0（[Value] の 0）を区別する。**
 * 空欄は「不明」＝ null、0 は入力された数値としてそのまま保存する。
 */
sealed interface FieldValue<out T> {
    data object Unknown : FieldValue<Nothing>
    data class Value<T>(val value: T) : FieldValue<T>
    data object Invalid : FieldValue<Nothing>
}

/** 空・空白のみは「不明」。数値でない文字列、範囲外は [FieldValue.Invalid]。 */
fun parseOptionalInt(raw: String, minimum: Int): FieldValue<Int> {
    val text = raw.trim()
    if (text.isEmpty()) return FieldValue.Unknown
    val value = text.toIntOrNull() ?: return FieldValue.Invalid
    return if (value < minimum) FieldValue.Invalid else FieldValue.Value(value)
}

/** 空・空白のみは「不明」。年-月-日として読めない文字列は [FieldValue.Invalid]。 */
fun parseOptionalDate(raw: String): FieldValue<String> {
    val text = raw.trim()
    if (text.isEmpty()) return FieldValue.Unknown
    val date = runCatching { LocalDate.parse(text) }.getOrNull() ?: return FieldValue.Invalid
    return FieldValue.Value(date.toString())
}

/** 画面に出す入力エラー。項目と日本語のメッセージの組。 */
data class ManualEntryProblem(val field: ManualEntryField, val message: String)

/**
 * パース結果。[problems] が空でなければ [document] は null（保存しない）。
 * 1項目でも読めない文字列が入っていたら保存を止め、その項目だけを指して直させる。
 */
data class ManualEntryParse(
    val document: RunDocument?,
    val problems: List<ManualEntryProblem>,
)

/**
 * 手入力を [RunDocument] にする純関数（設計 §8.6）。
 *
 * - 全項目が任意。空欄・空白のみは null（「不明」）
 * - 数値は 0 を「不明」と混同しない（0 は 0 のまま）
 * - 読めない文字列はその項目の日本語エラーにし、保存は行わない
 * - ランキングの空欄は `ranking_id` の空文字で表す（`:core` の [Run.rankingId] は非 null のため）
 */
fun parseManualEntry(input: ManualEntryInput, runId: String): ManualEntryParse {
    val problems = mutableListOf<ManualEntryProblem>()

    fun <T> take(field: ManualEntryField, parsed: FieldValue<T>): T? = when (parsed) {
        is FieldValue.Value -> parsed.value
        FieldValue.Unknown -> null
        FieldValue.Invalid -> {
            problems += ManualEntryProblem(field, problemMessage(field))
            null
        }
    }

    val playedOn = take(ManualEntryField.PLAYED_ON, parseOptionalDate(input.playedOn))
    val rank = take(ManualEntryField.RANK, parseOptionalInt(input.rank, minimum = 1))
    val evalPoint = take(ManualEntryField.EVAL_POINT, parseOptionalInt(input.evalPoint, minimum = 0))
    val paramTotal = take(ManualEntryField.PARAM_TOTAL, parseOptionalInt(input.paramTotal, minimum = 0))
    val plv = take(ManualEntryField.PLV, parseOptionalInt(input.plv, minimum = 0))

    if (problems.isNotEmpty()) return ManualEntryParse(document = null, problems = problems)

    val document = RunDocument(
        run = Run(
            id = runId,
            rankingId = input.rankingId.trim(),
            rank = rank,
            plv = plv,
            grade = input.grade.trim().takeIf { it.isNotEmpty() },
            evalPoint = evalPoint,
            paramTotal = paramTotal,
            playedOn = playedOn,
            note = input.note.trim().takeIf { it.isNotEmpty() },
        ),
        idol = input.idolCardId.trim().takeIf { it.isNotEmpty() }?.let { cardId ->
            RunIdol(runId = runId, cardId = cardId)
        },
    )
    return ManualEntryParse(document = document, problems = emptyList())
}

private fun problemMessage(field: ManualEntryField): String = when (field) {
    ManualEntryField.PLAYED_ON -> "日付は 2026-11-27 の形（年-月-日）で入れてください。"
    ManualEntryField.RANK -> "順位は1以上の整数で入れてください。"
    ManualEntryField.EVAL_POINT -> "評価点は0以上の整数で入れてください。"
    ManualEntryField.PARAM_TOTAL -> "パラメータ合計は0以上の整数で入れてください。"
    ManualEntryField.PLV -> "PLv は0以上の整数で入れてください。"
}
