package com.lmreader.core.database

import android.content.Context
import com.lmreader.core.database.repository.MangaRepositoryImpl
import com.lmreader.core.database.repository.ReadingProgressRepositoryImpl
import com.lmreader.core.database.repository.ShelfRepositoryImpl
import com.lmreader.core.database.repository.SourceRepositoryImpl
import com.lmreader.core.database.repository.TranslationRepositoryImpl
import com.lmreader.core.index.ChapterOrdering
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.model.TranslationRepository

/**
 * 数据库模块的对外入口。
 *
 * 为什么用工厂函数而不是让 `:app` 直接 `new` 实现类：实现类是 `internal`，
 * 由本模块决定暴露什么（开发文档 15.2「接口返回带身份的结果/错误，
 * 不返回 UI 对象」）。
 */
object DatabaseProvider {

    fun create(
        context: Context,
        /**
         * 用户保存的章节排序方式；发现新章节时按它决定插到哪里。
         *
         * 从外面传进来而不是在本模块读 DataStore：`core:database` 不该认识偏好存储
         * （开发文档 15.1「core:database 只管索引、事务、搜索、迁移」）。
         */
        chapterOrder: ChapterOrdering.SettingProvider = ChapterOrdering.SettingProvider {
            ChapterOrdering.Setting.DEFAULT
        },
    ): Components = create(LmReaderDatabase.build(context), chapterOrder)

    fun create(
        database: LmReaderDatabase,
        chapterOrder: ChapterOrdering.SettingProvider = ChapterOrdering.SettingProvider {
            ChapterOrdering.Setting.DEFAULT
        },
    ): Components = Components(
        database = database,
        sources = SourceRepositoryImpl(database, database.sourceDao()),
        mangas = MangaRepositoryImpl(database, database.mangaDao(), chapterOrder),
        translations = TranslationRepositoryImpl(database),
        shelf = ShelfRepositoryImpl(database),
        readingProgress = ReadingProgressRepositoryImpl(database.readingProgressDao()),
    )

    class Components(
        val database: LmReaderDatabase,
        val sources: SourceRepository,
        val mangas: MangaRepository,
        /** 待翻译队列与漫画译名字典（阶段 2）。 */
        val translations: TranslationRepository,
        val shelf: ShelfRepository,
        val readingProgress: ReadingProgressRepository,
    )
}
