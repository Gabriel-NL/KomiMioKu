package tachiyomi.domain.localtag.model

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectionTagChangesTest {
    @Test
    fun `returning catalog consumes edits while keeping other catalogs independent`() {
        val initial = CollectionTagChanges.current(701)
        val firstCatalog = CollectionTagRefreshCursor(initial)
        val secondCatalog = CollectionTagRefreshCursor(initial)
        CollectionTagChanges.markDirty(701)
        val revision = CollectionTagChanges.current(701)
        assertTrue(firstCatalog.consume(revision))
        assertFalse(firstCatalog.consume(revision))
        assertTrue(secondCatalog.consume(revision))
    }

    @Test
    fun `many bulk edits coalesce into one refresh after dialog closes`() {
        val cursor = CollectionTagRefreshCursor(10)
        assertTrue(cursor.consume(30))
        assertFalse(cursor.consume(30))
        assertTrue(cursor.consume(31))
    }

    @Test
    fun `edits in another collection do not invalidate this collection`() = runBlocking {
        withTimeout(5000) {
            val initial = CollectionTagChanges.current(702)
            val values = async(start = CoroutineStart.UNDISPATCHED) {
                CollectionTagChanges.observe(702).take(2).toList()
            }
            CollectionTagChanges.markDirty(703)
            CollectionTagChanges.markDirty(702)
            assertEquals(listOf(initial, initial + 1), values.await())
        }
    }

    @Test
    fun `unchanged catalog is not refreshed on every return`() {
        val cursor = CollectionTagRefreshCursor(25)
        repeat(3) { assertFalse(cursor.consume(25)) }
    }
}
