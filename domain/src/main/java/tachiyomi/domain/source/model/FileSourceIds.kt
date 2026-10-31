package tachiyomi.domain.source.model

/** Persistent built-in IDs; do not renumber after release. */
object FileSourceIds {
    const val LOCAL = 0L
    const val PRIVATE_COLLECTION = 1000000000001L
    private const val CUSTOM_SOURCE_PREFIX = 2000000000000L

    fun getCustomSourceId(folderName: String): Long {
        val hash = folderName.lowercase().hashCode().toLong() and 0x7FFFFFFF
        return CUSTOM_SOURCE_PREFIX + hash
    }

    fun contains(id: Long): Boolean = id == LOCAL || id == PRIVATE_COLLECTION || id >= CUSTOM_SOURCE_PREFIX
}
