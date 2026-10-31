package tachiyomi.domain.storage.service

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

class PrivateCollectionStorageTest {
    private val context = mockk<Context>()
    private val preferences = mockk<StoragePreferences>()
    private val basePreference = mockk<Preference<String>>()
    private val collectionPreference = mockk<Preference<String>>()
    private val baseUri = mockk<Uri>()
    private val customUri = mockk<Uri>()
    private val base = mockk<UniFile>()
    private val legacy = mockk<UniFile>()
    private val custom = mockk<UniFile>()
    private var selected = ""

    @BeforeEach
    fun setup() {
        mockkStatic(Uri::class)
        mockkStatic(UniFile::class)
        every { preferences.baseStorageDirectory() } returns basePreference
        every { preferences.privateCollectionDirectory() } returns collectionPreference
        every { basePreference.get() } returns "content://base"
        every { basePreference.changes() } returns flowOf("content://base")
        every { collectionPreference.get() } answers { selected }
        every { Uri.parse("content://base") } returns baseUri
        every { Uri.parse("content://collection") } returns customUri
        every { UniFile.fromUri(context, baseUri) } returns base
        every { UniFile.fromUri(context, customUri) } returns custom
        listOf(base, custom).forEach { directory ->
            every { directory.exists() } returns true
            every { directory.isDirectory } returns true
            every { directory.canRead() } returns true
            every { directory.canWrite() } returns true
        }
        every { base.createDirectory("local") } returns legacy
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Uri::class)
        unmockkStatic(UniFile::class)
    }

    @Test
    fun `default keeps the existing local folder`() {
        assertSame(legacy, StorageManager(context, preferences).getLocalSourceDirectory())
    }

    @Test
    fun `custom folder is used directly without creating another local subfolder`() {
        selected = "content://collection"
        assertSame(custom, StorageManager(context, preferences).getPrivateCollectionDirectory())
        verify(exactly = 0) { custom.createDirectory(any()) }
        verify(exactly = 0) { base.createDirectory("local") }
    }

    @Test
    fun `lost permission does not silently switch back to legacy content`() {
        selected = "content://collection"
        every { UniFile.fromUri(context, customUri) } throws SecurityException("Access revoked")
        assertNull(StorageManager(context, preferences).getPrivateCollectionDirectory())
        verify(exactly = 0) { base.createDirectory("local") }
    }

    @Test
    fun `selecting a collection does not redirect downloads or backups`() {
        selected = "content://collection"
        val downloads = mockk<UniFile>()
        val backups = mockk<UniFile>()
        every { base.createDirectory("downloads") } returns downloads
        every { base.createDirectory("autobackup") } returns backups
        val storage = StorageManager(context, preferences)
        assertSame(custom, storage.getPrivateCollectionDirectory())
        assertSame(downloads, storage.getDownloadsDirectory())
        assertSame(backups, storage.getAutomaticBackupsDirectory())
    }

    @Test
    fun `Local stays on its original directory when Private selects another root`() {
        selected = "content://collection"
        val storage = StorageManager(context, preferences)
        assertSame(legacy, storage.getLocalSourceDirectory())
        assertSame(custom, storage.getPrivateCollectionDirectory())
    }

    @Test
    fun `default Private root is a separate dedicated directory`() {
        val ownFolder = mockk<UniFile>()
        every { base.createDirectory("MinhaColecao") } returns ownFolder
        val storage = StorageManager(context, preferences)
        assertSame(legacy, storage.getLocalSourceDirectory())
        assertSame(ownFolder, storage.getPrivateCollectionDirectory())
    }

    @Test
    fun `inaccessible custom folder remains unavailable`() {
        selected = "content://collection"
        every { custom.canRead() } returns false
        assertNull(StorageManager(context, preferences).getPrivateCollectionDirectory())
    }
}
