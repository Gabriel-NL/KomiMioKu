package tachiyomi.domain.localtag.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Process-local revisions survive screen navigation and source-instance recreation. */
object CollectionTagChanges {
    private val revisions = MutableStateFlow<Map<Long, Long>>(emptyMap())

    fun current(source: Long): Long = revisions.value[source] ?: 0L

    fun observe(source: Long) = revisions.map { it[source] ?: 0L }.distinctUntilChanged()

    fun markDirty(source: Long) {
        revisions.update { it + (source to ((it[source] ?: 0L) + 1L)) }
    }
}

/** Every open catalog consumes revisions independently; refreshing one cannot clean another. */
class CollectionTagRefreshCursor(initial: Long) {
    private var displayed = initial

    fun consume(revision: Long): Boolean {
        if (revision == displayed) return false
        displayed = revision
        return true
    }
}
