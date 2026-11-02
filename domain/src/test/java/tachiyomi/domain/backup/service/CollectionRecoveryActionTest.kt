package tachiyomi.domain.backup.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectionRecoveryActionTest {
    @Test
    fun `untouched old files and uncreated new files are left alone`() {
        assertEquals(CollectionRecoveryAction.KEEP, collectionRecoveryAction("old", "new", "old"))
        assertEquals(CollectionRecoveryAction.KEEP, collectionRecoveryAction(null, "new", null))
    }

    @Test
    fun `only the operation's new contents may be removed or restored`() {
        assertEquals(CollectionRecoveryAction.REMOVE_NEW, collectionRecoveryAction(null, "new", "new"))
        assertEquals(CollectionRecoveryAction.RESTORE_OLD, collectionRecoveryAction("old", "new", "new"))
    }

    @Test
    fun `interruption between moving the original and promoting replacement restores original`() {
        assertEquals(CollectionRecoveryAction.RESTORE_OLD, collectionRecoveryAction("old", "new", null))
    }

    @Test
    fun `post interruption user edits are preserved as conflicts`() {
        assertEquals(CollectionRecoveryAction.CONFLICT, collectionRecoveryAction("old", "new", "user edit"))
        assertEquals(CollectionRecoveryAction.CONFLICT, collectionRecoveryAction(null, "new", "user edit"))
    }
}
