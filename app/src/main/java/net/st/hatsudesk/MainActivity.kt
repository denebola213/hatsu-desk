package net.st.hatsudesk

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import net.st.hatsudesk.data.RunRepository
import net.st.hatsudesk.ui.HatsuDeskApp

/**
 * M0 の器。一覧・構成詳細・カード辞書・手入力の4画面を持つ。
 * 保存先は `filesDir` の直下で、DocumentStore が `runs/`・`cards/` を作る。
 *
 * 端末のバーはアプリの背景（[Surface]）をそのまま見せる。バー自体に色は塗らない
 * （API 35 以降 `window.statusBarColor` は効かない）。明るい背景なので、
 * アイコンだけ濃い色にする。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
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
