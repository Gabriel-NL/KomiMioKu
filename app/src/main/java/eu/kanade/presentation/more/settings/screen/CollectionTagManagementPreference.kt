package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.local.CollectionTagTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.localtag.service.CollectionWorkbook
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** The selected collection is explicit for every import/export; files never choose a destination. */
@Composable
fun collectionTagManagementPreference(): Preference.PreferenceItem.TextPreference {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { Injekt.get<SourceManager>() }
    val transfer = remember { CollectionTagTransfer(Injekt.get<DatabaseHandler>(), Injekt.get<LocalTagRepository>(), sources) }
    var choosing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf<CollectionTagTransfer.Snapshot?>(null) }
    var legacy by remember { mutableStateOf<CollectionTagTransfer.LegacyPlan?>(null) }
    var importTarget by remember { mutableStateOf<Long?>(null) }
    val success = stringResource(KMR.strings.collection_tags_success)
    val failure = stringResource(KMR.strings.collection_tags_error)
    val close = stringResource(KMR.strings.reconcile_close)

    fun work(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "$failure\n${e.localizedMessage.orEmpty()}"
            } finally {
                busy = false
            }
        }
    }

    suspend fun writeVerified(uri: android.net.Uri, writer: (OutputStream) -> Unit) = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
            DigestOutputStream(output, digest).use { writer(it) }
        }
        val expected = digest.digest()
        val actual = MessageDigest.getInstance("SHA-256")
        checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                actual.update(buffer, 0, count)
            }
        }
        check(expected.contentEquals(actual.digest())) { "Export verification failed" }
    }

    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = export
        export = null
        if (uri != null && snapshot != null) {
            work {
                writeVerified(uri) { it.write(snapshot.catalogJson().toByteArray(Charsets.UTF_8)) }
                message = success
            }
        }
    }
    val saveExcel = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val snapshot = export
        export = null
        if (uri != null && snapshot != null) {
            work {
                writeVerified(uri) { CollectionWorkbook.write(it, snapshot.sheets()) }
                message = success
            }
        }
    }
    val saveLegacy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        val plan = legacy
        if (uri != null && plan != null) {
            work {
                writeVerified(uri) { CollectionWorkbook.write(it, plan.sheets()) }
                withContext(Dispatchers.IO) { transfer.applyLegacy(plan) }
                legacy = null
                message = success
            }
        }
    }
    val loadJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val destination = importTarget
        importTarget = null
        if (uri != null && destination != null) {
            work {
                withContext(Dispatchers.IO) {
                    transfer.importCatalog(destination, checkNotNull(context.contentResolver.openInputStream(uri)))
                }
                message = success
            }
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(KMR.strings.reconcile_target)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    sources.getAll().filterIsInstance<LocalSource>().sortedBy { it.name }.forEach { source ->
                        TextButton(onClick = {
                            selected = source.id
                            choosing = false
                        }) { Text(source.name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosing = false }) { Text(close) } },
        )
    }
    val destination = selected
    if (destination != null) {
        val source = sources.get(destination)
        AlertDialog(
            onDismissRequest = { if (!busy) selected = null },
            title = { Text(source?.name.orEmpty()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(KMR.strings.collection_tags_isolation))
                    TextButton(enabled = !busy, onClick = {
                        importTarget = destination
                        loadJson.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }) { Text(stringResource(KMR.strings.collection_tags_import)) }
                    TextButton(enabled = !busy, onClick = {
                        work {
                            export = withContext(Dispatchers.IO) { transfer.snapshot(destination) }
                            saveJson.launch("collection-tags-${System.currentTimeMillis()}.json")
                        }
                    }) { Text(stringResource(KMR.strings.collection_tags_export)) }
                    TextButton(enabled = !busy, onClick = {
                        work {
                            export = withContext(Dispatchers.IO) { transfer.snapshot(destination) }
                            saveExcel.launch("collection-database-${System.currentTimeMillis()}.xlsx")
                        }
                    }) { Text(stringResource(KMR.strings.collection_tags_excel)) }
                    if (source?.name.equals("NSFW", ignoreCase = true)) {
                        TextButton(enabled = !busy, onClick = {
                            work {
                                legacy = withContext(Dispatchers.IO) { transfer.previewLegacy(destination) }
                                selected = null
                            }
                        }) { Text(stringResource(KMR.strings.collection_tags_legacy)) }
                    }
                }
            },
            confirmButton = { TextButton(enabled = !busy, onClick = { selected = null }) { Text(close) } },
        )
    }
    val plan = legacy
    if (plan != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) legacy = null },
            title = { Text(stringResource(KMR.strings.collection_tags_legacy)) },
            text = {
                Text(stringResource(KMR.strings.collection_tags_legacy_warning, plan.tagCount, plan.linkCount))
            },
            confirmButton = {
                TextButton(enabled = !busy && (plan.tagCount > 0 || plan.linkCount > 0), onClick = {
                    saveLegacy.launch("legacy-tags-backup-${System.currentTimeMillis()}.xlsx")
                }) { Text(stringResource(KMR.strings.collection_tags_clean)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { legacy = null }) { Text(close) } },
        )
    }
    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(close) } },
        )
    }
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(KMR.strings.collection_tags_manage),
        subtitle = stringResource(if (busy) KMR.strings.reconcile_busy else KMR.strings.collection_tags_description),
        enabled = !busy,
        onClick = { choosing = true },
    )
}
