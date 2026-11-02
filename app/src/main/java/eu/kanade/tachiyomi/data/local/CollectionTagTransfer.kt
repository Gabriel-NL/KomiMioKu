package eu.kanade.tachiyomi.data.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.model.LocalTag
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.localtag.service.CollectionWorkbook
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.metadata.MAX_TAG_CATALOG_BYTES
import tachiyomi.source.local.metadata.decodeCollectionTagCatalog
import java.io.InputStream
import java.nio.ByteBuffer

class CollectionTagTransfer(
    private val handler: DatabaseHandler,
    private val tags: LocalTagRepository,
    private val sources: SourceManager,
) {
    data class Snapshot(
        val source: Long,
        val name: String,
        val tags: List<LocalTag>,
        val mangas: List<List<String>>,
        val links: List<List<String>>,
    ) {
        fun sheets(prefix: String = ""): List<CollectionWorkbook.Sheet> = listOf(
            CollectionWorkbook.Sheet("${prefix}Collection", listOf(listOf("source_id", "name"), listOf(source.toString(), name))),
            CollectionWorkbook.Sheet("${prefix}Tags", listOf(listOf("tag_id", "name", "normalized_name")) + tags.map { listOf(it.id.toString(), it.name, it.normalizedName) }),
            CollectionWorkbook.Sheet("${prefix}Mangas", listOf(listOf("manga_id", "source_id", "relative_path", "title")) + mangas),
            CollectionWorkbook.Sheet("${prefix}Relations", listOf(listOf("manga_id", "relative_path", "manga_title", "tag_id", "tag_name")) + links),
        )

        fun catalogJson(): String = buildJsonObject {
            put("version", 1)
            put("tags", JsonArray(tags.map { JsonPrimitive(it.name) }))
        }.toString().also { decodeCollectionTagCatalog(it) }
    }

    data class LegacyPlan(val target: Long, val targetName: String, val before: List<Snapshot>) {
        val linkCount get() = before.sumOf { it.links.size }
        val tagCount get() = before.flatMap { it.tags }.distinctBy { it.id }.size
        fun sheets(): List<CollectionWorkbook.Sheet> =
            listOf(
                CollectionWorkbook.Sheet(
                    "Recovery",
                    listOf(
                        listOf("operation", "target_id", "target_name"),
                        listOf("legacy-tags-to-collection", target.toString(), targetName),
                        listOf("The following sheets back up legacy tag catalogs and manga-tag links. This is not a full library backup."),
                    ),
                ),
            ) + before.flatMapIndexed { i, snapshot -> snapshot.sheets("Old${i + 1}") }
    }

    private fun target(source: Long): LocalSource =
        sources.get(source) as? LocalSource ?: error("Collection unavailable")

    private fun Database.snapshot(source: Long, name: String): Snapshot = Snapshot(
        source,
        name,
        localSourceTagsQueries.getSourceTags(source, ::LocalTag).executeAsList(),
        localTagCollectionsQueries.exportMangas(source) { id, sourceId, url, title ->
            listOf(id.toString(), sourceId.toString(), url, title)
        }.executeAsList(),
        localTagCollectionsQueries.exportLinks(source) { mangaId, url, title, tagId, tagName ->
            listOf(mangaId.toString(), url, title, tagId.toString(), tagName)
        }.executeAsList(),
    )

    suspend fun snapshot(source: Long): Snapshot {
        val name = target(source).name
        return handler.await(inTransaction = true) { snapshot(source, name) }
    }

    suspend fun importCatalog(source: Long, input: InputStream): Int {
        target(source)
        val bytes = input.use { stream ->
            val buffer = ByteArray(MAX_TAG_CATALOG_BYTES + 1)
            var size = 0
            while (size < buffer.size) {
                val count = stream.read(buffer, size, buffer.size - size)
                if (count < 0) break
                if (count == 0) continue
                size += count
            }
            require(size <= MAX_TAG_CATALOG_BYTES) { "Tag catalog exceeds 1 MiB" }
            buffer.copyOf(size)
        }
        val names = decodeCollectionTagCatalog(Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString())
        tags.addAll(names, source)
        return names.size
    }

    suspend fun previewLegacy(target: Long): LegacyPlan {
        val collection = target(target)
        require(collection.name.equals("NSFW", ignoreCase = true)) { "Choose NSFW as destination" }
        require(target != FileSourceIds.LOCAL && target != FileSourceIds.PRIVATE_COLLECTION)
        return handler.await(inTransaction = true) {
            LegacyPlan(
                target,
                collection.name,
                listOf(
                    snapshot(FileSourceIds.LOCAL, "Local"),
                    snapshot(FileSourceIds.PRIVATE_COLLECTION, "Legacy private collection"),
                ),
            )
        }
    }

    suspend fun applyLegacy(plan: LegacyPlan) {
        require(target(plan.target).name == plan.targetName) { "Collection changed. Analyze again." }
        handler.await(inTransaction = true) {
            val current = plan.before.map { snapshot(it.source, it.name) }
            check(current == plan.before) { "Tags changed. Analyze again." }
            localTagCollectionsQueries.ensureCollection(plan.target)
            localTagCollectionsQueries.copyLegacyCatalog(plan.target)
            localTagCollectionsQueries.clearLegacyLinks()
            localTagCollectionsQueries.clearLegacyCatalog()
        }
    }
}
