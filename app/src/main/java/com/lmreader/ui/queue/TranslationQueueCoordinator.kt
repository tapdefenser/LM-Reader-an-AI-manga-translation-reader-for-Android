package com.lmreader.ui.queue

import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.translation.PageTranslationProgress
import com.lmreader.ui.reader.translation.PageTranslationStage
import com.lmreader.ui.translation.matchEngineLanguage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import com.lmreader.core.workflow.*
import com.lmreader.ui.workflow.*
import com.lmreader.core.api.ApiProfileCodec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class QueueCurrentStep(val pageName: String, val progress: PageTranslationProgress, val rowLabel: String? = null)

class TranslationQueueCoordinator(private val container: AppContainer) {
    private val scope = container.backgroundScope
    private val dao get() = container.database.translationDao()
    private val _items = MutableStateFlow<List<ChapterTranslationEntity>>(emptyList())
    val items = _items.asStateFlow()
    private val _paused = MutableStateFlow(container.queueOrder.isPaused())
    val paused = _paused.asStateFlow()
    private val _orderRevision = MutableStateFlow(0)
    val orderRevision = _orderRevision.asStateFlow()
    val pageUpdates = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val pagePreviews = MutableSharedFlow<com.lmreader.ui.reader.translation.ReaderPageTranslation>(extraBufferCapacity = 8)
    private val previewRevisions = ConcurrentHashMap<String, String>()
    fun isPreviewCurrent(saved: com.lmreader.ui.reader.translation.ReaderPageTranslation) = previewRevisions[saved.pageId] == saved.revision
    private val _priorityPage = MutableStateFlow<String?>(null)
    val priorityPage = _priorityPage.asStateFlow()
    private val budget = TranslationCacheBudget(container.translationCachePreferences.megabytes.value.toLong() * 1_048_576)
    val cacheBytes = budget.bytes
    private val _currentPage = MutableStateFlow<String?>(null)
    val currentPage = _currentPage.asStateFlow()
    private val controlRevision = MutableStateFlow(0L)
    private val pendingOrderPriority = AtomicBoolean(false)
    private val globalCommand = AtomicLong()
    @Volatile private var runner: Job? = null
    @Volatile private var priority = false
    private val priorityActive = MutableStateFlow(false)
    @Volatile private var releasing: Job? = null
    @Volatile private var resourcesOwned = false
    private val resourceMutex = Mutex()
    private val enqueueMutex = Mutex()
    val loadedResources get() = container.loadedTranslationResources
    private val _currentStep = MutableStateFlow<QueueCurrentStep?>(null)
    val currentStep = _currentStep.asStateFlow()
    val activeSeg get() = container.localVision.activeSeg
    val activeOcr get() = container.localVision.activeOcr
    val activeApi get() = container.apiClient.activeRequests

    init {
        scope.launch { container.translationCachePreferences.megabytes.collect { budget.setLimit(it.toLong() * 1_048_576) } }
        scope.launch {
            try { container.startupReady.await() } catch (_: Exception) { return@launch }
            synchronized(this@TranslationQueueCoordinator) { _paused.value = container.queueOrder.isPaused() }
            dao.interruptRunning(System.currentTimeMillis()); dao.deleteInvalidQueueItems()
            dao.observeQueue().collect { _items.value = it; start() }
        }
    }
    @Synchronized fun start() {
        if (!container.taskService.canStart()) return
        if (priority || releasing?.isActive == true || runner?.isActive == true) return
        if (_paused.value || _items.value.none { remaining(it) }) {
            if (resourcesOwned) scheduleResourceRelease()
            return
        }
        if (_items.value.none { runnable(it) }) return
        val nextRunner = scope.launch(start = CoroutineStart.LAZY) {
            var lease: String? = null
            try {
                lease = container.taskService.acquire()
                while (!_paused.value && !priority) {
                    val next = ordered(dao.queueSnapshot()).firstOrNull(::runnable) ?: break
                    runManga(next.mangaId)
                }
            } catch (error: Exception) {
                pause()
                if (error !is CancellationException) android.util.Log.e("TranslationQueue", "后台任务启动或运行失败", error)
            } finally {
                withContext(NonCancellable) { dao.interruptRunning(now()) }
                lease?.let(container.taskService::release)
            }
        }
        runner = nextRunner
        nextRunner.invokeOnCompletion {
            synchronized(this) { if (runner === nextRunner) runner = null }
            start()
        }
        nextRunner.start()
    }
    @Synchronized fun pause() {
        globalCommand.incrementAndGet()
        _paused.value = true
        try { container.queueOrder.setPaused(true) }
        finally { scheduleResourceRelease() }
    }
    @Synchronized private fun scheduleResourceRelease() {
        if (releasing?.isActive == true) return
        val release = scope.launch(start = CoroutineStart.LAZY) {
            runner?.join()
            priorityActive.first { !it }
            resourceMutex.withLock {
                if (!priority && (_paused.value || dao.queueSnapshot().none { remaining(it) })) {
                    container.localPageTranslator.releaseModels(); resourcesOwned = false
                }
            }
        }
        releasing = release
        release.invokeOnCompletion {
            synchronized(this) { if (releasing === release) releasing = null }
            start()
        }
        release.start()
    }
    @Synchronized fun resume() { container.taskService.allowRetry(); container.queueOrder.setPaused(false); _paused.value = false; start() }
    /** Notification resume restores paused items without silently retrying failed chapters. */
    fun resumePaused(): Job {
        val command = globalCommand.incrementAndGet()
        return scope.launch {
            val ids = dao.queueSnapshot().filter { it.state == "PAUSED" }.map { it.chapterId }
            if(ids.isNotEmpty()) { dao.controlQueueItems(ids, "PENDING", now()); controlRevision.update { it + 1 } }
            synchronized(this@TranslationQueueCoordinator) { if(command == globalCommand.get()) resume() }
        }
    }
    /** A new batch starts an empty queue even if its previous batch was manually paused. */
    suspend fun enqueue(mangaId: String, chapterIds: List<String>, request: TranslationRequest): Int = enqueueMutex.withLock {
        container.startupReady.await()
        val command = globalCommand.get()
        val wasEmpty = dao.queueSnapshot().isEmpty()
        val added = container.translationRepository.enqueue(mangaId, chapterIds, request)
        synchronized(this) {
            if (added > 0 && wasEmpty && command == globalCommand.get()) resume() else start()
        }
        added
    }
    suspend fun pauseAndAwait() { pause(); runner?.cancelAndJoin(); releasing?.join() }
    fun startAll(): Job {
        val command = globalCommand.incrementAndGet()
        return scope.launch {
            if (dao.startAllQueueItems(now()) > 0) controlRevision.update { it + 1 }
            synchronized(this@TranslationQueueCoordinator) {
                if (command == globalCommand.get()) resume()
            }
        }
    }
    fun pauseForPriority() { priority = true; priorityActive.value = true }
    suspend fun awaitCurrentPage() { runner?.join() }
    suspend fun awaitResourceRelease() { releasing?.join() }
    fun resumeAfterPriority() { priority = false; priorityActive.value = false; start() }
    fun setPriorityPage(label: String?) { _priorityPage.value = label; if (label == null) _currentStep.value = null }
    fun setPriorityProgress(label: String, progress: PageTranslationProgress, rowLabel: String? = null) { _currentStep.value = QueueCurrentStep(label, progress, rowLabel) }
    fun ordered(rows: List<ChapterTranslationEntity>): List<ChapterTranslationEntity> =
        container.queueOrder.mangaOrder(rows.map { it.mangaId }.distinct()).flatMap { id ->
            val group = rows.filter { it.mangaId == id }.associateBy { it.key() }
            container.queueOrder.chapterOrder(id, group.keys.toList()).mapNotNull(group::get)
        }
    fun moveManga(id: String, delta: Int) {
        container.queueOrder.moveManga(id, delta, ordered(_items.value).map { it.mangaId }.distinct()); orderChanged()
    }
    fun topManga(id: String) { sortManga(listOf(id) + ordered(_items.value).map { it.mangaId }.distinct().filterNot { it == id }) }
    fun moveChapter(mangaId: String, id: String, delta: Int) {
        container.queueOrder.moveChapter(mangaId, id, delta, ordered(_items.value).filter { it.mangaId == mangaId }.map { it.key() })
        orderChanged()
    }
    private fun orderChanged() { pendingOrderPriority.set(true); _orderRevision.value++ }
    fun sortManga(ids: List<String>) { container.queueOrder.sortManga(ids); orderChanged() }
    fun sortChapters(mangaId: String, ids: List<String>) { container.queueOrder.sortChapters(mangaId, ids); orderChanged() }
    fun retry(item: ChapterTranslationEntity) = scope.launch { container.taskService.allowRetry(); dao.retryQueueItem(item.chapterId, item.targetLanguage, now()); controlRevision.update { it + 1 }; start() }
    fun cancel(item: ChapterTranslationEntity) = scope.launch { dao.cancelQueueItems(listOf(item.chapterId), now()); controlRevision.update { it + 1 } }
    fun cancelManga(id: String) = scope.launch { dao.cancelQueueItems(dao.queueSnapshot().filter { it.mangaId == id }.map { it.chapterId }, now()); controlRevision.update { it + 1 } }
    fun setChapterPaused(item: ChapterTranslationEntity, paused: Boolean) = scope.launch {
        if (!paused) container.taskService.allowRetry()
        dao.controlQueueItems(listOf(item.chapterId), if (paused) "PAUSED" else "PENDING", now()); controlRevision.update { it + 1 }; start()
    }
    fun setMangaPaused(id: String, paused: Boolean) = scope.launch {
        if (!paused) container.taskService.allowRetry()
        dao.controlQueueItems(dao.queueSnapshot().filter { it.mangaId == id }.map { it.chapterId },
            if (paused) "PAUSED" else "PENDING", now()); controlRevision.update { it + 1 }; start()
    }
    /** Prevent late publication by the current page, then clear actual page JSON and all language records. */
    suspend fun clearTranslations(mangaId: String, ids: List<String>): Int {
        dao.cancelQueueItems(ids, now())
        controlRevision.update { it + 1 }
        return container.localPageTranslator.pageWriteMutex.withLock {
            val manga = container.mangaRepository.getBackfillTarget(mangaId) ?: error("漫画不存在")
            for (chapter in manga.chapters.filter { it.chapterId in ids }) {
                val opened = container.pageSourceFactory.open(manga.sourceTreeUri, chapter)
                val source = (opened as? PageSourceOpenResult.Ready)?.source
                    ?: error((opened as PageSourceOpenResult.Unsupported).reason)
                for (page in source.pages()) { container.localPageTranslator.artifacts.delete(page.pageId); pageUpdates.emit(page.pageId) }
            }
            container.translationRepository.clearTranslations(mangaId, ids)
        }
    }
    /** The exclusion and deletion share publication's lock, including in-flight native calls. */
    suspend fun clearPage(chapterId: String, pageId: String) = container.localPageTranslator.pageWriteMutex.withLock {
        previewRevisions.remove(pageId)
        val task = dao.byChapter(chapterId).firstOrNull()
        if (task != null) container.queueOrder.excludePage(chapterId, task.queuedAt ?: 0L, pageId)
        controlRevision.update { it + 1 }
        val hadTranslation = container.localPageTranslator.artifacts.has(pageId)
        container.localPageTranslator.artifacts.delete(pageId)
        dao.pageCleared(chapterId, if (hadTranslation) 1 else 0, now())
        pageUpdates.emit(pageId)
    }
    fun includePage(chapterId: String, pageId: String) { container.queueOrder.includePage(chapterId, pageId) }
    suspend fun prepareMangaResources(routes: Set<Pair<LocalTranslationLanguage, LocalTranslationLanguage>>,
        needsSeg: Boolean = true, ocrSources: Set<LocalTranslationLanguage> = routes.map { it.first }.toSet()) = resourceMutex.withLock {
        resourcesOwned = true
        container.localVision.retainModels(needsSeg, ocrSources.map { com.lmreader.ui.reader.translation.LocalPageTranslator.ocrLanguage(it) }.toSet())
        container.localTranslator.retainModels(routes)
    }
    private suspend fun runManga(mangaId: String) {
        val task = ordered(dao.queueSnapshot()).firstOrNull { it.mangaId == mangaId && runnable(it) } ?: return
        when (runCatching { JSONObject(requireNotNull(task.configSnapshot)).getInt("schema") }.getOrNull()) {
            2 -> runProgramManga(task)
            1 -> runLegacyManga(mangaId)
            else -> dao.setQueueState(task.chapterId, task.targetLanguage, "FAILED",
                "无法运行此版本的工作流快照", task.translatedCount, now())
        }
    }
    private suspend fun runProgramManga(first: ChapterTranslationEntity) = coroutineScope {
        val mangaId = first.mangaId
        val initialOrder = _orderRevision.value; val initialControls = controlRevision.value
        val prioritizeFirstPage = pendingOrderPriority.getAndSet(false)
        val tasks = ordered(dao.queueSnapshot()).filter { it.mangaId == mangaId && runnable(it) && it.configSnapshot == first.configSnapshot }
        val taskMap = tasks.associateBy { it.chapterId }
        val counts = ConcurrentHashMap<String, AtomicInteger>(); val totals = mutableMapOf<String, Int>(); val failures = ConcurrentHashMap<String, String>()
        val chapters = mutableListOf<WorkflowChapterInput>()
        var host: AndroidWorkflowHost? = null
        var observer: Job? = null
        try {
            val json = JSONObject(requireNotNull(first.configSnapshot))
            val program = WorkflowProgramCodec.decode(json.getJSONObject("program").toString())
            require(program.uses(WorkflowKind.SEG)) { "工作流需要 SEG 生成气泡" }
            val installedLanguages = container.translationModels.installedCatalog().languages
            val source = matchEngineLanguage(json.getString("sourceLanguage"), installedLanguages) ?: LocalTranslationLanguage.fromTag(json.getString("sourceLanguage"))
            val target = matchEngineLanguage(json.getString("targetLanguage"), installedLanguages) ?: LocalTranslationLanguage.fromTag(json.getString("targetLanguage"))
            val render = BubbleRenderSettings(BubbleFillMode.valueOf(json.getString("fillMode")), json.getInt("opacity"), json.getInt("padding"),
                runCatching { BubbleFont.valueOf(json.optString("font")) }.getOrDefault(BubbleFont.SYSTEM), json.optInt("fontScale", 100), json.optBoolean("bold"), json.optInt("freeTextMaskExpansion", 6))
            val manga = container.mangaRepository.getBackfillTarget(mangaId) ?: error("漫画或来源不存在")
            for (task in tasks) {
                require(task.sourceLanguage == json.getString("sourceLanguage") && task.targetLanguage == json.getString("targetLanguage")) { "任务语言与快照不一致" }
                val chapter = manga.chapters.firstOrNull { it.chapterId == task.chapterId } ?: error("章节不存在")
                val opened = container.pageSourceFactory.open(manga.sourceTreeUri, chapter)
                val pageSource = (opened as? PageSourceOpenResult.Ready)?.source ?: error((opened as PageSourceOpenResult.Unsupported).reason)
                val pages = pageSource.pages(); require(pages.isNotEmpty()) { "章节没有图片" }
                if (chapter.pageCount != pages.size) container.mangaRepository.updateChapterPageInfo(task.chapterId, pages.size, null)
                totals[task.chapterId] = pages.size; counts[task.chapterId] = AtomicInteger()
                val eligible = pages.filterNot { container.queueOrder.isPageExcluded(task.chapterId, task.queuedAt ?: 0, it.pageId) }
                if (eligible.isEmpty()) dao.setQueueState(task.chapterId, task.targetLanguage, "CANCELLED", null, 0, now())
                else chapters += WorkflowChapterInput(task.chapterId, chapter.title, pageSource, eligible)
            }
            // Retain the union of resources needed by every queued revision in this manga.
            val mangaPrograms = ordered(dao.queueSnapshot()).filter { it.mangaId == mangaId && runnable(it) }.mapNotNull {
                runCatching { val snapshot = JSONObject(it.configSnapshot!!); val p = snapshot.optJSONObject("program")?.let { v -> WorkflowProgramCodec.decode(v.toString()) } ?: WorkflowTemplates.localMachine()
                    p to ((matchEngineLanguage(it.sourceLanguage, installedLanguages) ?: LocalTranslationLanguage.fromTag(it.sourceLanguage!!)) to
                        (matchEngineLanguage(it.targetLanguage, installedLanguages) ?: LocalTranslationLanguage.fromTag(it.targetLanguage))) }.getOrNull()
            }
            val routes = mangaPrograms.filter { it.first.uses(WorkflowKind.TRANSLATE) }.map { it.second }.toSet()
            routes.forEach { container.translationModels.installedCatalog().route(it.first, it.second) }
            prepareMangaResources(routes, mangaPrograms.any { it.first.uses(WorkflowKind.SEG) },
                mangaPrograms.filter { it.first.uses(WorkflowKind.OCR) }.map { it.second.first }.toSet())
            val active = ConcurrentHashMap<String, Job>()
            val activeLock = Mutex()
            val preferredPages = chapters.firstOrNull()?.pages.orEmpty().map { it.pageId }
            val firstPage = preferredPages.firstOrNull()
            val preferredPage = MutableStateFlow(firstPage)
            val firstPageFinished = MutableStateFlow(false)
            var primaryStarted = false
            val stopping = MutableStateFlow(false)
            var protectedPage: String? = null
            val wholeBatch = AtomicBoolean(false)
            var wholeRequest: Job? = null
            val settings = WorkflowRunSettings(source, target, json.optString("style"), render, json.getDouble("segThreshold").toFloat(),
                json.optJSONObject("apiProfiles")?.let { ApiProfileCodec.decode(it.toString()) }.orEmpty(),
                SegTextScope.fromValue(json.optString("segTextScope").takeIf { it.isNotBlank() }), json.optDouble("textDetectionThreshold", .45).toFloat(),
                json.optDouble("freeTextMergeGapRatio", 1.2).toFloat())
            val mangaName = container.mangaRepository.getCards(listOf(mangaId)).firstOrNull()?.displayName ?: mangaId
            val runHost = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, mangaName, chapters, settings, budget) {
                override fun continueScheduling(frame: WorkflowFrame?): Boolean = wholeBatch.get() || !stopping.value || frame?.identity(WorkflowSystem.PAGE) == protectedPage && protectedPage != null
                override suspend fun chapters(): List<WorkflowValue.Record> {
                    val available = super.chapters()
                    if(!wholeBatch.get()) return available
                    val rank = ordered(dao.queueSnapshot()).mapIndexed { index, task -> task.chapterId to index }.toMap()
                    return available.sortedBy { rank[(it.fields.getValue("id") as WorkflowValue.Text).value] ?: Int.MAX_VALUE }
                }
                override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                    if(!call.wholeManga) return super.api(call, frame)
                    return supervisorScope {
                        val request = async(start = CoroutineStart.LAZY) { super.api(call, frame) }
                        activeLock.withLock {
                            if(stopping.value || _paused.value || priority) throw WorkflowPageStopped()
                            wholeBatch.set(true); wholeRequest = request
                        }
                        try { request.start(); request.await() }
                        finally { activeLock.withLock { wholeRequest = null } }
                    }
                }
                override suspend fun apiStream(call: WorkflowApiCall, frame: WorkflowFrame, item: suspend (WorkflowValue, Int) -> Unit): WorkflowValue.ListValue {
                    if(!call.wholeManga) return super.apiStream(call, frame, item)
                    return supervisorScope {
                        val request = async(start = CoroutineStart.LAZY) { super.apiStream(call, frame, item) }
                        activeLock.withLock {
                            if(stopping.value || _paused.value || priority) throw WorkflowPageStopped()
                            wholeBatch.set(true); wholeRequest = request
                        }
                        try { request.start(); request.await() }
                        finally { activeLock.withLock { wholeRequest = null } }
                    }
                }
                override suspend fun pagePreviewed(frame: WorkflowFrame, saved: com.lmreader.ui.reader.translation.ReaderPageTranslation) {
                    if(canPublish(frame)) { previewRevisions[saved.pageId] = saved.revision; pagePreviews.emit(saved) }
                }
                override suspend fun beforePage(frame: WorkflowFrame) {
                    if (prioritizeFirstPage && !wholeBatch.get())
                        combine(preferredPage, firstPageFinished, stopping) { preferred, done, stop ->
                            done || stop || frame.identity(WorkflowSystem.PAGE) == preferred
                        }.first { it }
                }
                override suspend fun admitPage(frame: WorkflowFrame, job: Job): WorkflowPageAdmission {
                    val pageId = requireNotNull(frame.identity(WorkflowSystem.PAGE)); val chapterId = requireNotNull(frame.identity(WorkflowSystem.CHAPTER))
                    val task = taskMap.getValue(chapterId)
                    activeLock.withLock {
                        if ((!wholeBatch.get() && stopping.value) || container.queueOrder.isPageExcluded(chapterId, task.queuedAt ?: 0, pageId)) return WorkflowPageAdmission.SKIP
                        active[pageId] = job
                        if (_currentPage.value == null && (primaryStarted || pageId == firstPage)) {
                            _currentPage.value = pageId; primaryStarted = true
                        }
                    }
                    val latest = dao.byChapter(chapterId).firstOrNull()
                    if (latest == null || !runnable(latest) || latest.queuedAt != task.queuedAt || latest.configSnapshot != task.configSnapshot) return WorkflowPageAdmission.SKIP
                    dao.setQueueState(chapterId, task.targetLanguage, "RUNNING", null, counts.getValue(chapterId).get(), now())
                    return super.admitPage(frame, job)
                }
                override suspend fun canPublish(frame: WorkflowFrame): Boolean {
                    val task = taskMap.getValue(requireNotNull(frame.identity(WorkflowSystem.CHAPTER)))
                    val latest = dao.byChapter(task.chapterId).firstOrNull() ?: return false
                    return latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot &&
                        latest.state in setOf("PENDING", "RUNNING", "PAUSED") &&
                        !container.queueOrder.isPageExcluded(task.chapterId, task.queuedAt ?: 0, requireNotNull(frame.identity(WorkflowSystem.PAGE)))
                }
                override suspend fun pagePublished(frame: WorkflowFrame, saved: com.lmreader.ui.reader.translation.ReaderPageTranslation) {
                    val id = requireNotNull(frame.identity(WorkflowSystem.CHAPTER)); val task = taskMap.getValue(id)
                    val count = counts.getValue(id).incrementAndGet()
                    val latest = dao.byChapter(id).firstOrNull()
                    if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot && latest.state in setOf("PENDING", "RUNNING", "PAUSED"))
                        dao.setQueueState(id, task.targetLanguage, if (latest.state == "PAUSED") "PAUSED" else "RUNNING", failures[id], count, now())
                    if (!isCached(frame)) pageUpdates.emit(saved.pageId)
                }
                override suspend fun closePage(frame: WorkflowFrame) {
                    super.closePage(frame)
                    frame.identity(WorkflowSystem.PAGE)?.let { previewRevisions.remove(it) }
                    frame.identity(WorkflowSystem.PAGE)?.let { if(!published.containsKey(it)) pageUpdates.emit(it) }
                    finishActivePage(frame)
                }
                override suspend fun closePreparationPage(frame: WorkflowFrame) {
                    super.closePreparationPage(frame)
                    finishActivePage(frame)
                }
                private suspend fun finishActivePage(frame: WorkflowFrame) {
                    activeLock.withLock {
                        val pageId = frame.identity(WorkflowSystem.PAGE); active.remove(pageId)
                        if (pageId == preferredPage.value && !firstPageFinished.value) {
                            val next = preferredPages.getOrNull(preferredPages.indexOf(pageId) + 1)
                            if (isCached(frame) && next != null) preferredPage.value = next
                            else firstPageFinished.value = true
                        }
                        if (_currentPage.value == pageId) { _currentPage.value = active.keys.firstOrNull(); _currentStep.value = null }
                    }
                }
                override suspend fun pageFailed(frame: WorkflowFrame, failure: Throwable) {
                    val id = requireNotNull(frame.identity(WorkflowSystem.CHAPTER)); failures.putIfAbsent(id, failure.message ?: "工作流失败")
                }
                override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) {
                    container.localPageTranslator.pageWriteMutex.withLock {
                    val id = requireNotNull(frame.identity(WorkflowSystem.CHAPTER)); val task = taskMap.getValue(id)
                    val latest = dao.byChapter(id).firstOrNull() ?: return
                    if (latest.queuedAt != task.queuedAt || latest.configSnapshot != task.configSnapshot || latest.state !in setOf("PENDING", "RUNNING", "PAUSED")) return
                    val pages = chapters.first { it.id == id }.pages
                    val count = pages.count { container.localPageTranslator.artifacts.has(it.pageId) && !container.queueOrder.isPageExcluded(id, task.queuedAt ?: 0, it.pageId) }
                    val failure = failures[id]
                    val state = when { failure != null -> "FAILED"; complete -> if (count == totals[id]) "DONE" else "CANCELLED"; latest.state == "PAUSED" -> "PAUSED"; else -> "PENDING" }
                    dao.setQueueState(id, task.targetLanguage, state, failure, count, now())
                    }
                }
                override fun step(node: WorkflowNode, frame: WorkflowFrame) {
                    val pageId = frame.identity(WorkflowSystem.PAGE)
                    if (pageId == _currentPage.value || pageId == null) {
                        val stage = when(node.kind) { WorkflowKind.SEG -> PageTranslationStage.SEGMENTING; WorkflowKind.OCR -> PageTranslationStage.OCR; else -> PageTranslationStage.TRANSLATING }
                        _currentStep.value = QueueCurrentStep(pageLabel(frame), PageTranslationProgress(stage), node.label.ifBlank { WorkflowLabels.kind(node.kind) })
                    }
                }
                override fun progress(frame: WorkflowFrame, progress: PageTranslationProgress) {
                    if (frame.identity(WorkflowSystem.PAGE) == _currentPage.value)
                        _currentStep.value = QueueCurrentStep(pageLabel(frame), progress, if (progress.stage == PageTranslationStage.SAVING) "保存译文" else _currentStep.value?.rowLabel)
                }
            }
            host = runHost
            observer = launch {
                combine(_orderRevision, controlRevision, _paused, priorityActive) { order, controls, pause, high ->
                    order != initialOrder || controls != initialControls || pause || high
                }.collect { changed ->
                    if(!changed) return@collect
                    val latest = dao.queueSnapshot().associateBy { it.chapterId }
                    activeLock.withLock {
                        if(wholeBatch.get()) {
                            // Reordering and global pause retain the one response through publication.
                            // Cancellation of the entire batch can still interrupt its network request.
                            if(tasks.none { task -> latest[task.chapterId]?.let { it.queuedAt == task.queuedAt && it.configSnapshot == task.configSnapshot && it.state in setOf("PENDING", "RUNNING", "PAUSED") } == true }) {
                                wholeRequest?.cancel(WorkflowPageStopped())
                            }
                        } else if(!stopping.value) {
                            protectedPage = _currentPage.value; stopping.value = true
                            active.filterKeys { it != protectedPage }.values.forEach { it.cancel(WorkflowPageStopped()) }
                        }
                    }
                }
            }
            WorkflowRuntime(8, json.optInt("retries", 0).coerceIn(0, 5)).execute(program, runHost)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            for (task in tasks) {
                val latest = dao.byChapter(task.chapterId).firstOrNull()
                if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot && runnable(latest))
                    dao.setQueueState(task.chapterId, task.targetLanguage, "FAILED", failure.message, counts[task.chapterId]?.get() ?: task.translatedCount, now())
            }
        } finally {
            withContext(NonCancellable) {
            observer?.cancelAndJoin(); host?.close(); _currentPage.value = null; _currentStep.value = null
            for (task in tasks) {
                val latest = dao.byChapter(task.chapterId).firstOrNull()
                if (latest?.state == "RUNNING" && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot)
                    dao.setQueueState(task.chapterId, task.targetLanguage, "PENDING", null, counts[task.chapterId]?.get() ?: latest.translatedCount, now())
            }
            }
        }
    }
    private suspend fun runLegacyManga(mangaId: String) = coroutineScope {
        val initialOrder = _orderRevision.value
        val initialControls = controlRevision.value
        val works = ArrayList<QueuePageWork>()
        val counts = mutableMapOf<String, Int>()
        val totals = mutableMapOf<String, Int>()
        val pageTotals = mutableMapOf<String, Int>()
        for (task in ordered(dao.queueSnapshot()).filter { it.mangaId == mangaId && runnable(it) }) {
            if (runCatching { JSONObject(task.configSnapshot!!).optInt("schema") }.getOrDefault(1) != 1) continue
            try {
                val json = JSONObject(requireNotNull(task.configSnapshot))
                require(json.getInt("schema") == 1) { "无法运行此版本的工作流快照" }
                require(json.getString("sourceLanguage") == task.sourceLanguage && json.getString("targetLanguage") == task.targetLanguage)
                val languages = container.translationModels.installedCatalog().languages
                val source = matchEngineLanguage(task.sourceLanguage, languages) ?: error("原文语言的模型包未安装")
                val target = matchEngineLanguage(task.targetLanguage, languages) ?: error("目标语言的模型包未安装")
                container.translationModels.installedCatalog().route(source, target)
                val render = BubbleRenderSettings(BubbleFillMode.valueOf(json.getString("fillMode")), json.getInt("opacity"), json.getInt("padding"),
                    runCatching { BubbleFont.valueOf(json.optString("font")) }.getOrDefault(BubbleFont.SYSTEM),
                    json.optInt("fontScale", 100), json.optBoolean("bold", false), json.optInt("freeTextMaskExpansion", 6))
                val manga = container.mangaRepository.getBackfillTarget(task.mangaId) ?: error("漫画或来源不存在")
                val chapter = manga.chapters.firstOrNull { it.chapterId == task.chapterId } ?: error("章节不存在")
                val opened = container.pageSourceFactory.open(manga.sourceTreeUri, chapter)
                val pageSource = (opened as? PageSourceOpenResult.Ready)?.source ?: error((opened as PageSourceOpenResult.Unsupported).reason)
                val pages = pageSource.pages(); require(pages.isNotEmpty()) { "章节没有可翻译图片" }
                if (chapter.pageCount != pages.size) container.mangaRepository.updateChapterPageInfo(chapter.chapterId, pages.size, null)
                val eligible = pages.filterNot { container.queueOrder.isPageExcluded(task.chapterId, task.queuedAt ?: 0L, it.pageId) }
                totals[task.chapterId] = eligible.size; counts[task.chapterId] = 0
                pageTotals[task.chapterId] = pages.size
                if (eligible.isEmpty()) dao.setQueueState(task.chapterId, task.targetLanguage, "CANCELLED", null, 0, now())
                works += eligible.map { QueuePageWork(task, pageSource, it, source, target, render,
                    json.getDouble("segThreshold").toFloat(), json.getInt("retries").coerceIn(0, 5),
                    SegTextScope.fromValue(json.optString("segTextScope").takeIf { it.isNotBlank() }), json.optDouble("textDetectionThreshold", .45).toFloat(),
                    json.optDouble("freeTextMergeGapRatio", 1.2).toFloat()) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                dao.setQueueState(task.chapterId, task.targetLanguage, "FAILED", failure.message, task.translatedCount, now())
            }
        }
        if (works.isEmpty()) return@coroutineScope
        prepareMangaResources(works.map { it.sourceLanguage to it.targetLanguage }.toSet())
        val consumed = mutableSetOf<String>()
        val pipeline = MangaPagePipeline(this, works, container.localPageTranslator, budget,
            container.localVision.segConcurrency, container.localVision.ocrConcurrency)
        try {
            while (!_paused.value && !priority) {
                // Rebuild admission order after controls/drag so old buffered pages cannot
                // consume the entire MB budget ahead of the newly selected first page.
                if (_orderRevision.value != initialOrder || controlRevision.value != initialControls) break
                val task = ordered(dao.queueSnapshot()).firstOrNull(::runnable) ?: break
                if (task.mangaId != mangaId) break
                val work = works.firstOrNull { it.task.chapterId == task.chapterId && it.task.queuedAt == task.queuedAt &&
                    it.task.configSnapshot == task.configSnapshot && it.page.pageId !in consumed } ?: break
                dao.setQueueState(task.chapterId, task.targetLanguage, "RUNNING", null,
                    maxOf(counts[task.chapterId] ?: 0, task.translatedCount), now())
                _currentPage.value = work.page.pageId
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    pipeline.progress(work.page.pageId).collect { _currentStep.value = QueueCurrentStep(work.page.displayName, it) }
                }
                try {
                    val recognized = pipeline.await(work.page.pageId)
                    container.localPageTranslator.pageWriteMutex.withLock {
                        if (_paused.value || priority || container.queueOrder.isPageExcluded(task.chapterId, task.queuedAt ?: 0L, work.page.pageId) ||
                            dao.queueSnapshot().none { it.chapterId == task.chapterId && it.queuedAt == task.queuedAt && it.configSnapshot == task.configSnapshot && runnable(it) }) return@withLock
                        if (recognized != null) retry(work.retries) {
                            pipeline.setProgress(work.page.pageId, PageTranslationProgress(PageTranslationStage.TRANSLATING))
                            container.localPageTranslator.translateRecognized(recognized, work.sourceLanguage, work.targetLanguage, work.render) {
                                pipeline.setProgress(work.page.pageId, it)
                            }
                        }
                        consumed += work.page.pageId
                        val completed = (counts[task.chapterId] ?: 0) + 1; counts[task.chapterId] = completed
                        if (recognized != null) pageUpdates.emit(work.page.pageId)
                        val latest = dao.byChapter(task.chapterId).firstOrNull()
                        if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot)
                            dao.setQueueState(task.chapterId, task.targetLanguage,
                            if (latest.state == "CANCELLED") "CANCELLED" else if (completed == totals[task.chapterId])
                                if (completed == pageTotals[task.chapterId]) "DONE" else "CANCELLED"
                            else if (latest.state == "PAUSED") "PAUSED" else "PENDING",
                            null, completed, now())
                    }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    val latest = dao.queueSnapshot().firstOrNull { it.chapterId == task.chapterId }
                    if (latest != null && runnable(latest)) dao.setQueueState(task.chapterId, task.targetLanguage,
                        "FAILED", failure.message, counts[task.chapterId] ?: 0, now())
                    // Discard remaining prefetch from the failed chapter so its buffers
                    // cannot occupy the budget needed by the next runnable chapter.
                    break
                } finally {
                    observer.cancelAndJoin(); pipeline.consume(work.page.pageId)
                    _currentPage.value = null; _currentStep.value = null
                }
                // Next iteration re-evaluates manga and chapter positions after each atomic page.
            }
        } finally {
            withContext(NonCancellable) {
            pipeline.close()
            for (task in dao.queueSnapshot().filter { it.mangaId == mangaId && it.state == "RUNNING" })
                dao.setQueueState(task.chapterId, task.targetLanguage, "PENDING", null, counts[task.chapterId] ?: task.translatedCount, now())
            }
        }
    }
    private fun runnable(item: ChapterTranslationEntity) = item.state == "PENDING" || item.state == "RUNNING"
    private fun remaining(item: ChapterTranslationEntity) = item.state in setOf("PENDING", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED")
    private fun now() = System.currentTimeMillis()
}

fun ChapterTranslationEntity.key(): String = chapterId
