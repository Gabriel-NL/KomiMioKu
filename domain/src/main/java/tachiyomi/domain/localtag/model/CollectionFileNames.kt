package tachiyomi.domain.localtag.model

/** Portable names shared by UI, file operations and their tests. Never silently rewrite a title. */
object CollectionFileNames {
    fun validate(value: String): String {
        require(value.isNotBlank() && value == value.trim() && value.length <= 160) { "Use a name with 1-160 characters, without surrounding spaces" }
        require(value != "." && value != ".." && !value.startsWith('.') && !value.endsWith('.')) { "Hidden and relative names are not allowed" }
        require(value.none { it.code < 32 || it in "/\\:*?\"<>|" }) { "The name contains a character unsupported by file systems" }
        require(value.substringBefore('.').uppercase() !in setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }) { "Reserved file name" }
        return value
    }

    fun chapterFile(title: String, original: String, directory: Boolean): String {
        validate(title)
        return if (directory || !original.contains('.')) title else "$title.${original.substringAfterLast('.')}"
    }

    fun numberedChapter(index: Int, title: String, original: String, directory: Boolean): String {
        require(index > 0)
        val prefix = index.toString().padStart(6, '0')
        val safeTitle = title.map { if (it.code < 32 || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trim('.').take(110).ifBlank { "Chapter" }
        return chapterFile("$prefix - $safeTitle", original, directory)
    }
}
