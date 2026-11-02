package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalDatabaseMetadataTest {
    private val file = LocalMangaMetadata("File title", "File author", "File artist", "File summary", listOf("Old tag"), 2)

    @Test
    fun `initialized database values including cleared fields win over old files`() {
        val current = LocalMangaMetadata("Edited title", null, null, null, emptyList(), 0)
        assertEquals(current, mergeLegacyLocalMetadata(current, file, true))
    }

    @Test
    fun `legacy files fill only missing fields of uninitialized records`() {
        val current = LocalMangaMetadata("Database title", "Database author", null, null, listOf("Database tag"), 1)
        val result = mergeLegacyLocalMetadata(current, file, false)
        assertEquals(current.title, result.title)
        assertEquals(current.author, result.author)
        assertEquals(current.tags, result.tags)
        assertEquals(1L, result.status)
        assertEquals(file.artist, result.artist)
        assertEquals(file.description, result.description)
    }

    @Test
    fun `new records receive file tags without modifying the input`() {
        val empty = LocalMangaMetadata("", null, null, null, emptyList(), 0)
        assertEquals(file, mergeLegacyLocalMetadata(empty, file, false))
        assertEquals(emptyList<String>(), empty.tags)
    }

    @Test
    fun `cleanup requires committed import and never removes changed external data`() {
        val document = LocalMetadataDocument("Book", "ComicInfo.xml", "utf8", "original")
        assertFalse(canRemoveLegacyMetadata(false, document, "original".toByteArray()))
        assertFalse(canRemoveLegacyMetadata(true, null, "original".toByteArray()))
        assertFalse(canRemoveLegacyMetadata(true, document, "changed after import".toByteArray()))
        assertTrue(canRemoveLegacyMetadata(true, document, "original".toByteArray()))
    }

    @Test
    fun `opaque legacy bytes are preserved exactly without assuming text encoding`() {
        val bytes = byteArrayOf(0, -1, 15, -128)
        val document = LocalMetadataDocument("Book", "ComicInfo.cbm", "base64", java.util.Base64.getEncoder().encodeToString(bytes))
        assertTrue(canRemoveLegacyMetadata(true, document, bytes))
        assertFalse(canRemoveLegacyMetadata(true, document, byteArrayOf(0)))
    }
}
