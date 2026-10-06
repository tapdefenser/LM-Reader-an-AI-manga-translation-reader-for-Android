package com.lmreader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.LibraryDisplayMode
import com.lmreader.core.model.Category
import com.lmreader.core.model.MangaAvailability
import com.lmreader.core.model.MangaCard
import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.needsChapterCountProbe
import com.lmreader.core.model.ResolvedCover
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.storage.cover.CoverMetadataWriter
import com.lmreader.core.storage.cover.CoverResolver
import com.lmreader.core.storage.scan.ChapterCounter
import com.lmreader.core.storage.scan.LibraryScanCoordinator
import com.lmreader.core.storage.scan.ScanReason
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.ui.common.ChapterCountProbeQueue
import com.lmreader.ui.common.CoverProbeQueue
import com.lmreader.ui.common.ScreenState
import com.lmreader.ui.paging.PageSlice
import com.lmreader.ui.paging.PagingState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 图库页（开发文档 8.1、6.4）。
 *
 * 三条必须守住的行为：
 * 1. **边扫边显示**：扫描在后台继续，界面只按 40 项额度增长，绝不等待全库扫完；
 * 2. **不阻塞**：读缓存立即出结果，刷新只是发起扫描；
 * 3. **不伪造总数**：总数未知时只显示"已发现 N 项"。
 */
class LibraryViewModel(
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val shelfRepository: ShelfRepository,
    private val scanCoordinator: LibraryScanCoordinator,
    private val preferences: AppPreferences,
    private val coverResolver: CoverResolver,
    private val coverMetadataWriter: CoverMetadataWriter,
    private val chapterCounter: ChapterCounter,
) : ViewModel() {

    private val paging = PagingState<MangaCard>(idOf = { it.mangaId })

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    /**
     * 封面懒加载队列（用户要求：**滚动到可见才取**，一次一批，取过就不再取）。
     *
     * 与 `loadMore()` 完全解耦：加载列表项与取封面是两条独立协程，取封面慢不会让
     * 列表停在加载态。
     */
    private val coverProbes = CoverProbeQueue(
        scope = viewModelScope,
        probeTargets = mangaRepository::coverProbeTargets,
        resolve = coverResolver::resolve,
        // 落库走 CoverMetadataWriter：写封面的同时把简介也读掉（用户要求：
        // 在所有更新封面的时候也要更新简介）。两者读的是同一批目录。
        persist = { mangaId, cover, at -> coverMetadataWriter.write(mangaId, cover, at) },
        onResolved = ::onCoverResolved,
    )

    /**
     * 章节计数懒加载队列（用户口径：**滚动 + 搜索结果时**加载，不要做成扫描时加载）。
     *
     * 与封面队列同形、同样与 `loadMore()` 解耦。它只写 `countedChapterCount` 那一对列，
     * 不落章节清单、不碰 `chapterCountKnown`（见 `ChapterCounter` 的说明）。
     */
    private val chapterCounts = ChapterCountProbeQueue(
        scope = viewModelScope,
        probeTargets = mangaRepository::chapterCountProbeTargets,
        count = chapterCounter::count,
        persist = { mangaId, count, at -> mangaRepository.markChapterCounted(mangaId, count, at) },
        onCounted = ::onChapterCounted,
    )

    private val queryFlow = MutableStateFlow("")

    /**
     * 已生效的图源筛选。
     *
     * 与右滑栏里的草稿分开：用户可能在栏里改了半天又按"取消"，
     * 已生效的筛选必须在那之前保持不动（与路径编辑弹窗同一套约定）。
     */
    private var appliedSourceFilter: Set<String> = emptySet()

    /** Serialize page queries; discard results belonging to a replaced filter/search session. */
    private val pageLoadMutex = Mutex()
    private var sessionRevision = 0L

    init {
        paging.requestInitial()
        viewModelScope.launch {
            shelfRepository.ensureUncategorized()
            shelfRepository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        viewModelScope.launch {
            preferences.libraryDisplayMode.collect { mode ->
                _state.update { it.copy(displayMode = mode) }
            }
        }
        // 图源筛选：持久化的选择在冷启动时恢复，并且只影响查询，不触发扫描。
        viewModelScope.launch {
            preferences.librarySourceFilter.collect { saved ->
                appliedSourceFilter = saved
                _state.update { it.copy(appliedSourceFilter = saved, draftSourceFilter = saved) }
                resetSession()
                loadMore()
            }
        }
        // 图源列表（右滑栏内容）+ 每个来源已发现的数量。
        // 只有一张来源表：图片与归档由同一次扫描一起识别。
        viewModelScope.launch {
            sourceRepository.observeSources().collect { sources ->
                _state.update { it.copy(sources = sources) }
            }
        }
        viewModelScope.launch {
            mangaRepository.observeVisibleCountsBySource().collect { counts ->
                _state.update { current -> current.copy(discoveredBySource = counts) }
            }
        }
        viewModelScope.launch {
            scanCoordinator.overall.collect { overall -> _state.update { it.copy(scan = overall) } }
        }
        // 扫描期间新条目落库：额度还有空位就立即补入（开发文档 6.4）。
        viewModelScope.launch {
            mangaRepository.observeDiscoveryProgress().collect {
                if (paging.hasFreeSlot()) loadMore()
            }
        }
        // 新记录可能排到 OFFSET 之前（同名作品也按各自 ID 排序），追加查询会漏掉它。
        // 扫描结束后从零核对已申请的范围，包括未到底的列表，保留用户的分页额度。
        viewModelScope.launch {
            var wasRunning = false
            var lastFinishedAt: Long? = null
            var initialized = false
            scanCoordinator.overall
                .map { it.running to it.lastFinishedAt }
                .distinctUntilChanged()
                .collect { (running, finishedAt) ->
                    // A short scan can finish between two StateFlow observations.
                    val finished = !running && (wasRunning ||
                        (initialized && finishedAt != null && finishedAt != lastFinishedAt))
                    wasRunning = running
                    lastFinishedAt = finishedAt
                    initialized = true
                    if (finished) reconcileScanResults()
                }
        }
        // 可见集合**变小**时（扫描完整结束后把"本轮没再发现"的旧卡片标成陈旧），
        // 已经加载进内存的卡片不会自己消失：必须重建分页会话，否则用户看到的是
        // "刷新过了，旧卡片还在"——正是"单章节模式里还有带子文件夹的卡片"那类现象。
        //
        // 只在计数下降时检查，并且先用**已加载的 ID** 确认里面确实有陈旧卡片才重建：
        // 扫描中的插入会让计数频繁变化，无条件重建会把用户滚了很远的列表打回第一页。
        viewModelScope.launch {
            var previous = -1
            mangaRepository.observeVisibleCount(inShelfOnly = false, categoryId = null)
                .distinctUntilChanged()
                .collect { visible ->
                    val shrank = previous >= 0 && visible < previous
                    previous = visible
                    val loaded = _state.value.items
                    if (!shrank || loaded.isEmpty()) return@collect
                    val hasStaleCard = mangaRepository.getCards(loaded.map { it.mangaId })
                        .any { it.availability == MangaAvailability.STALE }
                    if (hasStaleCard) {
                        resetSession()
                        loadMore()
                    }
                }
        }
        viewModelScope.launch {
            observeQuery()
        }
        // 打开图库**只读本地缓存**，不触发扫描（用户要求）。
        //
        // 理由与开发文档 6.3「普通冷启动：先读本地缓存，快速校验源授权；不强制
        // 全库扫描」一致：万级图库的全量变化扫描要几十秒，把它绑在"打开页面"上
        // 会让用户每次进图库都要等。扫描只由三类显式动作触发：
        // 保存/新增路径、用户点刷新、用户点强制重新扫描索引。
        loadFromCache()
    }

    // ------------------------------------------------------------ 右滑栏：图源筛选

    fun openSourceFilter() {
        _state.update { it.copy(sourceFilterOpen = true, draftSourceFilter = appliedSourceFilter) }
    }

    fun closeSourceFilter() {
        // 取消不修改已生效筛选（草稿丢弃）。
        _state.update { it.copy(sourceFilterOpen = false, draftSourceFilter = appliedSourceFilter) }
    }

    fun toggleSourceFilter(sourceId: String) {
        _state.update { current ->
            val next = current.draftSourceFilter.toMutableSet().apply {
                if (!add(sourceId)) remove(sourceId)
            }
            current.copy(draftSourceFilter = next)
        }
    }

    fun selectAllSources() {
        _state.update { current ->
            current.copy(draftSourceFilter = current.allSources.map { it.sourceId }.toSet())
        }
    }

    fun clearAllSources() {
        _state.update { it.copy(draftSourceFilter = emptySet()) }
    }

    /** 确认筛选：写入偏好并重建分页会话。 */
    fun confirmSourceFilter() {
        val selection = _state.value.draftSourceFilter
        viewModelScope.launch {
            preferences.setLibrarySourceFilter(selection)
            _state.update { it.copy(sourceFilterOpen = false) }
        }
    }

    // ------------------------------------------------------------ 长按多选

    fun startSelection(mangaId: String) {
        _state.update { it.copy(selection = it.selection + mangaId) }
    }

    fun toggleSelection(mangaId: String) {
        _state.update { current ->
            val next = current.selection.toMutableSet().apply {
                if (!add(mangaId)) remove(mangaId)
            }
            current.copy(selection = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    /**
     * 反选（用户要求，替代原来的全选按钮）。
     *
     * 语义：把"当前已加载的条目"里未选中的选上、已选中的去掉。已加载集合之外的
     * 条目不受影响——它们既没有被选中过，也无法在没有加载的情况下被取消选中。
     *
     * 为什么反选比全选更合用：用户多选时的典型动作是"先点掉几个不要的"，
     * 反选一次就能把剩下的都选上；而全选要求用户先自己想清楚再逐个排除。
     *
     * 开发文档 9 要求批量选择明确"作用范围与总数"，因此状态栏显示的是
     * 已选数量，而反选只作用于已加载的 N 项（网格与列表都会显示这个 N）。
     */
    fun invertSelection() {
        _state.update { current ->
            val loaded = current.items.map { card -> card.mangaId }.toSet()
            // 只反转"已加载集合"内的选择，已加载之外的选中项原样保留。
            val inverted = loaded.filterNot { it in current.selection }.toSet()
            current.copy(selection = inverted)
        }
    }

    fun addSelectionToShelf(categoryId: Long) {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { shelfRepository.addToShelf(it, categoryId) }
            _state.update { it.copy(selection = emptySet(), hint = "已加入书架：${ids.size} 部") }
            paging.reset()
            loadMore()
        }
    }

    fun consumeHint() {
        _state.update { it.copy(hint = null) }
    }

    /** 首屏：只读已建好的本地索引。 */
    private fun loadFromCache() {
        viewModelScope.launch {
            resetSession()
            loadMore()
        }
    }

    /**
     * 重建分页会话。
     *
     * 必须与 `paging.reset()` 成对调用：换搜索词、换筛选、重建列表之后，旧的封面探测
     * 队列指向的是**已经不在屏幕上**的卡片，继续取只是白花目录枚举。
     */
    private fun resetSession() {
        sessionRevision++
        paging.reset()
        coverProbes.clear()
        chapterCounts.clear()
    }

    /** 顶部刷新按钮/下拉刷新：这是用户显式要求扫描的入口（开发文档 6.3）。 */
    fun onRefresh() {
        viewModelScope.launch {
            paging.reset()
            coverProbes.clear()
            chapterCounts.clear()
            loadMore()
            scanCoordinator.rescanAll(ScanReason.REFRESH)
        }
    }

    /**
     * 上报当前可见的卡片下标（列表与网格共用）。
     *
     * 只上报"还没有封面/还没数过章节数、且从未探测过"的那些 id：这一步是纯内存过滤，
     * 真正的目录枚举由两个队列按批、按并发上限去做。用**下标**而不是 id 是因为列表状态
     * 是唯一的真相来源，界面上拿到的本来就只有下标。
     */
    fun onCardsVisible(indices: List<Int>) {
        if (indices.isEmpty()) return
        val items = paging.items
        val needsCover = ArrayList<String>(indices.size)
        val needsCount = ArrayList<String>(indices.size)
        for (index in indices) {
            val card = items.getOrNull(index) ?: continue
            if (card.coverDocumentId == null && card.coverProbedAt == null) {
                needsCover += card.mangaId
            }
            if (card.needsChapterCountProbe) needsCount += card.mangaId
        }
        coverProbes.request(needsCover)
        chapterCounts.request(needsCount)
    }

    /**
     * 一张卡片的封面探测完成：就地更新内存里的卡片，不重建分页会话。
     *
     * 重建会话会把用户滚了很远的位置打回第一页，而封面是"滚动时"才有结果的，
     * 重建的代价正好落在用户正在滚动的那一刻。
     */
    private fun onCoverResolved(mangaId: String, cover: ResolvedCover?, at: Long) {
        val updated = paging.updateItem(mangaId) { card ->
            card.copy(
                coverDocumentId = cover?.coverDocumentId,
                coverChapterId = cover?.coverChapterId,
                coverProbedAt = at,
            )
        }
        if (updated) _state.update { it.copy(items = paging.items.toList()) }
    }

    /**
     * 一张卡片的章节计数完成：同样就地更新，不重建分页会话。
     *
     * `count = null`（数不出可读章节）也要把时间写进卡片：否则这张卡在用户眼里
     * 永远是"还没数过"，而队列每次滚动都会再排一次（持久层已经记下了，内存这张也得跟上）。
     */
    private fun onChapterCounted(mangaId: String, count: Int?, at: Long) {
        val updated = paging.updateItem(mangaId) { card ->
            card.copy(countedChapterCount = count, countedChapterCountAt = at)
        }
        if (updated) _state.update { it.copy(items = paging.items.toList()) }
    }

    /**
     * 刷新一张卡片（图库页从详情页返回时调用）。
     *
     * 为什么需要：详情页会改这张卡片的数据——「更新章节」把章节清单与 `chapterCountKnown`
     * 落成准确值、强取封面与简介、按需回填页数。用户返回图库时若还看着旧的那张卡片，
     * 就会以为"更新章节没生效"（用户口径：退出详情页要在图库页更新这一对应卡片）。
     *
     * 只查一行、只替换一行：重建分页会话会把用户滚动的位置打回第一页。
     */
    fun refreshCard(mangaId: String) {
        viewModelScope.launch {
            val card = runCatching { mangaRepository.getCards(listOf(mangaId)) }.getOrNull()
                ?.firstOrNull()
                ?: return@launch
            val updated = paging.updateItem(mangaId) { card }
            if (updated) _state.update { it.copy(items = paging.items.toList()) }
        }
    }

    /**
     * 搜索输入：300ms 防抖查询数据库，不为每个字符启动扫描（开发文档 6.3）。
     *
     * 用户要求：**搜索不触发重新扫描**。搜索只查已经建好的本地索引，
     * 因此结果可能不含"扫描之后才加进来的文件"——这种情况由用户显式点刷新按钮
     * 解决（开发文档 6.3 的"搜索显式刷新"入口保留）。
     */
    @OptIn(FlowPreview::class)
    private suspend fun observeQuery() {
        queryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .collect { query ->
                resetSession()
                _state.update {
                    it.copy(
                        // ⚠️ 这里**不能**写 `query = query`。
                        //
                        // `query` 是搜索框的 `value`，而这是防抖之后的**延迟**值：用户连打
                        // 三个字符时，300ms 后的这次发射带的是"当时"的旧文本，把它写回
                        // `value` 会覆盖用户刚输入的内容，光标同时被重置到开头。
                        // 真机症状正是"每次只能输入一个字符，光标还跑到前面去"。
                        // 输入框的值只由 [onQueryChange] 同步更新，这里只记"结果对应哪个查询"。
                        appliedQuery = query,
                        indexingHint = query.isNotBlank() && scanCoordinator.overall.value.running,
                    )
                }
                loadMore()
            }
    }

    /**
     * 搜索框输入。
     *
     * 同步更新 [LibraryUiState.query]（输入框的 `value`），防抖查询交给 [queryFlow]。
     * 两者必须分开：输入框需要**立刻**看到用户刚敲的字符，而查询需要防抖。
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        queryFlow.value = query
    }

    fun onSearchOpened() {
        // 刻意不在这里启动扫描（用户要求）。只标记"索引可能不全"，
        // 让界面显示"正在更新索引，结果可能不全"而不是把零结果说成"没有漫画"。
        _state.update { it.copy(indexingHint = scanCoordinator.overall.value.running) }
    }

    /** 滚动到列表末端：额度 +40 并追加（开发文档 8.1「底部加载」）。 */
    fun onLoadMore() {
        paging.requestNextBatch()
        viewModelScope.launch { loadMore() }
    }

    fun setDisplayMode(mode: LibraryDisplayMode) {
        viewModelScope.launch { preferences.setLibraryDisplayMode(mode) }
    }

    fun cancelScan() {
        scanCoordinator.cancelAll()
    }

    private suspend fun loadMore() = pageLoadMutex.withLock {
        if (!paging.needsMore()) return@withLock
        val revision = sessionRevision
        paging.beginLoad()
        _state.update { it.copy(loading = true) }
        try {
            val query = _state.value.appliedQuery.trim()
            val page = if (query.isEmpty()) {
                // 图源筛选下推到 SQL：只勾一个来源时不必先读回全部行再丢弃。
                mangaRepository.pageLibrary(
                    offset = paging.nextOffset,
                    limit = PAGE_SIZE,
                    sourceFilter = _state.value.effectiveSourceFilter,
                )
            } else {
                // 搜索**落在已生效的图源筛选之内**：用户在"只看某个图源"的状态下搜索，
                // 期望的是"在这个图源里找"，而不是把别的图源的书也翻出来。
                mangaRepository.search(
                    query = query,
                    offset = paging.nextOffset,
                    limit = PAGE_SIZE,
                    sourceFilter = _state.value.effectiveSourceFilter,
                )
            }
            if(revision != sessionRevision) return@withLock
            paging.append(
                PageSlice(items = page.items, nextOffset = page.nextOffset, exhausted = page.exhausted),
                totalKnown = page.totalKnown,
            )
            _state.update {
                it.copy(
                    loading = false,
                    items = paging.items.toList(),
                    exhausted = paging.exhausted,
                    discoveredCount = paging.items.size,
                    error = null,
                )
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // 读缓存失败是可恢复错误：给出原因与重试，而不是显示空列表（开发文档 3）。
            if(revision == sessionRevision) _state.update {
                it.copy(loading = false, error = error.message ?: "读取本地索引失败")
            }
        } finally {
            paging.endLoad()
        }
    }

    private suspend fun reconcileScanResults() = pageLoadMutex.withLock {
        val revision = sessionRevision
        val current = _state.value
        val quota = paging.capacity.coerceAtLeast(PAGE_SIZE)
        try {
            val page = if(current.appliedQuery.isBlank()) mangaRepository.pageLibrary(0, quota, current.effectiveSourceFilter)
                else mangaRepository.search(current.appliedQuery.trim(), 0, quota, current.effectiveSourceFilter)
            if(revision != sessionRevision) return@withLock
            paging.replaceWindow(PageSlice(page.items, page.nextOffset, page.exhausted), page.totalKnown)
            _state.update { it.copy(items = paging.items.toList(), exhausted = paging.exhausted,
                discoveredCount = paging.items.size, loading = false, error = null) }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(error: Exception) { if(revision == sessionRevision) _state.update { it.copy(error = error.message ?: "读取本地索引失败") } }
    }

    companion object {
        /** 一批多少项；与 `PagingState.DEFAULT_PAGE_SIZE` 必须一致（用户口径：30）。 */
        const val PAGE_SIZE = PagingState.DEFAULT_PAGE_SIZE
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun factory(container: com.lmreader.di.AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    mangaRepository = container.mangaRepository,
                    sourceRepository = container.sourceRepository,
                    shelfRepository = container.shelfRepository,
                    scanCoordinator = container.scanCoordinator,
                    preferences = container.preferences,
                    coverResolver = container.coverResolver,
                    coverMetadataWriter = container.coverMetadataWriter,
                    chapterCounter = container.chapterCounter,
                )
            }
        }
    }
}

/** 图库页状态。 */
data class LibraryUiState(
    val items: List<MangaCard> = emptyList(),
    val loading: Boolean = false,
    val exhausted: Boolean = false,
    /**
     * 搜索框里**当前**的文本，只由 [LibraryViewModel.onQueryChange] 同步更新。
     *
     * 它是 `OutlinedTextField` 的 `value`，因此**绝不能**被防抖之后的延迟值覆盖——
     * 那样会覆盖用户刚敲的字符并把光标重置到开头（真机症状：每次只能输入一个字符）。
     */
    val query: String = "",
    /**
     * 当前结果对应的查询词（防抖之后的）。
     *
     * 与 [query] 分开的理由：输入框要立刻反映按键，而查询要防抖。空状态文案要按**结果**
     * 说话，所以用这个而不是 [query]——否则用户刚敲下第一个字符时就会看到"没有找到"。
     */
    val appliedQuery: String = "",
    val discoveredCount: Int = 0,
    val displayMode: LibraryDisplayMode = LibraryDisplayMode.LIST,
    val scan: com.lmreader.core.storage.scan.OverallScanState =
        com.lmreader.core.storage.scan.OverallScanState(),
    val indexingHint: Boolean = false,
    val error: String? = null,
    // ---- 图源筛选（右滑栏）----
    /** 全部来源；只有一张表，图片与归档由同一次扫描一起识别。 */
    val sources: List<LibrarySource> = emptyList(),
    /** 右滑栏当前是否打开。 */
    val sourceFilterOpen: Boolean = false,
    /** 栏内草稿；只有点「确认」才写进 [appliedSourceFilter]。 */
    val draftSourceFilter: Set<String> = emptySet(),
    /** 已生效的筛选；空集合 = 未筛选（显示全部）。 */
    val appliedSourceFilter: Set<String> = emptySet(),
    /** 各来源已发现的数量，用于栏内的副标题。 */
    val discoveredBySource: Map<String, Int> = emptyMap(),
    // ---- 长按多选 ----
    /** 选中的 mangaId 集合；按 ID 存，滚动与加载更多都不会错位。 */
    val selection: Set<String> = emptySet(),
    val categories: List<Category> = emptyList(),
    val hint: String? = null,
) {
    /** 图源筛选栏的来源列表（只有一张表）。 */
    val allSources: List<LibrarySource> get() = sources

    val selectionMode: Boolean get() = selection.isNotEmpty()

    /** 实际参与查询的筛选：空集合按"未筛选"处理，避免用户面对必然空白的图库。 */
    val effectiveSourceFilter: Set<String>?
        get() = appliedSourceFilter.takeIf { it.isNotEmpty() }

    /** 四态判定；空态必须区分"还没扫描"与"扫描完确实没有漫画"。 */
    val screenState: ScreenState<Unit>
        get() = when {
            error != null -> ScreenState.Error(error, actionLabel = "重试")
            items.isNotEmpty() -> ScreenState.Content(Unit)
            loading -> ScreenState.Loading
            scan.running -> ScreenState.Empty("正在发现漫画，已发现 $discoveredCount 项，请稍候")
            // 用 appliedQuery 而不是 query：空态说的是"这一批结果为空，而它对应的查询是
            // 什么"。用 query 的话，用户刚敲下第一个字符、查询还没跑（防抖中）时就会
            // 看到"没有匹配「a」的漫画"，而其实只是还没查。
            appliedQuery.isNotBlank() -> ScreenState.Empty("没有匹配「$appliedQuery」的漫画")
            else -> ScreenState.Empty(
                message = "图库里还没有漫画",
                actionLabel = null,
            )
        }
}
