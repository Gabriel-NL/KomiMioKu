package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo

class CollectionTagMetadataTest {
    private fun original(): ComicInfo = SManga.create().apply {
        title = "Series"
        author = "Author"
        artist = "Artist"
        description = "Summary"
        genre = "Old tag"
        status = SManga.ONGOING
    }.getComicInfo().copy(
        title = ComicInfo.Title("Chapter title"),
        number = ComicInfo.Number("2"),
        tags = ComicInfo.Tags("Legacy tag"),
        categories = ComicInfo.CategoriesTachiyomi("Legacy category"),
    )

    @Test
    fun `editing tags preserves author title status and chapter metadata`() {
        val original = original()
        val updated = original.withCollectionTags(listOf("New tag", "oneshot"))
        assertEquals("New tag, Oneshot", updated.genre?.value)
        assertNull(updated.tags)
        assertNull(updated.categories)
        assertEquals(original, updated.copy(genre = original.genre, tags = original.tags, categories = original.categories))
    }

    @Test
    fun `clearing tags clears all legacy locations so they do not reappear`() {
        val updated = original().withCollectionTags(emptyList())
        assertNull(updated.genre)
        assertNull(updated.tags)
        assertNull(updated.categories)
    }

    @Test
    fun `saving normalizes blank duplicate and comma separated tags`() {
        val updated = original().withCollectionTags(listOf("  Drama ", "DRAMA", "", "oneshot, Romance"))
        assertEquals("Drama, Oneshot, Romance", updated.genre?.value)
    }
}
