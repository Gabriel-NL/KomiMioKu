package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.localtag.repository.ExportReviewRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
fun CollectionExportReviewDialog(source: LocalSource, mangas: List<Manga>, onDismiss: () -> Unit) {
    var approved by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(KMR.strings.export_review_title)) },
        text = {
            Column {
                Text(stringResource(KMR.strings.bulk_tags_selected, mangas.size))
                Text(stringResource(KMR.strings.export_review_approved))
                Checkbox(checked = approved, enabled = !busy, onCheckedChange = { approved = it })
                if (busy) LinearProgressIndicator()
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        withContext(Dispatchers.IO + NonCancellable) {
                            source.withExclusiveCollectionAccess { Injekt.get<ExportReviewRepository>().setApproved(source.id, mangas.map { it.id }, approved) }
                        }
                        onDismiss()
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(KMR.strings.collection_edit_apply)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(KMR.strings.collection_edit_cancel)) } },
    )
}
