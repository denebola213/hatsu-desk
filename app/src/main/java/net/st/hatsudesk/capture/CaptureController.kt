package net.st.hatsudesk.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.st.hatsudesk.core.store.CaptureFile
import net.st.hatsudesk.core.store.CaptureStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 投影画面に出す状態。[CaptureController.state] が唯一の出所。 */
data class CaptureState(
    val status: ProjectionSession.State = ProjectionSession.State.IDLE,
    val hasFrame: Boolean = false,
    val savedCount: Int = 0,
    /** 最後に保存したファイルの「名前（サイズ）」。まだ保存していなければ null。 */
    val lastCapture: String? = null,
    /** 最後の取得で、押してから書き終わるまでにかかった時間（ms）。 */
    val lastCaptureMs: Long? = null,
    /** 利用者に見せる日本語の一言。失敗や拒否の理由。 */
    val message: String? = null,
) {
    /** 保存ボタンを押せるか。投影中で、フレームが1枚以上届いていること。 */
    val canSave: Boolean get() = status == ProjectionSession.State.RUNNING && hasFrame

    /**
     * 停止ボタンを押せるか。投影中だけ。
     *
     * **通知の権限に依存させない。** 通知が出ない環境（Android 13 以降で拒否）でも
     * 画面から投影を止められるようにするため、ここはセッションの状態だけで決める。
     */
    val canStop: Boolean get() = status == ProjectionSession.State.RUNNING
}

/**
 * 投影セッションの実体。`MediaProjection`・`VirtualDisplay`・`ImageReader`・最新フレームの
 * バッファを1箇所に持ち、状態は [ProjectionSession] に閉じる（設計 §4.1）。
 *
 * プロセス内で1つ（`object`）。画面（Activity）と前面サービスが同じインスタンスを見るので、
 * 画面を作り直しても投影は続き、[state] を購読すれば状態が戻る。
 *
 * 順序の約束:
 * 1. サービスが `startForeground` を済ませてから [startProjection] を呼ぶ
 *    （Android 14 以降、`getMediaProjection` は前面サービスが動いていないと通らない）
 * 2. [save] は [Dispatchers.IO] で PNG に圧縮して書き、メインスレッドで Bitmap を触らない
 * 3. 停止・失効は [stop] と [MediaProjection.Callback.onStop] のどちらから来ても
 *    [releaseLocked] を1回だけ通る
 *
 * 状態を読む・変える関数は [Synchronized] で直列化する。ImageReader のコールバックは
 * 専用の HandlerThread、保存は IO、画面とサービスは main から来るため。
 */
object CaptureController {

    private const val LOG_TAG = "hatsudesk"
    private const val VIRTUAL_DISPLAY_NAME = "hatsu-desk"

    /** 最新1枚と、書き込み中の1枚を同時に持てれば足りる。 */
    private const val MAX_IMAGES = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val session = ProjectionSession()

    /** 連打しても保存が重ならないようにする。押した回数だけ順に書く。 */
    private val saveMutex = Mutex()

    /**
     * 保存済みの枚数の読み直し。結果は [applySavedCount] でだけ反映する。
     * 読み直しと保存の反映は同じ世代番号で順序が決まり、古い一覧が後から勝たない。
     */
    private val savedCountRefresh = SavedCountRefresher(
        scope = scope,
        read = { captureStore().list().size },
        apply = { count -> applySavedCount(count) },
        onError = { Log.e(LOG_TAG, "保存済みの枚数を読めませんでした", it) },
    )

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var store: CaptureStore? = null
    private var countsLoaded = false

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null

    /**
     * 届いたフレームの置き場。**セッション世代ごとに分ける。** 停止→即再開で旧
     * `ImageReader` のコールバックが遅れて来ても、古い世代なら捨てる（[FrameStore]）。
     * 時刻は `Image.getTimestamp()`（ns、`System.nanoTime()` と同じ単調時計）。
     */
    private val frameStore = FrameStore<Image>(
        close = { image -> image.close() },
        stampOf = { image -> image.timestamp },
    )

    /**
     * 取得ボタン。投影中だけ存在する。停止・失効・サービス終了はどれも
     * [releaseLocked] を通るので、オーバーレイだけが残る経路は無い。
     *
     * **外れたと確認できるまで参照を捨てない**（[OverlayCleanup]）。停止時の
     * `removeViewImmediate()` が外れないことがあり、参照を捨てると二度と消せない。
     */
    private val overlayCleanup = OverlayCleanup<CaptureOverlayButton> { button -> button.hide() }

    /**
     * 停止の段取り。**停止済みでも残ったボタンを外しにいく**（[StopSequence]）。
     * `requestStop()` の false で早期 return すると、1回目の停止で外せなかったボタンを
     * 2回目の `stop()` で外せない（停止中は `capture()` も先に return するため、
     * 取得からも再試行できない）。
     */
    private val stopSequence = StopSequence(session, overlayCleanup) { releaseLocked() }

    /** 取得の最中か。連打で「隠す・戻す」が重ならないようにする（メインスレッドだけ）。 */
    private var capturing = false

    /** こちらの [releaseLocked] から `stop()` した時に callback が二重に片付けないための印。 */
    private var releasing = false

    private var savedCount = 0
    private var lastCapture: String? = null
    private var lastCaptureMs: Long? = null
    private var message: String? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // こちらの stop() に伴う通知なら既に片付け済み。OS 側の失効だけを拾う。
            if (releasing) return
            handleSystemStop()
        }
    }

    // ---- 画面から呼ぶ ----

    /** 画面・サービスから呼ぶ。`filesDir` を握り、保存済みの枚数を一度だけ読み込む。 */
    @Synchronized
    fun attach(context: Context) {
        appContext = context.applicationContext
        if (countsLoaded) return
        countsLoaded = true
        savedCountRefresh.refresh()
    }

    /**
     * 保存済みの枚数をディスクから数え直す。画面に戻ってきたとき（`ON_RESUME`）に呼ぶ。
     *
     * 起動時の一度きりだと、外部（adb・ファイルマネージャ）でファイルが消えたときに
     * 「保存した枚数」が実際とずれる。数えるのは表示のためだけなので、失敗しても
     * 前回の値のままにする。まだ [attach] されていなければ何もしない。
     *
     * 読み直しは並走し得る（連続した ON_RESUME）。反映するのは自分より新しい要求が
     * 始まっていない場合だけで、古い一覧が後から枚数を戻すことはない。
     */
    @Synchronized
    fun refreshSavedCount() {
        if (appContext == null) return
        savedCountRefresh.refresh()
    }

    /** 読み直した枚数を反映する。古い結果は [SavedCountRefresher] が捨てる。 */
    @Synchronized
    private fun applySavedCount(count: Int) {
        savedCount = count
        publish()
    }

    /**
     * 開始要求。同意ダイアログに渡す Intent を返す。
     * すでに投影中・同意待ちなら null（二重 start ではダイアログを出さない）。
     */
    @Synchronized
    fun requestConsentIntent(context: Context): Intent? {
        attach(context)
        if (!session.requestConsent()) return null
        message = null
        publish()
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        return manager.createScreenCaptureIntent()
    }

    /** 同意が得られた。続けて [CaptureService] を起こすのは呼出側。 */
    @Synchronized
    fun onConsentGranted() {
        if (session.consentGranted()) publish()
    }

    /** 同意が拒否された。 */
    @Synchronized
    fun onConsentDenied() {
        if (session.consentDenied()) {
            message = "同意が得られなかったので投影しません"
            publish()
        }
    }

    /** サービスの起動に失敗した等、投影を始められなかった。 */
    @Synchronized
    fun failStart(reason: String) {
        // 停止が成立すればセッションも解放する。成立しなくても、残ったボタンは外しにいく。
        stopSequence.stop()
        message = reason
        publish()
    }

    /**
     * `startForeground` の後にサービスから呼ぶ。成功したら true。
     * 二重に呼ばれても既存のセッションを壊さない（2回目は true を返して何もしない）。
     */
    @Synchronized
    fun startProjection(resultCode: Int, data: Intent): Boolean {
        val context = appContext
        if (context == null) {
            message = "投影を開始できませんでした（初期化前）"
            publish()
            return false
        }
        if (!session.isRunning) {
            message = "同意の状態が揃っていないので投影を開始しません"
            publish()
            return false
        }
        if (projection != null) return true

        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val captured = try {
            manager.getMediaProjection(resultCode, data)
        } catch (error: SecurityException) {
            Log.e(LOG_TAG, "getMediaProjection に失敗しました", error)
            null
        }
        if (captured == null) {
            session.requestStop()
            message = "投影を開始できませんでした（同意が無効になりました）"
            publish()
            return false
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val projectionThread = HandlerThread("hatsu-desk-projection").apply { start() }
        val handler = Handler(projectionThread.looper)
        // フレームの受け口はセッション世代ごとに新しくする。停止→即再開で旧 ImageReader の
        // コールバックが遅れて来ても、古い世代の受け口には入らない（FrameStore）。
        val frameSession = frameStore.begin()
        val imageReader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        } catch (error: Throwable) {
            frameStore.end()
            projectionThread.quitSafely()
            runCatching { captured.stop() }
            session.requestStop()
            message = "フレームの受け口を作れませんでした"
            publish()
            return false
        }

        // acquireLatestImage は「最新1枚」を返し、それより古いものは捨てる。
        // 取った Image は FrameStore に渡し、置き換え・破棄のどこかで必ず閉じる。
        imageReader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (error: IllegalStateException) {
                Log.e(LOG_TAG, "フレームを取得できませんでした", error)
                null
            } ?: return@setOnImageAvailableListener
            // フレームの時刻（合成された時刻）は FrameStore が覚える。取得は「隠し終えた
            // 時刻より後か」だけを見るので、ここで数えるものは無い。
            // 古い世代のフレームは、ここで閉じたうえで捨てられる（保存に使わない）。
            when (frameStore.offer(frameSession, image)) {
                FrameOffer.STALE -> Unit
                FrameOffer.FIRST -> scope.launch { onFirstFrame() }
                FrameOffer.NEXT -> Unit
            }
        }, handler)

        captured.registerCallback(projectionCallback, handler)

        val virtualDisplay = try {
            captured.createVirtualDisplay(
                VIRTUAL_DISPLAY_NAME,
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                handler,
            )
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "VirtualDisplay を作れませんでした", error)
            releasing = true
            try {
                imageReader.setOnImageAvailableListener(null, null)
                imageReader.close()
                frameStore.end()
                runCatching { captured.unregisterCallback(projectionCallback) }
                runCatching { captured.stop() }
            } finally {
                releasing = false
                projectionThread.quitSafely()
            }
            session.requestStop()
            message = "VirtualDisplay を作れませんでした"
            publish()
            return false
        }

        projection = captured
        display = virtualDisplay
        reader = imageReader
        thread = projectionThread
        message = null
        startOverlayLocked()
        publish()
        return true
    }

    /**
     * 停止。停止が成立したら状態と一言を更新する。
     *
     * **停止済みでも、前回の停止で外せなかったボタンを外しにいく**（[StopSequence]）。
     * ここが「停止中でも呼べる掃除の経路」。参照は外れたと確認できたときだけ手放す。
     */
    @Synchronized
    fun stop(reason: String? = null) {
        val stopped = stopSequence.stop()
        if (!stopped) return
        message = reason
        publish()
    }

    /**
     * 停止時に外せなかったボタンを、もう一度外しにいく。画面に戻ってきたとき（`ON_RESUME`）に呼ぶ。
     * 投影中は触らない。外れたら参照を手放し、外れなければ次の機会に持ち越す。
     */
    @Synchronized
    fun retryOverlayRemoval() {
        stopSequence.sweepIfStopped()
    }

    /** いまのフレームを1枚、`files/captures/<timestamp>.png` に保存する（アプリ画面のボタン）。 */
    fun save() = capture(notifyWithToast = false)

    /** オーバーレイのボタンから保存する。押した本人が画像に写らないようにする。 */
    fun captureFromOverlay() = capture(notifyWithToast = true)

    /**
     * 1枚保存する。
     *
     * **`FLAG_SECURE` は焼き付きを消さない。** 実機ではボタン窓の矩形が投影フレームで
     * 完全な黒 (0, 0, 0) になる。この黒を「ボタンがまだ写っている」印に使う。
     * 順序は、押下時の矩形を控える → 隠す → 候補を撮る → **矩形が非黒なのを確かめる**
     * → 写し取る → 戻す。黒い候補は捨て、次のフレームでやり直す。
     *
     * **時刻では焼き付きを証明できない。** `Image.getTimestamp()` は合成時刻ではなく、
     * 最後の sink へ `queueBuffer()` した時刻なので、隠す前に合成された絵が隠した後に
     * キューされれば「隠した後」の時刻を持つ。時刻は候補を絞る一次フィルタにだけ使い、
     * **保存してよいかの最終判断は候補の黒い印**（[OverlayPixelCheck]）で行う。
     *
     * 1. 押した瞬間の最新フレームから、ボタンの矩形の画素を控える
     * 2. 隠す（現行の手順のまま）
     * 3. 候補フレームの同じ矩形を読む
     * 4. 平均輝度が 3 以上＝非黒なら保存。3 未満＝まだ黒い印が写っているので捨てる
     * 5. 捨てたら次のフレームでやり直す（[VerifiedFrameGate]）
     * 6. [CaptureDeadline.TIMEOUT_MS] を超えたら保存せず [CapturePlan.STALLED_MESSAGE]
     *
     * 下の画面そのものが真っ黒なら、ボタンを隠した後も黒いままになる。この場合も保存せず、
     * 時間切れの文言でボタンを動かすよう人に知らせる。下の絵の変化との差は判定に使わない。
     *
     * 隠すボタンが無い（オーバーレイ未許可）ときは隠すものが無いので、待たずに最新1枚を
     * そのまま保存する。静止した画面では新しいフレームが来ないので、待つと必ず失敗する。
     */
    private fun capture(notifyWithToast: Boolean) {
        // 取得中に押された分は捨てる。隠している最中に次の「隠す」が重なると、
        // 戻す順序が入れ替わってボタンが消えたままになる。
        if (capturing) return
        if (!session.isRunning) {
            report("投影していないので保存できません", notifyWithToast)
            return
        }
        // この取得が対象にする世代。途中で停止→再開しても、古い世代のフレームは使わない。
        val frames = frameStore.current()
        if (frames == null || !frameStore.hasFrame(frames)) {
            report("フレームがまだ届いていません", notifyWithToast)
            return
        }

        val button = overlayCleanup.peek()
        // 画素で比べる矩形。ボタンが画面に載っていなければ読めない。
        val rect = button?.boundsOnScreen()
        if (button != null && rect == null) {
            // どこを見ればよいか分からないまま「隠した」と判断しない。保存しない側に倒す。
            report("取得ボタンの位置を確認できないため保存しませんでした", notifyWithToast)
            return
        }

        capturing = true
        val pressedAt = SystemClock.elapsedRealtime()

        scope.launch {
            // 隠せたときだけ戻す。隠せていない（状態が不確かな）ときに show() を呼ぶと、
            // 画面に残っている View へ addView してしまい、かえって消すことになる。
            var pendingRestore: CaptureOverlayButton? = null
            try {
                // 1. 押した瞬間の最新フレームから、ボタンの矩形の画素を控える。
                //    まだ隠していないので、この1枚にはボタンが写っている。
                val pressedRect = if (rect != null) grabRect(frames, rect) else null
                if (rect != null && pressedRect == null) {
                    val text = "取得ボタンの画素を読めないため保存しませんでした"
                    setMessage(text)
                    if (notifyWithToast) toast(text)
                    return@launch
                }
                // 2. 隠す。隠せなかったら撮らない（隠れていないボタンが写った1枚を残さない）。
                val hidden = button?.hide() ?: true
                pendingRestore = if (hidden) button else null
                if (!hidden) {
                    val text = "取得ボタンを隠せなかったため保存しませんでした"
                    setMessage(text)
                    if (notifyWithToast) toast(text)
                    return@launch
                }
                // ここが「隠し終えた時刻」。時刻は一次フィルタにだけ使う。
                val hideCompletedAtNanos = if (button != null) System.nanoTime() else null
                val hiddenAt = SystemClock.elapsedRealtime()
                val plan = CapturePlan.forPress(hideCompletedAtNanos)
                val check = if (rect != null && pressedRect != null) OverlayPixelCheck(rect, pressedRect) else null
                val gate = VerifiedFrameGate(plan, check)
                val deadline = CaptureDeadline(hiddenAt)

                // 3〜6. 候補を撮り、画素で確かめ、駄目なら次のフレームでやり直す。
                var snapshot: Bitmap? = null
                var discarded = 0
                while (snapshot == null) {
                    val remaining = deadline.remainingMillis(SystemClock.elapsedRealtime())
                    if (remaining <= 0) break
                    // 条件を満たす候補がすでにあれば first はその場で返る。
                    val woke = withTimeoutOrNull(remaining) { frames.stamp.first { gate.wants(it) } }
                    if (woke == null) break
                    when (val shot = takeShot(frames, gate, rect)) {
                        null -> break // 世代が変わった（停止→再開）。古いフレームは使わない。
                        is Shot.Burned -> {
                            gate.discard(shot.stampNanos)
                            discarded += 1
                        }
                        is Shot.Taken -> snapshot = shot.bitmap
                    }
                }
                // 画素を写し取ったらすぐ戻す。PNG の圧縮と書き込みの間、画面に穴を空けない。
                pendingRestore?.let { restoreOverlay(it) }
                pendingRestore = null
                val grabbedAt = SystemClock.elapsedRealtime()

                if (snapshot == null) {
                    // 隠した後のフレームが来なかったか、来てもボタンが残っていた。保存しない。
                    // 途中で停止された場合は、停止の一言を上書きしない（投影の話ではない）。
                    if (session.isRunning) {
                        setMessage(CapturePlan.STALLED_MESSAGE)
                        if (notifyWithToast) toast(CapturePlan.STALLED_MESSAGE)
                    }
                    Log.w(LOG_TAG, "capture: 焼き付きを排除できず中止（捨てた候補=$discarded）")
                    return@launch
                }
                val stored = storeFrame(snapshot)
                val totalMs = SystemClock.elapsedRealtime() - pressedAt
                if (stored == null) {
                    if (notifyWithToast) toast("保存に失敗しました")
                    return@launch
                }
                val (file, total) = stored
                Log.i(
                    LOG_TAG,
                    "capture: press→hidden=${hiddenAt - pressedAt}ms, " +
                        "hidden→grab=${grabbedAt - hiddenAt}ms, " +
                        "grab→done=${totalMs - (grabbedAt - pressedAt)}ms, " +
                        "discarded=$discarded, total=${totalMs}ms, bytes=${file.sizeBytes}",
                )
                synchronized(this@CaptureController) {
                    lastCapture = "${file.name}（${formatSize(file.sizeBytes)}）"
                    lastCaptureMs = totalMs
                    message = "1枚保存しました"
                    // 保存の反映も読み直しと同じ世代で順序を決める（古い一覧に巻き戻されない）。
                    savedCountRefresh.record(total)
                }
                publish()
                if (notifyWithToast) toast("保存しました")
            } finally {
                pendingRestore?.let { restoreOverlay(it) }
                capturing = false
            }
        }
    }

    /** 画面の向きが変わった。ボタンが画面の外に残らないよう位置を移す。 */
    @Synchronized
    fun onConfigurationChanged() {
        overlayCleanup.peek()?.onScreenChanged()
    }

    // ---- 内部 ----

    /**
     * 取得ボタンを出す。`SYSTEM_ALERT_WINDOW` が無いときは出さない（投影は続ける）。
     * 権限は人が設定画面で許可するもので、ここから要求はしない。
     */
    private fun startOverlayLocked() {
        val context = appContext ?: return
        if (overlayCleanup.isHeld) return
        if (!Settings.canDrawOverlays(context)) {
            message = "オーバーレイの権限が無いため、取得ボタンは出していません"
            return
        }
        val button = CaptureOverlayButton(context) { captureFromOverlay() }
        if (button.show()) {
            overlayCleanup.hold(button)
            return
        }
        // 出したものの外せなかった View は、停止時に外せるよう参照を残す。
        // 参照を失うと、画面に残ったボタンを二度と消せない。
        if (button.isAttached) {
            overlayCleanup.hold(button)
            message = "取得ボタンの準備に失敗しました"
        } else {
            message = "取得ボタンを出せませんでした"
        }
    }

    /**
     * 取得のために隠したボタンを戻す。**投影が終わっていれば戻さない。**
     * 停止と取得が重なったときに、投影の無い画面へボタンだけが戻る経路を塞ぐ。
     */
    @Synchronized
    private fun restoreOverlay(button: CaptureOverlayButton) {
        if (overlayCleanup.peek() !== button) return
        if (!session.isRunning) return
        if (!button.show()) Log.w(LOG_TAG, "取得ボタンを戻せませんでした")
    }

    /** 失敗の一言。オーバーレイから押されたときは画面の外にいるのでトーストでも知らせる。 */
    @Synchronized
    private fun report(text: String, notifyWithToast: Boolean) {
        message = text
        publish()
        if (notifyWithToast) toast(text)
    }

    private fun toast(text: String) {
        val context = appContext ?: return
        runCatching { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    @Synchronized
    private fun onFirstFrame() {
        if (session.isRunning && frameStore.hasFrame()) publish()
    }

    @Synchronized
    private fun handleSystemStop() {
        // このコールバックは投影用の HandlerThread で走る。停止の片付けは WindowManager を
        // 触る（ボタンを外す）ので、main へ渡してから行う。
        scope.launch { onStoppedBySystem() }
    }

    @Synchronized
    private fun onStoppedBySystem() {
        // 停止済みでも、前回外せなかったボタンはここで試し直す（StopSequence）。
        if (!stopSequence.stop()) return
        message = "OS 側で投影が終了しました"
        publish()
    }

    /** リソースを手放す。何度呼んでも安全（null なら何もしない）。 */
    @Synchronized
    private fun releaseLocked() {
        releasing = true
        try {
            // オーバーレイは投影より先に消す。投影が止まった後にボタンだけ残る経路を作らない。
            // 外せなかったときは参照を捨てない（次の停止・画面の再表示で外し直せるようにする）。
            if (!overlayCleanup.release()) {
                Log.e(LOG_TAG, "取得ボタンが画面に残っています。次の停止で外し直します")
            }
            // 世代ごと捨てる。以後その世代へ届いたフレームは閉じられ、保存に使われない。
            frameStore.end()
            reader?.setOnImageAvailableListener(null, null)
            reader?.close()
            reader = null
            display?.release()
            display = null
            val captured = projection
            projection = null
            if (captured != null) {
                runCatching { captured.unregisterCallback(projectionCallback) }
                runCatching { captured.stop() }
            }
            thread?.quitSafely()
            thread = null
        } finally {
            releasing = false
        }
    }

    /** 画素で確かめた1枚。[Burned] は黒い印が残っている＝保存しない候補。 */
    private sealed interface Shot {
        class Taken(val bitmap: Bitmap, val stampNanos: Long) : Shot
        class Burned(val stampNanos: Long) : Shot
    }

    /**
     * ボタンの矩形の画素を、押した瞬間の最新フレームから控える。
     * 矩形の位置と画素数を確かめるためのもので、候補との差分比較には使わない。
     * 矩形が画像に収まらない・フレームが無いときは null。
     */
    private suspend fun grabRect(frames: FrameStore.Session<Image>, rect: PixelRect): IntArray? =
        withContext(Dispatchers.Default) {
            frameStore.withLatest(frames) { image -> image.copyRectArgb(rect) }
        }

    /**
     * 候補フレームを1枚取り、**保存してよいかを黒い印で確かめる**。バッファは消費しない。
     *
     * 矩形の平均輝度が 3 以上なら [Shot.Taken]（全体を Bitmap に写す）。3 未満なら
     * [Shot.Burned]。Bitmap への写しは判断が通った1枚だけでよい。
     */
    private suspend fun takeShot(
        frames: FrameStore.Session<Image>,
        gate: VerifiedFrameGate,
        rect: PixelRect?,
    ): Shot? = withContext(Dispatchers.Default) {
        frameStore.withLatestAfter(frames, gate.sinceNanos) { image ->
            val stamp = image.timestamp
            if (rect == null) {
                Shot.Taken(image.copyToBitmap(), stamp)
            } else {
                val candidate = image.copyRectArgb(rect)
                // 矩形が読めない（画面の大きさが変わった等）ときは保存しない側に倒す。
                if (candidate == null || !gate.keeps(candidate)) {
                    Shot.Burned(stamp)
                } else {
                    Shot.Taken(image.copyToBitmap(), stamp)
                }
            }
        }
    }

    /** PNG にして `captures/` へ書く。書けたらファイルと保存後の枚数。失敗は null。 */
    private suspend fun storeFrame(snapshot: Bitmap): Pair<CaptureFile, Int>? =
        saveMutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    try {
                        val store = captureStore()
                        // 書き込み前の一覧で名前を決め、保存後の実数も同じ一覧から出す。
                        // 加算にすると、このファイルを数えた読み直しと二重に数えてしまう。
                        val existing = store.list()
                        val name = uniqueName(existing.map { it.name }.toSet())
                        store.save(name, compressPng(snapshot)) to (existing.size + 1)
                    } finally {
                        snapshot.recycle()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.e(LOG_TAG, "フレームを保存できませんでした", error)
                setMessage("保存に失敗しました（${describe(error)}）")
                null
            }
        }

    /**
     * RGBA_8888 の [Image] から、指定した矩形の画素を ARGB の配列で読む（行優先）。
     *
     * 全体を Bitmap に写すと 1080×2410 で 10MB になるが、ボタンの矩形は 116×116 で約 54KB。
     * 判定のたびに全体を写さずに済むよう、必要なところだけ読む。矩形が画像に収まらなければ null。
     */
    private fun Image.copyRectArgb(rect: PixelRect): IntArray? {
        if (!rect.fitsIn(width, height)) return null
        val plane = planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        if (pixelStride < 3) return null
        val buffer = plane.buffer
        val pixels = IntArray(rect.pixelCount)
        var index = 0
        for (row in rect.y until rect.y + rect.height) {
            val rowStart = row * plane.rowStride
            for (column in rect.x until rect.x + rect.width) {
                val offset = rowStart + column * pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                pixels[index] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                index += 1
            }
        }
        return pixels
    }

    /**
     * RGBA_8888 の [Image] から、行のパディングを落とした [Bitmap] を作る。
     * 呼出側が持ち主になる（使い終わったら `recycle()`）。
     */
    private fun Image.copyToBitmap(): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        if (pixelStride <= 0) throw IOException("画素の間隔が読めません")
        val paddedWidth = plane.rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        try {
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == width) return padded
            val visible = Bitmap.createBitmap(padded, 0, 0, width, height)
            padded.recycle()
            return visible
        } catch (error: Throwable) {
            padded.recycle()
            throw error
        }
    }

    private fun compressPng(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            throw IOException("PNG に変換できませんでした")
        }
        return out.toByteArray()
    }

    /** 同じミリ秒に2回押されても上書きしないよう、空いている名前まで連番を足す。 */
    private fun uniqueName(taken: Set<String>): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        var name = "$stamp.png"
        var index = 2
        while (name in taken) {
            name = "$stamp-$index.png"
            index += 1
        }
        return name
    }

    /** I/O スレッドからだけ呼ぶ。最初の利用で `captures/` を作る。 */
    private fun captureStore(): CaptureStore {
        store?.let { return it }
        val context = appContext ?: throw IOException("初期化されていません")
        return CaptureStore(context.filesDir.toPath()).also { store = it }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else String.format(Locale.JAPAN, "%.1f KB", bytes / 1024.0)

    private fun describe(error: Throwable): String = when (error) {
        is IOException -> "書き込みに失敗しました"
        is OutOfMemoryError -> "画像が大きすぎます"
        else -> error.javaClass.simpleName
    }

    @Synchronized
    private fun setMessage(text: String) {
        message = text
        publish()
    }

    @Synchronized
    private fun publish() {
        _state.value = CaptureState(
            status = session.state,
            hasFrame = session.isRunning && frameStore.hasFrame(),
            savedCount = savedCount,
            lastCapture = lastCapture,
            lastCaptureMs = lastCaptureMs,
            message = message,
        )
    }
}
