package tachiyomi.data.localtag

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.model.CollectionTagChanges
import tachiyomi.domain.localtag.repository.LocalMangaUsageRepository

class LocalMangaUsageRepositoryImpl(private val handler: DatabaseHandler) : LocalMangaUsageRepository {
    override suspend fun recordOpen(mangaId: Long) {
        val source = handler.await(inTransaction = true) {
            val source = localMangaTagsQueries.getFileMangaSource(mangaId).executeAsOneOrNull() ?: return@await null
            localMangaUsageQueries.increment(mangaId)
            source
        }
        if (source != null) CollectionTagChanges.markDirty(source)
    }

    override suspend fun counts(source: Long): Map<String, Long> = handler.await {
        localMangaUsageQueries.getCounts(source).executeAsList().associate { it.url to it.open_count }
    }
}
