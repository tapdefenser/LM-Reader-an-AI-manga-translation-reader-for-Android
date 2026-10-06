package com.lmreader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.lmreader.core.model.ChapterKind
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourceKind
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.core.model.StyleMode

/**
 * Room 实体。表名与列名遵循 `docs/框架实现说明.md` §5.1。
 *
 * 为什么实体与 `core:model` 的领域类型分开：Room 需要可空/注解化的行结构，
 * 而领域类型要给纯 JVM 扫描器与 UI 用，不能带注解依赖。转换在 `Mappers.kt`。
 * 两边字段名保持同名，避免"两个名字指同一件事"的长期歧义。
 */

/**
 * 路径表一行（只有一张表）。
 *
 * [kind] 不再决定扫描行为——一次遍历同时识别图片章节与归档章节（见 `StructureScanner`），
 * 它只作为来源身份与卡片身份（`mangaId`）的判别位保留。
 */
@Entity(
    tableName = "library_sources",
    indices = [Index(value = ["kind", "orderIndex"])],
)
data class LibrarySourceEntity(
    @PrimaryKey val sourceId: String,
    val kind: SourceKind,
    val treeUri: String,
    val displayPath: String,
    val providerLabel: String?,
    /** 用户可编辑的显示名称；为空时界面回退到 displayPath。 */
    val displayName: String?,
    val recursive: Boolean,
    val mode: LayoutMode,
    val orderIndex: Int,
    val permission: SourcePermissionState,
    /** 每次保存配置 +1；过期扫描结果不得覆盖新配置（验收 A09）。 */
    val revision: Long,
    val lastScanAt: Long?,
    val lastScanStatus: ScanRunStatus?,
    val lastScanError: String?,
)

/**
 * 漫画行。
 *
 * 唯一约束是 `(anchorDocumentId, sourceKind)` 而不是 documentId 单列：`sourceKind` 是
 * `mangaId` 的判别位（开发文档 15.3），改它会让已有卡片换 ID、连带丢掉书架与阅读进度。
 * 卡片内容（章节是图片目录还是压缩包）由 `chapters.kind` 表达，不由这里表达。
 */
@Entity(
    tableName = "mangas",
    indices = [
        Index(value = ["anchorDocumentId", "sourceKind"], unique = true),
        Index(value = ["sourceId", "sourceOrderIndex"]),
        Index(value = ["sortKey", "mangaId"]),
        Index(value = ["displayName"]),
    ],
)
data class MangaEntity(
    @PrimaryKey val mangaId: String,
    val anchorDocumentId: String,
    val sourceId: String,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val displayName: String,
    /**
     * 自然序预计算列：SQLite 只能做字典序比较，分页要稳定就必须在写入时把
     * 「不区分大小写 + 数字按数值」的键算好（框架 5.2）。
     */
    val sortKey: String,
    /**
     * 来源表内顺序的冗余列。
     *
     * 为什么冗余：分页查询要按 `来源顺序 → 自然名称 → mangaId` 排序（开发文档 6.4），
     * 若每次 JOIN 来源表排序，路径表重排会让整个查询计划退化；冗余一列后
     * 重排只需一条 UPDATE。写入方负责与 `library_sources.orderIndex` 保持一致。
     */
    val sourceOrderIndex: Int,
    val author: String?,
    val hasMetadata: Boolean,
    val summary: String?,
    val coverDocumentId: String?,
    val coverChapterId: String?,
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    /**
     * 滚动/搜索时**只数数量**得到的章节数；null = 数过但没数出可读章节。
     *
     * 为什么不复用 [chapterCount]：那个列与 [chapterCountKnown] 是一对，后者同时是
     * 同步逻辑"可以删掉多余章节行"的闸门。滚动计数只列了一次锚点目录、**没有**枚举
     * 并落库章节清单，所以绝不能把闸门打开；两者分开之后各写各的，互不影响。
     *
     * 取数时机与封面一致（用户口径："滚动 + 搜索结果时加载，不要做成扫描时加载"）。
     */
    val countedChapterCount: Int? = null,
    /**
     * 滚动计数的时间；null = 从未数过。
     *
     * 与 [countedChapterCount] 的分工和封面那对一样：后者是**结果**（可能为 null =
     * 没数出章节），本列是**过程**（数过一次就不再数，失败也算数过，否则每次滚动都会
     * 对同一批必然失败的卡片重来一遍）。
     */
    val countedChapterCountAt: Long? = null,
    val availability: MangaAvailability,
    val discoveryGeneration: Long,
    val discoveredAt: Long,
    val updatedAt: Long,
    /**
     * 漫画级阅读模式覆盖（开发文档 15.3「漫画级阅读偏好归数据库」）。
     *
     * 存枚举**名称**而不是序数：序数会在枚举增删或重排后悄悄指向另一个模式，
     * 而这类错误没有任何报错，只是"打开这部漫画时模式变了"。名称失配时回退全局默认。
     *
     * null 表示"从未覆盖"，使用全局默认。这里刻意**不用**哨兵值成员，因此不存在
     * "未解析的哨兵渗进阅读器"这类问题（与阅读设置模型的设计一致）。
     */
    val readerModeOverride: String? = null,
    /** 漫画级屏幕方向覆盖；null 表示跟随全局默认。同样存名称。 */
    val readerOrientationOverride: String? = null,
    /**
     * 封面探测时间；null = 从未探测过。
     *
     * 与 [coverDocumentId] 的分工：后者是**结果**（可能确实没有），本列是**过程**
     * （取过一次就不再取）。封面现在由图库滚动懒加载，扫描只写路径，因此必须能区分
     * "没有封面"与"还没取过"，否则每次滚动都会把必然拿不到的卡片重新枚举一遍目录。
     */
    val coverProbedAt: Long? = null,
    /**
     * 简介（ComicInfo）探测时间；null = 从未探测过。
     *
     * 与 [hasMetadata] 的分工：后者是**结果**（读到了 XML），本列是**过程**（去读过一次）。
     * 少了它，`hasMetadata = 0` 的条目会把补全队列的头 120 条永久占住——每轮扫描都
     * 重新打开同一批目录、什么也读不到，后面的作品永远轮不到（真机上 5408 部里
     * `hasMetadata = 0` 的有 5408 部，也就是整个队列从来不前进）。
     */
    val metadataProbedAt: Long? = null,
    /**
     * 漫画级翻译设置（语言与文风；见 `MangaTranslationSettings`）。
     *
     * 全部可空/带默认：**留空 = 用上一层**（文风走 漫画→分类→全局 的覆盖链，
     * 语言回退全局默认）。用可空而不是哨兵值，是因为"没设置"与"设置成默认值"在
     * 覆盖链里是两件事——后者会挡住分类与全局。
     */
    val translationSourceLanguage: String? = null,
    /** 自动识别源语言；true 时优先于 [translationSourceLanguage]。 */
    val translationAutoDetectSource: Boolean = false,
    val translationTargetLanguage: String? = null,
    /** 漫画自己的文风模式；只有 CUSTOM + 非空文本才会覆盖分类与全局。 */
    val translationStyleMode: String? = null,
    val translationCustomStyle: String? = null,
    val translationWorkflowId: String? = null,
    val translationPageMode: String? = null,
    val translationSegThreshold: Float? = null,
    val translationBubbleFillMode: String? = null,
    val translationBubbleOpacity: Int? = null,
    val translationBubblePadding: Int? = null,
    val translationBubbleFont: String? = null,
    val translationBubbleFontScale: Int? = null,
    val translationBubbleBold: Boolean? = null,
    val translationSegTextScope: String? = null,
    val translationTextDetectionThreshold: Float? = null,
    val translationFreeTextMaskExpansion: Int? = null,
    val translationFreeTextMergeGapRatio: Float? = null,
)

/** 章节行；物理定位键是 `(documentId, kind)`（开发文档 15.3）。 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["mangaId"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["documentId", "kind"], unique = true),
        Index(value = ["mangaId", "sortKey"]),
        // 详情页按显示顺序取章节；没有这条索引时每次进详情页都要为一部漫画排序。
        Index(value = ["mangaId", "position"]),
    ],
)
data class ChapterEntity(
    @PrimaryKey val chapterId: String,
    val mangaId: String,
    val documentId: String,
    val kind: ChapterKind,
    val title: String,
    val sortKey: String,
    /**
     * 显示位置（详情页章节列表的顺序，见 `ChapterRecord.position`）。
     *
     * 与 [sortKey] 分工：`sortKey` 永远是自然序、用于"自然序第一章"（封面/简介）；
     * 本列是用户看到的顺序，排序抽屉与手动拖动都改它。两者不能合成一列。
     */
    val position: Long = 0L,
    /** 目录/归档文件的修改时间；null = 提供方未给出（见 `ChapterRecord.modifiedAt`）。 */
    val modifiedAt: Long? = null,
    val pageCount: Int?,
    val coverDocumentId: String?,
    val contentRevision: Long,
    val discoveredAt: Long,
)

/**
 * ComicInfo 记录；`ownerId` 是 mangaId 或 chapterId。
 *
 * 章节的原始 XML 按章节独立保存，漫画级搜索投影另建（开发文档 6.4）。
 */
@Entity(
    tableName = "metadata_records",
    indices = [Index(value = ["ownerType", "ownerId"])],
)
data class MetadataEntity(
    @PrimaryKey val ownerId: String,
    val ownerType: MetadataOwnerType,
    val xml: String,
    /** JSON 文本；未知字段必须保留（开发文档 7.1）。 */
    @ColumnInfo(name = "fieldsJson") val fieldsJson: String,
    val summary: String?,
    val series: String?,
    val title: String?,
    val writer: String?,
    val alternateSeries: String?,
    val normalizedSearchText: String,
    val parseError: String?,
    val sourceLabel: String,
    val fingerprint: String,
    val updatedAt: Long,
)

/** 书架分类；categoryId = 0 是内置「未分类」，不可删（开发文档 1.3）。 */
@Entity(
    tableName = "categories",
    indices = [Index(value = ["name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey val categoryId: Long,
    val name: String,
    val styleMode: StyleMode,
    val customStyle: String?,
    val orderIndex: Int,
    val revision: Long,
)

/**
 * 书架项：一个漫画最多一条收藏关系（开发文档 8.2）。
 *
 * 外键挂 mangaId 而非 anchorDocumentId：重扫只更新派生字段，漫画行不会被删，
 * 因此收藏关系天然存活（开发文档 15.3）。
 */
@Entity(
    tableName = "shelf_entries",
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["mangaId"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class ShelfEntryEntity(
    @PrimaryKey val mangaId: String,
    val categoryId: Long,
    val addedAt: Long,
)

/** 阅读进度；本步只建表，阅读器在 P2 接入。 */
@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val mangaId: String,
    val chapterId: String?,
    val pageOrdinal: Int,
    val intraPageRatio: Float,
    val read: Boolean,
    val bookmark: Boolean,
    val updatedAt: Long,
)

/**
 * 按章的已读标记（章节多选底栏的「标记已读/未读」）。
 *
 * 为什么不是 `reading_progress.read`：那张表一部漫画一行，"读到哪一章哪一页"，
 * `read` 只描述**那一章**。章节列表要给每章显示已读状态、多选要批量标记，因此按章存。
 *
 * 为什么不加在 `chapters` 上：开发文档 15.3「索引可变状态与用户状态分表」——
 * `chapters` 每次扫描都会被 upsert 重写，用户标记放进去的唯一结局是被重扫抹掉。
 * 外键指向 `chapters.chapterId` 并级联删除：章节行消失时标记跟着走，不留孤儿。
 */
@Entity(
    tableName = "chapter_read_state",
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mangaId"])],
)
data class ChapterReadStateEntity(
    @PrimaryKey val chapterId: String,
    val mangaId: String,
    val read: Boolean,
    val updatedAt: Long,
)

/** 每章一套翻译记录；目标语言是任务快照的内容，不参与主键。取消保留完成页，清除删除记录。 */
@Entity(
    tableName = "chapter_translation",
    primaryKeys = ["chapterId"],
    foreignKeys = [
        ForeignKey(
            entity = ChapterEntity::class,
            parentColumns = ["chapterId"],
            childColumns = ["chapterId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mangaId", "state"])],
)
data class ChapterTranslationEntity(
    val chapterId: String,
    val mangaId: String,
    val targetLanguage: String,
    /** `TranslationState` 的名字；存名字而不是序数，枚举增删不会悄悄换含义。 */
    val state: String,
    val sourceLanguage: String?,
    val autoDetectSource: Boolean,
    /**
     * 入队时的有效配置快照（JSON）。
     *
     * 队列**带着快照走**：用户随后改语言或文风时，已经排队的任务不会换一种翻法
     * （开发文档"队列包含有效配置快照"；也是"每部漫画一套独立翻译方式"的前提）。
     */
    val configSnapshot: String?,
    val queuedAt: Long?,
    val translatedAt: Long?,
    /** 已保存的页数；「清除翻译文本」把它与 [translatedAt] 一起抹掉。 */
    val translatedCount: Int,
    val failure: String?,
    val updatedAt: Long,
)

/**
 * 漫画译名字典：`原词 → 译名`，**只和漫画有关，与语言无关**（用户口径）。
 *
 * 主键 `(mangaId, source)`：同一部作品里一个原词只有一条译名。上游规格（TR09）原本
 * 按"漫画 + 目标语言"分区，用户明确否掉了那一维——他只翻一种语言，多出来的
 * "正在编辑：简体中文"只是噪音。代价见 `GlossaryEntry` 的说明。
 */
@Entity(
    tableName = "manga_glossary",
    primaryKeys = ["mangaId", "source"],
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["mangaId"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["mangaId"])],
)
data class MangaGlossaryEntity(
    val mangaId: String,
    val source: String,
    val target: String,
    val manual: Boolean,
    val updatedAt: Long,
)

/**
 * 目录快照：记录「这个容器是否被完整枚举过」。
 *
 * 为什么存摘要而不是成员列表：成员集合可以从 `mangas/chapters` 的 documentId
 * 反查，重复存一份必然出现两处不一致（开发文档 6.2）。
 */
@Entity(
    tableName = "directory_snapshots",
    primaryKeys = ["sourceId", "documentId"],
    indices = [Index(value = ["sourceId"])],
)
data class DirectorySnapshotEntity(
    val sourceId: String,
    val documentId: String,
    val memberDigest: String?,
    val memberCount: Int,
    val generation: Long,
    val completedAt: Long,
    val lastError: String?,
)

/** 扫描运行记录；用于界面显示"上次扫描"与诊断（开发文档 6.2）。 */
@Entity(tableName = "scan_runs", indices = [Index(value = ["sourceId"])])
data class ScanRunEntity(
    @PrimaryKey val generation: Long,
    val sourceId: String,
    val sourceRevision: Long,
    val status: ScanRunStatus,
    val startedAt: Long,
    val finishedAt: Long?,
    val mangasFound: Int,
    val error: String?,
)
