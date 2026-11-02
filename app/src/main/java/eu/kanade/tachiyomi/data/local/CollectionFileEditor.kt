package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.util.AtomicFile
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.model.CollectionFileNames
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.CustomMangaRepository
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.metadata.LOCAL_CHAPTER_TITLES
import tachiyomi.source.local.metadata.LocalChapterTitle
import tachiyomi.source.local.metadata.LocalChapterTitles
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.UUID

/** Copy-on-write edits: originals survive in hidden folders; chapter IDs/history survive in SQLite. */
class CollectionFileEditor private constructor(private val context: Context) {
    private val handler: DatabaseHandler = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val tags: LocalTagRepository = Injekt.get()
    private val custom: CustomMangaRepository = Injekt.get()
    private val json = Json { encodeDefaults = true }
    private val mutex = Mutex()
    private val files = CollectionEditFiles(context)
    private val work get() = File(context.filesDir, "collection-file-edits").also { it.mkdirs() }

    data class ChapterChoice(val mangaId: Long, val url: String, val file: String, val title: String, val number: Float, val directory: Boolean)
    data class Preview(val mangas: List<Manga>, val chapters: List<ChapterChoice>, val root: String)

    @Serializable
    private data class Original(val name: String, val backup: String)

    @Serializable
    private data class Journal(val operation: String, val source: Long, val root: String, val stage: String, val output: String, val originals: List<Original>, val mangaId: Long, val title: String, val signature: Map<String, String> = emptyMap())
    private data class Planned(val choice: ChapterChoice, val file: String, val title: String, val number: Float)

    suspend fun preview(source: LocalSource, selected: List<Manga>): Preview = withContext(Dispatchers.IO) {
        mutex.withLock {
            source.withExclusiveCollectionAccess {
                recoverLocked(source)
                val mangas = selected.map { selectedManga ->
                    checkNotNull(getManga.await(selectedManga.id)).also {
                        check(it.source == source.id && it.url == selectedManga.url) { "The selected manga changed. Select it again." }
                    }
                }
                require(mangas.isNotEmpty() && mangas.distinctBy { it.id }.size == mangas.size)
                val chapters = mangas.flatMap { manga ->
                    source.getMangaUpdate(manga.toSManga(), emptyList(), false, true).chapters.asReversed().map { chapter ->
                        val file = chapter.url.removePrefix("${manga.url}/")
                        CollectionFileNames.validate(file)
                        val actual = root(source).findFile(manga.url)?.findFile(file) ?: error("Chapter disappeared: $file")
                        ChapterChoice(manga.id, chapter.url, file, chapter.name, chapter.chapter_number, actual.isDirectory)
                    }.also { require(it.isNotEmpty()) { "No readable chapters: ${manga.title}" } }
                }
                Preview(mangas, chapters, root(source).uri.toString())
            }
        }
    }

    suspend fun apply(source: LocalSource, preview: Preview, title: String, chapterTitles: List<String>, merge: Boolean) =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                source.withExclusiveCollectionAccess {
                    recoverLocked(source)
                    editLocked(source, preview, title, chapterTitles, merge)
                }
            }
        }

    suspend fun recover(source: LocalSource) = withContext(Dispatchers.IO + NonCancellable) {
        mutex.withLock { source.withExclusiveCollectionAccess { recoverLocked(source) } }
    }

    private fun root(source: LocalSource): UniFile = checkNotNull(source.getCollectionDirectory()) { "Collection unavailable" }.also {
        check(it.isDirectory && it.canRead() && it.canWrite()) { "Collection is not writable" }
    }

    private fun journalFile(id: String) = AtomicFile(File(work, "$id.json"))
    private fun writeJournal(journal: Journal) {
        val file = journalFile(journal.operation)
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(journal).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Throwable) {
            file.failWrite(output)
            throw e
        }
    }

    private suspend fun committed(id: String) = handler.await { collectionBackupCommitsQueries.isCommitted(id).executeAsOne() > 0 }
    private fun pending(journal: Journal) = ".collection-edit-pending-${journal.operation}.json"

    private suspend fun recoverLocked(source: LocalSource) {
        val journals = work.listFiles().orEmpty().filter { (it.extension == "json" || it.name.endsWith(".json.bak")) && !it.name.contains("-before.json") }.map { it.name.removeSuffix(".bak").removeSuffix(".json") }.distinct()
        for (id in journals) {
            val journal = json.decodeFromString<Journal>(journalFile(id).readFully().toString(Charsets.UTF_8))
            if (journal.source != source.id) continue
            val root = root(source)
            check(root.uri.toString() == journal.root) { "Restore the original collection location to finish recovery." }
            if (committed(id)) {
                val output = checkNotNull(root.findFile(journal.output)) { "Edited collection output is missing" }
                // Keep a marker until the durable database commit has been observed.
                custom.get(journal.mangaId)?.let { custom.set(it.copy(title = journal.title, thumbnailUrl = null, genre = null)) }
                output.findFile(".collection-edit-owner.txt")?.let { check(it.delete()) }
                files.removeOwned(root.findFile(journal.stage), id)
            } else {
                val target = root.findFile(journal.output)
                if (target != null && files.owned(target, id)) {
                    files.rollbackOutput(target, id, journal.signature)
                }
                journal.originals.asReversed().forEach { original ->
                    root.findFile(original.backup)?.let { backup ->
                        check(root.findFile(original.name) == null) { "Recovery conflict: ${original.name}. Original files were preserved in ${original.backup}." }
                        files.checkedRename(backup, root, original.name)
                    }
                }
                files.removeOwned(root.findFile(journal.stage), id)
            }
            root.findFile(pending(journal))?.let { check(it.delete()) }
            journalFile(id).delete()
            source.refreshAfterFileEdit()
        }
    }

    private suspend fun editLocked(source: LocalSource, preview: Preview, title: String, titles: List<String>, merge: Boolean) {
        CollectionFileNames.validate(title)
        require(titles.size == preview.chapters.size)
        require(if (merge) preview.mangas.size >= 2 else preview.mangas.size == 1)
        val root = root(source)
        check(root.uri.toString() == preview.root) { "Collection location changed since preview; open the editor again." }
        preview.mangas.forEach { manga ->
            val current = source.getMangaUpdate(manga.toSManga(), emptyList(), false, true).chapters
            val expected = preview.chapters.filter { it.mangaId == manga.id }
            check(current.size == expected.size && current.all { chapter -> expected.any { it.url == chapter.url && it.title == chapter.name && it.number == chapter.chapter_number } }) { "Chapter files changed since preview; open the editor again." }
        }
        val ids = preview.mangas.map { it.id }
        val originalRows = handler.await { collectionFileEditsQueries.getRows(ids).executeAsList() }
        check(originalRows.size == ids.size && originalRows.all { row -> preview.mangas.any { it.id == row._id && it.source == row.source && it.url == row.url && it.version == row.version } }) { "Manga changed since preview; open the editor again." }
        check(handler.await { collectionFileEditsQueries.protectedReferences(ids).executeAsOne() } == 0L) { "Remove tracker/virtual-merge/advanced metadata links before changing these local files." }
        val originalChapters = handler.await { collectionFileEditsQueries.getChapters(ids).executeAsList() }
        check(originalChapters.all { row -> preview.chapters.any { it.mangaId == row.manga_id && it.url == row.url } }) { "Some stored chapters no longer exist on disk; refresh chapters before editing." }
        val plans = preview.chapters.mapIndexed { index, chapter ->
            val name = CollectionFileNames.validate(titles[index])
            val filename = if (merge) {
                CollectionFileNames.numberedChapter(index + 1, name, chapter.file, chapter.directory)
            } else if (name == chapter.title) {
                chapter.file
            } else {
                CollectionFileNames.chapterFile(name, chapter.file, chapter.directory)
            }
            Planned(chapter, filename, name, if (merge) (index + 1).toFloat() else chapter.number)
        }
        check(plans.map { it.file.lowercase() }.distinct().size == plans.size) { "Chapter file names must be different" }
        val originals = preview.mangas.map { manga ->
            CollectionFileNames.validate(manga.url)
            checkNotNull(root.findFile(manga.url)) { "Manga folder disappeared" }.also { check(it.isDirectory) }
        }
        val collisions = root.listFiles().orEmpty().filter { it.name.equals(title, true) }
        check(collisions.all { collision -> originals.any { it.uri == collision.uri } }) { "A different manga already uses that folder name" }
        val signatures = originals.map { files.tree(it) }
        val operation = UUID.randomUUID().toString()
        val stageName = ".collection-edit-stage-$operation"
        val primary = preview.mangas.first()
        var journal = Journal(
            operation,
            source.id,
            root.uri.toString(),
            stageName,
            title,
            preview.mangas.mapIndexed { index, manga -> Original(manga.url, ".collection-edit-backup-$operation-$index") },
            primary.id,
            title,
        )
        val allTags = preview.mangas.flatMap { manga -> tags.getTagsForManga(manga.id).map { it.name } + manga.genre.orEmpty() }.map(::canonicalLocalTagName).distinct()
        // Preserve complete native record snapshots alongside the durable operation journal.
        File(work, "$operation-before.json").writeText(json.encodeToString(MangaBackupCreator()(preview.mangas, BackupOptions())))
        writeJournal(journal)
        try {
            check(root.findFile(pending(journal)) == null)
            files.writeText(checkNotNull(root.createFile(pending(journal))), operation)
            val stage = checkNotNull(root.createDirectory(stageName)) { "Cannot stage collection files" }
            files.writeText(checkNotNull(stage.createFile(".collection-edit-owner.txt")), operation)
            val primaryChapterFiles = preview.chapters.filter { it.mangaId == primary.id }.map { it.file }.toSet()
            checkNotNull(originals.first().listFiles()).filter { it.name !in primaryChapterFiles && it.name !in setOf(LOCAL_CHAPTER_TITLES, ".collection-edit-owner.txt", ".noxml") }.forEach {
                files.copy(it, stage, checkNotNull(it.name))
            }
            plans.forEach { plan ->
                val original = originals[preview.mangas.indexOfFirst { it.id == plan.choice.mangaId }]
                files.copy(checkNotNull(original.findFile(plan.choice.file)), stage, plan.file)
            }
            val overrides = LocalChapterTitles(chapters = plans.mapIndexed { index, plan -> plan.file to LocalChapterTitle(plan.title, plan.number, index) }.toMap())
            require(json.encodeToString(overrides).toByteArray().size <= 4 * 1024 * 1024) { "Chapter title catalog is too large" }
            files.writeText(checkNotNull(stage.createFile(LOCAL_CHAPTER_TITLES)), json.encodeToString(overrides))
            // Persist Series/tags in root ComicInfo, keeping opaque (including encrypted) chapter archives intact.
            val metadata = primary.toSManga().apply {
                url = stageName
                this.title = title
                genre = allTags.joinToString(", ")
            }
            source.updateMangaInfo(metadata)
            check(stage.findFile("ComicInfo.xml") != null || stage.findFile(LocalSource.COMIC_INFO_ARCHIVE) != null) { "Cannot persist collection metadata" }
            journal = journal.copy(signature = files.tree(stage))
            writeJournal(journal)
            originals.forEachIndexed { index, original -> check(files.tree(original) == signatures[index]) { "Files changed during staging; no originals were replaced" } }
            check(root(source).uri == root.uri)
            journal.originals.forEach { original -> files.checkedRename(checkNotNull(root.findFile(original.name)), root, original.backup) }
            files.checkedRename(stage, root, title)
            handler.await(inTransaction = true) {
                check(collectionFileEditsQueries.getRows(ids).executeAsList() == originalRows && collectionFileEditsQueries.getChapters(ids).executeAsList() == originalChapters) { "Reading state changed during editing; original files will be restored" }
                val notes = preview.mangas.map { it.notes }.filter { it.isNotBlank() }.distinct().joinToString("\n\n")
                collectionFileEditsQueries.relocateManga(title, title, preview.mangas.any { it.favorite }, notes, allTags, System.currentTimeMillis(), primary.id, source.id)
                plans.forEachIndexed { index, plan ->
                    val old = originalChapters.filter { it.manga_id == plan.choice.mangaId && it.url == plan.choice.url }
                    val url = "$title/${plan.file}"
                    if (old.isEmpty()) {
                        collectionFileEditsQueries.insertChapter(primary.id, url, plan.title, plan.number.toDouble(), (plans.lastIndex - index).toLong(), System.currentTimeMillis(), 0L)
                    } else {
                        old.forEach { collectionFileEditsQueries.relocateChapter(primary.id, url, plan.title, plan.number.toDouble(), (plans.lastIndex - index).toLong(), it._id) }
                    }
                }
                preview.mangas.drop(1).forEach { manga ->
                    collectionFileEditsQueries.copyTags(primary.id, manga.id)
                    collectionFileEditsQueries.copyCategories(primary.id, manga.id)
                    collectionFileEditsQueries.removeMergedManga(manga.id, source.id)
                }
                tags.addAll(allTags, source.id)
                tags.replaceMangaTags(primary.id, allTags)
                collectionBackupCommitsQueries.markCommitted(operation, source.id)
            }
            recoverLocked(source)
        } catch (error: Throwable) {
            try {
                recoverLocked(source)
            } catch (recovery: Throwable) {
                error.addSuppressed(recovery)
            }
            throw error
        } finally {
            source.refreshAfterFileEdit()
        }
    }

    companion object {
        @Volatile private var instance: CollectionFileEditor? = null
        fun get(context: Context): CollectionFileEditor = instance ?: synchronized(this) {
            instance ?: CollectionFileEditor(context.applicationContext).also { instance = it }
        }
    }
}
