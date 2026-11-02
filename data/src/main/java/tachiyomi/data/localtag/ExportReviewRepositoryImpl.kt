package tachiyomi.data.localtag

import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.repository.CollectionExportApproval
import tachiyomi.domain.localtag.repository.ExportReviewRepository

class ExportReviewRepositoryImpl(private val handler: DatabaseHandler) : ExportReviewRepository {
    override suspend fun approvals(source: Long) = handler.awaitList {
        localExportReviewQueries.getApprovals(source) { url, approved -> CollectionExportApproval(url, approved != 0L) }
    }
    override suspend fun setApproved(source: Long, ids: List<Long>, approved: Boolean) {
        require(ids.isNotEmpty() && ids.distinct().size == ids.size)
        handler.await(inTransaction = true) {
            check(localExportReviewQueries.getSourceIds(source, ids).executeAsList().toSet() == ids.toSet()) { "Selected manga changed collection" }
            ids.forEach { if (approved) localExportReviewQueries.approve(it) else localExportReviewQueries.revoke(it) }
        }
    }
    override suspend fun importApprovals(source: Long, values: List<CollectionExportApproval>) {
        require(values.map { it.manga }.distinct().size == values.size)
        handler.await(inTransaction = true) {
            val ids = values.map { value ->
                val records = localExportReviewQueries.getId(source, value.manga).executeAsList()
                check(records.size == 1) { "Manga unavailable or ambiguous: ${value.manga}" }
                records.single() to value.approved
            }
            ids.forEach { (id, approved) -> if (approved) localExportReviewQueries.approve(id) else localExportReviewQueries.revoke(id) }
        }
    }
}
