package net.st.hatsudesk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 1回のプロデュース。列は設計 §8 の `run` 表に合わせる。
 *
 * 画面から取れなかった値は null（「不明」）で持つ。0 で埋めない。
 */
@Serializable
data class Run(
    val id: String,
    @SerialName("ranking_id") val rankingId: String,
    @SerialName("producer_id") val producerId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val rank: Int? = null,
    val plv: Int? = null,
    val grade: String? = null,
    @SerialName("rank_cap") val rankCap: String? = null,
    @SerialName("eval_point") val evalPoint: Int? = null,
    @SerialName("param_total") val paramTotal: Int? = null,
    val badge: String? = null,
    @SerialName("played_on") val playedOn: String? = null,
    val completeness: Int? = null,
    val note: String? = null,
)

/** `run_idol` 表。1 run に1件。 */
@Serializable
data class RunIdol(
    @SerialName("run_id") val runId: String,
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("char_id") val charId: String? = null,
    val rarity: String? = null,
    val vo: Int? = null,
    val da: Int? = null,
    val vi: Int? = null,
    val stamina: Int? = null,
    val star: Int? = null,
    @SerialName("limit_break") val limitBreak: Int? = null,
)

/** `run_support` 表。編成サポート6枠。 */
@Serializable
data class RunSupport(
    @SerialName("run_id") val runId: String,
    val slot: Int,
    @SerialName("card_id") val cardId: String? = null,
    val level: Int? = null,
    val training: Int? = null,
    val rental: Boolean = false,
)

/** `run_memory` 表。メモリー4枠。 */
@Serializable
data class RunMemory(
    @SerialName("run_id") val runId: String,
    val slot: Int,
    @SerialName("card_id") val cardId: String? = null,
    val rental: Boolean = false,
)

/** `run_challenge_item` 表。挑戦アイテム（レッスン上限アップ）。 */
@Serializable
data class RunChallengeItem(
    @SerialName("run_id") val runId: String,
    val slot: Int,
    @SerialName("item_id") val itemId: String? = null,
    @SerialName("lesson_cap_bonus_pct") val lessonCapBonusPct: Int? = null,
)

/** `run_final_item` 表。最終所持アイテム（実測4枠）。 */
@Serializable
data class RunFinalItem(
    @SerialName("run_id") val runId: String,
    val slot: Int,
    @SerialName("item_id") val itemId: String? = null,
    val tag: String? = null,
)

/** `run_skill` 表。最終所持スキルカード（実測33・典型26〜30）。 */
@Serializable
data class RunSkill(
    @SerialName("run_id") val runId: String,
    val idx: Int,
    @SerialName("card_id") val cardId: String? = null,
    @SerialName("enhance_from") val enhanceFrom: Int? = null,
    @SerialName("enhance_to") val enhanceTo: Int? = null,
    val cost: Int? = null,
    @SerialName("cost_mod") val costMod: Int? = null,
    val attr: String? = null,
    val tag: String? = null,
)

/**
 * `runs/<run_id>.json` の1ファイルの中身。
 *
 * 論理モデルは 7 表に分かれるが、物理は run を単位とした木なので1文書にまとめる
 * （ADR 0004「1件を丸ごと読んで丸ごと書く」）。表の列は各クラスがそのまま持つ。
 */
@Serializable
data class RunDocument(
    val run: Run,
    val idol: RunIdol? = null,
    val supports: List<RunSupport> = emptyList(),
    val memories: List<RunMemory> = emptyList(),
    @SerialName("challenge_items") val challengeItems: List<RunChallengeItem> = emptyList(),
    @SerialName("final_items") val finalItems: List<RunFinalItem> = emptyList(),
    val skills: List<RunSkill> = emptyList(),
)
