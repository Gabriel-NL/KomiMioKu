package tachiyomi.source.local

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import logcat.LogPriority
import mihon.core.archive.ArchiveReader
import mihon.core.archive.ZipWriter
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import nl.adaptivity.xmlutil.core.AndroidXmlReader
import nl.adaptivity.xmlutil.serialization.XML
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.storage.nameWithoutExtension
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.core.metadata.comicinfo.ComicInfoPublishingStatus
import tachiyomi.core.metadata.comicinfo.copyFromComicInfo
import tachiyomi.core.metadata.comicinfo.getComicInfo
import tachiyomi.core.metadata.tachiyomi.MangaDetails
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.domain.localtag.model.CollectionTagChanges
import tachiyomi.domain.localtag.model.CollectionTagEdit
import tachiyomi.domain.localtag.model.IndexedLocalManga
import tachiyomi.domain.localtag.model.LocalChapterMetadata
import tachiyomi.domain.localtag.model.LocalCollectionOrder
import tachiyomi.domain.localtag.model.LocalMangaMetadata
import tachiyomi.domain.localtag.model.LocalMetadataDocument
import tachiyomi.domain.localtag.model.LocalMetadataStamp
import tachiyomi.domain.localtag.model.applyCollectionTagEdits
import tachiyomi.domain.localtag.model.canRemoveLegacyMetadata
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.domain.localtag.model.localMetadataFingerprint
import tachiyomi.domain.localtag.model.sortLocalCollection
import tachiyomi.domain.localtag.repository.LocalCollectionIndexRepository
import tachiyomi.domain.localtag.repository.LocalMetadataRepository
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.source.local.filter.CollectionIndexLocks
import tachiyomi.source.local.filter.CollectionMetadataCache
import tachiyomi.source.local.filter.CollectionOptions
import tachiyomi.source.local.filter.CollectionValues
import tachiyomi.source.local.filter.OrderBy
import tachiyomi.source.local.filter.collectionReadingKey
import tachiyomi.source.local.filter.collectionTagNames
import tachiyomi.source.local.filter.matchesCollectionValues
import tachiyomi.source.local.filter.parseCollectionValues
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.Archive
import tachiyomi.source.local.io.Format
import tachiyomi.source.local.io.LocalSourceFileSystem
import tachiyomi.source.local.metadata.COLLECTION_TAG_CATALOG
import tachiyomi.source.local.metadata.LOCAL_CHAPTER_TITLES
import tachiyomi.source.local.metadata.LocalChapterTitles
import tachiyomi.source.local.metadata.MAX_TAG_CATALOG_BYTES
import tachiyomi.source.local.metadata.decodeCollectionTagCatalog
import tachiyomi.source.local.metadata.fillMetadata
import tachiyomi.source.local.metadata.localChapterOrder
import tachiyomi.source.local.metadata.withCollectionTags
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.days
import tachiyomi.domain.source.model.Source as DomainSource

open class LocalSource(
    private val context: Context,
    private val fileSystem: LocalSourceFileSystem,
    private val coverManager: LocalCoverManager,
    // SY -->
    private val allowHiddenFiles: () -> Boolean,
    // SY <--
    private val applicationScope: CoroutineScope,
    override val id: Long = ID,
    customName: String? = null,
) : Source, UnmeteredSource {

    private val json: Json by injectLazy()
    private val xml: XML by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val getChapters: GetChaptersByMangaId by injectLazy()
    private val localTags: LocalTagRepository by injectLazy()
    private val databaseMetadata: LocalMetadataRepository by injectLazy()
    private val customMetadata: CustomMangaRepository by injectLazy()
    private val migrationMutex = Mutex()
    private val collectionIndex: LocalCollectionIndexRepository by injectLazy()
    private val lastVerification = AtomicLong(0L)
    private val collectionMetadata by lazy {
        CollectionMetadataCache(
            scope = applicationScope,
            mutex = CollectionIndexLocks.forSource(id),
            tagUpdates = localTags.observeAll(id).map { tags -> tags.map { it.name } },
            collectionKey = {
                val directory = fileSystem.getBaseDirectory()
                val catalog = directory?.findFile(COLLECTION_TAG_CATALOG)
                "${directory?.uri}:${directory?.lastModified()}:${allowHiddenFiles()}:" +
                    "${catalog?.lastModified()}:${catalog?.length()}"
            },
            scanAuthors = ::scanCollectionMetadata,
            onError = { error -> logcat(LogPriority.ERROR, error) { "Failed to preload local collection metadata" } },
        )
    }

    @Suppress("PrivatePropertyName")
    private val PopularFilters = FilterList(OrderBy.Popular(context))

    @Suppress("PrivatePropertyName")
    private val LatestFilters = FilterList(OrderBy.Latest(context))

    override val name: String = customName ?: context.stringResource(
        if (id == PRIVATE_COLLECTION_ID) KMR.strings.private_collection_name else MR.strings.local_source,
    )

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = true

    // Browse related
    override suspend fun getPopularManga(page: Int) = getSearchManga(page, "", PopularFilters)

    override suspend fun getLatestUpdates(page: Int) = getSearchManga(page, "", LatestFilters)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = withIOContext {
        val lastModifiedLimit = if (filters === LatestFilters) {
            System.currentTimeMillis() - LATEST_THRESHOLD
        } else {
            0L
        }
        val entries = indexedEntries()
        val valueFilters = filters.filterIsInstance<CollectionValues>()
        val options = filters.filterIsInstance<CollectionOptions>().associate { it.field to it.selections() }
        val (formatIncluded, formatExcluded) = options[CollectionOptions.Field.FORMAT] ?: (emptySet<String>() to emptySet())
        val (readingIncluded, readingExcluded) = options[CollectionOptions.Field.READING] ?: (emptySet<String>() to emptySet())
        val readingActive = readingIncluded.isNotEmpty() || readingExcluded.isNotEmpty()
        val selections = CollectionValues.Field.entries.associateWith { field ->
            val included = valueFilters.filter { it.field == field && !it.excluded }
                .flatMap { it.selections() }.toSet()
            val excluded = valueFilters.filter { it.field == field && it.excluded }
                .flatMap { it.selections() }.toSet()
            included to excluded
        }
        val reading = if (readingActive) collectionIndex.readingStates(id) else emptyMap()
        var accepted = entries.filter { entry ->
            if (!allowHiddenFiles() && entry.url.startsWith('.')) return@filter false
            if (lastModifiedLimit == 0L && query.isNotBlank() && !entry.url.contains(query, ignoreCase = true)) return@filter false
            if (lastModifiedLimit != 0L && entry.modified < lastModifiedLimit) return@filter false
            val tags = parseCollectionValues(entry.genre)
            val metadataMatches = CollectionValues.Field.entries.all { field ->
                val values = when (field) {
                    CollectionValues.Field.TAGS -> tags
                    CollectionValues.Field.AUTHORS -> parseCollectionValues(entry.author)
                }
                val (included, excluded) = selections.getValue(field)
                matchesCollectionValues(values, included, excluded)
            }
            metadataMatches && matchesCollectionValues(tags, formatIncluded, formatExcluded) &&
                (!readingActive || matchesCollectionValues(setOf(reading[entry.url] ?: "unread"), readingIncluded, readingExcluded))
        }
        // Order only matching indexed entries; the dropdown state chooses the actual field.
        filters.filterIsInstance<OrderBy>().firstOrNull()?.let { filter ->
            val selection = filter.state ?: eu.kanade.tachiyomi.source.model.Filter.Sort.Selection(0, true)
            val order = LocalCollectionOrder.entries.getOrElse(selection.index) { LocalCollectionOrder.TITLE }
            accepted = sortLocalCollection(accepted, order, selection.ascending)
        }
        MangasPage(accepted.map { entry -> entry.toSourceManga() }, false)
    }

    private fun SManga.databaseFields() = LocalMangaMetadata(
        title,
        author,
        artist,
        description,
        collectionTagNames(listOfNotNull(genre)),
        status.toLong(),
        thumbnail_url,
    )

    private fun SManga.applyDatabase(fields: LocalMangaMetadata) {
        title = fields.title
        author = fields.author
        artist = fields.artist
        description = fields.description
        genre = fields.tags.joinToString(", ")
        status = fields.status.toInt()
        thumbnail_url = fields.thumbnail
        initialized = true
    }

    suspend fun updateMangaTags(manga: SManga, mangaId: Long, tags: List<String>): List<String> =
        editMangaTags(manga, mangaId) { tags }

    suspend fun applyMangaTagEdits(manga: SManga, mangaId: Long, edits: List<CollectionTagEdit>): List<String> =
        editMangaTags(manga, mangaId) { applyCollectionTagEdits(it, edits) }

    private suspend fun editMangaTags(manga: SManga, mangaId: Long, transform: (List<String>) -> List<String>): List<String> = withIOContext {
        var names = emptyList<String>()
        collectionMetadata.invalidate(rescan = false) {
            ensureDatabaseMetadata(manga)
            val stored = checkNotNull(databaseMetadata.read(id, manga.url))
            check(stored.id == mangaId) { "Manga identity changed" }
            names = collectionTagNames(transform(stored.fields.tags).map(::canonicalLocalTagName))
            databaseMetadata.save(id, manga.url, stored.fields.copy(tags = names))
            manga.applyDatabase(stored.fields.copy(tags = names))
            updateIndexedManga(manga)
            CollectionTagChanges.markDirty(id)
        }
        names
    }

    suspend fun updateMangaInfo(manga: SManga) = withIOContext {
        collectionMetadata.invalidate(rescan = false) {
            ensureDatabaseMetadata(manga)
            databaseMetadata.save(id, manga.url, manga.databaseFields())
            updateIndexedManga(manga)
            CollectionTagChanges.markDirty(id)
        }
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { getMangaDetails(manga) } else null
        val asyncChapters = if (fetchChapters) async { getChapterList(manga) } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    // Migration reads legacy documents once. Normal detail reads use SQLite exclusively.
    private suspend fun getMangaDetails(manga: SManga, findCover: Boolean = true, onMetadataError: (() -> Unit)? = null): SManga = withIOContext {
        ensureDatabaseMetadata(manga)
        manga.applyDatabase(checkNotNull(databaseMetadata.read(id, manga.url)).fields)
        if (findCover) coverManager.find(manga.url)?.let { manga.thumbnail_url = it.uri.toString() }
        manga
    }

    private fun readLegacyBytes(stream: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(output.size() + count <= 8 * 1024 * 1024) { "Legacy metadata exceeds 8 MiB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
    private fun bytes(file: UniFile): ByteArray {
        require(file.length() <= 8 * 1024 * 1024) { "Legacy metadata exceeds 8 MiB" }
        return file.openInputStream().use(::readLegacyBytes)
    }

    private suspend fun ensureDatabaseMetadata(manga: SManga) = migrationMutex.withLock {
        if (databaseMetadata.read(id, manga.url)?.imported == true) {
            cleanupMigratedSidecars(manga.url)
            return@withLock
        }
        val directory = fileSystem.getMangaDirectory(manga.url) ?: error("Manga folder unavailable")
        val documents = mutableListOf<LocalMetadataDocument>()
        fun capture(file: UniFile, path: String, binary: Boolean = false): ByteArray {
            val value = bytes(file)
            val text = if (binary) null else runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(value)).toString() }.getOrNull()
            documents += LocalMetadataDocument(manga.url, path, if (text == null) "base64" else "utf8", text ?: Base64.getEncoder().encodeToString(value))
            return value
        }
        val legacy = SManga.create().apply {
            url = manga.url
            title = manga.url
        }
        val files = checkNotNull(directory.listFiles())
        val plain = files.firstOrNull { it.name == COMIC_INFO_FILE }
        val encrypted = files.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
        if (plain != null) legacy.copyFromComicInfo(parseComicInfo(capture(plain, COMIC_INFO_FILE).inputStream()))
        if (encrypted != null) {
            capture(encrypted, COMIC_INFO_ARCHIVE, binary = true)
            if (plain == null) {
                encrypted.archiveReader(context).use { reader ->
                    checkNotNull(reader.getInputStream(COMIC_INFO_FILE)).use { legacy.copyFromComicInfo(parseComicInfo(it)) }
                }
            }
        }
        files.firstOrNull { it.name == "details.json" }?.let { file ->
            val raw = capture(file, "details.json")
            if (plain == null && encrypted == null) {
                json.decodeFromString<MangaDetails>(raw.toString(Charsets.UTF_8)).run {
                    title?.let { legacy.title = it }
                    author?.let { legacy.author = it }
                    artist?.let { legacy.artist = it }
                    description?.let { legacy.description = it }
                    genre?.let { legacy.genre = it.joinToString(", ") }
                    status?.let { legacy.status = it }
                }
            }
        }
        val overrides = files.firstOrNull { it.name == LOCAL_CHAPTER_TITLES }?.let { file ->
            json.decodeFromString<LocalChapterTitles>(capture(file, LOCAL_CHAPTER_TITLES).toString(Charsets.UTF_8)).also { require(it.version == 1) }.chapters
        }.orEmpty()
        var chapterFallbackRead = plain != null || encrypted != null || files.any { it.name == "details.json" }
        val legacyChapters = chapterFiles(files).map { file ->
            SChapter.create().apply {
                url = "${manga.url}/${file.name}"
                name = if (file.isDirectory) file.name.orEmpty() else file.nameWithoutExtension.orEmpty()
                chapter_number = ChapterRecognition.parseChapterNumber(legacy.title, name, -1.0).toFloat()
                date_upload = file.lastModified()
                if (Format.valueOf(file) is Format.Epub) {
                    file.epubReader(context).use { it.fillMetadata(legacy, this) }
                } else {
                    getComicInfoForChapter(file) { stream, _ ->
                        val raw = readLegacyBytes(stream)
                        val path = if (file.isDirectory) "${file.name}/$COMIC_INFO_FILE" else "${file.name}!/$COMIC_INFO_FILE"
                        documents += LocalMetadataDocument(manga.url, path, "utf8", raw.toString(Charsets.UTF_8))
                        val info = parseComicInfo(raw.inputStream())
                        if (!chapterFallbackRead) {
                            legacy.copyFromComicInfo(info)
                            chapterFallbackRead = true
                        }
                        setChapterDetailsFromComicInfoFile(raw.inputStream(), this)
                    }
                }
                overrides[file.name]?.let {
                    name = it.name
                    chapter_number = it.number
                }
            }
        }.sortedWith(localChapterOrder(overrides))
        val chapters = legacyChapters.mapIndexed { index, chapter -> LocalChapterMetadata(chapter.url, chapter.name, chapter.chapter_number.toDouble(), index.toLong(), chapter.scanlator, chapter.date_upload) }
        files.filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }.forEach { child ->
            child.listFiles().orEmpty().filter { it.name in setOf(COMIC_INFO_FILE, COMIC_INFO_ARCHIVE, "details.json", LOCAL_CHAPTER_TITLES) }.forEach { file ->
                val path = "${child.name}/${file.name}"
                if (documents.none { it.path == path }) capture(file, path, file.name == COMIC_INFO_ARCHIVE)
            }
        }
        val stored = getManga.await(manga.url, id)
        val override = stored?.let { customMetadata.get(it.id) }
        val preferred = if (stored != null && override != null) {
            LocalMangaMetadata(
                stored.title,
                stored.author,
                stored.artist,
                stored.description,
                stored.genre.orEmpty(),
                stored.status,
                stored.thumbnailUrl,
            )
        } else {
            null
        }
        databaseMetadata.importLegacy(id, manga.url, legacy.databaseFields(), chapters, documents, preferred)
        if (stored != null && override != null) customMetadata.set(CustomMangaInfo(stored.id, null))
        // Import has committed before any external copy is eligible for deletion.
        cleanupMigratedSidecars(manga.url)
    }

    private suspend fun cleanupMigratedSidecars(url: String) {
        val folder = fileSystem.getMangaDirectory(url) ?: return
        val sidecars = mutableListOf<Pair<String, UniFile>>()
        fun collect(directory: UniFile, prefix: String) {
            directory.listFiles().orEmpty().filter { it.name in setOf(COMIC_INFO_FILE, COMIC_INFO_ARCHIVE, "details.json", LOCAL_CHAPTER_TITLES) }.forEach {
                sidecars += (prefix + it.name) to it
            }
            directory.findFile(".noxml")?.let { check(it.delete()) }
        }
        collect(folder, "")
        folder.listFiles().orEmpty().filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }.forEach { collect(it, "${it.name}/") }
        if (sidecars.isEmpty()) return
        val stored = databaseMetadata.documents(id).filter { it.manga == url }.associateBy { it.path }
        sidecars.forEach { (path, file) ->
            val document = stored[path] ?: return@forEach
            if (canRemoveLegacyMetadata(true, document, bytes(file))) check(file.delete()) { "Cannot remove migrated metadata: $path" }
        }
    }

    private fun chapterFiles(files: Array<UniFile>) = files.filterNot { it.name.orEmpty().startsWith('.') || it.name in setOf(COMIC_INFO_FILE, COMIC_INFO_ARCHIVE, "details.json") }
        .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }

    private fun <T> getComicInfoForChapter(chapter: UniFile, block: (InputStream, ArchiveReader?) -> T): T? {
        if (chapter.isDirectory) {
            return chapter.findFile(COMIC_INFO_FILE)?.let { file ->
                file.openInputStream().use { block(it, /* SY --> */ null /* SY <-- */) }
            }
        } else {
            return chapter.archiveReader(context).use { reader ->
                reader.getInputStream(COMIC_INFO_FILE)?.use { block(it, /* SY --> */ reader /* SY <-- */) }
            }
        }
    }

    private fun parseComicInfo(stream: InputStream): ComicInfo {
        return AndroidXmlReader(stream, StandardCharsets.UTF_8.name()).use {
            xml.decodeFromReader<ComicInfo>(it)
        }
    }

    private fun setMangaDetailsFromComicInfoFile(stream: InputStream, manga: SManga) {
        manga.copyFromComicInfo(parseComicInfo(stream))
    }

    private fun setChapterDetailsFromComicInfoFile(stream: InputStream, chapter: SChapter) {
        val comicInfo = parseComicInfo(stream)

        comicInfo.title?.let { chapter.name = it.value }
        comicInfo.number?.value?.toFloatOrNull()?.let { chapter.chapter_number = it }
        comicInfo.translator?.let { chapter.scanlator = it.value }
    }

    // Discover physical chapter paths, then take every display field from SQLite.
    private suspend fun getChapterList(manga: SManga, createCover: Boolean = true): List<SChapter> = withIOContext {
        ensureDatabaseMetadata(manga)
        var stored = checkNotNull(databaseMetadata.read(id, manga.url))
        val physical = chapterFiles(fileSystem.getFilesInMangaDirectory(manga.url).toTypedArray())
        val newFiles = physical.filter { file -> stored.chapters.none { it.url == "${manga.url}/${file.name}" } }
        if (newFiles.isNotEmpty()) {
            val base = (stored.chapters.minOfOrNull { it.order } ?: 0L) - newFiles.size
            databaseMetadata.discoverChapters(
                id,
                manga.url,
                newFiles.mapIndexed { index, file ->
                    val name = if (file.isDirectory) file.name.orEmpty() else file.nameWithoutExtension.orEmpty()
                    LocalChapterMetadata("${manga.url}/${file.name}", name, ChapterRecognition.parseChapterNumber(stored.fields.title, name, -1.0), base + index, null, file.lastModified())
                },
            )
            stored = checkNotNull(databaseMetadata.read(id, manga.url))
        }
        val byUrl = stored.chapters.associateBy { it.url }
        val chapters = physical.map { file ->
            SChapter.create().apply {
                url = "${manga.url}/${file.name}"
                val saved = byUrl[url]
                name = saved?.name ?: if (file.isDirectory) file.name.orEmpty() else file.nameWithoutExtension.orEmpty()
                chapter_number = saved?.number?.toFloat() ?: ChapterRecognition.parseChapterNumber(stored.fields.title, name, -1.0).toFloat()
                scanlator = saved?.scanlator
                date_upload = saved?.uploaded ?: file.lastModified()
            }
        }.sortedWith { a, b ->
            val first = byUrl[a.url]?.order
            val second = byUrl[b.url]?.order
            when {
                first != null && second != null -> first.compareTo(second)
                first != null -> 1
                second != null -> -1
                else -> b.name.compareToCaseInsensitiveNaturalOrder(a.name)
            }
        }
        if (createCover && manga.thumbnail_url.isNullOrBlank()) chapters.lastOrNull()?.let { updateCover(it, manga) }
        chapters
    }

    /** Caller already holds exclusive collection access during backup. */
    suspend fun prepareDatabaseMetadata() {
        scanCollectionMetadata()
    }

    /** Show persisted entries immediately; verify child files off the browsing path. */
    fun preloadCollectionMetadata() {
        applicationScope.launch {
            try {
                fileSystem.getBaseDirectory()?.let { collectionIndex.load(id, it.uri.toString()) }
                requestIndexVerification(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Could not hydrate collection index" }
            }
        }
    }

    private fun requestIndexVerification(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val previous = lastVerification.get()
        if ((force || now - previous > 30000L) && lastVerification.compareAndSet(previous, now)) {
            collectionMetadata.preload(force = true)
        }
    }

    private suspend fun indexedEntries(): List<IndexedLocalManga> {
        val root = fileSystem.getBaseDirectory()?.uri?.toString() ?: return emptyList()
        val snapshot = collectionIndex.load(id, root)
        if (!snapshot.ready) collectionMetadata.ensureLoaded() else requestIndexVerification()
        return collectionIndex.load(id, root).entries
    }

    private fun IndexedLocalManga.toSourceManga(): SManga = SManga.create().also {
        it.url = url
        it.title = title
        it.author = author
        it.artist = artist
        it.description = description
        it.genre = genre
        it.status = status.toInt()
        it.thumbnail_url = thumbnail
    }

    private fun fingerprint(directory: UniFile): String {
        val files = checkNotNull(directory.listFiles()) { "Cannot inspect manga folder" }
        val stamps = files.map { LocalMetadataStamp(it.name.orEmpty(), it.lastModified(), it.length()) }.toMutableList()
        return localMetadataFingerprint(stamps)
    }

    private fun indexEntry(root: String, directory: UniFile, manga: SManga, signature: String): IndexedLocalManga {
        val cover = directory.listFiles().orEmpty().firstOrNull {
            it.isFile && it.name.orEmpty().lowercase(java.util.Locale.ROOT) in setOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp", "cover.avif", "cover.gif", "cover.bmp", "cover.heic", "cover.heif", "cover.jxl", "cover.cbi")
        }?.uri?.toString()
        return IndexedLocalManga(
            id, root, manga.url, manga.title, manga.author, manga.artist, manga.description,
            manga.genre, manga.status.toLong(), cover, directory.lastModified(), signature,
        )
    }

    private suspend fun updateIndexedManga(manga: SManga) {
        val root = fileSystem.getBaseDirectory()?.uri?.toString() ?: error("Collection unavailable")
        val directory = fileSystem.getMangaDirectory(manga.url) ?: error("Manga folder unavailable")
        val entry = indexEntry(root, directory, manga, fingerprint(directory))
        check(fileSystem.getBaseDirectory()?.uri?.toString() == root) { "Collection changed" }
        collectionIndex.put(entry)
    }

    private suspend fun scanCollectionMetadata(): List<String> = withIOContext {
        val directory = fileSystem.getBaseDirectory() ?: error("Collection unavailable")
        check(directory.exists() && directory.isDirectory && directory.canRead()) { "Collection unavailable" }
        val root = directory.uri.toString()
        val old = collectionIndex.load(id, root).entries.associateBy { it.url }
        val children = checkNotNull(directory.listFiles()) { "Could not list collection" }
        check(children.none { it.name.orEmpty().startsWith(".collection-edit-pending-") }) { "Collection file edit requires recovery" }
        val folders = children.filter { it.isDirectory && !it.name.orEmpty().startsWith(".collection-edit-") }.distinctBy { it.name }
        val changed = mutableListOf<IndexedLocalManga>()
        importCollectionTagCatalog()
        for (folder in folders) {
            currentCoroutineContext().ensureActive()
            val url = folder.name ?: continue
            if (!allowHiddenFiles() && url.startsWith('.')) continue
            try {
                val signature = fingerprint(folder)
                val previous = old[url]
                val manga = SManga.create().apply {
                    this.url = url
                    title = url
                }
                var failed = false
                getMangaDetails(manga, findCover = false) { failed = true }
                getChapterList(manga, createCover = false)
                check(fileSystem.getBaseDirectory()?.uri?.toString() == root) { "Collection changed" }
                if (!failed) {
                    changed += indexEntry(root, folder, manga, fingerprint(folder))
                } else if (previous == null) {
                    changed += indexEntry(root, folder, manga, "")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // An unreadable title cannot erase its previously indexed metadata or links.
                if (old[url] == null) {
                    changed += IndexedLocalManga(id, root, url, url, null, null, null, null, 0, null, 0, "")
                }
                logcat(LogPriority.ERROR, e) { "Could not update indexed local title: $url" }
            }
        }
        check(fileSystem.getBaseDirectory()?.uri?.toString() == root && directory.canRead()) { "Collection changed or unavailable" }
        val updated = collectionIndex.finishScan(id, root, changed, folders.mapNotNull { it.name }.toSet())
        if (updated) CollectionTagChanges.markDirty(id)
        collectionTagNames(collectionIndex.load(id, root).entries.filter { allowHiddenFiles() || !it.url.startsWith('.') }.mapNotNull { it.author })
    }

    /** The app owns database writes; the PC only publishes this shared-storage catalog. */
    private suspend fun importCollectionTagCatalog() {
        val catalog = fileSystem.getBaseDirectory()?.findFile(COLLECTION_TAG_CATALOG) ?: return
        try {
            val text = checkNotNull(catalog.openInputStream()).use { stream ->
                val bytes = ByteArray(MAX_TAG_CATALOG_BYTES + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = stream.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                }
                check(size <= MAX_TAG_CATALOG_BYTES) { "Tag catalog is too large" }
                StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes, 0, size)).toString()
            }
            localTags.addAll(decodeCollectionTagCatalog(text), id)
            databaseMetadata.restoreDocuments(id, listOf(LocalMetadataDocument("", COLLECTION_TAG_CATALOG, "utf8", text)))
            if (bytes(catalog).toString(Charsets.UTF_8) == text) check(catalog.delete()) { "Cannot remove migrated tag catalog" }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Could not import local tag catalog; existing tags preserved" }
        }
    }

    suspend fun getCollectionTags(): List<String> = withIOContext {
        requestIndexVerification()
        localTags.getAll(id).map { it.name }
    }

    suspend fun getCollectionAuthors(): List<String> = withIOContext {
        collectionTagNames(indexedEntries().filter { allowHiddenFiles() || !it.url.startsWith('.') }.mapNotNull { it.author })
    }

    fun observeCollectionAuthors(): Flow<List<String>> = flow {
        val root = fileSystem.getBaseDirectory()?.uri?.toString() ?: return@flow
        collectionIndex.load(id, root)
        requestIndexVerification()
        emitAll(collectionIndex.observe(id, root).map { snapshot -> collectionTagNames(snapshot?.entries.orEmpty().filter { allowHiddenFiles() || !it.url.startsWith('.') }.mapNotNull { it.author }) })
    }

    /** Replay in-memory tags immediately; database changes remain observed between dialogs. */
    fun observeCollectionTags(): Flow<List<String>> {
        collectionMetadata.preload()
        return collectionMetadata.tags
    }

    suspend fun updateCollectionMangaTags(mangaId: Long, tags: List<String>) = withIOContext {
        collectionMetadata.invalidate(rescan = false) {
            getManga.await(mangaId)?.let { stored ->
                val current = checkNotNull(databaseMetadata.read(id, stored.url))
                databaseMetadata.save(id, stored.url, current.fields.copy(tags = collectionTagNames(tags)))
                val manga = SManga.create().apply {
                    url = stored.url
                    title = stored.ogTitle
                    author = stored.ogAuthor
                    artist = stored.ogArtist
                    description = stored.ogDescription
                    genre = tags.joinToString(", ")
                    status = stored.ogStatus.toInt()
                }
                updateIndexedManga(manga)
            }
            CollectionTagChanges.markDirty(id)
        }
    }

    // Filters
    override fun getFilterList() = FilterList(
        OrderBy.Popular(context),
        // Retain the saved-search field position; the shared dialog renders one legend above all sections.
        Filter.Header(""),
        CollectionValues(
            context,
            CollectionValues.Field.TAGS,
            excluded = false,
            loadValues = ::getCollectionTags,
            observeValues = ::observeCollectionTags,
        ),
        CollectionValues(context, CollectionValues.Field.TAGS, excluded = true),
        CollectionValues(
            context,
            CollectionValues.Field.AUTHORS,
            excluded = false,
            loadValues = ::getCollectionAuthors,
            observeValues = ::observeCollectionAuthors,
        ),
        CollectionValues(context, CollectionValues.Field.AUTHORS, excluded = true),
        CollectionOptions(context, CollectionOptions.Field.FORMAT),
        CollectionOptions(context, CollectionOptions.Field.READING),
    )

    // Unused stuff
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    suspend fun <T> withExclusiveCollectionAccess(block: suspend () -> T): T =
        CollectionIndexLocks.forSource(id).withLock { block() }

    fun refreshAfterFileEdit() {
        applicationScope.launch {
            collectionIndex.invalidate(id)
            lastVerification.set(0L)
            CollectionTagChanges.markDirty(id)
            requestIndexVerification(force = true)
        }
    }

    fun getCollectionDirectory() = fileSystem.getBaseDirectory()

    fun getFormat(chapter: SChapter): Format {
        try {
            val (mangaDirName, chapterName) = chapter.url.split('/', limit = 2)
            return fileSystem.getBaseDirectory()
                ?.findFile(mangaDirName)
                ?.findFile(chapterName)
                ?.let(Format.Companion::valueOf)
                ?: throw Exception(context.stringResource(MR.strings.chapter_not_found))
        } catch (_: Format.UnknownFormatException) {
            throw Exception(context.stringResource(MR.strings.local_invalid_format))
        } catch (e: Exception) {
            throw e
        }
    }

    private fun updateCover(chapter: SChapter, manga: SManga): UniFile? {
        return try {
            when (val format = getFormat(chapter)) {
                is Format.Directory -> {
                    val entry = format.file.listFiles()
                        ?.sortedWith { f1, f2 ->
                            f1.name.orEmpty().compareToCaseInsensitiveNaturalOrder(
                                f2.name.orEmpty(),
                            )
                        }
                        ?.find {
                            !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() }
                        }

                    entry?.let { coverManager.update(manga, it.openInputStream()) }
                }
                is Format.Archive -> {
                    format.file.archiveReader(context).use { reader ->
                        val entry = reader.useEntries { entries ->
                            entries
                                .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
                                .find { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                        }

                        entry?.let { coverManager.update(manga, reader.getInputStream(it.name)!!, reader.encrypted) }
                    }
                }
                is Format.Epub -> {
                    format.file.epubReader(context).use { epub ->
                        val entry = epub.getImagesFromPages().firstOrNull()

                        entry?.let { coverManager.update(manga, epub.getInputStream(it)!!) }
                    }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Error updating cover for ${manga.title}" }
            null
        }
    }

    companion object {
        const val ID = FileSourceIds.LOCAL
        const val PRIVATE_COLLECTION_ID = FileSourceIds.PRIVATE_COLLECTION
        const val HELP_URL = "https://komikku-app.github.io/docs/guides/local-source/"

        // SY -->
        const val COMIC_INFO_ARCHIVE = "ComicInfo.cbm"
        // SY <--

        private val LATEST_THRESHOLD = 7.days.inWholeMilliseconds
    }
}

fun Manga.isLocal(): Boolean = FileSourceIds.contains(source)

fun Source.isLocal(): Boolean = FileSourceIds.contains(id)

fun DomainSource.isLocal(): Boolean = FileSourceIds.contains(id)
