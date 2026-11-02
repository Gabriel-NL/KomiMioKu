package eu.kanade.presentation.more.settings.screen

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.backup.collection.CollectionBackupJobs
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreJob
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.localtag.repository.CollectionExportApproval
import tachiyomi.domain.localtag.repository.ExportReviewRepository
import tachiyomi.domain.localtag.repository.parseCollectionApprovalImport
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun collectionBackupPreferences(defaultSource: Long? = null): List<Preference.PreferenceItem<out Any, out Any>> {
    val context = LocalContext.current
    val manager = remember { Injekt.get<SourceManager>() }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var pendingReview by rememberSaveable { mutableStateOf<Long?>(null) }
    val importedLabel = stringResource(KMR.strings.export_review_import_done)
    val mismatchLabel = stringResource(KMR.strings.export_review_collection_mismatch)
    val jobs = remember { CollectionBackupJobs.get(context) }
    val state by jobs.state.collectAsState()
    val available by manager.sources.collectAsState(initial = manager.getAll())
    val collections = available.filterIsInstance<LocalSource>().filter { FileSourceIds.contains(it.id) && it.id != FileSourceIds.LOCAL && it.id != FileSourceIds.PRIVATE_COLLECTION }.sortedBy { it.name }
    // Intentionally not a preference or rememberSaveable: each settings session starts empty.
    var selected by remember { mutableStateOf(defaultSource?.toString()) }
    var pendingCreate by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingRestore by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { jobs.checkRecovery() }
    LaunchedEffect(collections.map { it.id }) { if (collections.none { it.id.toString() == selected }) selected = null }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingCreate
        pendingCreate = null
        if (uri != null && id != null) jobs.create(id, uri)
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingRestore
        pendingRestore = null
        if (uri != null && id != null) jobs.prepare(id, uri)
    }
    val importReviews = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pendingReview
        pendingReview = null
        if (uri != null && target != null) {
            importing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO + NonCancellable) {
                        val source = manager.get(target) as? LocalSource ?: error("Collection unavailable")
                        val raw = checkNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                            val bytes = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = stream.read(buffer)
                                if (count < 0) break
                                check(bytes.size() + count <= 1024 * 1024) { "Approval import exceeds 1 MiB" }
                                bytes.write(buffer, 0, count)
                            }
                            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
                        }
                        val data = parseCollectionApprovalImport(raw)
                        check(data.collection.equals(source.name, ignoreCase = true)) { mismatchLabel }
                        source.withExclusiveCollectionAccess {
                            source.prepareDatabaseMetadata()
                            Injekt.get<ExportReviewRepository>().importApprovals(target, data.approvedPaths.map { CollectionExportApproval(it, true) })
                        }
                    }
                    importMessage = importedLabel
                } catch (e: Exception) {
                    importMessage = e.message ?: e.javaClass.simpleName
                } finally {
                    importing = false
                }
            }
        }
    }
    importMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { importMessage = null },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importMessage = null }) { Text(stringResource(KMR.strings.reconcile_close)) } },
        )
    }
    val enabled = selected != null && !importing && !state.busy && state.prepared == null && !state.recovery
    val close = stringResource(KMR.strings.reconcile_close)
    val plan = state.prepared
    if (plan != null && !state.busy) {
        AlertDialog(
            onDismissRequest = jobs::discard,
            title = { Text(stringResource(KMR.strings.collection_backup_preview)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            KMR.strings.collection_backup_summary,
                            plan.manifest.collection,
                            manager.getOrStub(plan.source).name,
                            plan.manifest.assets.size,
                            Formatter.formatFileSize(context, plan.manifest.assets.sumOf { it.size }),
                            plan.conflicts.size,
                        ),
                    )
                    Text(stringResource(KMR.strings.collection_backup_merge_policy))
                    LazyColumn(Modifier.heightIn(max = 240.dp)) { items(plan.conflicts) { Text(it) } }
                }
            },
            confirmButton = { TextButton(onClick = jobs::apply) { Text(stringResource(KMR.strings.collection_backup_confirm_restore)) } },
            dismissButton = { TextButton(onClick = jobs::discard) { Text(close) } },
        )
    }
    if (state.outcome != null) {
        val result = when (state.outcome) {
            "verified" -> KMR.strings.manager_verified
            "created" -> KMR.strings.collection_backup_created
            "restored" -> KMR.strings.collection_backup_restored
            "recovered" -> KMR.strings.collection_backup_recovered
            else -> KMR.strings.collection_backup_error
        }
        AlertDialog(
            onDismissRequest = jobs::clearMessage,
            text = { Text(stringResource(result) + state.detail?.let { "\n$it" }.orEmpty()) },
            confirmButton = { TextButton(onClick = jobs::clearMessage) { Text(close) } },
        )
    }
    return persistentListOf(
        Preference.PreferenceItem.BasicListPreference(
            value = selected.orEmpty(),
            entries = persistentMapOf("" to stringResource(KMR.strings.collection_backup_select)).puttingAll(collections.associate { it.id.toString() to it.name }),
            title = stringResource(KMR.strings.collection_backup_collection),
            enabled = !importing && !state.busy && state.prepared == null,
            onValueChanged = {
                selected = it.takeIf(String::isNotEmpty)
            },
        ),
        Preference.PreferenceItem.TextPreference(
            title = stringResource(KMR.strings.export_review_import),
            enabled = enabled,
            onClick = {
                pendingReview = selected?.toLongOrNull()
                importReviews.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            },
        ),
        Preference.PreferenceItem.CustomPreference(title = stringResource(KMR.strings.collection_backup_actions)) {
            MultiChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                SegmentedButton(checked = false, enabled = enabled, modifier = Modifier.fillMaxHeight(), shape = SegmentedButtonDefaults.itemShape(0, 2), onCheckedChange = {
                    selected?.toLongOrNull()?.let {
                        pendingCreate = it
                        create.launch("collection-${System.currentTimeMillis()}.zip")
                    }
                }) { Text(stringResource(KMR.strings.collection_backup_create)) }
                SegmentedButton(checked = false, enabled = enabled, modifier = Modifier.fillMaxHeight(), shape = SegmentedButtonDefaults.itemShape(1, 2), onCheckedChange = {
                    if (!BackupRestoreJob.isRunning(context)) {
                        selected?.toLongOrNull()?.let {
                            pendingRestore = it
                            restore.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed"))
                        }
                    }
                }) { Text(stringResource(KMR.strings.collection_backup_restore)) }
            }
        },
        Preference.PreferenceItem.CustomPreference(title = stringResource(KMR.strings.collection_backup_status)) {
            if (state.busy) {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(KMR.strings.collection_backup_progress, state.count, state.total))
                }
            }
        },
        Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.collection_backup_description)),
    ) + if (state.recovery) {
        listOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.collection_backup_recover),
                subtitle = stringResource(KMR.strings.collection_backup_recovery_info),
                enabled = !state.busy,
                onClick = jobs::recover,
            ),
        )
    } else {
        emptyList()
    }
}
