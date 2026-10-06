package com.lmreader.core.model

/**
 * 图库来源：用户授权的一条目录配置（开发文档 4.1、15.3）。
 *
 * [revision] 每次保存配置 +1：扫描结果是异步落库的，只有携带当前 revision 的
 * 结果才允许覆盖派生字段，否则快速改动「子目录/类型」会被上一个版本的扫描结果
 * 覆盖（验收 A09「旧版本扫描不能覆盖新配置」）。
 */
data class LibrarySource(
    val sourceId: String,
    val kind: SourceKind,
    val treeUri: String,
    val displayPath: String,
    /** 提供方名称；无法解析时用目录名（开发文档 4.1「路径状态」）。 */
    val providerLabel: String?,
    /**
     * 用户可编辑的显示名称。
     *
     * 为什么需要它：SAF 只能给出 `primary:Tachiyomi/downloads` 这类 documentId，
     * 真正的文件系统绝对路径不保证可解析（开发文档 4.1 的已知限制）。与其给用户
     * 一个不可读的路径串，不如让用户给它起一个自己认得的名字；为空时界面显示
     * [displayPath]，因此不会出现"没有名字"的状态。
     *
     * 它同时作为该来源下漫画卡片的默认名称来源（扫描发现阶段用文件夹名，
     * 用户在路径行里起的名字只影响展示，不改变身份与稳定 ID）。
     */
    val displayName: String?,
    /** 子目录列，默认 true（开发文档 4.1）。 */
    val recursive: Boolean,
    /** 类型列，默认 MULTI_CHAPTER（开发文档 4.1）。 */
    val mode: LayoutMode,
    /** 来源顺序，0 起；只有一张路径表，因此是一条序列（拖动排序即时持久化）。 */
    val orderIndex: Int,
    val permission: SourcePermissionState,
    val revision: Long,
    val lastScanAt: Long?,
    val lastScanStatus: ScanRunStatus?,
    val lastScanError: String?,
)

/**
 * 漫画行（开发文档 15.3）。
 *
 * [chapterCount]/[chapterCountKnown] 分开表达「已发现 N 章」与「N 是否完整」：
 * 发现阶段先出卡片、章节数随后补齐，UI 不得把探测到一章伪报成完整一章
 * （开发文档 5.1「漫画发现与章节同步分开」）。
 */
data class MangaRecord(
    val mangaId: String,
    val anchorDocumentId: String,
    /** effectiveSourceId：重叠授权时最具体的授权目录（开发文档 6.4）。 */
    val sourceId: String,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val displayName: String,
    /** 未知显示「未知」；发现阶段未读 ComicInfo 时为 null。 */
    val author: String?,
    /** 是否读到 ComicInfo.xml（开发文档 7.1）。 */
    val hasMetadata: Boolean,
    /** 压缩摘要，可空；「无简介」由 UI 呈现（开发文档 2）。 */
    val summary: String?,
    /** 第一章第一页或 cover.*；空则占位图（开发文档 7.2）。 */
    val coverDocumentId: String?,
    val coverChapterId: String?,
    /**
     * 封面**探测过**的时间；null = 从未探测。
     *
     * 与 [coverDocumentId] 的分工见 `MangaCard.coverProbedAt`：封面从"扫描期补全"
     * 改成"图库滚动懒加载"之后，必须能区分"没有封面"与"还没取过"，否则那些必然
     * 拿不到封面的卡片会在每次滚动时被重新枚举一遍目录。
     */
    val coverProbedAt: Long? = null,
    /**
     * 简介（ComicInfo）**探测过**的时间；null = 从未探测。
     *
     * 与 [hasMetadata] 的分工同 `MangaEntity.metadataProbedAt`：后者是**结果**（读到了 XML），
     * 本字段是**过程**（去读过一次）。详情页需要它来回答"这部漫画的简介要不要现在去读"——
     * 只看 [hasMetadata] 的话，"目录里确实没有 XML"的作品每次打开详情页都会再枚举一次目录。
     */
    val metadataProbedAt: Long? = null,
    /** null = 已发现 ≥1 章但未枚举完（开发文档 5.1）。 */
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    /**
     * 滚动/搜索时**只数数量**得到的章节数；null = 数过但没数出可读章节，或还没数过。
     *
     * 与 [chapterCount]/[chapterCountKnown] 分工见 `MangaEntity.countedChapterCount`：
     * 后者属于"章节清单是否完整"的同步语义，这一对只服务于显示，且**永不**打开删除闸门。
     */
    val countedChapterCount: Int? = null,
    /** 滚动计数的时间；null = 从未数过（取过一次就不再取，与封面同规则）。 */
    val countedChapterCountAt: Long? = null,
    val availability: MangaAvailability,
    val discoveryGeneration: Long,
    val discoveredAt: Long,
    val updatedAt: Long,
    /** 旧版漫画级阅读覆盖，仅保留用于数据库和备份兼容；阅读器不再应用。 */
    val readerModeOverride: ReadingMode? = null,
    /** 旧版屏幕方向覆盖，阅读器不再应用。 */
    val readerOrientationOverride: ReaderOrientation? = null,
    /**
     * 漫画级翻译设置（源/目标语言、自动识别、漫画自己的文风）。
     *
     * 放这里而不是塞进 `MangaCard`：卡片是每批 30 张都要读的投影，而这几项只在
     * 详情页与翻译入队时用得到。
     */
    val translationSettings: MangaTranslationSettings = MangaTranslationSettings(),
)

/**
 * 旧版覆盖解析，用于兼容历史数据；当前阅读器直接使用全局设置。
 */
fun ReaderSettings.withMangaOverride(
    modeOverride: ReadingMode?,
    orientationOverride: ReaderOrientation?,
): ReaderSettings = copy(
    readingMode = modeOverride ?: readingMode,
    orientation = orientationOverride ?: orientation,
)

/**
 * 章节行（开发文档 15.3）。
 *
 * ## 两种"顺序"必须分开
 *
 * - [sortKey] 是**自然序**的预计算列：SQLite 只能做字典序比较，所以写入时就把
 *   「不区分大小写 + 数字按数值」的键算好。它**永远是自然序**，用来回答
 *   "哪一章是第一章"——封面（开发文档 7.2）与简介（7.1 第 2 条）都要求**自然序第一章**，
 *   不能因为用户手动拖过章节就换一章；
 * - [position] 是**显示顺序**：详情页章节列表唯一的排序依据。用户点排序方式时整表重排
 *   并重写它，手动拖动也改它。新探到的章节按用户已保存的排序方式插入（见
 *   `ChapterOrdering`），已有章节的相对顺序不变。
 *
 * 把两者塞进一个字段就会二选一地坏掉：要么用户拖一下就换了封面/简介的来源章节，
 * 要么手动顺序永远存不住。
 */
data class ChapterRecord(
    val chapterId: String,
    val mangaId: String,
    val documentId: String,
    val kind: ChapterKind,
    /** 目录名或去扩展名的文件名（开发文档 1.3）。 */
    val title: String,
    /** 自然序键；**只用于**"自然序第一章"与自然排序，不参与显示顺序。 */
    val sortKey: String,
    /** 显示位置，越小越靠前（开发文档 8.1 的章节列表）。 */
    val position: Long = 0L,
    /**
     * 目录/归档文件的修改时间；null = 提供方没有给出。
     *
     * 「按修改时间排序」用它。**不要**复用 [contentRevision]：那一列是"内容是否变化"的
     * 判据，没有 mtime 时会退化成常量，拿它排序会把一堆章节排成并列。
     */
    val modifiedAt: Long? = null,
    /** null = 尚未枚举页面；本步不建立页清单（框架 9.4）。 */
    val pageCount: Int?,
    /** 归档首图/目录首页（开发文档 7.2）。 */
    val coverDocumentId: String?,
    val contentRevision: Long,
    val discoveredAt: Long,
)

/** 漫画级阅读恢复点（开发文档 9、15.3）。 */
data class ReadingProgress(
    val mangaId: String,
    val chapterId: String?,
    val pageOrdinal: Int,
    val intraPageRatio: Float,
    val read: Boolean,
    val bookmark: Boolean,
    val updatedAt: Long,
)

/**
 * ComicInfo 解析结果：原文始终保留，未知字段不丢弃（开发文档 7）。
 *
 * [parseError] 非空表示格式错误或超过大小上限，此时 [fields] 为空但 [xml] 仍可
 * 展示，阅读与标题不受影响（开发文档 7.1）。
 */
data class MetadataRecord(
    /** mangaId 或 chapterId。 */
    val ownerId: String,
    val ownerType: MetadataOwnerType,
    /** XML 原文，始终保留（开发文档 7）。 */
    val xml: String,
    /** 已知字段；未知字段也保留在 map 中，键为元素本地名。 */
    val fields: Map<String, String>,
    val summary: String?,
    val series: String?,
    val title: String?,
    val writer: String?,
    val alternateSeries: String?,
    /** 规范化后的搜索文本：名称 + 别名 + 全部字段值 + XML 文本节点（框架 4.5）。 */
    val normalizedSearchText: String,
    /** 非空表示格式错误但仍保留原文。 */
    val parseError: String?,
    /** 例如「第一章 ComicInfo.xml」「漫画顶层兜底」（开发文档 7.1）。 */
    val sourceLabel: String,
    /** 稳定内容指纹，用于发现同长度同时间的变化（开发文档 6.2）。 */
    val fingerprint: String,
    val updatedAt: Long,
)

/**
 * 书架分类：首版单分类，内置「未分类」（categoryId = 0）不可删（开发文档 1.3、8.2）。
 */
data class Category(
    val categoryId: Long,
    val name: String,
    val styleMode: StyleMode,
    /** styleMode=CUSTOM 时的文本。 */
    val customStyle: String?,
    val orderIndex: Int,
    val revision: Long,
)

/**
 * 书架项：指向漫画的收藏关系，与源文件相互独立（开发文档 2）。
 *
 * 重扫只更新派生字段，**不得删除**书架条目（开发文档 15.3）。
 */
data class ShelfEntry(
    val mangaId: String,
    val categoryId: Long,
    val addedAt: Long,
)
