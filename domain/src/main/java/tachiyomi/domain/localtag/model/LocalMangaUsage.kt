package tachiyomi.domain.localtag.model

/** SQLite statistics use the existing collection document transport, without physical sidecars. */
object LocalMangaUsage {
    const val BACKUP_PATH = "_komimioku/open-count.json"

    fun document(manga: String, count: Long): LocalMetadataDocument {
        require(manga.isNotBlank() && count >= 0)
        return LocalMetadataDocument(manga, BACKUP_PATH, "utf8", count.toString())
    }

    fun decode(document: LocalMetadataDocument): Long {
        require(document.path == BACKUP_PATH && document.manga.isNotBlank() && document.format == "utf8") { "Invalid manga opening counter" }
        require(document.content.matches(Regex("0|[1-9][0-9]{0,18}"))) { "Invalid manga opening count" }
        return requireNotNull(document.content.toLongOrNull()) { "Manga opening count overflow" }
    }

    fun sum(counts: List<Long>): Long = counts.fold(0L) { total, value ->
        require(value >= 0)
        if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
    }
}
