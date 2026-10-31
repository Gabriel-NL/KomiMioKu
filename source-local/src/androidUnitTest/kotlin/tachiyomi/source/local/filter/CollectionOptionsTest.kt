package tachiyomi.source.local.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

class CollectionOptionsTest {
    @Test
    fun `old format and reading selections still mean the same inclusion`() {
        assertEquals(listOf(0, 1), collectionOptionStates(2, 2))
        assertEquals(listOf(0, 0, 1), collectionOptionStates(3, 3))
        assertEquals(listOf(0, 0), collectionOptionStates(0, 2))
    }

    @Test
    fun `choices cycle independently and support several inclusions and exclusions`() {
        var state = cycleCollectionOption(0, 0, 2)
        assertEquals(listOf(1, 0), collectionOptionStates(state, 2))
        state = cycleCollectionOption(state, 1, 2)
        assertEquals(listOf(1, 1), collectionOptionStates(state, 2))
        state = cycleCollectionOption(state, 0, 2)
        assertEquals(listOf(2, 1), collectionOptionStates(state, 2))
        state = cycleCollectionOption(state, 0, 2)
        assertEquals(listOf(0, 1), collectionOptionStates(state, 2))
        state = cycleCollectionOption(state, 1, 2)
        state = cycleCollectionOption(state, 1, 2)
        assertEquals(0, state)
    }

    @Test
    fun `changing a restored legacy choice preserves the other selected option`() {
        val state = cycleCollectionOption(3, 0, 3)
        assertEquals(listOf(1, 0, 1), collectionOptionStates(state, 3))
    }

    @Test
    fun `invalid saved states fall back to neutral without overflow`() {
        assertEquals(listOf(0, 0), collectionOptionStates(Int.MIN_VALUE, 2))
        assertEquals(listOf(0, 0), collectionOptionStates(99, 2))
        assertEquals(listOf(0, 0), collectionOptionStates(-9, 2))
    }

    @Test
    fun `format exclusions win and a whitelist excludes titles without a format tag`() {
        assertFalse(matchesCollectionValues(setOf("oneshot", "multi chapter"), setOf("oneshot"), setOf("multi chapter")))
        assertFalse(matchesCollectionValues(emptySet(), setOf("oneshot"), emptySet()))
        assertTrue(matchesCollectionValues(emptySet(), emptySet(), setOf("oneshot")))
    }

    @Test
    fun `reading distinguishes unknown unread partial progress and all known chapters read`() {
        assertEquals("unread", collectionReadingKey(emptyList()))
        assertEquals("unread", collectionReadingKey(listOf(Chapter.create())))
        assertEquals("started", collectionReadingKey(listOf(Chapter.create().copy(lastPageRead = 1))))
        assertEquals("started", collectionReadingKey(listOf(Chapter.create().copy(read = true), Chapter.create())))
        assertEquals("finished", collectionReadingKey(listOf(Chapter.create().copy(read = true))))
    }

    @Test
    fun `reading accepts any included state and excludes a conflicting state`() {
        assertTrue(matchesCollectionValues(setOf("unread"), setOf("unread", "started"), emptySet()))
        assertFalse(matchesCollectionValues(setOf("started"), setOf("unread", "started"), setOf("started")))
    }

    @Test
    fun `author choices deduplicate names and use exact case insensitive matching`() {
        assertEquals(listOf("Alice", "Bob"), collectionTagNames(listOf(" Bob, Alice ", "ALICE")))
        assertTrue(matchesCollectionValues(parseCollectionValues("Alice, Bob"), setOf("bob"), emptySet()))
        assertFalse(matchesCollectionValues(parseCollectionValues("Alice, Bob"), setOf("alice"), setOf("bob")))
    }
}
