package com.lmreader.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lmreader.core.database.dao.ChapterDao
import com.lmreader.core.database.dao.ChapterReadStateDao
import com.lmreader.core.database.dao.MangaDao
import com.lmreader.core.database.dao.MetadataDao
import com.lmreader.core.database.dao.ReadingProgressDao
import com.lmreader.core.database.dao.ScanDao
import com.lmreader.core.database.dao.ShelfDao
import com.lmreader.core.database.dao.SourceDao
import com.lmreader.core.database.dao.TranslationDao
import com.lmreader.core.database.entity.CategoryEntity
import com.lmreader.core.database.entity.ChapterEntity
import com.lmreader.core.database.entity.ChapterReadStateEntity
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.entity.DirectorySnapshotEntity
import com.lmreader.core.database.entity.LibrarySourceEntity
import com.lmreader.core.database.entity.MangaEntity
import com.lmreader.core.database.entity.MangaGlossaryEntity
import com.lmreader.core.database.entity.MetadataEntity
import com.lmreader.core.database.entity.ReadingProgressEntity
import com.lmreader.core.database.entity.ScanRunEntity
import com.lmreader.core.database.entity.ShelfEntryEntity
import com.lmreader.core.model.StyleMode

/**
 * 应用数据库（开发文档 15.1「Room/SQLite 管理实体和事务」）。
 *
 * 迁移纪律：`exportSchema = true` 且 schema 输出到 `core/database/schemas`，
 * 每次改实体都必须提交新的 schema JSON 并提供迁移；开发文档 15.4 要求
 * 「数据库迁移必须可测试」，因此不允许 `fallbackToDestructiveMigration`——
 * 那会静默删掉书架、进度与译名字典。
 */
@Database(
    entities = [
        LibrarySourceEntity::class,
        MangaEntity::class,
        ChapterEntity::class,
        ChapterReadStateEntity::class,
        ChapterTranslationEntity::class,
        MangaGlossaryEntity::class,
        MetadataEntity::class,
        CategoryEntity::class,
        ShelfEntryEntity::class,
        ReadingProgressEntity::class,
        DirectorySnapshotEntity::class,
        ScanRunEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LmReaderDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterReadStateDao(): ChapterReadStateDao
    abstract fun translationDao(): TranslationDao
    abstract fun metadataDao(): MetadataDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun shelfDao(): ShelfDao
    abstract fun scanDao(): ScanDao

    companion object {
        /** 内置「未分类」的固定 ID（开发文档 1.3「一个漫画的分类」）。 */
        const val UNCATEGORIZED_ID = 0L

        private const val DATABASE_NAME = "lmreader.db"

        fun build(context: Context): LmReaderDatabase =
            Room.databaseBuilder(context.applicationContext, LmReaderDatabase::class.java, DATABASE_NAME)
                // 迁移必须显式登记：绝不使用 fallbackToDestructiveMigration，
                // 那会静默删掉书架、阅读进度与译名字典（开发文档 15.4）。
                .addMigrations(*Migrations.ALL)
                .addCallback(UncategorizedSeeder)
                .build()

        /**
         * 首次建库即插入「未分类」。
         *
         * 为什么用建库回调而不是"第一次用到时再建"：`shelf_entries.categoryId` 没有
         * 外键约束（分类可以晚于收藏出现），如果依赖惰性创建，一旦在创建前崩溃就会
         * 留下指向不存在分类的收藏，而 UI 会把它们全部归到「全部」里，用户看不到
         * 自己选过的分类。建库时就保证它存在，代价只有一次 INSERT。
         */
        private object UncategorizedSeeder : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "INSERT OR IGNORE INTO categories " +
                        "(categoryId, name, styleMode, customStyle, orderIndex, revision) " +
                        "VALUES (?, ?, ?, NULL, ?, 0)",
                    arrayOf<Any>(UNCATEGORIZED_ID, "未分类", StyleMode.GLOBAL.name, -1),
                )
            }
        }
    }
}
