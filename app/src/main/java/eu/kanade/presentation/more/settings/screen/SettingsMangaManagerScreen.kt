package eu.kanade.presentation.more.settings.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import eu.kanade.tachiyomi.data.local.CollectionFileEditor
import eu.kanade.tachiyomi.data.local.MangaManagerService
import eu.kanade.tachiyomi.ui.browse.source.browse.BulkMangaTagsDialog
import eu.kanade.tachiyomi.ui.browse.source.browse.CollectionExportReviewDialog
import eu.kanade.tachiyomi.ui.browse.source.browse.CollectionFileEditDialog
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsMangaManagerScreen : SearchableSettings {
    @Composable override fun getTitleRes() = KMR.strings.manager_title

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val sources = remember { Injekt.get<SourceManager>() }
        val available by sources.sources.collectAsState(initial = sources.getAll())
        val collections = available.filterIsInstance<LocalSource>().filter { FileSourceIds.contains(it.id) && it.id != FileSourceIds.PRIVATE_COLLECTION && it.id != FileSourceIds.LOCAL }.sortedBy { it.name }
        var selected by remember { mutableStateOf<String?>(null) }
        val source = collections.firstOrNull { it.id.toString() == selected }
        val service = remember { MangaManagerService(context.applicationContext) }
        val jobs = remember { CollectionBackupJobs.get(context) }
        val job by jobs.state.collectAsState()
        var working by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var confirmText by remember { mutableStateOf<String?>(null) }
        var confirmAction by remember { mutableStateOf<(suspend () -> Unit)?>(null) }
        var inputAction by remember { mutableStateOf<String?>(null) }
        var first by remember { mutableStateOf("") }
        var second by remember { mutableStateOf("") }
        var choosing by remember { mutableStateOf<String?>(null) }
        var catalog by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var checked by remember { mutableStateOf<List<Long>>(emptyList()) }
        var dialogMangas by remember { mutableStateOf<List<Manga>>(emptyList()) }
        var fileDialog by remember { mutableStateOf<String?>(null) }
        var pendingSource by rememberSaveable { mutableStateOf<Long?>(null) }
        var pendingMode by rememberSaveable { mutableStateOf("") }
        var pendingRemove by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
        val done = stringResource(KMR.strings.manager_done)
        val close = stringResource(KMR.strings.manager_cancel)
        val apply = stringResource(KMR.strings.manager_apply)
        val removeHint = stringResource(KMR.strings.manager_remove_hint)
        val busy = working || job.busy || job.prepared != null
        val enabled = source != null && !busy

        fun work(block: suspend () -> Unit) {
            if (working) return
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO + NonCancellable) { block() }
                } catch (e: Exception) {
                    message = e.localizedMessage ?: e.javaClass.simpleName
                } finally {
                    working = false
                }
            }
        }
        fun ask(text: String, action: suspend () -> Unit) {
            confirmText = text
            confirmAction = action
        }
        fun input(action: String) {
            inputAction = action
            first = ""
            second = ""
        }
        fun choose(action: String) {
            val target = source ?: return
            work {
                val loaded = service.catalog(target)
                withContext(Dispatchers.Main) {
                    catalog = loaded
                    checked = emptyList()
                    choosing = action
                }
            }
        }
        val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val id = pendingSource
            val mode = pendingMode
            val ids = pendingRemove
            pendingSource = null
            pendingMode = ""
            pendingRemove = emptyList()
            if (uri != null && id != null) {
                val target = sources.get(id) as? LocalSource
                if (target != null) {
                    if (mode == "remove") {
                        work {
                            val records = service.catalog(target).filter { it.id in ids }
                            require(records.size == ids.size) { "Selection changed" }
                            service.remove(target, records, uri) { _, _ -> }
                            message = done
                        }
                    } else {
                        jobs.create(id, uri, approvedOnly = true)
                    }
                }
            }
        }
        val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val id = pendingSource
            val mode = pendingMode
            pendingSource = null
            pendingMode = ""
            if (uri != null) {
                if (mode == "verify") {
                    jobs.verify(uri)
                } else {
                    (id?.let { sources.get(it) } as? LocalSource)?.let { jobs.importFiles(it, uri, false, mode == "repair") }
                }
            }
        }
        val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val id = pendingSource
            val mode = pendingMode
            pendingSource = null
            pendingMode = ""
            if (uri != null) (id?.let { sources.get(it) } as? LocalSource)?.let { jobs.importFiles(it, uri, true, mode == "repair", batch = mode == "batch") }
        }
        fun pick(mode: String, tree: Boolean = false) {
            pendingSource = source?.id
            pendingMode = mode
            if (tree) folder.launch(null) else open.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed", "application/vnd.comicbook+zip"))
        }
        message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Text(text) } } }, confirmButton = { TextButton(onClick = { message = null }) { Text(close) } }) }
        confirmText?.let { text ->
            AlertDialog(
                onDismissRequest = {
                    confirmText = null
                    confirmAction = null
                },
                title = { Text(stringResource(KMR.strings.manager_confirm)) },
                text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { item { Text(text) } } },
                confirmButton = {
                    TextButton(onClick = {
                        val action = confirmAction
                        confirmText = null
                        confirmAction = null
                        if (action != null) work { action() }
                    }) { Text(apply) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmText = null
                        confirmAction = null
                    }) { Text(close) }
                },
            )
        }
        inputAction?.let { action ->
            AlertDialog(onDismissRequest = { inputAction = null }, text = {
                Column {
                    OutlinedTextField(first, { first = it }, singleLine = true, label = {
                        Text(
                            stringResource(
                                when (action) {
                                    "rename" -> KMR.strings.manager_old_tag
                                    "related" -> KMR.strings.manager_query
                                    else -> KMR.strings.manager_new_tag
                                },
                            ),
                        )
                    })
                    if (action == "rename") OutlinedTextField(second, { second = it }, singleLine = true, label = { Text(stringResource(KMR.strings.manager_new_tag)) })
                }
            }, confirmButton = {
                TextButton(enabled = action == "related" || (first.isNotBlank() && (action != "rename" || second.isNotBlank())), onClick = {
                    val target = source ?: return@TextButton
                    val a = first
                    val b = second
                    inputAction = null
                    when (action) {
                        "rename" -> ask("$a -> $b") {
                            service.renameTag(target, a, b)
                            message = done
                        }
                        "add" -> work {
                            service.addTag(target, a)
                            message = done
                        }
                        "related" -> work { message = service.related(target, a) }
                    }
                }) { Text(apply) }
            }, dismissButton = { TextButton(onClick = { inputAction = null }) { Text(close) } })
        }
        choosing?.let { action ->
            AlertDialog(onDismissRequest = { choosing = null }, title = { Text(stringResource(KMR.strings.manager_choose)) }, text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(catalog, key = { it.id }) { manga ->
                        Row(Modifier.fillMaxWidth().clickable { checked = if (manga.id in checked) checked - manga.id else checked + manga.id }) {
                            Checkbox(manga.id in checked, onCheckedChange = { value -> checked = if (value) (checked + manga.id).distinct() else checked - manga.id })
                            Text((checked.indexOf(manga.id).takeIf { it >= 0 }?.let { "${it + 1}. " } ?: "") + manga.title)
                        }
                    }
                }
            }, confirmButton = {
                TextButton(enabled = checked.isNotEmpty() && (action != "rename" || checked.size == 1), onClick = {
                    val records = checked.map { id -> catalog.first { it.id == id } }
                    choosing = null
                    if (action == "remove") {
                        pendingSource = source?.id
                        pendingRemove = records.map { it.id }
                        pendingMode = "remove"
                        ask(removeHint + "\n\n" + records.joinToString("\n") { it.title }) { withContext(Dispatchers.Main) { save.launch("collection-before-removal-${System.currentTimeMillis()}.zip") } }
                    } else {
                        dialogMangas = records
                        fileDialog = action
                    }
                }) { Text(apply) }
            }, dismissButton = { TextButton(onClick = { choosing = null }) { Text(close) } })
        }
        if (source != null && dialogMangas.isNotEmpty()) {
            when (fileDialog) {
                "rename", "merge" -> CollectionFileEditDialog(source, dialogMangas, fileDialog == "merge", { fileDialog = null }, { fileDialog = null })
                "review" -> CollectionExportReviewDialog(source, dialogMangas) { fileDialog = null }
                "tags" -> BulkMangaTagsDialog(source, dialogMangas) { fileDialog = null }
            }
        }
        val entries = collections.associate { it.id.toString() to it.name }.toMutableMap().also { it[""] = stringResource(KMR.strings.collection_backup_select) }.toImmutableMap()
        fun item(title: String, action: () -> Unit) = Preference.PreferenceItem.TextPreference(title = title, enabled = enabled, onClick = action)
        val preferences = mutableListOf<Preference>(
            Preference.PreferenceItem.BasicListPreference(value = selected.orEmpty(), entries = entries, title = stringResource(KMR.strings.collection_backup_collection), enabled = !busy && fileDialog == null, onValueChanged = { selected = it.takeIf(String::isNotBlank) }),
            Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.manager_import_hint)),
            item(stringResource(KMR.strings.manager_import_zip)) { pick("import") },
            item(stringResource(KMR.strings.manager_import_folder)) { pick("import", true) },
            item(stringResource(KMR.strings.manager_import_batch)) { pick("batch", true) },
            item(stringResource(KMR.strings.manager_repair)) { pick("repair") },
            item(stringResource(KMR.strings.manager_repair_folder)) { pick("repair", true) },
            item(stringResource(KMR.strings.manager_approved)) {
                pendingSource = source?.id
                pendingMode = "approved"
                save.launch("collection-approved-${System.currentTimeMillis()}.zip")
            },
            Preference.PreferenceItem.TextPreference(title = stringResource(KMR.strings.manager_verify), enabled = !busy, onClick = { pick("verify") }),
            item(stringResource(KMR.strings.export_review_title)) { choose("review") },
            item(stringResource(KMR.strings.bulk_tags_title)) { choose("tags") },
            item(stringResource(KMR.strings.manager_add_tag)) { input("add") },
            item(stringResource(KMR.strings.manager_rename_tag)) { input("rename") },
            item(stringResource(KMR.strings.manager_normalize)) {
                val target = source!!
                ask(target.name) {
                    service.normalizeTags(target)
                    message = done
                }
            },
            item(stringResource(KMR.strings.manager_format)) {
                val target = source!!
                work {
                    val plan = service.formatPlan(target)
                    withContext(Dispatchers.Main) {
                        ask(plan.joinToString("\n") { "${it.manga.title} -> ${it.next.title}\n${it.next.credits.joinToString()} / ${it.next.tags.joinToString()}" }.ifBlank { "0" }) {
                            service.applyFormat(target, plan)
                            message = done
                        }
                    }
                }
            },
            item(stringResource(KMR.strings.manager_related)) { input("related") },
            item(stringResource(KMR.strings.collection_edit_rename)) { choose("rename") },
            item(stringResource(KMR.strings.collection_edit_merge)) { choose("merge") },
            item(stringResource(KMR.strings.manager_remove)) { choose("remove") },
            item(stringResource(KMR.strings.manager_rescan)) {
                val target = source!!
                work {
                    target.withExclusiveCollectionAccess {
                        target.prepareDatabaseMetadata()
                        target.refreshAfterFileEdit()
                    }
                    message = done
                }
            },
            item(stringResource(KMR.strings.manager_recover)) {
                val target = source!!
                work {
                    service.recover(target)
                    CollectionFileEditor.get(context).recover(target)
                    message = done
                }
            },
        )
        preferences += collectionTagManagementPreference()
        preferences += key(source?.id) { collectionBackupPreferences(source?.id).drop(1) }
        if (working) preferences += Preference.PreferenceItem.CustomPreference(title = "") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        preferences += Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.manager_help))
        return preferences
    }
}
