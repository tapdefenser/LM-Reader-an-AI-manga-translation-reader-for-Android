package com.lmreader.core.database.repository

import com.lmreader.core.database.LmReaderDatabase
import com.lmreader.core.database.dao.CardQueryRow
import com.lmreader.core.database.dao.MangaDao
import com.lmreader.core.database.entity.ChapterReadStateEntity
import com.lmreader.core.database.entity.toCard
import com.lmreader.core.database.entity.toDomain
import com.lmreader.core.database.entity.toEntity
import com.lmreader.core.index.ChapterOrdering
import com.lmreader.core.model.BookshelfSort
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChapterCountProbeTarget
import com.lmreader.core.model.CoverProbeTarget
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaBackfillTarget
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.MangaMetadataUpdate
import com.lmreader.core.model.MangaPage
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.MangaTranslationSettings
import com.lmreader.core.model.MetadataOwnerType
import com.lmreader.core.model.MetadataRecord
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ScanPersistReport
import com.lmreader.core.model.ScanResult
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 漫画仓储实现（开发文档 6.4、8.1；框架 3.6、5.2）。
 *
 * 关于分页的已知限制（框架 9.2）：这里用 `LIMIT/OFFSET`，不是开发文档 6.4 要求的
 * 会话顺序表 + keyset 分页。差别在扫描过程中翻页：新条目插入会让某一项在两次
 * 请求里都出现，因此 UI 必须按 `mangaId` 去重（[com.lmreader.ui.paging.PagingState]
 * 已经这样做）。排序键本身是稳定的（源顺序 → 自然名 → mangaId），所以不会丢项。
 */
internal class MangaRepositoryImpl(
    private val database: LmReaderDatabase,
    private val mangaDao: MangaDao,
    /**
     * 用户保存的章节排序方式。
     *
     * 数据库层不认识 DataStore，所以偏好从一个函数问进来：发现新章节时要按它决定
     * 插到哪里（见 `ChapterOrdering`）。默认值是"从未选过排序方式"——新章节追加到末尾。
     */
    private val chapterOrder: ChapterOrdering.SettingProvider = ChapterOrdering.SettingProvider {
        ChapterOrdering.Setting.DEFAULT
    },
) : MangaRepository {

    private val chapterDao = database.chapterDao()
    private val chapterReadStateDao = database.chapterReadStateDao()
    private val metadataDao = database.metadataDao()

    override suspend fun pageLibrary(
        offset: Int,
        limit: Int,
        sourceFilter: Set<String>?,
    ): MangaPage {
        val rows = when {
            // null = 不筛选；空集合 = 没有匹配（由界面决定怎么解释空筛选）。
            sourceFilter == null -> mangaDao.pageLibrary(offset, limit)
            sourceFilter.isEmpty() -> emptyList()
            else -> mangaDao.pageLibraryFiltered(sourceFilter.toList(), offset, limit)
        }
        return pageOf(rows, offset, limit)
    }

    override suspend fun pageShelf(
        categoryId: Long?,
        offset: Int,
        limit: Int,
        query: String?,
        sort: BookshelfSort,
    ): MangaPage {
        // 关键字与分类是正交的两个条件，现在由一条 SQL 里的 `:pattern IS NULL` /
        // `:categoryId IS NULL` 表达（见 MangaDao.pageShelf 的说明）。
        val pattern = query?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "%" + escapeLike(it.lowercase()) + "%" }
        val rows = mangaDao.pageShelf(
            categoryId = categoryId,
            pattern = pattern,
            mode = sort.mode.name,
            descending = sort.descending,
            offset = offset,
            limit = limit,
        )
        return pageOf(rows, offset, limit)
    }

    override suspend fun shelfMangaIds(categoryId: Long?, query: String?): List<String> {
        // 转义规则与 pageShelf 保持一致：同一个关键词在"列表"与"一键更新范围"
        // 上必须命中同一批漫画，否则用户会看到"列表里 3 部、却更新了 5 部"。
        val pattern = query?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { "%" + escapeLike(it.lowercase()) + "%" }
        return mangaDao.shelfMangaIds(categoryId, pattern)
    }

    override fun observeVisibleCount(inShelfOnly: Boolean, categoryId: Long?): Flow<Int> = when {
        !inShelfOnly -> mangaDao.observeCount()
        categoryId == null -> mangaDao.observeShelfTotal()
        else -> mangaDao.observeShelfCountInCategory(categoryId)
    }

    /**
     * 扫描过程中「可以补位了」的信号。
     *
     * 实现说明：契约里叫 observeVisibleCount，但这里刻意用 `MAX(rowid)` 而不是
     * `COUNT(*)`。原因是扫描会写入已存在的漫画（重扫），COUNT 不变而列表内容已经
     * 变了；反过来删除又会让 COUNT 变小，按「数量没变就不刷新」的写法会漏掉批次。
     * rowid 只增不减，每次插入都会推一次，正好对应发现阶段的批量写入。
     * 契约语义（订阅可见集合的变化以补足当前额度）不变，因此没有改签名。
     */
    override fun observeDiscoveryProgress(): Flow<Long> = mangaDao.observeDiscoveryProgress()

    override suspend fun getCards(mangaIds: List<String>): List<MangaCard> {
        if (mangaIds.isEmpty()) return emptyList()
        return mangaDao.cardsByIds(mangaIds).map { it.toCard() }
    }

    override suspend fun getChapters(mangaId: String): List<ChapterRecord> =
        chapterDao.getByManga(mangaId).map { it.toDomain() }

    override suspend fun getChaptersInDisplayOrder(mangaId: String): List<ChapterRecord> =
        chapterDao.getByMangaInDisplayOrder(mangaId).map { it.toDomain() }

    override suspend fun setChapterOrder(mangaId: String, orderedChapterIds: List<String>) {
        database.withTransaction {
            // 传入的清单必须正好等于这部漫画的章节集合：少一个就会留下旧位置的行，
            // 与重排后的下标撞车（两行同 position，列表顺序变得不确定）。
            // 调用方永远拿当前完整列表来算，所以这里是"宁可整体不写"的兜底。
            val current = chapterDao.chapterIdsOf(mangaId)
            if (current.size != orderedChapterIds.size || current.toHashSet() != orderedChapterIds.toHashSet()) {
                return@withTransaction
            }
            orderedChapterIds.forEachIndexed { index, chapterId ->
                chapterDao.updatePosition(chapterId, index.toLong())
            }
        }
    }

    /**
     * 一批卡片的封面探测输入。
     *
     * 两次查询（漫画 + 来源的 JOIN、这些漫画的全部章节）就够：章节按
     * `mangaId, sortKey` 排序返回，分组后每组第一条就是第一章，与详情页的
     * `getChapters` 是同一个顺序。
     */
    override suspend fun coverProbeTargets(mangaIds: List<String>): List<CoverProbeTarget> {
        if (mangaIds.isEmpty()) return emptyList()
        val rows = mangaDao.probeTargets(mangaIds)
        if (rows.isEmpty()) return emptyList()
        val firstChapters = chapterDao.getByMangas(rows.map { it.mangaId })
            .groupBy { it.mangaId }
            .mapValues { (_, chapters) -> chapters.first().toDomain() }
        return rows.map { row ->
            CoverProbeTarget(
                mangaId = row.mangaId,
                anchorDocumentId = row.anchorDocumentId,
                layoutMode = row.layoutMode,
                sourceTreeUri = row.sourceTreeUri,
                firstChapter = firstChapters[row.mangaId],
            )
        }
    }

    /**
     * 章节计数的探测输入：**只查一次**（不需要章节表）。
     *
     * 与 [coverProbeTargets] 的差别就在这儿：封面要"第一章是哪一章"，而数章节数只要
     * 打开锚点目录列一遍子项，因此这次多出来的第二次查询可以省掉。
     */
    override suspend fun chapterCountProbeTargets(
        mangaIds: List<String>,
    ): List<ChapterCountProbeTarget> {
        if (mangaIds.isEmpty()) return emptyList()
        return mangaDao.probeTargets(mangaIds).map { row ->
            ChapterCountProbeTarget(
                mangaId = row.mangaId,
                anchorDocumentId = row.anchorDocumentId,
                layoutMode = row.layoutMode,
                sourceTreeUri = row.sourceTreeUri,
            )
        }
    }

    override suspend fun markChapterCounted(mangaId: String, count: Int?, at: Long) {
        mangaDao.markChapterCounted(mangaId, count, at)
    }

    override suspend fun updateChapterPageInfo(
        chapterId: String,
        pageCount: Int,
        coverDocumentId: String?,
    ) {
        chapterDao.updateDerivedFields(chapterId, pageCount, coverDocumentId)
    }

    override suspend fun markCoverProbed(
        mangaId: String,
        coverDocumentId: String?,
        coverChapterId: String?,
        at: Long,
    ) {
        mangaDao.markCoverProbed(mangaId, coverDocumentId, coverChapterId, at)
    }

    override suspend fun pendingBackfillIds(limit: Int): List<String> =
        mangaDao.pendingBackfillIds(limit)

    override suspend fun setAvailabilityBySource(sourceId: String, availability: MangaAvailability) {
        mangaDao.updateAvailabilityBySource(sourceId, availability)
    }

    override suspend fun updateReaderOverrides(
        mangaId: String,
        mode: ReadingMode?,
        orientation: ReaderOrientation?,
    ) {
        // 存枚举名称而不是序数：序数会在枚举增删或重排后悄悄指向另一个值。
        mangaDao.updateReaderOverrides(mangaId, mode?.name, orientation?.name)
    }

    override suspend fun markUndiscoveredAsStale(sourceId: String, generation: Long): Int =
        mangaDao.markUndiscoveredAsStale(sourceId, generation)

    override suspend fun markOrphanedAsStale(): Int = mangaDao.markOrphanedAsStale()

    override fun observeVisibleCountsBySource(): Flow<Map<String, Int>> =
        mangaDao.observeVisibleCountsBySource().map { rows ->
            rows.associate { row -> row.sourceId to row.itemCount }
        }

    /**
     * 取补全工作投影。
     *
     * 返回 null 的两种情形要分开看：漫画行不存在（用户删了来源）与来源行不存在
     * （配置被改坏）。两者都不该让补全崩溃，因此统一返回 null 由调用方跳过。
     */
    override suspend fun getBackfillTarget(mangaId: String): MangaBackfillTarget? {
        val manga = mangaDao.getById(mangaId) ?: return null
        val source = database.sourceDao().getById(manga.sourceId) ?: return null
        return MangaBackfillTarget(
            manga = manga.toDomain(),
            chapters = chapterDao.getByManga(mangaId).map { it.toDomain() },
            sourceTreeUri = source.treeUri,
            sourceKind = source.kind,
            sourcePermission = source.permission,
            hasCover = manga.coverDocumentId != null,
            hasMetadata = manga.hasMetadata,
        )
    }

    /**
     * 应用补全结果。
     *
     * 事务边界：漫画派生字段与 ComicInfo 记录必须一起提交。如果先写简介再写记录时
     * 崩溃，界面会显示有一份不存在的简介来源，用户点进详情却看不到原文
     * （开发文档 7.1 要求"显示来源章节和更新时间"）。
     */
    override suspend fun applyMetadataUpdate(update: MangaMetadataUpdate) =
        database.withTransaction {
            mangaDao.updateDerivedFields(
                mangaId = update.mangaId,
                coverDocumentId = update.coverDocumentId,
                coverChapterId = update.coverChapterId,
                summary = update.summary,
                author = update.author,
                hasMetadata = update.hasMetadata,
                metadataProbedAt = update.metadataProbedAt,
                at = update.at,
            )
            update.records.forEach { metadataDao.upsert(it.toEntity()) }

            // 漫画级搜索投影（开发文档 6.4）：只写文本，不在此处生成 2-gram
            // （框架 9.1 的已知限制）。空文本保持为空串，表示"确实没有简介"。
            //
            // 关键点：漫画级投影与"漫画目录顶层 ComicInfo"共用 ownerId = mangaId
            // 这一行。若本次已经写入了真实的顶层 XML，就**不能再覆盖**它，否则
            // 详情页会显示一份没有原文的简介（开发文档 7.1 要求显示来源章节）。
            val hasMangaLevelXml = update.records.any { it.ownerId == update.mangaId }
            if (!hasMangaLevelXml) {
                val existing = metadataDao.getByOwner(update.mangaId)?.toDomain()
                metadataDao.upsert(
                    (existing ?: MetadataRecord(
                        ownerId = update.mangaId,
                        ownerType = MetadataOwnerType.MANGA,
                        xml = "",
                        fields = emptyMap(),
                        summary = null,
                        series = null,
                        title = null,
                        writer = null,
                        alternateSeries = null,
                        normalizedSearchText = "",
                        parseError = null,
                        sourceLabel = "",
                        fingerprint = "",
                        updatedAt = update.at,
                    )).copy(
                        summary = update.summary ?: existing?.summary,
                        writer = update.author ?: existing?.writer,
                        normalizedSearchText = update.searchText,
                        updatedAt = update.at,
                    ).toEntity(),
                )
            }
        }

    override suspend fun observeTotalCount(): Flow<Int> = mangaDao.observeCount()

    override suspend fun search(
        query: String,
        offset: Int,
        limit: Int,
        sourceFilter: Set<String>?,
    ): MangaPage {
        val trimmed = query.trim()
        // 关键词为空时退化成"浏览"，因此筛选必须一并带上；否则清空搜索框会突然
        // 把被筛掉的图源放回来（用户会以为筛选失效了）。
        if (trimmed.isEmpty()) return pageLibrary(offset, limit, sourceFilter)
        val pattern = "%" + escapeLike(trimmed.lowercase()) + "%"
        val rows = when {
            sourceFilter == null -> mangaDao.search(pattern, offset, limit)
            sourceFilter.isEmpty() -> emptyList()
            else -> mangaDao.searchInSources(sourceFilter.toList(), pattern, offset, limit)
        }
        return pageOf(rows, offset, limit)
    }

    /**
     * 落库一次扫描结果。
     *
     * 事务边界内的规则（框架 5.2）：
     * 1. 漫画行按主键 upsert，派生字段随后由补全阶段覆盖，这里只写发现阶段已知的值；
     * 2. 章节按 `documentId` 在扫描结果内 upsert，**不做整体删除再插入**——章节行
     *    的 ID 会被阅读进度与译文引用，删掉再建会静默丢用户数据（开发文档 15.3）；
     * 3. 只有「锚点目录已被完整枚举」**且**「扫描器声明这份章节清单完整」
     *    （`chapterCountKnown`）时才删除消失的章节；`fullyEnumeratedContainers`
     *    为空（扫描被取消或 IO 失败）时一律不删（验收 A07）。
     *    两个条件缺一不可：发现阶段会完整枚举锚点目录却只发出部分章节，
     *    只判前者会把用户手动补齐的章节列表删掉（真机 NovaSamus 59 个 zip 的归档）。
     */
    override suspend fun upsertScanResult(result: ScanResult): ScanPersistReport {
        // 排序方式在事务**之前**读：它是 DataStore 里的偏好，读它不该把数据库事务
        // 撑长（也不该让事务里出现与数据库无关的挂起点）。
        val orderSetting = chapterOrder.current()
        return database.withTransaction {
            val manga = result.manga
            val source = database.sourceDao().getById(manga.sourceId)
            // A09 版本门禁：扫描期间修改类型/递归/目录会使 revision 自增。
            // 旧扫描即使随后才发出结果，也只能得到一个空报告，绝不能覆盖新配置。
            // 这次来源查询原本就用于读取 sourceOrderIndex，因此版本校验不增加每部
            // 漫画的数据库往返次数。
            if (source == null || source.revision != result.sourceRevision) {
                return@withTransaction ScanPersistReport(
                    mangasInserted = 0,
                    mangasUpdated = 0,
                    chaptersInserted = 0,
                    chaptersRemoved = 0,
                    mangasMarkedUnavailable = 0,
                    accepted = false,
                )
            }
            val sourceOrder = source.orderIndex
            val existing = mangaDao.getById(manga.mangaId)

            val entity = if (existing == null) {
                manga.toEntity(sourceOrderIndex = sourceOrder)
            } else {
                // 保留补全阶段已经写入的派生字段：封面、简介、作者、可用性。
                // 发现阶段每次都会带着 null 重发，覆盖会让已经显示出来的封面消失。
                manga.toEntity(sourceOrderIndex = sourceOrder).copy(
                    coverDocumentId = manga.coverDocumentId ?: existing.coverDocumentId,
                    coverChapterId = manga.coverChapterId ?: existing.coverChapterId,
                    // 封面探测记录必须一起保留：发现阶段完全不知道它，每次重扫都带 null
                    // 重发，抹掉之后那些"探测过但没有封面"的卡片会在下一次滚动里被
                    // 重新枚举一遍目录，滚动越深越慢。
                    coverProbedAt = existing.coverProbedAt,
                    metadataProbedAt = existing.metadataProbedAt,
                    summary = manga.summary ?: existing.summary,
                    author = manga.author ?: existing.author,
                    hasMetadata = manga.hasMetadata || existing.hasMetadata,
                    discoveredAt = existing.discoveredAt,
                    availability = MangaAvailability.AVAILABLE,
                    // 阅读覆盖是**用户的设置**，不是扫描派生字段：发现阶段完全不知道它，
                    // 每次重扫都会带 null 重发。不在这里保留就会被静默清空——用户会看到
                    // "我明明把这部漫画设成条漫了，怎么又变回去"。
                    readerModeOverride = existing.readerModeOverride,
                    readerOrientationOverride = existing.readerOrientationOverride,
                )
            }
            mangaDao.upsert(entity)

            val incomingIds = result.chapters.mapTo(HashSet()) { it.documentId }
            val existingIds = chapterDao.documentIdsOf(manga.mangaId).toHashSet()

            // 事务内先删后插：`(documentId, kind)` 是唯一键，若同一 documentId 换了
            // kind（理论上不同来源种类才会发生），留下的旧行会让 upsert 撞唯一约束。
            val obsolete = existingIds - incomingIds
            val anchorEnumerated = manga.anchorDocumentId in result.fullyEnumeratedContainers
            // 删除必须同时满足"锚点目录被完整枚举"与"扫描器声明这份章节清单是完整的"。
            //
            // 只判 `anchorEnumerated` 是不够的，而且会毁数据：多章节发现阶段的
            // `scanManga` 通过 `enumerateOnce()` 把锚点目录记入 `fullyEnumerated`
            // （它确实列了该目录），但随后只发出**部分**章节。于是用户手动「更新章节」
            // 补齐的章节列表，会被下一次扫描用"这份清单里没有你"删掉。
            // 真机样例：`/Tachiyomi/local/NovaSamus/` 有 59 个 zip，发现阶段只发 1 个，
            // 用户点「更新章节」补齐 60 章后，下一次扫描会把另外 59 行删掉，
            // 连带删掉引用它们的 `reading_progress`（该表没有外键级联）。
            //
            // `chapterCountKnown` 正是"这份清单完整"的既有声明（开发文档 5.1：
            // 不得把探测到一章伪报成完整的一章），因此用它当删除的前提。
            // 部分枚举时章节数会被重算为已发现数（见下方 else 分支），不会虚报。
            val incomingIsComplete = anchorEnumerated && manga.chapterCountKnown
            val removed = if (obsolete.isNotEmpty() && incomingIsComplete) {
                chapterDao.deleteByDocumentIds(manga.mangaId, obsolete.toList())
                obsolete.size
            } else {
                0
            }

            if (result.chapters.isNotEmpty()) {
                // 显示顺序（开发文档 8.1 的章节列表）在这里落定：
                // - 已有章节**保持原位**，一个都不动；
                // - 新探到的章节按用户保存的排序方式**插入**到对应位置；从没选过排序方式时
                //   追加到末尾，同一批之间按首字母（`ChapterOrdering` 的口径）。
                //
                // 必须先读删除后的当前顺序：`obsolete` 刚被删掉，它们不该再参与插入定位。
                val existingChapters = chapterDao.getByMangaInDisplayOrder(manga.mangaId)
                    .map { it.toDomain() }
                val existingById = existingChapters.associateBy { it.chapterId }
                val ordered = ChapterOrdering.planInsertion(
                    existing = existingChapters,
                    discovered = result.chapters,
                    setting = orderSetting,
                )
                val positionById = ordered.associate { it.chapterId to it.position }

                chapterDao.upsertAll(
                    result.chapters.map { chapter ->
                        val previous = existingById[chapter.chapterId]
                        chapter.toEntity().copy(
                            // 位置只能来自上面的插入计划；扫描器不知道它，直接用会归零。
                            position = positionById[chapter.chapterId] ?: previous?.position ?: 0L,
                            // mtime 缺失时保留上次读到的值：提供方偶尔不返回时间，
                            // 归零会让"按修改时间排序"把这一章甩到最后。
                            modifiedAt = chapter.modifiedAt ?: previous?.modifiedAt,
                        )
                    },
                )
            }
            // 章节数只在锚点完整枚举**且扫描器自己声明已知**时才敢声明"已知"
            // （开发文档 5.1：不得把探测到一章伪报成完整的一章）。
            // 多章节的发现阶段只探测一个章节，因此这里是"已发现 N 章，更新中"，
            // 不是"共 N 章"——完整清单由详情页的「更新章节」枚举。
            if (incomingIsComplete) {
                chapterDao.refreshChapterCount(manga.mangaId)
            } else if (existing?.chapterCountKnown != true) {
                // 仍需记录当前已发现的章节数（否则卡片会显示"章节待更新"而看不出有几个）。
                //
                // 但**不能**把已经知道的完整章节数降级：普通「刷新」只做发现阶段（每部只
                // 探测一个章节），而用户之前点过「更新章节」、或者列表本来就已经完整。
                // 无条件降级会让每次刷新都把"共 10 章"变成"已发现 1 章，更新中"——信息
                // 明明还在库里（章节行没被删，见上面的 incomingIsComplete 门禁）。
                chapterDao.markChapterCountUnknown(manga.mangaId, result.chapters.size)
            }

            ScanPersistReport(
                mangasInserted = if (existing == null) 1 else 0,
                mangasUpdated = if (existing == null) 0 else 1,
                chaptersInserted = (incomingIds - existingIds).size,
                chaptersRemoved = removed,
                mangasMarkedUnavailable = 0,
            )
        }
    }

    override suspend fun deleteManga(mangaId: String) {
        // 外键 ON DELETE CASCADE 会一并清掉章节与书架项；这是"用户明确删除"的路径，
        // 与重扫的"派生字段更新"是两件事（开发文档 15.3）。
        mangaDao.delete(mangaId)
    }

    override suspend fun chapterReadMarks(mangaId: String): Map<String, Boolean> =
        chapterReadStateDao.marksOf(mangaId).associate { it.chapterId to it.read }

    override suspend fun setChapterRead(chapterIds: List<String>, read: Boolean) {
        if (chapterIds.isEmpty()) return
        if (!read) {
            // 未读 = 没有行。只在表里留"用户标记过已读"的章节，500 章里标 3 章时
            // 不该为其余 497 章各写一行。
            chapterReadStateDao.deleteAll(chapterIds)
            return
        }
        val at = System.currentTimeMillis()
        val owners = chapterDao.mangaIdsOf(chapterIds).associate { it.chapterId to it.mangaId }
        chapterReadStateDao.upsertAll(
            chapterIds.mapNotNull { chapterId ->
                // 章节行不存在的 id 直接跳过，否则外键会拒绝整批写入。
                val mangaId = owners[chapterId] ?: return@mapNotNull null
                ChapterReadStateEntity(
                    chapterId = chapterId,
                    mangaId = mangaId,
                    read = true,
                    updatedAt = at,
                )
            },
        )
    }

    override suspend fun translationSettings(mangaId: String): MangaTranslationSettings =
        mangaDao.getById(mangaId)?.toDomain()?.translationSettings ?: MangaTranslationSettings()

    override suspend fun updateTranslationSettings(
        mangaId: String,
        settings: MangaTranslationSettings,
    ) {
        mangaDao.updateTranslationSettings(
            mangaId = mangaId,
            sourceLanguage = settings.sourceLanguage?.trim()?.takeIf { it.isNotEmpty() },
            autoDetectSource = settings.autoDetectSource,
            targetLanguage = settings.targetLanguage?.trim()?.takeIf { it.isNotEmpty() },
            styleMode = settings.styleMode?.name,
            customStyle = settings.customStyle,
            workflowId = settings.workflowId,
            apiProfileId = settings.apiProfileId,
            pageMode = settings.pageMode?.name,
            segThreshold = settings.segThreshold,
            segTextScope = settings.segTextScope.name,
            bubbleFillMode = settings.bubbleFillMode?.name,
            bubbleOpacity = settings.bubbleOpacityPercent,
            bubblePadding = settings.bubbleTextPaddingPercent,
            bubbleFont = settings.bubbleFont?.name,
            bubbleFontScale = settings.bubbleFontScalePercent,
            bubbleBold = settings.bubbleBold,
            textDetectionThreshold = settings.textDetectionThreshold,
            freeTextMaskExpansion = settings.freeTextMaskExpansionPercent,
            freeTextMergeGapRatio = settings.freeTextMergeGapRatio,
        )
    }

    private suspend fun pageOf(rows: List<CardQueryRow>, offset: Int, limit: Int): MangaPage {
        val items = rows.map { it.toCard() }
        val total = mangaDao.count()
        val nextOffset = offset + rows.size
        return MangaPage(
            items = items,
            nextOffset = nextOffset,
            // 用「本批不满」判断结束而不是 offset >= total：扫描中 total 还在涨，
            // 拿它比较会让"恰好取满最后一页"误判为还有更多，多打一次空查询。
            exhausted = rows.size < limit,
            totalKnown = total,
        )
    }

    private companion object {
        /** LIKE 通配符转义；`\` 是 SQL 里声明的 ESCAPE 字符（与 DAO 查询一致）。 */
        fun escapeLike(raw: String): String = buildString(raw.length) {
            raw.forEach { ch ->
                when (ch) {
                    '\\', '%', '_' -> {
                        append('\\')
                        append(ch)
                    }

                    else -> append(ch)
                }
            }
        }
    }
}
