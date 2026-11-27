package net.st.hatsudesk

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * M0 の器。ここに取り込み・一覧・構成詳細が載る。
 * いまは「ビルドが実機で動く」ことと、版がピン留めされていることの確認だけを行う。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BuildInfoScreen()
                }
            }
        }
    }
}

@Composable
private fun BuildInfoScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("構成ビューア", style = MaterialTheme.typography.headlineMedium)
        Text("ランキングの構成を端末内に貯める。通信しない。", style = MaterialTheme.typography.bodyMedium)

        HorizontalDivider()

        Row("アプリ", BuildConfig.VERSION_NAME)
        Row("compileSdk", BuildConfig.COMPILE_SDK)
        Row("build-tools", BuildConfig.BUILD_TOOLS_PIN)
        Row("targetSdk", BuildConfig.TARGET_SDK)
        Row("端末", "${Build.MANUFACTURER} ${Build.MODEL}")
        Row("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        Row("ABI", Build.SUPPORTED_ABIS.joinToString(", "))

        HorizontalDivider()

        Text(
            "取り込み機能はまだ入っていない。次は M1（画面録画と取得ボタン）。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Row(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
