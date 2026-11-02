package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalMangaUsageTest {
    @Test fun `counts use existing metadata transport without changing manga identity`() {
        val document = LocalMangaUsage.document("Book", 42)
        assertEquals("Book", document.manga)
        assertEquals(42L, LocalMangaUsage.decode(document))
        assertEquals(Long.MAX_VALUE, LocalMangaUsage.decode(LocalMangaUsage.document("Book", Long.MAX_VALUE)))
    }

    @Test fun `reject invalid negative overflowing and non-text counters`() {
        val document = LocalMangaUsage.document("Book", 0)
        listOf("-1", "+1", "1.5", "1e3", "9223372036854775808", "01", " 1 ", "{}").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { LocalMangaUsage.decode(document.copy(content = text)) }
        }
        assertThrows(IllegalArgumentException::class.java) { LocalMangaUsage.decode(document.copy(format = "base64")) }
        assertThrows(IllegalArgumentException::class.java) { LocalMangaUsage.decode(document.copy(manga = "")) }
    }

    @Test fun `merge sums counters without overflowing`() {
        assertEquals(12L, LocalMangaUsage.sum(listOf(2, 4, 6)))
        assertEquals(0L, LocalMangaUsage.sum(emptyList()))
        assertEquals(Long.MAX_VALUE, LocalMangaUsage.sum(listOf(Long.MAX_VALUE, 1)))
    }
}
