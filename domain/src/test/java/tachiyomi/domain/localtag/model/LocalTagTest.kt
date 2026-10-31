package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.util.Locale

class LocalTagTest {
    @Test
    fun `normalization trims and ignores letter case without stripping accents`() {
        assertEquals(localTagKey("Adventure"), localTagKey("  ADVENTURE  "))
        assertEquals("\u00e1ventura", localTagKey(" \u00c1VENTURA "))
        assertNotEquals(localTagKey("\u00e1ventura"), localTagKey("aventura"))
        assertEquals("", localTagKey("   "))
    }

    @Test
    fun `normalization does not depend on the phone locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("history", localTagKey("HISTORY"))
        } finally {
            Locale.setDefault(previous)
        }
    }
}
