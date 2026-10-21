package com.lmreader.ui.queue

import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.tasks.TaskRecord
import androidx.room.withTransaction
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskExecution
import com.lmreader.tasks.TaskRequeue
import com.lmreader.tasks.TaskScheduler
import com.lmreader.tasks.TaskWaiting
import com.lmreader.tasks.inTranslationQueue
import com.lmreader.tasks.translationGroupKey
import com.lmreader.ui.reader.translation.PageTranslationProgress
import com.lmreader.ui.reader.translation.PageTranslationStage
import com.lmreader.ui.translation.matchEngineLanguage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import com.lmreader.core.workflow.*
import com.lmreader.ui.workflow.*
import com.lmreader.core.api.ApiProfileCodec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.selects.select

data class QueueCurrentStep(val pageName: String, val progress: PageTranslationProgress, val rowLabel: String? = null,
    val mangaId: String? = null, val chapterName: String = "", val pageId: String? = null)

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
    private val budget = TranslationCacheBudget(container.translationCachePreferences.megabytes.value.toLong() * 1_048_576, container.imageCacheBudget)
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
    private var lastStartState = ""
    private val resourceMutex = Mutex()
    private val enqueueMutex = Mutex()
    private val legacyReady = CompletableDeferred<Unit>()
    internal suspend fun awaitReady() { legacyReady.await() }
    val loadedResources get() = container.loadedTranslationResources
    private val _currentStep = MutableStateFlow<QueueCurrentStep?>(null)
    val currentStep = _currentStep.asStateFlow()
    private val _currentSteps = MutableStateFlow<Map<String, QueueCurrentStep>>(emptyMap())
    val currentSteps = _currentSteps.asStateFlow()
    private val _activeMangas = MutableStateFlow<Set<String>>(emptySet())
    val activeMangas = _activeMangas.asStateFlow()
    private val apiWaiting = ConcurrentHashMap<String, ConcurrentHashMap<String, AtomicInteger>>()
    private val apiWake = MutableStateFlow(0L)
    private val preprocessingAllowed = MutableStateFlow<Set<String>>(emptySet())
    private fun runningPlan(mangaId: String, plan: TranslationResourcePlan): TranslationResourcePlan {
        val profiles = apiWaiting[mangaId]?.filterValues { it.get() > 0 }?.keys.orEmpty()
        return plan.waitingForApi(profiles)
    }
    private fun workflowApiWaiting(mangaId: String, profileId: String, waiting: Boolean) {
        val counters = apiWaiting.computeIfAbsent(mangaId) { ConcurrentHashMap() }
        counters.computeIfAbsent(profileId) { AtomicInteger() }.addAndGet(if(waiting) 1 else -1)
        apiWake.update { it + 1 }
    }
    private val _schedulingPriority = MutableStateFlow(TranslationSchedulingPriority.entries
        .firstOrNull { it.name == container.taskScheduler.orderMode.value } ?: container.queueOrder.schedulingPriority())
    val schedulingPriority = _schedulingPriority.asStateFlow()
    fun setSchedulingPriority(value: TranslationSchedulingPriority) {
        container.queueOrder.setSchedulingPriority(value)
        container.taskScheduler.setOrderMode(value.name)
        _schedulingPriority.value = value
        orderChanged()
    }
    /** Chapter work already admitted by the canonical ledger owns its publication gate. */
    private val executionsByManga = ConcurrentHashMap<String, TaskExecution>()
    /** Whole-manga API calls keep their single in-flight response even when the legacy queue pauses. */
    private val wholeWorkMangas = ConcurrentHashMap.newKeySet<String>()
    /** Live per-task copy of payload `_pausedChapterIds`; commands update it before editing the row. */
    private val pausedChapterIntents = ConcurrentHashMap<String, MutableSet<String>>()
    @Volatile private var pauseCommand: Job? = null
    private fun updateFocus(mangaId: String, value: QueueCurrentStep?) {
        _currentSteps.update { if (value == null) it - mangaId else it + (mangaId to value) }
        if (priority) return
        val first = ordered(_items.value).firstOrNull { it.mangaId in _currentSteps.value }?.mangaId
        _currentStep.value = first?.let { _currentSteps.value[it] } ?: _currentSteps.value.values.firstOrNull()
        _currentPage.value = _currentStep.value?.pageId
    }
    val activeSeg get() = container.localVision.activeSeg
    val activeOcr get() = container.localVision.activeOcr
    val activeApi get() = container.apiClient.activeRequests

    init {
        scope.launch { container.translationCachePreferences.megabytes.collect {
            budget.setLimit(it.toLong() * 1_048_576)
            container.localVision.setPreprocessingCacheLimit(it.toLong() * 1_048_576)
        } }
        scope.launch {
            container.taskScheduler.orderMode.collect { mode ->
                val value = TranslationSchedulingPriority.entries.firstOrNull { it.name == mode } ?: return@collect
                if (_schedulingPriority.value != value) _schedulingPriority.value = value
            }
        }
        scope.launch {
            try { container.startupReady.await() } catch (_: Exception) { return@launch }
            synchronized(this@TranslationQueueCoordinator) { _paused.value = container.queueOrder.isPaused() }
            _schedulingPriority.value = TranslationSchedulingPriority.entries
                .firstOrNull { it.name == container.taskScheduler.orderMode.value } ?: container.queueOrder.schedulingPriority()
            dao.interruptRunning(System.currentTimeMillis()); dao.deleteInvalidQueueItems()
            container.taskScheduler.ready.await()
            val inheritedPause = _paused.value
            val existing = container.taskScheduler.dao.all()
            // Old queue holds become pauses on existing cards, including every chapter in
            // a held batch. A later single-chapter start must not revive its neighbours.
            for(task in existing.filter { it.type == "CHAPTER" && it.state == "PAUSED" }) {
                val ids = taskChapterIds(task).toSet()
                val rows = dao.queueSnapshot().filter { it.chapterId in ids && it.state != "FAILED" }
                if(rows.isNotEmpty()) dao.upsertAll(rows.map { it.copy(state = "PAUSED", updatedAt = now()) })
                mutateChapterTask(task.taskId) { json -> addPausedChapters(json, ids.toList()) }
            }
            for((id, rows) in dao.queueSnapshot().groupBy { it.mangaId }) {
                val owner = "chapters:$id"
                if(existing.any { it.ownerKey == owner && it.state !in TaskScheduler.TERMINAL }) continue
                val title = container.mangaRepository.getCards(listOf(id)).firstOrNull()?.displayName ?: id
                val individuallyPaused = rows.filter { it.state == "PAUSED" || inheritedPause && it.state != "FAILED" }.map { it.chapterId }
                if(inheritedPause) dao.upsertAll(rows.filter { it.state != "FAILED" }.map { it.copy(state = "PAUSED", updatedAt = now()) })
                val allFailed = rows.isNotEmpty() && rows.all { it.state == "FAILED" }
                val held = inheritedPause || rows.all { it.state == "PAUSED" }
                val state = when {
                    allFailed -> "FAILED"
                    held -> "PAUSED"
                    else -> "QUEUED"
                }
                val reason = when {
                    state != "PAUSED" -> null
                    inheritedPause -> CHAPTER_QUEUE
                    else -> "USER"
                }
                val goal = uniqueGoalKey("legacy-chapters:$id")
                val payload = JSONObject().put("mangaId", id)
                    .put("chapterIds", JSONArray(rows.map { it.chapterId }))
                    .put("_pausedChapterIds", JSONArray(individuallyPaused))
                    .put("_goalKey", goal).toString()
                container.workDatabase.withTransaction {
                    container.taskScheduler.dao.insert(TaskRecord(
                        taskId = UUID.randomUUID().toString(), type = "CHAPTER", title = title, ownerKey = owner,
                        activeKey = if (state in TaskScheduler.TERMINAL) null else goal, payload = payload,
                        orderIndex = System.currentTimeMillis(), state = state,
                        userPaused = state == "PAUSED", pauseReason = reason,
                        failure = rows.firstOrNull { it.state == "FAILED" }?.failure))
                }
            }
            // 启动时默认暂停：旧队列的暂停意图转成逐卡暂停后，不要把这次默认暂停清掉。
            if(!inheritedPause) { container.queueOrder.setPaused(false); _paused.value = false }
            container.taskScheduler.poke()
            legacyReady.complete(Unit)
            dao.observeQueue().collect { _items.value = it; start() }
        }
        scope.launch {
            legacyReady.await()
            // Canonical completion is the moment a later pending batch becomes eligible for a
            // missing goal. This observer only submits; it never resumes an existing intent.
            val terminalSeen = ConcurrentHashMap.newKeySet<String>()
            container.taskScheduler.tasks.collect { rows ->
                val terminal = rows.filter { it.type == "CHAPTER" && it.state in TaskScheduler.TERMINAL }
                if (terminal.any { terminalSeen.add(it.taskId) }) start()
            }
        }
    }
    init {
        container.taskScheduler.register("CHAPTER") { execution ->
            legacyReady.await()
            if(_paused.value) throw TaskWaiting(CHAPTER_QUEUE, "翻译队列已暂停，请手动继续")
            val payload=JSONObject(execution.payload)
            val mangaId=payload.getString("mangaId")
            val ids = payload.optJSONArray("chapterIds")?.let(::stringSet)
            val initialPaused = payload.optJSONArray("_pausedChapterIds")?.let(::stringSet).orEmpty()
            executionsByManga[mangaId] = execution
            pausedChapterIntents.compute(execution.taskId) { _, existing ->
                (existing ?: ConcurrentHashMap.newKeySet<String>()).apply { addAll(initialPaused) }
            }
            _activeMangas.update {it+mangaId}
            try {
                val restore = dao.byManga(mangaId).filter { row -> (ids == null || row.chapterId in ids) &&
                    !isChapterPaused(execution.taskId, row.chapterId) &&
                    (row.state == "INTERRUPTED" || row.state == "PAUSED" || payload.optBoolean("_retryFailed") && row.state == "FAILED") }
                if(restore.isNotEmpty()) dao.controlQueueItems(restore.map { it.chapterId }, "PENDING", now())
                if(payload.optBoolean("_retryFailed")) mutateChapterTask(execution.taskId) { it.remove("_retryFailed") }
                dao.byManga(mangaId).firstOrNull { ids == null || it.chapterId in ids }?.configSnapshot?.let { snapshot ->
                    val json = JSONObject(snapshot)
                    val program = json.optJSONObject("program")?.let { WorkflowProgramCodec.decode(it.toString()) } ?: WorkflowTemplates.localMachine()
                    com.lmreader.tasks.requireWorkflowCapabilities(container, program,
                        LocalTranslationLanguage.fromTag(json.getString("sourceLanguage")), LocalTranslationLanguage.fromTag(json.getString("targetLanguage")),
                        json.optJSONObject("apiProfiles")?.let { com.lmreader.core.api.ApiProfileCodec.decode(it.toString()) } ?: emptyList())
                }
                runManga(mangaId, ids, execution)
                execution.checkActive()
                val rows=dao.byManga(mangaId).filter { ids == null || it.chapterId in ids }
                val paused=rows.filter { it.state != "DONE" &&
                    (it.state == "PAUSED" || isChapterPaused(execution.taskId, it.chapterId)) }
                if(paused.isNotEmpty()) throw TaskWaiting(CHAPTER_PAUSED,
                    "有 ${paused.size} 个已暂停章节；继续这些章节后任务会恢复")
                val unfinished=rows.filter { it.state in setOf("PENDING", "RUNNING", "INTERRUPTED") }
                if(unfinished.isNotEmpty()) {
                    if(_paused.value) throw TaskWaiting(CHAPTER_QUEUE, "章节队列已暂停，已保留完成成果")
                    throw TaskRequeue()
                }
                val failed=rows.firstOrNull {it.state=="FAILED"}
                if(failed!=null) error(failed.failure ?: "章节翻译失败")
                val done=rows.sumOf {it.translatedCount.toLong()}
                execution.progress(done,0,"已完成")
            } finally {
                executionsByManga.remove(mangaId, execution)
                pausedChapterIntents.remove(execution.taskId)
                _activeMangas.update {it-mangaId}
                start()
            }
        }
    }
    @Synchronized fun start() {
        if(!container.taskService.canStart() || _paused.value || !container.startupReady.isCompleted || runner?.isCompleted==false) return
        if(_items.value.none(::runnable)) {
            if(resourcesOwned && _activeMangas.value.isEmpty()) scheduleResourceRelease()
            return
        }
        val job=scope.launch(start=CoroutineStart.LAZY) {
            container.taskScheduler.ready.await()
            val snapshot=ordered(dao.queueSnapshot())
            val candidates=snapshot.filter(::runnable).distinctBy {it.mangaId}
            for(task in candidates) {
                val owner="chapters:${task.mangaId}"
                val covered = container.taskScheduler.dao.all().filter { it.ownerKey == owner && it.state !in TaskScheduler.TERMINAL }
                    .flatMap { taskChapterIds(it) }.toSet()
                // A batch owns every still-unfinished row, including individually paused chapters.
                // Paused intent travels in the canonical payload instead of competing as a dispatcher.
                val selected=snapshot.filter { it.mangaId==task.mangaId && it.chapterId !in covered && it.state in setOf("PENDING", "RUNNING", "PAUSED") }
                val runnable=selected
                if(runnable.isEmpty()) continue
                val title=container.mangaRepository.getCards(listOf(task.mangaId)).firstOrNull()?.displayName ?: task.mangaId
                val paused=runnable.filter { it.state == "PAUSED" }.map { it.chapterId }
                val goal="$owner:${task.queuedAt}:${task.configSnapshot.hashCode()}:${UUID.randomUUID()}"
                container.taskScheduler.submit("CHAPTER",title,owner,goal,
                    JSONObject().put("mangaId",task.mangaId)
                        .put("chapterIds", JSONArray(runnable.map { it.chapterId }))
                        .put("_pausedChapterIds", JSONArray(paused)).toString())
            }
            container.taskScheduler.poke()
        }
        runner=job
        job.invokeOnCompletion {synchronized(this) {if(runner===job) runner=null}}
        job.start()
    }
    private fun queueWakeStamp(): List<Any> = listOf(_items.value, _orderRevision.value, controlRevision.value, _paused.value, priorityActive.value, apiWake.value)
    @Synchronized fun pause() {
        globalCommand.incrementAndGet()
        _paused.value = true
        try { container.queueOrder.setPaused(true) }
        finally { scheduleResourceRelease() }
        val previous = pauseCommand
        pauseCommand = scope.launch {
            previous?.join()
            container.taskScheduler.ready.await()
            for (task in chapterTasks()) {
                if (task.state !in setOf("QUEUED", "RUNNING", "PAUSING", "WAITING", "INTERRUPTED")) continue
                // A single whole-work response keeps its protected request through this category pause.
                val mangaId = chapterTaskMangaId(task) ?: continue
                if (task.state == "RUNNING" && mangaId in wholeWorkMangas) continue
                container.taskScheduler.pause(task.taskId, pauseReason = CHAPTER_QUEUE)
            }
        }
    }
    @Synchronized private fun scheduleResourceRelease() {
        if (releasing?.isActive == true) return
        val precedingRunner = runner
        val release = scope.launch(start = CoroutineStart.LAZY) {
            precedingRunner?.join()
            _activeMangas.first { it.isEmpty() }
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
    @Synchronized fun resume() {
        container.taskService.allowRetry(); container.queueOrder.setPaused(false); _paused.value = false
        releasing?.cancel()
        val previous = pauseCommand
        scope.launch {
            previous?.join()
            container.taskScheduler.ready.await()
            // Only the old queue-category pause is undone here. USER/global task pause is durable.
            chapterTasks().filter { !it.remoteOutcomeUnknown && (it.state == "PAUSED" && it.pauseReason == CHAPTER_QUEUE || it.state == "WAITING" && it.waitingReason == CHAPTER_QUEUE) }
                .forEach { container.taskScheduler.resume(it.taskId) }
            start()
        }
    }
    /** Notification resume restores paused items without silently retrying failed chapters. */
    fun resumePaused(): Job {
        val command = globalCommand.incrementAndGet()
        return scope.launch {
            pauseCommand?.join()
            container.taskScheduler.ready.await()
            val individual = chapterTasks().filter { it.state !in TaskScheduler.TERMINAL }
                .flatMap { taskPausedChapterIds(it) }.toSet()
            val ids = dao.queueSnapshot().filter { it.state == "PAUSED" && it.chapterId !in individual }.map { it.chapterId }
            if(ids.isNotEmpty()) { dao.controlQueueItems(ids, "PENDING", now()); controlRevision.update { it + 1 } }
            chapterTasks().filter { !it.remoteOutcomeUnknown && (it.state == "PAUSED" && it.pauseReason == CHAPTER_QUEUE || it.state == "WAITING" && it.waitingReason == CHAPTER_QUEUE) }
                .forEach { container.taskScheduler.resume(it.taskId) }
            synchronized(this@TranslationQueueCoordinator) {
                if(command == globalCommand.get()) {
                    container.queueOrder.setPaused(false); _paused.value = false; releasing?.cancel(); start()
                }
            }
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
    suspend fun pauseAndAwait() {
        pause(); pauseCommand?.join(); runner?.join()
        container.taskScheduler.ready.await()
        chapterTasks().filter { it.state !in TaskScheduler.TERMINAL }
            .forEach { container.taskScheduler.pause(it.taskId, pauseReason = CHAPTER_QUEUE) }
        releasing?.join()
    }
    fun startAll(retryFailed: Boolean = true): Job {
        val command = globalCommand.incrementAndGet()
        return scope.launch {
            pauseCommand?.join()
            container.taskService.allowRetry()
            container.taskScheduler.ready.await()
            val unknown = chapterTasks().filter { it.remoteOutcomeUnknown }
                .flatMap { taskChapterIds(it) }.toSet()
            val rows = dao.queueSnapshot()
            val resumeIds = rows.filter { it.state == "PAUSED" && it.chapterId !in unknown }.map { it.chapterId }
            if (resumeIds.isNotEmpty()) dao.controlQueueItems(resumeIds, "PENDING", now())
            rows.filter { (it.state == "INTERRUPTED" || retryFailed && it.state == "FAILED") && it.chapterId !in unknown }
                .forEach { dao.retryQueueItem(it.chapterId, it.targetLanguage, now()) }
            if (rows.any { it.chapterId !in unknown && it.state in setOf("PAUSED", "FAILED", "INTERRUPTED") }) controlRevision.update { it + 1 }
            // Explicit start-all is the retry action: clear individual pause intent, but never
            // retry an unresolved remote request or manufacture a new FAILED task.
            for (task in chapterTasks()) {
                if(task.remoteOutcomeUnknown || task.state == "FAILED" && !retryFailed) continue
                mutateChapterTask(task.taskId) { json -> json.put("_pausedChapterIds", JSONArray()) }
                pausedChapterIntents[task.taskId]?.clear()
                when {
                    task.remoteOutcomeUnknown -> Unit
                    task.pauseReason == "GLOBAL" && retryFailed -> Unit
                    task.state == "FAILED" -> container.taskScheduler.resume(task.taskId, retryFailed = true)
                    task.state in setOf("PAUSED", "WAITING", "INTERRUPTED") -> container.taskScheduler.resume(task.taskId)
                }
            }
            synchronized(this@TranslationQueueCoordinator) {
                if (command == globalCommand.get()) {
                    container.queueOrder.setPaused(false); _paused.value = false; releasing?.cancel(); start()
                }
            }
        }
    }
    /** A targeted start clears the category hold; other paused rows remain paused. */
    fun clearQueuePauseIntent() {
        globalCommand.incrementAndGet()
        container.queueOrder.setPaused(false); _paused.value = false; releasing?.cancel()
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
        syncCanonicalMangaOrder()
    }
    fun topManga(id: String) { sortManga(listOf(id) + ordered(_items.value).map { it.mangaId }.distinct().filterNot { it == id }) }
    fun moveChapter(mangaId: String, id: String, delta: Int) {
        container.queueOrder.moveChapter(mangaId, id, delta, ordered(_items.value).filter { it.mangaId == mangaId }.map { it.key() })
        orderChanged()
    }
    private fun orderChanged() { pendingOrderPriority.set(true); _orderRevision.value++ }
    fun sortManga(ids: List<String>) { container.queueOrder.sortManga(ids); orderChanged(); syncCanonicalMangaOrder() }
    fun sortChapters(mangaId: String, ids: List<String>) { container.queueOrder.sortChapters(mangaId, ids); orderChanged() }
    fun retry(item: ChapterTranslationEntity) = scope.launch {
        container.taskService.allowRetry()
        pauseCommand?.join()
        container.taskScheduler.ready.await()
        val tasks = chapterTasksForChapter(item.mangaId, item.chapterId)
        if (tasks.any { it.remoteOutcomeUnknown }) return@launch
        dao.controlQueueItems(listOf(item.chapterId), "PENDING", now())
        dao.retryQueueItem(item.chapterId, item.targetLanguage, now())
        controlRevision.update { it + 1 }
        for (task in tasks) {
            mutateChapterTask(task.taskId) { json -> removePausedChapter(json, item.chapterId) }
            pausedChapterIntents[task.taskId]?.remove(item.chapterId)
            when {
                task.remoteOutcomeUnknown -> Unit
                task.state == "FAILED" -> container.taskScheduler.resume(task.taskId, retryFailed = true)
                task.pauseReason == "GLOBAL" -> Unit
                task.state in setOf("PAUSED", "WAITING", "INTERRUPTED") ->
                    container.taskScheduler.resume(task.taskId, onlyPauseReason = task.pauseReason)
            }
        }
        start()
    }
    fun cancel(item: ChapterTranslationEntity) = scope.launch {
        pauseCommand?.join()
        container.taskScheduler.ready.await()
        val tasks = chapterTasksForChapter(item.mangaId, item.chapterId).filter { it.state !in setOf("CANCELLED", "SUCCEEDED") }
        for (task in tasks) {
            val remaining = mutateChapterTask(task.taskId) { json -> removeChapter(json, item.chapterId) }
            pausedChapterIntents[task.taskId]?.remove(item.chapterId)
            if (taskChapterIds(remaining ?: task).isEmpty()) container.taskScheduler.pause(task.taskId, cancel = true)
        }
        container.localPageTranslator.pageWriteMutex.withLock {
            dao.cancelQueueItems(listOf(item.chapterId), now()); controlRevision.update { it + 1 }
        }
        start()
    }
    fun cancelManga(id: String) = scope.launch {
        pauseCommand?.join()
        container.taskScheduler.ready.await()
        val ids = dao.queueSnapshot().filter { it.mangaId == id }.map { it.chapterId }
        for (task in chapterTasksForManga(id).filter { it.state !in setOf("CANCELLED", "SUCCEEDED") }) {
            mutateChapterTask(task.taskId) { json -> removeChapters(json, ids) }
            pausedChapterIntents[task.taskId]?.removeAll(ids.toSet())
            container.taskScheduler.pause(task.taskId, cancel = true)
        }
        container.localPageTranslator.pageWriteMutex.withLock {
            dao.cancelQueueItems(ids, now()); controlRevision.update { it + 1 }
        }
        start()
    }
    fun setChapterPaused(item: ChapterTranslationEntity, paused: Boolean) = scope.launch {
        if (!paused) container.taskService.allowRetry()
        pauseCommand?.join()
        container.taskScheduler.ready.await()
        val tasks = chapterTasksForChapter(item.mangaId, item.chapterId).filter { it.state !in TaskScheduler.TERMINAL }
        for (task in tasks) {
            mutateChapterTask(task.taskId) { json ->
                if (paused) addPausedChapter(json, item.chapterId) else removePausedChapter(json, item.chapterId)
            }
            val intents = pausedChapterIntents.getOrPut(task.taskId) { ConcurrentHashMap.newKeySet() }
            if (paused) intents.add(item.chapterId) else intents.remove(item.chapterId)
        }
        dao.controlQueueItems(listOf(item.chapterId), if (paused) "PAUSED" else "PENDING", now())
        controlRevision.update { it + 1 }
        if (!paused) tasks.forEach { task ->
            when {
                task.remoteOutcomeUnknown -> Unit
                    task.state in setOf("PAUSED", "INTERRUPTED") ->
                    container.taskScheduler.resume(task.taskId, onlyPauseReason = task.pauseReason)
                task.state == "WAITING" -> container.taskScheduler.resume(task.taskId)
            }
        }
        start()
    }
    fun setMangaPaused(id: String, paused: Boolean) = scope.launch {
        if (!paused) container.taskService.allowRetry()
        pauseCommand?.join()
        container.taskScheduler.ready.await()
        val tasks = chapterTasksForManga(id).filter { it.state !in TaskScheduler.TERMINAL }
        for (task in tasks) {
            mutateChapterTask(task.taskId) { json ->
                if (paused) addPausedChapters(json, taskChapterIds(task))
                else removePausedChapters(json, taskChapterIds(task))
            }
            val intents = pausedChapterIntents.getOrPut(task.taskId) { ConcurrentHashMap.newKeySet() }
            if (paused) intents.addAll(taskChapterIds(task)) else intents.removeAll(taskChapterIds(task))
        }
        val ids = dao.queueSnapshot().filter { it.mangaId == id && (paused || it.state != "FAILED") }.map { it.chapterId }
        if (paused) {
            tasks.filter { it.state in setOf("QUEUED", "RUNNING", "PAUSING", "WAITING", "INTERRUPTED") }
                .forEach { container.taskScheduler.pause(it.taskId, pauseReason = "USER") }
            dao.controlQueueItems(ids, "PAUSED", now())
        } else {
            dao.controlQueueItems(ids, "PENDING", now())
            tasks.forEach { task ->
                when {
                    task.remoteOutcomeUnknown -> Unit
                    task.state in setOf("PAUSED", "INTERRUPTED") -> container.taskScheduler.resume(task.taskId)
                    task.state == "WAITING" -> container.taskScheduler.resume(task.taskId)
                }
            }
        }
        controlRevision.update { it + 1 }
        start()
    }
    /** Prevent late publication by the current page, then clear actual page JSON and all language records. */
    suspend fun clearTranslations(mangaId: String, ids: List<String>): Int {
        pauseCommand?.join()
        container.taskScheduler.ready.await()
        container.taskScheduler.dao.all().filter { row -> row.type == "READER_PAGE" && row.state !in TaskScheduler.TERMINAL &&
            runCatching { JSONObject(row.payload).let { it.optString("mangaId") == mangaId && it.optString("chapterId") in ids } }.getOrDefault(false) }
            .forEach { container.taskScheduler.pause(it.taskId, cancel = true) }
        for (task in chapterTasksForManga(mangaId).filter { it.state !in TaskScheduler.TERMINAL }) {
            val remaining = mutateChapterTask(task.taskId) { json -> removeChapters(json, ids) }
            pausedChapterIntents[task.taskId]?.removeAll(ids.toSet())
            if (taskChapterIds(remaining ?: task).isEmpty()) container.taskScheduler.pause(task.taskId, cancel = true)
        }
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
    suspend fun clearPage(chapterId: String, pageId: String) {
        container.taskScheduler.ready.await()
        container.taskScheduler.dao.all().filter { it.ownerKey == "reader-page:$pageId" && it.state !in TaskScheduler.TERMINAL }
            .forEach { container.taskScheduler.pause(it.taskId, cancel = true) }
        container.localPageTranslator.pageWriteMutex.withLock {
        previewRevisions.remove(pageId)
        val task = dao.byChapter(chapterId).firstOrNull()
        if (task != null) container.queueOrder.excludePage(chapterId, task.queuedAt ?: 0L, pageId)
        controlRevision.update { it + 1 }
        val hadTranslation = container.localPageTranslator.artifacts.has(pageId)
        container.localPageTranslator.artifacts.delete(pageId)
        dao.pageCleared(chapterId, if (hadTranslation) 1 else 0, now())
        pageUpdates.emit(pageId)
        }
    }
    fun includePage(chapterId: String, pageId: String) { container.queueOrder.includePage(chapterId, pageId) }
    /** Screen hot reload must preserve routes belonging to queued manga and an active reader page. */
    suspend fun retainScreenTranslationModels(routes: Set<LocalTranslationRoute>) = resourceMutex.withLock {
        if (priorityPage.value != null) return@withLock
        val queuedRoutes = dao.queueSnapshot().filter(::runnable).flatMap { task -> runCatching {
            val json = JSONObject(requireNotNull(task.configSnapshot))
            val program = json.optJSONObject("program")?.let { WorkflowProgramCodec.decode(it.toString()) } ?: WorkflowTemplates.localMachine()
            val source = LocalTranslationLanguage.fromTag(json.getString("sourceLanguage"))
            val target = LocalTranslationLanguage.fromTag(json.getString("targetLanguage"))
            program.localTranslationRoutes(source, target).toList()
        }.getOrDefault(emptyList()) }.toSet()
        val catalog = container.translationModels.installedCatalog()
        val installedQueuedRoutes = queuedRoutes.filter { runCatching { catalog.route(it.source, it.target, it.engine) }.isSuccess }
        container.localTranslator.retainEngineModels(routes + installedQueuedRoutes)
    }
    suspend fun prepareMangaResources(routes: Set<LocalTranslationRoute>,
        needsSeg: Boolean = true, ocrSources: Set<LocalTranslationLanguage> = routes.map { it.source }.toSet(),
        needTextDetection: Boolean = needsSeg || ocrSources.isNotEmpty()) = resourceMutex.withLock {
        resourcesOwned = true
        android.util.Log.i("TranslationQueue", "Reconciling engines routes=${routes.size} SEG=$needsSeg OCR=${ocrSources.map { it.tag }}")
        val queued = dao.queueSnapshot().filter(::runnable).mapNotNull { task -> runCatching {
            val json = JSONObject(requireNotNull(task.configSnapshot))
            val program = json.optJSONObject("program")?.let { WorkflowProgramCodec.decode(it.toString()) } ?: WorkflowTemplates.localMachine()
            val source = LocalTranslationLanguage.fromTag(json.getString("sourceLanguage"))
            val target = LocalTranslationLanguage.fromTag(json.getString("targetLanguage"))
            Triple(program, source, target) to SegTextScope.fromValue(json.optString("segTextScope").takeIf { it.isNotBlank() })
        }.getOrNull() }
        val allRoutes = routes + queued.flatMap { it.first.first.localTranslationRoutes(it.first.second, it.first.third) }
        val allOcr = ocrSources + queued.filter { it.first.first.uses(WorkflowKind.OCR) }.map { it.first.second }
        container.localVision.reconfigure(needsSeg || queued.any { it.first.first.uses(WorkflowKind.SEG) && it.second != SegTextScope.FREE_TEXT },
            allOcr.map { com.lmreader.ui.reader.translation.LocalPageTranslator.ocrLanguage(it) }.toSet(),
            needTextDetection || queued.any { it.first.first.uses(WorkflowKind.SEG) || it.first.first.uses(WorkflowKind.OCR) }, warm = false)
        container.localVision.prepare(needsSeg, ocrSources.map { com.lmreader.ui.reader.translation.LocalPageTranslator.ocrLanguage(it) }.toSet(), needTextDetection)
        container.localTranslator.retainEngineModels(allRoutes)
        val preparationOwner = "queue-prepare:${java.util.UUID.randomUUID()}"
        try { container.localTranslator.prepareEngineModels(preparationOwner, routes) }
        finally { container.localTranslator.unpin(preparationOwner) }
        android.util.Log.i("TranslationQueue", "Engines ready")
    }
    private suspend fun runManga(mangaId: String, allowed: Set<String>?, execution: TaskExecution) {
        val task = ordered(dao.queueSnapshot()).firstOrNull {
            it.mangaId == mangaId && (allowed == null || it.chapterId in allowed) && runnable(it) &&
                !isChapterPaused(execution.taskId, it.chapterId)
        } ?: return
        when (runCatching { JSONObject(requireNotNull(task.configSnapshot)).getInt("schema") }.getOrNull()) {
            2 -> runProgramManga(task, allowed, execution)
            1 -> runLegacyManga(mangaId, allowed, execution)
            else -> dao.setQueueState(task.chapterId, task.targetLanguage, "FAILED",
                "无法运行此版本的工作流快照", task.translatedCount, now())
        }
    }
    private suspend fun runProgramManga(first: ChapterTranslationEntity, allowed: Set<String>?, execution: TaskExecution) = coroutineScope {
        val mangaId = first.mangaId
        val initialOrder = _orderRevision.value; val initialControls = controlRevision.value
        val prioritizeFirstPage = pendingOrderPriority.getAndSet(false)
        val tasks = ordered(dao.queueSnapshot()).filter {
            it.mangaId == mangaId && (allowed == null || it.chapterId in allowed) && runnable(it) &&
                !isChapterPaused(execution.taskId, it.chapterId) && it.configSnapshot == first.configSnapshot
        }
        val taskMap = tasks.associateBy { it.chapterId }
        val counts = ConcurrentHashMap<String, AtomicInteger>(); val totals = mutableMapOf<String, Int>(); val failures = ConcurrentHashMap<String, String>()
        val failedPages = ConcurrentHashMap.newKeySet<String>()
        val chapters = mutableListOf<WorkflowChapterInput>()
        val runJob = currentCoroutineContext().job
        val lifetime = launch(start = CoroutineStart.UNDISPATCHED) {
            dao.observeQueue().collect { latest ->
                val pending = latest.associateBy { it.chapterId }
                if (tasks.any { task -> pending[task.chapterId]?.let { item ->
                    item.queuedAt == task.queuedAt && item.configSnapshot == task.configSnapshot &&
                        item.state in setOf("PENDING", "RUNNING", "PAUSED")
                } == true }) return@collect
                val stored = dao.byManga(mangaId).associateBy { it.chapterId }
                if (tasks.any { task -> stored[task.chapterId]?.let { item ->
                    item.queuedAt != task.queuedAt || item.configSnapshot != task.configSnapshot || item.state == "CANCELLED"
                } != false }) {
                    android.util.Log.i("TranslationQueue", "Cancelled obsolete batch manga=$mangaId")
                    runJob.cancel(WorkflowPageStopped())
                }
            }
        }
        var host: AndroidWorkflowHost? = null
        var observer: Job? = null
        var runCurrentPage: String? = null
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
                try {
                    if (isChapterPaused(execution.taskId, task.chapterId)) continue
                    android.util.Log.i("TranslationQueue", "Opening chapter=${task.chapterId}")
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
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    val latest = dao.byChapter(task.chapterId).firstOrNull()
                    if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot && runnable(latest))
                        dao.setQueueState(task.chapterId, task.targetLanguage, "FAILED", failure.message, latest.translatedCount, now())
                    android.util.Log.w("TranslationQueue", "Skipping unavailable chapter=${task.chapterId}", failure)
                }
            }
            if (chapters.isEmpty()) return@coroutineScope
            val routes = program.localTranslationRoutes(source, target)
            routes.forEach { container.translationModels.installedCatalog().route(it.source, it.target, it.engine) }
            prepareMangaResources(routes, program.uses(WorkflowKind.SEG) && SegTextScope.fromValue(json.optString("segTextScope")) != SegTextScope.FREE_TEXT,
                if (program.uses(WorkflowKind.OCR)) setOf(source) else emptySet(), program.uses(WorkflowKind.SEG))
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
            val runHost = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, mangaName, chapters, settings, budget,
                restartConflictingPages = true,
                commitGate = { action -> publicationGate(executionsByManga[mangaId] ?: execution) { action() } }) {
                override suspend fun waitingForApi(call: WorkflowApiCall, frame: WorkflowFrame, waiting: Boolean) =
                    workflowApiWaiting(mangaId, call.profileId, waiting)
                override suspend fun awaitPrefetchPermission(frame: WorkflowFrame): Boolean =
                    combine(preprocessingAllowed, stopping, _paused, priorityActive) { allowed, stop, pause, high ->
                        (mangaId in allowed) to (stop || pause || high)
                    }.first { it.first || it.second }.let { it.first && !it.second }
                override suspend fun canPrefetch(chapterId: String, pageId: String): Boolean {
                    if (pageId in failedPages || isChapterPaused(execution.taskId, chapterId)) return false
                    val task = taskMap[chapterId] ?: return false
                    val latest = dao.byChapter(chapterId).firstOrNull() ?: return false
                    return !stopping.value && !_paused.value && !priority && runnable(latest) &&
                        latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot &&
                        !container.queueOrder.isPageExcluded(chapterId, task.queuedAt ?: 0, pageId)
                }
                override fun continueScheduling(frame: WorkflowFrame?): Boolean = wholeBatch.get() || !stopping.value || frame?.identity(WorkflowSystem.PAGE) == protectedPage && protectedPage != null
                override suspend fun chapters(): List<WorkflowValue.Record> {
                    val available = super.chapters()
                    if(!wholeBatch.get()) return available
                    val rank = ordered(dao.queueSnapshot()).mapIndexed { index, task -> task.chapterId to index }.toMap()
                    return available.sortedBy { rank[(it.fields.getValue("id") as WorkflowValue.Text).value] ?: Int.MAX_VALUE }
                }
                override suspend fun pages(chapter: WorkflowValue.Record) = super.pages(chapter).filterNot {
                    (it.fields.getValue("id") as WorkflowValue.Text).value in failedPages
                }
                override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                    if(!call.wholeManga) return super.api(call, frame)
                    return supervisorScope {
                        val request = async(start = CoroutineStart.LAZY) { super.api(call, frame) }
                        activeLock.withLock {
                            if(stopping.value || _paused.value || priority) throw WorkflowPageStopped()
                            wholeBatch.set(true); wholeWorkMangas += mangaId; wholeRequest = request
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
                            wholeBatch.set(true); wholeWorkMangas += mangaId; wholeRequest = request
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
                        if (pageId in failedPages || isChapterPaused(execution.taskId, chapterId) ||
                            (!wholeBatch.get() && stopping.value) || container.queueOrder.isPageExcluded(chapterId, task.queuedAt ?: 0, pageId)
                        ) return WorkflowPageAdmission.SKIP
                        active[pageId] = job
                        if (runCurrentPage == null && (primaryStarted || pageId == firstPage)) {
                            runCurrentPage = pageId; primaryStarted = true
                            updateFocus(mangaId, QueueCurrentStep(pageLabel(frame), PageTranslationProgress(PageTranslationStage.READING),
                                mangaId = mangaId, chapterName = chapters.firstOrNull { it.id == chapterId }?.name.orEmpty(), pageId = pageId))
                        }
                    }
                    val latest = dao.byChapter(chapterId).firstOrNull()
                    if (latest == null || !runnable(latest) || latest.queuedAt != task.queuedAt || latest.configSnapshot != task.configSnapshot) return WorkflowPageAdmission.SKIP
                    dao.setQueueState(chapterId, task.targetLanguage, "RUNNING", null, counts.getValue(chapterId).get(), now())
                    return super.admitPage(frame, job)
                }
                override suspend fun canPublish(frame: WorkflowFrame): Boolean {
                    if (!executionActive(execution)) return false
                    val task = taskMap.getValue(requireNotNull(frame.identity(WorkflowSystem.CHAPTER)))
                    val intent = container.taskScheduler.dao.get(execution.taskId) ?: return false
                    if(task.chapterId !in taskChapterIds(intent)) return false
                    val latest = dao.byChapter(task.chapterId).firstOrNull() ?: return false
                    return latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot &&
                        latest.state in setOf("PENDING", "RUNNING", "PAUSED") &&
                        !isChapterPaused(execution.taskId, task.chapterId) &&
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
                        if (runCurrentPage == pageId) {
                            runCurrentPage = active.keys.firstOrNull()
                            val next = runCurrentPage
                            updateFocus(mangaId, next?.let { id -> QueueCurrentStep(
                                chapters.flatMap { it.pages }.firstOrNull { it.pageId == id }?.displayName.orEmpty(),
                                PageTranslationProgress(PageTranslationStage.TRANSLATING), mangaId = mangaId,
                                chapterName = chapters.firstOrNull { chapter -> chapter.pages.any { it.pageId == id } }?.name.orEmpty(), pageId = id) })
                        }
                    }
                }
                override suspend fun pageFailed(frame: WorkflowFrame, failure: Throwable) {
                    val id = requireNotNull(frame.identity(WorkflowSystem.CHAPTER))
                    val pageId = requireNotNull(frame.identity(WorkflowSystem.PAGE))
                    failedPages += pageId
                    failures.putIfAbsent(id, "${pageLabel(frame)}：${failure.message ?: "工作流失败"}")
                    // Failed preparation has no later consumer. Release its lease while
                    // retaining the reloadable preprocessing cache for an explicit retry.
                    super.closePage(frame)
                    android.util.Log.w("TranslationQueue", "Skipping failed page chapter=$id page=$pageId", failure)
                }
                override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) {
                    val id = requireNotNull(frame.identity(WorkflowSystem.CHAPTER))
                    container.localPageTranslator.pageWriteMutex.withLock {
                    val task = taskMap.getValue(id)
                    val latest = dao.byChapter(id).firstOrNull() ?: return
                    if (latest.queuedAt != task.queuedAt || latest.configSnapshot != task.configSnapshot || latest.state !in setOf("PENDING", "RUNNING", "PAUSED")) return
                    val pages = chapters.first { it.id == id }.pages
                    val count = pages.count { page ->
                        val committed = published[page.pageId]
                        val current = container.localPageTranslator.artifacts.current(page.pageId)
                        committed != null && current != null && current.sourceSha256 == committed.sourceSha256 &&
                            current.matchesTask(source, target, requireNotNull(resultIdentity)) &&
                            !container.queueOrder.isPageExcluded(id, task.queuedAt ?: 0, page.pageId)
                    }
                    val failure = failures[id]
                    val paused = latest.state == "PAUSED" || isChapterPaused(execution.taskId, id)
                    val state = when {
                        !complete -> if (paused) "PAUSED" else "PENDING"
                        paused -> "PAUSED"
                        failure != null -> "FAILED"
                        count == totals[id] -> "DONE"
                        else -> "CANCELLED"
                    }
                    dao.setQueueState(id, task.targetLanguage, state, failure, count, now())
                    }
                }
                override fun step(node: WorkflowNode, frame: WorkflowFrame) {
                    val pageId = frame.identity(WorkflowSystem.PAGE)
                    if (pageId == runCurrentPage || pageId == null) {
                        val stage = when(node.kind) { WorkflowKind.SEG -> PageTranslationStage.SEGMENTING; WorkflowKind.OCR -> PageTranslationStage.OCR; else -> PageTranslationStage.TRANSLATING }
                        updateFocus(mangaId, QueueCurrentStep(pageLabel(frame), PageTranslationProgress(stage), node.label.ifBlank { WorkflowLabels.kind(node.kind) },
                            mangaId, chapters.firstOrNull { it.id == frame.identity(WorkflowSystem.CHAPTER) }?.name.orEmpty(), pageId))
                    }
                }
                override fun progress(frame: WorkflowFrame, progress: PageTranslationProgress) {
                    if (frame.identity(WorkflowSystem.PAGE) == runCurrentPage)
                        updateFocus(mangaId, QueueCurrentStep(pageLabel(frame), progress, if (progress.stage == PageTranslationStage.SAVING) "保存译文" else _currentSteps.value[mangaId]?.rowLabel,
                            mangaId, chapters.firstOrNull { it.id == frame.identity(WorkflowSystem.CHAPTER) }?.name.orEmpty(), runCurrentPage))
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
                            protectedPage = runCurrentPage; stopping.value = true
                            active.filterKeys { it != protectedPage }.values.forEach { it.cancel(WorkflowPageStopped()) }
                        }
                    }
                }
            }
            WorkflowRuntime(8, json.optInt("retries", 0).coerceIn(0, 5)).execute(program, runHost)
            android.util.Log.i("TranslationQueue", "Workflow finished manga=$mangaId")
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (failure is TaskRequeue) throw failure
            for (task in tasks) {
                val latest = dao.byChapter(task.chapterId).firstOrNull()
                if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot && runnable(latest))
                    dao.setQueueState(task.chapterId, task.targetLanguage, "FAILED", failure.message, counts[task.chapterId]?.get() ?: task.translatedCount, now())
            }
        } finally {
            withContext(NonCancellable) {
            wholeWorkMangas.remove(mangaId)
            lifetime.cancelAndJoin(); observer?.cancelAndJoin(); host?.close(); updateFocus(mangaId, null)
            for (task in tasks) {
                val latest = dao.byChapter(task.chapterId).firstOrNull()
                if (latest?.state == "RUNNING" && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot)
                    dao.setQueueState(task.chapterId, task.targetLanguage, "PENDING", null, counts[task.chapterId]?.get() ?: latest.translatedCount, now())
            }
            }
        }
    }
    private suspend fun runLegacyManga(mangaId: String, allowed: Set<String>?, execution: TaskExecution) = coroutineScope {
        val initialOrder = _orderRevision.value
        val initialControls = controlRevision.value
        val works = ArrayList<QueuePageWork>()
        val counts = mutableMapOf<String, Int>()
        val failures = mutableMapOf<String, String>()
        val pageTotals = mutableMapOf<String, Int>()
        for (task in ordered(dao.queueSnapshot()).filter {
            it.mangaId == mangaId && (allowed == null || it.chapterId in allowed) && runnable(it) &&
                !isChapterPaused(execution.taskId, it.chapterId)
        }) {
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
                val legacySettings = WorkflowRunSettings(source, target, json.optString("style"), render,
                    json.getDouble("segThreshold").toFloat(), segTextScope = SegTextScope.fromValue(json.optString("segTextScope").takeIf { it.isNotBlank() }),
                    textDetectionThreshold = json.optDouble("textDetectionThreshold", .45).toFloat(), freeTextMergeGapRatio = json.optDouble("freeTextMergeGapRatio", 1.2).toFloat())
                val identity = workflowResultIdentity(WorkflowTemplates.localMachine(), legacySettings,
                    container.translationModels.installedPacks.value.values.toList(), container.localVision.modelIdentities, container.visionExecutionPreferences.settings.value,
                    pipelineVersion = "legacy-local-page-v1")
                val bases = mutableMapOf<String, String?>()
                publicationGate(execution) { container.localPageTranslator.pageWriteMutex.withLock {
                    eligible.forEach { page ->
                        val saved = container.localPageTranslator.artifacts.current(page.pageId)
                        if(saved != null && !saved.matchesTask(source, target, identity)) {
                            container.localPageTranslator.artifacts.delete(page.pageId)
                            pageUpdates.emit(page.pageId)
                            bases[page.pageId] = null
                        } else bases[page.pageId] = saved?.revision
                    }
                } }
                counts[task.chapterId] = 0
                pageTotals[task.chapterId] = pages.size
                if (eligible.isEmpty()) dao.setQueueState(task.chapterId, task.targetLanguage, "CANCELLED", null, 0, now())
                works += eligible.map { QueuePageWork(task, pageSource, it, source, target, render,
                    json.getDouble("segThreshold").toFloat(), json.getInt("retries").coerceIn(0, 5),
                    SegTextScope.fromValue(json.optString("segTextScope").takeIf { it.isNotBlank() }), json.optDouble("textDetectionThreshold", .45).toFloat(),
                    json.optDouble("freeTextMergeGapRatio", 1.2).toFloat(), identity, bases[it.pageId]) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                val latest = dao.byChapter(task.chapterId).firstOrNull()
                if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot)
                    dao.setQueueState(task.chapterId, task.targetLanguage,
                        if (latest.state == "PAUSED" || isChapterPaused(execution.taskId, task.chapterId)) "PAUSED" else "FAILED",
                        failure.message, latest.translatedCount, now())
            }
        }
        if (works.isEmpty()) return@coroutineScope
        prepareMangaResources(works.map { LocalTranslationRoute(it.sourceLanguage, it.targetLanguage) }.toSet(), works.any { it.segTextScope != SegTextScope.FREE_TEXT }, needTextDetection = true)
        val consumed = mutableSetOf<String>()
        val pipeline = MangaPagePipeline(this, works, container.localPageTranslator, budget,
            container.localVision.segConcurrency, container.localVision.ocrConcurrency)
        try {
            while (!_paused.value && !priority) {
                // Rebuild admission order after controls/drag so old buffered pages cannot
                // consume the entire MB budget ahead of the newly selected first page.
                if (_orderRevision.value != initialOrder || controlRevision.value != initialControls) break
                val task = ordered(dao.queueSnapshot()).firstOrNull {
                    it.mangaId == mangaId && (allowed == null || it.chapterId in allowed) && runnable(it) &&
                        !isChapterPaused(execution.taskId, it.chapterId)
                } ?: break
                val work = works.firstOrNull { it.task.chapterId == task.chapterId && it.task.queuedAt == task.queuedAt &&
                    it.task.configSnapshot == task.configSnapshot && it.page.pageId !in consumed } ?: break
                dao.setQueueState(task.chapterId, task.targetLanguage, "RUNNING", null,
                    maxOf(counts[task.chapterId] ?: 0, task.translatedCount), now())
                updateFocus(mangaId, QueueCurrentStep(work.page.displayName, PageTranslationProgress(PageTranslationStage.READING), mangaId = mangaId, pageId = work.page.pageId))
                val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                    pipeline.progress(work.page.pageId).collect { updateFocus(mangaId, QueueCurrentStep(work.page.displayName, it, mangaId = mangaId, pageId = work.page.pageId)) }
                }
                try {
                    val recognized = pipeline.await(work.page.pageId)
                    // Legacy publication uses the same canonical generation lock as workflow publication.
                    publicationGate(executionsByManga[mangaId] ?: execution) { container.localPageTranslator.pageWriteMutex.withLock {
                        if(task.chapterId !in taskChapterIds(container.taskScheduler.dao.get(execution.taskId) ?: return@withLock)) return@withLock
                        if (_paused.value || priority || isChapterPaused(execution.taskId, task.chapterId) ||
                            container.queueOrder.isPageExcluded(task.chapterId, task.queuedAt ?: 0L, work.page.pageId) ||
                            dao.queueSnapshot().none { it.chapterId == task.chapterId && it.queuedAt == task.queuedAt && it.configSnapshot == task.configSnapshot && runnable(it) }) return@withLock
                        if (recognized != null) retry(work.retries) {
                            val identityNow = workflowResultIdentity(WorkflowTemplates.localMachine(), WorkflowRunSettings(work.sourceLanguage,
                                work.targetLanguage, JSONObject(requireNotNull(work.task.configSnapshot)).optString("style"), work.render, work.threshold,
                                segTextScope = work.segTextScope, textDetectionThreshold = work.textDetectionThreshold, freeTextMergeGapRatio = work.freeTextMergeGapRatio),
                                container.translationModels.installedPacks.value.values.toList(), container.localVision.modelIdentities, container.visionExecutionPreferences.settings.value,
                                pipelineVersion = "legacy-local-page-v1")
                            check(identityNow == work.resultIdentity) { "翻译配置在运行中变化，已有成果已保留；请重新确认任务" }
                            pipeline.setProgress(work.page.pageId, PageTranslationProgress(PageTranslationStage.TRANSLATING))
                            container.localPageTranslator.translateRecognized(recognized, work.sourceLanguage, work.targetLanguage, work.render,
                                expectedRevision = work.baseRevision, requireAbsent = work.baseRevision == null, resultIdentity = work.resultIdentity) {
                                pipeline.setProgress(work.page.pageId, it)
                            }
                        }
                        else {
                            val current = container.localPageTranslator.artifacts.current(work.page.pageId)
                            if(current == null || !current.matchesTask(work.sourceLanguage, work.targetLanguage, requireNotNull(work.resultIdentity)))
                                throw com.lmreader.ui.reader.translation.PageTranslationRevisionConflict()
                        }
                        consumed += work.page.pageId
                        val completed = (counts[task.chapterId] ?: 0) + 1; counts[task.chapterId] = completed
                        if (recognized != null) pageUpdates.emit(work.page.pageId)
                        val exhausted = works.filter { it.task.chapterId == task.chapterId }.all { it.page.pageId in consumed }
                        val latest = dao.byChapter(task.chapterId).firstOrNull()
                        if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot)
                            dao.setQueueState(task.chapterId, task.targetLanguage,
                            if (latest.state == "CANCELLED") "CANCELLED"
                            else if (latest.state == "PAUSED" || isChapterPaused(execution.taskId, task.chapterId)) "PAUSED"
                            else if (exhausted)
                                if (failures[task.chapterId] != null) "FAILED" else if (completed == pageTotals[task.chapterId]) "DONE" else "CANCELLED"
                            else "PENDING",
                            failures[task.chapterId], completed, now())
                    } }
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    if(generateSequence<Throwable>(failure) { it.cause }.any { it is com.lmreader.ui.reader.translation.PageTranslationRevisionConflict }) {
                        publicationGate(execution) { container.localPageTranslator.pageWriteMutex.withLock {
                            container.localPageTranslator.artifacts.delete(work.page.pageId)
                            pageUpdates.emit(work.page.pageId)
                        } }
                        throw TaskRequeue(resultConflict = true)
                    }
                    consumed += work.page.pageId
                    failures.putIfAbsent(task.chapterId, "${work.page.displayName}：${failure.message ?: "翻译失败"}")
                    val exhausted = works.filter { it.task.chapterId == task.chapterId }.all { it.page.pageId in consumed }
                    val latest = dao.queueSnapshot().firstOrNull { it.chapterId == task.chapterId }
                    if (latest != null && latest.queuedAt == task.queuedAt && latest.configSnapshot == task.configSnapshot &&
                        latest.state in setOf("PENDING", "RUNNING", "PAUSED")) dao.setQueueState(task.chapterId, task.targetLanguage,
                        if (latest.state == "PAUSED" || isChapterPaused(execution.taskId, task.chapterId)) "PAUSED"
                        else if (exhausted) "FAILED" else "PENDING",
                        failures[task.chapterId], counts[task.chapterId] ?: 0, now())
                    android.util.Log.w("TranslationQueue", "Skipping failed page chapter=${task.chapterId} page=${work.page.pageId}", failure)
                } finally {
                    observer.cancelAndJoin(); pipeline.consume(work.page.pageId)
                    updateFocus(mangaId, null)
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
    // The canonical ledger is small; read it on command boundaries, never per page.
    private suspend fun chapterTasks(): List<TaskRecord> = container.taskScheduler.dao.all().filter { it.type == "CHAPTER" }
    private suspend fun chapterTasksForManga(mangaId: String) = chapterTasks().filter { chapterTaskMangaId(it) == mangaId }
    private suspend fun chapterTasksForChapter(mangaId: String, chapterId: String) = chapterTasks().filter {
        chapterTaskMangaId(it) == mangaId && chapterId in taskChapterIds(it)
    }.sortedBy { it.state in TaskScheduler.TERMINAL }
    private fun chapterTaskMangaId(row: TaskRecord): String? = if (row.type != "CHAPTER") null else
        runCatching { JSONObject(row.payload).optString("mangaId").takeIf { it.isNotBlank() } }.getOrNull()
    private fun taskChapterIds(row: TaskRecord): Set<String> =
        runCatching { jsonStrings(JSONObject(row.payload).optJSONArray("chapterIds")).toSet() }.getOrDefault(emptySet())
    private fun taskPausedChapterIds(row: TaskRecord): Set<String> =
        runCatching { jsonStrings(JSONObject(row.payload).optJSONArray("_pausedChapterIds")).toSet() }.getOrDefault(emptySet())
    private fun isChapterPaused(taskId: String, chapterId: String) = pausedChapterIntents[taskId]?.contains(chapterId) == true

    private suspend fun uniqueGoalKey(base: String): String =
        if (container.taskScheduler.dao.active(base) == null) base else "$base:${UUID.randomUUID()}"

    private suspend fun mutateChapterTask(taskId: String, block: (JSONObject) -> Unit): TaskRecord? =
        container.taskScheduler.lock(taskId).withLock {
            container.workDatabase.withTransaction {
                val row = container.taskScheduler.dao.get(taskId)
                if (row == null) null else {
                    val json = JSONObject(row.payload)
                    block(json)
                    val next = row.copy(payload = json.toString())
                    if (next != row) container.taskScheduler.dao.update(next)
                    next
                }
            }
        }

    private fun jsonStrings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
    private fun stringSet(array: JSONArray): Set<String> = jsonStrings(array).toSet()

    private fun updatePausedChapterIds(json: JSONObject, paused: Collection<String>) {
        val chapters = jsonStrings(json.optJSONArray("chapterIds"))
        val ordered = chapters.filter { it in paused } + paused.filterNot { it in chapters }
        json.put("_pausedChapterIds", JSONArray(ordered.distinct()))
    }
    private fun addPausedChapter(json: JSONObject, chapterId: String) =
        updatePausedChapterIds(json, jsonStrings(json.optJSONArray("_pausedChapterIds")) + chapterId)
    private fun removePausedChapter(json: JSONObject, chapterId: String) =
        updatePausedChapterIds(json, jsonStrings(json.optJSONArray("_pausedChapterIds")).filterNot { it == chapterId })
    private fun addPausedChapters(json: JSONObject, ids: Collection<String>) =
        updatePausedChapterIds(json, jsonStrings(json.optJSONArray("_pausedChapterIds")) + ids)
    private fun removePausedChapters(json: JSONObject, ids: Collection<String>) =
        updatePausedChapterIds(json, jsonStrings(json.optJSONArray("_pausedChapterIds")).filterNot { it in ids })
    private fun removeChapter(json: JSONObject, chapterId: String) = removeChapters(json, setOf(chapterId))
    private fun removeChapters(json: JSONObject, ids: Collection<String>) {
        val removed = ids.toSet()
        val chapters = jsonStrings(json.optJSONArray("chapterIds")).filterNot { it in removed }
        json.put("chapterIds", JSONArray(chapters))
        updatePausedChapterIds(json, jsonStrings(json.optJSONArray("_pausedChapterIds")).filterNot { it in removed })
    }

    private suspend fun executionActive(execution: TaskExecution): Boolean =
        try { execution.checkActive(); true } catch (_: IllegalStateException) { false }

    /** Canonical publication gate; a stale generation is a stop, not a business failure. */
    private suspend fun <T> publicationGate(execution: TaskExecution, action: suspend () -> T): T =
        try { execution.publish(action) }
        catch (failure: IllegalStateException) {
            currentCoroutineContext().ensureActive()
            val row = container.taskScheduler.dao.get(execution.taskId)
            if (row?.generation != execution.initial.generation || row.state != "RUNNING")
                throw CancellationException("任务已暂停或取消")
            throw failure
        }

    /** Legacy manga-only moves retain video slots in the shared queue. */
    private fun syncCanonicalMangaOrder() {
        scope.launch {
            container.taskScheduler.ready.await()
            val mangaOrder = ordered(_items.value).map { "manga:${it.mangaId}" }.distinct()
            val present = container.taskScheduler.dao.all().filter { it.parentTaskId == null && it.priority == 0 &&
                it.inTranslationQueue() }.map { it.translationGroupKey() }.distinct()
            val shared = container.queueOrder.translationGroupOrder(present)
            val moved = mangaOrder.filter { it in shared }.iterator()
            container.taskScheduler.orderTranslationGroups(shared.map { if(it in mangaOrder && moved.hasNext()) moved.next() else it })
        }
    }

    private fun runnable(item: ChapterTranslationEntity) = item.state == "PENDING" || item.state == "RUNNING"
    private fun remaining(item: ChapterTranslationEntity) = item.state in setOf("PENDING", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED")
    private fun now() = System.currentTimeMillis()
    private companion object {
        const val CHAPTER_QUEUE = "CHAPTER_QUEUE"
        const val CHAPTER_PAUSED = "CHAPTER_PAUSED"
    }
}

fun ChapterTranslationEntity.key(): String = chapterId
