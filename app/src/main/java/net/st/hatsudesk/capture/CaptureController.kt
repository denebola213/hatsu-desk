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
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private var buffer: LatestFrameBuffer? = null

    /** こちらの [releaseLocked] から `stop()` した時に callback が二重に片付けないための印。 */
    private var releasing = false

    private var savedCount = 0
    private var lastCapture: String? = null
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
        if (!session.requestStop()) {
            message = reason
            publish()
            return
        }
        releaseLocked()
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
        val frames = LatestFrameBuffer()
        val imageReader = try {
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES)
        } catch (error: Throwable) {
            projectionThread.quitSafely()
            runCatching { captured.stop() }
            session.requestStop()
            message = "フレームの受け口を作れませんでした"
            publish()
            return false
        }

        // acquireLatestImage は「最新1枚」を返し、それより古いものは捨てる。
        // 取った Image は LatestFrameBuffer に渡し、置き換え・取り出し・破棄のどこかで必ず閉じる。
        imageReader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireLatestImage()
            } catch (error: IllegalStateException) {
                Log.e(LOG_TAG, "フレームを取得できませんでした", error)
                null
            } ?: return@setOnImageAvailableListener
            val wasEmpty = !frames.hasFrame()
            frames.offer(image)
            if (wasEmpty) scope.launch { onFirstFrame() }
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
        buffer = frames
        message = null
        publish()
        return true
    }

    /** 停止。すでに停止していれば何もしない。 */
    @Synchronized
    fun stop(reason: String? = null) {
        if (!session.requestStop()) return
        releaseLocked()
        message = reason
        publish()
    }

    /** いまのフレームを1枚、`files/captures/<timestamp>.png` に保存する。 */
    fun save() {
        if (!session.isRunning) {
            setMessage("投影していないので保存できません")
            return
        }
        if (buffer?.hasFrame() != true) {
            setMessage("フレームがまだ届いていません")
            return
        }
        scope.launch {
            saveMutex.withLock { writeOneFrame() }
        }
    }

    // ---- 内部 ----

    @Synchronized
    private fun onFirstFrame() {
        if (session.isRunning && buffer?.hasFrame() == true) publish()
    }

    @Synchronized
    private fun handleSystemStop() {
        if (!session.onSystemStop()) return
        releaseLocked()
        message = "OS 側で投影が終了しました"
        publish()
    }

    /** リソースを手放す。何度呼んでも安全（null なら何もしない）。 */
    @Synchronized
    private fun releaseLocked() {
        releasing = true
        try {
            buffer?.clear()
            buffer = null
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

    private suspend fun writeOneFrame() {
        val frames = buffer ?: run {
            setMessage("フレームがありません")
            return
        }
        val saved = try {
            withContext(Dispatchers.IO) {
                // 最新1枚を写し取る。バッファからは消さない（静止画でも何度でも保存できる）。
                val snapshot = frames.withLatest { image -> image.copyToBitmap() }
                if (snapshot == null) {
                    null
                } else {
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
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.e(LOG_TAG, "フレームを保存できませんでした", error)
            setMessage("保存に失敗しました（${describe(error)}）")
            return
        }

        if (saved == null) {
            setMessage("フレームがまだ届いていません")
            return
        }
        val (file, total) = saved

        // 投影が止まった後に書き終わった場合でも、枚数とファイル名は出す。
        synchronized(this) {
            lastCapture = "${file.name}（${formatSize(file.sizeBytes)}）"
            message = "1枚保存しました"
            // 保存の反映も読み直しと同じ世代で順序を決める（古い一覧に巻き戻されない）。
            savedCountRefresh.record(total)
        }
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
            hasFrame = session.isRunning && buffer?.hasFrame() == true,
            savedCount = savedCount,
            lastCapture = lastCapture,
            message = message,
        )
    }
}
