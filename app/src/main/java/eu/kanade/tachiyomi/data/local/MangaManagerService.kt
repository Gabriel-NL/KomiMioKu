package eu.kanade.tachiyomi.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.collection.CollectionAsset
import eu.kanade.tachiyomi.data.backup.collection.CollectionBackupManifest
import eu.kanade.tachiyomi.data.backup.collection.CollectionBackupService
import eu.kanade.tachiyomi.data.backup.collection.CollectionUserData
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.backup.service.CollectionArchivePolicy
import tachiyomi.domain.localtag.model.CollectionFileNames
import tachiyomi.domain.localtag.model.CollectionTagChanges
import tachiyomi.domain.localtag.model.MangaManagerRules
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.domain.localtag.model.localTagKey
import tachiyomi.domain.localtag.repository.LocalMetadataRepository
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Operations use the same source lock and restore journal as collection backups. */
class MangaManagerService(
    private val context: Context,
    private val handler: DatabaseHandler = Injekt.get(),
    private val mangas: MangaRepository = Injekt.get(),
    private val tags: LocalTagRepository = Injekt.get(),
    private val metadata: LocalMetadataRepository = Injekt.get(),
    private val backup: CollectionBackupService = CollectionBackupService(context),
) {
    private val json = Json { encodeDefaults = true }
    private fun root(source: LocalSource) = checkNotNull(source.getCollectionDirectory()) { "Collection unavailable" }

    private suspend fun requireReady(source: LocalSource) {
        require(handler.await { localManagerOperationsQueries.getPending(source.id).executeAsList().isEmpty() }) { "Recover the interrupted removal before another operation" }
    }

    suspend fun catalog(source: LocalSource): List<Manga> = source.withExclusiveCollectionAccess {
        requireReady(source)
        source.prepareDatabaseMetadata()
        val names = root(source).listFiles().orEmpty().filter { it.isDirectory && !it.name.orEmpty().startsWith('.') }.map { it.name }.toSet()
        mangas.getMangaBySourceId(source.id).filter { it.url in names }.sortedBy { it.title.lowercase() }
    }

    suspend fun addTag(source: LocalSource, value: String) = source.withExclusiveCollectionAccess {
        requireReady(source)
        require(value.isNotBlank() && value.length <= 160)
        tags.addAll(listOf(value), source.id)
        CollectionTagChanges.markDirty(source.id)
    }

    suspend fun renameTag(source: LocalSource, old: String, new: String) = source.withExclusiveCollectionAccess {
        requireReady(source)
        val name = canonicalLocalTagName(new)
        require(name.isNotBlank() && name.length <= 160)
        require(tags.getAll(source.id).any { localTagKey(it.name) == localTagKey(old) }) { "Tag no longer exists" }
        source.prepareDatabaseMetadata()
        handler.await(inTransaction = true) {
            tags.addAll(listOf(name), source.id)
            mangas.getMangaBySourceId(source.id).forEach { manga ->
                val current = metadata.read(source.id, manga.url) ?: return@forEach
                if (current.fields.tags.any { localTagKey(it) == localTagKey(old) }) {
                    metadata.save(source.id, manga.url, current.fields.copy(tags = current.fields.tags.map { if (localTagKey(it) == localTagKey(old)) name else it }.distinctBy(::localTagKey)))
                }
            }
            if (localTagKey(old) != localTagKey(name)) localSourceTagsQueries.removeSourceTag(source.id, localTagKey(old))
        }
        source.refreshAfterFileEdit()
    }

    suspend fun normalizeTags(source: LocalSource) = source.withExclusiveCollectionAccess {
        requireReady(source)
        source.prepareDatabaseMetadata()
        handler.await(inTransaction = true) {
            tags.addAll(tags.getAll(source.id).map { it.name }, source.id)
            mangas.getMangaBySourceId(source.id).forEach { manga ->
                metadata.read(source.id, manga.url)?.let { metadata.save(source.id, manga.url, it.fields.copy(tags = it.fields.tags.map(::canonicalLocalTagName).distinctBy(::localTagKey))) }
            }
        }
        source.refreshAfterFileEdit()
    }

    data class Format(val manga: Manga, val next: MangaManagerRules.Formatted)
    suspend fun formatPlan(source: LocalSource): List<Format> = catalog(source).mapNotNull { manga ->
        val next = MangaManagerRules.format(manga.title)
        val oldCredits = manga.author.orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
        require(next.credits.isEmpty() || oldCredits.isEmpty() || oldCredits.all { old -> next.credits.any { it.equals(old, true) } }) { "Conflicting author: ${manga.title}" }
        if (next.title == manga.title && next.credits.isEmpty() && next.tags.isEmpty()) null else Format(manga, next)
    }.also { plans ->
        val names = root(source).listFiles().orEmpty().mapNotNull { it.name }.map { it.lowercase() }.toSet()
        require(plans.map { it.next.title.lowercase() }.distinct().size == plans.size) { "Formatted titles collide" }
        plans.forEach { require(it.next.title == it.manga.url || it.next.title.lowercase() !in names) { "Destination exists: ${it.next.title}" } }
    }

    suspend fun applyFormat(source: LocalSource, plans: List<Format>) {
        require(formatPlan(source) == plans) { "Collection changed; preview again" }
        for (plan in plans) {
            val editor = CollectionFileEditor.get(context)
            val preview = editor.preview(source, listOf(plan.manga))
            editor.apply(source, preview, plan.next.title, preview.chapters.map { it.title }, false)
            source.withExclusiveCollectionAccess {
                val current = checkNotNull(metadata.read(source.id, plan.next.title))
                metadata.save(source.id, plan.next.title, current.fields.copy(author = plan.next.credits.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: current.fields.author, tags = (current.fields.tags + plan.next.tags).distinctBy(::localTagKey)))
                source.refreshAfterFileEdit()
            }
        }
    }

    suspend fun related(source: LocalSource, query: String): String {
        val books = catalog(source).filter { query.isBlank() || it.title.contains(query, true) }
        val words = books.map { MangaManagerRules.words(it.title) }
        val stop = setOf("a", "an", "the", "of", "and", "to", "in", "no", "de", "da", "do", "e", "english", "translated", "digital", "comic", "comics", "magazine", "chapter", "chapters", "oneshot", "multi", "complete", "book", "unknown", "author")
        val groups = linkedMapOf<String, MutableSet<Int>>()
        for (i in books.indices) {
            for (j in i + 1 until books.size) {
                val phrase = MangaManagerRules.commonPhrase(words[i], words[j])
                val parts = phrase.split(' ').filter { it.isNotBlank() }
                val useful = parts.filter { it !in stop }
                if (useful.isEmpty() || (parts.size == 1 && useful[0].length < 6) || (parts.size > 1 && useful.joinToString(" ").length < 8)) continue
                if (parts.size < minOf(words[i].size, words[j].size) * 0.6 || parts.size < maxOf(words[i].size, words[j].size) * 0.3) continue
                groups.getOrPut(phrase) { linkedSetOf() }.addAll(listOf(i, j))
            }
        }
        return groups.entries.sortedByDescending { it.value.size }.distinctBy { it.value.sorted() }.joinToString("\n\n") { (phrase, indexes) -> "$phrase\n" + indexes.joinToString("\n") { books[it].title } }.ifBlank { "0" }
    }

    suspend fun recover(source: LocalSource) = source.withExclusiveCollectionAccess {
        val directory = root(source)
        val pending = handler.await { localManagerOperationsQueries.getPending(source.id).executeAsList() }
        pending.forEach { operation ->
            require(operation.root == directory.uri.toString()) { "Restore the original collection location before recovery" }
            val names = json.decodeFromString<Map<String, String>>(operation.payload)
            val committed = handler.await { collectionBackupCommitsQueries.isCommitted(operation.operation).executeAsOne() > 0 }
            if (!committed) {
                names.forEach { (old, hidden) ->
                    val backup = directory.findFile(hidden)
                    if (backup != null) {
                        require(directory.findFile(old) == null) { "Recovery conflict: $old" }
                        check(backup.renameTo(old)) { "Cannot recover $old" }
                    } else {
                        require(directory.findFile(old) != null) { "Recovery copy missing: $old" }
                    }
                }
            }
            handler.await { localManagerOperationsQueries.remove(operation.operation) }
        }
        source.refreshAfterFileEdit()
    }

    /** Recovery ZIP is verified before hiding files; the journal rolls back interrupted removals. */
    suspend fun remove(source: LocalSource, selected: List<Manga>, recovery: Uri, progress: (Int, Int) -> Unit) = withContext(Dispatchers.IO + NonCancellable) {
        require(selected.isNotEmpty() && selected.all { it.source == source.id })
        backup.create(source.id, recovery, progress)
        source.withExclusiveCollectionAccess {
            val directory = root(source)
            val operation = UUID.randomUUID().toString()
            val names = selected.associate { manga ->
                require(mangas.getMangaById(manga.id) == manga) { "Manga changed; select again" }
                CollectionFileNames.validate(manga.url)
                require(directory.findFile(manga.url)?.isDirectory == true) { "Manga folder missing" }
                manga.url to ".collection-edit-backup-remove-$operation-${manga.id}"
            }
            handler.await { localManagerOperationsQueries.put(operation, source.id, directory.uri.toString(), json.encodeToString(names)) }
            try {
                names.forEach { (old, hidden) ->
                    check(directory.findFile(hidden) == null)
                    check(checkNotNull(directory.findFile(old)).renameTo(hidden)) { "Cannot quarantine $old" }
                }
                handler.await(inTransaction = true) {
                    selected.forEach { manga ->
                        require(mangas.getMangaById(manga.id) == manga) { "Manga changed during removal" }
                        localMetadataQueries.clearDocuments(source.id, listOf(manga.url))
                        mangas.deleteManga(manga.id)
                    }
                    collectionBackupCommitsQueries.markCommitted(operation, source.id)
                }
                handler.await { localManagerOperationsQueries.remove(operation) }
            } catch (error: Throwable) {
                // Keep the durable journal; the recovery action restores original folder names.
                throw error
            } finally {
                source.refreshAfterFileEdit()
            }
        }
    }

    private fun digest(stream: InputStream): Pair<Long, String> {
        val hash = MessageDigest.getInstance("SHA-256")
        var size = 0L
        stream.use { input ->
            val buffer = ByteArray(131072)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                size += n
                hash.update(buffer, 0, n)
            }
        }
        return size to hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun imageName(path: String) = path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")

    private fun pageHash(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(131072)
        var size = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            size += count
            require(size <= 128L * 1024 * 1024) { "Page or archive entry exceeds 128 MiB" }
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fingerprints(name: String, open: () -> InputStream): List<String> {
        if (name.substringBeforeLast('.').equals("cover", true)) return emptyList()
        if (imageName(name)) return listOf(open().use(::pageHash))
        if (name.substringAfterLast('.').lowercase() !in setOf("zip", "cbz")) return emptyList()
        val values = mutableListOf<String>()
        ZipInputStream(open()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                require(++entries <= CollectionArchivePolicy.MAX_FILES)
                if (!entry.isDirectory) {
                    val hash = pageHash(zip)
                    if (imageName(entry.name) && !entry.name.substringAfterLast('/').substringBeforeLast('.').equals("cover", true)) values += hash
                }
            }
        }
        return values
    }

    private suspend fun duplicateWarnings(source: LocalSource, incoming: Map<String, List<String>>, records: List<Manga>): List<String> = source.withExclusiveCollectionAccess {
        val results = mutableListOf<String>()
        fun pages(folder: UniFile, depth: Int, visited: MutableSet<String>): List<String> {
            require(depth < 64 && visited.add(folder.uri.toString()))
            return checkNotNull(folder.listFiles()).filter { !it.name.orEmpty().startsWith('.') }.flatMap { child ->
                if (child.isDirectory) pages(child, depth + 1, visited) else fingerprints(child.name.orEmpty()) { child.openInputStream() }
            }
        }
        for (record in records) {
            if (record.url in incoming) continue
            val folder = root(source).findFile(record.url) ?: continue
            val known = pages(folder, 0, mutableSetOf())
            val counts = known.groupingBy { it }.eachCount()
            for ((title, hashes) in incoming) {
                val overlap = hashes.groupingBy { it }.eachCount().entries.sumOf { (hash, count) -> minOf(count, counts[hash] ?: 0) }
                if (overlap > 0) results += "$title / ${record.title}: $overlap/${hashes.size} pages (${known.size})"
            }
        }
        results
    }

    suspend fun prepareBatch(source: LocalSource, uri: Uri, progress: (Int, Int) -> Unit): CollectionBackupService.Prepared {
        val directory = checkNotNull(UniFile.fromUri(context, uri))
        val candidates = checkNotNull(directory.listFiles()).filter { !it.name.orEmpty().startsWith('.') && (it.isDirectory || it.name.orEmpty().substringAfterLast('.').lowercase() in setOf("zip", "cbz")) }.sortedBy { MangaManagerRules.naturalKey(it.name.orEmpty()) }
        require(candidates.isNotEmpty()) { "Choose a folder containing manga folders or archives" }
        val plans = mutableListOf<CollectionBackupService.Prepared>()
        val work = File(context.cacheDir, "manager-batch-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        try {
            for (candidate in candidates) {
                require(candidate.uri != root(source).uri) { "Cannot import the active collection" }
                plans += prepareImport(source, candidate.uri, candidate.isDirectory, false, false, progress)
            }
            val combined = CollectionBackupManifest(
                collection = source.name,
                createdAt = System.currentTimeMillis(),
                directories = plans.flatMap { it.manifest.directories }.distinct(),
                assets = plans.flatMap { it.manifest.assets },
                customCovers = emptyMap(),
                data = CollectionUserData(plans.flatMap { it.manifest.data.mangas }, emptyList(), emptyList(), emptyList()),
            )
            CollectionArchivePolicy.validatePaths(combined.assets.map { it.path }, combined.directories.map { "files/$it" })
            require(combined.data.mangas.map { it.url.lowercase() }.distinct().size == combined.data.mangas.size) { "Duplicate manga names in import" }
            val bytes = json.encodeToString(combined).toByteArray(Charsets.UTF_8)
            require(bytes.size <= CollectionArchivePolicy.MAX_MANIFEST)
            val archive = File(work, "batch.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(CollectionArchivePolicy.MANIFEST))
                zip.write(bytes)
                zip.closeEntry()
                plans.forEach { plan ->
                    plan.manifest.assets.forEach { asset ->
                        zip.putNextEntry(ZipEntry(asset.path))
                        File(plan.directory, asset.path).inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
                zip.putNextEntry(ZipEntry(CollectionArchivePolicy.COMPLETE))
                zip.write(buildJsonObject { put("manifest_sha256", digest(bytes.inputStream()).second) }.toString().toByteArray())
                zip.closeEntry()
            }
            val incoming = plans.associate { plan ->
                plan.manifest.data.mangas.single().url to plan.manifest.assets.flatMap { asset -> fingerprints(asset.path.substringAfterLast('/')) { File(plan.directory, asset.path).inputStream() } }
            }
            val warnings = duplicateWarnings(source, incoming, catalog(source)).toMutableList()
            val titles = incoming.keys.toList()
            for (i in titles.indices) {
                for (j in i + 1 until titles.size) {
                    val overlap = incoming.getValue(titles[i]).toSet().intersect(incoming.getValue(titles[j]).toSet()).size
                    if (overlap > 0) warnings += "${titles[i]} / ${titles[j]}: $overlap pages"
                }
            }
            val result = backup.prepare(source.id, Uri.fromFile(archive), progress)
            return result.copy(conflicts = result.conflicts + warnings)
        } finally {
            plans.forEach { backup.discard(it) }
            work.deleteRecursively()
        }
    }

    /** Import is staged as a normal restore: hashing, conflict preview, durable rollback. */
    suspend fun prepareImport(source: LocalSource, uri: Uri, tree: Boolean, repair: Boolean, compareDuplicates: Boolean = true, progress: (Int, Int) -> Unit): CollectionBackupService.Prepared = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "manager-import-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val files = linkedMapOf<String, File>()
        val directories = linkedSetOf<String>()
        fun save(path: String, input: InputStream) {
            CollectionArchivePolicy.path(path)
            require(files.keys.none { it.equals(path, true) }) { "Duplicate path: $path" }
            require(files.size < 100000) { "Too many files" }
            val output = File(work, "asset-${files.size}." + path.substringAfterLast('.', "bin"))
            output.outputStream().use { target ->
                val buffer = ByteArray(131072)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(work.usableSpace > count + 64L * 1024 * 1024) { "Insufficient space for import" }
                    target.write(buffer, 0, count)
                }
            }
            files[path] = output
            val parts = path.split('/')
            (1 until parts.size).forEach { directories += parts.take(it).joinToString("/") }
        }
        fun walk(folder: UniFile, prefix: String, depth: Int, visited: MutableSet<String>) {
            require(depth < 64 && visited.add(folder.uri.toString())) { "Cyclic or deeply nested folder" }
            if (folder.uri.scheme == "file") require(!java.nio.file.Files.isSymbolicLink(File(checkNotNull(folder.uri.path)).toPath())) { "Linked folders are unsupported" }
            checkNotNull(folder.listFiles()).sortedBy { it.name }.forEach { child ->
                val name = checkNotNull(child.name)
                CollectionFileNames.validate(name)
                val path = "$prefix/$name"
                if (child.isDirectory) walk(child, path, depth + 1, visited) else child.openInputStream().use { save(path, it) }
            }
        }
        try {
            val input = checkNotNull(UniFile.fromUri(context, uri))
            val title = CollectionFileNames.validate(checkNotNull(input.name).let { if (tree) it else it.substringBeforeLast('.') })
            if (tree) {
                val collectionUri = root(source).uri
                fun documentPath(value: Uri): String = try {
                    android.provider.DocumentsContract.getDocumentId(value)
                } catch (_: Exception) {
                    android.provider.DocumentsContract.getTreeDocumentId(value)
                }
                if (uri.authority == collectionUri.authority && uri.scheme == "content" && collectionUri.scheme == "content") {
                    val a = documentPath(uri).trimEnd('/')
                    val b = documentPath(collectionUri).trimEnd('/')
                    require(a != b && !a.startsWith("$b/") && !b.startsWith("$a/")) { "Import source and collection must not contain each other" }
                }
                if (uri.scheme == "file" && collectionUri.scheme == "file") {
                    val a = File(checkNotNull(uri.path)).canonicalPath
                    val b = File(checkNotNull(collectionUri.path)).canonicalPath
                    require(a != b && !a.startsWith("$b/") && !b.startsWith("$a/")) { "Import source and collection must not contain each other" }
                }
                walk(input, title, 0, mutableSetOf())
            } else {
                require(input.name.orEmpty().substringAfterLast('.').lowercase() in setOf("zip", "cbz")) { "Choose ZIP or CBZ" }
                if (input.name.orEmpty().endsWith(".cbz", true)) {
                    input.openInputStream().use { save("$title/${input.name}", it) }
                } else {
                    ZipInputStream(input.openInputStream()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val path = CollectionArchivePolicy.path(entry.name.removeSuffix("/"))
                            require(!path.startsWith('.') && path.split('/').none { it.startsWith('.') }) { "Hidden archive path" }
                            require(path !in setOf(CollectionArchivePolicy.MANIFEST, CollectionArchivePolicy.COMPLETE)) { "Use Restore collection backup for app backups" }
                            if (!entry.isDirectory) save("$title/$path", zip)
                        }
                    }
                }
            }
            require(files.isNotEmpty()) { "Empty import" }
            // Loose image-only manga are converted into one CBZ; originals remain in the chosen source.
            val images = files.filterKeys { !it.substringAfterLast('/').substringBeforeLast('.').equals("cover", true) && it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl") }
            if (images.isNotEmpty()) {
                val imageGroups = images.keys.map { it.substringBeforeLast('/') }.toSet()
                require(files.keys.none { it.substringAfterLast('.').lowercase() in setOf("zip", "cbz") && it.substringBeforeLast('/') in imageGroups }) { "Loose images and archives are mixed in the same chapter; choose one representation" }
                images.keys.forEach { files.remove(it) }
                directories.clear()
                directories += title
                val groups = images.entries.groupBy { it.key.substringBeforeLast('/') }.toSortedMap(compareBy { MangaManagerRules.naturalKey(it) })
                groups.entries.forEachIndexed { index, (group, pages) ->
                    val cbz = File(work, "chapter-$index.cbz")
                    ZipOutputStream(cbz.outputStream()).use { zip ->
                        pages.sortedBy { MangaManagerRules.naturalKey(it.key) }.forEach { (path, file) ->
                            zip.putNextEntry(ZipEntry(path.substringAfterLast('/')))
                            file.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                    val name = if (group == title) "Chapter 1" else CollectionFileNames.validate(group.removePrefix("$title/").replace('/', ' '))
                    val path = "$title/$name.cbz"
                    require(files.keys.none { it.equals(path, true) }) { "Chapter names collide" }
                    files[path] = cbz
                }
            }
            directories.clear()
            files.keys.forEach { path ->
                val parts = path.split('/')
                (1 until parts.size).forEach { directories += parts.take(it).joinToString("/") }
            }
            files.values.filter { it.extension.lowercase() in setOf("cbz", "zip") }.forEach { archive ->
                java.util.zip.ZipFile(archive).use { zip ->
                    val entries = zip.entries().asSequence().toList()
                    require(entries.size <= CollectionArchivePolicy.MAX_FILES) { "Too many archive entries" }
                    CollectionArchivePolicy.validatePaths(entries.filterNot { it.isDirectory }.map { it.name }, entries.filter { it.isDirectory }.map { it.name.removeSuffix("/") })
                    var pages = 0
                    var total = 0L
                    entries.filterNot { it.isDirectory }.forEach { entry ->
                        require(entry.size in 0..128L * 1024 * 1024) { "Archive entry exceeds 128 MiB" }
                        total = Math.addExact(total, entry.size)
                        require(total <= 16L * 1024 * 1024 * 1024) { "Expanded chapter exceeds 16 GiB" }
                        val crc = java.util.zip.CRC32()
                        val buffer = ByteArray(131072)
                        var count = 0L
                        zip.getInputStream(entry).use { stream ->
                            while (true) {
                                val n = stream.read(buffer)
                                if (n < 0) break
                                count += n
                                require(count <= entry.size)
                                crc.update(buffer, 0, n)
                            }
                        }
                        require(count == entry.size && crc.value == entry.crc) { "Corrupt chapter entry: ${entry.name}" }
                        if (entry.name.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "jxl")) pages++
                    }
                    require(pages > 0) { "Archive contains no readable image pages" }
                }
            }
            require(files.keys.any { it.substringAfterLast('.').lowercase() in setOf("cbz", "zip", "jpg", "jpeg", "png", "webp", "gif", "avif", "jxl") }) { "No supported chapters or images" }
            val current = catalog(source)
            val existing = current.firstOrNull { it.url.equals(title, true) }
            require(existing == null || repair) { "Title already exists. Use repair to add missing files." }
            require(existing == null || existing.url == title) { "Title differs only by letter case" }
            val assets = files.map { (path, file) ->
                val (size, hash) = digest(file.inputStream())
                CollectionAsset("files/$path", size, hash)
            }
            val data = CollectionUserData(if (existing == null) listOf(BackupManga(source.id, title, title, favorite = false)) else emptyList(), emptyList(), emptyList(), emptyList())
            val manifest = CollectionBackupManifest(collection = source.name, createdAt = System.currentTimeMillis(), directories = directories.toList(), assets = assets, customCovers = emptyMap(), data = data)
            val bytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            require(bytes.size <= CollectionArchivePolicy.MAX_MANIFEST)
            val archive = File(work, "import.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(CollectionArchivePolicy.MANIFEST))
                zip.write(bytes)
                zip.closeEntry()
                files.entries.forEachIndexed { i, (path, file) ->
                    zip.putNextEntry(ZipEntry("files/$path"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    progress(i + 1, files.size)
                }
                zip.putNextEntry(ZipEntry(CollectionArchivePolicy.COMPLETE))
                zip.write(buildJsonObject { put("manifest_sha256", digest(bytes.inputStream()).second) }.toString().toByteArray())
                zip.closeEntry()
            }
            val plan = backup.prepare(source.id, Uri.fromFile(archive), progress)
            if (plan.conflicts.isNotEmpty()) {
                backup.discard(plan)
                error("Different content already exists. Originals preserved.\n" + plan.conflicts.joinToString("\n"))
            }
            if (compareDuplicates && !repair) {
                try {
                    val hashes = files.flatMap { (path, file) -> fingerprints(path.substringAfterLast('/')) { file.inputStream() } }
                    plan.copy(conflicts = duplicateWarnings(source, mapOf(title to hashes), current))
                } catch (error: Throwable) {
                    backup.discard(plan)
                    throw error
                }
            } else {
                plan
            }
        } finally {
            work.deleteRecursively()
        }
    }
}
