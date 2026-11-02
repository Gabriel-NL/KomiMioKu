package tachiyomi.domain.backup.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CollectionArchivePolicyTest {
    @Test
    fun `unicode relative paths and ordinary colon names are preserved`() {
        assertEquals("files/Book: special/\u65e5\u672c.cbz", CollectionArchivePolicy.path("files/Book: special/\u65e5\u672c.cbz"))
    }

    @Test
    fun `traversal absolute drives and control characters are rejected`() {
        listOf("../x", "/x", "C:/x", "C:x", "a/../x", "a//x", "a/./x", "a\\x", "a\u0000x", "a\nx").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { CollectionArchivePolicy.path(path) }
        }
    }

    @Test
    fun `case collisions also include implicit parent directories`() {
        assertThrows(IllegalArgumentException::class.java) { CollectionArchivePolicy.validatePaths(listOf("files/Book/a", "files/book/b"), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { CollectionArchivePolicy.validatePaths(listOf("files/A", "files/a"), emptyList()) }
    }

    @Test
    fun `files cannot replace directory ancestors`() {
        assertThrows(IllegalArgumentException::class.java) { CollectionArchivePolicy.validatePaths(listOf("files/Book"), listOf("files/Book/chapter")) }
        assertThrows(IllegalArgumentException::class.java) { CollectionArchivePolicy.validatePaths(listOf("files/Book", "files/Book/page.jpg"), emptyList()) }
    }

    @Test
    fun `empty collection and distinct paths are valid`() {
        CollectionArchivePolicy.validatePaths(emptyList(), emptyList())
        CollectionArchivePolicy.validatePaths(listOf("files/Book/a.cbz", "covers/0"), listOf("files/Book"))
    }
}
