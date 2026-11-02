package eu.kanade.tachiyomi.data.backup.collection

import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CollectionArchiveReadTest {
    @TempDir lateinit var temp: File
    private val service = CollectionBackupService(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
    private val json = Json { encodeDefaults = true }
    private val bytes = byteArrayOf(1, 0, 2, 3, 4)
    private val path = "files/Book/chapter.cbz"
    private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun manifest(asset: CollectionAsset = CollectionAsset(path, bytes.size.toLong(), digest(bytes))): CollectionBackupManifest {
        val manga = BackupManga(
            source = 2000000000001,
            url = "Book",
            title = "Book",
            notes = "Personal note",
            favorite = true,
            chapters = listOf(BackupChapter("Book/chapter.cbz", "Chapter", read = true, bookmark = true, lastPageRead = 3)),
            history = listOf(BackupHistory("Book/chapter.cbz", 42, 100)),
        )
        return CollectionBackupManifest(
            collection = "NSFW",
            createdAt = 1,
            directories = listOf("Book"),
            assets = listOf(asset),
            customCovers = emptyMap(),
            data = CollectionUserData(listOf(manga), emptyList(), listOf("Assigned", "Unused"), listOf(CollectionTagLink("Book", listOf("Assigned")))),
        )
    }
    private fun zip(m: CollectionBackupManifest, files: List<Pair<String, ByteArray>> = listOf(path to bytes), complete: Boolean = true): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(m).toByteArray())
            zip.closeEntry()
            files.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
            if (complete) {
                zip.putNextEntry(ZipEntry("complete.json"))
                zip.write(("{\"manifest_sha256\":\"" + digest(json.encodeToString(m).toByteArray()) + "\"}").toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `real zip round trip preserves unassigned tags links notes and reading state`() {
        val restored = service.inspectArchive(ByteArrayInputStream(zip(manifest())), temp)
        assertEquals(listOf("Assigned", "Unused"), restored.data.tags)
        assertEquals(listOf("Assigned"), restored.data.links.single().tags)
        assertEquals("Personal note", restored.data.mangas.single().notes)
        assertTrue(restored.data.mangas.single().chapters.single().read)
        assertTrue(restored.data.mangas.single().chapters.single().bookmark)
        assertEquals(3L, restored.data.mangas.single().chapters.single().lastPageRead)
        assertEquals(42L, restored.data.mangas.single().history.single().lastRead)
        assertTrue(File(temp, path).readBytes().contentEquals(bytes))
    }

    @Test
    fun `hash mismatch and undeclared extra bytes are rejected`() {
        assertThrows(IllegalStateException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(manifest(), listOf(path to byteArrayOf(9, 8, 7, 6, 5))))) }
        assertThrows(IllegalStateException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(manifest(), listOf(path to (bytes + 6))))) }
    }

    @Test
    fun `missing and unexpected zip entries cannot be accepted as a backup`() {
        assertThrows(IllegalArgumentException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(manifest(), emptyList()))) }
        assertThrows(IllegalStateException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(manifest(), listOf("../outside" to bytes))), temp) }
        assertFalse(File(temp.parentFile, "outside").exists())
    }

    @Test
    fun `manifest paths and incomplete tag catalogs are checked before extraction`() {
        val badPath = manifest(CollectionAsset("files/../outside", 5, digest(bytes)))
        assertThrows(IllegalArgumentException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(badPath)), temp) }
        val missingTag = manifest().let { it.copy(data = it.data.copy(tags = emptyList())) }
        assertThrows(IllegalArgumentException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(missingTag)), temp) }
        assertEquals(0, temp.listFiles().orEmpty().size)
    }

    @Test
    fun `unsupported versions and ambiguous duplicate assets are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(manifest().copy(version = 99)))) }
        val m = manifest()
        assertThrows(IllegalArgumentException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(m.copy(assets = m.assets + m.assets)))) }
    }

    @Test
    fun `manga chapter identities cannot escape the selected collection`() {
        val m = manifest()
        m.data.mangas.single().chapters.single().url = "Other/chapter.cbz"
        assertThrows(IllegalArgumentException::class.java) { service.inspectArchive(ByteArrayInputStream(zip(m)), temp) }
        assertEquals(0, temp.listFiles().orEmpty().size)
    }

    @Test
    fun `restore changes source identity without mutating backup or chapter paths`() {
        val original = manifest().data.mangas.single()
        original.customThumbnailUrl = "content://old-device/cover"
        val target = remapCollectionManga(original, 2000000000123, "content://new-device/cover", json)
        assertEquals(2000000000123L, target.source)
        assertEquals(2000000000001L, original.source)
        assertEquals("content://new-device/cover", target.thumbnailUrl)
        assertEquals(null, target.customThumbnailUrl)
        assertEquals("content://old-device/cover", original.customThumbnailUrl)
        assertEquals(original.url, target.url)
        assertEquals(original.chapters.single().url, target.chapters.single().url)
        assertEquals(original.history.single().url, target.history.single().url)
    }

    @Test
    fun `zip from an interrupted export is rejected even if every asset was written`() {
        assertThrows(IllegalArgumentException::class.java) {
            service.inspectArchive(ByteArrayInputStream(zip(manifest(), complete = false)))
        }
    }
}
