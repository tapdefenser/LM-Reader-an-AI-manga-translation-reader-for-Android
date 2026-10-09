package com.lmreader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.lmreader.core.database.entity.MangaEntity
import com.lmreader.core.model.LayoutMode
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.SourceKind
import kotlinx.coroutines.flow.Flow

/**
 * 卡片投影行：图库/书架列表一次查询取齐（开发文档 8.1）。
 *
 * 列名必须与下面的 SQL 别名一致；Room 只按名字匹配，改名不会编译失败，
 * 只会在运行时抛缺列异常，所以两处改动必须同时进行。
 */
data class CardQueryRow(
    val mangaId: String,
    val displayName: String,
    val summaryPreview: String?,
    val sourceId: String,
    val coverDocumentId: String?,
    val coverChapterId: String?,
    /** 封面探测时间；null = 从未探测过（见 `MangaEntity.coverProbedAt`）。 */
    val coverProbedAt: Long?,
    val sourceKind: SourceKind,
    val layoutMode: LayoutMode,
    val chapterCount: Int?,
    val chapterCountKnown: Boolean,
    /**
     * 滚动时数出来的章节数（只数数量，不落章节清单）。
     *
     * 与 [chapterCount] 分开是**必须**的：`chapterCountKnown` 同时是同步逻辑的
     * "可以删掉多余章节行"闸门，而滚动时的计数并没有真的枚举并落库章节清单，
     * 因此那一位必须保持 0（见 `MangaRepositoryImpl` 里 `incomingIsComplete` 的说明）。
     * 这两个计数列互不干扰：全量同步写 `chapterCount/chapterCountKnown`，滚动计数
     * 写 `countedChapterCount/countedChapterCountAt`。
     */
    val countedChapterCount: Int?,
    /** 计数时间；null = 从未数过（照 `coverProbedAt` 的"取过就不再取"语义）。 */
    val countedChapterCountAt: Long?,
    val availability: MangaAvailability,
    /**
     * 这张卡片的章节里是否有归档章节（CBZ/ZIP/PDF）。
     *
     * 为什么由查询算：一次遍历同时识别图片与归档之后，「来源种类」不再说明卡片内容
     * （一个来源里可以同时有图片章节的漫画与压缩包章节的漫画），卡片徽标必须看**章节**。
     * 用 `EXISTS` 子查询而不是新增列：章节表本来就有 `(mangaId, sortKey)` 索引，
     * 每行只在这个漫画自己的章节里找一次，不需要迁移与维护冗余列。
     */
    val hasArchiveChapters: Boolean,
    /** null = 不在书架；用于推导 `MangaCard.inShelf`。 */
    val shelfCategoryId: Long?,
)

/** 图源筛选栏的持久计数投影。 */
data class SourceVisibleCountRow(
    val sourceId: String,
    val itemCount: Int,
)

/**
 * 懒加载探测的输入投影（图库/书架滚动时，封面与章节计数共用）。
 *
 * 与 [CardQueryRow] 分开的理由：卡片投影每批都要读，而探测输入只对"还没取到、且
 * 从未探测过"的少数卡片需要，且必须带上锚点目录与来源树 URI（卡片上没有这两个字段）。
 * 两种探测需要的字段恰好相同（都要打开锚点目录），因此共用一条查询、一个行类型。
 */
data class ProbeTargetRow(
    val mangaId: String,
    val anchorDocumentId: String,
    val layoutMode: LayoutMode,
    /** 来源的授权树 URI；目录只能按「树 URI + documentId」组合打开（开发文档 4.1）。 */
    val sourceTreeUri: String,
)

/**
 * 漫画与卡片的查询（开发文档 6.4、8.1）。
 *
 * 分页用 `LIMIT/OFFSET`（框架 9.2 的已知限制），排序键固定在
 * `sourceOrderIndex → sortKey → mangaId`：前两列分别表达「源顺序」与「自然名称」，
 * 最后一列保证同序时结果稳定，否则翻页会出现重复与丢失。
 */
@Dao
interface MangaDao {
    @Query("SELECT displayName FROM mangas WHERE translationWorkflowId = :workflowId ORDER BY displayName LIMIT 20")
    suspend fun workflowReferences(workflowId: String): List<String>

    /**
     * 必须使用真正的 UPDATE/INSERT upsert，不能使用 INSERT OR REPLACE。
     *
     * SQLite 的 REPLACE 会先删除旧的 mangas 父行；chapters 与 shelf_entries 对
     * mangaId 都配置了 ON DELETE CASCADE，因此一次普通重扫就会清空章节和书架关系。
     * Room 的 [Upsert] 在主键已存在时执行 UPDATE，父行身份不会被删除。
     */
    @Upsert
    suspend fun upsert(entity: MangaEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: MangaEntity): Long

    @Query("SELECT * FROM mangas WHERE mangaId = :mangaId")
    suspend fun getById(mangaId: String): MangaEntity?

    @Query("SELECT * FROM mangas WHERE anchorDocumentId = :documentId AND sourceKind = :kind")
    suspend fun getByAnchor(documentId: String, kind: SourceKind): MangaEntity?

    /**
     * 可见卡片总数。
     *
     * `availability != 'STALE'`：陈旧卡片（本来源最近一次完整扫描没有再发现的旧卡片）
     * 默认不出现在图库/书架，总数必须与列表同一口径，否则界面会显示"共 N 项"却只滚
     * 得到更少的卡片。
     */
    @Query("SELECT COUNT(*) FROM mangas WHERE availability != 'STALE'")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM mangas WHERE availability != 'STALE'")
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT sourceId, COUNT(*) AS itemCount
        FROM mangas
        WHERE availability != 'STALE'
        GROUP BY sourceId
        """,
    )
    fun observeVisibleCountsBySource(): Flow<List<SourceVisibleCountRow>>

    /**
     * 单调递增的发现进度。
     *
     * 为什么不用 `COUNT(*)` 当刷新信号：扫描过程中条目数会因为重扫去重与删除
     * 忽增忽减，用它触发"还有空位就补入"的判断会漏掉批次；rowid 只会增长，
     * 每插入一行就变一次，正好对应"发现阶段批量写数据库"（开发文档 6.1）。
     */
    @Query("SELECT COALESCE(MAX(rowid), 0) FROM mangas")
    fun observeDiscoveryProgress(): Flow<Long>

    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageLibrary(offset: Int, limit: Int): List<CardQueryRow>

    /**
     * 图库分页 + 图源筛选。
     *
     * 筛选放在 SQL 里而不是取回后内存过滤：万级图库只勾选一个来源时，
     * 内存过滤要先读回全部行再丢弃，等于把分页的意义抹掉（开发文档 6.4）。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.sourceId IN (:sourceIds)
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageLibraryFiltered(sourceIds: List<String>, offset: Int, limit: Int): List<CardQueryRow>

    /**
     * 书架列表：分类（可空）+ 关键字（可空）+ 排序方式，**一条查询**。
     *
     * ## 为什么合成一条
     *
     * 原来拆成"分不分类 × 搜不搜索"四条查询，四份 SQL 里各有一份 `ORDER BY`。现在排序
     * 有 3 种方式 × 2 个方向，再复制四份就是 24 处要同步的地方——加一种排序方式要改四处，
     * 漏一处就会出现"在某个分类里搜索时不按设置排序"这种极难发现的偏差。
     * `:categoryId IS NULL` / `:pattern IS NULL` 把四种组合收进一条。
     *
     * 代价：`(:categoryId IS NULL OR s.categoryId = :categoryId)` 可能让 SQLite 放弃
     * `categoryId` 索引。书架是**用户自己收藏的那些**（几百到几千行，不是全库几万），
     * 一次全扫 + 排序仍远低于一次分页的预算；以"排序规则只有一处"换这点开销是值得的。
     *
     * ## 排序为什么要用 CASE
     *
     * Room 不能把 `ORDER BY` 当参数传。用 `CASE WHEN :mode = '...' THEN <列> END` 逐项
     * 求出"这一模式下该比较的值"，不匹配的模式返回 NULL：SQLite 的 `ORDER BY a, b` 里
     * NULL 不参与比较，于是恰好只有生效的那一项决定顺序。方向靠同一项写两次 ASC/DESC
     * 表达，避免再用一个字符串拼 SQL。
     *
     * "最近阅读"里**从没读过**的作品 `updatedAt` 是 NULL：ASC 时排最前（= 最久没碰，
     * 符合"最该读的排前面"），DESC 时排最后（= 最近读过的在最上面）。两种方向都合理，
     * 因此不额外用 COALESCE 把它们强行归到某一端。
     *
     * 最后两级 `m.sortKey, m.mangaId` 是**并列兜底**：主键相同的一组（从没读过的那些、
     * 同一时刻加入的几本）如果只用 mangaId 兜底，用户看到的就是随机顺序。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        LEFT JOIN reading_progress AS p ON p.mangaId = m.mangaId
        WHERE m.availability != 'STALE'
          AND (:categoryId IS NULL OR s.categoryId = :categoryId)
          AND (
            :pattern IS NULL
            OR m.displayName LIKE :pattern ESCAPE '\'
            OR md.normalizedSearchText LIKE :pattern ESCAPE '\'
          )
        ORDER BY
          CASE WHEN :mode = 'NAME' AND :descending = 0 THEN m.sortKey END ASC,
          CASE WHEN :mode = 'NAME' AND :descending = 1 THEN m.sortKey END DESC,
          CASE WHEN :mode = 'ADDED' AND :descending = 0 THEN s.addedAt END ASC,
          CASE WHEN :mode = 'ADDED' AND :descending = 1 THEN s.addedAt END DESC,
          CASE WHEN :mode = 'READ' AND :descending = 0 THEN p.updatedAt END ASC,
          CASE WHEN :mode = 'READ' AND :descending = 1 THEN p.updatedAt END DESC,
          -- 最新章节更新时间：该漫画**所有章节里最晚的那个** `modifiedAt`（用户口径）。
          -- 目录章节的 `modifiedAt` 就是目录自身的 mtime，归档章节是文件自身的 mtime，
          -- 所以这就是"这部作品的文件最近一次是什么时候变的"。
          --
          -- 用相关子查询而不是再加一列：那一列要在每次发现/同步章节时维护，而书架只有
          -- 用户收藏的几百到几千部，按 `mangaId` 取一次 MAX 很便宜（与 `hasArchiveChapters`
          -- 那个 EXISTS 子查询同一个取舍）。
          --
          -- 没有章节、或章节都没有时间戳的作品得到 NULL：逆向时排最后（"没更新过"），
          -- 正向时排最前，两种方向都说得通，不额外用 COALESCE 归到某一端。
          CASE WHEN :mode = 'RECENT_CHAPTER' AND :descending = 0
               THEN (SELECT MAX(c.modifiedAt) FROM chapters AS c WHERE c.mangaId = m.mangaId) END ASC,
          CASE WHEN :mode = 'RECENT_CHAPTER' AND :descending = 1
               THEN (SELECT MAX(c.modifiedAt) FROM chapters AS c WHERE c.mangaId = m.mangaId) END DESC,
          -- 并列时退回名称：主排序键相同的一组（从没读过的那些、同一时刻加入的几本）
          -- 若用 mangaId 兜底，在用户眼里就是"随机顺序"。名称是用户唯一能预期的兜底。
          m.sortKey ASC,
          m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageShelf(
        categoryId: Long?,
        pattern: String?,
        mode: String,
        descending: Boolean,
        offset: Int,
        limit: Int,
    ): List<CardQueryRow>

    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.mangaId IN (:mangaIds)
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        """,
    )
    suspend fun cardsByIds(mangaIds: List<String>): List<CardQueryRow>

    /**
     * 关键字搜索。
     *
     * 本步只做漫画名与 `normalized_search_text` 的子串匹配（框架 9.1 的已知限制）：
     * 开发文档 6.4 的 2-gram 侧表与 `MangaSearchDocument` 投影是 P1 项，未实现。
     * 因此这里**不能**声称万级全库搜索已达性能目标。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE (m.displayName LIKE :pattern ESCAPE '\'
           OR md.normalizedSearchText LIKE :pattern ESCAPE '\')
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun search(pattern: String, offset: Int, limit: Int): List<CardQueryRow>

    /**
     * 同 [search]，但限定在已勾选的图源内。
     *
     * 为什么必须单独一个查询而不是"搜完全库再在内存里过滤"：图库是分页读的，
     * 内存过滤会让"本页 30 条里恰好 2 条属于勾选的图源"变成一页 2 条，
     * 而且 `nextOffset` 会按未过滤的总数前进，于是**结果越翻越少、还会漏项**。
     * 筛选必须下推到 SQL，和 [pageLibraryFiltered] 一样。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        LEFT JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE (m.displayName LIKE :pattern ESCAPE '\'
           OR md.normalizedSearchText LIKE :pattern ESCAPE '\')
          AND m.availability != 'STALE'
          AND m.sourceId IN (:sourceIds)
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchInSources(
        sourceIds: List<String>,
        pattern: String,
        offset: Int,
        limit: Int,
    ): List<CardQueryRow>

    /**
     * 在**书架范围内**按关键字搜索。
     *
     * 与 [search] 的差别只有 `JOIN shelf_entries`：书架是收藏引用，因此搜索必须限定在
     * 已收藏的那些作品里，否则会搜出图库里没收藏的漫画（开发文档 8.2）。
     * 判据与 [search] 相同：名称 + `normalized_search_text` 子串匹配。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE (m.displayName LIKE :pattern ESCAPE '\'
           OR md.normalizedSearchText LIKE :pattern ESCAPE '\')
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchShelf(pattern: String, offset: Int, limit: Int): List<CardQueryRow>

    /** 同上，但限定在某个分类内。 */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.displayName AS displayName,
               COALESCE(md.summary, m.summary) AS summaryPreview,
               m.sourceId AS sourceId,
               m.coverDocumentId AS coverDocumentId,
               m.coverChapterId AS coverChapterId,
               m.coverProbedAt AS coverProbedAt,
               m.sourceKind AS sourceKind,
               m.layoutMode AS layoutMode,
               m.chapterCount AS chapterCount,
               m.chapterCountKnown AS chapterCountKnown,
               m.countedChapterCount AS countedChapterCount,
               m.countedChapterCountAt AS countedChapterCountAt,
               m.availability AS availability,
               EXISTS(
                   SELECT 1 FROM chapters AS c
                   WHERE c.mangaId = m.mangaId AND c.kind = 'ARCHIVE'
               ) AS hasArchiveChapters,
               s.categoryId AS shelfCategoryId
        FROM mangas AS m
        JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE s.categoryId = :categoryId
          AND (m.displayName LIKE :pattern ESCAPE '\'
           OR md.normalizedSearchText LIKE :pattern ESCAPE '\')
          AND m.availability != 'STALE'
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun searchShelfInCategory(
        categoryId: Long,
        pattern: String,
        offset: Int,
        limit: Int,
    ): List<CardQueryRow>

    /**
     * 当前书架筛选（分类 + 关键词）下的**全部**漫画 ID，不分页。
     *
     * 用途只有一个：书架的「一键更新章节」要知道该更新哪几部。它必须跟着筛选走
     * （用户要求："更新所有（经过筛选的）书架页里漫画的章节"），因此**不能**只取
     * 已加载的那几页，也不能整库更新。
     *
     * 两个筛选条件都用可空参数在一个查询里表达，而不是按组合写四份：SQL 的
     * `:param IS NULL OR ...` 让"不筛"与"筛"共用一条语句，四份拷贝里任何一份走形
     * 都会变成"某个组合下更新了不该更新的漫画"，而那种 bug 只在特定组合下出现。
     *
     * @param categoryId null = 「全部」
     * @param pattern 已转义并小写的 LIKE 模式；null = 不按关键词筛
     */
    @Query(
        """
        SELECT m.mangaId FROM mangas AS m
        JOIN shelf_entries AS s ON s.mangaId = m.mangaId
        LEFT JOIN metadata_records AS md
               ON md.ownerId = m.mangaId AND md.ownerType = 'MANGA'
        WHERE m.availability != 'STALE'
          AND (:categoryId IS NULL OR s.categoryId = :categoryId)
          AND (
                :pattern IS NULL
                OR m.displayName LIKE :pattern ESCAPE '\'
                OR md.normalizedSearchText LIKE :pattern ESCAPE '\'
              )
        ORDER BY m.sourceOrderIndex ASC, m.sortKey ASC, m.mangaId ASC
        """,
    )
    suspend fun shelfMangaIds(categoryId: Long?, pattern: String?): List<String>

    /**
     * 书架条目数。陈旧卡片不算进去：它们默认不出现在书架上（行本身保留，
     * 卡片被重新发现时会自动回到书架）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM shelf_entries AS e
        JOIN mangas AS m ON m.mangaId = e.mangaId
        WHERE m.availability != 'STALE'
        """,
    )
    fun observeShelfTotal(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM shelf_entries AS e
        JOIN mangas AS m ON m.mangaId = e.mangaId
        WHERE e.categoryId = :categoryId
          AND m.availability != 'STALE'
        """,
    )
    fun observeShelfCountInCategory(categoryId: Long): Flow<Int>

    @Query("DELETE FROM mangas WHERE mangaId = :mangaId")
    suspend fun delete(mangaId: String)

    /**
     * 补全阶段写回派生字段。
     *
     * 用 `COALESCE(:value, 原值)` 而不是直接赋值：补全可能只拿到封面、没拿到简介
     * （首章目录临时不可读），直接覆盖会把已经显示的简介清空。
     * `hasMetadata` 用 OR 同理——读过一次 XML 就不该因为本次没读到而退回"无简介"。
     */
    @Query(
        """
        UPDATE mangas
        SET coverDocumentId = COALESCE(:coverDocumentId, coverDocumentId),
            coverChapterId = COALESCE(:coverChapterId, coverChapterId),
            summary = COALESCE(:summary, summary),
            author = COALESCE(:author, author),
            hasMetadata = CASE WHEN :hasMetadata = 1 THEN 1 ELSE hasMetadata END,
            metadataProbedAt = COALESCE(:metadataProbedAt, metadataProbedAt),
            updatedAt = :at
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun updateDerivedFields(
        mangaId: String,
        coverDocumentId: String?,
        coverChapterId: String?,
        summary: String?,
        author: String?,
        hasMetadata: Boolean,
        metadataProbedAt: Long?,
        at: Long,
    )

    @Query("SELECT * FROM mangas WHERE sourceId = :sourceId")
    suspend fun getBySource(sourceId: String): List<MangaEntity>

    /**
     * 批量取懒加载探测输入：漫画的锚点目录 + 布局模式 + 来源树 URI。
     *
     * 用 JOIN 一次取齐而不是分三次查：滚动时这个动作每批都要做，而每张卡片三次往返
     * 会让"边滚边取"自己变成卡顿的来源。来源行不存在（孤儿卡片）时该行直接不返回，
     * 调用方因此不会把"来源没了"误标成"探测过了、只是没有结果"。
     *
     * 封面与章节计数共用本查询（两者需要的字段相同）。
     */
    @Query(
        """
        SELECT m.mangaId AS mangaId,
               m.anchorDocumentId AS anchorDocumentId,
               m.layoutMode AS layoutMode,
               s.treeUri AS sourceTreeUri
        FROM mangas AS m
        JOIN library_sources AS s ON s.sourceId = m.sourceId
        WHERE m.mangaId IN (:mangaIds)
        """,
    )
    suspend fun probeTargets(mangaIds: List<String>): List<ProbeTargetRow>

    /**
     * 写入"滚动时数出来的章节数"。
     *
     * **只写 [countedChapterCount] 那一对，绝不动 `chapterCount`/`chapterCountKnown`**：
     * 后者是同步逻辑"可以删掉多余章节行"的闸门，而这里并没有枚举并落库章节清单。
     * 数不出章节时也写时间（`count = null`）——那正是"不再数第二次"的载体，与
     * `coverProbedAt` 的失败也落库同理。
     */
    @Query(
        """
        UPDATE mangas
        SET countedChapterCount = :count,
            countedChapterCountAt = :at
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun markChapterCounted(mangaId: String, count: Int?, at: Long)

    /**
     * 待补全**简介**（ComicInfo）的漫画。
     *
     * 两个条件缺一不可：
     * - `hasMetadata = 0`：还没有读到 XML；
     * - `metadataProbedAt IS NULL`：**还没有去读过**。缺了这条，读不到 XML 的条目会
     *   永久占据队列头部，每轮补全都重开同一批目录（真机上 5408 部全是 `hasMetadata = 0`，
     *   也就是队列从来不前进）。
     *
     * 封面已经搬去图库滚动懒加载，因此这里**不再**看 `coverDocumentId`。
     */
    @Query(
        """
        SELECT mangaId FROM mangas
        WHERE availability = 'AVAILABLE'
          AND hasMetadata = 0
          AND metadataProbedAt IS NULL
        ORDER BY sourceOrderIndex ASC, sortKey ASC, mangaId ASC
        LIMIT :limit
        """,
    )
    suspend fun pendingBackfillIds(limit: Int): List<String>

    /**
     * 写入一次封面探测结果（图库滚动懒加载 / 详情页「更新章节」）。
     *
     * 与 [updateDerivedFields] 的区别：这里的 null 是**结论**（"确实没有首图"）而不是
     * "本次没有新值"，所以直接赋值而不是 COALESCE，并且一定会写 `coverProbedAt`。
     * 三者必须一起更新：只写 `coverProbedAt` 不写封面会让已探测的卡片永远空白，
     * 只写封面不写 `coverProbedAt` 会让失败项每次滚动都被重新枚举。
     */
    @Query(
        """
        UPDATE mangas
        SET coverDocumentId = :coverDocumentId,
            coverChapterId = :coverChapterId,
            coverProbedAt = :at
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun markCoverProbed(
        mangaId: String,
        coverDocumentId: String?,
        coverChapterId: String?,
        at: Long,
    )

    /**
     * 来源顺序变化时重排冗余列。
     *
     * 与 `library_sources.orderIndex` 必须成对更新，否则图库排序会与路径表不一致
     * （开发文档 4.1 拖动排序、6.4 有效源顺序）。历史分叉（同一来源下 1 与 4 并存）
     * 由 v3→v4 迁移里的一次性校正负责，这里只保证之后的写入不再产生新的分叉。
     */
    @Query("UPDATE mangas SET sourceOrderIndex = :orderIndex WHERE sourceId = :sourceId")
    suspend fun updateSourceOrder(sourceId: String, orderIndex: Int)

    @Query("UPDATE mangas SET availability = :availability WHERE sourceId = :sourceId")
    suspend fun updateAvailabilityBySource(sourceId: String, availability: MangaAvailability)

    /**
     * 写入漫画级阅读覆盖（阅读模式与屏幕方向），传 null 即清除覆盖。
     *
     * 单独的 UPDATE 而不是整行 upsert：覆盖是用户设置，只该被"用户改了它"这一件事改写，
     * 用整行 upsert 会让任何一次重扫都有机会把它抹掉。
     */
    @Query("UPDATE mangas SET readerModeOverride = :mode, readerOrientationOverride = :orientation WHERE mangaId = :mangaId")
    suspend fun updateReaderOverrides(mangaId: String, mode: String?, orientation: String?)

    /**
     * 写入漫画级翻译设置（语言与文风覆盖）。
     *
     * 与阅读覆盖同样用**独立 UPDATE** 而不是整行 upsert：这几项是用户的设置，
     * 整行 upsert 会让任何一次重扫都有机会把它们抹掉。null 是**有意义的结论**
     * （"这一层没设置、往下回退"），所以直接赋值，不用 COALESCE。
     */
    @Query(
        """
        UPDATE mangas
        SET translationSourceLanguage = :sourceLanguage,
            translationAutoDetectSource = :autoDetectSource,
            translationTargetLanguage = :targetLanguage,
            translationStyleMode = :styleMode,
            translationCustomStyle = :customStyle,
            translationWorkflowId = :workflowId,
            translationApiProfileId = :apiProfileId,
            translationPageMode = :pageMode,
            translationSegThreshold = :segThreshold,
            translationSegTextScope = :segTextScope,
            translationBubbleFillMode = :bubbleFillMode,
            translationBubbleOpacity = :bubbleOpacity,
            translationBubblePadding = :bubblePadding,
            translationBubbleFont = :bubbleFont,
            translationBubbleFontScale = :bubbleFontScale,
            translationBubbleBold = :bubbleBold,
            translationTextDetectionThreshold = :textDetectionThreshold,
            translationFreeTextMaskExpansion = :freeTextMaskExpansion,
            translationFreeTextMergeGapRatio = :freeTextMergeGapRatio
        WHERE mangaId = :mangaId
        """,
    )
    suspend fun updateTranslationSettings(
        mangaId: String,
        sourceLanguage: String?,
        autoDetectSource: Boolean,
        targetLanguage: String?,
        styleMode: String?,
        customStyle: String?,
        workflowId: String?,
        apiProfileId: String?,
        pageMode: String?,
        segThreshold: Float?,
        segTextScope: String?,
        bubbleFillMode: String?,
        bubbleOpacity: Int?,
        bubblePadding: Int?,
        bubbleFont: String?,
        bubbleFontScale: Int?,
        bubbleBold: Boolean?,
        textDetectionThreshold: Float?,
        freeTextMaskExpansion: Int?,
        freeTextMergeGapRatio: Float?,
    )

    /**
     * 把该来源里"不是本轮发现的"卡片标成陈旧（[MangaAvailability.STALE]）。
     *
     * 只在来源扫描**完整跑完**之后调用（调用点见
     * [com.lmreader.core.model.MangaRepository.markUndiscoveredAsStale]）：取消、目录读取
     * 失败、授权失效时一律不能调用，否则会把"这次没读到"误判成"已经不存在"（验收 A07）。
     *
     * `discoveryGeneration` 是发现阶段每次写入都带上的本次扫描代次，所以"本轮是否发现"
     * 不需要回传一份 ID 清单；`availability != 'STALE'` 让返回值正好等于"本次新隐藏的
     * 数量"，重复调用不会把同一批卡片反复计数。
     *
     * 只改可用性、不删行：章节、书架关系、阅读进度与译文全部保留（外键级联删除会
     * 把这些一起带走，因此这里绝不能改成 DELETE）。
     */
    @Query(
        """
        UPDATE mangas
        SET availability = 'STALE'
        WHERE sourceId = :sourceId
          AND discoveryGeneration != :generation
          AND availability != 'STALE'
        """,
    )
    suspend fun markUndiscoveredAsStale(sourceId: String, generation: Long): Int

    /**
     * 把一个来源的**全部**卡片标成陈旧；删除该来源行之前调用。
     *
     * 为什么不是删行：用户可能只是想换一个目录，过一会儿又把原目录加回来。漫画行
     * 按稳定 ID 保存，加回来再扫一次就会重新发现同一批 ID，卡片、书架关系、阅读进度
     * 与译文都会原样回来；删行则会级联带走这些用户数据（开发文档 4.1「授权在所有
     * 引用释放后再释放」的同一思路：先保住数据，再谈清理）。
     */
    @Query("UPDATE mangas SET availability = 'STALE' WHERE sourceId = :sourceId AND availability != 'STALE'")
    suspend fun markSourceAsStale(sourceId: String): Int

    /**
     * 把"来源行已经不存在的"卡片标成陈旧（孤儿卡片清扫）。
     *
     * 历史遗留：`mangas` 没有指向 `library_sources` 的外键，早期版本删掉一条路径之后
     * 卡片会永远留在图库里（真机实测 4749 张卡片里 4595 张是这种孤儿）。这条清扫在
     * 启动时跑一次，把它们归入陈旧、从图库/书架隐藏，但**不删行**。
     */
    @Query(
        """
        UPDATE mangas
        SET availability = 'STALE'
        WHERE availability != 'STALE'
          AND sourceId NOT IN (SELECT sourceId FROM library_sources)
        """,
    )
    suspend fun markOrphanedAsStale(): Int
}
