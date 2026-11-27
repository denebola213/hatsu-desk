package net.st.hatsudesk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.st.hatsudesk.core.model.CardMaster
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** 画面の読み込み状態。失敗は握り潰さず、そのまま表示する。 */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Failed(val message: String) : LoadState<Nothing>
    data class Ready<T>(val value: T) : LoadState<T>
}

/**
 * 読み込みを [LoadState] に変換する。キャンセルだけは通し、
 * I/O 障害を含む失敗はメッセージにして画面へ渡す（再試行は画面側）。
 */
suspend fun <T> loadCatching(block: suspend () -> T): LoadState<T> =
    try {
        LoadState.Ready(block())
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Throwable) {
        LoadState.Failed(describeFailure(error))
    }

/** 例外を利用者向けの日本語にする。型名とメッセージは調査用に残す。 */
fun describeFailure(error: Throwable): String = when (error) {
    is IOException ->
        "保存領域の読み書きに失敗しました。${error.javaClass.simpleName}: ${error.message ?: "詳細なし"}"
    else ->
        "予期しない失敗です。${error.javaClass.simpleName}: ${error.message ?: "詳細なし"}"
}

fun formatNumber(value: Int?): String =
    if (value == null) "不明" else String.format(Locale.JAPAN, "%,d", value)

/** 辞書に名前があれば名前、無ければ null（呼出側が「未詳」と出す）。 */
fun resolvedCardName(cardId: String?, cards: Map<String, CardMaster>): String? =
    cardId?.let { cards[it]?.name }?.takeIf { it.isNotBlank() }

/** 見出し行。影やカードを使わず、罫線と余白だけで階層を作る。 */
@Composable
fun ScreenFrame(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) { Text("戻る") }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        HorizontalDivider()
        content()
    }
}

@Composable
fun SectionTitle(text: String) {
    Spacer(Modifier.height(20.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
}

@Composable
fun Field(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * カードの表示。名前が辞書に無いときは「未詳」とカード ID を出す（ADR 0009）。
 * ID 自体が無いときは「不明」。
 */
@Composable
fun CardValue(
    label: String,
    cardId: String?,
    cards: Map<String, CardMaster>,
    modifier: Modifier = Modifier,
) {
    val name = resolvedCardName(cardId, cards)
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        when {
            cardId == null -> Text("不明", style = MaterialTheme.typography.bodyLarge)
            name == null -> {
                Text("未詳", style = MaterialTheme.typography.bodyLarge)
                Text("カード番号: $cardId", style = MaterialTheme.typography.bodySmall)
            }
            else -> {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                Text("カード番号: $cardId", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun LoadingPane() {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text("読み込み中", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("読み込みに失敗しました", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onRetry) { Text("再試行") }
    }
}
