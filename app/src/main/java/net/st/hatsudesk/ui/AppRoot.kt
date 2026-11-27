package net.st.hatsudesk.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
