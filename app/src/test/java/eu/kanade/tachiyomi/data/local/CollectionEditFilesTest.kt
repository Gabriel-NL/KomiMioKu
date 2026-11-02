package eu.kanade.tachiyomi.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

class CollectionEditFilesTest {
    @TempDir lateinit var directory: File
    private val resolver = mockk<ContentResolver>()
    private val context = mockk<Context>().also { every { it.contentResolver } returns resolver }
    private val operations = CollectionEditFiles(context)
    private fun provider(actual: File): UniFile {
        val uri = mockk<Uri>()
        every { uri.scheme } returns "file"
        every { uri.path } returns actual.absolutePath
        every { uri.toString() } returns actual.toURI().toString()
        val file = mockk<UniFile>()
        every { file.uri } returns uri
        every { file.name } answers { actual.name }
        every { file.isDirectory } answers { actual.isDirectory }
        every { file.listFiles() } answers { actual.listFiles()?.map(::provider)?.toTypedArray() }
        every { file.findFile(any()) } answers { File(actual, firstArg<String>()).takeIf { it.exists() }?.let(::provider) }
        every { file.openInputStream() } answers { actual.inputStream() }
        every { file.createDirectory(any()) } answers { File(actual, firstArg<String>()).let { if (it.mkdir()) provider(it) else null } }
        every { file.createFile(any()) } answers { File(actual, firstArg<String>()).let { if (it.createNewFile()) provider(it) else null } }
        every { file.renameTo(any()) } answers { actual.renameTo(File(actual.parentFile, firstArg<String>())) }
        every { file.delete() } answers { actual.deleteRecursively() }
        every { resolver.openOutputStream(uri, "wt") } answers { actual.outputStream() }
        return file
    }

    @Test
    fun `copy verifies real chapter bytes and preserves empty image directories`() {
        val original = File(directory, "Original").also { it.mkdir() }
        File(original, "chapter.cbz").writeBytes(byteArrayOf(0, 1, 2, 3, 0))
        File(original, "Empty").mkdir()
        val destination = File(directory, "Staging").also { it.mkdir() }
        val copied = operations.copy(provider(original), provider(destination), "New")
        assertEquals(operations.tree(provider(original)), operations.tree(copied))
        assertTrue(original.exists())
        assertTrue(File(destination, "New/Empty").isDirectory)
    }

    @Test
    fun `case insensitive name collision leaves both original files unchanged`() {
        val original = File(directory, "chapter.cbz").also { it.writeText("old") }
        val destination = File(directory, "Staging").also { it.mkdir() }
        File(destination, "CHAPTER.cbz").writeText("keep")
        assertThrows(IllegalStateException::class.java) { operations.copy(provider(original), provider(destination), "chapter.cbz") }
        assertEquals("old", original.readText())
        assertEquals("keep", File(destination, "CHAPTER.cbz").readText())
    }

    @Test
    fun `rollback removes only output owned by this operation`() {
        val output = File(directory, "New").also { it.mkdir() }
        File(output, ".collection-edit-owner.txt").writeText("operation")
        File(output, "chapter.cbz").writeText("chapter")
        val expected = operations.tree(provider(output))
        assertThrows(IllegalStateException::class.java) { operations.rollbackOutput(provider(output), "other", expected) }
        assertTrue(output.exists())
        operations.rollbackOutput(provider(output), "operation", expected)
        assertFalse(output.exists())
    }

    @Test
    fun `rollback preserves files edited after interruption`() {
        val output = File(directory, "New").also { it.mkdir() }
        File(output, ".collection-edit-owner.txt").writeText("operation")
        val chapter = File(output, "chapter.cbz").also { it.writeText("before") }
        val expected = operations.tree(provider(output))
        chapter.writeText("user edit")
        assertThrows(IllegalStateException::class.java) { operations.rollbackOutput(provider(output), "operation", expected) }
        assertEquals("user edit", chapter.readText())
    }

    @Test
    fun `failed provider rename does not remove the original folder`() {
        val original = File(directory, "Original").also { it.mkdir() }
        File(original, "chapter.cbz").writeText("chapter")
        val file = provider(original)
        every { file.renameTo("New") } returns false
        assertThrows(IllegalStateException::class.java) { operations.checkedRename(file, provider(directory), "New") }
        assertEquals("chapter", File(original, "chapter.cbz").readText())
        assertFalse(File(directory, "New").exists())
    }
}
