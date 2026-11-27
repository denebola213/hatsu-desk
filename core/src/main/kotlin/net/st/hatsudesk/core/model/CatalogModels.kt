package net.st.hatsudesk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `card_master` 表。`cards/<card_id>.json` の1ファイルの中身。
 *
 * カード辞書は端末で育つ。外部キーは持たず、欠けた参照は「未詳」として扱う（ADR 0004）。
 */
@Serializable
data class CardMaster(
    @SerialName("card_id") val cardId: String,
    val kind: String? = null,
    val name: String? = null,
    @SerialName("char_id") val charId: String? = null,
    val rarity: String? = null,
    @SerialName("lesson_type") val lessonType: String? = null,
    val probability: Double? = null,
    val attr: String? = null,
    val cost: Int? = null,
    val condition: String? = null,
    val source: String? = null,
    @SerialName("detail_ok") val detailOk: Boolean = false,
)

/** `ranking` 表。公演 × シーズン × 難易度 × Pアイドル。 */
@Serializable
data class Ranking(
    val id: String,
    @SerialName("production_id") val productionId: String,
    val season: String? = null,
    val difficulty: String? = null,
    /** producer / p_idol */
    val kind: String? = null,
    @SerialName("p_idol_char_id") val pIdolCharId: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("captured_at") val capturedAt: String? = null,
)

/** `producer` 表。ゲーム内に ID が無いので名前とサムネ pHash で同一性を見る。 */
@Serializable
data class Producer(
    val id: String,
    val name: String? = null,
    @SerialName("name_norm") val nameNorm: String? = null,
    @SerialName("thumb_phash") val thumbPhash: String? = null,
    @SerialName("first_seen") val firstSeen: String? = null,
)
