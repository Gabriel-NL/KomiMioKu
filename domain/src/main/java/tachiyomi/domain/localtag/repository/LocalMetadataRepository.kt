package tachiyomi.domain.localtag.repository

import tachiyomi.domain.localtag.model.LocalChapterMetadata
import tachiyomi.domain.localtag.model.LocalDatabaseMetadata
import tachiyomi.domain.localtag.model.LocalMangaMetadata
import tachiyomi.domain.localtag.model.LocalMetadataDocument

interface LocalMetadataRepository {
    suspend fun read(source: Long, url: String): LocalDatabaseMetadata?
    suspend fun importLegacy(source: Long, url: String, fields: LocalMangaMetadata, chapters: List<LocalChapterMetadata>, documents: List<LocalMetadataDocument>, preferred: LocalMangaMetadata? = null): LocalDatabaseMetadata
    suspend fun discoverChapters(source: Long, url: String, chapters: List<LocalChapterMetadata>)
    suspend fun save(source: Long, url: String, fields: LocalMangaMetadata)
    suspend fun documents(source: Long): List<LocalMetadataDocument>
    suspend fun restoreDocuments(source: Long, documents: List<LocalMetadataDocument>)
    suspend fun relocateDocuments(source: Long, urls: List<String>, target: String)
    suspend fun resetImport(source: Long)
}
