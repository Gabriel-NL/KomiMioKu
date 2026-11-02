package tachiyomi.domain.localtag.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.localtag.model.LocalTag

interface LocalTagRepository {
    suspend fun ensureCollection(sourceId: Long)

    suspend fun getAll(sourceId: Long = 0L): List<LocalTag>

    fun observeAll(sourceId: Long = 0L): Flow<List<LocalTag>>

    /** Add known tags without replacing existing rows or changing their IDs. */
    suspend fun addAll(names: List<String>, sourceId: Long = 0L)

    /** Register a file-source title without changing existing library/progress data.
     * Null tags preserve existing links when metadata could not be read; empty tags clear them.
     */
    suspend fun syncLocalManga(url: String, title: String, tags: List<String>?, sourceId: Long = 0L)

    suspend fun replaceMangaTags(mangaId: Long, tags: List<String>)

    suspend fun getTagsForManga(mangaId: Long): List<LocalTag>
}
