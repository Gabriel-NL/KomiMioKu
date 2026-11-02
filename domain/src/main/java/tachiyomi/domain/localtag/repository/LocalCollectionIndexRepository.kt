package tachiyomi.domain.localtag.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.localtag.model.IndexedLocalManga
import tachiyomi.domain.localtag.model.LocalCollectionSnapshot

interface LocalCollectionIndexRepository {
    suspend fun load(source: Long, root: String): LocalCollectionSnapshot
    fun observe(source: Long, root: String): Flow<LocalCollectionSnapshot?>
    suspend fun put(entry: IndexedLocalManga)
    suspend fun finishScan(source: Long, root: String, changed: List<IndexedLocalManga>, seen: Set<String>): Boolean
    suspend fun readingStates(source: Long): Map<String, String>
}
