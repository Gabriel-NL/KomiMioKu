package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MangaManagerRulesTest {
    @Test fun `format extracts credits and language without erasing unrelated markers`() {
        val value = MangaManagerRules.format("[Studio (Alice, Bob)] A Story [English] [Digital]")
        assertEquals("A Story [Digital]", value.title)
        assertEquals(listOf("Alice", "Bob", "Studio"), value.credits)
        assertEquals(listOf("English"), value.tags)
        assertEquals("[Unknown Author] Story", MangaManagerRules.format("[Unknown Author] Story").title)
        assertEquals("Story", MangaManagerRules.format("Story (English)").title)
        assertEquals(listOf("English"), MangaManagerRules.format("Story - English").tags)
    }

    @Test fun `format rejects empty or unsafe physical destination`() {
        listOf("[English]", "../Story", "A/B", "CON").forEach { value -> assertThrows(IllegalArgumentException::class.java) { MangaManagerRules.format(value) } }
    }

    @Test fun `related titles share contiguous phrase with accents and chapter markers normalized`() {
        val a = MangaManagerRules.words("[Alice] Caf\u00e9 Collection Chapter 1 [English]")
        val b = MangaManagerRules.words("Cafe Collection Chapter 2")
        assertEquals("cafe collection", MangaManagerRules.commonPhrase(a, b))
        assertEquals("alpha", MangaManagerRules.commonPhrase(listOf("alpha", "x", "beta"), listOf("alpha", "y", "beta")))
    }

    @Test fun `page order is natural rather than lexical`() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), listOf("10.jpg", "2.jpg", "1.jpg").sortedBy(MangaManagerRules::naturalKey))
    }
}
