package app.folio.core.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecentDocumentsTest {

    private fun doc(uri: String, at: Long = 0) = RecentDocument(uri = uri, name = "$uri.pdf", lastOpenedAt = at)

    @Test
    fun upsertMovesExistingToFrontWithoutDuplicates() {
        val list = listOf(doc("a"), doc("b"), doc("c"))
        val result = RecentDocuments.upsert(list, doc("c", at = 9))
        assertEquals(listOf("c", "a", "b"), result.map { it.uri })
        assertEquals(9, result.first().lastOpenedAt)
    }

    @Test
    fun upsertCapsListSize() {
        var list = emptyList<RecentDocument>()
        repeat(RecentDocuments.MAX_ENTRIES + 10) { list = RecentDocuments.upsert(list, doc("u$it")) }
        assertEquals(RecentDocuments.MAX_ENTRIES, list.size)
        assertEquals("u${RecentDocuments.MAX_ENTRIES + 9}", list.first().uri)
    }

    @Test
    fun updatePositionKeepsOrderAndClampsNegative() {
        val list = listOf(doc("a"), doc("b"))
        val result = RecentDocuments.updatePosition(list, "b", -4)
        assertEquals(listOf("a", "b"), result.map { it.uri })
        assertEquals(0, result[1].lastPage)
        assertEquals(7, RecentDocuments.updatePosition(list, "a", 7)[0].lastPage)
    }

    @Test
    fun renameReplacesUriAndName() {
        val result = RecentDocuments.rename(listOf(doc("a")), "a", "z", "New.pdf")
        assertEquals("z", result[0].uri)
        assertEquals("New.pdf", result[0].name)
    }

    @Test
    fun storePersistsAcrossInstancesAndSurvivesCorruption() = runBlocking {
        val dir = Files.createTempDirectory("recents").toFile()
        val file = File(dir, "recents.json")
        val store = RecentDocumentsStore(file)
        store.upsert(doc("a").copy(lastPage = 3))
        store.upsert(doc("b"))

        val reloaded = RecentDocumentsStore(file)
        awaitLoaded(reloaded, 2)
        assertEquals(listOf("b", "a"), reloaded.items.value.map { it.uri })
        assertEquals(3, reloaded.find("a")?.lastPage)

        file.writeText("{ not json")
        val broken = RecentDocumentsStore(file)
        Thread.sleep(200)
        assertTrue(broken.items.value.isEmpty())
        assertNull(broken.find("a"))
        dir.deleteRecursively()
        Unit
    }

    private fun awaitLoaded(store: RecentDocumentsStore, size: Int) {
        val deadline = System.currentTimeMillis() + 3000
        while (store.items.value.size != size && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test
    fun fileNamesAreSanitized() {
        assertEquals("Report.pdf", FileNames.sanitize("Report"))
        assertEquals("Report.PDF", FileNames.sanitize("Report.PDF"))
        assertEquals("a_b_c.pdf", FileNames.sanitize("a/b\\c"))
        assertNull(FileNames.sanitize("   "))
        assertEquals("Report (copy).pdf", FileNames.copyName("Report.pdf"))
    }
}
