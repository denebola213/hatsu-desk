package net.st.hatsudesk.data

import kotlinx.coroutines.runBlocking
import net.st.hatsudesk.core.model.Run
import net.st.hatsudesk.core.model.RunDocument
import net.st.hatsudesk.core.model.RunIdol
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

/**
 * 保存先が `filesDir/runs`・`filesDir/cards` であることと、保存した run が
 * 別の [RunRepository]（＝プロセスを起動し直した状態）から読めることを確かめる。
 */
class RunRepositoryTest {

    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("hatsudesk-repository-")
    }

    @After
    fun tearDown() {
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun document(id: String) = RunDocument(
        run = Run(
            id = id,
            rankingId = "ranking-1",
            rank = 3,
            evalPoint = 12345,
            paramTotal = 3120,
            playedOn = "2026-11-27",
        ),
        idol = RunIdol(runId = id, cardId = "idol-unknown"),
    )

    @Test
    fun savedRunIsVisibleToANewInstance() = runBlocking {
        RunRepository(root.toFile()).save(document("run-1"))

        val reopened = RunRepository(root.toFile())
        val overview = reopened.loadOverview()
        assertEquals(listOf("run-1"), overview.entries.map { it.runId })
        assertEquals(12345, overview.entries.single().evalPoint)
        assertEquals("idol-unknown", overview.entries.single().idolCardId)

        val detail = reopened.loadDetail("run-1")
        assertNotNull(detail)
        assertEquals("idol-unknown", detail!!.document.idol?.cardId)
        assertEquals(3, detail.document.run.rank)
    }

    @Test
    fun storesUnderRunsAndCards() = runBlocking {
        RunRepository(root.toFile()).save(document("run-1"))

        assertTrue(Files.isDirectory(root.resolve("runs")))
        assertTrue(Files.isDirectory(root.resolve("cards")))
        assertTrue(Files.isRegularFile(root.resolve("runs/run-1.json")))
    }

    @Test
    fun reportsReferencedCardAsUnknownWhenDictionaryIsEmpty() = runBlocking {
        val repository = RunRepository(root.toFile())
        repository.save(document("run-1"))

        val dictionary = repository.loadDictionary()
        assertEquals(emptyList<String>(), dictionary.cards.map { it.cardId })
        assertEquals(listOf("idol-unknown"), dictionary.unknownCardIds)
    }
}
