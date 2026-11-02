package tachiyomi.domain.localtag.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class LocalMetadataFingerprintTest {
    @Test
    fun `directory enumeration order does not change fingerprint`() {
        val a = LocalMetadataStamp("ComicInfo.xml", 10, 200)
        val b = LocalMetadataStamp("Chapter.cbz", 20, 300)
        assertEquals(localMetadataFingerprint(listOf(a, b)), localMetadataFingerprint(listOf(b, a)))
    }

    @Test
    fun `metadata content indicators and added or removed files invalidate fingerprint`() {
        val files = listOf(LocalMetadataStamp("ComicInfo.xml", 10, 200))
        val original = localMetadataFingerprint(files)
        assertNotEquals(original, localMetadataFingerprint(listOf(files.single().copy(modified = 11))))
        assertNotEquals(original, localMetadataFingerprint(listOf(files.single().copy(size = 201))))
        assertNotEquals(original, localMetadataFingerprint(files + LocalMetadataStamp("Chapter.cbz", 20, 100)))
        assertNotEquals(original, localMetadataFingerprint(emptyList()))
    }

    @Test
    fun `unknown timestamps require verification instead of trusting a cache hit`() {
        assertEquals("", localMetadataFingerprint(listOf(LocalMetadataStamp("ComicInfo.xml", 0, 100))))
    }
}
