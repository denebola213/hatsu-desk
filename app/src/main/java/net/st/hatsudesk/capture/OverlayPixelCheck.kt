package net.st.hatsudesk.capture

import kotlin.math.abs

/**
 * 画面上の矩形（px、画面の左上が原点）。
 *
 * 取得ボタンの位置を画素の比較に渡すためだけの型。`android.*` を使わないので JVM の
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
 * 取得ボタンが保存する絵に写っているかを、**画素そのもの**で決める。
 *
 * 時刻では決められない。`Image.getTimestamp()` は合成時刻ではなく、最後の sink へ
 * `queueBuffer()` した時刻なので、隠す前に合成された絵が隠した後にキューされれば
 * 「隠した後」の時刻を持つ。時刻の比較は候補を絞る一次フィルタにすぎない。
 *
 * 代わりに、押した瞬間の最新フレーム（ボタンが写っている）から**ボタンの矩形の画素**を
 * 控え、候補フレームの同じ矩形と比べる。ボタンは 44dp（実測 116px）の円なので、
 * 控える量は 116×116×4B ≒ 54KB で、保持のコストは無視できる。
 *
 * 差が大きい＝ボタンが消えて下の絵が出ている（保存してよい）。
 * 差が小さい＝ボタンが残っている（捨てて次のフレームでやり直す）。
 *
 * @param rect ボタンの矩形（画面の px）。
 * @param baseline 押した瞬間の、ボタンが写っているフレームの矩形の画素（ARGB_8888、行優先）。
 */
internal class OverlayPixelCheck(
    val rect: PixelRect,
    private val baseline: IntArray,
    /** これ以上の差なら「ボタンが消えた」と判断する。根拠は [MIN_BUTTON_GONE_DIFF]。 */
    private val minMeanAbsDiff: Double = MIN_BUTTON_GONE_DIFF,
) {

    init {
        require(baseline.size == rect.pixelCount) {
            "矩形の画素数 ${rect.pixelCount} と控えた画素 ${baseline.size} が合わない"
        }
    }

    /**
     * 候補の矩形が「ボタンの消えた絵」か。true のときだけ保存してよい。
     *
     * 比較する画素数が違う（矩形がずれた）ときは、判断せずに例外にする。黙って false や
     * true を返すと、ずれた矩形で「消えた」と誤認して焼き付いた1枚を残し得る。
     */
    fun isButtonGone(candidate: IntArray): Boolean {
        require(candidate.size == baseline.size) {
            "候補の画素数 ${candidate.size} が控えた画素 ${baseline.size} と違う"
        }
        return meanAbsDiff(baseline, candidate) >= minMeanAbsDiff
    }

    companion object {

        /**
         * 「ボタンが消えた」と判断する平均絶対差の下限（RGB 3 チャンネルの平均、0〜255）。
         *
         * 根拠（数値）:
         * - 実測（Pixel 10 Pro、1080×2410、ボタン 116px）: ボタン有無の矩形の差は平均 55.36。
         *   同じ2枚の対照区画（ボタンの無い場所）は 0.00。静止した画面では差が 0 になるので、
         *   ノイズの床はほぼ 0。
         * - 最悪条件の見積もり: 背景が真っ黒だと塗り（40% の黒）が効かず、白い輪と四角だけが
         *   動く。実寸（44dp・density 2.625 = 116px、輪 5.25px、四角 3.94px）で描いて比べると
         *   平均 35.28。灰色 (32,32,32) で 38.05、中間色で 44.45、白で 58.22。
         * - 20 は実測 55.36 の 36%、最悪条件 35.28 の 57% で、どちらよりも下。ノイズの床 0 より
         *   十分上。ボタンが残っている候補（差 ≒ 0〜1）と、消えている候補（差 35〜58）を分ける。
         */
        const val MIN_BUTTON_GONE_DIFF = 20.0

        /**
         * 2つの矩形の平均絶対差。RGB の3チャンネルだけを見て、アルファは見ない
         * （どちらも不透明な画面で、アルファは常に 255）。
         *
         * 実測値（55.36 と 0.00）と同じ定義にするため、画素ごとの差ではなく
         * 「全チャンネルの差の合計 ÷ (画素数 × 3)」で出す。
         */
        fun meanAbsDiff(a: IntArray, b: IntArray): Double {
            require(a.size == b.size) { "画素数が違う: ${a.size} と ${b.size}" }
            if (a.isEmpty()) return 0.0
            var sum = 0L
            for (index in a.indices) {
                val left = a[index]
                val right = b[index]
                sum += abs((left shr 16 and 0xFF) - (right shr 16 and 0xFF))
                sum += abs((left shr 8 and 0xFF) - (right shr 8 and 0xFF))
                sum += abs((left and 0xFF) - (right and 0xFF))
            }
            return sum.toDouble() / (a.size * 3)
        }
    }
}

/**
 * 候補フレームを保存してよいかの判定を持つ。**時刻は一次フィルタ、最終判断は画素。**
 *
 * `CaptureController.capture()` の繰り返し（待つ → 画素で確かめる → 駄目なら次）を、
 * `android.*` を持たずに JVM で再現できる形にしたもの。
 *
 * 捨てた候補の時刻を覚え、次は**それより新しいフレーム**だけを候補にする。同じ1枚を
 * 何度も見直して時間切れになるのを防ぐ。
 */
internal class VerifiedFrameGate(
    /** 時刻の一次フィルタ（隠し終えた後に合成されたか）。 */
    private val plan: CapturePlan,
    /** 画素の最終判断。null は「隠すボタンが無い」＝検査しない。 */
    private val pixels: OverlayPixelCheck?,
) {

    /** 捨てた候補の次に受け付ける下限（ns）。まだ捨てていなければ null。 */
    private var resumeFromNanos: Long? = null

    /** 次の候補として受け付ける合成時刻の下限（ns）。 */
    val sinceNanos: Long
        get() = maxOf(plan.hiddenAtNanos ?: Long.MIN_VALUE, resumeFromNanos ?: Long.MIN_VALUE)

    /** 画素で確かめる必要があるか（ボタンを隠したときだけ）。 */
    val verifiesPixels: Boolean get() = pixels != null

    /**
     * [timestampNanos] に合成されたフレームを候補にしてよいか。
     * 隠す前に合成されたフレームと、すでに捨てたフレームは候補にしない。
     */
    fun wants(timestampNanos: Long): Boolean =
        plan.accepts(timestampNanos) && timestampNanos >= sinceNanos

    /** 候補の矩形の画素。true のときだけ保存してよい。 */
    fun keeps(candidatePixels: IntArray): Boolean =
        pixels?.isButtonGone(candidatePixels) ?: true

    /** ボタンが残っていた候補を捨てる。次はこれより新しいフレームだけを見る。 */
    fun discard(timestampNanos: Long) {
        resumeFromNanos = maxOf(resumeFromNanos ?: Long.MIN_VALUE, timestampNanos + 1)
    }
}
