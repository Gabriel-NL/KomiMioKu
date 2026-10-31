package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.CollapsibleBox
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.source.local.filter.CollectionOptions
import tachiyomi.source.local.filter.CollectionValues
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalCollectionValueFilter(
    included: CollectionValues,
    excluded: CollectionValues,
    onUpdate: () -> Unit,
) {
    var tags by remember(included) { mutableStateOf(emptyList<String>()) }
    var loading by remember(included) { mutableStateOf(true) }
    var failed by remember(included) { mutableStateOf(false) }
    var retry by remember(included) { mutableIntStateOf(0) }
    // Filter objects are mutable and StateFlow may not emit when their identity stays
    // unchanged. Keep a Compose snapshot so each tap updates its background immediately.
    var selection by remember(included, excluded) { mutableStateOf(included.state to excluded.state) }

    LaunchedEffect(included, retry) {
        try {
            included.observeValues?.invoke()?.collect { tags = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        }
    }

    LaunchedEffect(included, retry) {
        loading = true
        failed = false
        try {
            tags = included.loadValues?.invoke().orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            failed = true
        } finally {
            loading = false
        }
    }

    val selectedNames = remember(selection) { included.selections() + excluded.selections() }
    val names = remember(tags, selectedNames) {
        (tags + selectedNames)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
    }
    LocalCollectionChoices(
        heading = stringResource(
            if (included.field == CollectionValues.Field.TAGS) KMR.strings.local_collection_tags else KMR.strings.local_collection_authors,
        ),
        options = names.map { it to it },
        loading = loading,
        failed = failed,
        onRetry = { retry++ },
        stateFor = { included.tagState(it, excluded) },
        onCycle = {
            included.cycleTag(it, excluded)
            selection = included.state to excluded.state
            onUpdate()
        },
    )
}

@Composable
internal fun LocalCollectionOptionFilter(filter: CollectionOptions, onUpdate: () -> Unit) {
    var selection by remember(filter) { mutableIntStateOf(filter.state) }
    // The local snapshot ensures taps immediately redraw the mutable source filter.
    val options = remember(filter, selection) { filter.keys.mapIndexed { index, key -> key to filter.values[index] } }
    LocalCollectionChoices(
        heading = filter.name,
        options = options,
        stateFor = { filter.optionState(filter.keys.indexOf(it)) },
        onCycle = {
            filter.cycle(filter.keys.indexOf(it))
            selection = filter.state
            onUpdate()
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalCollectionChoices(
    heading: String,
    options: List<Pair<String, String>>,
    loading: Boolean = false,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
    stateFor: (String) -> Int,
    onCycle: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val dark = colors.surface.luminance() < 0.5f
    val neutralDescription = stringResource(KMR.strings.local_tag_neutral)
    val includedDescription = stringResource(KMR.strings.local_tag_included)
    val excludedDescription = stringResource(KMR.strings.local_tag_excluded)

    CollapsibleBox(heading = heading, startExpanded = false) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(stringResource(KMR.strings.local_choices_loading))
            }
            if (failed) {
                Text(stringResource(KMR.strings.local_choices_load_error))
                TextButton(onClick = onRetry) {
                    Text(stringResource(KMR.strings.local_tags_retry))
                }
            } else if (!loading && options.isEmpty()) {
                Text(stringResource(KMR.strings.local_choices_empty))
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { (key, label) ->
                    val state = stateFor(key)
                    val background = when (state) {
                        1 -> if (dark) Color(0xFF1B5E20) else Color(0xFFA5D6A7)
                        2 -> if (dark) Color(0xFF8E2424) else Color(0xFFEF9A9A)
                        else -> colors.surfaceContainerHigh
                    }
                    val description = when (state) {
                        1 -> includedDescription
                        2 -> excludedDescription
                        else -> neutralDescription
                    }
                    Surface(
                        onClick = {
                            onCycle(key)
                        },
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .semantics { stateDescription = description },
                        shape = MaterialTheme.shapes.small,
                        color = background,
                        contentColor = colors.onSurface,
                        border = BorderStroke(1.dp, colors.outlineVariant),
                    ) {
                        Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp))
                    }
                }
            }
        }
    }
}
