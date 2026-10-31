package tachiyomi.domain.localtag.model

import java.text.Normalizer
import java.util.Locale

private fun languageAliasKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").trim().lowercase(Locale.ROOT)

private val languageNames: Map<String, String> by lazy {
    buildMap {
        Locale.getAvailableLocales().filter { it.language.isNotEmpty() }.forEach { locale ->
            val language = Locale.forLanguageTag(locale.language)
            val english = language.getDisplayLanguage(Locale.ENGLISH)
            listOf(locale.language, language.getDisplayLanguage(locale), english).forEach { alias ->
                put(languageAliasKey(alias), english)
            }
        }
        mapOf(
            "ingles" to "English", "japones" to "Japanese", "chines" to "Chinese",
            "portugues" to "Portuguese", "espanhol" to "Spanish", "frances" to "French",
            "alemao" to "German", "coreano" to "Korean", "russo" to "Russian",
            "italiano" to "Italian", "arabe" to "Arabic",
        ).forEach { (alias, name) -> put(alias, name) }
    }
}

/** Sentence case, including acronyms; language labels use the English language name. */
fun canonicalLocalTagName(name: String): String {
    val trimmed = name.trim()
    val language = trimmed.replace(Regex("^(?:idioma|language|lang)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
    val label = languageNames[languageAliasKey(language)] ?: trimmed
    val lower = (if (label.equals("netorase", ignoreCase = true)) "Nts" else label).lowercase(Locale.ROOT)
    return lower.replaceFirstChar { it.uppercase(Locale.ROOT) }
}
