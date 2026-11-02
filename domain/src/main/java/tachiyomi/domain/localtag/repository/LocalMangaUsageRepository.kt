package tachiyomi.domain.localtag.repository

interface LocalMangaUsageRepository {
    suspend fun recordOpen(mangaId: Long)
    suspend fun counts(source: Long): Map<String, Long>
}
