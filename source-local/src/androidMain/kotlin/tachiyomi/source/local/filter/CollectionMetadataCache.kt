package tachiyomi.source.local.filter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-owned catalog: dialog cancellation must not cancel startup discovery. */
internal class CollectionMetadataCache(
    private val scope: CoroutineScope,
    tagUpdates: Flow<List<String>>,
    private val collectionKey: () -> String,
    private val scanAuthors: suspend () -> List<String>,
    private val onError: (Throwable) -> Unit,
    private val mutex: Mutex = Mutex(),
) {
    private var loadedKey: String? = null
    private val mutableAuthors = MutableStateFlow<List<String>>(emptyList())

    // Eager sharing keeps one database subscription, even with every dialog closed.
    val tags = tagUpdates
        .retryWhen { cause, _ ->
            onError(cause)
            delay(1000)
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val authors = mutableAuthors.asStateFlow()

    fun preload(force: Boolean = false) {
        scope.launch {
            try {
                ensureLoaded(force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave loadedKey unchanged so opening the filter can retry.
                onError(e)
            }
        }
    }

    suspend fun ensureLoaded(force: Boolean = false) {
        mutex.withLock {
            val key = collectionKey()
            if (!force && loadedKey == key) return
            mutableAuthors.value = scanAuthors()
            loadedKey = key
        }
    }

    /** Serialize metadata edits with discovery so an older scan cannot overwrite them. */
    suspend fun invalidate(rescan: Boolean = true, update: suspend () -> Unit) {
        mutex.withLock {
            update()
            loadedKey = if (rescan) null else collectionKey()
        }
        if (rescan) preload()
    }
}
