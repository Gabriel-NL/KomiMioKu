package tachiyomi.domain.localtag.model

import kotlinx.serialization.Serializable

@Serializable
data class LocalMetadataDocument(val manga: String, val path: String, val format: String, val content: String)

data class LocalMangaMetadata(val title: String, val author: String?, val artist: String?, val description: String?, val tags: List<String>, val status: Long, val thumbnail: String? = null)
data class LocalChapterMetadata(val url: String, val name: String, val number: Double, val order: Long, val scanlator: String?, val uploaded: Long)
data class LocalDatabaseMetadata(val id: Long, val fields: LocalMangaMetadata, val chapters: List<LocalChapterMetadata>, val imported: Boolean)

/** Explicit empty tags on an initialized record are meaningful and must never be resurrected. */
fun mergeLegacyLocalMetadata(current: LocalMangaMetadata, incoming: LocalMangaMetadata, initialized: Boolean): LocalMangaMetadata =
    if (initialized) {
        current
    } else {
        current.copy(
            title = current.title.ifBlank { incoming.title },
            author = current.author?.takeIf { it.isNotBlank() } ?: incoming.author,
            artist = current.artist?.takeIf { it.isNotBlank() } ?: incoming.artist,
            description = current.description?.takeIf { it.isNotBlank() } ?: incoming.description,
            tags = current.tags.ifEmpty { incoming.tags },
            status = current.status.takeIf { it != 0L } ?: incoming.status,
            thumbnail = current.thumbnail ?: incoming.thumbnail,
        )
    }

/** Removal is allowed only after a committed import and an exact byte-for-byte match. */
fun canRemoveLegacyMetadata(imported: Boolean, document: LocalMetadataDocument?, bytes: ByteArray): Boolean {
    if (!imported || document == null) return false
    val original = when (document.format) {
        "utf8" -> document.content.toByteArray(Charsets.UTF_8)
        "base64" -> java.util.Base64.getDecoder().decode(document.content)
        else -> return false
    }
    return original.contentEquals(bytes)
}
