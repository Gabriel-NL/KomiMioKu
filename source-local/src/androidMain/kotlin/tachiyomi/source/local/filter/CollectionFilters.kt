package tachiyomi.source.local.filter

import android.content.Context
import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.coroutines.flow.Flow
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR

/** Exact, case-insensitive values separated by commas; exclusions take priority. */
class CollectionValues(
    context: Context,
    val field: Field,
    val excluded: Boolean,
    val loadValues: (suspend () -> List<String>)? = null,
    val observeValues: (() -> Flow<List<String>>)? = null,
) : Filter.Text(
    context.stringResource(
        when (field) {
            Field.TAGS -> if (excluded) KMR.strings.local_tags_exclude else KMR.strings.local_tags_include
            Field.AUTHORS -> if (excluded) KMR.strings.local_authors_exclude else KMR.strings.local_authors_include
        },
    ),
) {
    enum class Field {
        TAGS,
        AUTHORS,
    }

    fun selections(): Set<String> = parseCollectionValues(state)

    fun tagState(tag: String, blacklist: CollectionValues): Int =
        collectionTagState(selections(), blacklist.selections(), tag)

    fun cycleTag(tag: String, blacklist: CollectionValues) {
        val (included, excluded) = cycleCollectionTag(selections(), blacklist.selections(), tag)
        state = included.joinToString(", ")
        blacklist.state = excluded.joinToString(", ")
    }
}

/** Uses SELECT serialization to retain saved-search compatibility; rendered as three-state chips. */
class CollectionOptions(context: Context, val field: Field) : Filter.Select<String>(
    context.stringResource(
        if (field == Field.FORMAT) KMR.strings.local_collection_format else KMR.strings.local_reading_state,
    ),
    when (field) {
        Field.FORMAT -> arrayOf("Oneshot", "Multi chapter")
        Field.READING -> arrayOf(
            context.stringResource(KMR.strings.local_reading_unread),
            context.stringResource(KMR.strings.local_reading_started),
            context.stringResource(KMR.strings.local_reading_finished),
        )
    },
) {
    enum class Field { FORMAT, READING }

    val keys: List<String> = when (field) {
        Field.FORMAT -> listOf("oneshot", "multi chapter")
        Field.READING -> listOf("unread", "started", "finished")
    }

    fun optionState(index: Int): Int = collectionOptionStates(state, keys.size)[index]

    fun cycle(index: Int) {
        state = cycleCollectionOption(state, index, keys.size)
    }

    fun selections(): Pair<Set<String>, Set<String>> {
        val states = collectionOptionStates(state, keys.size)
        return keys.filterIndexed { index, _ -> states[index] == 1 }.toSet() to
            keys.filterIndexed { index, _ -> states[index] == 2 }.toSet()
    }
}
