package com.lmreader.core.model

import kotlinx.coroutines.flow.Flow

/**
 * 来源仓储契约（框架 3.6）。实现在 `core:database`。
 *
 * 顺带说明为什么接口在 `core:model` 而不是 `core:database`：扫描调度（`core:storage`）
 * 与 UI（`:app`）都要用同一组签名，若接口跟着 Room 走，双方都会被拖去依赖数据库模块。
 */
interface SourceRepository {
    /**
     * 全部路径来源，按用户排序（`orderIndex`）。
     *
     * 只有**一张**路径表：一次遍历同时解释图片目录与 CBZ/ZIP/PDF，来源的 `kind`
     * 不再区分表，只保留为身份与徽标（见 `SourceKind`）。
     */
    fun observeSources(): Flow<List<LibrarySource>>
    suspend fun getSources(): List<LibrarySource>
    suspend fun saveSource(source: LibrarySource): LibrarySource
    suspend fun deleteSource(sourceId: String)
    suspend fun getSource(sourceId: String): LibrarySource?

    /** 拖动排序：立即持久化（开发文档 4.1）。 */
    suspend fun reorder(orderedSourceIds: List<String>)
    suspend fun updateScanResult(sourceId: String, at: Long, status: ScanRunStatus, error: String?)
    suspend fun updatePermission(sourceId: String, permission: SourcePermissionState)

    /**
     * 把"上次扫描被中断"（进程被杀）而停在 RUNNING 的来源收敛成 FAILED。
     *
     * 应用启动时调用一次；返回本次收敛的数量。不收敛的话路径表会永远显示"正在扫描"，
     * 用户无法区分"真的在扫"与"上次没扫完"。
     */
    suspend fun clearInterruptedScans(reason: String): Int
}

/**
 * 漫画仓储契约（框架 3.6）。实现在 `core:database`。
 */
interface MangaRepository {
    /**
     * 按当前来源顺序与自然名称排序取一页；只返回已发现条目，不阻塞扫描（开发文档 6.4）。
     *
     * @param sourceFilter 图源筛选：`null` = 不筛选（显示全部）。**空集合表示"没有
     * 匹配项"**而不是"显示全部"——仓储不做这层解释，否则"用户取消了所有勾选"与
     * "用户没有筛选"无法区分；界面负责决定空筛选按哪种语义处理。
     */
    suspend fun pageLibrary(
        offset: Int,
        limit: Int,
        sourceFilter: Set<String>? = null,
    ): MangaPage

    /**
     * 取书架的一页。
     *
     * @param categoryId `null` = 「全部」（未分类 + 所有自建分类）
     * @param query 关键字；`null` 或空白 = 不搜索。搜索与分类是**正交**的两个条件，
     *   同时给出时必须都生效——否则用户在某个分类里搜索会看到别的分类的书。
     */
    /**
     * 书架分页。
     *
     * @param sort 书架的显示排序：**全局偏好、不落库**——书架不建排序表也不支持拖动
     *   （用户口径："不需要拖动，不建每分类的排序方法，就是一个全局显示的排序"）。
     *   分类与关键字是筛选，排序只决定这些筛选结果内部的顺序。
     */
    suspend fun pageShelf(
        categoryId: Long?,
        offset: Int,
        limit: Int,
        query: String? = null,
        sort: BookshelfSort = BookshelfSort.DEFAULT,
    ): MangaPage

    /** 订阅「可见集合长度」变化，用于扫描过程中把新条目补进当前额度（开发文档 6.4）。 */
    /**
     * 当前书架筛选（分类 + 关键词）下的**全部**漫画 ID，不分页。
     *
     * 给书架的「一键更新章节」用：更新范围必须与用户当前看到的筛选一致
     * （"更新所有（经过筛选的）书架页里漫画的章节"），因此这里刻意返回全部 ID
     * 而不是已加载的那几页。
     *
     * @param categoryId null = 「全部」
     * @param query 关键词；空白 = 不按关键词筛
     */
    suspend fun shelfMangaIds(categoryId: Long?, query: String?): List<String>

    fun observeVisibleCount(inShelfOnly: Boolean, categoryId: Long?): Flow<Int>

    /**
     * 各来源当前可见卡片数。
     *
     * 图源筛选栏必须读取持久索引，而不是读取本进程的扫描状态；否则冷启动按要求
     * 不自动扫描时，每个来源都会错误显示“共 0 项”。
     */
    fun observeVisibleCountsBySource(): Flow<Map<String, Int>>

    /**
     * 扫描写入进度信号，只增不减；每次插入一行漫画就发射一次。
     *
     * 与 [observeVisibleCount] 的分工：后者给出「现在该显示多少」（绝对值，会因删除
     * 与重扫上下波动），本方法给出「又有新条目落库了」（单调触发信号）。UI 在额度
     * 还有空位时用本信号补入下一批，避免用计数比较来推断"是否有新增"。
     */
    fun observeDiscoveryProgress(): Flow<Long>

    suspend fun getCards(mangaIds: List<String>): List<MangaCard>

    /**
     * 为一批卡片准备封面探测所需的输入（图库/书架滚动懒加载）。
     *
     * 一次批量查询而不是每张卡片各查一次：一批 30 张卡片若各自查"漫画 + 来源 + 第一章"，
     * 就是 90 次数据库往返，而滚动时这个动作每批都要做一遍。
     *
     * 返回的列表只包含**存在且来源仍有效**的卡片；找不到的 id 直接不出现在结果里
     * （调用方对它们不做任何事，也就不会误标成"已探测"）。
     */
    suspend fun coverProbeTargets(mangaIds: List<String>): List<CoverProbeTarget>

    /**
     * 批量取**章节计数**探测输入（漫画 + 锚点目录 + 布局模式 + 来源树 URI）。
     *
     * 与 [coverProbeTargets] 同源同规则（都只返回存在且来源仍有效的卡片），只是服务
     * 另一件事：滚动/搜索到可见时数一次章节数（用户口径："滚动+搜索结果时加载，
     * 不要做成扫描时加载"）。
     */
    suspend fun chapterCountProbeTargets(mangaIds: List<String>): List<ChapterCountProbeTarget>

    /**
     * 写入一次章节计数结果。
     *
     * `count = null` 表示"数过但没数出可读章节"，同样要落库标记时间——否则每次滚动
     * 都会对同一批必然失败的卡片重来一遍。
     *
     * **不得触碰 `chapterCount`/`chapterCountKnown`**：那是章节清单完整性的声明，
     * 也是同步逻辑删除多余章节行的闸门，而这里并没有落库章节清单。
     */
    suspend fun markChapterCounted(mangaId: String, count: Int?, at: Long)

    /**
     * 取补全阶段需要的工作投影（开发文档 6.1 第 2 步）。
     *
     * 返回 null 表示漫画行已不存在（例如用户刚删掉了整个来源）。
     */
    suspend fun getBackfillTarget(mangaId: String): MangaBackfillTarget?

    /** 应用一次补全结果；`null` 字段表示"本次没有新值"，不覆盖已有值。 */
    suspend fun applyMetadataUpdate(update: MangaMetadataUpdate)

    /**
     * 尚未补全的漫画 ID（缺封面或缺 XML 简介），按分页顺序返回。
     *
     * 补全不能只在"当前可见卡片"上做：开发文档 6.4 要求「后台补全扫描不以已显示
     * 卡片为前提」，否则只有滚到过的作品才可被简介搜索命中（验收 A10）。
     */
    suspend fun pendingBackfillIds(limit: Int): List<String>

    /** 来源不可用时把其下漫画标灰，但**保留**卡片与用户数据（开发文档 8.2）。 */
    suspend fun setAvailabilityBySource(sourceId: String, availability: MangaAvailability)

    /**
     * 把该来源里"本轮没有被再发现"的卡片标成 [MangaAvailability.STALE]。
     *
     * 调用时机只有一个：该来源的扫描**完整跑完**（`ScanSummary.completed = true`）之后。
     * 取消、目录读取失败、授权失效时**不得**调用——那会把"这次没读到"误判成"已经不存在"
     * （验收 A07「失败不删索引」）。开发文档 6.2 的删除判定依据同源：只有完整枚举过的
     * 容器才能用来判断"什么已经消失"。
     *
     * 为什么用 generation 而不是回传一份 ID 清单：发现阶段每次写入都已经把本次
     * generation 写进卡片行，一条 `UPDATE ... WHERE discoveryGeneration != :generation`
     * 就能表达"本轮没再发现"，万级来源也不必构造超长 `IN (...)`。
     *
     * 只改 `availability`，不删行：书架关系、章节、阅读进度与译文全部保留，
     * 卡片被重新发现时会自动回到 [MangaAvailability.AVAILABLE]。
     *
     * @return 本次**新**标成陈旧的卡片数（已经是陈旧的不重复计数）。
     */
    suspend fun markUndiscoveredAsStale(sourceId: String, generation: Long): Int

    /**
     * 把"来源行已经不存在"的卡片标成陈旧（孤儿卡片清扫），返回本次新标的数量。
     *
     * 为什么需要单独一条：`mangas` 没有指向 `library_sources` 的外键，删除一条路径
     * 不会带走它的卡片。真机实测某次图库里 4749 张卡片中有 4595 张是这种孤儿，
     * 界面因此完全没法看。启动时跑一次把它们从图库/书架隐藏（仍然只改可用性、
     * 不删行，用户把原目录加回来再扫一次就会全部恢复）。
     */
    suspend fun markOrphanedAsStale(): Int

    /** 批量取章节；按自然序（`sortKey`）返回，封面取第一章不依赖调用方再排序。 */
    suspend fun getChapters(mangaId: String): List<ChapterRecord>

    /**
     * 详情页章节列表：**按用户看到的顺序**（`position`）返回。
     *
     * 与 [getChapters] 分成两条而不是让调用方自己排：封面与简介要求"**自然序**第一章"
     * （开发文档 7.1/7.2），而用户手动拖过章节之后这两者必然不同。合成一条就一定会
     * 有一边是错的。
     */
    suspend fun getChaptersInDisplayOrder(mangaId: String): List<ChapterRecord>

    /**
     * 写入这部漫画的完整章节显示顺序（`orderedChapterIds` 的下标就是新的 `position`）。
     *
     * 排序抽屉"整表重排"与手动拖动共用它：**顺序是整体属性**，一次拖动的结果必须
     * 整条链一起落库，局部更新在重排时会留下互相冲突的中间状态。
     */
    suspend fun setChapterOrder(mangaId: String, orderedChapterIds: List<String>)

    /**
     * 该漫画**按章**的已读标记（章节 id → 是否已读）。
     *
     * 为什么不是 `reading_progress.read`：那张表一部漫画只有一行（"读到哪一章哪一页"），
     * 它的 `read` 描述的是**那一章**，不是每一章。章节列表要给每章显示已读状态、
     * 多选要能批量标记，因此必须有按章的状态。
     *
     * 存在独立表而不是 `chapters` 的列：开发文档 15.3 要求"索引可变状态与用户状态分表"——
     * `chapters` 会被每次扫描重写，用户标记混在里面的唯一结局是被重扫抹掉。
     */
    suspend fun chapterReadMarks(mangaId: String): Map<String, Boolean>

    /** 批量设置已读/未读（章节多选底栏）。 */
    suspend fun setChapterRead(chapterIds: List<String>, read: Boolean)

    /**
     * 漫画级翻译设置（语言与文风覆盖）。
     *
     * 放在这里而不是翻译仓储里：它们是 `mangas` 表的列，与"语言/文风跟着漫画走"
     * （用户口径）一致；翻译仓储只管两张新表（章节翻译状态、译名字典）。
     */
    suspend fun translationSettings(mangaId: String): MangaTranslationSettings

    suspend fun updateTranslationSettings(mangaId: String, settings: MangaTranslationSettings)

    /** 页源完整枚举后回填页数和首页；不改变章节身份。 */
    suspend fun updateChapterPageInfo(chapterId: String, pageCount: Int, coverDocumentId: String?)

    /**
     * 写入一次**封面探测**的结果（图库滚动懒加载 / 详情页更新章节）。
     *
     * 与 `coverDocumentId = COALESCE(...)` 那套"null 表示本次没有新值"的语义**不同**：
     * 这里的 null 是**有意义的结论**——"这个位置确实没有可用首图"。因此它会同时写
     * [coverProbedAt]，让界面与后续探测都知道"不用再试了"。
     *
     * @param coverDocumentId 探测到的首图；null = 探测过了但没有
     * @param coverChapterId 封面所属章节；与 [coverDocumentId] 同生共死
     */
    suspend fun markCoverProbed(
        mangaId: String,
        coverDocumentId: String?,
        coverChapterId: String?,
        at: Long,
    )

    /**
     * 写入漫画级阅读覆盖（开发文档 15.3「漫画级阅读偏好归数据库」）。
     *
     * 用独立方法而不是走 [upsertScanResult]：覆盖是**用户的设置**，与扫描派生字段的
     * 生命周期完全不同。混在一起会让"重扫时保留用户设置"变成一个需要每个调用点都
     * 记得处理的约定；独立方法把它变成类型上难以出错的事。
     *
     * @param mode 阅读模式覆盖；null 表示清除覆盖、回到全局默认
     * @param orientation 屏幕方向覆盖；null 表示清除覆盖
     */
    suspend fun updateReaderOverrides(
        mangaId: String,
        mode: ReadingMode?,
        orientation: ReaderOrientation?,
    )

    /**
     * 落库一次扫描结果。必须在单个事务内完成，并且只有 `completed = true` 的扫描
     * 才允许删除章节（框架 5.2）。
     */
    suspend fun upsertScanResult(result: ScanResult): ScanPersistReport
    /**
     * 按关键字搜索。
     *
     * @param sourceFilter 与 [pageLibrary] 同一个语义：`null` = 不筛；空集合 = 没有匹配
     *   （当前勾选的图源一个都没有）。**搜索必须落在筛选结果之内**，否则用户在"只看某个
     *   图源"的状态下搜索会看到别的图源的书，而标题还写着"图库（1 个图源）"。
     */
    suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        sourceFilter: Set<String>? = null,
    ): MangaPage
    suspend fun deleteManga(mangaId: String)
    suspend fun observeTotalCount(): Flow<Int>
}

/**
 * 书架仓储契约（框架 3.6）。实现在 `core:database`。
 */
interface ShelfRepository {
    fun observeCategories(): Flow<List<Category>>

    /** 取内置「未分类」的 categoryId（0），首次调用时确保它存在（框架 5.3）。 */
    suspend fun ensureUncategorized(): Long

    /**
     * 这部漫画所在的分类；null = 不在书架（或来源已失效）。
     *
     * 文风覆盖链要从"漫画 → **分类** → 全局"逐层回退，因此详情页必须知道分类是谁。
     * 不给 `MangaCard` 加这一列：卡片是每批 30 张都要读的投影，而分类只在这一处用到。
     */
    suspend fun categoryIdOf(mangaId: String): Long?
    suspend fun createCategory(name: String): Category
    suspend fun renameCategory(categoryId: Long, name: String)
    suspend fun updateCategoryStyle(categoryId: Long, mode: StyleMode, customStyle: String?)
    suspend fun deleteCategory(categoryId: Long)

    /** 删除分类后其收藏移到未分类，不删漫画（开发文档 8.2）。 */
    suspend fun reorderCategories(orderedIds: List<Long>)
    suspend fun addToShelf(mangaId: String, categoryId: Long)
    /** 自动收藏到未分类；已收藏的漫画保留原分类和收藏时间。 */
    suspend fun ensureOnShelf(mangaId: String)
    suspend fun removeFromShelf(mangaId: String)

    /**
     * 书架条目数。
     *
     * 刻意**不是** suspend：它返回的是冷流，订阅本身不挂起，做成 suspend 只会
     * 强迫调用方在没有必要时也进入协程（分类侧栏在 Compose 里直接 collect）。
     */
    fun observeShelfCount(categoryId: Long?): Flow<Int>
}

/** 阅读恢复点与派生索引分开，重扫不得删除（开发文档 15.3）。 */
interface ReadingProgressRepository {
    suspend fun get(mangaId: String): ReadingProgress?
    suspend fun save(progress: ReadingProgress)
}

/**
 * 翻译数据（阶段 2：待翻译记录与漫画译名字典；引擎是 P3）。
 *
 * ## 为什么"入队"必须是真的
 *
 * 详情页多选底栏的「翻译所选」如果只弹个提示，就成了开发文档 17 明令禁止的
 * "仅摆放未接线的核心控件"。因此这一层真的写记录：侧栏「翻译队列」的计数因此会变，
 * 章节行会显示「待翻译」徽标，P3 的引擎接上来时直接读这批记录就能开工。
 *
 * ## 状态与译文分开
 *
 * [enqueue] 只写"要翻"这件事，[clearTranslations] 才动"译文"（条数与时间戳）。
 * 这样用户只想取消排队时不会连译文一起丢——即使现在还没有译文可丢。
 */
interface TranslationRepository {

    /** 该漫画**已有记录**的章节（没有记录的章节 = 未翻译）。 */
    suspend fun chapterTranslations(
        mangaId: String,
    ): Map<String, ChapterTranslation>

    /**
     * 入队：把选中章节置为待翻译（幂等，已经是 PENDING 的不重复写）。
     *
     * 已经在翻译中或已完成的章节**不动**——「翻译所选」不该把已完成的作品退回去重翻。
     * 有效的非空请求同时将漫画加入书架，已收藏的漫画保留原分类。
     *
     * @return 真正新入队的章节数
     */
    suspend fun enqueue(
        mangaId: String,
        chapterIds: List<String>,
        request: TranslationRequest,
    ): Int

    /** 删除章节记录；应用层先取消执行并删除页译文 JSON。清除不依赖语言配置，不重新入队。 */
    suspend fun clearTranslations(
        mangaId: String,
        chapterIds: List<String>,
    ): Int

    /** 全库待翻译（含翻译中）章节数；侧栏「翻译队列」的角标。 */
    fun observePendingCount(): Flow<Int>

    /** 该漫画的译名字典（按原词排序，供列表展示）；**只和漫画有关，与语言无关**。 */
    suspend fun glossary(mangaId: String): List<GlossaryEntry>

    /** 只新增空缺原词；已有译名一律保留，与 manual 无关。 */
    suspend fun upsertGlossary(entry: GlossaryEntry)

    /** 用户直接编辑选中的条目；改名不能覆盖另一个已存在的原词。 */
    suspend fun editGlossary(originalSource: String, entry: GlossaryEntry) {
        error("此存储不支持编辑译名")
    }

    suspend fun deleteGlossary(mangaId: String, source: String)
}
