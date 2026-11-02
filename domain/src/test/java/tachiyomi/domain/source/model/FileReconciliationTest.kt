package tachiyomi.domain.source.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileReconciliationTest {
    private val target = FileSourceIds.getCustomSourceId("NSFW")
    private fun row(id: Long = 1, source: Long = 0, url: String = "Book", title: String = "Book") =
        FileMangaRecord(id, source, url, title, 0, 0)

    @Test
    fun `unique exact paths migrate from either original source`() {
        listOf(FileSourceIds.LOCAL, FileSourceIds.PRIVATE_COLLECTION).forEach { origin ->
            val entry = planFileReconciliation(listOf(row(source = origin)), target, listOf("Book"), emptySet()).single()
            assertTrue(entry.movable)
            assertEquals(origin, entry.candidates.single().source)
        }
    }

    @Test
    fun `destination and origin duplicates are never merged automatically`() {
        val entry = planFileReconciliation(listOf(row(), row(2, target)), target, listOf("Book"), emptySet()).single()
        assertFalse(entry.movable)
        assertFalse(entry.alreadyLinked)
        assertEquals(2, entry.candidates.size)
    }

    @Test
    fun `title only match is a suggestion not a move`() {
        val entry = planFileReconciliation(listOf(row(url = "Old folder")), target, listOf("Book"), emptySet()).single()
        assertFalse(entry.movable)
        assertEquals(1, entry.candidates.size)
    }

    @Test
    fun `existing origin folders cannot be stolen`() {
        assertFalse(planFileReconciliation(listOf(row()), target, listOf("Book"), setOf(0L to "Book")).single().movable)
    }

    @Test
    fun `already connected and unknown folders are not modified`() {
        val entries = planFileReconciliation(listOf(row(source = target)), target, listOf("Unknown", "Book"), emptySet())
        assertTrue(entries.first().alreadyLinked)
        assertTrue(entries.none { it.movable })
        assertTrue(entries.last().candidates.isEmpty())
    }
}
