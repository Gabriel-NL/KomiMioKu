package eu.kanade.presentation.more.settings.screen

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StorageManager.Companion.isAccessibleDirectory
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsPrivateCollectionScreen : SearchableSettings {
    @Suppress("unused")
    private fun readResolve(): Any = SettingsPrivateCollectionScreen

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = KMR.strings.private_collection_name

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val storage = remember { Injekt.get<StorageManager>() }
        val preference = remember { Injekt.get<StoragePreferences>().privateCollectionDirectory() }
        val selected by preference.collectAsState()
        var folderRevision by remember { mutableIntStateOf(0) }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    val directory = UniFile.fromUri(context, uri)
                    if (directory?.isAccessibleDirectory != true) {
                        context.toast(KMR.strings.private_collection_folder_error)
                    } else {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                        preference.set(uri.toString())
                        folderRevision++
                        (Injekt.get<SourceManager>().get(LocalSource.PRIVATE_COLLECTION_ID) as? LocalSource)?.preloadCollectionMetadata()
                    }
                } catch (_: SecurityException) {
                    context.toast(KMR.strings.private_collection_folder_error)
                } catch (_: IllegalArgumentException) {
                    context.toast(KMR.strings.private_collection_folder_error)
                }
            }
        }
        val path = remember(selected, folderRevision) { storage.getPrivateCollectionDirectory()?.displayablePath }
            ?: stringResource(KMR.strings.private_collection_folder_missing)

        return persistentListOf(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(KMR.strings.private_collection_folder),
                subtitle = path,
                onClick = {
                    try {
                        picker.launch(selected.takeIf { it.isNotBlank() }?.toUri())
                    } catch (_: ActivityNotFoundException) {
                        context.toast(KMR.strings.private_collection_folder_error)
                    }
                },
            ),
            Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.private_collection_layout)),
            Preference.PreferenceItem.InfoPreference(stringResource(KMR.strings.private_collection_folder_summary)),
        )
    }
}
