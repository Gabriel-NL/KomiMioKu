package tachiyomi.source.local.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectionTagSelectionTest {
    @Test
    fun `three clicks cycle neutral included excluded neutral and update matches`() {
        var included = emptySet<String>()
        var excluded = emptySet<String>()
        val tagged = setOf("adventure")
        val other = setOf("romance")
        assertEquals(0, collectionTagState(included, excluded, "Adventure"))
        assertTrue(matchesCollectionValues(other, included, excluded))

        cycleCollectionTag(included, excluded, " Adventure ").let { (i, e) ->
            included = i
            excluded = e
        }
        assertEquals(1, collectionTagState(included, excluded, "ADVENTURE"))
        assertTrue(matchesCollectionValues(tagged, included, excluded))
        assertFalse(matchesCollectionValues(other, included, excluded))

        cycleCollectionTag(included, excluded, "adventure").let { (i, e) ->
            included = i
            excluded = e
        }
        assertEquals(2, collectionTagState(included, excluded, "Adventure"))
        assertFalse(matchesCollectionValues(tagged, included, excluded))
        assertTrue(matchesCollectionValues(other, included, excluded))

        cycleCollectionTag(included, excluded, "Adventure").let { (i, e) ->
            included = i
            excluded = e
        }
        assertEquals(0, collectionTagState(included, excluded, "Adventure"))
        assertTrue(matchesCollectionValues(tagged, included, excluded))
        assertTrue(matchesCollectionValues(other, included, excluded))
    }

    @Test
    fun `cycling one tag preserves unrelated selections`() {
        val (included, excluded) = cycleCollectionTag(setOf("adventure", "comedy"), setOf("horror"), "Adventure")
        assertEquals(setOf("comedy"), included)
        assertEquals(setOf("horror", "adventure"), excluded)
    }

    @Test
    fun `legacy conflicting selections show excluded then clear both`() {
        assertEquals(2, collectionTagState(setOf("adventure"), setOf("adventure"), "Adventure"))
        assertEquals(emptySet<String>() to emptySet<String>(), cycleCollectionTag(setOf("adventure"), setOf("adventure"), "Adventure"))
    }

    @Test
    fun `tag discovery trims deduplicates and sorts metadata from all titles`() {
        assertEquals(
            listOf("Adventure", "Comedy", "Romance"),
            collectionTagNames(listOf("Romance, Adventure", " adventure , Comedy", "", "ROMANCE")),
        )
    }

    @Test
    fun `selection storage round trip preserves the selected tags`() {
        val (included, excluded) = cycleCollectionTag(setOf("comedy"), setOf("horror"), "Adventure")
        assertEquals(1, collectionTagState(parseCollectionValues(included.joinToString(", ")), parseCollectionValues(excluded.joinToString(", ")), "Adventure"))
        assertEquals(2, collectionTagState(parseCollectionValues(included.joinToString(", ")), parseCollectionValues(excluded.joinToString(", ")), "Horror"))
    }
}
