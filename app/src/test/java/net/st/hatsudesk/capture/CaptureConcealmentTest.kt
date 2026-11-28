package net.st.hatsudesk.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「矩形を確定する」→「隠す」の順序を fixed する**（PR #22 の指摘 4）。
 *
 * 2つを別々の呼び出しにすると、間に `withContext(Dispatchers.Default)` を置けてしまう。
 * 実際にその形へ戻しても129件が緑のままだったので、順序と「suspend でないこと」を
 * ここで固定する。呼び出しを記録する fake で、読む→隠すの順に1回ずつ進むことを見る。
 */
class CaptureConcealmentTest {

    private val rect = PixelRect(964, 819, 116, 116)
    private val imageWidth = 1080
    private val imageHeight = 2400
    private val geometry = CaptureGeometry(rect, 3L, imageWidth, imageHeight)

    /** 位置の読み取りと hide を、呼ばれた順に記録する fake。 */
    private class FakeButton(
        private val log: MutableList<String>,
        private val measured: CaptureGeometry?,
        private val hideWorks: Boolean = true,
    ) {
        var hides = 0
            private set

        fun readGeometry(): CaptureGeometry? {
            log += "geometry"
            return measured
        }

        fun hide(): Boolean {
            log += "hide"
            hides += 1
            return hideWorks
        }
    }

    @Test
    fun geometryIsReadInTheSameCallThatHides() {
        val log = mutableListOf<String>()
        val button = FakeButton(log, geometry)

        val outcome = concealForCapture(
            button = button,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            geometryOf = { it.readGeometry() },
            hide = { it.hide() },
        )

        assertTrue(outcome is ConcealOutcome.Hidden)
        assertEquals(listOf("geometry", "hide"), log)
        assertEquals(1, button.hides)
    }

    /** 位置が読めなければ隠さない。隠したつもりで矩形を当てにしない。 */
    @Test
    fun noGeometryDoesNotHide() {
        val log = mutableListOf<String>()
        val button = FakeButton(log, measured = null)

        val outcome = concealForCapture(
            button = button,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            geometryOf = { it.readGeometry() },
            hide = { it.hide() },
        )

        assertEquals(ConcealOutcome.NoGeometry, outcome)
        assertEquals(listOf("geometry"), log)
        assertEquals(0, button.hides)
    }

    /** 矩形が画像の外なら隠さない。画素を読めない1枚を残さない。 */
    @Test
    fun outsideImageDoesNotHide() {
        val log = mutableListOf<String>()
        val outside = CaptureGeometry(PixelRect(1000, 2300, 116, 116), 3L, imageWidth, imageHeight)
        val button = FakeButton(log, outside)

        val outcome = concealForCapture(
            button = button,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            geometryOf = { it.readGeometry() },
            hide = { it.hide() },
        )

        assertEquals(ConcealOutcome.OutsideImage, outcome)
        assertFalse(log.contains("hide"))
    }

    /** 外せなかったら取得しない。 */
    @Test
    fun failedHideIsReported() {
        val log = mutableListOf<String>()
        val button = FakeButton(log, geometry, hideWorks = false)

        val outcome = concealForCapture(
            button = button,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            geometryOf = { it.readGeometry() },
            hide = { it.hide() },
        )

        assertEquals(ConcealOutcome.NotHidden, outcome)
        assertEquals(1, button.hides)
    }

    /** 隠すボタンが無い（オーバーレイ未許可）。読むものも隠すものも無い。 */
    @Test
    fun noButtonDoesNothing() {
        val log = mutableListOf<String>()

        val outcome = concealForCapture<FakeButton>(
            button = null,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            geometryOf = { it.readGeometry() },
            hide = { it.hide() },
        )

        assertEquals(ConcealOutcome.NoButton, outcome)
        assertTrue(log.isEmpty())
    }

    /**
     * `capture()` が suspend でないことを固定する。
     *
     * suspend にすると、矩形の確定と hide の間に `withContext()` を挟める余地が生まれる
     * （呼び出し側を `suspend` にする必要があるので、ここが最初に落ちる）。
     */
    @Test
    fun captureIsNotSuspend() {
        val method = CaptureController::class.java.getDeclaredMethod(
            "capture",
            Boolean::class.javaPrimitiveType,
        )

        assertEquals(Void.TYPE, method.returnType)
        assertTrue(
            "capture() に Continuation を足さない（suspend にしない）",
            method.parameterTypes.none { it.name == "kotlin.coroutines.Continuation" },
        )
    }
}
