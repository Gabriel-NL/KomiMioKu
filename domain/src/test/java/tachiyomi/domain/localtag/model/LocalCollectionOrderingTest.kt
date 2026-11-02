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
}
