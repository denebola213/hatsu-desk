package net.st.hatsudesk.capture

/** ボタンを隠す段取りの結果。[concealForCapture] が返す。 */
internal sealed interface ConcealOutcome<out B> {

    /** 隠せた。[geometry] は隠す直前に読んだ実座標・配置世代・画像寸法。 */
    class Hidden<B>(val button: B, val geometry: CaptureGeometry) : ConcealOutcome<B>

    /** 隠すボタンが無い（オーバーレイ未許可）。隠すものが無いので待たない。 */
    data object NoButton : ConcealOutcome<Nothing>

    /** 実 View の位置を読めない。どこを見ればよいか分からないので隠さない。 */
    data object NoGeometry : ConcealOutcome<Nothing>

    /** 矩形が投影画像の外。画素を読めないので隠さない。 */
    data object OutsideImage : ConcealOutcome<Nothing>

    /** 外せなかった。隠れていないボタンが写った1枚を残さないよう、取得しない。 */
    data object NotHidden : ConcealOutcome<Nothing>
}

/**
 * **ボタンの位置を確定してから隠す。その間に何も挟まない。**
 *
 * 2つを別々の呼び出しにすると、間に `withContext(Dispatchers.Default)` のような suspend を
 * 置けてしまう。その間 main はドラッグや回転を処理できるので、確定した矩形と隠したときの
 * 位置がずれる（PR #22 の blocking 指摘）。ここを **suspend しない1つの関数**にしてあるので、
 * 間に切り替えを挟むには呼出側の関数ごと suspend にするしかない。`CaptureController.capture()`
 * が suspend でないことは `CaptureConcealmentTest` で固定している。
 *
 * Android の型を持たないのは、読む順序と「隠せなかったら取得しない」を JVM の単体テストで
 * 固定するため。実際の読み取り・`removeView` は [geometryOf] / [hide] が行う。
 *
 * @param button 取得ボタン。null はオーバーレイ未許可。
 * @param imageWidth 投影フレームの幅。セッション中は一定（[CaptureGeometry] の説明）。
 * @param geometryOf 実 View の座標・配置世代・画像寸法を読む。**必ず [hide] より先**。
 * @param hide その場で隠す。外れたかを返す。
 */
internal fun <B : Any> concealForCapture(
    button: B?,
    imageWidth: Int,
    imageHeight: Int,
    geometryOf: (B) -> CaptureGeometry?,
    hide: (B) -> Boolean,
): ConcealOutcome<B> {
    if (button == null) return ConcealOutcome.NoButton
    val geometry = geometryOf(button) ?: return ConcealOutcome.NoGeometry
    if (!geometry.rect.fitsIn(imageWidth, imageHeight)) return ConcealOutcome.OutsideImage
    if (!hide(button)) return ConcealOutcome.NotHidden
    return ConcealOutcome.Hidden(button, geometry)
}
