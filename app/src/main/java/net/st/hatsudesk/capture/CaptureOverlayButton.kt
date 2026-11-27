package net.st.hatsudesk.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 画面の上に出す小さな取得ボタン（設計 §4.1）。押すと [onCapture] を呼ぶ。
 *
 * ゲームの操作を壊さないための約束:
 * - `FLAG_NOT_FOCUSABLE` … キー入力もフォーカスも奪わない。ボタンの外のタッチはそのまま
 *   下のアプリへ抜ける（`FLAG_NOT_TOUCH_MODAL`）。ボタンの上だけは自分が受け取る
 * - 見た目は円ひとつ。影もカードも無い。押している間だけ少し濃くする
 *
 * 位置は [OverlayPosition] が持ち、ドラッグで動き、離すと左右の近い端へ寄る。
 * 画面の大きさが変わったら [DisplayManager] の通知で追従し、画面の外へは出さない。
 *
 * インスタンスはメインスレッドからだけ触る（WindowManager の作法）。
 */
class CaptureOverlayButton(
    context: Context,
    private val onCapture: () -> Unit,
) {

    private val appContext = context.applicationContext
    private val windowManager: WindowManager = appContext.getSystemService(WindowManager::class.java)
    private val displayManager: DisplayManager = appContext.getSystemService(DisplayManager::class.java)
    private val density = appContext.resources.displayMetrics.density
    private val sizePx = (SIZE_DP * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(appContext).scaledTouchSlop
    private val mainHandler = Handler(Looper.getMainLooper())

    private val button = ButtonView(appContext)

    private var params: WindowManager.LayoutParams? = null
    private var screen = ScreenSize(0, 0)

    /**
     * 出し入れの段取り。**外せなかった View は参照を失わない**（`addView` の後に listener の
     * 登録が失敗する経路があるため）。成否はここだけで決める。
     */
    private val attachment = OverlayAttachment(
        object : OverlayAttachment.Window {
            override fun addView() {
                params?.let { windowManager.addView(button, it) } ?: throw IllegalStateException("レイアウトが無い")
            }

            override fun register() {
                displayManager.registerDisplayListener(displayListener, mainHandler)
            }

            override fun unregister() {
                runCatching { displayManager.unregisterDisplayListener(displayListener) }
                    .onFailure { Log.w(LOG_TAG, "画面の変化の追従を止められませんでした", it) }
            }

            override fun isOnScreen(): Boolean = this@CaptureOverlayButton.isOnScreen()

            override fun removeView(): Boolean {
                // 外れているかを例外ではなく View の状態で確かめる。二重に外しても安全。
                if (isOnScreen()) {
                    try {
                        windowManager.removeViewImmediate(button)
                    } catch (error: Throwable) {
                        Log.e(LOG_TAG, "取得ボタンを外せませんでした", error)
                    }
                }
                // 例外なく戻っても外れているとは限らない。状態を見て決める。
                val gone = !isOnScreen()
                if (gone) params = null
                return gone
            }
        },
        attempts = REMOVE_ATTEMPTS,
        // メインスレッドを長く止めない。View の detach は通常この間に反映される。
        pause = { SystemClock.sleep(REMOVE_RETRY_DELAY_MS) },
    )
    private var position = OverlayPosition(0, 0)

    /** 画面に載っているか。外せなかった場合は true のまま残る（＝参照を捨てなくてよい）。 */
    val isAttached: Boolean get() = attachment.isAttached

    /** 一度でも位置を決めたか。取得のたびに隠しても位置を忘れない。 */
    private var placed = false

    /** 画面の回転・解像度の変化に追従する。 */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) return
            // 値が入れ替わるのはこの通知の後。次のループで読む。
            mainHandler.post { onScreenChanged() }
        }
    }

    /**
     * 表示する。権限が無い・WindowManager に断られたときは false を返し、
     * 例外は投げない（投影そのものは続けられる）。
     */
    fun show(): Boolean {
        if (attachment.isAttached) return true
        if (!Settings.canDrawOverlays(appContext)) return false
        val next = currentScreen()
        if (!placed) {
            // 最初は右端の上寄り。ゲームの操作帯（下）と通知（上）を避ける。
            position = OverlayPosition(
                x = (next.width - sizePx).coerceAtLeast(0),
                y = (next.height * START_Y_RATIO).toInt(),
            ).clamped(sizePx, sizePx, next.width, next.height)
            placed = true
        } else {
            // 隠している間に回転していたら、出す前に移し直す。
            position = position.reflow(
                oldScreenWidth = screen.width,
                oldScreenHeight = screen.height,
                newScreenWidth = next.width,
                newScreenHeight = next.height,
                buttonWidth = sizePx,
                buttonHeight = sizePx,
            )
        }
        screen = next
        params = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = position.x
            y = position.y
        }
        val shown = attachment.attach()
        if (!shown) {
            // 権限が実行中に取り消された場合もここに来る。投影は止めない。
            Log.w(LOG_TAG, "取得ボタンを出せませんでした")
            // 出したが外せなかった View は、参照を失わないよう呼出側が持ち続ける。
            // 外れていることを確かめた場合だけレイアウトを捨てる。
            if (!attachment.isAttached) params = null
        }
        return shown
    }

    /**
     * 隠す。**外れたかを返す。** 外れていなければ呼出側は保存を中止する
     * （ボタンが写った1枚を残さない）。外せなかった場合は出たままの状態を保つので、
     * もう一度呼べば外し直せる。何度呼んでも安全。
     */
    fun hide(): Boolean = attachment.detach()

    /** 実際に画面に載っているか。状態の取り違えで「外したつもり」にならないよう見る。 */
    private fun isOnScreen(): Boolean =
        button.parent != null || button.isAttachedToWindow || button.windowToken != null

    /**
     * 画面の大きさが変わった。ボタンが画面の外に残らないよう、位置を移し直す。
     * 表示していない間（取得のため一瞬隠している間）も位置だけは追従させる。
     */
    fun onScreenChanged() {
        val next = currentScreen()
        if (next.width <= 0 || next.height <= 0) return
        if (next == screen) return
        position = position.reflow(
            oldScreenWidth = screen.width,
            oldScreenHeight = screen.height,
            newScreenWidth = next.width,
            newScreenHeight = next.height,
            buttonWidth = sizePx,
            buttonHeight = sizePx,
        )
        screen = next
        applyLayout()
    }

    private fun applyLayout() {
        if (!attachment.isAttached) return
        val layout = params ?: return
        layout.x = position.x
        layout.y = position.y
        runCatching { windowManager.updateViewLayout(button, layout) }
    }

    private fun currentScreen(): ScreenSize =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            ScreenSize(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            ScreenSize(metrics.widthPixels, metrics.heightPixels)
        }

    private data class ScreenSize(val width: Int, val height: Int)

    /**
     * 円ひとつ。影・カード・文字は使わない（設計 §4.1 の「目立たないこと」）。
     * 中に小さな四角を描いて「1枚切り取る」ことを示す。
     */
    private inner class ButtonView(context: Context) : View(context) {

        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FILL_COLOR }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_WIDTH_DP * density
            color = RING_COLOR
        }
        private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = MARK_WIDTH_DP * density
            color = RING_COLOR
        }
        private val markRect = RectF()

        private var downRawX = 0f
        private var downRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        init {
            contentDescription = "いまの画面を1枚保存"
            isClickable = true
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f - ring.strokeWidth
            fill.alpha = if (isPressed) PRESSED_ALPHA else IDLE_ALPHA
            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, ring)
            val half = radius * MARK_RATIO
            markRect.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(markRect, half * 0.35f, half * 0.35f, mark)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = position.x
                    startY = position.y
                    dragging = false
                    isPressed = true
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                    if (dragging) {
                        position = OverlayPosition(
                            (startX + dx).roundToInt(),
                            (startY + dy).roundToInt(),
                        ).clamped(sizePx, sizePx, screen.width, screen.height)
                        applyLayout()
                    }
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    isPressed = false
                    if (dragging) {
                        position = position.snapped(sizePx, screen.width)
                        applyLayout()
                    } else {
                        // 自分のタッチ処理の途中で自分を WindowManager から外さない。
                        // 1ループ後なら、抜けた後の View に触る者がいない。
                        post { onCapture() }
                    }
                    return true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isPressed = false
                    if (dragging) applyLayout()
                    return true
                }
            }
            return false
        }
    }

    private companion object {
        const val LOG_TAG = "hatsudesk"

        /** 小さすぎると押しにくく、大きいとゲームの邪魔になる。 */
        const val SIZE_DP = 44f
        const val RING_WIDTH_DP = 2f
        const val MARK_WIDTH_DP = 1.5f

        /** ボタンの中の四角の大きさ（半径に対する比）。 */
        const val MARK_RATIO = 0.42f

        /** 最初に出す高さ（画面の高さに対する比）。 */
        const val START_Y_RATIO = 0.34f

        /** 外れなかったときに試す回数と、試行の間の待ち。取得の待ち時間を大きくしない。 */
        const val REMOVE_ATTEMPTS = 3
        const val REMOVE_RETRY_DELAY_MS = 5L

        val FILL_COLOR = Color.argb(0x66, 0, 0, 0)
        val RING_COLOR = Color.argb(0xE6, 255, 255, 255)
        const val IDLE_ALPHA = 0x66
        const val PRESSED_ALPHA = 0xAA
    }
}
