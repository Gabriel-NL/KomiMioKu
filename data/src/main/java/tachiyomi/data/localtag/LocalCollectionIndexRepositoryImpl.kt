package tachiyomi.data.localtag

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.model.IndexedLocalManga
import tachiyomi.domain.localtag.model.LocalCollectionSnapshot
import tachiyomi.domain.localtag.repository.LocalCollectionIndexRepository

/** The singleton cache is shared by all source instances and never stores images. */
class LocalCollectionIndexRepositoryImpl(private val handler: DatabaseHandler) : LocalCollectionIndexRepository {
    private val mutex = Mutex()
    private val snapshots = MutableStateFlow<Map<Pair<Long, String>, LocalCollectionSnapshot>>(emptyMap())

    override suspend fun invalidate(source: Long) = mutex.withLock {
        snapshots.update { entries -> entries.filterKeys { it.first != source } }
    }

    override suspend fun load(source: Long, root: String): LocalCollectionSnapshot = mutex.withLock { loadLocked(source, root) }

    private suspend fun loadLocked(source: Long, root: String): LocalCollectionSnapshot {
        snapshots.value[source to root]?.let { return it }
        val snapshot = handler.await(inTransaction = true) {
            val records = localMetadataQueries.catalogMangas(source).executeAsList().groupBy { it.url }.mapValues { it.value.first() }
            val entries = localCollectionIndexQueries.getEntries(source, root, ::IndexedLocalManga).executeAsList().map { entry ->
                val row = records[entry.url]
                if (row == null || !row.initialized) {
                    entry
                } else {
                    entry.copy(
                        title = row.title,
                        author = row.author,
                        artist = row.artist,
                        description = row.description,
                        genre = row.genre.orEmpty().joinToString(", "),
                        status = row.status,
                        thumbnail = row.thumbnail_url ?: entry.thumbnail,
                    )
                }
            }
            LocalCollectionSnapshot(
                entries,
                localCollectionIndexQueries.isReady(source, root).executeAsOne() > 0,
            )
        }
        snapshots.update { it + ((source to root) to snapshot) }
        return snapshot
    }

    override fun observe(source: Long, root: String) = snapshots.map { it[source to root] }.distinctUntilChanged()

    private fun Database.write(entry: IndexedLocalManga) {
        localCollectionIndexQueries.putEntry(
            entry.sourceId, entry.root, entry.url, entry.title, entry.author, entry.artist,
            entry.description, entry.genre, entry.status, entry.thumbnail, entry.modified, entry.fingerprint,
        )
    }

    override suspend fun put(entry: IndexedLocalManga) = mutex.withLock {
        val old = loadLocked(entry.sourceId, entry.root)
        handler.await { write(entry) }
        val entries = old.entries.associateBy { it.url }.toMutableMap().apply { put(entry.url, entry) }.values.toList()
        snapshots.update { it + ((entry.sourceId to entry.root) to old.copy(entries = entries)) }
    }

    override suspend fun finishScan(source: Long, root: String, changed: List<IndexedLocalManga>, seen: Set<String>): Boolean = mutex.withLock {
        val old = loadLocked(source, root)
        handler.await(inTransaction = true) {
            changed.forEach { write(it) }
            old.entries.filterNot { it.url in seen }.forEach { localCollectionIndexQueries.removeEntry(source, root, it.url) }
            localCollectionIndexQueries.markReady(source, root)
        }
        val entries = old.entries.filter { it.url in seen }.associateBy { it.url }.toMutableMap()
        changed.forEach { entries[it.url] = it }
        val next = LocalCollectionSnapshot(entries.values.sortedBy { it.url }, true)
        snapshots.update { it + ((source to root) to next) }
        old != next
    }

    override suspend fun readingStates(source: Long): Map<String, String> = handler.awaitList {
        localCollectionIndexQueries.readingStates(source) { url, state -> url to state }
    }.toMap()
}
