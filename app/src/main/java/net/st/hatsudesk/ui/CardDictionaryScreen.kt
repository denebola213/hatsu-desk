package net.st.hatsudesk.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.data.DictionaryData
import net.st.hatsudesk.data.RunRepository

/**
 * カード辞書。空から始まり、登録済みの閲覧と、構成に出てくるが辞書に無いカード ID の
 * 一覧を出す（ADR 0009）。M0 では閲覧のみで、登録・編集は持たない。
 */
@Composable
fun CardDictionaryScreen(repository: RunRepository, onBack: () -> Unit) {
    var state by remember { mutableStateOf<LoadState<DictionaryData>>(LoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        state = LoadState.Loading
        state = loadCatching { repository.loadDictionary() }
    }

    ScreenFrame(title = "カード辞書", onBack = onBack) {
        when (val current = state) {
            LoadState.Loading -> LoadingPane()
            is LoadState.Failed -> ErrorPane(current.message) { attempt += 1 }
            is LoadState.Ready -> DictionaryBody(current.value)
        }
    }
}

@Composable
private fun DictionaryBody(dictionary: DictionaryData) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header-registered") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("登録済みのカード", style = MaterialTheme.typography.titleMedium)
                Text("${dictionary.cards.size}件", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }

        if (dictionary.cards.isEmpty()) {
            item(key = "registered-empty") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("登録済みのカードはありません。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "辞書は空の状態で始まります。カード詳細を取り込むと増えます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                HorizontalDivider()
            }
        } else {
            items(dictionary.cards, key = { "card-${it.cardId}" }) { card ->
                RegisteredCardRow(card)
                HorizontalDivider()
            }
        }

        item(key = "header-unknown") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("未登録のカード", style = MaterialTheme.typography.titleMedium)
                Text(
                    "構成に出てくるが、辞書に名前が無いカード。${dictionary.unknownCardIds.size}件",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()
        }

        if (dictionary.unknownCardIds.isEmpty()) {
            item(key = "unknown-empty") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("未登録のカードはありません。", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
            }
        } else {
            items(dictionary.unknownCardIds, key = { "unknown-$it" }) { cardId ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("未詳", style = MaterialTheme.typography.bodyLarge)
                    Text("カード番号: $cardId", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }

        item(key = "note") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    "いまは閲覧のみです。辞書の登録と照合は今後の版で入ります。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RegisteredCardRow(card: CardMaster) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            text = card.name?.takeIf { it.isNotBlank() } ?: "未詳",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text("カード番号: ${card.cardId}", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Field("種別", card.kind ?: "不明", Modifier.weight(1f))
            Field("レアリティ", card.rarity ?: "不明", Modifier.weight(1f))
        }
    }
}
