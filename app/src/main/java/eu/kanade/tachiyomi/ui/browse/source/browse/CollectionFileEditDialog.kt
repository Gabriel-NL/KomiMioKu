package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.local.CollectionFileEditor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource

/** One editor for standalone works and physical multichapters; list order is selection order. */
@Composable
fun CollectionFileEditDialog(source: LocalSource, mangas: List<Manga>, merge: Boolean, onDismiss: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val editor = remember { CollectionFileEditor.get(context) }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<CollectionFileEditor.Preview?>(null) }
    var title by remember { mutableStateOf(mangas.firstOrNull()?.title.orEmpty()) }
    var titles by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source, mangas.map { it.id }) {
        try {
            val loaded = editor.preview(source, mangas)
            preview = loaded
            titles = loaded.chapters.map { it.title }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(if (merge) KMR.strings.collection_edit_merge else KMR.strings.collection_edit_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(KMR.strings.collection_edit_hint))
                OutlinedTextField(
                    value = title,
                    onValueChange = { value ->
                        if (!merge && titles.size == 1 && (titles.first() == title || titles.first() == preview?.chapters?.firstOrNull()?.title)) titles = listOf(value)
                        title = value
                    },
                    enabled = !busy,
                    singleLine = true,
                    label = { Text(stringResource(KMR.strings.collection_edit_title)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy || (preview == null && error == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (merge) {
                        itemsIndexed(mangas, key = { _, manga -> "manga:${manga.id}" }) { index, manga ->
                            Text("${index + 1}. ${manga.title}")
                        }
                    }
                    itemsIndexed(preview?.chapters.orEmpty(), key = { _, chapter -> "${chapter.mangaId}:${chapter.url}" }) { index, chapter ->
                        OutlinedTextField(
                            value = titles[index],
                            onValueChange = { value -> titles = titles.toMutableList().also { it[index] = value } },
                            enabled = !busy,
                            singleLine = true,
                            label = { Text("${index + 1}. ${chapter.file}") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                error?.let { Text(it) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && preview != null && title.isNotBlank() && titles.all { it.isNotBlank() }, onClick = {
                val plan = preview ?: return@TextButton
                busy = true
                error = null
                scope.launch {
                    try {
                        editor.apply(source, plan, title, titles, merge)
                        onDone()
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                    } finally {
                        busy = false
                    }
                }
            }) { Text(stringResource(KMR.strings.collection_edit_apply)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(KMR.strings.collection_edit_cancel)) } },
    )
}
