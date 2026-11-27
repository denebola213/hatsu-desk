package net.st.hatsudesk.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.st.hatsudesk.core.store.RunIndexEntry
import net.st.hatsudesk.data.RunOverview
import net.st.hatsudesk.data.RunRepository

/**
 * 一覧。ranking ごとにまとめ、その中を評価点の降順に並べる（設計 §8.6）。
 * 評価点が不明な run は末尾。並び順は索引だけで決まるので、run の本文は読まない。
 */
@Composable
fun RunListScreen(
    repository: RunRepository,
    generation: Int,
    onOpenRun: (String) -> Unit,
    onManualEntry: () -> Unit,
    onDictionary: () -> Unit,
) {
    var state by remember { mutableStateOf<LoadState<RunOverview>>(LoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(generation, attempt) {
        state = LoadState.Loading
        state = loadCatching { repository.loadOverview() }
    }

    ScreenFrame(
        title = "一覧",
        actions = {
            TextButton(onClick = onManualEntry) { Text("手入力") }
            TextButton(onClick = onDictionary) { Text("カード辞書") }
        },
    ) {
        when (val current = state) {
            LoadState.Loading -> LoadingPane()
            is LoadState.Failed -> ErrorPane(current.message) { attempt += 1 }
            is LoadState.Ready -> RunListBody(current.value, onOpenRun)
        }
    }
}

@Composable
private fun RunListBody(overview: RunOverview, onOpenRun: (String) -> Unit) {
    if (overview.entries.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("まだ記録がありません", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "手入力から1件目を追加できます。保存すると、ここに評価点の降順で並びます。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        return
    }

    val groups = orderRunsForList(overview.entries)

    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { (rankingId, runs) ->
            item(key = "ranking-$rankingId") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("ランキング $rankingId", style = MaterialTheme.typography.labelLarge)
                    Text("${runs.size}件", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
            items(items = runs, key = { it.runId }) { entry ->
                RunRow(entry, overview, onClick = { onOpenRun(entry.runId) })
                HorizontalDivider()
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun RunRow(entry: RunIndexEntry, overview: RunOverview, onClick: () -> Unit) {
    val idolName = when {
        entry.idolCardId == null -> "アイドル 不明"
        else -> resolvedCardName(entry.idolCardId, overview.cards) ?: "未詳"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.rank?.let { "${formatNumber(it)}位" } ?: "順位 不明",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = entry.evalPoint?.let { "評価点 ${formatNumber(it)}" } ?: "評価点 不明",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = idolName,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = entry.paramTotal?.let { "パラメータ ${formatNumber(it)}" } ?: "パラメータ 不明",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text("記録番号 ${entry.runId}", style = MaterialTheme.typography.bodySmall)
    }
}
