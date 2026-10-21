package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.entity.MangaGlossaryEntity
import kotlinx.coroutines.flow.Flow

/** 侧栏「翻译队列」角标用的计数投影。 */
data class PendingTranslationRow(
    val chapterId: String,
)

/**
 * 翻译数据读写（阶段 2 的待翻译队列与漫画译名字典）。
 *
 * 状态列存 `TranslationState` 的**名字**而不是序数：枚举增删后序数会悄悄指向另一个值，
 * 而这种错误没有任何报错（与阅读覆盖列同一个理由）。
 */
@Dao
interface TranslationDao {

    /** Completion records remain available to chapter status and cached translations. */
    @Query("SELECT * FROM chapter_translation WHERE state NOT IN ('DONE', 'CANCELLED') ORDER BY queuedAt ASC, chapterId ASC")
    fun observeQueue(): Flow<List<ChapterTranslationEntity>>

    @Query("SELECT * FROM chapter_translation WHERE state NOT IN ('DONE', 'CANCELLED') ORDER BY queuedAt ASC, chapterId ASC")
    suspend fun queueSnapshot(): List<ChapterTranslationEntity>

    @Query("UPDATE chapter_translation SET state = :state, failure = :failure, translatedCount = :completed, translatedAt = CASE WHEN :state = 'DONE' THEN :at ELSE translatedAt END, updatedAt = :at WHERE chapterId = :chapterId AND targetLanguage = :targetLanguage")
    suspend fun setQueueState(chapterId: String, targetLanguage: String, state: String,
        failure: String?, completed: Int, at: Long)

    @Query("UPDATE chapter_translation SET state = 'PENDING', failure = NULL, updatedAt = :at WHERE chapterId = :chapterId AND targetLanguage = :targetLanguage AND state IN ('FAILED', 'INTERRUPTED')")
    suspend fun retryQueueItem(chapterId: String, targetLanguage: String, at: Long)

    /** Start every unfinished task; completed and cancelled translations stay untouched. */
    @Query("UPDATE chapter_translation SET state = 'PENDING', failure = NULL, updatedAt = :at WHERE state IN ('PAUSED', 'FAILED', 'INTERRUPTED')")
    suspend fun startAllQueueItems(at: Long): Int

    @Query("UPDATE chapter_translation SET state = 'INTERRUPTED', failure = '上次运行被中断', updatedAt = :at WHERE state = 'RUNNING'")
    suspend fun interruptRunning(at: Long)

    @Query("UPDATE chapter_translation SET state = :state, updatedAt = :at WHERE chapterId IN (:ids) AND state IN ('PENDING', 'RUNNING', 'PAUSED', 'INTERRUPTED')")
    suspend fun controlQueueItems(ids: List<String>, state: String, at: Long)

    @Query("UPDATE chapter_translation SET state = 'CANCELLED', updatedAt = :at WHERE chapterId IN (:ids) AND state != 'DONE'")
    suspend fun cancelQueueItems(ids: List<String>, at: Long)

    @Query("UPDATE chapter_translation SET translatedCount = MAX(0, translatedCount - :removed), translatedAt = NULL, state = CASE WHEN state = 'DONE' THEN 'CANCELLED' ELSE state END, updatedAt = :at WHERE chapterId = :chapterId")
    suspend fun pageCleared(chapterId: String, removed: Int, at: Long)

    @Query("DELETE FROM chapter_translation WHERE chapterId IN (:ids)")
    suspend fun deleteChapters(ids: List<String>)

    @Query("DELETE FROM chapter_translation WHERE state NOT IN ('DONE', 'CANCELLED') AND (sourceLanguage IS NULL OR TRIM(sourceLanguage) = '' OR TRIM(targetLanguage) = '' OR autoDetectSource != 0 OR configSnapshot IS NULL OR TRIM(configSnapshot) = '')")
    suspend fun deleteInvalidQueueItems()

    @Query(
        """
        SELECT * FROM chapter_translation
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun byManga(mangaId: String): List<ChapterTranslationEntity>

    @Query("SELECT * FROM chapter_translation WHERE chapterId = :chapterId")
    suspend fun byChapter(chapterId: String): List<ChapterTranslationEntity>

    /** 每章一套翻译记录；入队前的状态判定用一次查询取齐。 */
    @Query("SELECT * FROM chapter_translation WHERE chapterId IN (:chapterIds)")
    suspend fun byChapters(chapterIds: List<String>): List<ChapterTranslationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ChapterTranslationEntity>)

    @Query(
        """
        DELETE FROM chapter_translation
        WHERE chapterId IN (:chapterIds) AND targetLanguage = :targetLanguage
        """,
    )
    suspend fun deleteAll(chapterIds: List<String>, targetLanguage: String)

    /** 待翻译（含翻译中）：侧栏角标。 */
    @Query("SELECT chapterId FROM chapter_translation WHERE state IN ('PENDING', 'RUNNING', 'PAUSED', 'FAILED', 'INTERRUPTED')")
    fun observePending(): Flow<List<PendingTranslationRow>>

    @Query("SELECT COUNT(*) FROM chapter_translation WHERE state IN ('PENDING', 'RUNNING', 'PAUSED', 'FAILED', 'INTERRUPTED')")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM manga_glossary WHERE mangaId = :mangaId ORDER BY source ASC")
    suspend fun glossary(mangaId: String): List<MangaGlossaryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGlossary(entities: List<MangaGlossaryEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGlossary(entities: List<MangaGlossaryEntity>): List<Long>

    @Transaction
    suspend fun editGlossary(originalSource: String, entity: MangaGlossaryEntity) {
        val entries = glossary(entity.mangaId)
        require(entries.any { it.source == originalSource }) { "译名已删除" }
        require(entity.source == originalSource || entries.none { it.source == entity.source }) { "该原词已有译名，请编辑已有条目" }
        if (entity.source != originalSource) deleteGlossary(entity.mangaId, originalSource)
        upsertGlossary(listOf(entity))
    }

    @Query("DELETE FROM manga_glossary WHERE mangaId = :mangaId AND source = :source")
    suspend fun deleteGlossary(mangaId: String, source: String)
}
