package tachiyomi.source.local.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectionMatchingTest {
    @Test
    fun `empty selections allow untagged manga`() {
        assertTrue(matchesCollectionValues(emptySet(), emptySet(), emptySet()))
    }

    @Test
    fun `whitelist accepts any match ignoring case and whitespace`() {
        val values = parseCollectionValues(" Adventure, Romance ")
        assertTrue(matchesCollectionValues(values, parseCollectionValues("ROMANCE, Comedy"), emptySet()))
        assertFalse(matchesCollectionValues(emptySet(), parseCollectionValues("Romance"), emptySet()))
    }

    @Test
    fun `blacklist rejects any match but keeps untagged manga`() {
        val excluded = parseCollectionValues("Romance")
        assertFalse(matchesCollectionValues(parseCollectionValues("Adventure, Romance"), emptySet(), excluded))
        assertTrue(matchesCollectionValues(emptySet(), emptySet(), excluded))
    }

    @Test
    fun `blacklist wins when a manga also matches whitelist`() {
        assertFalse(
            matchesCollectionValues(
                parseCollectionValues("Adventure, Romance"),
                parseCollectionValues("Adventure"),
                parseCollectionValues("Romance"),
            ),
        )
    }

    @Test
    fun `unknown whitelist values never allow all manga`() {
        assertFalse(matchesCollectionValues(parseCollectionValues("Romance"), setOf("missing"), emptySet()))
    }

    @Test
    fun `matching uses complete values rather than substrings`() {
        assertFalse(matchesCollectionValues(setOf("multi chapter"), setOf("chapter"), emptySet()))
        assertEquals(setOf("oneshot"), parseCollectionValues(" OneShot, , oneshot,"))
    }
}
