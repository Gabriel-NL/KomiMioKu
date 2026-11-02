package tachiyomi.domain.localtag.model

import java.text.Normalizer
import java.util.Locale

/** Pure planning shared by the manager preview and its application. */
object MangaManagerRules {
    data class Formatted(val title: String, val credits: List<String>, val tags: List<String>)
    private val languageLabels by lazy { Locale.getISOLanguages().map { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }.toSet() }

    fun format(original: String): Formatted {
        val languages = mutableListOf<String>()
        var text = Regex("\\[([^\\[\\]]+)\\]").replace(original.trim()) { match ->
            val tag = canonicalLocalTagName(match.groupValues[1])
            if (tag.lowercase(Locale.ROOT) in languageLabels) {
                languages += tag
                ""
            } else {
                match.value
            }
        }.trim()
        text = text.replace(Regex("\\(\\s*English\\s*\\)", RegexOption.IGNORE_CASE)) {
            languages += "English"
            ""
        }
        text = text.replace(Regex("\\s+[-–—]\\s*English(?=\\s*$|\\s+(?:AI\\s+)?TRANSLATED\\b)", RegexOption.IGNORE_CASE)) {
            languages += "English"
            ""
        }.trim()
        val credits = mutableListOf<String>()
        val match = Regex("^(\\([^)]*\\)\\s*)?\\[([^\\[\\]]+)\\]\\s*(.+)$").matchEntire(text)
        if (match != null) {
            val credit = match.groupValues[2].trim()
            if (!Regex("(?i)^(unknown author|unknown|anonymous|various|translated|digital|fanbox|decensored|uncensored|mtl|nhentai[-_ ]\\d+|c\\d+)$").matches(credit)) {
                val pair = Regex("^(.+?)\\s*\\(([^()]+)\\)$").matchEntire(credit)
                credits += (pair?.groupValues?.get(2) ?: credit).split(',').map(String::trim).filter(String::isNotEmpty)
                pair?.let { credits += it.groupValues[1].trim() }
                text = match.groupValues[1] + match.groupValues[3]
            }
        }
        text = text.replace(Regex("\\s{2,}"), " ").trim()
        CollectionFileNames.validate(text)
        return Formatted(text, credits.distinct(), languages.distinct())
    }

    fun naturalKey(value: String): String = Regex("\\d+").replace(value.lowercase(Locale.ROOT)) { it.value.padStart(20, '0') }

    fun words(title: String): List<String> {
        var text = Normalizer.normalize(title, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
        text = text.replace(Regex("\\[[^]]*]"), " ")
            .replace(Regex("\\((?:c\\d+|comiket\\s*\\d+|futaket\\s*\\d+)\\)"), " ")
            .replace(Regex("\\b(?:chapters?|chap|ch|capitulos?|cap|volumes?|vol|parts?|parte|pt)\\.?\\s*[-:#]?\\s*\\d+(?:\\.\\d+)?(?:\\s*[-~]\\s*\\d+)?\\b", RegexOption.IGNORE_CASE), " ")
        return Regex("\\p{L}+").findAll(text).map { it.value }.toList()
    }
    fun commonPhrase(a: List<String>, b: List<String>): String {
        var previous = IntArray(b.size + 1)
        var length = 0
        var end = 0
        a.indices.forEach { i ->
            val next = IntArray(b.size + 1)
            b.indices.forEach { j ->
                if (a[i] == b[j]) {
                    next[j + 1] = previous[j] + 1
                    if (next[j + 1] > length) {
                        length = next[j + 1]
                        end = i + 1
                    }
                }
            }
            previous = next
        }
        return a.subList(end - length, end).joinToString(" ")
    }
}
