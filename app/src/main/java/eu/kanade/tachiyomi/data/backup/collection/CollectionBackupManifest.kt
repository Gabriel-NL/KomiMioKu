package eu.kanade.tachiyomi.data.backup.collection

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.Serializable
import tachiyomi.domain.localtag.model.LocalMetadataDocument
import tachiyomi.domain.localtag.repository.CollectionExportApproval

@Serializable
data class CollectionAsset(val path: String, val size: Long, val sha256: String)

@Serializable
data class CollectionTagLink(val manga: String, val tags: List<String>)

@Serializable
data class CollectionUserData(
    val mangas: List<BackupManga>,
    val categories: List<BackupCategory>,
    val tags: List<String>,
    val links: List<CollectionTagLink>,
    val documents: List<LocalMetadataDocument> = emptyList(),
    val approvals: List<CollectionExportApproval> = emptyList(),
)

@Serializable
data class CollectionBackupManifest(
    val version: Int = 3,
    val collection: String,
    val createdAt: Long,
    val directories: List<String>,
    val assets: List<CollectionAsset>,
    val customCovers: Map<String, String>,
    val data: CollectionUserData,
)

@Serializable
data class CollectionRestoreWrite(
    val type: String,
    val path: String,
    val oldHash: String?,
    val newHash: String,
    val backup: String,
    val mangaUrl: String? = null,
)

@Serializable
data class CollectionRestoreJournal(
    val operation: String,
    val source: Long,
    val root: String,
    val writes: List<CollectionRestoreWrite>,
    val createdDirectories: List<String>,
)

internal fun remapCollectionManga(original: BackupManga, source: Long, thumbnail: String?, json: kotlinx.serialization.json.Json): BackupManga {
    val copy = json.decodeFromString(BackupManga.serializer(), json.encodeToString(BackupManga.serializer(), original))
    copy.source = source
    copy.thumbnailUrl = thumbnail
    if (copy.customThumbnailUrl?.startsWith("content:") == true || copy.customThumbnailUrl?.startsWith("file:") == true) copy.customThumbnailUrl = null
    return copy
}

internal fun CollectionUserData.approvedSubset(): CollectionUserData {
    val selected = approvals.filter { it.approved }.map { it.manga }.toSet()
    require(selected.isNotEmpty()) { "No approved manga in this collection" }
    require(selected.all { url -> mangas.any { it.url == url } }) { "Unknown approved manga" }
    val selectedMangas = mangas.filter { it.url in selected }
    val orders = selectedMangas.flatMap { it.categories }.toSet()
    return copy(
        mangas = selectedMangas,
        categories = categories.filter { it.order in orders },
        links = links.filter { it.manga in selected },
        documents = documents.filter { it.manga.isEmpty() || it.manga in selected },
        approvals = approvals.filter { it.manga in selected },
    )
}
