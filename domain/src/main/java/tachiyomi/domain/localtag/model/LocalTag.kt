package tachiyomi.domain.localtag.model

import java.util.Locale

data class LocalTag(
    val id: Long,
    val name: String,
    val normalizedName: String,
)

/** SQLite NOCASE is ASCII-only; normalize Unicode names in Kotlin before inserting. */
fun localTagKey(name: String): String = name.trim().lowercase(Locale.ROOT)
