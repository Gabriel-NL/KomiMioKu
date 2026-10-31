package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LocalTagFormattingTest {
    @Test
    fun `sentence case applies to acronyms and multiple words`() {
        assertEquals("Oneshot", canonicalLocalTagName(" oneshot "))
        assertEquals("Nsfw", canonicalLocalTagName("NSFW"))
        assertEquals("Young adult", canonicalLocalTagName("Young ADULT"))
    }

    @Test
    fun `languages use English names without the old prefix`() {
        assertEquals("English", canonicalLocalTagName("Idioma: Inglês"))
        assertEquals("Japanese", canonicalLocalTagName("Idioma: japones"))
        assertEquals("French", canonicalLocalTagName("Language: français"))
        assertEquals("Portuguese", canonicalLocalTagName("Português"))
    }
}
