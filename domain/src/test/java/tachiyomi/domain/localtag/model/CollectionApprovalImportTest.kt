package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.localtag.repository.parseCollectionApprovalImport

class CollectionApprovalImportTest {
    @Test
    fun `import preserves exact current names without alias history`() {
        val value = parseCollectionApprovalImport("""{"version":1,"collection":"NSFW","approvedPaths":["Current name"]}""")
        assertEquals(listOf("Current name"), value.approvedPaths)
    }

    @Test
    fun `reject unsafe ambiguous paths and unsupported versions`() {
        listOf("../Book", "a/b", ".", "..").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { parseCollectionApprovalImport("""{"version":1,"collection":"NSFW","approvedPaths":["$path"]}""") }
        }
        assertThrows(IllegalArgumentException::class.java) { parseCollectionApprovalImport("""{"version":1,"collection":"NSFW","approvedPaths":["Book","book"]}""") }
        assertThrows(IllegalArgumentException::class.java) { parseCollectionApprovalImport("""{"version":2,"collection":"NSFW","approvedPaths":[]}""") }
    }
}
