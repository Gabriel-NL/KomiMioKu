package tachiyomi.domain.localtag.model

data class IndexedLocalManga(
    val sourceId: Long,
    val root: String,
    val url: String,
    val title: String,
    val author: String?,
    val artist: String?,
    val description: String?,
    val genre: String?,
    val status: Long,
    val thumbnail: String?,
    val modified: Long,
    val fingerprint: String,
)

data class LocalCollectionSnapshot(val entries: List<IndexedLocalManga>, val ready: Boolean)
