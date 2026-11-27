package net.st.hatsudesk.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import net.st.hatsudesk.MainActivity
import net.st.hatsudesk.R

/**
 * 投影を維持する前面サービス（`mediaProjection` 型）。設計 §4.1 の常駐通知。
 *
 * 役割は2つだけ。プロセスを前面に留めて `MediaProjection` を失効させないこと、通知の
 * 「停止」から投影を終わらせられること。フレームの保持と保存は [CaptureController]。
 *
 * **`startForeground` を `getMediaProjection` より先に呼ぶ。** Android 14 以降、
 * `mediaProjection` 型の前面サービスが動いていない状態で `getMediaProjection` を呼ぶと
 * `SecurityException` になる。順序を守るために、投影の開始もこのサービスの中で行う
 * （Activity からは「同意の結果」を Intent で渡すだけ）。
 */
class CaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 一度でも投影を始めたか。始める前の IDLE で自分を止めないための印。 */
    private var started = false

    override fun onCreate() {
        super.onCreate()
        CaptureController.attach(this)
        ensureChannel()
        // OS 側の失効（他アプリが投影を開始した、など）で通知を残さない。
        scope.launch {
            CaptureController.state.collect { current ->
                if (started && current.status == ProjectionSession.State.IDLE) {
                    started = false
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CaptureController.stop("通知から停止しました")
                return START_NOT_STICKY
            }
            else -> handleStart(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent?) {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let(::projectionData)

        // 先に前面へ。getMediaProjection はこの後。
        startForegroundCompat()

        if (resultCode != Activity.RESULT_OK || data == null) {
            CaptureController.failStart("同意の結果を読めなかったので投影しません")
            stopSelf()
            return
        }
        if (CaptureController.startProjection(resultCode, data)) {
            started = true
        } else {
            stopSelf()
        }
    }

    @Suppress("DEPRECATION")
    private fun projectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_DATA)
        }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_projection)
            .setContentTitle("画面を投影中")
            .setContentText("「停止」で投影を終わります")
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_projection),
                    "停止",
                    stop,
                ).build(),
            )
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投影", NotificationManager.IMPORTANCE_LOW).apply {
                description = "画面の投影中に出す常駐通知"
                setShowBadge(false)
            },
        )
    }

    override fun onDestroy() {
        scope.cancel()
        // サービスが消えるなら投影も終える（通知からも OS からも来ない経路の保険）。
        CaptureController.stop("投影を終了しました")
        super.onDestroy()
    }

    /**
     * 画面の回転。オーバーレイのボタンは Activity の一部ではないので、ここで位置を
     * 移し直さないと画面の外に取り残される（ボタン自身も DisplayManager を見ているが、
     * サービスが生きている間はこちらが確実）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        CaptureController.onConfigurationChanged()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "projection"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "net.st.hatsudesk.capture.START"
        private const val ACTION_STOP = "net.st.hatsudesk.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "result_data"

        /** 同意の結果を持ってサービスを起こす。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }
    }
}
