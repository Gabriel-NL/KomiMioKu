package tachiyomi.domain.backup.service

enum class CollectionRecoveryAction { KEEP, REMOVE_NEW, RESTORE_OLD, CONFLICT }

/** A file edited after interruption must never be overwritten by recovery. */
fun collectionRecoveryAction(oldHash: String?, newHash: String, currentHash: String?): CollectionRecoveryAction = when {
    currentHash == oldHash -> CollectionRecoveryAction.KEEP
    currentHash != null && currentHash != newHash -> CollectionRecoveryAction.CONFLICT
    oldHash == null -> CollectionRecoveryAction.REMOVE_NEW
    else -> CollectionRecoveryAction.RESTORE_OLD
}
