package tachiyomi.source.local.filter

import tachiyomi.domain.chapter.model.Chapter

internal fun parseCollectionValues(value: String?): Set<String> = value.orEmpty()
    .split(',')
    .map { it.trim().lowercase(java.util.Locale.ROOT) }
    .filter { it.isNotEmpty() }
    .toSet()

internal fun matchesCollectionValues(values: Set<String>, included: Set<String>, excluded: Set<String>): Boolean =
    values.none { it in excluded } && (included.isEmpty() || values.any { it in included })

/** 0 = neutral, 1 = included, 2 = excluded. Exclusions take precedence. */
internal fun collectionTagState(included: Set<String>, excluded: Set<String>, tag: String): Int {
    val key = tag.trim().lowercase(java.util.Locale.ROOT)
    return when (key) {
        in excluded -> 2
        in included -> 1
        else -> 0
    }
}

internal fun cycleCollectionTag(
    included: Set<String>,
    excluded: Set<String>,
    tag: String,
): Pair<Set<String>, Set<String>> {
    val key = tag.trim().lowercase(java.util.Locale.ROOT)
    return when (collectionTagState(included, excluded, key)) {
        0 -> (included + key) to (excluded - key)
        1 -> (included - key) to (excluded + key)
        else -> (included - key) to (excluded - key)
    }
}

internal fun collectionTagNames(values: Iterable<String>): List<String> = values
    .flatMap { it.split(',') }
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinctBy { it.lowercase(java.util.Locale.ROOT) }
    .sortedWith(String.CASE_INSENSITIVE_ORDER)

/** Positive values are the old single-choice selections. New selections use negative base-3 states. */
internal fun collectionOptionStates(state: Int, count: Int): List<Int> {
    if (state >= 0) return List(count) { if (state == it + 1) 1 else 0 }
    var encoded = -state.toLong()
    val result = List(count) {
        val value = (encoded % 3).toInt()
        encoded /= 3
        value
    }
    return if (encoded == 0L) result else List(count) { 0 }
}

internal fun cycleCollectionOption(state: Int, index: Int, count: Int): Int {
    require(index in 0 until count)
    val states = collectionOptionStates(state, count).toMutableList()
    states[index] = (states[index] + 1) % 3
    var factor = 1
    var encoded = 0
    states.forEach { value ->
        encoded += value * factor
        factor *= 3
    }
    return -encoded
}

internal fun collectionReadingKey(chapters: List<Chapter>): String = when {
    chapters.isNotEmpty() && chapters.all { it.read } -> "finished"
    chapters.any { it.read || it.lastPageRead > 0 } -> "started"
    else -> "unread"
}
