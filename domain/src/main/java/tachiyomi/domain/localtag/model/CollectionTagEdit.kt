package tachiyomi.domain.localtag.model

data class CollectionTagEdit(val name: String, val add: Boolean)

/** Stable set edits: unrelated tags survive and retries do not duplicate additions. */
fun applyCollectionTagEdits(existing: List<String>, edits: List<CollectionTagEdit>): List<String> {
    val names = existing.map(::canonicalLocalTagName).filter(String::isNotBlank).associateBy(::localTagKey).toMutableMap()
    edits.forEach { edit ->
        val name = canonicalLocalTagName(edit.name)
        if (name.isNotBlank()) {
            val key = localTagKey(name)
            if (edit.add) names.putIfAbsent(key, name) else names.remove(key)
        }
    }
    return names.values.toList()
}
