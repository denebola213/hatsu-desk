package net.st.hatsudesk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.st.hatsudesk.data.RunRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 手入力。run を1件作って保存する（設計 §8.6）。
 *
 * **全項目が任意。** 空欄・空白のみは null（「不明」）として保存し、0 は 0 として残す。
 * 数値欄に読めない文字列が入ったときだけ、その欄に日本語のエラーを出して保存を止める
 * （他が埋まっていても、打ち間違いを黙って「不明」に落とさないため）。
 * 保存に成功したら一覧へ戻る。
 */
@Composable
fun ManualEntryScreen(repository: RunRepository, onBack: () -> Unit, onSaved: () -> Unit) {
    var playedOn by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var rankingId by rememberSaveable { mutableStateOf("") }
    var rank by rememberSaveable { mutableStateOf("") }
    var evalPoint by rememberSaveable { mutableStateOf("") }
    var paramTotal by rememberSaveable { mutableStateOf("") }
    var plv by rememberSaveable { mutableStateOf("") }
    var grade by rememberSaveable { mutableStateOf("") }
    var idolCardId by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }
    var problems by remember { mutableStateOf<List<ManualEntryProblem>>(emptyList()) }
    var saveFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun input() = ManualEntryInput(
        playedOn = playedOn,
        rankingId = rankingId,
        rank = rank,
        evalPoint = evalPoint,
        paramTotal = paramTotal,
        plv = plv,
        grade = grade,
        idolCardId = idolCardId,
        note = note,
    )

    fun problemOf(field: ManualEntryField): String? =
        problems.firstOrNull { it.field == field }?.message

    ScreenFrame(title = "手入力", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "すべて任意です。空欄は「不明」として保存します。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (problems.isNotEmpty()) {
                Text("入力に誤りがあります", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(12.dp))
            }

            saveFailure?.let { message ->
                Text("保存に失敗しました", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }

            EntryField(
                value = playedOn,
                onValueChange = { playedOn = it },
                label = "日付",
                problem = problemOf(ManualEntryField.PLAYED_ON),
                supporting = "年-月-日。例: 2026-11-27",
            )
            EntryField(
                value = rankingId,
                onValueChange = { rankingId = it },
                label = "ランキング",
                supporting = "公演・シーズン・難易度・Pアイドルをまとめた識別子",
            )
            EntryField(
                value = rank,
                onValueChange = { rank = it },
                label = "順位",
                problem = problemOf(ManualEntryField.RANK),
            )
            EntryField(
                value = evalPoint,
                onValueChange = { evalPoint = it },
                label = "評価点",
                problem = problemOf(ManualEntryField.EVAL_POINT),
            )
            EntryField(
                value = paramTotal,
                onValueChange = { paramTotal = it },
                label = "パラメータ合計",
                problem = problemOf(ManualEntryField.PARAM_TOTAL),
            )
            EntryField(
                value = plv,
                onValueChange = { plv = it },
                label = "PLv",
                problem = problemOf(ManualEntryField.PLV),
            )
            EntryField(
                value = grade,
                onValueChange = { grade = it },
                label = "グレード",
            )
            EntryField(
                value = idolCardId,
                onValueChange = { idolCardId = it },
                label = "Pアイドルのカード番号",
                supporting = "辞書に無い番号でも保存できます。詳細では「未詳」と出ます。",
            )
            EntryField(
                value = note,
                onValueChange = { note = it },
                label = "メモ",
                minLines = 3,
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    enabled = !saving,
                    onClick = {
                        val parsed = parseManualEntry(input(), newRunId())
                        problems = parsed.problems
                        saveFailure = null
                        val document = parsed.document
                        if (document != null) {
                            scope.launch {
                                saving = true
                                try {
                                    repository.save(document)
                                    onSaved()
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (error: Throwable) {
                                    saveFailure = describeFailure(error)
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    },
                ) {
                    Text(if (saving) "保存中" else "保存")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onBack, enabled = !saving) { Text("やめる") }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 1行の入力欄。エラーがあるときだけ、その欄の下に日本語のメッセージを出す。
 * 空欄を許す欄には「（必須）」を付けない。
 */
@Composable
private fun EntryField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    problem: String? = null,
    supporting: String? = null,
    minLines: Int = 1,
) {
    val note = problem ?: supporting
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = problem != null,
        supportingText = if (note == null) null else ({ Text(note) }),
        singleLine = minLines == 1,
        minLines = minLines,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun newRunId(): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
    return "run-$stamp-$suffix"
}
