package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class NetorareTagTest {
    @Test
    fun `legacy ntr becomes netorare while netori remains distinct`() {
        assertEquals("Netorare", canonicalLocalTagName("NTR"))
        assertEquals("Netorare", canonicalLocalTagName(" ntr "))
        assertEquals("Netori", canonicalLocalTagName("Netori"))
        assertNotEquals(canonicalLocalTagName("Ntr"), canonicalLocalTagName("Netori"))
    }
}
