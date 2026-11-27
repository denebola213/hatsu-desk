package net.st.hatsudesk.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import net.st.hatsudesk.capture.CaptureController
import net.st.hatsudesk.capture.CaptureService
import net.st.hatsudesk.capture.ProjectionSession

/** 通知が許可されていないときに、開始前に画面へ出す説明（理由と影響）。 */
private const val NOTIFICATION_DENIED_NOTICE =
    "通知が許可されていないため、投影中も通知は表示されません。" +
        "停止はこの画面の「投影を停止」から行えます。"

/**
 * M1-a の暫定画面。設計 §4.1 の「常時バッファ＋取得ボタン」のうち、オーバーレイではなく
 * アプリの画面にボタンを置いたもの（M1-b でオーバーレイへ移す）。
 *
 * **同意ダイアログは OS のもの**で、押すのは人間。ここは `createScreenCaptureIntent()` を
 * 起動するだけで、同意そのものを代行しない（設計 §4・§12）。
 */
@Composable
fun ProjectionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by CaptureController.state.collectAsState()

    // 同意の結果。OK なら前面サービスを起こし、そこで startForeground → getMediaProjection の順に進む。
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CaptureController.onConsentGranted()
            try {
                CaptureService.start(context, result.resultCode, data)
            } catch (error: Throwable) {
                // Android 12 以降、背景からの前面サービス起動は制限される。画面からなら通るはずだが、
                // 失敗したまま「投影中」と表示しない。
                CaptureController.failStart("前面サービスを開始できませんでした")
            }
        } else {
            CaptureController.onConsentDenied()
        }
    }

    // 通知の許可は同意より先に片付ける。2つのダイアログを同時に出すと、どちらが前面に
    // 出るか保証されない（許可の結果を受けてから同意を出す）。
    var waitingForNotification by remember { mutableStateOf(false) }

    val launchConsent = {
        val intent = CaptureController.requestConsentIntent(context)
        if (intent != null) consent.launch(intent)
    }

    // 通知は「あれば使う」だけ。Android 13 以降で拒否されると前面サービスの通知は
    // ドロワーに出ないが、停止はこの画面の「投影を停止」で行えるので、拒否されても
    // そのまま同意へ進む（投影を止められない状態を作らない）。理由と影響は開始前に見せる。
    var notificationDenied by remember { mutableStateOf(!notificationPermissionGranted(context)) }

    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationDenied = !granted
        if (waitingForNotification) {
            waitingForNotification = false
            launchConsent()
        }
    }

    LaunchedEffect(Unit) { CaptureController.attach(context) }

    // 画面に戻ってきたとき（ON_RESUME）に読み直す。起動時の一度きりだと、外部で
    // ファイルが消えたときに「保存した枚数」がずれ、通知を後から許可しても説明が残る。
    LifecycleResumeEffect(context) {
        CaptureController.refreshSavedCount()
        notificationDenied = !notificationPermissionGranted(context)
        onPauseOrDispose {}
    }

    ScreenFrame(title = "投影", onBack = onBack) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            SectionTitle("状態")
            Field("投影", statusLabel(state.status))
            Field("フレーム", if (state.hasFrame) "あり（最新1枚を保持）" else "なし")
            Field("保存した枚数", "${state.savedCount} 枚")
            state.lastCapture?.let { Field("最後に保存したファイル", it) }
            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (notificationDenied) {
                Spacer(Modifier.height(8.dp))
                Text(NOTIFICATION_DENIED_NOTICE, style = MaterialTheme.typography.bodyMedium)
            }

            SectionTitle("操作")
            Button(
                onClick = {
                    if (notificationPermissionGranted(context)) {
                        notificationDenied = false
                        launchConsent()
                    } else {
                        waitingForNotification = true
                        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                enabled = state.status == ProjectionSession.State.IDLE,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("投影を開始") }

            // 通知の「停止」が出ない環境でも止められるよう、投影中は常にこの画面に出す。
            // 通知の「停止」と同じ CaptureController.stop() を通るので、両方押されても
            // 二重に解放しない（停止済みなら何もしない）。
            if (state.canStop) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { CaptureController.stop("投影を停止しました") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("投影を停止") }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { CaptureController.save() },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("いまのフレームを1枚保存") }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text(
                "停止はこの画面の「投影を停止」から行えます（通知が出ているときは通知の" +
                    "「停止」からも止められます）。停止すると投影は終わり、次に始めるときは" +
                    "もう一度同意が必要です。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun statusLabel(status: ProjectionSession.State): String = when (status) {
    ProjectionSession.State.IDLE -> "停止中"
    ProjectionSession.State.AWAITING_CONSENT -> "同意待ち"
    ProjectionSession.State.RUNNING -> "投影中"
}

private fun notificationPermissionGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
}
