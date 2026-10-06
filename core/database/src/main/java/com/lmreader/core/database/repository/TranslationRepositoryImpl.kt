package com.lmreader.core.database.repository

import androidx.room.withTransaction
import com.lmreader.core.database.LmReaderDatabase
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.entity.MangaGlossaryEntity
import com.lmreader.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A chapter has one translation regardless of the language chosen for its next task. */
internal class TranslationRepositoryImpl(private val database: LmReaderDatabase) : TranslationRepository {
    private val dao = database.translationDao()
    private val shelf = ShelfRepositoryImpl(database)
    override suspend fun chapterTranslations(mangaId: String): Map<String, ChapterTranslation> =
        dao.byManga(mangaId).associate { it.chapterId to it.toDomain() }

    override suspend fun enqueue(mangaId: String, chapterIds: List<String>, request: TranslationRequest): Int {
        if (chapterIds.isEmpty()) return 0
        require(translationSetupComplete(request.sourceLanguage, request.targetLanguage, request.autoDetectSource))
        require(!request.configSnapshot.isNullOrBlank()) { "翻译工作流配置不能为空" }
        return database.withTransaction {
            val states = dao.byChapters(chapterIds).associateBy { it.chapterId }
            val rows = chapterIds.distinct().mapNotNull { id ->
                val old = states[id]
                if (old != null && old.state != TranslationState.CANCELLED.name) return@mapNotNull null
                ChapterTranslationEntity(id, mangaId, request.targetLanguage, TranslationState.PENDING.name,
                    request.sourceLanguage, false, request.configSnapshot, request.at, old?.translatedAt,
                    old?.translatedCount ?: 0, null, request.at)
            }
            dao.upsertAll(rows)
            // 同一事务收藏，队列开始运行前书架即可看到；重复入队也不改已有分类。
            shelf.ensureOnShelf(mangaId)
            rows.size
        }
    }

    /** The caller removes page JSON under the queue's page lock before deleting these records. */
    override suspend fun clearTranslations(mangaId: String, chapterIds: List<String>): Int {
        dao.deleteChapters(chapterIds)
        return chapterIds.size
    }
    override fun observePendingCount(): Flow<Int> = dao.observePending().map { it.size }
    override suspend fun glossary(mangaId: String) = dao.glossary(mangaId).map {
        GlossaryEntry(it.mangaId, it.source, it.target, it.manual, it.updatedAt)
    }
    override suspend fun upsertGlossary(entry: GlossaryEntry) {
        val source = entry.source.trim()
        val target = entry.target.trim()
        if (source.isEmpty() || target.isEmpty()) return
        dao.insertGlossary(listOf(MangaGlossaryEntity(entry.mangaId, source, target, entry.manual, entry.updatedAt)))
    }
    override suspend fun editGlossary(originalSource: String, entry: GlossaryEntry) {
        require(entry.source.isNotBlank() && entry.target.isNotBlank())
        dao.editGlossary(originalSource, MangaGlossaryEntity(entry.mangaId, entry.source.trim(), entry.target.trim(), entry.manual, entry.updatedAt))
    }
    override suspend fun deleteGlossary(mangaId: String, source: String) = dao.deleteGlossary(mangaId, source)
}

private fun ChapterTranslationEntity.toDomain() = ChapterTranslation(chapterId, mangaId, targetLanguage,
    TranslationState.entries.firstOrNull { it.name == state } ?: TranslationState.PENDING,
    sourceLanguage, autoDetectSource, configSnapshot, queuedAt, translatedAt, translatedCount, failure, updatedAt)
