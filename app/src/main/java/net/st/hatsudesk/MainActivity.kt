package net.st.hatsudesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import net.st.hatsudesk.data.RunRepository
import net.st.hatsudesk.ui.HatsuDeskApp

/**
 * M0 の器。一覧・構成詳細・カード辞書・手入力の4画面を持つ。
 * 保存先は `filesDir` の直下で、DocumentStore が `runs/`・`cards/` を作る。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = RunRepository(filesDir)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HatsuDeskApp(repository)
                }
            }
        }
    }
}
