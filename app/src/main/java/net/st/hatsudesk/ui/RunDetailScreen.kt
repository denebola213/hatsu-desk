package net.st.hatsudesk.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.core.model.RunSkill
import net.st.hatsudesk.data.RunDetailData
import net.st.hatsudesk.data.RunRepository

/**
 * 構成詳細。1件を木として読み、取れていない値は「不明」、辞書に無いカードは
 * 「未詳」＋カード ID として出す（設計 §8.6・ADR 0009）。
 */
@Composable
fun RunDetailScreen(repository: RunRepository, runId: String, onBack: () -> Unit) {
    var state by remember(runId) { mutableStateOf<LoadState<RunDetailData?>>(LoadState.Loading) }
    var attempt by remember(runId) { mutableIntStateOf(0) }

    LaunchedEffect(runId, attempt) {
        state = LoadState.Loading
        state = loadCatching { repository.loadDetail(runId) }
    }

    ScreenFrame(title = "構成詳細", onBack = onBack) {
        when (val current = state) {
            LoadState.Loading -> LoadingPane()
            is LoadState.Failed -> ErrorPane(current.message) { attempt += 1 }
            is LoadState.Ready -> when (val detail = current.value) {
                null -> MissingRun()
                else -> RunDetailBody(detail)
            }
        }
    }
}

@Composable
private fun MissingRun() {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("記録が見つかりませんでした", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("一覧に戻って選び直してください。", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RunDetailBody(detail: RunDetailData) {
    val document = detail.document
    val cards = detail.cards
    val run = document.run

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        SectionTitle("記録")
        Field("日付", run.playedOn ?: "不明")
        Field("ランキング", run.rankingId)
        Field("順位", run.rank?.let { "${formatNumber(it)}位" } ?: "不明")
        Field("評価点", run.evalPoint?.let { "${formatNumber(it)}点" } ?: "不明")
        Field("パラメータ合計", formatNumber(run.paramTotal))
        Row(Modifier.fillMaxWidth()) {
            Field("PLv", run.plv?.toString() ?: "不明", Modifier.weight(1f))
            Field("グレード", run.grade ?: "不明", Modifier.weight(1f))
        }
        Field("メモ", run.note ?: "不明")

        SectionTitle("Pアイドル")
        val idol = document.idol
        if (idol == null) {
            Text("不明", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
        } else {
            CardValue("カード", idol.cardId, cards)
            Row(Modifier.fillMaxWidth()) {
                Field("レアリティ", idol.rarity ?: "不明", Modifier.weight(1f))
                Field("スター", idol.star?.toString() ?: "不明", Modifier.weight(1f))
                Field("限界突破", idol.limitBreak?.toString() ?: "不明", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Field("ボーカル", formatNumber(idol.vo), Modifier.weight(1f))
                Field("ダンス", formatNumber(idol.da), Modifier.weight(1f))
                Field("ビジュアル", formatNumber(idol.vi), Modifier.weight(1f))
                Field("スタミナ", formatNumber(idol.stamina), Modifier.weight(1f))
            }
        }

        SectionTitle("サポート（6枠）")
        (0..5).forEach { slot ->
            val support = document.supports.firstOrNull { it.slot == slot }
            if (support == null) {
                Field("サポート ${slot + 1}", "不明")
            } else {
                CardValue("サポート ${slot + 1}", support.cardId, cards)
                Row(Modifier.fillMaxWidth()) {
                    Field("レベル", support.level?.toString() ?: "不明", Modifier.weight(1f))
                    Field("特訓", support.training?.toString() ?: "不明", Modifier.weight(1f))
                    Field("レンタル", if (support.rental) "あり" else "なし", Modifier.weight(1f))
                }
            }
        }

        SectionTitle("メモリー（4枠）")
        (0..3).forEach { slot ->
            val memory = document.memories.firstOrNull { it.slot == slot }
            if (memory == null) {
                Field("メモリー ${slot + 1}", "不明")
            } else {
                CardValue("メモリー ${slot + 1}", memory.cardId, cards)
                Field("レンタル", if (memory.rental) "あり" else "なし")
            }
        }

        SectionTitle("アイテム")
        Text("挑戦アイテム", style = MaterialTheme.typography.labelLarge)
        if (document.challengeItems.isEmpty()) {
            Field("挑戦アイテム", "不明")
        } else {
            document.challengeItems.sortedBy { it.slot }.forEach { item ->
                Field(
                    "挑戦アイテム ${item.slot + 1}",
                    "${item.itemId ?: "不明"}（" +
                        (item.lessonCapBonusPct?.let { "レッスン上限 +$it%" } ?: "レッスン上限 不明") + "）",
                )
            }
        }
        Text("最終所持アイテム", style = MaterialTheme.typography.labelLarge)
        if (document.finalItems.isEmpty()) {
            Field("最終所持アイテム", "不明")
        } else {
            document.finalItems.sortedBy { it.slot }.forEach { item ->
                Row(Modifier.fillMaxWidth()) {
                    Field("最終アイテム ${item.slot + 1}", item.itemId ?: "不明", Modifier.weight(1f))
                    Field("タグ", item.tag ?: "不明", Modifier.weight(1f))
                }
            }
        }

        SectionTitle("スキルカード")
        if (document.skills.isEmpty()) {
            Field("スキルカード", "不明")
        } else {
            document.skills.sortedBy { it.idx }.forEach { skill ->
                SkillRow(skill, cards)
                HorizontalDivider()
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SkillRow(skill: RunSkill, cards: Map<String, CardMaster>) {
    CardValue("スキル ${skill.idx + 1}", skill.cardId, cards)
    Row(Modifier.fillMaxWidth()) {
        Field("コスト", skill.cost?.toString() ?: "不明", Modifier.weight(1f))
        Field("強化", enhancement(skill) ?: "不明", Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth()) {
        Field("属性", skill.attr ?: "不明", Modifier.weight(1f))
        Field("タグ", skill.tag ?: "不明", Modifier.weight(1f))
    }
}

private fun enhancement(skill: RunSkill): String? {
    val from = skill.enhanceFrom
    val to = skill.enhanceTo
    return when {
        from != null && to != null -> "$from → $to"
        to != null -> "→ $to"
        from != null -> "$from → 不明"
        else -> null
    }
}
