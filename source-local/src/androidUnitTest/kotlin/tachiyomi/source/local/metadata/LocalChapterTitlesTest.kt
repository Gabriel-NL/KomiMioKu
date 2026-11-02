package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalChapterTitlesTest {
    private fun chapter(file: String, name: String) = SChapter.create().apply {
        url = "Book/$file"
        this.name = name
        chapter_number = -1f
    }

    @Test
    fun `saved selection order beats filenames titles and unknown chapter numbers`() {
        val chapters = listOf(chapter("b.cbz", "First"), chapter("a.cbz", "Last"), chapter("c.cbz", "Middle"))
        val titles = mapOf("b.cbz" to LocalChapterTitle("First", -1f, 0), "c.cbz" to LocalChapterTitle("Middle", -1f, 1), "a.cbz" to LocalChapterTitle("Last", -1f, 2))
        assertEquals(listOf("Book/b.cbz", "Book/c.cbz", "Book/a.cbz"), chapters.sortedWith(localChapterOrder(titles)).asReversed().map { it.url })
    }

    @Test
    fun `legacy works keep natural source ordering without a title catalog`() {
        val chapters = listOf(chapter("2.cbz", "Chapter 2"), chapter("10.cbz", "Chapter 10"), chapter("1.cbz", "Chapter 1"))
        assertEquals(listOf("Chapter 10", "Chapter 2", "Chapter 1"), chapters.sortedWith(localChapterOrder(emptyMap())).map { it.name })
    }

    @Test
    fun `new files remain discoverable without disturbing the saved sequence`() {
        val chapters = listOf(chapter("b.cbz", "Second"), chapter("new.cbz", "New"), chapter("a.cbz", "First"))
        val titles = mapOf("a.cbz" to LocalChapterTitle("First", 1f, 0), "b.cbz" to LocalChapterTitle("Second", 2f, 1))
        assertEquals(listOf("First", "Second", "New"), chapters.sortedWith(localChapterOrder(titles)).asReversed().map { it.name })
    }
}
