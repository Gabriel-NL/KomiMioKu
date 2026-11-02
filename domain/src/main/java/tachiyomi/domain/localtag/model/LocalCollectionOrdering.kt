package tachiyomi.domain.localtag.model

/** Indices are persisted in saved source filters: append new choices, never reorder them. */
enum class LocalCollectionOrder { TITLE, MODIFIED, OPEN_COUNT }

fun sortLocalCollection(entries: List<IndexedLocalManga>, order: LocalCollectionOrder, ascending: Boolean, openCounts: Map<String, Long> = emptyMap()): List<IndexedLocalManga> {
    val title = compareBy(String.CASE_INSENSITIVE_ORDER, IndexedLocalManga::title).thenBy { it.url }
    val primary = when (order) {
        LocalCollectionOrder.TITLE -> title
        LocalCollectionOrder.MODIFIED -> compareBy(IndexedLocalManga::modified)
        LocalCollectionOrder.OPEN_COUNT -> compareBy { openCounts[it.url] ?: 0L }
    }
    return entries.sortedWith((if (ascending) primary else primary.reversed()).then(title))
}
