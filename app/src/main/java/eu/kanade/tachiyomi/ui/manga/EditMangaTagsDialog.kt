package eu.kanade.tachiyomi.ui.manga

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.domain.localtag.model.localTagKey
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
internal fun EditMangaTagsDialog(
    mangaId: Long,
    initialTags: List<String>,
    catalog: List<String>,
    saving: Boolean,
    failed: Boolean,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by rememberSaveable(mangaId) { mutableStateOf(initialTags.joinToString(", ")) }
    val selected = text.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinctBy(::localTagKey)
    val keys = selected.map(::localTagKey).toSet()
    val choices = (catalog + selected).distinctBy(::localTagKey).sortedWith(String.CASE_INSENSITIVE_ORDER)

    AlertDialog(
        onDismissRequest = { if (!saving) onDismissRequest() },
        title = { Text(stringResource(KMR.strings.action_edit_manga_tags)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = !saving,
                    label = { Text(stringResource(KMR.strings.local_collection_tags)) },
                    supportingText = { Text(stringResource(KMR.strings.edit_manga_tags_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { tag ->
                        FilterChip(
                            selected = localTagKey(tag) in keys,
                            enabled = !saving,
                            onClick = {
                                text = if (localTagKey(tag) in keys) {
                                    selected.filterNot { localTagKey(it) == localTagKey(tag) }
                                } else {
                                    selected + tag
                                }.joinToString(", ")
                            },
                            label = { Text(tag) },
                        )
                    }
                }
                if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (failed) {
                    Text(stringResource(KMR.strings.edit_manga_tags_error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = { onSave(selected) }) {
                Text(stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismissRequest) {
                Text(stringResource(MR.strings.action_cancel))
            }
        },
    )
}
