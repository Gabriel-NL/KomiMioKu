package tachiyomi.source.local.metadata

import tachiyomi.core.metadata.comicinfo.ComicInfo
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.source.local.filter.collectionTagNames

/** Replace the combined editable tag list without touching bibliographic fields. */
internal fun ComicInfo.withCollectionTags(tags: List<String>): ComicInfo = copy(
    genre = collectionTagNames(collectionTagNames(tags).map(::canonicalLocalTagName))
        .takeIf { it.isNotEmpty() }?.let { ComicInfo.Genre(it.joinToString(", ")) },
    tags = null,
    categories = null,
)
