package tachiyomi.data.localtag

import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.DatabaseHandler
import tachiyomi.domain.localtag.model.LocalTag
import tachiyomi.domain.localtag.model.canonicalLocalTagName
import tachiyomi.domain.localtag.model.localTagKey
import tachiyomi.domain.localtag.repository.LocalTagRepository
import tachiyomi.domain.source.model.FileSourceIds

class LocalTagRepositoryImpl(
    private val handler: DatabaseHandler,
) : LocalTagRepository {
    override suspend fun ensureCollection(sourceId: Long) {
        require(FileSourceIds.contains(sourceId))
        handler.await { localTagCollectionsQueries.ensureCollection(sourceId) }
    }

    override suspend fun getAll(sourceId: Long): List<LocalTag> = handler.awaitList {
        localSourceTagsQueries.getSourceTags(sourceId, ::LocalTag)
    }

    override fun observeAll(sourceId: Long): Flow<List<LocalTag>> = handler.subscribeToList {
        localSourceTagsQueries.getSourceTags(sourceId, ::LocalTag)
    }

    override suspend fun addAll(names: List<String>, sourceId: Long) {
        require(FileSourceIds.contains(sourceId))
        val tags = names.map(::canonicalLocalTagName)
            .filter { it.isNotEmpty() }
            .distinctBy(::localTagKey)
        if (tags.isEmpty()) return
        handler.await(inTransaction = true) {
            localTagCollectionsQueries.ensureCollection(sourceId)
            normalizeStoredTags()
            tags.forEach { name ->
                val key = localTagKey(name)
                localTagsQueries.insertTag(name, key)
                localSourceTagsQueries.insertSourceTag(sourceId, key)
            }
        }
    }

    override suspend fun syncLocalManga(url: String, title: String, tags: List<String>?, sourceId: Long) {
        require(FileSourceIds.contains(sourceId))
        handler.await(inTransaction = true) {
            localMangaTagsQueries.ensureLocalManga(sourceId, url, title)
            val mangaId = localMangaTagsQueries.getLocalMangaId(sourceId, url).executeAsOne()
            localTagCollectionsQueries.ensureCollection(sourceId)
            if (tags != null) replaceLinks(mangaId, tags)
        }
    }

    override suspend fun replaceMangaTags(mangaId: Long, tags: List<String>) {
        handler.await(inTransaction = true) { replaceLinks(mangaId, tags) }
    }

    override suspend fun getTagsForManga(mangaId: Long): List<LocalTag> = handler.awaitList {
        localMangaTagsQueries.getTagsForManga(mangaId, ::LocalTag)
    }

    private fun Database.replaceLinks(mangaId: Long, names: List<String>) {
        val sourceId = localMangaTagsQueries.getFileMangaSource(mangaId).executeAsOneOrNull() ?: return
        normalizeStoredTags()
        val tags = names.map(::canonicalLocalTagName).filter { it.isNotEmpty() }.distinctBy(::localTagKey)
        localMangaTagsQueries.clearMangaTags(mangaId)
        tags.forEach { name ->
            val key = localTagKey(name)
            localTagsQueries.insertTag(name, key)
            localSourceTagsQueries.insertSourceTag(sourceId, key)
            localMangaTagsQueries.insertMangaTag(mangaId, key)
        }
    }

    /** Rename in place when possible; merge every existing link when aliases collide. */
    private fun Database.normalizeStoredTags() {
        val rows = localTagsQueries.getAllTags(::LocalTag).executeAsList()
        val byKey = rows.associateBy { it.normalizedName }.toMutableMap()
        rows.forEach { old ->
            val name = canonicalLocalTagName(old.name)
            val key = localTagKey(name)
            if (old.name == name && old.normalizedName == key) return@forEach
            val target = byKey[key]
            if (target != null && target.id != old.id) {
                localSourceTagsQueries.mergeSourceTags(target.id, old.id)
                localMangaTagsQueries.mergeTagLinks(target.id, old.id)
                localMangaTagsQueries.clearTagLinks(old.id)
                localTagsQueries.deleteTag(old.id)
                byKey.remove(old.normalizedName)
                localTagsQueries.updateTagName(name, key, target.id)
                byKey[key] = target.copy(name = name, normalizedName = key)
            } else {
                localTagsQueries.updateTagName(name, key, old.id)
                byKey.remove(old.normalizedName)
                byKey[key] = old.copy(name = name, normalizedName = key)
            }
        }
    }
}
