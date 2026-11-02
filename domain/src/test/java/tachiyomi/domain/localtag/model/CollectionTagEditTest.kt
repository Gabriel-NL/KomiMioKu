package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectionTagEditTest {
    @Test
    fun `adding and removing preserves unrelated tags`() {
        val result = applyCollectionTagEdits(listOf("English", "Old", "Keep"), listOf(CollectionTagEdit("New", true), CollectionTagEdit("old", false)))
        assertEquals(setOf("english", "keep", "new"), result.map(::localTagKey).toSet())
    }

    @Test
    fun `retry is idempotent and case insensitive`() {
        val edits = listOf(CollectionTagEdit(" english ", true), CollectionTagEdit("OLD", false))
        val once = applyCollectionTagEdits(listOf("English", "Old"), edits)
        assertEquals(listOf("English"), once)
        assertEquals(once, applyCollectionTagEdits(once, edits))
    }

    @Test
    fun `last edit for the same canonical tag determines result`() {
        assertEquals(emptyList<String>(), applyCollectionTagEdits(emptyList(), listOf(CollectionTagEdit("English", true), CollectionTagEdit("english", false))))
    }

    @Test
    fun `removing an absent tag does not remove others`() {
        assertEquals(listOf("Keep"), applyCollectionTagEdits(listOf("Keep"), listOf(CollectionTagEdit("Absent", false))))
    }
}
