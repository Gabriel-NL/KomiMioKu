package tachiyomi.source.local.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.domain.localtag.model.localTagKey

const val COLLECTION_TAG_CATALOG = ".tags.json"
const val MAX_TAG_CATALOG_BYTES = 1024 * 1024

/** Portable additions to the tag database; never replaces tags or manga links. */
internal fun decodeCollectionTagCatalog(text: String): List<String> {
    require(text.toByteArray(Charsets.UTF_8).size <= MAX_TAG_CATALOG_BYTES) { "Tag catalog is too large" }
    val catalog = Json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
    require(catalog != null && catalog.keys == setOf("version", "tags")) { "Invalid tag catalog" }
    val version = catalog["version"] as? JsonPrimitive
    require(version != null && !version.isString && version.intOrNull == 1) { "Unsupported tag catalog version" }
    val tags = catalog["tags"] as? JsonArray
    require(tags != null && tags.size <= 10000) { "Invalid catalog tags" }
    return tags.map { element ->
        val tag = element as? JsonPrimitive
        require(tag != null && tag.isString) { "Tag must be a string" }
        tag.content.trim().also {
            require(it.isNotEmpty() && it.length <= 128 && ',' !in it && it.none(Char::isISOControl)) {
                "Invalid catalog tag"
            }
        }
    }.map(::canonicalLocalTagName).distinctBy(::localTagKey)
}
