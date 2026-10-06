package com.lmreader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.NavigationRegions
import com.lmreader.core.model.ReaderOrientation
import com.lmreader.core.model.ReaderSettings
import com.lmreader.core.model.ReadingMode
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.TapAction
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.storage.settings.ReaderPreferences
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 阅读器状态机。
 *
 * ## 只有一个动作：在一条直线上前后移动
 *
 * 阅读器展示的是一条线性列表（见 [ViewerChapters] 的说明）：
 *
 * ```
 * … issue1 的页、[过渡页]、issue2 的页、[过渡页]、issue3 的页 …
 * ```
 *
 * 滑动、点按区域、音量键、滑杆、「上一章 / 下一章」按钮，**全部**归结为同一个操作：
 * 把 [ReaderUiState.currentPageIndex] 加减若干。当前在哪一章由"当前项属于哪一章"反推。
 *
 * 这里**没有**"跨章"这个事件，也没有"提升章节"。上一版把它当成事件处理，于是要重建列表、
 * 要把位置在两套下标之间换算——跳页、卡住、"有时进下一章有时被送回上一章"全部由此而来。
 * 把跨章降级为位置之后，"翻页跳到别的页"在结构上不可能发生。
 *
 * ## 窗口固定，只在接近边界时才接
 *
 * 窗口（这条线上有哪些章）在阅读过程中**不动**，因此所有项的下标不变。只有当读者走到
 * 窗口靠边的章时，才向那一端补一批章；补的时候按页身份重新定位（[reanchorIndex]），
 * 所以画面不跳。
 *
 * ## 进度粒度
 *
 * 照搬 Mihon：只持久化**页码**，页内偏移丢弃。因此 `intraPageRatio` 恒为 0。
 */
class ReaderViewModel(
    private val mangaId: String,
    private val requestedChapterId: String,
    /**
     * 从哪一页开始（0 基）；[NO_START_PAGE] 表示"没指定"，此时按章节/进度决定。
     */
    private val requestedStartPage: Int = NO_START_PAGE,
    private val mangaRepository: MangaRepository,
    private val progressRepository: ReadingProgressRepository,
    private val pageSourceFactory: PageSourceFactory,
    private val readerPreferences: ReaderPreferences,
    /** 页面字节的预取缓存；为空时一切照旧，只是每页都走页源现读。 */
    private val prefetcher: PagePrefetcher? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    /** Installed by the reader's draft editor; absent in ordinary reading and unit tests. */
    var navigationGuard: (((() -> Unit)) -> Boolean)? = null
    var navigationBlocked: () -> Boolean = { false }

    private var savedProgress: ReadingProgress? = null
    private val progressSaveMutex = Mutex()

    /** 已安排的章节加载任务，按章节 ID 保存。 */
    private val loadJobs = HashMap<String, Job>()

    /** 已经加载出页清单的章，按 ID 索引。窗口由它与规划共同决定。 */
    private val loaded = LinkedHashMap<String, ViewerChapter>()

    /** 阅读器（分页器 / 条带）是否正在滚动。滚动中不替换项列表，见 [writeItems]。 */
    private var scrolling = false

    /**
     * 滚动期间有窗口需要重建。
     *
     * 刻意**只记"需要重建"这个事实，不保存算好的窗口**：窗口是按"当时那一章"算的，
     * 而快滑时读者已经又前进了好几章——把旧窗口照原样应用，就会出现
     * "窗口里根本没有当前位置的项 → 身份找不到 → 退回下标重锚 → 位置跳到几章之前"，
     * 而且随后的连续快滑会把这个回跳重复成循环（真机反馈：567567）。
     * 落定后重新规划，用的就是**最新**的当前章。
     */
    private var rebuildPending = false

    init {
        viewModelScope.launch {
            readerPreferences.settings.collect { global ->
                val previous = _state.value.settings
                _state.update { it.copy(settings = global) }
                // 过渡页开关改了要重组列表；预载页数改了要重算预取；
                // 缓存章节数改小了要立刻淘汰（改大了下次重建自然会带上更多章）。
                if (previous.showChapterTransitions != global.showChapterTransitions ||
                    previous.preloadPages != global.preloadPages ||
                    previous.cachedChaptersPerSide != global.cachedChaptersPerSide
                ) {
                    rebuild()
                }
            }
        }
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val target = mangaRepository.getBackfillTarget(mangaId)
                    ?: error("漫画或来源已不存在")
                if (target.chapters.isEmpty()) error("这部漫画还没有可读章节")
                savedProgress = progressRepository.get(mangaId)
                // 旧版漫画覆盖列保留以兼容备份，但阅读器始终使用全局偏好。
                val global = readerPreferences.settings.first()
                _state.update { it.copy(settings = global) }
                val desired = requestedChapterId.takeUnless { it == RESUME_CHAPTER }
                    ?: savedProgress?.chapterId
                val index = target.chapters.indexOfFirst { it.chapterId == desired }
                    .takeIf { it >= 0 }
                    ?: 0
                _state.update {
                    it.copy(
                        mangaTitle = target.manga.displayName,
                        sourceTreeUri = target.sourceTreeUri,
                        chapterList = target.chapters,
                    )
                }
                // 恢复页码的三种来源，按优先级：
                // 1. 详情页带过来的起始页；2. "继续阅读"入口 + 进度记的正是这一章；
                // 3. 都没有 → 第一页。
                val fromRoute = requestedStartPage.takeIf { it >= 0 }
                val fromProgress = savedProgress
                    ?.takeIf {
                        requestedChapterId == RESUME_CHAPTER &&
                            it.chapterId == target.chapters[index].chapterId
                    }
                    ?.pageOrdinal
                val restorePage = fromRoute ?: fromProgress ?: 0
                openAt(index, restorePage)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开阅读器") }
            }
        }
    }

    // ------------------------------------------------------------ 打开与窗口

    /**
     * 打开第 [chapterIndex] 章并落在它的第 [page] 页。
     *
     * 只有"用户主动跳章"（详情页点章节、打开阅读器）才走这里；翻页本身**不**走这里，
     * 它只移动 [ReaderItem] 下标。
     */
    private suspend fun openAt(chapterIndex: Int, page: Int) {
        val snapshot = _state.value
        val record = snapshot.chapterList.getOrNull(chapterIndex) ?: error("章节已不存在")
        val treeUri = snapshot.sourceTreeUri ?: error("来源路径不可用")
        _state.update { it.copy(loading = true, error = null) }

        val current = withContext(Dispatchers.IO) { loadChapter(record, treeUri) }
        if (current == null) {
            _state.update { it.copy(loading = false, error = "无法打开「${record.title}」") }
            return
        }
        loaded[record.chapterId] = current
        // 先把窗口建立起来（只为算出当前章第一项的位置），再落到指定页。
        fillWindow(record.chapterId)
        _state.update { state ->
            val items = state.items
            val first = items.indexOfFirstPageOfChapter(record.chapterId).coerceAtLeast(0)
            val target = (first + page).coerceIn(0, (items.size - 1).coerceAtLeast(0))
            state.copy(
                loading = false,
                currentPageIndex = target,
                // 位置真相是身份；下标只是它的派生量。两者必须一起写。
                positionKey = items.getOrNull(target)?.key.orEmpty(),
                // 打开/跳章是状态机主动定位，要驱动分页器跟过来。
                positionSyncToken = state.positionSyncToken + 1,
                error = null,
            )
        }
        val opened = _state.value
        mangaRepository.updateChapterPageInfo(
            chapterId = record.chapterId,
            pageCount = current.pages.size,
            coverDocumentId = current.pages.firstOrNull()?.documentId,
        )
        opened.items.getOrNull(opened.currentPageIndex)?.let { saveProgressAt(it) }
        loadNeighbors()
        warmPrefetch()
    }

    /**
     * 用"已缓存的章"重建显示窗口与项列表。
     *
     * 窗口不再按预载预算切段（理由见 [buildWindow]）。这个方法因此可以反复调用而
     * **必然是幂等的**：只要 `loaded` 没变，算出来的窗口就一样，[writeItems] 的等价判断
     * 会直接跳过，不会打扰分页器。
     */
    private fun fillWindow(currentChapterId: String) {
        val snapshot = _state.value
        val list = snapshot.chapterList
        if (list.isEmpty()) return
        val effectiveCurrent = currentChapterId.ifEmpty {
            snapshot.chapters?.currentChapterId ?: list.first().chapterId
        }
        val (window, currentIndex) = buildWindow(list, effectiveCurrent, loaded)
        if (window.isEmpty()) return
        writeItems(ViewerChapters(window = window, currentIndex = currentIndex))
    }

    /**
     * 按用户设置的「缓存章节数」淘汰离当前章最远的页清单。
     *
     * ## 为什么现在淘汰是安全的
     *
     * 位置是**项身份**（见 [ReaderUiState.positionKey]），淘汰导致的"项下标平移"不再是问题。
     * 三条不可越过的红线：
     * 1. **当前章永不淘汰**——它是位置的载体；
     * 2. **当前章前后各一格永不淘汰**——过渡项靠它们生成，抽掉会让读者在章末卡住；
     * 3. **正在加载的章不淘汰**——那是白费一次目录枚举。
     *
     * 淘汰不会改变显示窗口里的内容（窗口 = 剩余缓存），因此也不会触发项列表重排；
     * 它只是让"往回翻很远"退化成重新枚举一次目录。
     */
    private fun evictLoadedChapters() {
        val keep = cachedChapterBudget()
        if (loaded.size <= keep) return
        val list = _state.value.chapterList
        if (list.isEmpty()) return
        val currentListIndex = list.indexOfFirst {
            it.chapterId == _state.value.chapters?.currentChapterId
        }
        if (currentListIndex < 0) return
        val removable = loaded.keys
            .filterNot { it in loadJobs }
            .mapNotNull { id ->
                val index = list.indexOfFirst { it.chapterId == id }
                if (index < 0) null else index to id
            }
            // 离当前章越远越先淘汰；距离相同则淘汰**更早**的（读者更可能往回翻近处）。
            .sortedWith(compareByDescending<Pair<Int, String>> { abs(it.first - currentListIndex) }.thenBy { it.first })
        var size = loaded.size
        for ((index, id) in removable) {
            if (size <= keep) break
            // 红线 1、2：当前章 ±1 永不淘汰。
            if (abs(index - currentListIndex) <= 1) continue
            loaded.remove(id)
            size--
        }
    }

    /** 缓存上限（章数）：至少覆盖预载规划的范围，否则会"刚淘汰就又被要求加载"。 */
    private fun cachedChapterBudget(): Int {
        val perSide = maxOf(
            _state.value.settings.cachedChaptersPerSide,
            MAX_PRELOAD_CHAPTERS_PER_SIDE + 1,
        )
        return 1 + perSide * 2
    }

    /**
     * 算出要收进窗口的章。
     *
     * 除了规划覆盖的章之外，还会再接**一格边界章**。必须这样做的理由：规划用的是已知页数，
     * 而一章的页数要等它加载完才知道。于是"刚量到页数的那些章"会立刻改变规划结果，
     * 一次规划只能多覆盖一章——窗口会永远比预载进度慢一步。
     *
     * 边界章同样是**要被加载的**（见 [chaptersToLoad]），否则窗口永远长不起来。
     */
    private fun planFor(
        list: List<ChapterRecord>,
        currentChapterId: String,
        settings: ReaderSettings,
    ): PreloadPlan {
        val currentIndex = list.indexOfFirst { it.chapterId == currentChapterId }
        if (currentIndex < 0) return PreloadPlan.EMPTY
        val budgets = prefetchBudget(settings.preloadPages)
        val base = PreloadPlan.compute(
            chapterCount = list.size,
            currentIndex = currentIndex,
            budget = budgets.forward,
            maxChapters = MAX_PRELOAD_CHAPTERS_PER_SIDE,
            // 往前读的预算略多于往后：凑整时余数给"往前"，因为往前读的第一步
            // 往往要先跨一个过渡页（见 [prefetchBudget]）。
            backBudget = budgets.backward,
            pagesOf = { index ->
                list.getOrNull(index)?.let { record ->
                    // 先看数据库里的页数（「更新章节」与扫描都顺手数了），再看已经加载的章。
                    //
                    // 为什么顺序是这样：`walk` 只要遇到一个"页数未知"的章就必须停下并把它
                    // 塞进加载队列（否则预算算不下去，窗口再也长不起来）。而一章的页数原先
                    // 只有加载完才知道，于是"预载下一章"必然要先真的加载它。库里已经有页数时
                    // 这些章不必被加载就能参与预算，预载因此更准、也少读盘。
                    record.pageCount?.takeIf { it > 0 }
                        ?: loaded[record.chapterId]?.pages?.size?.takeIf { size -> size > 0 }
                }
            },
        )
        // 边界章：规划覆盖不到的那一章正好是"下一步需要知道页数"的那一章。
        // 它必须一并加载，否则它的页数永远未知、窗口再也长不起来。
        val next = base.nextIndices.toMutableList()
        val previous = base.previousIndices.toMutableList()
        val nextFrontier = (next.lastOrNull() ?: currentIndex) + 1
        val previousFrontier = (previous.firstOrNull() ?: currentIndex) - 1
        if (nextFrontier in list.indices && next.size < MAX_PRELOAD_CHAPTERS_PER_SIDE) {
            next += nextFrontier
        }
        if (previousFrontier in list.indices && previous.size < MAX_PRELOAD_CHAPTERS_PER_SIDE) {
            previous += previousFrontier
        }
        return PreloadPlan(previousIndices = previous, nextIndices = next)
    }

    /**
     * 把「预载页数」拆成往后读与往前读两份预算。
     *
     * ## 为什么往前也要预载
     *
     * 预载的**价值在于"从哪一页打开"**，而不只是"往后读到哪"。读者从第 37 页打开、
     * 或者看了一会儿想往回翻，此时前面几页若没预读就要现等——那与他从第 1 页开始读
     * 时的体验不一致。因此预算对半分，两侧都覆盖。
     *
     * ## 边界情况
     *
     * - **预算 9 → 往后 4、往前 5。** 余数给"往前"是因为往前读的第一步常常要先跨一个
     *   **过渡页**（它占一格）。从某章第一页打开时：往前 5 格 = 1 个过渡页 + 上一章末尾
     *   **4 页**，覆盖了"至少要看到上一章最后几页"这个需求。
     * - 从第 1 页打开：往前没有内容，`PreloadPlan` 取不到更早的章，于是这份预算自然落空，
     *   往后仍然是 4 页。**不会**因为往前没东西就把预算挪过去——那会让"预载 9"在首页
     *   表现出 9 页、在第 37 页表现出 4 页，行为不可预测。
     * - 预算 2 → 往后 1、往前 1：下限保证"往后翻一页"永远不用现读。
     */
    private fun prefetchBudget(total: Int): PrefetchBudget = PrefetchBudget(
        forward = total / 2,
        backward = total - total / 2,
    )

    /** 两侧各自的预载预算（格数，过渡页也算一格）。 */
    private data class PrefetchBudget(val forward: Int, val backward: Int)

    /**
     * 需要加载的章：规划覆盖的章 **加上** 一格边界章。
     *
     * 边界章必须一起加载——否则窗口只会包含"规划算出来的那几章"，而边界章的页数永远
     * 未知，窗口就再也长不起来（真机上表现为只剩当前章、翻到末页就显示"已是最后一章"）。
     */
    private fun chaptersToLoad(
        list: List<ChapterRecord>,
        currentChapterId: String,
        settings: ReaderSettings,
    ): List<ChapterRecord> {
        val currentIndex = list.indexOfFirst { it.chapterId == currentChapterId }
        if (currentIndex < 0) return emptyList()
        val plan = planFor(list, currentChapterId, settings)
        val indices = plan.nextIndices + plan.previousIndices.asReversed()
        return indices.mapNotNull { list.getOrNull(it) }
    }

    /** 重新组装项列表并按项身份把读者放回原处。 */
    /**
     * 把窗口写成项列表，并按项身份把读者放回原处。
     *
     * ## 窗口没变时**必须什么都不做**
     *
     * 这里曾经无条件替换 `items`（哪怕内容一模一样），而 `items` 一变，分页器就要重新
     * 布局：它的「滚动偏移 ↔ 下标」映射在**右到左**模式（`reverseLayout`）下会随手抖动一格。
     * 这一格足以让落页回报指到相邻的另一章，而当前章一翻转窗口内容就跟着翻转——
     * **表长变 → 抖一格 → 章翻转 → 表长再变**，形成每帧一轮的正反馈环，实测每秒重建
     * 80 多个引擎视图、每次都整图解码，把 native heap 顶到 300MB。
     *
     * 判据是**章的 id 序列**而不是列表实例：只要窗口还是那几章（页数与设置都没动），
     * 项列表就一定等价，没有任何理由让分页器重排。
     */
    private fun writeItems(chapters: ViewerChapters) {
        val current = _state.value.chapters
        val sameWindow = current?.window?.map { it.chapterId } == chapters.window.map { it.chapterId }
        // 闸①：**滚动中不替换项列表**（Mihon 的 `awaitingIdleViewerChapters`）。
        //
        // 换列表会让所有下标重新编号，而分页器还在滑——它记的数字立刻失去意义。
        // 挂起来等落定再**重新规划**（只记"待重建"，不保存这份按旧章算出的窗口——
        // 保存它会让落定后应用一个不含当前位置的窗口，位置就跳到几章之前）。
        if (scrolling && !sameWindow) {
            rebuildPending = true
            return
        }
        var identityLost = false
        _state.update { state ->
            if (sameWindow) {
                // 只同步"当前章在窗口里的下标"，`items` 原样保留（保持同一个实例）。
                if (state.chapters?.currentIndex == chapters.currentIndex) {
                    state
                } else {
                    state.copy(chapters = chapters)
                }
            } else {
                val items = chapters.items(
                    showTransitions = state.settings.showChapterTransitions,
                    isFinalChapter = { id -> state.chapterList.lastOrNull()?.chapterId == id },
                )
                // **位置真相是身份**：窗口前滚会把整整一章从列表前端放掉，于是下标整体平移
                // （平移量 = 那一章的页数 + 1 个过渡页）。按下标"重锚"迟早会漏——分页器与
                // 状态机各自持有一份数字，任何一次没对上就是"偏一整章"。身份则不受平移影响。
                val byIdentity = items.indexOfKey(state.positionKey)
                val newIndex = when {
                    byIdentity >= 0 -> byIdentity
                    // 还没有位置可言（`openAt` 是"先建窗口、后落页"）：沿用当前下标即可，
                    // **不是**异常情况，不能记成"窗口策略被破坏"。
                    state.positionKey.isEmpty() ->
                        state.currentPageIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
                    else -> {
                        // 身份不在新列表里：理论上不该发生（窗口永远含当前章及其相邻章）。
                        // 落回按下标重锚，并让调用方记一条日志——走到这里意味着窗口策略被破坏了。
                        identityLost = true
                        reanchorIndex(state.items, state.currentPageIndex, items)
                    }
                }
                state.copy(
                    chapters = chapters,
                    items = items,
                    currentPageIndex = newIndex,
                    positionKey = items.getOrNull(newIndex)?.key ?: state.positionKey,
                    // 列表被替换 → 所有下标重新编号 → 必须让分页器按身份重新归位一次。
                    positionSyncToken = state.positionSyncToken + 1,
                )
            }
        }
        if (identityLost) {
            android.util.Log.w(
                "LMR-POS",
                "positionKey 在新窗口里找不到，已退化为按下标重锚（窗口策略被破坏）",
            )
            identityLost = false
        }
    }

    /**
     * 按当前状态重算窗口，并把新规划出来的章排上加载。
     *
     * **必须同时做这两件事。** 这里出过一个很隐蔽的 bug：`rebuild()` 只重排窗口、
     * 不请求加载，于是加载链只在"上一章加载完成"时前进一次——进入第 1 章时请求了
     * 2/3/4，三章都完成后 `chaptersToLoad` 仍然只返回 2/3/4（已在 `loadJobs` 里，被跳过），
     * **链就断了**。读者于是被永久关在最初的这几章里（真机反馈：最多只能读到 4 章）。
     * 读者每推进一章都要重新规划，因此规划与加载必须一起发生。
     */
    private fun rebuild() {
        val current = _state.value.chapters?.currentChapterId
            ?: _state.value.chapterList.firstOrNull()?.chapterId
            ?: return
        // 先加载、再瘦身、最后重建：淘汰可能把远处已加载的章移出窗口，
        // 因此顺序上要"先确保当前章附近都在，再淘汰"。
        loadNeighbors()
        evictLoadedChapters()
        fillWindow(current)
    }

    private fun loadNeighbors() {
        val snapshot = _state.value
        val treeUri = snapshot.sourceTreeUri ?: return
        val current = snapshot.chapters?.currentChapterId
            ?: snapshot.chapterList.firstOrNull()?.chapterId
            ?: return
        for (record in chaptersToLoad(snapshot.chapterList, current, snapshot.settings)) {
            loadOne(record, treeUri)
        }
    }

    /**
     * 加载一章的页清单。
     *
     * 完成后把它写进 `loaded` 并重建列表。因为窗口固定、且重建走 [reanchorIndex]，
     * 页与过渡页是**插进**已有列表的，读者的位置不变。
     *
     * 两个守卫的意思不同，都不能省：
     * - `loadJobs` 里已有 → 同一章正在加载，别重复排队；
     * - `loaded` 里已有 → 这一章**已经有结论**（成功或失败），不必再来一次。
     *   失败的章由「重试」显式重来（[retryFailedChapters] 会先把它从 `loaded` 里摘掉），
     *   否则每次 rebuild 都会重试失败章，变成无限重试。
     */
    private fun loadOne(record: ChapterRecord, treeUri: String) {
        if (loadJobs.containsKey(record.chapterId)) return
        if (loaded.containsKey(record.chapterId)) return
        // 占位用的页源在协程外先取好：启动之后再取可能已经换章（`?: return@launch`
        // 会把后面的重建一起跳过，那是另一个坑）。
        val fallbackSource = _state.value.chapters?.current?.source ?: return
        loadJobs[record.chapterId] = viewModelScope.launch {
            try {
                // 列一章的页 = 一次目录枚举（真机上是系统调用）。`viewModelScope` 跑在
                // 主调度器上，不切走的话"打开章节"会先卡住首帧再显示。
                val result = withContext(Dispatchers.IO) { loadChapter(record, treeUri) }
                loaded[record.chapterId] = result ?: ViewerChapter(
                    chapter = record,
                    pages = emptyList(),
                    source = fallbackSource,
                    state = ViewerChapter.LoadState.FAILED,
                )
                rebuild()
                warmPrefetch()
            } finally {
                // 任务结束时把登记撤掉，这样"正在加载"这个判断才始终是真的。
                // 一直留着会让 `loadJobs` 变成"曾经请求过"的集合，语义就错了。
                loadJobs.remove(record.chapterId)
            }
        }
    }

    /** 列出一章的页；失败返回 null（章级问题由过渡页显示原因与重试）。 */
    private suspend fun loadChapter(record: ChapterRecord, treeUri: String): ViewerChapter? = try {
        when (val opened = pageSourceFactory.open(treeUri, record)) {
            is PageSourceOpenResult.Unsupported -> null
            is PageSourceOpenResult.Ready -> {
                val pages = opened.source.pages()
                if (pages.isEmpty()) {
                    null
                } else {
                    ViewerChapter(chapter = record, pages = pages, source = opened.source)
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    // ------------------------------------------------------------ 移动

    /** 在直线上移动 [delta] 格。滑动、点按、音量键、按钮都汇聚到这里。 */
    fun move(delta: Int) {
        val current = _state.value
        if (current.items.isEmpty()) return
        val target = (current.currentPageIndex + delta).coerceIn(current.items.indices)
        if (target == current.currentPageIndex) return
        moveTo(target)
    }

    /**
     * 状态机**主动**把读者挪到某一项（点按 / 音量键 / 滑杆 / 按钮）。
     *
     * 与 [settleAt] 的区别只有一个，但很关键：这条路径要**驱动分页器跟随**，
     * 因为位置是状态机决定的，分页器不可能自己知道。而 [settleAt] 是"分页器告诉
     * 我们它到哪了"，那条路径**绝不能**反过来去驱动分页器。
     */
    private fun moveTo(absoluteIndex: Int) {
        val key = _state.value.items.getOrNull(absoluteIndex)?.key ?: return
        if (key == _state.value.positionKey) return
        guardedNavigation {
            val index = _state.value.items.indexOfFirst { it.key == key }
            if (index >= 0) { settleAt(index); requestPositionSync() }
        }
    }
    private fun guardedNavigation(action: () -> Unit) = navigationGuard?.invoke(action) ?: run { action(); true }

    /** 请求一次归位：分页器会把当前位置挪到 [ReaderUiState.positionKey] 所在的下标。 */
    private fun requestPositionSync(force: Boolean = false) {
        _state.update { it.copy(positionSyncToken = it.positionSyncToken + 1, forcePositionSync = force) }
    }

    /** 「上一章 / 下一章」按钮：同样是移动一格，只是移动的是整章的量。 */
    fun jumpToAdjacentChapter(forward: Boolean) {
        val state = _state.value
        val list = state.chapterList
        val index = list.indexOfFirst { it.chapterId == state.chapters?.currentChapterId ?: "" }
        val target = if (forward) index + 1 else index - 1
        if (index < 0 || target !in list.indices) return
        guardedNavigation { viewModelScope.launch {
            try {
                openAt(target, 0)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(loading = false, error = error.message ?: "无法打开章节") }
            }
        } }
    }

    private fun settleAt(absoluteIndex: Int) {
        val snapshot = _state.value
        if (absoluteIndex !in snapshot.items.indices) return
        val item = snapshot.items[absoluteIndex]
        // 下标与身份都对得上才算"没动"：只比下标的话，窗口重排后"同一个下标指向了另一项"
        // 会被误判成没动，位置就悄悄留在错的项上。
        if (absoluteIndex == snapshot.currentPageIndex && item.key == snapshot.positionKey) return
        _state.update { it.copy(currentPageIndex = absoluteIndex, positionKey = item.key) }
        // 当前章由"当前项属于哪一章"反推——不再有提升动作。
        // [chapterEnteredBy] 说明为什么**过渡项不算换章**，以及少了这条判定会怎样。
        val entered = chapterEnteredBy(item)
        if (item is ReaderItem.PageItem) saveProgressAt(item)
        val moved = _state.value
        val chapterChanged = entered != null && moved.chapters?.currentChapterId != entered
        if (chapterChanged) {
            _state.update { state ->
                val window = state.chapters ?: return@update state
                val position = window.indexOf(entered!!)
                if (position < 0) state else state.copy(chapters = window.copy(currentIndex = position))
            }
        }
        // 换章就重算一次窗口。
        //
        // 不再用 `nearWindowEdge()` 门控：窗口现在是"已缓存的章"，重算不依赖页数预算，
        // 因此它是**幂等**的——窗口没变时 [writeItems] 直接跳过，不会打扰分页器。
        // 而加载链必须每换一章就前进一次（否则会退回到"最多只能读 4 章"那个 bug）。
        if (chapterChanged) rebuild()
    }

    /** 页码滑杆：跳到当前章的第 [localPageIndex] 页（0 基）。 */
    fun jumpToPage(localPageIndex: Int) {
        val current = _state.value
        val chapterId = current.chapters?.currentChapterId ?: return
        val pages = current.chapters?.current?.pages.orEmpty()
        if (localPageIndex !in pages.indices) return
        val first = current.items.indexOfFirstPageOfChapter(chapterId)
        if (first < 0) return
        moveTo(first + localPageIndex)
    }

    fun focusPage(pageId: String) {
        val index = _state.value.items.indexOfFirst { it is ReaderItem.PageItem && it.page.pageId == pageId }
        if (index >= 0) moveTo(index)
    }

    /** 分页器或条带落到了第 [absoluteIndex] 项。 */
    fun onItemSettled(absoluteIndex: Int) {
        if (navigationBlocked()) return
        val key = _state.value.items.getOrNull(absoluteIndex)?.key ?: return
        if (key == _state.value.positionKey) return
        var deferred = false
        if (!guardedNavigation {
            val index = _state.value.items.indexOfFirst { it.key == key }
            if (index >= 0) {
                settleAt(index)
                // The editor may execute this after a blocked swipe was restored to the old page.
                if (deferred) requestPositionSync(force = true)
            }
        }) { deferred = true; requestPositionSync(force = true) }
    }

    /**
     * 阅读器报告滚动状态（Mihon 的 `isIdle`）。
     *
     * 滚动中**不替换项列表**：换列表会把所有下标重新编号，而分页器还在滑，它记的数字
     * 立刻失去意义——这正是"偏一整章"那类 bug 的入口。滚动期间算好的窗口先挂起，落定后应用。
     */
    fun onScrollingChanged(value: Boolean) {
        if (scrolling == value) return
        scrolling = value
        if (value) return
        // 落定后**重新规划**，而不是应用滚动期间算出的那份窗口：
        // 那份是按当时的章算的，而读者可能已经又前进了好几章。
        if (!rebuildPending) return
        rebuildPending = false
        rebuild()
    }

    /** 记录一页在条带里的布局高度，供滚动定位使用。 */
    fun onPageHeightMeasured(pageId: String, heightDp: Int) {
        if (heightDp <= 0) return
        _state.update { current ->
            if (current.pageHeights[pageId] == heightDp) {
                current
            } else {
                current.copy(pageHeights = current.pageHeights + (pageId to heightDp))
            }
        }
    }

    // ------------------------------------------------------------ 预取页字节

    /**
     * 把当前页**前后各** [PrefetchBudget] 格之内的**图片字节**提前读进磁盘缓存。
     *
     * ## 为什么前后都要
     *
     * 预载的价值在于"从哪一页打开"：读者从第 37 页打开、或看了一会儿想往回翻，
     * 前面几页若没预读就要现等，与他从第 1 页开始读的体验不一致。因此两侧用同一套
     * 拆分（[prefetchBudget]），与窗口规划保持一致。
     *
     * ## 格数而不是页数
     *
     * 计数单位是**项**（含过渡页），与预算规则一致：过渡页也算一格，因为它翻过去也要
     * 一瞬间。因此"从某章第一页往前 5 格"= 1 个过渡页 + 上一章末尾 4 页。
     *
     * 缓存只放磁盘、不放堆：真机上解码一页就已经吃过整图分配的亏（见 `ReaderImageView`），
     * 再往堆里压几页字节会把 OOM 重新引回来。
     */
    private fun warmPrefetch() {
        val prefetcher = prefetcher ?: return
        val snapshot = _state.value
        val budgets = prefetchBudget(snapshot.settings.preloadPages)
        if (budgets.forward <= 0 && budgets.backward <= 0) return
        val chapters = snapshot.chapters ?: return

        // 直接按项列表走：项序就是阅读顺序，因此"前 N 格 / 后 N 格"不需要再分章计算，
        // 跨章与过渡页自动正确。
        val ahead = ArrayList<PrefetchCandidate>()
        val behind = ArrayList<PrefetchCandidate>()
        var aheadSlots = budgets.forward
        var behindSlots = budgets.backward

        for (index in snapshot.currentPageIndex + 1 until snapshot.items.size) {
            if (aheadSlots <= 0) break
            when (val item = snapshot.items[index]) {
                // 过渡页占一格：翻到它也要一瞬间，所以它消耗预算。
                is ReaderItem.Transition -> aheadSlots--
                is ReaderItem.PageItem -> {
                    ahead += PrefetchCandidate(item.page, item.chapter.source)
                    aheadSlots--
                }
            }
        }
        for (index in snapshot.currentPageIndex - 1 downTo 0) {
            if (behindSlots <= 0) break
            when (val item = snapshot.items[index]) {
                is ReaderItem.Transition -> behindSlots--
                is ReaderItem.PageItem -> {
                    behind += PrefetchCandidate(item.page, item.chapter.source)
                    behindSlots--
                }
            }
        }
        // 当前章内、当前页之后的页也要覆盖：项列表里它们本来就在后面，上面两个循环
        // 已经取到了；这里只处理"当前页本身位于过渡页上"的情形——那时 currentPageIndex
        // 指向过渡项，它后面/前面第一次扫描就会取到两侧的页。
        prefetcher.request(
            scopeKey = prefetchScopeKey(snapshot),
            ahead = ahead,
            behind = behind,
        )
    }

    /** 预取范围的稳定标识；只在窗口边界变化时才清理旧缓存。 */
    private fun prefetchScopeKey(snapshot: ReaderUiState): String {
        val chapters = snapshot.chapters
        return buildString {
            append(mangaId)
            append('|').append(chapters?.window?.firstOrNull()?.chapterId.orEmpty())
            append('|').append(chapters?.window?.lastOrNull()?.chapterId.orEmpty())
        }
    }

    // ------------------------------------------------------------ 点按与控制栏

    fun onTap(x: Float, y: Float) {
        val current = _state.value
        val action = NavigationRegions.hitTest(
            zones = current.settings.tapZones,
            invert = current.settings.tapInvert,
            mode = current.settings.readingMode,
            x = x,
            y = y,
        )
        when (action) {
            TapAction.MENU -> toggleChrome()
            TapAction.PREVIOUS, TapAction.PAN_LEFT -> move(-1)
            TapAction.NEXT, TapAction.PAN_RIGHT -> move(1)
        }
    }

    fun toggleChrome() {
        _state.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    fun showTapZoneOverlay() {
        _state.update { it.copy(tapZoneOverlayVisible = true) }
    }

    fun hideTapZoneOverlay() {
        if (!_state.value.tapZoneOverlayVisible) return
        _state.update { it.copy(tapZoneOverlayVisible = false) }
    }

    /**
     * 重试加载失败的章。
     *
     * 关键是**先把失败结论从 `loaded` 里摘掉**：`loadOne` 会把"已经有结论"（含失败）的章
     * 直接跳过，不摘掉的话这里点了重试也不会真的重来。失败章可能已经不在当前窗口里
     * （读者翻过去了），所以按 `loaded` 里所有失败项来重试，而不是只扫窗口。
     */
    fun retryFailedChapters() {
        val snapshot = _state.value
        val treeUri = snapshot.sourceTreeUri ?: return
        val list = snapshot.chapterList
        val failed = linkedSetOf<String>()
        loaded.forEach { (id, chapter) ->
            if (chapter.state == ViewerChapter.LoadState.FAILED) failed += id
        }
        snapshot.chapters?.window.orEmpty().forEach { chapter ->
            if (chapter.state == ViewerChapter.LoadState.FAILED) failed += chapter.chapterId
        }
        for (id in failed) {
            loaded.remove(id)
            loadJobs.remove(id)?.cancel()
            val record = list.firstOrNull { it.chapterId == id } ?: continue
            loadOne(record, treeUri)
        }
        rebuild()
    }

    // ------------------------------------------------------------ 设置

    /** 阅读中修改模式同样写入全局偏好，其他漫画与设置页立即共享。 */
    fun setReadingMode(mode: ReadingMode) {
        updateGlobalSettings { it.copy(readingMode = mode) }
    }

    fun setOrientation(orientation: ReaderOrientation) {
        updateGlobalSettings { it.copy(orientation = orientation) }
    }

    /** 所有阅读设置统一保存在全局 DataStore。 */
    fun updateGlobalSettings(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { readerPreferences.update(transform) }
    }

    // ------------------------------------------------------------ 进度

    /** 把某个页面项写成阅读进度。落在过渡页上时不写（那不是某一页）。 */
    private fun saveProgressAt(item: ReaderItem) {
        if (item !is ReaderItem.PageItem) return
        val progress = ReadingProgress(
            mangaId = mangaId,
            chapterId = item.chapter.chapterId,
            pageOrdinal = item.page.ordinal,
            // 页内比例照搬 Mihon：只存页码，恢复时对齐页顶。
            intraPageRatio = 0f,
            read = savedProgress?.read == true,
            bookmark = savedProgress?.bookmark == true,
            updatedAt = clock(),
        )
        savedProgress = progress
        viewModelScope.launch {
            progressSaveMutex.withLock { progressRepository.save(progress) }
        }
    }

    /** 把当前落点写成阅读进度。 */
    fun saveProgress() {
        val current = _state.value
        current.items.getOrNull(current.currentPageIndex)?.let { saveProgressAt(it) }
    }

    override fun onCleared() {
        super.onCleared()
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        // 阅读器销毁时强制解除"滚动中"：否则挂起的重建永远不会被应用。
        scrolling = false
        rebuildPending = false
        prefetcher?.cancelAll()
    }

    companion object {
        const val RESUME_CHAPTER = "resume"

        /** 没有指定起始页的哨兵值；见 [requestedStartPage]。 */
        const val NO_START_PAGE = -1

        /**
         * 单侧最多预载几章。
         *
         * 预算是页数，理论上"每章只有 1 页"的长篇会把整部作品拉进来；这个上限把最坏情况
         * 钉住。取 3 是因为真机样本里一章 29–106 页，而预算上限 60 页在 3 章内必然用完。
         */
        const val MAX_PRELOAD_CHAPTERS_PER_SIDE = 3

        fun factory(
            container: com.lmreader.di.AppContainer,
            mangaId: String,
            chapterId: String,
            startPage: Int = NO_START_PAGE,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ReaderViewModel(
                    mangaId = mangaId,
                    requestedChapterId = chapterId,
                    requestedStartPage = startPage,
                    mangaRepository = container.mangaRepository,
                    progressRepository = container.readingProgressRepository,
                    pageSourceFactory = container.pageSourceFactory,
                    readerPreferences = container.readerPreferences,
                    prefetcher = container.pagePrefetcher,
                )
            }
        }
    }
}

data class ReaderUiState(
    val loading: Boolean = true,
    val mangaTitle: String = "",
    val sourceTreeUri: String? = null,
    /** 全部章节，用于换章、窗口规划与"是不是最后一章"。 */
    val chapterList: List<ChapterRecord> = emptyList(),
    /** 当前窗口与当前章在其中的下标。 */
    val chapters: ViewerChapters? = null,
    /** 整条直线的项列表：窗口里已加载章的页 + 章之间的过渡页。 */
    val items: List<ReaderItem> = emptyList(),
    /** 在 [items] 中的绝对下标。 */
    val currentPageIndex: Int = 0,
    /**
     * 读者位置的**真相**：当前项的身份（`pageId` 或 `transition:from->to`）。
     *
     * [currentPageIndex] 只是它在 [items] 里的**派生量**。窗口前滚会把整整一章从列表前端
     * 放掉，下标因此整体平移（平移量 = 那一章的页数 + 1 个过渡页）——按下标记位置时，
     * 只要有一次没和分页器对上，读者就会偏掉一整章。身份不受平移影响。
     *
     * 不变量：`items.getOrNull(currentPageIndex)?.key == positionKey`。
     */
    val positionKey: String = "",
    /**
     * "请把分页器挪到 [positionKey] 所在的下标"的信号；数值变化即表示有一次归位请求。
     *
     * **只有当状态机主动移动读者、或项列表被替换时才自增**。绝不能因为"读者自己翻页导致
     * [currentPageIndex] 变了"就自增：快滑时状态总是滞后于分页器，那样会反过来把已经
     * 滑到前面的分页器**拽回**状态记得的旧位置（真机反馈：快滑时位置来回跳）。
     */
    val positionSyncToken: Int = 0,
    val forcePositionSync: Boolean = false,
    /**
     * 控制栏是否可见；默认**隐藏**：阅读器一打开就应该是内容
     * （Mihon 的 `ReaderActivity` 同样以隐藏态进入）。
     */
    val chromeVisible: Boolean = false,
    val tapZoneOverlayVisible: Boolean = false,
    /** 已探测到的条带页高（dp），键为页 ID。 */
    val pageHeights: Map<String, Int> = emptyMap(),
    val settings: ReaderSettings = ReaderSettings(),
    val error: String? = null,
) {
    /** 当前章在整部里的下标；用于「上一章 / 下一章」按钮的可用性。 */
    val currentChapterListIndex: Int
        get() = chapters?.currentChapterId?.let { id ->
            chapterList.indexOfFirst { it.chapterId == id }
        } ?: -1

    val hasPreviousChapter: Boolean get() = currentChapterListIndex > 0
    val hasNextChapter: Boolean
        get() = currentChapterListIndex >= 0 && currentChapterListIndex < chapterList.lastIndex

    /** 当前章的页清单。 */
    val currentPages: List<ReaderPage> get() = chapters?.current?.pages.orEmpty()

    /** 当前章的记录。 */
    val currentChapter: ChapterRecord?
        get() = currentChapterListIndex.takeIf { it >= 0 }?.let { chapterList.getOrNull(it) }

    /**
     * 当前落点是否是过渡页。
     *
     * 过渡页上页码相关的一切都要"置零置灰"（用户要求），而它本身没有任何按钮——
     * 它就是一张夹在中间的图。
     */
    val currentItemIsTransition: Boolean
        get() = items.getOrNull(currentPageIndex) is ReaderItem.Transition

    /** 当前章内已读到的页序号（0 基）；落在过渡页上时为 null。 */
    val localPageIndex: Int?
        get() = (items.getOrNull(currentPageIndex) as? ReaderItem.PageItem)?.page?.ordinal

    /** 当前章内页数；过渡页上仍返回本章页数（滑杆自己决定要不要置灰）。 */
    val currentPageCount: Int get() = currentPages.size

    val readingMode: ReadingMode get() = settings.readingMode
    val isContinuous: Boolean get() = readingMode.continuous

    /** 窗口里是否有章加载失败，供过渡页显示重试。 */
    val windowHasFailedChapter: Boolean
        get() = chapters?.window?.any { it.state == ViewerChapter.LoadState.FAILED } ?: false
}
