package tachiyomi.source.local.filter

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class CollectionMetadataCacheTest {
    @Test
    fun `database tags stay hot without dialog subscribers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val database = MutableStateFlow(listOf("Cached"))
            val cache = CollectionMetadataCache(scope, database, { "root" }, { emptyList() }, { throw it })
            assertEquals(listOf("Cached"), cache.tags.value)
            // A metadata edit while every filter is closed must still reach the cache.
            database.value = listOf("Cached", "New tag")
            assertEquals(database.value, cache.tags.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `closing a dialog does not cancel preload and reopen does not rescan`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var scans = 0
            val release = CompletableDeferred<Unit>()
            val cache = CollectionMetadataCache(
                scope,
                MutableStateFlow(listOf("Cached")),
                { "root" },
                {
                    scans++
                    release.await()
                    listOf("Author")
                },
                { throw it },
            )
            cache.preload()
            val dialog = launch(Dispatchers.Unconfined) { cache.ensureLoaded() }
            dialog.cancel()
            assertEquals(1, scans)
            release.complete(Unit)
            cache.ensureLoaded()
            cache.ensureLoaded()
            assertEquals(1, scans)
            assertEquals(listOf("Author"), cache.authors.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `changing collection rescans but ordinary requests reuse memory`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var key = "first"
            var scans = 0
            val cache = CollectionMetadataCache(scope, MutableStateFlow(emptyList()), { key }, {
                scans++
                listOf(key)
            }, { throw it })
            cache.ensureLoaded()
            cache.ensureLoaded()
            key = "second"
            cache.ensureLoaded()
            assertEquals(2, scans)
            assertEquals(listOf("second"), cache.authors.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `metadata edit waits for an older scan then refreshes catalog`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            var scans = 0
            val cache = CollectionMetadataCache(
                scope,
                MutableStateFlow(emptyList()),
                { "root" },
                {
                    if (++scans == 1) release.await()
                    order += "scan"
                    listOf("Author $scans")
                },
                { throw it },
            )
            cache.preload()
            val edit = launch(Dispatchers.Unconfined) { cache.invalidate { order += "edit" } }
            assertFalse(edit.isCompleted)
            release.complete(Unit)
            edit.join()
            cache.ensureLoaded()
            assertEquals(listOf("scan", "edit", "scan"), order)
            assertEquals(listOf("Author 2"), cache.authors.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed startup scan preserves database tags and can be retried`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var attempts = 0
            val errors = mutableListOf<Throwable>()
            val cache = CollectionMetadataCache(
                scope,
                MutableStateFlow(listOf("Cached")),
                { "root" },
                {
                    if (++attempts == 1) error("Storage unavailable")
                    listOf("Author")
                },
                errors::add,
            )
            cache.preload()
            assertEquals(1, errors.size)
            assertEquals(listOf("Cached"), cache.tags.value)
            cache.ensureLoaded()
            assertEquals(2, attempts)
            assertEquals(listOf("Author"), cache.authors.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `indexed edit updates the key without rescanning the collection`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var scans = 0
            var key = "before"
            val cache = CollectionMetadataCache(scope, MutableStateFlow(emptyList()), { key }, {
                scans++
                emptyList()
            }, { throw it })
            cache.ensureLoaded()
            cache.invalidate(rescan = false) { key = "after" }
            cache.ensureLoaded()
            assertEquals(1, scans)
            cache.ensureLoaded(force = true)
            assertEquals(2, scans)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `recreated source instances share scan and edit serialization`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val mutex = kotlinx.coroutines.sync.Mutex()
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val first = CollectionMetadataCache(scope, MutableStateFlow(emptyList()), { "root" }, {
                release.await()
                order += "scan"
                emptyList()
            }, { throw it }, mutex)
            val second = CollectionMetadataCache(scope, MutableStateFlow(emptyList()), { "root" }, { emptyList() }, { throw it }, mutex)
            first.preload()
            val edit = launch(Dispatchers.Unconfined) { second.invalidate(rescan = false) { order += "edit" } }
            assertEquals(emptyList<String>(), order)
            release.complete(Unit)
            edit.join()
            assertEquals(listOf("scan", "edit"), order)
        } finally {
            scope.cancel()
        }
    }
}
