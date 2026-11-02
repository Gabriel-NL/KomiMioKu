package eu.kanade.tachiyomi.data.backup.collection

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** One process-owned operation; leaving settings or rotating must not interrupt file replacement. */
class CollectionBackupJobs private constructor(context: Context) {
    data class State(
        val busy: Boolean = false,
        val phase: String = "",
        val count: Int = 0,
        val total: Int = 0,
        val prepared: CollectionBackupService.Prepared? = null,
        val outcome: String? = null,
        val detail: String? = null,
        val recovery: Boolean = false,
    )

    private val service = CollectionBackupService(context.applicationContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    private fun run(phase: String, block: suspend () -> Unit) {
        if (!mutex.tryLock()) return
        mutable.update { it.copy(busy = true, phase = phase, count = 0, total = 0, outcome = null, detail = null) }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value.prepared?.let { plan ->
                    if (plan.directory.exists() && !java.io.File(plan.directory, "journal.json").exists() && !java.io.File(plan.directory, "journal.json.bak").exists()) runCatching { service.discard(plan) }
                }
                mutable.update { it.copy(prepared = null, outcome = "error", detail = e.localizedMessage) }
            } finally {
                mutable.update { it.copy(busy = false, recovery = service.hasRecovery()) }
                mutex.unlock()
            }
        }
    }

    private fun progress(count: Int, total: Int) {
        mutable.update { it.copy(count = count, total = total) }
    }

    fun checkRecovery() {
        if (!mutable.value.busy) scope.launch { mutable.update { it.copy(recovery = service.hasRecovery()) } }
    }

    fun create(source: Long, uri: Uri) {
        if (mutable.value.prepared != null) return
        run("create") {
            service.cleanUnusedPreparations()
            service.create(source, uri, ::progress)
            mutable.update { it.copy(outcome = "created") }
        }
    }

    fun prepare(source: Long, uri: Uri) {
        if (mutable.value.prepared != null) return
        run("prepare") {
            service.cleanUnusedPreparations()
            val plan = service.prepare(source, uri, ::progress)
            mutable.update { it.copy(prepared = plan) }
        }
    }

    fun apply() {
        val plan = mutable.value.prepared ?: return
        run("restore") {
            service.apply(plan, ::progress)
            mutable.update { it.copy(prepared = null, outcome = "restored") }
        }
    }

    fun discard() {
        val plan = mutable.value.prepared ?: return
        run("discard") {
            service.discard(plan)
            mutable.update { it.copy(prepared = null) }
        }
    }

    fun recover() {
        if (mutable.value.prepared == null) {
            run("recover") {
                service.recover(::progress)
                mutable.update { it.copy(outcome = "recovered") }
            }
        }
    }
    fun clearMessage() {
        mutable.update { it.copy(outcome = null, detail = null) }
    }

    companion object {
        @Volatile private var instance: CollectionBackupJobs? = null
        fun get(context: Context): CollectionBackupJobs = instance ?: synchronized(this) {
            instance ?: CollectionBackupJobs(context.applicationContext).also { instance = it }
        }
    }
}
