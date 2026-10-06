package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lmreader.core.database.entity.CategoryEntity
import com.lmreader.core.database.entity.ShelfEntryEntity
import com.lmreader.core.model.StyleMode
import kotlinx.coroutines.flow.Flow

/**
 * 书架分类与收藏关系（开发文档 8.2）。
 *
 * 内置「未分类」的 `categoryId = 0` 是约定值而不是自增结果：分类表用显式 ID，
 * 自增会让删除后重建拿到不同 ID，而收藏关系里的 categoryId 需要跨迁移稳定。
 */
@Dao
interface ShelfDao {

    @Query("SELECT * FROM categories ORDER BY orderIndex ASC, categoryId ASC")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY orderIndex ASC, categoryId ASC")
    suspend fun getCategories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE categoryId = :categoryId")
    suspend fun getCategory(categoryId: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1")
    suspend fun findCategoryByName(name: String): CategoryEntity?

    @Query("SELECT COALESCE(MAX(categoryId), 0) FROM categories")
    suspend fun maxCategoryId(): Long

    @Query("SELECT COALESCE(MAX(orderIndex), -1) FROM categories")
    suspend fun maxCategoryOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(entity: CategoryEntity)

    @Query("DELETE FROM categories WHERE categoryId = :categoryId")
    suspend fun deleteCategory(categoryId: Long)

    @Query(
        """
        UPDATE categories
        SET name = :name, styleMode = :mode, customStyle = :customStyle, revision = revision + 1
        WHERE categoryId = :categoryId
        """,
    )
    suspend fun updateCategory(categoryId: Long, name: String, mode: StyleMode, customStyle: String?)

    @Query("UPDATE categories SET orderIndex = :orderIndex WHERE categoryId = :categoryId")
    suspend fun updateCategoryOrder(categoryId: Long, orderIndex: Int)

    @Query("SELECT * FROM shelf_entries WHERE mangaId = :mangaId")
    suspend fun getEntry(mangaId: String): ShelfEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(entity: ShelfEntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntryIfAbsent(entity: ShelfEntryEntity)

    @Query("DELETE FROM shelf_entries WHERE mangaId = :mangaId")
    suspend fun deleteEntry(mangaId: String)

    @Query("SELECT COUNT(*) FROM shelf_entries")
    suspend fun countAll(): Int

    /**
     * 书架条目数（含分类栏的角标）。
     *
     * 陈旧卡片（本来源最近一次完整扫描没有再发现的卡片）不算进去：它们默认不出现在
     * 书架上，角标必须与列表同一口径。收藏关系本身保留，卡片被重新发现时自动回来。
     */
    @Query(
        """
        SELECT COUNT(*) FROM shelf_entries AS e
        JOIN mangas AS m ON m.mangaId = e.mangaId
        WHERE m.availability != 'STALE'
        """,
    )
    fun observeShelfTotalFlow(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM shelf_entries AS e
        JOIN mangas AS m ON m.mangaId = e.mangaId
        WHERE e.categoryId = :categoryId
          AND m.availability != 'STALE'
        """,
    )
    fun observeShelfCountInCategory(categoryId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM shelf_entries WHERE categoryId = :categoryId")
    suspend fun countInCategory(categoryId: Long): Int

    @Query("UPDATE shelf_entries SET categoryId = :categoryId WHERE mangaId = :mangaId")
    suspend fun moveEntry(mangaId: String, categoryId: Long)

    /**
     * 删除分类时把收藏移到未分类，而不是删除收藏（开发文档 8.2「删除分类」）。
     *
     * 同一事务内完成移动与删除，避免中途崩溃留下指向不存在分类的收藏。
     */
    @Transaction
    suspend fun deleteCategoryAndReassign(categoryId: Long, uncategorizedId: Long) {
        moveAllToCategory(from = categoryId, to = uncategorizedId)
        deleteCategory(categoryId)
    }

    @Query("UPDATE shelf_entries SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveAllToCategory(from: Long, to: Long)
}
