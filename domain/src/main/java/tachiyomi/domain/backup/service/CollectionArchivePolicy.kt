package tachiyomi.domain.backup.service

import java.util.Locale

object CollectionArchivePolicy {
    const val MANIFEST = "manifest.json"
    const val COMPLETE = "complete.json"
    const val MAX_MANIFEST = 32 * 1024 * 1024
    const val MAX_FILES = 250000

    fun path(value: String): String {
        require(value.isNotEmpty() && value.length <= 4096 && !value.startsWith('/') && '\\' !in value && !Regex("^[A-Za-z]:").containsMatchIn(value) && value.none { it.isISOControl() }) { "Unsafe backup path" }
        require(value.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "Unsafe backup path" }
        return value
    }

    fun validatePaths(files: List<String>, directories: List<String>) {
        require(files.size <= MAX_FILES && directories.size <= MAX_FILES) { "Too many backup entries" }
        val seen = mutableSetOf<String>()
        files.forEach { require(seen.add(path(it).lowercase(Locale.ROOT))) { "Duplicate or ambiguous path" } }
        val spellings = mutableMapOf<String, String>()
        (files + directories).forEach { value ->
            val parts = value.split('/')
            parts.indices.forEach { index ->
                val prefix = parts.take(index + 1).joinToString("/")
                val previous = spellings.putIfAbsent(prefix.lowercase(Locale.ROOT), prefix)
                require(previous == null || previous == prefix) { "Ambiguous path casing" }
                require(spellings.size <= MAX_FILES * 2) { "Too many path components" }
            }
        }
        val dirs = mutableSetOf<String>()
        directories.forEach {
            val key = path(it).lowercase(Locale.ROOT)
            require(dirs.add(key) && key !in seen) { "File/directory collision" }
        }
        (files + directories).forEach { file ->
            var parent = file.substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                require(parent.lowercase(Locale.ROOT) !in seen) { "File used as directory" }
                parent = parent.substringBeforeLast('/', "")
            }
        }
    }
}
