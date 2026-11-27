package net.st.hatsudesk.core.store

import net.st.hatsudesk.core.model.CardMaster
import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunChallengeItem
import net.st.hatsudesk.core.model.RunDocument
import net.st.hatsudesk.core.model.RunFinalItem
import net.st.hatsudesk.core.model.RunIdol
import net.st.hatsudesk.core.model.RunMemory
import net.st.hatsudesk.core.model.RunSkill
import net.st.hatsudesk.core.model.RunSupport
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStoreTest {

    private val tempDirs = mutableListOf<Path>()

    private fun newStore(): DocumentStore {
        val dir = Files.createTempDirectory("hatsudesk-store-")
        tempDirs.add(dir)
        return DocumentStore(dir)
    }

    @AfterTest
    fun cleanUp() {
        tempDirs.forEach { dir ->
            if (!Files.exists(dir)) return@forEach
            Files.walk(dir).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        tempDirs.clear()
    }

    private fun sampleRun(id: String, evalPoint: Int?): RunDocument = RunDocument(
        run = Run(
            id = id,
            rankingId = "ranking-1",
            producerId = "producer-1",
            sessionId = "session-1",
            rank = 3,
            plv = 42,
            grade = "S4",
            rankCap = "S4",
            evalPoint = evalPoint,
            paramTotal = 3120,
            badge = "L2",
            playedOn = "2026-11-20",
            completeness = 2,
            note = "テスト",
        ),
        idol = RunIdol(
            runId = id,
            cardId = "idol-1",
            charId = "char-1",
            rarity = "SSR",
            vo = 1200,
            da = 980,
            vi = 940,
            stamina = 88,
            star = 4,
            limitBreak = 2,
        ),
        supports = listOf(
            RunSupport(runId = id, slot = 0, cardId = "support-1", level = 50, training = 4, rental = false),
            RunSupport(runId = id, slot = 1, cardId = null, rental = true),
        ),
        memories = listOf(RunMemory(runId = id, slot = 0, cardId = "memory-1", rental = false)),
        challengeItems = listOf(
            RunChallengeItem(runId = id, slot = 0, itemId = "item-1", lessonCapBonusPct = 30),
        ),
        finalItems = listOf(RunFinalItem(runId = id, slot = 0, itemId = "item-2", tag = "lesson")),
        skills = listOf(
            RunSkill(
                runId = id,
                idx = 0,
                cardId = "skill-1",
                enhanceFrom = 1,
                enhanceTo = 2,
                cost = 12,
                costMod = -1,
                attr = "vo",
                tag = "lesson",
            ),
        ),
    )

    @Test
    fun `保存して読み戻すと同値`() {
        val store = newStore()
        val document = sampleRun("run-1", evalPoint = 15400)

        store.saveRun(document)

        assertEquals(document, store.loadRun("run-1"))
        assertEquals(listOf(document), store.loadRuns())
        assertTrue(Files.isRegularFile(store.runFiles().single()))
        assertEquals("run-1.json", store.runFiles().single().fileName.toString())
    }

    @Test
    fun `保存は temp を残さず runs 直下の json だけになる`() {
        val store = newStore()
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 200))

        val names = Files.newDirectoryStream(store.runFiles().first().parent).use { stream ->
            stream.map { it.fileName.toString() }.sorted()
        }
        assertEquals(listOf("run-1.json", "run-2.json"), names)
    }

    @Test
    fun `壊れた JSON が1件あっても他の run は読める`() {
        val store = newStore()
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 200))

        val broken = store.runFiles().first().parent.resolve("run-3.json")
        Files.writeString(broken, "{ \"run\": { \"id\": \"run-3\" ")

        val loaded = store.loadRuns().map { it.run.id }.sorted()
        assertEquals(listOf("run-1", "run-2"), loaded)
        assertNull(store.loadRun("run-3"))
        // 壊れたファイルは消さない（人間が中身を見て直せるように残す）。
        assertTrue(Files.exists(broken))
        assertFalse(Files.readString(broken).isEmpty())
    }

    @Test
    fun `未知のフィールドがあっても読める`() {
        val store = newStore()
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        val file = store.runFiles().single()
        val body = Files.readString(file).replaceFirst("{", "{ \"future_field\": 1,")
        Files.writeString(file, body)

        assertEquals("run-1", store.loadRun("run-1")?.run?.id)
    }

    @Test
    fun `index を消しても runs から再生成できる`() {
        val store = newStore()
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        store.saveRun(sampleRun("run-2", evalPoint = 300))

        val indexFile = store.runFiles().first().parent.parent.resolve("index/runs.idx")
        assertTrue(Files.exists(indexFile))
        Files.delete(indexFile)

        val entries = store.loadIndex()

        assertEquals(listOf("run-1", "run-2"), entries.map { it.runId })
        assertEquals(listOf(100, 300), entries.map { it.evalPoint })
        assertEquals(listOf("idol-1", "idol-1"), entries.map { it.idolCardId })
        assertTrue(Files.exists(indexFile), "再生成した索引が書き戻されている")
    }

    @Test
    fun `壊れた index も runs から再生成できる`() {
        val store = newStore()
        store.saveRun(sampleRun("run-1", evalPoint = 100))
        val indexFile = store.runFiles().first().parent.parent.resolve("index/runs.idx")
        Files.writeString(indexFile, "not json at all")

        val entries = store.loadIndex()

        assertEquals(listOf("run-1"), entries.map { it.runId })
        assertEquals("run-1", store.loadRun(store.loadIndex().single().runId)?.run?.id)
    }

    @Test
    fun `カード辞書も1件1ファイルで往復する`() {
        val store = newStore()
        val card = CardMaster(
            cardId = "card-1",
            kind = "support",
            name = "テストカード",
            charId = "char-1",
            rarity = "SSR",
            lessonType = "vo",
            probability = 37.5,
            attr = "vo",
            cost = 10,
            condition = "レッスン3回",
            source = "detail",
            detailOk = true,
        )

        store.saveCard(card)

        assertEquals(card, store.loadCard("card-1"))
        assertEquals(listOf(card), store.loadCards())
    }

    @Test
    fun `ファイル名に使えない id は拒否する`() {
        val store = newStore()
        var rejected = false
        try {
            store.saveRun(sampleRun("../escape", evalPoint = 1))
        } catch (e: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
