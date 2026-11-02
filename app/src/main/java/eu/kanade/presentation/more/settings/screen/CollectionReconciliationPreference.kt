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
import eu.kanade.tachiyomi.data.local.CollectionReconciler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun collectionReconciliationPreference(): Preference.PreferenceItem.TextPreference {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources = remember { Injekt.get<SourceManager>() }
    val reconciler = remember { CollectionReconciler(Injekt.get<DatabaseHandler>(), sources) }
    var selecting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var plan by remember { mutableStateOf<CollectionReconciler.Plan?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val done = stringResource(KMR.strings.reconcile_done)
    val failure = stringResource(KMR.strings.reconcile_error)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val current = plan
        if (uri != null && current != null) {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val json = current.recoveryJson()
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(json) }
                        val saved = checkNotNull(context.contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
                        check(saved == json) { "Recovery file could not be verified" }
                        reconciler.apply(current)
                    }
                    plan = null
                    message = done
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    plan = null
                    message = "$failure\n${e.localizedMessage.orEmpty()}"
                } finally {
                    busy = false
                }
            }
        }
    }
    if (selecting) {
        val targets = sources.getAll().filterIsInstance<LocalSource>().sortedBy { it.name }
        AlertDialog(
            onDismissRequest = { selecting = false },
            title = { Text(stringResource(KMR.strings.reconcile_target)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    targets.forEach { target ->
                        TextButton(onClick = {
                            selecting = false
                            busy = true
                            scope.launch {
                                try {
                                    plan = withContext(Dispatchers.IO) { reconciler.preview(target.id) }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = "$failure\n${e.localizedMessage.orEmpty()}"
                                } finally {
                                    busy = false
                                }
                            }
                        }) { Text(target.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { selecting = false }) { Text(stringResource(KMR.strings.reconcile_close)) } },
        )
    }
    val current = plan
    if (current != null) {
        val moveLabel = stringResource(KMR.strings.reconcile_move)
        val linkedLabel = stringResource(KMR.strings.reconcile_linked)
        val reviewLabel = stringResource(KMR.strings.reconcile_review)
        val missingLabel = stringResource(KMR.strings.reconcile_missing)
        AlertDialog(
            onDismissRequest = { if (!busy) plan = null },
            title = { Text(stringResource(KMR.strings.reconcile_preview)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(current.root)
                    Text(stringResource(KMR.strings.reconcile_summary, current.moves.size))
                    current.entries.forEach { entry ->
                        val status = when {
                            entry.movable -> moveLabel
                            entry.alreadyLinked -> linkedLabel
                            entry.candidates.isEmpty() -> missingLabel
                            else -> reviewLabel
                        }
                        Text("\n$status: ${entry.folder}")
                        entry.candidates.forEach { row ->
                            Text("#${row.id} | ${sources.getOrStub(row.source).name} | ${row.url} | ${row.title}")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && current.moves.isNotEmpty(), onClick = {
                    save.launch("collection-recovery-${System.currentTimeMillis()}.json")
                }) { Text(stringResource(KMR.strings.reconcile_apply)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { plan = null }) { Text(stringResource(KMR.strings.reconcile_close)) } },
        )
    }
    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(KMR.strings.reconcile_close)) } },
        )
    }
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(KMR.strings.reconcile_title),
        subtitle = stringResource(if (busy) KMR.strings.reconcile_busy else KMR.strings.reconcile_description),
        enabled = !busy,
        onClick = { selecting = true },
    )
}
