package tachiyomi.domain.source.model

data class FileMangaRecord(
    val id: Long,
    val source: Long,
    val url: String,
    val title: String,
    val version: Long,
    val modified: Long,
)

data class FileReconciliationEntry(
    val folder: String,
    val candidates: List<FileMangaRecord>,
    val movable: Boolean,
    val alreadyLinked: Boolean,
)

/** Only exact, unique relative paths may move. Titles are suggestions, never identities. */
fun planFileReconciliation(
    records: List<FileMangaRecord>,
    target: Long,
    folders: List<String>,
    occupiedOrigins: Set<Pair<Long, String>>,
): List<FileReconciliationEntry> = folders.sorted().map { folder ->
    val exact = records.filter { it.url == folder }
    val candidates = exact.ifEmpty { records.filter { it.title.equals(folder, ignoreCase = true) } }
    val single = exact.singleOrNull()
    FileReconciliationEntry(
        folder = folder,
        candidates = candidates,
        movable = single != null && single.source != target && (single.source to folder) !in occupiedOrigins,
        alreadyLinked = single?.source == target,
    )
}
