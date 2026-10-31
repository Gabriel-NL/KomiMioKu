package tachiyomi.source.local

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import tachiyomi.source.local.image.LocalCoverManager
import tachiyomi.source.local.io.LocalSourceFileSystem

/** An independently registered file source with its own filesystem, cache and persistent ID. */
class PrivateCollectionSource(
    context: Context,
    fileSystem: LocalSourceFileSystem,
    coverManager: LocalCoverManager,
    allowHiddenFiles: () -> Boolean,
    applicationScope: CoroutineScope,
) : LocalSource(context, fileSystem, coverManager, allowHiddenFiles, applicationScope, LocalSource.PRIVATE_COLLECTION_ID)
