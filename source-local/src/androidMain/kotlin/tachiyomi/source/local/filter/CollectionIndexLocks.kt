package tachiyomi.source.local.filter

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** Recreated source instances must serialize scans and edits against the same files. */
internal object CollectionIndexLocks {
    private val locks = ConcurrentHashMap<Long, Mutex>()
    fun forSource(source: Long): Mutex = locks.computeIfAbsent(source) { Mutex() }
}
