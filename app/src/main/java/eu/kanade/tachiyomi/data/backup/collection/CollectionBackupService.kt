package eu.kanade.tachiyomi.data.backup.collection

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.hippo.unifile.UniFile
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.data.DatabaseHandler
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.domain.backup.service.CollectionArchivePolicy
import tachiyomi.domain.backup.service.CollectionRecoveryAction
import tachiyomi.domain.backup.service.collectionRecoveryAction
import tachiyomi.domain.localtag.model.CollectionTagChanges
import tachiyomi.domain.localtag.model.LocalMangaUsage
import tachiyomi.domain.localtag.repository.ExportReviewRepository
import tachiyomi.domain.localtag.repository.LocalMetadataRepository
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Full collection archives are independent of traditional user-data backups. */
class CollectionBackupService(
    private val context: Context,
    private val handler: DatabaseHandler = Injekt.get(),
    private val sources: SourceManager = Injekt.get(),
    private val mangas: MangaRepository = Injekt.get(),
    private val tags: LocalTagRepository = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val covers: CoverCache = Injekt.get(),
) {
    private val reviews get() = Injekt.get<ExportReviewRepository>()
    private val metadata get() = Injekt.get<LocalMetadataRepository>()
    private val json = Json { encodeDefaults = true }
    private val workRoot get() = File(context.filesDir, "collection-backup-work")

    data class SourceFile(
        val path: String,
        val size: Long,
        val modified: Long,
        val identity: String,
        val currentVersion: () -> Pair<Long, Long>,
        val open: () -> InputStream,
    )
    data class Prepared(
        val operation: String,
        val source: Long,
        val root: String,
        val directory: File,
        val manifest: CollectionBackupManifest,
        val expectedFiles: Map<String, String?>,
        val expectedCovers: Map<String, String?>,
        val expectedData: String,
        val conflicts: List<String>,
    )

    fun hasRecovery(): Boolean = workRoot.listFiles().orEmpty().any { journalFile(it).isFile || File(it, "journal.json.bak").isFile }

    private fun source(id: Long): LocalSource {
        require(id != FileSourceIds.LOCAL && id != FileSourceIds.PRIVATE_COLLECTION && FileSourceIds.contains(id)) { "Select a custom collection" }
        return sources.get(id) as? LocalSource ?: error("Collection unavailable")
    }

    private fun directory(source: LocalSource): UniFile = checkNotNull(source.getCollectionDirectory()).also {
        check(it.exists() && it.isDirectory && it.canRead() && it.canWrite()) { "Collection folder unavailable" }
        check(checkNotNull(it.listFiles()).none { file -> file.name.orEmpty().startsWith(".collection-edit-pending-") }) { "Finish pending collection file edit recovery before backing up or restoring." }
    }

    private fun identity(uri: Uri): String = when (uri.scheme) {
        "file" -> "file:" + File(checkNotNull(uri.path)).canonicalPath
        "content" -> runCatching { uri.authority + ":" + android.provider.DocumentsContract.getDocumentId(uri) }.getOrDefault(uri.toString())
        else -> uri.toString()
    }

    private fun hash(input: InputStream): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val n = it.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun copy(input: InputStream, output: OutputStream, expected: CollectionAsset? = null): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        val buffer = ByteArray(65536)
        input.use { stream ->
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                size = Math.addExact(size, n.toLong())
                check(expected == null || size <= expected.size) { "Backup entry exceeds declared size" }
                output.write(buffer, 0, n)
                digest.update(buffer, 0, n)
            }
        }
        val value = digest.digest().joinToString("") { "%02x".format(it) }
        check(expected == null || (size == expected.size && value == expected.sha256)) { "Backup integrity check failed" }
        return size to value
    }

    private suspend fun snapshot(id: Long): CollectionUserData = handler.await(inTransaction = true) {
        val records = mangas.getMangaBySourceId(id).sortedBy { it.url }
        val entries = MangaBackupCreator()(records, BackupOptions())
        val orders = entries.flatMap { it.categories }.toSet()
        CollectionUserData(
            entries,
            CategoriesBackupCreator()().filter { it.order in orders },
            tags.getAll(id).map { it.name },
            records.map { CollectionTagLink(it.url, tags.getTagsForManga(it.id).map { tag -> tag.name }) },
            metadata.documents(id),
            reviews.approvals(id),
        )
    }

    private fun files(root: UniFile): Pair<List<SourceFile>, List<String>> {
        val result = mutableListOf<SourceFile>()
        val directories = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun walk(folder: UniFile, prefix: String, depth: Int) {
            require(depth <= 128 && visited.add(folder.uri.toString())) { "Unsafe folder tree" }
            if (folder.uri.scheme == "file") require(!Files.isSymbolicLink(File(checkNotNull(folder.uri.path)).toPath())) { "Linked folders are unsupported" }
            checkNotNull(folder.listFiles()) { "Cannot read collection files" }.sortedBy { it.name }.forEach { file ->
                val name = checkNotNull(file.name)
                if (name.startsWith(".collection-edit-")) return@forEach
                require('/' !in name)
                val relative = CollectionArchivePolicy.path(prefix + name)
                if (file.uri.scheme == "file") require(!Files.isSymbolicLink(File(checkNotNull(file.uri.path)).toPath())) { "Linked files are unsupported" }
                if (file.isDirectory) {
                    directories += relative
                    walk(file, "$relative/", depth + 1)
                } else {
                    check(file.isFile && file.canRead()) { "Unreadable collection file" }
                    result += SourceFile("files/$relative", file.length(), file.lastModified(), identity(file.uri), { file.length() to file.lastModified() }) { checkNotNull(file.openInputStream()) }
                }
                require(result.size <= CollectionArchivePolicy.MAX_FILES && directories.size <= CollectionArchivePolicy.MAX_FILES)
            }
        }
        walk(root, "", 0)
        return result to directories
    }

    fun cleanUnusedPreparations() {
        workRoot.listFiles().orEmpty().filter { it.isDirectory && !journalFile(it).exists() && !File(it, "journal.json.bak").exists() }.forEach { removeWork(it) }
    }

    suspend fun create(id: Long, uri: Uri, progress: (Int, Int) -> Unit, approvedOnly: Boolean = false) {
        check(!hasRecovery()) { "Recover the interrupted restore first" }
        val collection = source(id)
        collection.withExclusiveCollectionAccess {
            check(handler.await { localManagerOperationsQueries.getPending(id).executeAsList().isEmpty() }) { "Recover the interrupted removal first" }
            collection.prepareDatabaseMetadata()
            val root = directory(collection)
            val rootUri = root.uri.toString()
            val originalData = snapshot(id)
            val selected = originalData.approvals.filter { it.approved }.map { it.manga }.toSet()
            require(!approvedOnly || selected.isNotEmpty()) { "No approved manga in this collection" }
            val data = if (approvedOnly) originalData.approvedSubset() else originalData
            val (originalFiles, originalDirs) = files(root)
            val regular = if (approvedOnly) originalFiles.filter { it.path.removePrefix("files/").substringBefore('/') in selected } else originalFiles
            val dirs = if (approvedOnly) originalDirs.filter { it.substringBefore('/') in selected } else originalDirs
            val custom = linkedMapOf<String, String>()
            val all = regular.toMutableList()
            mangas.getMangaBySourceId(id).filter { !approvedOnly || it.url in selected }.sortedBy { it.url }.forEachIndexed { index, manga ->
                val file = covers.getCustomCoverFile(manga.id)
                if (file.isFile) {
                    val path = "covers/$index"
                    custom[manga.url] = path
                    all += SourceFile(path, file.length(), file.lastModified(), "file:" + file.canonicalPath, { file.length() to file.lastModified() }) { file.inputStream() }
                }
            }
            check(all.none { it.identity == identity(uri) }) { "Choose a backup destination outside the selected collection" }
            CollectionArchivePolicy.validatePaths(all.map { it.path }, dirs.map { "files/$it" })
            val assets = all.mapIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                progress(index, all.size * 2)
                CollectionAsset(file.path, file.size, hash(file.open()))
            }
            val manifest = CollectionBackupManifest(collection = collection.name, createdAt = System.currentTimeMillis(), directories = dirs, assets = assets, customCovers = custom, data = data)
            validate(manifest)
            val bytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            require(bytes.size <= CollectionArchivePolicy.MAX_MANIFEST) { "Collection metadata exceeds backup format limit" }
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                ZipOutputStream(output).use { zip ->
                    zip.setLevel(Deflater.NO_COMPRESSION)
                    zip.putNextEntry(ZipEntry(CollectionArchivePolicy.MANIFEST))
                    zip.write(bytes)
                    zip.closeEntry()
                    all.forEachIndexed { index, file ->
                        currentCoroutineContext().ensureActive()
                        progress(all.size + index, all.size * 2)
                        zip.putNextEntry(ZipEntry(file.path))
                        copy(file.open(), zip, assets[index])
                        zip.closeEntry()
                    }
                    check(all.all { it.currentVersion() == (it.size to it.modified) }) { "Collection files or covers changed during backup; retry" }
                    check(directory(collection).uri.toString() == rootUri) { "Collection changed during backup" }
                    check(json.encodeToString(snapshot(id)) == json.encodeToString(originalData)) { "Collection data changed during backup; retry" }
                    val (after, afterDirs) = files(root)
                    check(originalFiles.map { Triple(it.path, it.size, it.modified) } == after.map { Triple(it.path, it.size, it.modified) } && originalDirs == afterDirs) { "Collection files changed during backup; retry" }
                    // A failed export may still have a ZIP central directory; only this final marker makes it restorable.
                    zip.putNextEntry(ZipEntry(CollectionArchivePolicy.COMPLETE))
                    val footer = JsonObject(mapOf("manifest_sha256" to JsonPrimitive(hash(bytes.inputStream()))))
                    zip.write(footer.toString().toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
            // Verify the actual saved archive, not only bytes supplied to the provider.
            readArchive(uri, null, progress)
        }
    }

    suspend fun verify(uri: Uri, progress: (Int, Int) -> Unit = { _, _ -> }): CollectionBackupManifest = readArchive(uri, null, progress)

    private fun validate(m: CollectionBackupManifest) {
        require(m.version in 1..3) { "Unsupported collection backup version" }
        require(m.data.documents.map { it.manga to it.path }.distinct().size == m.data.documents.size) { "Duplicate metadata documents" }
        m.data.documents.forEach { document ->
            require(document.manga.isEmpty() || m.data.mangas.any { it.url == document.manga }) { "Metadata belongs to another manga" }
            CollectionArchivePolicy.path(document.path)
            if (document.path == LocalMangaUsage.BACKUP_PATH) LocalMangaUsage.decode(document)
            require(document.format in setOf("utf8", "base64")) { "Unsupported metadata encoding" }
        }
        CollectionArchivePolicy.validatePaths(m.assets.map { it.path }, m.directories.map { "files/$it" })
        require(m.assets.all { it.size >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) && (it.path.startsWith("files/") || it.path.startsWith("covers/")) })
        val urls = m.data.mangas.map { CollectionArchivePolicy.path(it.url) }
        require(urls.distinct().size == urls.size && urls.all { '/' !in it }) { "Invalid manga identities" }
        require(urls.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == urls.size) { "Ambiguous manga paths" }
        val known = urls.toSet()
        require(m.data.approvals.map { it.manga }.distinct().size == m.data.approvals.size && m.data.approvals.all { it.manga in known }) { "Invalid export approvals" }
        require(m.data.tags.size <= 100000 && m.data.tags.all { it.isNotBlank() })
        require(m.data.categories.map { it.order }.distinct().size == m.data.categories.size && m.data.categories.map { it.name }.distinct().size == m.data.categories.size) { "Ambiguous category identities" }
        require(m.data.links.map { it.manga }.distinct().size == m.data.links.size)
        require(m.data.links.all { it.manga in known && it.tags.all(String::isNotBlank) })
        require(m.customCovers.keys.all { it in known } && m.customCovers.values.distinct().size == m.customCovers.size)
        require(m.assets.filter { it.path.startsWith("covers/") }.map { it.path }.toSet() == m.customCovers.values.toSet())
        val catalog = m.data.tags.toSet()
        require(m.data.links.all { catalog.containsAll(it.tags) }) { "Missing catalog tags" }
        m.data.mangas.forEach { manga ->
            require(manga.mergedMangaReferences.isEmpty() && manga.flatMetadata == null) { "Foreign source data is not part of a local collection backup" }
            MemoColumnAdapter.decode(manga.memo)
            manga.chapters.forEach { it.toChapterImpl() }
            require(manga.chapters.all { it.url.startsWith(manga.url + "/") && CollectionArchivePolicy.path(it.url).isNotEmpty() })
            require(manga.history.all { it.url.startsWith(manga.url + "/") && CollectionArchivePolicy.path(it.url).isNotEmpty() })
        }
        m.assets.fold(0L) { total, asset -> Math.addExact(total, asset.size) }
    }

    private fun localFile(work: File, path: String): File {
        CollectionArchivePolicy.path(path)
        return File(work, path).also { require(it.canonicalPath.startsWith(work.canonicalPath + File.separator)) }
    }

    private fun readArchive(uri: Uri, work: File?, progress: (Int, Int) -> Unit): CollectionBackupManifest =
        inspectArchive(checkNotNull(context.contentResolver.openInputStream(uri)), work, progress)

    internal fun inspectArchive(input: InputStream, work: File? = null, progress: (Int, Int) -> Unit = { _, _ -> }): CollectionBackupManifest {
        return ZipInputStream(input).use { zip ->
            val first = checkNotNull(zip.nextEntry) { "Empty backup" }
            require(first.name == CollectionArchivePolicy.MANIFEST && !first.isDirectory) { "Not a collection backup" }
            val buffer = ByteArray(CollectionArchivePolicy.MAX_MANIFEST + 1)
            var size = 0
            while (size < buffer.size) {
                val count = zip.read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            require(size <= CollectionArchivePolicy.MAX_MANIFEST) { "Backup manifest too large" }
            val manifest = json.decodeFromString<CollectionBackupManifest>(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(buffer, 0, size)).toString())
            validate(manifest)
            val total = manifest.assets.fold(0L) { n, f -> Math.addExact(n, f.size) }
            if (work != null) check(work.usableSpace > total + 16L * 1024 * 1024) { "Insufficient space to validate backup" }
            val assets = manifest.assets.associateBy { it.path }
            val seen = mutableSetOf<String>()
            var complete = false
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == CollectionArchivePolicy.COMPLETE) {
                    require(!complete && !entry.isDirectory && seen == assets.keys) { "Invalid backup completion marker" }
                    val footerBytes = ByteArray(4097)
                    var footerSize = 0
                    while (footerSize < footerBytes.size) {
                        val n = zip.read(footerBytes, footerSize, footerBytes.size - footerSize)
                        if (n < 0) break
                        footerSize += n
                    }
                    require(footerSize <= 4096) { "Invalid backup completion marker" }
                    val footer = json.decodeFromString<JsonObject>(footerBytes.copyOf(footerSize).toString(Charsets.UTF_8))
                    require(footer["manifest_sha256"]?.jsonPrimitive?.content == hash(buffer.copyOf(size).inputStream())) { "Backup manifest integrity check failed" }
                    complete = true
                    continue
                }
                require(!complete) { "Unexpected entry after backup completion" }
                val asset = checkNotNull(assets[entry.name]) { "Unexpected backup entry" }
                require(!entry.isDirectory && seen.add(entry.name)) { "Duplicate backup entry" }
                val output = if (work == null) {
                    object : OutputStream() {
                        override fun write(value: Int) = Unit
                        override fun write(buffer: ByteArray, offset: Int, length: Int) = Unit
                    }
                } else {
                    localFile(work, entry.name).also { it.parentFile?.mkdirs() }.outputStream()
                }
                // Closing a wrapper here must not close the shared ZipInputStream.
                val wrapper = object : java.io.FilterInputStream(zip) {
                    override fun close() = Unit
                }
                output.use { copy(wrapper, it, asset) }
                progress(seen.size, assets.size)
            }
            require(complete && seen == assets.keys) { "Incomplete collection backup" }
            manifest
        }
    }

    private fun contained(root: UniFile, file: UniFile): UniFile {
        check(file.uri.scheme == root.uri.scheme && file.uri.authority == root.uri.authority) { "File provider escaped the collection" }
        if (file.uri.scheme == "file") {
            val base = File(checkNotNull(root.uri.path)).canonicalPath
            val target = File(checkNotNull(file.uri.path))
            require(!Files.isSymbolicLink(target.toPath()) && target.canonicalPath.startsWith(base + File.separator)) { "Linked collection paths are unsupported" }
        }
        return file
    }

    private fun child(root: UniFile, path: String, createParents: Boolean = false): UniFile? {
        val parts = CollectionArchivePolicy.path(path).split('/')
        var current = root
        for (part in parts.dropLast(1)) {
            val next = current.findFile(part) ?: if (createParents) checkNotNull(current.createDirectory(part)) else return null
            contained(root, next)
            check(next.isDirectory) { "A file blocks a required folder" }
            current = next
        }
        return current.findFile(parts.last())?.let { contained(root, it) }
    }

    private fun parent(root: UniFile, path: String): UniFile {
        val parts = CollectionArchivePolicy.path(path).split('/')
        var current = root
        parts.dropLast(1).forEach {
            current = checkNotNull(current.findFile(it) ?: current.createDirectory(it)).also { folder ->
                contained(root, folder)
                check(folder.isDirectory)
            }
        }
        return current
    }

    suspend fun prepare(id: Long, uri: Uri, progress: (Int, Int) -> Unit): Prepared {
        check(!hasRecovery()) { "Recover the interrupted restore first" }
        val collection = source(id)
        val operation = UUID.randomUUID().toString()
        val work = File(workRoot, operation).also { check(it.mkdirs()) }
        try {
            val manifest = readArchive(uri, work, progress)
            return collection.withExclusiveCollectionAccess {
                check(handler.await { localManagerOperationsQueries.getPending(id).executeAsList().isEmpty() }) { "Recover the interrupted removal first" }
                val root = directory(collection)
                val expected = linkedMapOf<String, String?>()
                val conflicts = mutableListOf<String>()
                val expectedCovers = mutableMapOf<String, String?>()
                var rollbackSpace = 0L
                manifest.assets.filter { it.path.startsWith("files/") }.forEach { asset ->
                    val path = asset.path.removePrefix("files/")
                    val file = child(root, path)
                    check(file == null || file.isFile) { "Folder/file conflict: $path" }
                    expected[path] = file?.let { hash(checkNotNull(it.openInputStream())) }
                    if (file != null && expected[path] != asset.sha256) {
                        conflicts += path
                        rollbackSpace = Math.addExact(rollbackSpace, file.length())
                    }
                }
                manifest.directories.forEach { path -> check(child(root, path)?.isFile != true) { "File blocks directory: $path" } }
                manifest.customCovers.forEach { (url, path) ->
                    val manga = mangas.getMangaByUrlAndSourceId(url, id)
                    val cover = manga?.let { covers.getCustomCoverFile(it.id) }
                    expectedCovers[url] = cover?.takeIf { it.isFile }?.let { hash(it.inputStream()) }
                    if (cover?.isFile == true) {
                        rollbackSpace = Math.addExact(rollbackSpace, cover.length())
                        if (hash(cover.inputStream()) != manifest.assets.single { it.path == path }.sha256) conflicts += "cover: $url"
                    }
                }
                check(work.usableSpace > rollbackSpace + 16L * 1024 * 1024) { "Insufficient space for recovery copies" }
                Prepared(operation, id, root.uri.toString(), work, manifest, expected, expectedCovers, json.encodeToString(snapshot(id)), conflicts)
            }
        } catch (e: Throwable) {
            removeWork(work)
            throw e
        }
    }

    private fun journalFile(work: File) = File(work, "journal.json")
    private fun saveJournal(work: File, value: CollectionRestoreJournal) {
        val file = AtomicFile(journalFile(work))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Throwable) {
            file.failWrite(output)
            throw e
        }
    }

    private fun tempName(operation: String, path: String, suffix: String): String =
        ".cb-${operation.take(8)}-${MessageDigest.getInstance("SHA-256").digest(path.toByteArray()).take(8).joinToString("") { "%02x".format(it) }}.$suffix"

    private fun rootWrite(root: UniFile, operation: String, write: CollectionRestoreWrite, input: File, undo: Boolean = false) {
        val folder = parent(root, write.path)
        val name = write.path.substringAfterLast('/')
        val temporaryName = tempName(operation, write.path, if (undo) "undo" else "new")
        if (!undo) check(folder.findFile(temporaryName) == null) { "Temporary restore name already exists" }
        val temp = folder.findFile(temporaryName) ?: checkNotNull(folder.createFile(temporaryName))
        checkNotNull(context.contentResolver.openOutputStream(temp.uri, "wt")).use { output -> copy(input.inputStream(), output) }
        val desired = if (undo) checkNotNull(write.oldHash) else write.newHash
        check(hash(checkNotNull(temp.openInputStream())) == desired) { "Restored file verification failed" }
        val current = folder.findFile(name)
        if (current != null) {
            check(current.isFile && hash(checkNotNull(current.openInputStream())) == if (undo) write.newHash else write.oldHash) { "File changed during restoration" }
            val backupName = tempName(operation, write.path, "old")
            if (undo) {
                check(current.delete())
            } else {
                check(folder.findFile(backupName) == null)
                check(current.renameTo(backupName)) { "Folder provider does not support safe replacement" }
            }
        }
        if (current == null && !undo) check(write.oldHash == null) { "Original file disappeared" }
        check(temp.renameTo(name)) { "Folder provider does not support safe replacement" }
        check(hash(checkNotNull(folder.findFile(name)?.openInputStream())) == desired)
    }

    private fun coverWrite(id: Long, input: File) {
        val target = covers.getCustomCoverFile(id)
        target.parentFile?.mkdirs()
        val file = AtomicFile(target)
        val output = file.startWrite()
        try {
            input.inputStream().use { it.copyTo(output) }
            file.finishWrite(output)
        } catch (e: Throwable) {
            file.failWrite(output)
            throw e
        }
    }

    private suspend fun committed(operation: String): Boolean =
        handler.await { collectionBackupCommitsQueries.isCommitted(operation).executeAsOne() > 0 }

    suspend fun apply(plan: Prepared, progress: (Int, Int) -> Unit) {
        val collection = source(plan.source)
        collection.withExclusiveCollectionAccess {
            val root = directory(collection)
            check(root.uri.toString() == plan.root && json.encodeToString(snapshot(plan.source)) == plan.expectedData) { "Collection changed since preview; analyze again" }
            plan.expectedFiles.forEach { (path, oldHash) ->
                val actual = child(root, path)?.let {
                    check(it.isFile)
                    hash(checkNotNull(it.openInputStream()))
                }
                check(actual == oldHash) { "File changed since preview: $path" }
            }
            val dirPaths = (
                plan.manifest.directories + plan.expectedFiles.keys.flatMap { path ->
                    val parts = path.split('/')
                    (1 until parts.size).map { parts.take(it).joinToString("/") }
                }
                ).distinct().sortedBy { it.count { c -> c == '/' } }
            val created = dirPaths.filter { child(root, it) == null }
            var journal = CollectionRestoreJournal(plan.operation, plan.source, plan.root, emptyList(), created)
            File(plan.directory, "user-data-before.json").writeText(plan.expectedData)
            saveJournal(plan.directory, journal)
            try {
                dirPaths.forEach { path -> check(parent(root, "$path/_").isDirectory) }
                val regular = plan.manifest.assets.filter { it.path.startsWith("files/") }
                regular.forEachIndexed { index, asset ->
                    currentCoroutineContext().ensureActive()
                    val path = asset.path.removePrefix("files/")
                    if (plan.expectedFiles[path] != asset.sha256) {
                        val old = child(root, path)
                        val backup = "rollback/${journal.writes.size}"
                        if (old != null) {
                            localFile(plan.directory, backup).also { it.parentFile?.mkdirs() }.outputStream().use {
                                check(copy(checkNotNull(old.openInputStream()), it).second == plan.expectedFiles[path]) { "Original file changed while saving recovery copy" }
                            }
                        }
                        val write = CollectionRestoreWrite("root", path, plan.expectedFiles[path], asset.sha256, backup)
                        journal = journal.copy(writes = journal.writes + write)
                        saveJournal(plan.directory, journal)
                        rootWrite(root, plan.operation, write, localFile(plan.directory, asset.path))
                    }
                    progress(index + 1, regular.size)
                }
                // All user data and the commit marker share the same SQLite transaction.
                checkOriginalTemps(root, journal)
                handler.await(inTransaction = true) {
                    check(json.encodeToString(snapshot(plan.source)) == plan.expectedData) { "Collection data changed during restoration" }
                    val existingCategories = categoriesQueries.getCategories().executeAsList()
                    var order = existingCategories.maxOfOrNull { it.order }?.plus(1) ?: 0
                    plan.manifest.data.categories.forEach { category ->
                        if (existingCategories.none { it.name == category.name }) categoriesQueries.insert(category.name, order++, category.flags, if (category.hidden) 1L else 0L)
                    }
                    tags.addAll(plan.manifest.data.tags, plan.source)
                    val restorer = MangaRestorer(false)
                    plan.manifest.data.mangas.forEach { original ->
                        val coverUri = child(root, original.url)?.listFiles().orEmpty().firstOrNull { it.name.orEmpty().startsWith("cover.") }?.uri?.toString()
                        val manga = remapCollectionManga(original, plan.source, coverUri, json)
                        restorer.restore(manga, plan.manifest.data.categories)
                        val restored = checkNotNull(mangas.getMangaByUrlAndSourceId(manga.url, plan.source))
                        check(updateManga.await(MangaUpdate(restored.id, title = manga.title, author = manga.author, artist = manga.artist, description = manga.description, genre = manga.genre, status = manga.status.toLong(), thumbnailUrl = manga.thumbnailUrl, dateAdded = manga.dateAdded, coverLastModified = System.currentTimeMillis(), viewerFlags = (manga.viewer_flags ?: manga.viewer).toLong(), chapterFlags = manga.chapterFlags.toLong(), notes = manga.notes, memo = MemoColumnAdapter.decode(manga.memo))))
                        plan.manifest.data.links.singleOrNull { it.manga == manga.url }?.let { tags.replaceMangaTags(restored.id, it.tags) }
                        plan.manifest.customCovers[manga.url]?.let { assetPath ->
                            val asset = plan.manifest.assets.single { it.path == assetPath }
                            val old = covers.getCustomCoverFile(restored.id)
                            val oldHash = if (old.isFile) hash(old.inputStream()) else null
                            check(oldHash == plan.expectedCovers[manga.url]) { "Custom cover changed since preview" }
                            if (oldHash != asset.sha256) {
                                val backup = "rollback/${journal.writes.size}"
                                if (old.isFile) {
                                    localFile(plan.directory, backup).also { it.parentFile?.mkdirs() }.outputStream().use { output ->
                                        check(copy(old.inputStream(), output).second == oldHash) { "Custom cover changed while saving recovery copy" }
                                    }
                                }
                                val write = CollectionRestoreWrite("cover", restored.id.toString(), oldHash, asset.sha256, backup, manga.url)
                                journal = journal.copy(writes = journal.writes + write)
                                saveJournal(plan.directory, journal)
                                coverWrite(restored.id, localFile(plan.directory, assetPath))
                            }
                        }
                    }
                    reviews.importApprovals(plan.source, plan.manifest.data.approvals)
                    metadata.restoreDocuments(plan.source, plan.manifest.data.documents)
                    metadata.resetImport(plan.source)
                    collectionBackupCommitsQueries.resetRestoredChapterSync(plan.source)
                    collectionBackupCommitsQueries.markCommitted(plan.operation, plan.source)
                }
                cleanupTemps(root, journal)
                removeWork(plan.directory)
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    if (!committed(plan.operation)) rollback(plan.directory, journal, root)
                }
                throw e
            } finally {
                CollectionTagChanges.markDirty(plan.source)
            }
        }
        collection.preloadCollectionMetadata()
    }

    private fun checkOriginalTemps(root: UniFile, journal: CollectionRestoreJournal) {
        journal.writes.filter { it.type == "root" && it.oldHash != null }.forEach { write ->
            parent(root, write.path).findFile(tempName(journal.operation, write.path, "old"))?.let { old ->
                check(hash(checkNotNull(old.openInputStream())) == write.oldHash) { "Original file changed during restoration; recovery copy preserved" }
            }
        }
    }

    private fun cleanupTemps(root: UniFile, journal: CollectionRestoreJournal) {
        checkOriginalTemps(root, journal)
        journal.writes.filter { it.type == "root" }.forEach { write ->
            val folder = parent(root, write.path)
            listOf("new", "old", "undo").forEach { suffix -> folder.findFile(tempName(journal.operation, write.path, suffix))?.let { check(it.delete()) } }
        }
    }

    private suspend fun rollback(work: File, journal: CollectionRestoreJournal, root: UniFile) {
        checkOriginalTemps(root, journal)
        journal.writes.asReversed().forEach { write ->
            val file = if (write.type == "root") child(root, write.path) else null
            val custom = if (write.type == "cover") covers.getCustomCoverFile(write.path.toLong()) else null
            if (custom != null) {
                val owner = getManga.await(write.path.toLong())
                check((owner == null && write.oldHash == null) || (owner != null && owner.source == journal.source && owner.url == write.mangaUrl)) { "Recovery cover identity conflict" }
            }
            val current = file?.let { hash(checkNotNull(it.openInputStream())) } ?: custom?.takeIf { it.isFile || File(it.path + ".bak").isFile }?.let { hash(AtomicFile(it).openRead()) }
            val action = collectionRecoveryAction(write.oldHash, write.newHash, current)
            check(action != CollectionRecoveryAction.CONFLICT) { "Recovery conflict; existing edits preserved: ${write.path}" }
            if (action != CollectionRecoveryAction.KEEP) {
                if (action == CollectionRecoveryAction.REMOVE_NEW) {
                    if (file != null) check(file.delete())
                    if (custom?.exists() == true) check(custom.delete())
                } else {
                    val original = localFile(work, write.backup)
                    check(hash(original.inputStream()) == write.oldHash)
                    if (write.type == "root") rootWrite(root, journal.operation, write, original, undo = true) else coverWrite(write.path.toLong(), original)
                }
            }
        }
        cleanupTemps(root, journal)
        journal.createdDirectories.sortedByDescending { it.count { c -> c == '/' } }.forEach { path -> child(root, path)?.let { if (it.isDirectory && it.listFiles()?.isEmpty() == true) check(it.delete()) } }
        removeWork(work)
    }

    suspend fun recover(progress: (Int, Int) -> Unit) {
        val jobs = workRoot.listFiles().orEmpty().filter { journalFile(it).isFile || File(it, "journal.json.bak").isFile }
        jobs.forEachIndexed { index, work ->
            val journal = json.decodeFromString<CollectionRestoreJournal>(AtomicFile(journalFile(work)).readFully().toString(Charsets.UTF_8))
            check(journal.operation == work.name)
            val collection = source(journal.source)
            collection.withExclusiveCollectionAccess {
                val root = directory(collection)
                check(root.uri.toString() == journal.root) { "Select the original storage folder before recovery" }
                if (committed(journal.operation)) {
                    cleanupTemps(root, journal)
                    removeWork(work)
                } else {
                    rollback(work, journal, root)
                }
                CollectionTagChanges.markDirty(journal.source)
            }
            collection.preloadCollectionMetadata()
            progress(index + 1, jobs.size)
        }
    }

    fun discard(plan: Prepared) {
        check(!journalFile(plan.directory).exists() && !File(plan.directory, "journal.json.bak").exists())
        removeWork(plan.directory)
    }

    private fun removeWork(work: File) {
        require(work.parentFile?.canonicalFile == workRoot.canonicalFile && UUID.fromString(work.name).toString() == work.name)
        check(work.deleteRecursively()) { "Recovery files retained: ${work.absolutePath}" }
    }
}
