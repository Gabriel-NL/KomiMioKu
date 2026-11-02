package eu.kanade.tachiyomi.data.local

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.source.model.FileMangaRecord
import tachiyomi.domain.source.model.FileReconciliationEntry
import tachiyomi.domain.source.model.FileSourceIds
import tachiyomi.domain.source.model.planFileReconciliation
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource

class CollectionReconciler(private val handler: DatabaseHandler, private val sources: SourceManager) {
    data class Plan(
        val target: Long,
        val root: String,
        val folders: List<String>,
        val records: List<FileMangaRecord>,
        val occupied: Set<Pair<Long, String>>,
        val sourceIds: Set<Long>,
        val entries: List<FileReconciliationEntry>,
    ) {
        val moves get() = entries.filter { it.movable }.map { it.candidates.single() }

        // Only source is changed, so these old associations are sufficient to undo this operation.
        fun recoveryJson(): String = buildJsonObject {
            put("version", 1)
            put("operation", "file-source-reconciliation")
            put("createdAt", System.currentTimeMillis())
            put("targetSource", target)
            put("root", root)
            put("instructions", "Source-only recovery journal. Restore oldSource for each unchanged mangaId/url in one transaction. Manga IDs, chapters, favorites and tag links are not replaced. Added destination catalog entries may remain.")
            put(
                "records",
                JsonArray(
                    moves.map { row ->
                        buildJsonObject {
                            put("mangaId", row.id)
                            put("oldSource", row.source)
                            put("url", row.url)
                            put("title", row.title)
                        }
                    },
                ),
            )
        }.toString()
    }

    suspend fun preview(target: Long): Plan {
        val source = sources.get(target) as? LocalSource ?: error("Collection unavailable")
        val root = source.getCollectionDirectory() ?: error("Collection folder unavailable")
        check(root.isDirectory && root.canRead()) { "Collection folder unavailable" }
        val folders = checkNotNull(root.listFiles()) { "Cannot list collection folder" }
            .filter { it.isDirectory && it.name?.startsWith('.') == false }
            .mapNotNull { it.name }.sorted()
        val occupied = sources.getAll().filterIsInstance<LocalSource>().filter { it.id != target }
            .flatMap { origin ->
                origin.getCollectionDirectory()?.listFiles().orEmpty()
                    .filter { it.isDirectory }.mapNotNull { file -> file.name?.let { origin.id to it } }
            }.toSet()
        val sourceIds = sources.getAll().filterIsInstance<LocalSource>().map { it.id }.toSet() +
            setOf(FileSourceIds.LOCAL, FileSourceIds.PRIVATE_COLLECTION)
        val records = handler.awaitList {
            fileReconciliationQueries.getRecords(::FileMangaRecord)
        }.filter { it.source in sourceIds }
        return Plan(
            target,
            root.uri.toString(),
            folders,
            records,
            occupied,
            sourceIds,
            planFileReconciliation(records, target, folders, occupied),
        )
    }

    suspend fun apply(plan: Plan) {
        check(preview(plan.target) == plan) { "Collection or database changed. Analyze again." }
        handler.await(inTransaction = true) {
            check(fileReconciliationQueries.getRecords(::FileMangaRecord).executeAsList().filter { it.source in plan.sourceIds } == plan.records) {
                "Database changed. Analyze again."
            }
            plan.moves.forEach { row ->
                fileReconciliationQueries.moveRecord(plan.target, row.id, row.source, row.url)
                fileReconciliationQueries.copyCatalog(plan.target, row.id)
            }
        }
    }
}
