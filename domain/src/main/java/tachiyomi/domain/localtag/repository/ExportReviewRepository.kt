package tachiyomi.domain.localtag.repository

import kotlinx.serialization.Serializable

@Serializable
data class CollectionExportApproval(val manga: String, val approved: Boolean)

@Serializable
data class CollectionApprovalImport(val version: Int = 1, val collection: String, val approvedPaths: List<String>)

interface ExportReviewRepository {
    suspend fun approvals(source: Long): List<CollectionExportApproval>
    suspend fun setApproved(source: Long, ids: List<Long>, approved: Boolean)
    suspend fun importApprovals(source: Long, values: List<CollectionExportApproval>)
}

fun parseCollectionApprovalImport(text: String): CollectionApprovalImport {
    require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Approval import exceeds 1 MiB" }
    val value = kotlinx.serialization.json.Json.decodeFromString<CollectionApprovalImport>(text.removePrefix("\uFEFF"))
    require(value.version == 1 && value.collection.isNotBlank() && value.approvedPaths.size <= 10000)
    require(value.approvedPaths.all { it.isNotBlank() && it !in setOf(".", "..") && '/' !in it && '\\' !in it && it.none(Char::isISOControl) })
    require(value.approvedPaths.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size == value.approvedPaths.size)
    return value
}
