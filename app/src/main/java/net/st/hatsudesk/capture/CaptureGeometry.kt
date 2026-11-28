package net.st.hatsudesk.capture

/**
 * ボタンを隠した時点で確定した「実座標・配置世代・投影画像の寸法」。
 *
 * 候補フレームを保存してよいかを、この組と突き合わせて決める。矩形だけを控えると、
 * **同じ大きさのままボタンが動いた**ときに、旧矩形が非黒・移動先が黒のフレームを
 * 「ボタンが消えた」と誤認して保存し得る（PR #22 の blocking 指摘）。位置・配置世代・
 * 寸法のどれかが変わった候補は保存しない。
 *
 * **回転を見ているのは [layoutGeneration] で、寸法ではない。** `ImageReader.newInstance` と
 * `VirtualDisplay` はセッション開始時の寸法で1回だけ作られ、回転しても作り直さない。
 * よって `Image.width/height` はセッション中ずっと同じで、寸法の比較が偽になることは
 * 本番では無い。寸法は「受け口と判定の前提が揃っている」ことを見る安い不変条件として
 * 残しているだけなので、これを回転の検出根拠として扱わない。回転は
 * [CaptureOverlayButton.layoutGeneration] が進むことで分かる（聞き手はセッションの間ずっと
 * 生きており、ボタンを隠している間の回転も届く）。
 *
 * @param rect 隠す直前に読んだ**実 View の画面座標**（論理 `position` ではない）。
 * @param layoutGeneration 配置が変わるたびに増える番号。押した時点の値。
 * @param imageWidth 押した時点の投影フレームの幅。
 * @param imageHeight 押した時点の投影フレームの高さ。
 */
internal data class CaptureGeometry(
    val rect: PixelRect,
    val layoutGeneration: Long,
    val imageWidth: Int,
    val imageHeight: Int,
) {

    /**
     * 候補がこの配置・寸法のままの画面か。
     *
     * 配置（＝回転・再配置）は [layoutGeneration] で見る。寸法の一致は不変条件の確認で、
     * 回転の検出ではない（上の説明）。
     */
    fun stillDescribes(layoutGeneration: Long, imageWidth: Int, imageHeight: Int): Boolean =
        this.layoutGeneration == layoutGeneration &&
            this.imageWidth == imageWidth &&
            this.imageHeight == imageHeight
}

/** 候補1枚の判定結果。 */
internal enum class CandidateVerdict {

    /** 保存してよい（矩形が非黒、または隠すボタンが無い）。 */
    SAMPLED,

    /** 黒い印が残っている。次のフレームでやり直す。 */
    BURNED,

    /**
     * 位置・配置世代・寸法が押した時点と変わった。**保存せず、やり直しもしない。**
     * どの矩形を見ればよいかの証拠が崩れているので、同じ取得を続けても当てにならない。
     */
    MOVED,
}
