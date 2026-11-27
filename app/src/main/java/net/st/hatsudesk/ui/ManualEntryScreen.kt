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
import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunDocument
import net.st.hatsudesk.core.model.RunIdol
import net.st.hatsudesk.data.RunRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 手入力。run を1件作って保存する（設計 §8.6）。
 *
 * 必須は日付・ランキング・順位・評価点だけ。ほかは空欄でよく、空欄は null（「不明」）で
 * 保存する。保存に成功したら一覧へ戻る。
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
    var problems by remember { mutableStateOf<List<String>>(emptyList()) }
    var saveFailure by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun parse(): Pair<RunDocument?, List<String>> {
        val found = mutableListOf<String>()
        val date = runCatching { LocalDate.parse(playedOn.trim()) }.getOrNull()
        if (date == null) found += "日付を 2026-11-27 の形で入れてください。"
        if (rankingId.isBlank()) found += "ランキングを入れてください。"

        val rankValue = rank.trim().toIntOrNull()
        if (rankValue == null || rankValue <= 0) found += "順位は1以上の整数で入れてください。"

        val pointValue = evalPoint.trim().toIntOrNull()
        if (pointValue == null || pointValue < 0) found += "評価点は0以上の整数で入れてください。"

        val paramValue = paramTotal.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (paramTotal.isNotBlank() && (paramValue == null || paramValue < 0)) {
            found += "パラメータ合計は0以上の整数で入れてください。"
        }

        val plvValue = plv.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (plv.isNotBlank() && (plvValue == null || plvValue < 0)) {
            found += "PLv は0以上の整数で入れてください。"
        }

        if (found.isNotEmpty()) return null to found

        val id = newRunId()
        val document = RunDocument(
            run = Run(
                id = id,
                rankingId = rankingId.trim(),
                rank = rankValue,
                plv = plvValue,
                grade = grade.trim().takeIf { it.isNotEmpty() },
                evalPoint = pointValue,
                paramTotal = paramValue,
                playedOn = playedOn.trim(),
                note = note.trim().takeIf { it.isNotEmpty() },
            ),
            idol = idolCardId.trim().takeIf { it.isNotEmpty() }?.let { cardId ->
                RunIdol(runId = id, cardId = cardId)
            },
        )
        return document to emptyList()
    }

    ScreenFrame(title = "手入力", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "日付・ランキング・順位・評価点だけが必須です。空欄は「不明」として保存します。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))

            if (problems.isNotEmpty()) {
                Text("入力に誤りがあります", style = MaterialTheme.typography.titleSmall)
                problems.forEach { problem ->
                    Text("・$problem", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }

            saveFailure?.let { message ->
                Text("保存に失敗しました", style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }

            OutlinedTextField(
                value = playedOn,
                onValueChange = { playedOn = it },
                label = { Text("日付（必須）") },
                supportingText = { Text("年-月-日。例: 2026-11-27") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = rankingId,
                onValueChange = { rankingId = it },
                label = { Text("ランキング（必須）") },
                supportingText = { Text("公演・シーズン・難易度・Pアイドルをまとめた識別子") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = rank,
                onValueChange = { rank = it },
                label = { Text("順位（必須）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = evalPoint,
                onValueChange = { evalPoint = it },
                label = { Text("評価点（必須）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))
            Text("任意（空欄は「不明」）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))

            OutlinedTextField(
                value = paramTotal,
                onValueChange = { paramTotal = it },
                label = { Text("パラメータ合計") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = plv,
                onValueChange = { plv = it },
                label = { Text("PLv") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = grade,
                onValueChange = { grade = it },
                label = { Text("グレード") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = idolCardId,
                onValueChange = { idolCardId = it },
                label = { Text("Pアイドルのカード番号") },
                supportingText = { Text("辞書に無い番号でも保存できます。詳細では「未詳」と出ます。") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("メモ") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    enabled = !saving,
                    onClick = {
                        val (document, found) = parse()
                        problems = found
                        saveFailure = null
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

private fun newRunId(): String {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val suffix = UUID.randomUUID().toString().replace("-", "").take(6)
    return "run-$stamp-$suffix"
}
