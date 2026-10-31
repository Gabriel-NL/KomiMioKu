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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope
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
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
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
import tachiyomi.source.local.metadata.MAX_TAG_CATALOG_BYTES
import tachiyomi.source.local.metadata.decodeCollectionTagCatalog
import tachiyomi.source.local.metadata.fillMetadata
import tachiyomi.source.local.metadata.withCollectionTags
import uy.kohesive.injekt.injectLazy
import java.io.InputStream
import java.nio.charset.StandardCharsets
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
    private val collectionMetadata by lazy {
        CollectionMetadataCache(
            scope = applicationScope,
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
        // SY -->
        val allowLocalSourceHiddenFolders = allowHiddenFiles()
        // SY <--

        var mangaDirs = fileSystem.getFilesInBaseDirectory()
            // Filter out files that are hidden and is not a folder
            .filter {
                it.isDirectory &&
                    // SY -->
                    (
                        !it.name.orEmpty().startsWith('.') ||
                            allowLocalSourceHiddenFolders
                        )
                // SY <--
            }
            .distinctBy { it.name }
            .filter {
                if (lastModifiedLimit == 0L && query.isBlank()) {
                    true
                } else if (lastModifiedLimit == 0L) {
                    it.name.orEmpty().contains(query, ignoreCase = true)
                } else {
                    it.lastModified() >= lastModifiedLimit
                }
            }

        filters.forEach { filter ->
            when (filter) {
                is OrderBy.Popular -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    } else {
                        mangaDirs.sortedWith(compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() })
                    }
                }
                is OrderBy.Latest -> {
                    mangaDirs = if (filter.state!!.ascending) {
                        mangaDirs.sortedBy(UniFile::lastModified)
                    } else {
                        mangaDirs.sortedByDescending(UniFile::lastModified)
                    }
                }
                else -> {
                    /* Do nothing */
                }
            }
        }

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
        val needsMetadata = selections.values.any { (included, excluded) ->
            included.isNotEmpty() || excluded.isNotEmpty()
        } || formatIncluded.isNotEmpty() || formatExcluded.isNotEmpty()

        val mangas = mangaDirs
            .map { mangaDir ->
                async {
                    SManga.create().apply {
                        title = mangaDir.name.orEmpty()
                        url = mangaDir.name.orEmpty()

                        // Try to find the cover
                        coverManager.find(mangaDir.name.orEmpty())?.let {
                            thumbnail_url = it.uri.toString()
                        }
                    }
                }
            }
            .awaitAll()

        // Load metadata only when needed. Do not parse every archive during ordinary browsing.
        // Sequential filtering bounds open files and memory for large collections.
        val matching = mangas.filter { manga ->
            if (needsMetadata) {
                getMangaDetails(manga)
            }
            val tags = parseCollectionValues(manga.genre)
            val metadataMatches = CollectionValues.Field.entries.all { field ->
                val values = when (field) {
                    CollectionValues.Field.TAGS -> tags
                    CollectionValues.Field.AUTHORS -> parseCollectionValues(manga.author)
                }
                val (included, excluded) = selections.getValue(field)
                matchesCollectionValues(values, included, excluded)
            }
            val formatMatches = matchesCollectionValues(tags, formatIncluded, formatExcluded)
            val readingMatches = if (!readingActive || !metadataMatches || !formatMatches) {
                true
            } else {
                val stored = getManga.await(manga.url, id)
                val chapters = stored?.let { getChapters.await(it.id) }.orEmpty()
                matchesCollectionValues(setOf(collectionReadingKey(chapters)), readingIncluded, readingExcluded)
            }
            metadataMatches && formatMatches && readingMatches
        }
        MangasPage(matching, false)
    }

    // SY -->
    /** Save tags at series level, preserving the rest of ComicInfo and every chapter. */
    suspend fun updateMangaTags(manga: SManga, mangaId: Long, tags: List<String>): List<String> = withIOContext {
        val names = collectionTagNames(collectionTagNames(tags).map(::canonicalLocalTagName))
        collectionMetadata.invalidate {
            val directory = fileSystem.getMangaDirectory(manga.url) ?: error("Local manga folder is unavailable")
            val files = directory.listFiles().orEmpty()
            val plain = files.firstOrNull { it.name == COMIC_INFO_FILE }
            val archive = files.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            archive?.archiveReader(context).use { reader ->
                val stream = plain?.openInputStream() ?: reader?.getInputStream(COMIC_INFO_FILE)
                val original = stream?.use {
                    AndroidXmlReader(it, StandardCharsets.UTF_8.name()).use { xmlReader ->
                        xml.decodeFromReader<ComicInfo>(xmlReader)
                    }
                } ?: run {
                    var failed = false
                    val details = getMangaDetails(manga) { failed = true }
                    check(!failed) { "Could not read existing local metadata" }
                    details.getComicInfo()
                }
                val updated = original.withCollectionTags(names)
                checkNotNull(
                    copyComicInfoFile(
                        xml.encodeToString(ComicInfo.serializer(), updated).byteInputStream(),
                        directory,
                        plain == null && reader?.encrypted == true,
                    ),
                ) { "Could not write local ComicInfo metadata" }
            }
            localTags.replaceMangaTags(mangaId, names)
        }
        names
    }

    fun updateMangaInfo(manga: SManga) {
        val mangaDirFiles = fileSystem.getFilesInMangaDirectory(manga.url)
        val existingFile = mangaDirFiles
            .firstOrNull { it.name == COMIC_INFO_FILE }
        val comicInfoArchiveFile = mangaDirFiles.firstOrNull { it.name == COMIC_INFO_ARCHIVE }
        val comicInfoArchiveReader = comicInfoArchiveFile?.archiveReader(context)
        val existingComicInfo =
            (existingFile?.openInputStream() ?: comicInfoArchiveReader?.getInputStream(COMIC_INFO_FILE))?.use {
                AndroidXmlReader(it, StandardCharsets.UTF_8.name()).use { xmlReader ->
                    xml.decodeFromReader<ComicInfo>(xmlReader)
                }
            }
        val newComicInfo = if (existingComicInfo != null) {
            manga.run {
                existingComicInfo.copy(
                    series = ComicInfo.Series(title),
                    summary = description?.let { ComicInfo.Summary(it) },
                    writer = author?.let { ComicInfo.Writer(it) },
                    penciller = artist?.let { ComicInfo.Penciller(it) },
                    // The editor presents Genre, Tags and Categories as one combined list.
                    // Store that list once, so removed Tags/Categories do not return on reload.
                    genre = genre?.let { ComicInfo.Genre(it) },
                    tags = null,
                    categories = null,
                    publishingStatus = ComicInfo.PublishingStatusTachiyomi(
                        ComicInfoPublishingStatus.toComicInfoValue(status.toLong()),
                    ),
                )
            }
        } else {
            manga.getComicInfo()
        }

        fileSystem.getMangaDirectory(manga.url)?.let {
            copyComicInfoFile(
                xml.encodeToString(ComicInfo.serializer(), newComicInfo).byteInputStream(),
                it,
                comicInfoArchiveReader?.encrypted ?: false,
            )
        }
    }
    // SY <--

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

    // Manga details related
    private suspend fun getMangaDetails(
        manga: SManga,
        onMetadataError: (() -> Unit)? = null,
    ): SManga = withIOContext {
        coverManager.find(manga.url)?.let {
            manga.thumbnail_url = it.uri.toString()
        }

        // Augment manga details based on metadata files
        try {
            val mangaDir = fileSystem.getMangaDirectory(manga.url) ?: error("${manga.url} is not a valid directory")
            val mangaDirFiles = mangaDir.listFiles().orEmpty()

            val comicInfoFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_FILE }
            val noXmlFile = mangaDirFiles
                .firstOrNull { it.name == ".noxml" }
            val legacyJsonDetailsFile = mangaDirFiles
                .firstOrNull { it.extension == "json" }
            // SY -->
            val comicInfoArchiveFile = mangaDirFiles
                .firstOrNull { it.name == COMIC_INFO_ARCHIVE }
            // SY <--

            when {
                // Top level ComicInfo.xml
                comicInfoFile != null -> {
                    noXmlFile?.delete()
                    setMangaDetailsFromComicInfoFile(comicInfoFile.openInputStream(), manga)
                }
                // SY -->
                comicInfoArchiveFile != null -> {
                    noXmlFile?.delete()

                    comicInfoArchiveFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                        ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                }

                // SY <--

                // Old custom JSON format
                // TODO: remove support for this entirely after a while
                legacyJsonDetailsFile != null -> {
                    json.decodeFromStream<MangaDetails>(legacyJsonDetailsFile.openInputStream()).run {
                        title?.let { manga.title = it }
                        author?.let { manga.author = it }
                        artist?.let { manga.artist = it }
                        description?.let { manga.description = it }
                        genre?.let { manga.genre = it.joinToString() }
                        status?.let { manga.status = it }
                    }
                    // Replace with ComicInfo.xml file
                    val comicInfo = manga.getComicInfo()
                    mangaDir
                        .createFile(COMIC_INFO_FILE)
                        ?.openOutputStream()
                        ?.use {
                            val comicInfoString = xml.encodeToString(ComicInfo.serializer(), comicInfo)
                            it.write(comicInfoString.toByteArray())
                            legacyJsonDetailsFile.delete()
                        }
                }

                // Copy ComicInfo.xml from chapter archive to top level if found
                noXmlFile == null -> {
                    val chapterArchives = mangaDirFiles.filter(Archive::isSupported)

                    val copiedFile = copyComicInfoFileFromChapters(chapterArchives, mangaDir)

                    // SY -->
                    if (copiedFile != null && copiedFile.name != COMIC_INFO_ARCHIVE) {
                        setMangaDetailsFromComicInfoFile(copiedFile.openInputStream(), manga)
                    } else if (copiedFile != null && copiedFile.name == COMIC_INFO_ARCHIVE) {
                        copiedFile.archiveReader(context).getInputStream(COMIC_INFO_FILE)
                            ?.let { setMangaDetailsFromComicInfoFile(it, manga) }
                    } // SY <--
                    else {
                        // Avoid re-scanning
                        mangaDir.createFile(".noxml")
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onMetadataError?.invoke()
            logcat(LogPriority.ERROR, e) { "Error setting manga details from local metadata for ${manga.title}" }
        }

        return@withIOContext manga
    }

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

    private fun copyComicInfoFileFromChapters(chapterArchives: List<UniFile>, folder: UniFile): UniFile? {
        for (chapter in chapterArchives) {
            val file = getComicInfoForChapter(chapter) f@{ stream, /* SY --> */ reader /* SY <-- */ ->
                return@f copyComicInfoFile(stream, folder, /* SY --> */ reader?.encrypted == true /* SY <-- */)
            }
            if (file != null) return file
        }
        return null
    }

    private fun copyComicInfoFile(
        comicInfoFileStream: InputStream,
        folder: UniFile,
        // SY -->
        encrypt: Boolean,
        // SY <--
    ): UniFile? {
        // SY -->
        if (encrypt) {
            val comicInfoArchiveFile = folder.createFile(COMIC_INFO_ARCHIVE)
            comicInfoArchiveFile?.let { archive ->
                ZipWriter(context, archive, encrypt = true).use { writer ->
                    writer.write(comicInfoFileStream.use { it.readBytes() }, COMIC_INFO_FILE)
                }
            }
            return comicInfoArchiveFile
        } else {
            // SY <--
            return folder.createFile(COMIC_INFO_FILE)?.apply {
                openOutputStream().use { outputStream ->
                    comicInfoFileStream.use { it.copyTo(outputStream) }
                }
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

    // Chapters
    private suspend fun getChapterList(manga: SManga): List<SChapter> = withIOContext {
        val chapters = fileSystem.getFilesInMangaDirectory(manga.url)
            // Only keep supported formats
            .filterNot { it.name.orEmpty().startsWith('.') }
            .filter { it.isDirectory || Archive.isSupported(it) || it.extension.equals("epub", true) }
            .map { chapterFile ->
                SChapter.create().apply {
                    url = "${manga.url}/${chapterFile.name}"
                    name = if (chapterFile.isDirectory) {
                        chapterFile.name
                    } else {
                        chapterFile.nameWithoutExtension
                    }.orEmpty()
                    date_upload = chapterFile.lastModified()
                    chapter_number = ChapterRecognition
                        .parseChapterNumber(manga.title, this.name, this.chapter_number.toDouble())
                        .toFloat()

                    val format = Format.valueOf(chapterFile)
                    if (format is Format.Epub) {
                        format.file.epubReader(context).use { epub ->
                            epub.fillMetadata(manga, this)
                        }
                    } else {
                        getComicInfoForChapter(chapterFile) { stream, /* SY --> */ _ /* SY <-- */ ->
                            setChapterDetailsFromComicInfoFile(stream, this)
                        }
                    }
                }
            }
            .sortedWith { c1, c2 ->
                c2.name.compareToCaseInsensitiveNaturalOrder(c1.name)
            }

        // Copy the cover from the first chapter found if not available
        if (manga.thumbnail_url.isNullOrBlank()) {
            chapters.lastOrNull()?.let { chapter ->
                updateCover(chapter, manga)
            }
        }

        chapters
    }

    /** Runs in the process scope, independently of any filter dialog. */
    fun preloadCollectionMetadata() {
        collectionMetadata.preload()
    }

    private suspend fun scanCollectionMetadata(): List<String> = withIOContext {
        importCollectionTagCatalog()
        val authors = mutableListOf<String>()
        val allowHidden = allowHiddenFiles()
        fileSystem.getFilesInBaseDirectory()
            .filter { it.isDirectory && (allowHidden || !it.name.orEmpty().startsWith('.')) }
            .distinctBy { it.name }
            .forEach { directory ->
                currentCoroutineContext().ensureActive()
                val manga = SManga.create().apply {
                    title = directory.name.orEmpty()
                    url = directory.name.orEmpty()
                }
                var metadataFailed = false
                getMangaDetails(manga) { metadataFailed = true }
                manga.author?.let(authors::add)
                localTags.syncLocalManga(
                    sourceId = id,
                    url = manga.url,
                    title = manga.title,
                    tags = if (metadataFailed) null else collectionTagNames(listOfNotNull(manga.genre)),
                )
            }
        collectionTagNames(authors)
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logcat(LogPriority.ERROR, error) { "Could not import local tag catalog; existing tags preserved" }
        }
    }

    suspend fun getCollectionTags(): List<String> = withIOContext {
        collectionMetadata.ensureLoaded()
        collectionMetadata.tags.value
    }

    suspend fun getCollectionAuthors(): List<String> = withIOContext {
        collectionMetadata.ensureLoaded()
        collectionMetadata.authors.value
    }

    fun observeCollectionAuthors(): Flow<List<String>> = collectionMetadata.authors

    /** Replay in-memory tags immediately; database changes remain observed between dialogs. */
    fun observeCollectionTags(): Flow<List<String>> {
        collectionMetadata.preload()
        return collectionMetadata.tags
    }

    suspend fun updateCollectionMangaTags(mangaId: Long, tags: List<String>) = withIOContext {
        collectionMetadata.invalidate {
            localTags.replaceMangaTags(mangaId, collectionTagNames(tags))
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
