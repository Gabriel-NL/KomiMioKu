package tachiyomi.source.local.metadata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CollectionTagCatalogTest {
    @Test
    fun `imports unassigned names and keeps first spelling`() {
        assertEquals(
            listOf("English", "Ação"),
            decodeCollectionTagCatalog("""{"version":1,"tags":[" English ","english","Ação"]}"""),
        )
    }

    @Test
    fun `rejects invalid or unsupported catalog without partially importing`() {
        listOf(
            """{"version":2,"tags":["English"]}""",
            """{"version":1,"tags":["English","a,b"]}""",
            """{"version":1,"tags":[""]}""",
            """{"version":1,"tags":["line\nfeed"]}""",
            """{"tags":[]}""",
        ).forEach { input -> assertThrows(IllegalArgumentException::class.java) { decodeCollectionTagCatalog(input) } }
    }
}
