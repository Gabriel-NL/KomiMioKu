package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CollectionFileNamesTest {
    @Test
    fun `reject unsafe traversal hidden Windows reserved and ambiguous names`() {
        listOf("", " ", ".", "..", "../Book", "a/b", "a\\b", "C:Book", ".hidden", "Book.", " Book", "Book ", "NUL", "com1.cbz", "A\u0000B").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { CollectionFileNames.validate(value) }
        }
    }

    @Test
    fun `chapter rename preserves archive extension and image folders remain folders`() {
        assertEquals("New name.CBZ", CollectionFileNames.chapterFile("New name", "old.CBZ", false))
        assertEquals("Images", CollectionFileNames.chapterFile("Images", "old.folder", true))
        assertEquals("Book", CollectionFileNames.validate("Book"))
    }

    @Test
    fun `numbered files preserve selection order and duplicate titles remain unique`() {
        val titles = listOf("Volume C", "Volume A", "Volume C")
        val files = titles.mapIndexed { index, title -> CollectionFileNames.numberedChapter(index + 1, title, "old.cbz", false) }
        assertEquals(listOf("000001 - Volume C.cbz", "000002 - Volume A.cbz", "000003 - Volume C.cbz"), files)
        assertEquals(files, files.sorted())
    }
}
