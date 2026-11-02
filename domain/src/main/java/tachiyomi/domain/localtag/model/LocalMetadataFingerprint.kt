package tachiyomi.domain.localtag.model

import java.security.MessageDigest

data class LocalMetadataStamp(val name: String, val modified: Long, val size: Long)

/** Unknown provider timestamps cannot prove that metadata is unchanged. */
fun localMetadataFingerprint(files: List<LocalMetadataStamp>): String {
    if (files.any { it.modified <= 0 }) return ""
    val digest = MessageDigest.getInstance("SHA-256")
    files.sortedBy { it.name }.forEach {
        digest.update("${it.name.length}:${it.name}:${it.modified}:${it.size};".toByteArray(Charsets.UTF_8))
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
