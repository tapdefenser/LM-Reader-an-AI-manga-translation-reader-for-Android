package com.lmreader.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lmreader.core.index.ChapterOrdering
import com.lmreader.core.model.Category
import com.lmreader.core.model.ChapterRecord
import com.lmreader.core.model.ChapterTranslation
import com.lmreader.core.model.MangaRecord
import com.lmreader.core.model.MangaRepository
import com.lmreader.core.model.ReadingProgress
import com.lmreader.core.model.ReadingProgressRepository
import com.lmreader.core.model.ShelfRepository
import com.lmreader.core.model.SourceRepository
import com.lmreader.core.model.TranslationRepository
import com.lmreader.core.model.TranslationRequest
import com.lmreader.core.model.resolveSourceLanguage
import com.lmreader.core.model.resolveTargetLanguage
import com.lmreader.core.model.translationSetupComplete
import com.lmreader.core.model.resolveTranslationStyle
import com.lmreader.core.storage.cover.CoverMetadataWriter
import com.lmreader.core.storage.cover.CoverResolver
import com.lmreader.core.storage.reader.PageSourceFactory
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.storage.scan.ChapterSyncOutcome
import com.lmreader.core.storage.scan.MangaChapterSyncer
import com.lmreader.core.storage.settings.AppPreferences
import com.lmreader.core.storage.settings.BubbleRenderPreferences
import com.lmreader.ui.workflow.TranslationWorkflowStore
import com.lmreader.ui.workflow.translationTaskSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MangaDetailViewModel(
    private val mangaId: String,
    private val mangaRepository: MangaRepository,
    private val sourceRepository: SourceRepository,
    private val shelfRepository: ShelfRepository,
    private val translationRepository: TranslationRepository,
    private val chapterSyncer: MangaChapterSyncer,
    private val progressRepository: ReadingProgressRepository,
    private val pageSourceFactory: PageSourceFactory,
    private val preferences: AppPreferences,
    private val bubblePreferences: BubbleRenderPreferences,
    private val workflowStore: TranslationWorkflowStore,
    private val translationQueue: com.lmreader.ui.queue.TranslationQueueCoordinator,
    private val installedTranslationCatalog: () -> com.lmreader.core.translation.TranslationModelCatalog,
    private val coverResolver: CoverResolver,
    private val coverMetadataWriter: CoverMetadataWriter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val apiProfiles: suspend () -> List<com.lmreader.core.model.ApiProfile> = { emptyList() },
) : ViewModel() {
    private val _state = MutableStateFlow(MangaDetailUiState())
    val state: StateFlow<MangaDetailUiState> = _state.asStateFlow()

    /**
     * 页数回填只跑一次。
     *
     * 用一个字段而不是每次 `reload()` 都跑：`reload()` 会在每次回到详情页时触发
     * （含从阅读器返回），不能让它每次都去枚举目录。
     */
    private var pageBackfillStarted = false

    init {
        viewModelScope.launch {
            translationQueue.items.collect {
                val records = translationRepository.chapterTranslations(mangaId)
                _state.update { current -> current.copy(translations = records) }
            }
        }
        viewModelScope.launch {
            shelfRepository.ensureUncategorized()
            shelfRepository.observeCategories().collect { categories ->
                _state.update { it.copy(categories = categories) }
            }
        }
        // 章节排序方式（用户勾选的那一项）与"是否手动拖过"：两者都只是**显示**用
        // （抽屉里标出当前是哪一种、还是手动）。顺序本身在 `chapters.position` 里，
        // 不靠这两个偏好推导。
        viewModelScope.launch {
            preferences.chapterOrder.collect { setting ->
                _state.update { it.copy(orderSetting = setting) }
            }
        }
        viewModelScope.launch {
            preferences.chapterOrderManual.collect { manual ->
                _state.update { it.copy(orderManual = manual) }
            }
        }
        // 首次进入详情页：加载详情 → 回填页数 → **自动更新一次章节**（不弹提示）。
        //
        // 为什么自动更新（用户要求）：不自动更新时，新加进目录的章节不会出现，
        // 而"更新章节"这个按钮的含义对用户来说是"让列表变准"——那是打开详情页时
        // 就该成立的前提，不该让他每次多点一下。
        //
        // 为什么不放进 reload()：reload() 也会被"重试"按钮与**从阅读器返回**触发，
        // 每次返回都重新枚举章节目录是浪费（一部 100 章的漫画就是 100 次目录枚举）。
        // 这里的 init 只在这个详情页的 ViewModel 首次创建时跑一次。
        viewModelScope.launch {
            loadDetail(showLoading = true)
            resolveCover(force = false)
            backfillPageCounts()
            runChapterSync(announce = false)
        }
    }

    fun reload() {
        viewModelScope.launch {
            loadDetail(showLoading = _state.value.manga == null)
            backfillPageCounts()
        }
    }

    /**
     * 取这一部漫画的封面，**并顺手更新简介**（用户要求：在所有更新封面的时候也要更新简介）。
     *
     * ## 两条入口，语义不同
     *
     * - `force = false`：进详情页时的**懒加载**。只在"还没有封面且从未探测过"
     *   （`coverProbedAt == null`）或"**简介从未读过**"（`metadataProbedAt == null`）
     *   时才动手——图库里滚过的卡片封面早就探测过了，再取一次只是白关一次目录，
     *   但简介没读过的那些必须在这里补上，否则详情页会一直是"作者：未知 / 无简介"，
     *   而要等扫描期的补全队列（每来源每轮 120 条）慢慢轮到；
     * - `force = true`：点「更新章节」。用户明确要求**必取**：章节列表刚刚被完整
     *   枚举过，此刻的"第一章"才是权威的，封面与首章简介都必须按它重算并刷新探测记录。
     *
     * ## 写完为什么要再 loadDetail 一次
     *
     * `manga` 是这次加载的快照，简介与作者由 `CoverMetadataWriter` 写进了数据库；
     * 不重读的话界面会一直显示改之前的值，直到用户离开再回来。
     */
    private suspend fun resolveCover(force: Boolean) {
        val state = _state.value
        val needsCover = state.coverDocumentId == null && state.manga?.coverProbedAt == null
        val needsMetadata = force || state.manga?.metadataProbedAt == null
        if (!needsCover && !needsMetadata) return
        val target = runCatching { mangaRepository.coverProbeTargets(listOf(mangaId)) }
            .getOrNull()
            ?.firstOrNull()
            ?: return
        val resolved = runCatching { coverResolver.resolve(target) }.getOrNull()
        val at = clock()
        runCatching {
            coverMetadataWriter.write(mangaId, resolved, at, forceMetadata = force)
        }
        _state.update { it.copy(coverDocumentId = resolved?.coverDocumentId) }
        if (needsMetadata) loadDetail(showLoading = false)
    }

    /**
     * 给还没有页数的章节补上页数。
     *
     * ## 为什么需要它
     *
     * 页数原本只在**打开过那一章**时回填（`ImageDirectoryPageSource.pages()` 之后由
     * `updateChapterPageInfo` 写入）。真机核查发现可读的 4704 个章节里**只有 6 个**
     * 有页数——于是详情页几乎看不到「共 X 页」，用户会以为「更新章节」坏了。
     *
     * ## 为什么不在扫描/同步时就全量补齐
     *
     * 那需要打开每一个章节目录并列出其子项。对一部 100 章的漫画就是 100 次目录枚举，
     * 会让扫描变得很慢，而且大多数章节用户根本不会打开。这里改成**按需、有上限**：
     * 每次打开详情页最多补 [PAGE_BACKFILL_LIMIT] 章，剩下的下次再补。
     *
     * ## 与「更新章节」的分工
     *
     * 那个按钮走 `MangaChapterSyncer.sync`，负责**章节集合本身**的变化（新增/消失）。
     * 这里只补页数，不改章节集合，因此不会与它冲突。
     */
    private fun backfillPageCounts() {
        if (pageBackfillStarted) return
        val chapters = _state.value.chapters
        val sourceTreeUri = _state.value.sourceTreeUri ?: return
        val pending = chapters.filter { it.pageCount == null }
        if (pending.isEmpty()) return
        pageBackfillStarted = true

        viewModelScope.launch {
            var filled = 0
            for (chapter in pending.take(PAGE_BACKFILL_LIMIT)) {
                val counted = runCatching {
                    // 目录枚举是真正的 IO：`viewModelScope` 跑在主调度器上，
                    // 不切走的话"进详情页要点 8 次目录"会直接卡住首帧。
                    withContext(Dispatchers.IO) {
                        when (val opened = pageSourceFactory.open(sourceTreeUri, chapter)) {
                            is PageSourceOpenResult.Unsupported -> null
                            is PageSourceOpenResult.Ready -> {
                                val pages = opened.source.pages()
                                // 空页清单不写：写了会显示"共 0 页"，比"未知"更糟。
                                if (pages.isEmpty()) null else pages.size to pages.first().documentId
                            }
                        }
                    }
                }.getOrNull() ?: continue

                val (pageCount, coverDocumentId) = counted
                runCatching {
                    mangaRepository.updateChapterPageInfo(
                        chapterId = chapter.chapterId,
                        pageCount = pageCount,
                        coverDocumentId = coverDocumentId,
                    )
                }
                filled++
            }
            if (filled > 0) {
                // 只在真的补到了东西时重载：否则每次进详情页都会多一次数据库往返。
                loadDetail(showLoading = false)
            }
        }
    }

    /** 「更新章节」按钮：更新并**告诉用户结果**。 */
    fun syncChapters() = runChapterSync(announce = true)

    /**
     * 更新章节列表。
     *
     * @param announce 是否在成功后弹提示。**自动更新时不弹**：进详情页自动更新是
     *   后台行为，每次进来都弹一条"章节已更新"是噪音，用户并没有要求这件事。
     *   但**失败一定要报到**——列表可能是旧的，用户得知道为什么。
     */
    private fun runChapterSync(announce: Boolean) {
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, message = null) }
            try {
                when (val outcome = chapterSyncer.sync(mangaId)) {
                    is ChapterSyncOutcome.Failure -> _state.update {
                        it.copy(syncing = false, message = outcome.reason)
                    }
                    is ChapterSyncOutcome.Success -> {
                        loadDetail(showLoading = false)
                        // 「更新章节」必取封面（用户要求）：章节列表刚刚被完整枚举，
                        // 此刻的"第一章"才是权威的。这里**强制**重取，不看 coverProbedAt。
                        resolveCover(force = true)
                        _state.update {
                            it.copy(
                                syncing = false,
                                message = if (announce) {
                                    "章节已更新，共 ${outcome.chapters.size} 章"
                                } else {
                                    it.message
                                },
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(syncing = false, message = error.message ?: "更新章节失败")
                }
            }
        }
    }

    /**
     * 更新章节期间用户试图进入阅读时调用的提示（用户口径：更新章节时进入阅读的行为要阻塞）。
     *
     * 只发一句提示，**不做任何导航**：拦在界面的唯一阅读入口上（`startReading`），
     * 因此这里不需要"再检查一次状态"。
     */
    fun showReadBlockedHint() {
        if (!_state.value.syncing) return
        _state.update { it.copy(message = "正在更新章节，完成后再开始阅读") }
    }

    fun addToShelf(categoryId: Long) {
        viewModelScope.launch {
            try {
                shelfRepository.addToShelf(mangaId, categoryId)
                _state.update { it.copy(inShelf = true, message = "已加入书架") }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "加入书架失败") }
            }
        }
    }

    fun removeFromShelf() {
        viewModelScope.launch {
            try {
                shelfRepository.removeFromShelf(mangaId)
                _state.update { it.copy(inShelf = false, message = "已移出书架") }
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "移出书架失败") }
            }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    /**
     * 应用一种章节排序方式：**整表重排**（用户已确认这是期望行为）。
     *
     * 三件事必须一起做，缺一个都会出现"界面和库里不一致"：
     * 1. 内存里立刻按新顺序显示（用户点完马上看到结果）；
     * 2. 把结果写进 `chapters.position`——顺序是**库里的数据**，不是显示层的技巧，
     *    否则退出详情页再进来就变回去了；
     * 3. 记住这次的选择（[AppPreferences.chapterOrder]）：扫描发现新章节时要按它插入。
     *
     * 同一项再点一次 = 反向（用户口径：点一次正向，点两次逆向）。
     */
    fun applyChapterOrder(mode: ChapterOrdering.Mode) {
        val current = _state.value.orderSetting
        val reversed = current.mode == mode && !current.descending
        val setting = ChapterOrdering.Setting(mode = mode, descending = reversed)
        val reordered = ChapterOrdering.resort(_state.value.chapters, setting)
        _state.update { it.copy(chapters = reordered, orderSetting = setting, orderManual = false) }
        viewModelScope.launch {
            runCatching { preferences.setChapterOrder(mode, reversed) }
            runCatching { mangaRepository.setChapterOrder(mangaId, reordered.map { it.chapterId }) }
                .onFailure { _state.update { state -> state.copy(message = "排序未能保存") } }
        }
    }

    /**
     * 一次手动拖动的结果：把 [chapterId] 从当前位置移到 [toIndex]。
     *
     * 拖动即"顺序由我决定"：写库之外还把 `chapterOrderManual` 置位，让抽屉如实显示
     * "当前：手动"。**不改**用户已保存的排序方式——用户口径是新章节依然按它插入，
     * 手动结果不被重排。
     *
     * 多选态下拖动也只移动这一章（用户口径：一次拖动只能拖动一个章节）。
     */
    fun moveChapter(chapterId: String, toIndex: Int) {
        val chapters = _state.value.chapters
        val from = chapters.indexOfFirst { it.chapterId == chapterId }
        if (from < 0) return
        val target = toIndex.coerceIn(0, chapters.lastIndex)
        if (from == target) return
        val reordered = chapters.toMutableList().apply { add(target, removeAt(from)) }
            .mapIndexed { index, chapter -> chapter.copy(position = index.toLong()) }
        _state.update { it.copy(chapters = reordered, orderManual = true) }
        viewModelScope.launch {
            runCatching { preferences.setChapterOrderManual(true) }
            runCatching { mangaRepository.setChapterOrder(mangaId, reordered.map { it.chapterId }) }
                .onFailure { _state.update { state -> state.copy(message = "新顺序未能保存") } }
        }
    }

    // ---- 多选（为翻译铺路，见开发文档 8.1 章节多选） --------------------------

    /** 长按一行：没选中就选中它，已选中就取消它（与 Mihon 一致）。 */
    fun toggleSelection(chapterId: String) {
        _state.update { state ->
            val selection = state.selection
            state.copy(
                selection = if (chapterId in selection) selection - chapterId else selection + chapterId,
            )
        }
    }

    fun selectAllChapters() {
        _state.update { it.copy(selection = it.chapters.mapTo(LinkedHashSet()) { c -> c.chapterId }) }
    }

    fun invertSelection() {
        _state.update { state ->
            state.copy(selection = state.chapters.filterNot { it.chapterId in state.selection }
                .mapTo(LinkedHashSet()) { it.chapterId })
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    /**
     * 批量标记已读/未读。
     *
     * 只改 [ReadingProgress.read]，**不动阅读位置**：用户批量标已读时不该把他"读到第几页"
     * 的记录顺手改掉（那会让"继续阅读"跳到别处）。
     */
    fun markSelectionRead(read: Boolean) {
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { mangaRepository.setChapterRead(ids.toList(), read) }
                .onFailure {
                    _state.update { state -> state.copy(message = "标记未能保存") }
                    return@launch
                }
            loadDetail(showLoading = false)
            val label = if (read) "已标记已读" else "已标记未读"
            _state.update { it.copy(message = "$label ${ids.size} 章") }
        }
    }

    // ---- 翻译（阶段 2：真的写队列，引擎是 P3） ------------------------------

    /** 把选中章节入队（多选底栏的「翻译所选」）。 */
    fun translateSelection() = enqueue(_state.value.selection.toList())

    /** 整部作品入队（⋮ 的「全部翻译」）。 */
    fun translateAll() = enqueue(_state.value.chapters.map { it.chapterId })

    /**
     * 入队：写入待翻译记录。
     *
     * **先检查原文语言选没选**（用户口径：每部作品都得手动选，没有全局默认）：没选就把用户
     * 带到「翻译选项」页，由那一页弹提示（详情页弹的话会随导航立刻消失），而不是先排上队、
     * 等真要翻的时候才发现原文语言是空的。
     * 填完之后由用户**自己再点一次**翻译——不自动续跑（用户明确要求"手动重新启动翻译"）：
     * 自动续跑会造出"我刚点了一下，回来发现已经在翻了"这种不可预期的行为。
     *
     * 源语言与目标语言都检查；工作流不能填补缺失语言。
     *
     * 记录里带**入队那一刻**解析出来的语言与文风快照（见 `TranslationRequest`），
     * 因此用户随后改设置不会让已排队的任务换一种翻法。
     */
    private fun enqueue(chapterIds: List<String>) {
        if (chapterIds.isEmpty()) {
            _state.update { it.copy(message = "没有可翻译的章节") }
            return
        }
        viewModelScope.launch {
            val settings = mangaRepository.translationSettings(mangaId)
            val (source, autoDetect) = resolveSourceLanguage(settings)
            // 目标语言只来自本漫画的显式设置。
            val target = resolveTargetLanguage(settings)
            if (!translationSetupComplete(source, target, autoDetect)) {
                // 提示语不在这里给：由「翻译选项」页弹自己的 snackbar（见 showSetupPrompt）。
                // 详情页的 snackbar 会随导航把本页移出组合而立刻消失，用户看不到。
                _state.update { it.copy(openTranslationOptions = true) }
                return@launch
            }
            val configuredTarget = target ?: return@launch
            val workflow = workflowStore.find(settings.workflowId)
            if (workflow == null) {
                _state.update { it.copy(message = "所选翻译工作流已删除，请重新选择") }
                return@launch
            }
            val configuredSource = source ?: return@launch
            val catalog = installedTranslationCatalog()
            val sourceLanguage = com.lmreader.ui.translation.matchEngineLanguage(configuredSource, catalog.languages)
            val targetLanguage = com.lmreader.ui.translation.matchEngineLanguage(configuredTarget, catalog.languages)
            if (workflow.program.uses(com.lmreader.core.model.WorkflowKind.TRANSLATE) &&
                (sourceLanguage == null || targetLanguage == null || sourceLanguage == targetLanguage ||
                runCatching { catalog.route(sourceLanguage, targetLanguage) }.isFailure)) {
                _state.update { it.copy(openTranslationOptions = true) }
                return@launch
            }
            val globalStyle = preferences.translationGlobalStyle.first()
            val categoryId = shelfRepository.categoryIdOf(mangaId)
            val categoryStyle = categoryId?.let { id ->
                shelfRepository.observeCategories().first().firstOrNull { it.categoryId == id }?.customStyle
            }
            val snapshot = runCatching { translationTaskSnapshot(workflow, settings, configuredSource, configuredTarget,
                resolveTranslationStyle(settings, categoryStyle, globalStyle), bubblePreferences.settings.first(), apiProfiles()) }
                .getOrElse { failure -> _state.update { it.copy(message = failure.message ?: "工作流配置无效") }; return@launch }
            val queued = runCatching {
                translationQueue.enqueue(
                    mangaId = mangaId,
                    chapterIds = chapterIds,
                    request = TranslationRequest(
                        targetLanguage = configuredTarget,
                        sourceLanguage = source,
                        autoDetectSource = autoDetect,
                        configSnapshot = snapshot,
                        at = clock(),
                    ),
                )
            }.getOrElse { error ->
                _state.update { it.copy(message = error.message ?: "入队失败") }
                return@launch
            }
            clearSelection()
            loadDetail(showLoading = false)
            _state.update {
                it.copy(
                    message = if (queued == 0) {
                        "这些章节已经排过队或翻完了"
                    } else {
                        "已加入待翻译 $queued 章（目标语言：$target）"
                    },
                )
            }
        }
    }

    /** 界面消费完"请去翻译选项"这个事件之后调用。 */
    fun consumeOpenTranslationOptions() {
        _state.update { it.copy(openTranslationOptions = false) }
    }

    /** 清除现有译文并取消排队；无需填写翻译语言，清除后显示未翻译。 */
    fun clearSelectionTranslations() {
        val ids = _state.value.selection.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val affected = runCatching {
                translationQueue.clearTranslations(mangaId, ids)
            }.getOrElse { error ->
                _state.update { it.copy(message = error.message ?: "清除失败") }
                return@launch
            }
            clearSelection()
            loadDetail(showLoading = false)
            _state.update { it.copy(message = "已清除 $affected 章的译文") }
        }
    }

    private suspend fun loadDetail(showLoading: Boolean) {
        if (showLoading) _state.update { it.copy(loading = true, error = null) }
        try {
            val target = mangaRepository.getBackfillTarget(mangaId)
                ?: error("漫画或来源已不存在")
            val source = sourceRepository.getSource(target.manga.sourceId)
                ?: error("来源已被删除")
            val card = mangaRepository.getCards(listOf(mangaId)).firstOrNull()
            // 阅读进度要一起读出来：章节行要显示"读到第几页"，而且"继续阅读"要能从
            // 那一页打开。放在同一次加载里而不是让 UI 各自去查，避免两处显示不一致。
            val progress = progressRepository.get(mangaId)
            // 章节列表按**用户看到的顺序**（`position`）读，而不是 `target.chapters`
            // 的自然序：封面与简介要自然序第一章，章节列表要用户排的顺序，两者不同源。
            val chapters = mangaRepository.getChaptersInDisplayOrder(mangaId)
            val readMarks = mangaRepository.chapterReadMarks(mangaId)
            // 章节状态与当前语言选择无关，每章只有一套记录。
            val targetLanguage = resolveTargetLanguage(mangaRepository.translationSettings(mangaId)).orEmpty()
            val translations = translationRepository.chapterTranslations(mangaId)
            _state.update {
                it.copy(
                    loading = false,
                    manga = target.manga,
                    coverDocumentId = target.manga.coverDocumentId,
                    chapters = chapters,
                    readMarks = readMarks,
                    translations = translations,
                    translationTargetLanguage = targetLanguage,
                    sourceTreeUri = source.treeUri,
                    sourceDisplayPath = source.displayPath,
                    inShelf = card?.inShelf == true,
                    progress = progress,
                    // 章节集合变了（同步/扫描）之后，已经不在列表里的选择必须清掉，
                    // 否则"翻译所选"会把已经不存在的章节也算进去。
                    selection = it.selection.intersect(chapters.mapTo(HashSet()) { c -> c.chapterId }),
                    error = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(loading = false, error = error.message ?: "读取漫画详情失败") }
        }
    }

    companion object {
        fun factory(
            container: com.lmreader.di.AppContainer,
            mangaId: String,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MangaDetailViewModel(
                    mangaId = mangaId,
                    mangaRepository = container.mangaRepository,
                    sourceRepository = container.sourceRepository,
                    shelfRepository = container.shelfRepository,
                    translationRepository = container.translationRepository,
                    chapterSyncer = container.mangaChapterSyncer,
                    progressRepository = container.readingProgressRepository,
                    pageSourceFactory = container.pageSourceFactory,
                    preferences = container.preferences,
                    bubblePreferences = container.bubbleRenderPreferences,
                    workflowStore = container.translationWorkflows,
                    translationQueue = container.translationQueue,
                    installedTranslationCatalog = { container.translationModels.installedCatalog() },
                    coverResolver = container.coverResolver,
                    coverMetadataWriter = container.coverMetadataWriter,
                    apiProfiles = { container.apiProfiles.profiles.first() },
                )
            }
        }
    }
}

private const val PAGE_BACKFILL_LIMIT = 8

data class MangaDetailUiState(
    val loading: Boolean = true,
    val manga: MangaRecord? = null,
    /**
     * 详情页显示的封面；进页面后异步取到（或点「更新章节」强制重取）。
     *
     * 单独一个字段而不是复用 `manga.coverDocumentId`：探测结果必须能立刻反映到界面，
     * 而 `manga` 是这次加载的快照。两者在"刚探测完"这一刻必然不同。
     */
    val coverDocumentId: String? = null,
    /**
     * 章节列表，**已经按用户看到的顺序**（`chapters.position`）排好。
     *
     * 不再有"再排一次"的派生字段：顺序是库里的数据（谁排的、怎么排的都写进去了），
     * 显示层原样渲染。以前那个 `sortedChapters`（按偏好反转）在 v6 之后没有意义——
     * 方向已经是排序方式的一部分，并已落进 `position`。
     */
    val chapters: List<ChapterRecord> = emptyList(),
    /** 按章的已读标记；没有条目的章节 = 未读。 */
    val readMarks: Map<String, Boolean> = emptyMap(),
    /**
     * 按章的翻译记录（当前生效目标语言下的）；没有条目的章节 = 未翻译。
     *
     * 与 [readMarks] 并排而不是塞进 `ChapterRecord`：它们是**用户状态**，章节行每次扫描
     * 都会被重写（开发文档 15.3 要求用户状态与索引分表），因此只能从各自的表读进来。
     */
    val translations: Map<String, ChapterTranslation> = emptyMap(),
    /** 这些翻译记录属于哪种目标语言（界面文案要写出来，否则用户不知道在给哪种语言排队）。 */
    val translationTargetLanguage: String = "",
    /**
     * "请去翻译选项"的一次性事件。
     *
     * ViewModel 不认识导航，因此只置一个标记，由界面消费后调
     * [consumeOpenTranslationOptions] 清掉——用事件而不是持续状态，避免用户从翻译选项页
     * 返回详情页时又被弹一次。
     */
    val openTranslationOptions: Boolean = false,
    /** 用户保存的章节排序方式；抽屉据此显示"当前是哪一种"。 */
    val orderSetting: ChapterOrdering.Setting = ChapterOrdering.Setting.DEFAULT,
    /** 用户是否手动拖过章节顺序（抽屉显示「当前：手动」）。 */
    val orderManual: Boolean = false,
    /**
     * 多选中的章节 id。非空 = 处于多选态（顶栏换成 ✕/计数/全选反选，底栏换成批量动作）。
     */
    val selection: Set<String> = emptySet(),
    val sourceTreeUri: String? = null,
    val sourceDisplayPath: String? = null,
    val inShelf: Boolean = false,
    val categories: List<Category> = emptyList(),
    val syncing: Boolean = false,
    /** 这部漫画的阅读进度；null 表示还没读过。 */
    val progress: ReadingProgress? = null,
    val error: String? = null,
    val message: String? = null,
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()

    /** 抽屉里给当前项打勾用；手动拖过之后不再属于任何一种排序方式。 */
    val activeSortMode: ChapterOrdering.Mode? get() = if (orderManual) null else orderSetting.mode

    /** 已读数，给"共 N 章"旁边显示进度用。 */
    val readCount: Int get() = chapters.count { readMarks[it.chapterId] == true }
}
