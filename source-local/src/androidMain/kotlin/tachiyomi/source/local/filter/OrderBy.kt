package tachiyomi.source.local.filter

import android.content.Context
import eu.kanade.tachiyomi.source.model.Filter
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR

sealed class OrderBy(context: Context, selection: Selection) : Filter.Sort(
    context.stringResource(MR.strings.local_filter_order_by),
    arrayOf(context.stringResource(MR.strings.title), context.stringResource(MR.strings.date), context.stringResource(KMR.strings.local_sort_open_count)),
    selection,
) {
    class Popular(context: Context) : OrderBy(context, Selection(0, true))
    class Latest(context: Context) : OrderBy(context, Selection(1, false))
}
