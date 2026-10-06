package com.lmreader.core.database.repository

import com.lmreader.core.database.LmReaderDatabase
import androidx.room.withTransaction
import com.lmreader.core.database.entity.CategoryEntity
import com.lmreader.core.database.entity.ShelfEntryEntity
import com.lmreader.core.database.entity.toDomain
import com.lmreader.core.model.Category
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.StyleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书架与分类实现（开发文档 8.2）。
 *
 * 三条不能省的规则：
 * 1. 一个漫画最多一个分类（首版单分类，开发文档 1.3），因此加入书架是 upsert 而不是插入；
 * 2. 同名分类禁止（开发文档 8.2「新分类 +」），校验在仓储里做，UI 只负责提示；
 * 3. 删除分类把收藏移到未分类，**不移出书架、不删漫画**（开发文档 8.2）。
 */
internal class ShelfRepositoryImpl(private val database: LmReaderDatabase) : ShelfRepository {

    private val dao = database.shelfDao()

    override fun observeCategories(): Flow<List<Category>> =
        dao.observeCategories().map { list -> list.map { it.toDomain() } }

    override suspend fun ensureUncategorized(): Long {
        val existing = dao.getCategory(LmReaderDatabase.UNCATEGORIZED_ID)
        if (existing != null) return existing.categoryId
        // 建库回调理论上已经插过；这里兜住"回调没跑到"的情况（例如测试里用
        // inMemoryDatabaseBuilder 且没带回调），否则首次加入书架会写进一个
        // 不存在的分类，界面只能把它显示成"全部"。
        dao.upsertCategory(
            CategoryEntity(
                categoryId = LmReaderDatabase.UNCATEGORIZED_ID,
                name = UNCATEGORIZED_NAME,
                styleMode = StyleMode.GLOBAL,
                customStyle = null,
                orderIndex = -1,
                revision = 0,
            ),
        )
        return LmReaderDatabase.UNCATEGORIZED_ID
    }

    override suspend fun createCategory(name: String): Category {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分类名不能为空" }
        require(trimmed.length <= MAX_CATEGORY_NAME_LENGTH) {
            "分类名最长 $MAX_CATEGORY_NAME_LENGTH 个字符"
        }
        require(dao.findCategoryByName(trimmed) == null) { "已存在同名分类：$trimmed" }

        val entity = CategoryEntity(
            // 自增但避开内置的 0（它已经被占用）。
            categoryId = dao.maxCategoryId() + 1,            name = trimmed,
            styleMode = StyleMode.CATEGORY,
            customStyle = null,
            orderIndex = dao.maxCategoryOrder() + 1,
            revision = 1,
        )
        dao.upsertCategory(entity)
        return entity.toDomain()
    }

    override suspend fun renameCategory(categoryId: Long, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "分类名不能为空" }
        require(trimmed.length <= MAX_CATEGORY_NAME_LENGTH) {
            "分类名最长 $MAX_CATEGORY_NAME_LENGTH 个字符"
        }
        val current = dao.getCategory(categoryId) ?: return
        val sameName = dao.findCategoryByName(trimmed)
        require(sameName == null || sameName.categoryId == categoryId) { "已存在同名分类：$trimmed" }
        dao.updateCategory(categoryId, trimmed, current.styleMode, current.customStyle)
    }

    override suspend fun updateCategoryStyle(categoryId: Long, mode: StyleMode, customStyle: String?) {
        val current = dao.getCategory(categoryId) ?: return
        dao.updateCategory(categoryId, current.name, mode, customStyle)
    }

    override suspend fun deleteCategory(categoryId: Long) {
        // 内置「未分类」不可删（开发文档 1.3）。
        if (categoryId == LmReaderDatabase.UNCATEGORIZED_ID) return
        dao.deleteCategoryAndReassign(categoryId, LmReaderDatabase.UNCATEGORIZED_ID)
    }

    override suspend fun reorderCategories(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, categoryId -> dao.updateCategoryOrder(categoryId, index) }
    }

    override suspend fun addToShelf(mangaId: String, categoryId: Long) {
        val target = if (dao.getCategory(categoryId) == null) ensureUncategorized() else categoryId
        dao.upsertEntry(ShelfEntryEntity(mangaId = mangaId, categoryId = target, addedAt = now()))
    }

    override suspend fun categoryIdOf(mangaId: String): Long? = dao.getEntry(mangaId)?.categoryId

    override suspend fun ensureOnShelf(mangaId: String) {
        database.withTransaction {
            if (dao.getEntry(mangaId) != null) return@withTransaction
            dao.insertEntryIfAbsent(ShelfEntryEntity(mangaId, ensureUncategorized(), now()))
        }
    }

    override suspend fun removeFromShelf(mangaId: String) {
        // 只删收藏关系：漫画仍在图库、原文件仍在（开发文档 8.2「移出书架」）。
        dao.deleteEntry(mangaId)
    }

    override fun observeShelfCount(categoryId: Long?): Flow<Int> =
        if (categoryId == null) dao.observeShelfTotalFlow() else dao.observeShelfCountInCategory(categoryId)

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val UNCATEGORIZED_NAME = "未分类"

        /** 开发文档 8.2：新分类名称必填 1–40 字符。 */
        const val MAX_CATEGORY_NAME_LENGTH = 40
    }
}
