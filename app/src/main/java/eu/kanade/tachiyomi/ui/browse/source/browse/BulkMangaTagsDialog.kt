package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.toSManga
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.localtag.model.CollectionTagEdit
import tachiyomi.domain.localtag.model.localTagKey
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun BulkMangaTagsDialog(source: LocalSource, mangas: List<Manga>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val getManga = remember { Injekt.get<GetManga>() }
    val updateManga = remember { Injekt.get<UpdateManga>() }
    val catalogFlow = remember(source) { source.observeCollectionTags() }
    val catalog by catalogFlow.collectAsState(initial = emptyList())
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var add by remember { mutableStateOf(true) }
    var applying by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var failed by remember { mutableStateOf<List<String>>(emptyList()) }
    val choices = catalog.distinctBy(::localTagKey).sortedWith(String.CASE_INSENSITIVE_ORDER)
    LaunchedEffect(source) { source.preloadCollectionMetadata() }

    AlertDialog(
        onDismissRequest = { if (!applying) onDismiss() },
        title = { Text(stringResource(KMR.strings.bulk_tags_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(KMR.strings.bulk_tags_selected, mangas.size))
                Text(stringResource(KMR.strings.bulk_tags_hint))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (add) KMR.strings.bulk_tags_state_add else KMR.strings.bulk_tags_state_remove),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = add, enabled = !applying && !started, onCheckedChange = { add = it })
                }
                if (choices.isEmpty()) Text(stringResource(KMR.strings.bulk_tags_empty))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(choices, key = { localTagKey(it) }) { tag ->
                        val key = localTagKey(tag)
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = key in selectedTags,
                                enabled = !applying && !started,
                                role = Role.Checkbox,
                                onValueChange = { checked -> selectedTags = if (checked) selectedTags + key else selectedTags - key },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = key in selectedTags, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(tag)
                                Text(stringResource(if (add) KMR.strings.bulk_tags_state_add else KMR.strings.bulk_tags_state_remove), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (applying) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (completed.isNotEmpty()) Text(stringResource(KMR.strings.bulk_tags_progress, completed.size, mangas.size))
                if (failed.isNotEmpty()) {
                    Text(stringResource(KMR.strings.bulk_tags_failed, failed.size), color = MaterialTheme.colorScheme.error)
                    LazyColumn(Modifier.heightIn(max = 80.dp)) { items(failed) { Text(it) } }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !applying && selectedTags.isNotEmpty() && completed.size < mangas.size,
                onClick = {
                    val operations = choices.filter { localTagKey(it) in selectedTags }.map { CollectionTagEdit(it, add) }
                    started = true
                    applying = true
                    failed = emptyList()
                    scope.launch {
                        try {
                            mangas.distinctBy { it.id }.filterNot { it.id in completed }.forEach { selected ->
                                try {
                                    withContext(Dispatchers.IO + NonCancellable) {
                                        val current = checkNotNull(getManga.await(selected.id))
                                        check(current.source == source.id) { "Collection changed" }
                                        val saved = source.applyMangaTagEdits(current.toSManga(), current.id, operations)
                                        check(updateManga.await(MangaUpdate(current.id, genre = saved)))
                                    }
                                    completed = completed + selected.id
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    failed = failed + selected.title
                                }
                            }
                        } finally {
                            applying = false
                        }
                    }
                },
            ) { Text(stringResource(if (failed.isEmpty()) KMR.strings.bulk_tags_apply else KMR.strings.bulk_tags_retry)) }
        },
        dismissButton = {
            TextButton(enabled = !applying, onClick = onDismiss) { Text(stringResource(KMR.strings.reconcile_close)) }
        },
    )
}
