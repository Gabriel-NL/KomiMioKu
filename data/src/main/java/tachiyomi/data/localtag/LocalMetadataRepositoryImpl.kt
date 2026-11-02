package tachiyomi.data.localtag

import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.model.LocalChapterMetadata
import tachiyomi.domain.localtag.model.LocalDatabaseMetadata
import tachiyomi.domain.localtag.model.LocalMangaMetadata
import tachiyomi.domain.localtag.model.LocalMangaUsage
import tachiyomi.domain.localtag.model.LocalMetadataDocument
import tachiyomi.domain.localtag.model.mergeLegacyLocalMetadata
import tachiyomi.domain.localtag.repository.LocalMetadataRepository
import tachiyomi.domain.localtag.repository.LocalTagRepository

class LocalMetadataRepositoryImpl(private val handler: DatabaseHandler, private val tags: LocalTagRepository) : LocalMetadataRepository {
    private suspend fun Database.readStored(source: Long, url: String): LocalDatabaseMetadata? {
        val row = localMetadataQueries.readManga(source, url).executeAsOneOrNull() ?: return null
        val names = tags.getTagsForManga(row._id).map { it.name }
        val imported = localMetadataQueries.isImported(row._id).executeAsOne() != 0L
        return LocalDatabaseMetadata(
            row._id,
            LocalMangaMetadata(row.title, row.author, row.artist, row.description, if (imported) names else names.ifEmpty { row.genre.orEmpty() }, row.status, row.thumbnail_url),
            localMetadataQueries.readChapters(row._id).executeAsList().map { LocalChapterMetadata(it.url, it.name, it.chapter_number, it.source_order, it.scanlator, it.date_upload) },
            imported,
        )
    }
    override suspend fun read(source: Long, url: String) = handler.await(inTransaction = true) { readStored(source, url) }

    override suspend fun importLegacy(source: Long, url: String, fields: LocalMangaMetadata, chapters: List<LocalChapterMetadata>, documents: List<LocalMetadataDocument>, preferred: LocalMangaMetadata?): LocalDatabaseMetadata = handler.await(inTransaction = true) {
        localMangaTagsQueries.ensureLocalManga(source, url, fields.title)
        val current = checkNotNull(readStored(source, url))
        if (current.imported) return@await current
        val row = localMetadataQueries.readManga(source, url).executeAsOne()
        val merged = preferred ?: mergeLegacyLocalMetadata(current.fields, fields, row.initialized)
        localMetadataQueries.saveFields(merged.title, merged.author, merged.artist, merged.description, merged.tags, merged.status, merged.thumbnail, current.id)
        tags.replaceMangaTags(current.id, merged.tags)
        val known = current.chapters.map { it.url }.toSet()
        val added = chapters.filterNot { it.url in known }
        if (added.isNotEmpty()) localExportReviewQueries.revoke(current.id)
        added.forEach { chapter ->
            localMetadataQueries.insertChapter(current.id, chapter.url, chapter.name, chapter.scanlator, chapter.number, chapter.order, System.currentTimeMillis(), chapter.uploaded)
        }
        documents.forEach { localMetadataQueries.putDocument(source, url, it.path, it.format, it.content) }
        localMetadataQueries.markImported(current.id)
        checkNotNull(readStored(source, url))
    }
    override suspend fun discoverChapters(source: Long, url: String, chapters: List<LocalChapterMetadata>) {
        handler.await(inTransaction = true) {
            val stored = checkNotNull(readStored(source, url))
            val known = stored.chapters.map { it.url }.toSet()
            val added = chapters.filterNot { it.url in known }
            if (added.isNotEmpty()) localExportReviewQueries.revoke(stored.id)
            added.forEach { chapter ->
                localMetadataQueries.insertChapter(stored.id, chapter.url, chapter.name, chapter.scanlator, chapter.number, chapter.order, System.currentTimeMillis(), chapter.uploaded)
            }
        }
    }

    override suspend fun save(source: Long, url: String, fields: LocalMangaMetadata) {
        handler.await(inTransaction = true) {
            val current = checkNotNull(readStored(source, url)) { "Manga is not registered" }
            localMetadataQueries.saveFields(fields.title, fields.author, fields.artist, fields.description, fields.tags, fields.status, fields.thumbnail, current.id)
            tags.replaceMangaTags(current.id, fields.tags)
            localMetadataQueries.markImported(current.id)
        }
    }
    override suspend fun documents(source: Long) = handler.await(inTransaction = true) {
        val documents = localMetadataQueries.getDocuments(source, ::LocalMetadataDocument).executeAsList().filterNot { it.path == LocalMangaUsage.BACKUP_PATH }
        documents + localMangaUsageQueries.getCounts(source).executeAsList().map { LocalMangaUsage.document(it.url, it.open_count) }
    }
    override suspend fun restoreDocuments(source: Long, documents: List<LocalMetadataDocument>) {
        handler.await(inTransaction = true) {
            documents.forEach {
                if (it.path == LocalMangaUsage.BACKUP_PATH) {
                    localMangaUsageQueries.restore(LocalMangaUsage.decode(it), source, it.manga)
                } else {
                    localMetadataQueries.putDocument(source, it.manga, it.path, it.format, it.content)
                }
            }
        }
    }
    override suspend fun relocateDocuments(source: Long, urls: List<String>, target: String) {
        handler.await(inTransaction = true) {
            val documents = localMetadataQueries.getDocuments(source, ::LocalMetadataDocument).executeAsList().filter { it.manga in urls }
            localMetadataQueries.clearDocuments(source, urls)
            documents.forEach { localMetadataQueries.putDocument(source, target, "${it.manga}/${it.path}", it.format, it.content) }
        }
    }
    override suspend fun resetImport(source: Long) {
        handler.await { localMetadataQueries.resetImport(source) }
    }
}
