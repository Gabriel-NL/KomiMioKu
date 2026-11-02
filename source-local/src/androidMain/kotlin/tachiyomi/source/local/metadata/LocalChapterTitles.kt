package tachiyomi.source.local.metadata

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import kotlinx.serialization.Serializable

const val LOCAL_CHAPTER_TITLES = ".collection-chapters.json"

@Serializable
data class LocalChapterTitle(val name: String, val number: Float, val order: Int = 0)

@Serializable
data class LocalChapterTitles(val version: Int = 1, val chapters: Map<String, LocalChapterTitle>)

/** Source lists are descending; reversing them yields the user-selected reading sequence. */
fun localChapterOrder(titles: Map<String, LocalChapterTitle>): Comparator<SChapter> = Comparator { first, second ->
    val firstOrder = titles[first.url.substringAfterLast('/')]?.order
    val secondOrder = titles[second.url.substringAfterLast('/')]?.order
    when {
        firstOrder != null && secondOrder != null -> secondOrder.compareTo(firstOrder)
        firstOrder != null -> 1
        secondOrder != null -> -1
        else -> second.name.compareToCaseInsensitiveNaturalOrder(first.name)
    }
}
