package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalCollectionOrderingTest {
    private fun book(url: String, title: String, modified: Long) = IndexedLocalManga(1, "root", url, title, null, null, null, null, 0, null, modified, "")
    private val books = listOf(book("a-folder", "Zulu", 1), book("z-folder", "Alpha", 2), book("b-folder", "Beta", 2))

    @Test fun `title sort uses displayed title and supports both directions`() {
        assertEquals(listOf("Alpha", "Beta", "Zulu"), sortLocalCollection(books, LocalCollectionOrder.TITLE, true).map { it.title })
        assertEquals(listOf("Zulu", "Beta", "Alpha"), sortLocalCollection(books, LocalCollectionOrder.TITLE, false).map { it.title })
    }

    @Test fun `date choice sorts actual timestamps with stable title ties`() {
        assertEquals(listOf("Alpha", "Beta", "Zulu"), sortLocalCollection(books, LocalCollectionOrder.MODIFIED, false).map { it.title })
        assertEquals(listOf("Zulu", "Alpha", "Beta"), sortLocalCollection(books, LocalCollectionOrder.MODIFIED, true).map { it.title })
    }

    @Test fun `opening count supports both directions with zero for unopened manga and stable ties`() {
        val counts = mapOf("a-folder" to 5L, "z-folder" to 2L)
        assertEquals(listOf("Zulu", "Alpha", "Beta"), sortLocalCollection(books, LocalCollectionOrder.OPEN_COUNT, false, counts).map { it.title })
        assertEquals(listOf("Beta", "Alpha", "Zulu"), sortLocalCollection(books, LocalCollectionOrder.OPEN_COUNT, true, counts).map { it.title })
        assertEquals(listOf("Alpha", "Beta", "Zulu"), sortLocalCollection(books, LocalCollectionOrder.OPEN_COUNT, false).map { it.title })
    }
}
