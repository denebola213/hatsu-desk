package net.st.hatsudesk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.st.hatsudesk.data.RunRepository

/** 画面。Navigation ライブラリは入れず、スタックを1つ持つだけで切り替える。 */
sealed interface Screen {
    data object RunList : Screen
    data class RunDetail(val runId: String) : Screen
    data object CardDictionary : Screen
    data object ManualEntry : Screen
}

@Composable
fun HatsuDeskApp(repository: RunRepository) {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.RunList)) }

    // 一覧は保存後に読み直す。世代を上げて LaunchedEffect を再実行させる。
    var listGeneration by remember { mutableIntStateOf(0) }

    val pop: () -> Unit = {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    // 端末の安全領域（ステータスバー・ジェスチャーバー・切り欠き）をここで一度だけ空ける。
    // Android 15 以降は edge-to-edge が強制されるので、各画面に任せずルートで受ける。
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (val current = stack.last()) {
            Screen.RunList -> RunListScreen(
                repository = repository,
                generation = listGeneration,
                onOpenRun = { runId -> stack = stack + Screen.RunDetail(runId) },
                onManualEntry = { stack = stack + Screen.ManualEntry },
                onDictionary = { stack = stack + Screen.CardDictionary },
            )

            is Screen.RunDetail -> RunDetailScreen(
                repository = repository,
                runId = current.runId,
                onBack = pop,
            )

            Screen.CardDictionary -> CardDictionaryScreen(
                repository = repository,
                onBack = pop,
            )

            Screen.ManualEntry -> ManualEntryScreen(
                repository = repository,
                onBack = pop,
                onSaved = {
                    listGeneration += 1
                    stack = listOf(Screen.RunList)
                },
            )
        }
    }
}
