package net.st.hatsudesk.capture

/**
 * 画面上の矩形（px、画面の左上が原点）。
 *
 * 取得ボタンの位置を画素の判定に渡すためだけの型。`android.*` を使わないので JVM の
 * 単体テストで回せる（[OverlayPosition] と同じ扱い）。
 */
internal data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int) {

    val pixelCount: Int get() = width * height

    /** 画像の中に収まっているか。収まっていなければ画素を読めない。 */
    fun fitsIn(imageWidth: Int, imageHeight: Int): Boolean =
        width > 0 && height > 0 && x >= 0 && y >= 0 &&
            x + width <= imageWidth && y + height <= imageHeight
}

/**
 * `FLAG_SECURE` の黒い矩形を、取得ボタンがまだ投影フレームに写っている印として判定する。
 *
 * 実機では、セキュアなボタン窓の矩形が投影フレームでは完全な黒 (0, 0, 0) になる。
 * ボタンを隠した後も黒い候補は古いフレームなので捨て、非黒の候補だけを保存する。
 * 押下時のフレームとの平均絶対差は使わない。下の画面が動いても、その差を「ボタンが
 * 消えた」証拠にすることはできないため。
 *
 * @param rect ボタンの矩形（画面の px）。
 * @param pressedRect 押下時の最新フレームから控えた矩形の画素（ARGB_8888、行優先）。
 *   位置と画素数を確かめるために受け取る。判定値との比較には使わない。
 */
internal class OverlayPixelCheck(
    val rect: PixelRect,
    pressedRect: IntArray,
) {
    init {
        require(pressedRect.size == rect.pixelCount) {
            "矩形の画素数 ${rect.pixelCount} と控えた画素 ${pressedRect.size} が合わない"
        }
    }

    /**
     * 候補の矩形が非黒なら true（保存してよい）。黒い印なら false（次のフレームでやり直す）。
     *
     * 比較する画素数が違う（矩形がずれた）ときは例外にする。黙って true を返すと、
     * ずれた矩形で「消えた」と誤認して焼き付いた1枚を残し得る。
     */
    fun isButtonGone(candidate: IntArray): Boolean {
        require(candidate.size == rect.pixelCount) {
            "候補の画素数 ${candidate.size} が矩形の画素数 ${rect.pixelCount} と違う"
        }
        return meanBrightness(candidate) >= MIN_BUTTON_GONE_MEAN_BRIGHTNESS
    }

    companion object {
        /**
         * 非黒と判断する平均輝度の下限（RGB 3 チャンネルの平均、0〜255）。3 未満は棄却する。
         *
         * 根拠は実測した黒い印の完全な黒 (0, 0, 0)。PNG は可逆で圧縮による値の変動は無い。
         * わずかな近黒だけを許容して 3 に切り上げ、それ以上の暗いグレーや色は保存対象にする。
         * ボタンの下そのものが真っ黒な場合は区別できないため、600ms で中止して利用者に
         * ボタンを動かすよう案内する（[CapturePlan.STALLED_MESSAGE]）。
         */
        const val MIN_BUTTON_GONE_MEAN_BRIGHTNESS = 3.0

        /** 矩形の平均輝度。RGB の3チャンネルだけを見て、アルファは見ない。 */
        fun meanBrightness(pixels: IntArray): Double {
            if (pixels.isEmpty()) return 0.0
            var sum = 0L
            for (argb in pixels) {
                sum += argb shr 16 and 0xFF
                sum += argb shr 8 and 0xFF
                sum += argb and 0xFF
            }
            return sum.toDouble() / (pixels.size * 3)
        }
    }
}

/**
 * 候補フレームを保存してよいかの判定を持つ。**時刻は一次フィルタ、最終判断は黒い印。**
 *
 * `CaptureController.capture()` の繰り返し（待つ → 黒くないか確かめる → 駄目なら次）を、
 * `android.*` を持たずに JVM で再現できる形にしたもの。
 *
 * 捨てた候補の時刻を覚え、次は**それより新しいフレーム**だけを候補にする。同じ1枚を
 * 何度も見直して時間切れになるのを防ぐ。
 */
internal class VerifiedFrameGate(
    /** 時刻の一次フィルタ（隠し終えた後にキューされたか）。 */
    private val plan: CapturePlan,
    /** 黒い印の最終判断。null は「隠すボタンが無い」＝検査しない。 */
    private val pixels: OverlayPixelCheck?,
) {

    /** 捨てた候補の次に受け付ける下限（ns）。まだ捨てていなければ null。 */
    private var resumeFromNanos: Long? = null

    /** 次の候補として受け付けるフレーム時刻の下限（ns）。 */
    val sinceNanos: Long
        get() = maxOf(plan.hiddenAtNanos ?: Long.MIN_VALUE, resumeFromNanos ?: Long.MIN_VALUE)

    /** 黒い印を確かめる必要があるか（ボタンを隠したときだけ）。 */
    val verifiesPixels: Boolean get() = pixels != null

    /**
     * [timestampNanos] のフレームを候補にしてよいか。
     * 隠す前のフレームと、すでに捨てたフレームは候補にしない。
     */
    fun wants(timestampNanos: Long): Boolean =
        plan.accepts(timestampNanos) && timestampNanos >= sinceNanos

    /** 候補の矩形の画素。true のときだけ保存してよい。 */
    fun keeps(candidatePixels: IntArray): Boolean =
        pixels?.isButtonGone(candidatePixels) ?: true

    /** 黒い印が残っていた候補を捨てる。次はこれより新しいフレームだけを見る。 */
    fun discard(timestampNanos: Long) {
        resumeFromNanos = maxOf(resumeFromNanos ?: Long.MIN_VALUE, timestampNanos + 1)
    }
}
