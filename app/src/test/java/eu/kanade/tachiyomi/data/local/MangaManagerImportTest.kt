package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.collection.CollectionBackupManifest
import eu.kanade.tachiyomi.data.backup.collection.CollectionBackupService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.LocalSource
import java.io.File
import java.util.zip.ZipInputStream

class MangaManagerImportTest {
    @TempDir lateinit var directory: File

    @AfterEach fun teardown() {
        unmockkAll()
    }

    private class Fixture(val manager: MangaManagerService, val source: LocalSource, val uri: Uri, val backup: CollectionBackupService, val root: UniFile, val input: UniFile)
    private fun fixture(files: Map<String, ByteArray>): Fixture {
        mockkStatic(UniFile::class)
        mockkStatic(Uri::class)
        val inputDir = File(directory, "input/Story").also { it.mkdirs() }
        val context = mockk<Context>()
        every { context.cacheDir } returns File(directory, "cache").also { it.mkdirs() }
        val uri = mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.authority } returns null
        every { uri.path } returns inputDir.path
        val input = mockk<UniFile>()
        every { input.name } returns "Story"
        every { input.uri } returns uri
        val children = files.map { (name, content) ->
            val file = File(inputDir, name).also { it.writeBytes(content) }
            mockk<UniFile>().also { child ->
                every { child.name } returns name
                every { child.isDirectory } returns false
                every { child.openInputStream() } answers { file.inputStream() }
            }
        }
        every { input.listFiles() } returns children.toTypedArray()
        every { UniFile.fromUri(context, uri) } returns input
        every { Uri.fromFile(any()) } answers {
            val file = firstArg<File>()
            mockk<Uri>().also { result -> every { result.path } returns file.path }
        }
        val rootUri = mockk<Uri>()
        every { rootUri.scheme } returns "file"
        every { rootUri.authority } returns null
        every { rootUri.path } returns File(directory, "collection").path
        val root = mockk<UniFile>()
        every { root.uri } returns rootUri
        val source = mockk<LocalSource>()
        every { source.id } returns 2000000000001L
        every { source.name } returns "Test"
        every { source.getCollectionDirectory() } returns root
        val backup = mockk<CollectionBackupService>()
        val manager = spyk(MangaManagerService(context, mockk(), mockk(), mockk(), mockk(), backup))
        coEvery { manager.catalog(source) } returns emptyList()
        return Fixture(manager, source, uri, backup, root, input)
    }
    private fun reader() = CollectionBackupService(mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())

    @Test fun `folder import produces restore-compatible CBZ with naturally ordered pages and preserves input`() = runBlocking {
        val fixture = fixture(mapOf("10.jpg" to byteArrayOf(10), "2.jpg" to byteArrayOf(2), "1.jpg" to byteArrayOf(1)))
        var manifest: CollectionBackupManifest? = null
        val staged = File(directory, "staged").also { it.mkdirs() }
        val result = mockk<CollectionBackupService.Prepared>()
        every { result.conflicts } returns emptyList()
        coEvery { fixture.backup.prepare(any(), any(), any()) } coAnswers {
            manifest = reader().inspectArchive(File(secondArg<Uri>().path!!).inputStream(), staged)
            result
        }
        assertSame(result, fixture.manager.prepareImport(fixture.source, fixture.uri, true, false, false) { _, _ -> })
        assertEquals(listOf("Story"), manifest!!.data.mangas.map { it.url })
        val chapters = mutableListOf<String>()
        ZipInputStream(File(staged, "files/Story/Chapter 1.cbz").inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                chapters += entry.name
            }
        }
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), chapters)
        assertTrue(File(directory, "input/Story/10.jpg").readBytes().contentEquals(byteArrayOf(10)))
    }

    @Test
    fun `duplicate pages produce review warning without changing either work`() = runBlocking {
        val fixture = fixture(mapOf("1.jpg" to byteArrayOf(1)))
        val existing = mockk<Manga>()
        every { existing.url } returns "Existing"
        every { existing.title } returns "Existing"
        every { fixture.root.findFile("Existing") } returns fixture.input
        coEvery { fixture.manager.catalog(fixture.source) } returns listOf(existing)
        coEvery { fixture.source.withExclusiveCollectionAccess<List<String>>(any()) } coAnswers { firstArg<suspend () -> List<String>>().invoke() }
        coEvery { fixture.backup.prepare(any(), any(), any()) } coAnswers {
            val manifest = reader().inspectArchive(File(secondArg<Uri>().path!!).inputStream())
            CollectionBackupService.Prepared("operation", fixture.source.id, "root", directory, manifest, emptyMap(), emptyMap(), "", emptyList())
        }
        val plan = fixture.manager.prepareImport(fixture.source, fixture.uri, true, false) { _, _ -> }
        assertEquals(1, plan.conflicts.size)
        assertTrue(plan.conflicts.single().contains("Story / Existing"))
        assertTrue(plan.conflicts.single().contains("1/1"))
        coVerify(exactly = 0) { fixture.backup.apply(any(), any()) }
    }

    @Test fun `corrupt chapter is rejected before creating restore plan`() = runBlocking {
        val fixture = fixture(mapOf("chapter.cbz" to byteArrayOf(1, 2, 3)))
        assertThrows(Exception::class.java) { runBlocking { fixture.manager.prepareImport(fixture.source, fixture.uri, true, false, false) { _, _ -> } } }
        coVerify(exactly = 0) { fixture.backup.prepare(any(), any(), any()) }
    }

    @Test fun `existing title is never overwritten by import`() = runBlocking {
        val fixture = fixture(mapOf("1.jpg" to byteArrayOf(1)))
        val manga = mockk<Manga>()
        every { manga.url } returns "Story"
        coEvery { fixture.manager.catalog(fixture.source) } returns listOf(manga)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.manager.prepareImport(fixture.source, fixture.uri, true, false, false) { _, _ -> } } }
        coVerify(exactly = 0) { fixture.backup.prepare(any(), any(), any()) }
    }
}
