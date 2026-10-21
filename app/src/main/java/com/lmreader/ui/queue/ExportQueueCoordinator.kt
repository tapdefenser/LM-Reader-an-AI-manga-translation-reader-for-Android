package com.lmreader.ui.queue

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.model.*
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.core.vision.VisionImageDecoder
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskExecution
import com.lmreader.tasks.TaskScheduler
import com.lmreader.tasks.TaskWaiting
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.decodePageAnalysisImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class ExportTask(
    val id: String,
    val mangaId: String,
    val chapterId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val sourceTreeUri: String,
    val destinationTreeUri: String,
    val singleChapter: Boolean,
    val format: ExportFormat = ExportFormat.CBZ,
    val state: String = "PENDING",
    val completedPages: Int = 0,
    val totalPages: Int = 0,
    val outputUri: String? = null,
    val failure: String? = null,
    val temporaryUri: String? = null,
    val publicationName: String? = null,
    val publicationPhase: String? = null,
    val configurationSnapshot: String? = null,
)

/**
 * Durable legacy snapshots and a publication journal make retries and process death recoverable.
 * After a legacy row is adopted, the canonical TaskStore is the state authority; this file remains
 * the business snapshot/journal and receives a one-way projection of the canonical state.
 */
class ExportQueueCoordinator(private val container: AppContainer) {
    private val context = container.application
    private val scope = container.backgroundScope
    private val stateFile = File(context.filesDir, "export-queue.json")
    private val _storageFailure = MutableStateFlow<String?>(null)
    val storageFailure = _storageFailure.asStateFlow()
    private val staging = File(context.noBackupFilesDir, "export-staging").apply { mkdirs() }
    private val _tasks = MutableStateFlow(load())
    val tasks = _tasks.asStateFlow()
    private val _paused = MutableStateFlow(context.getSharedPreferences("export-settings", 0).getBoolean("queue-paused", false))
    val paused = _paused.asStateFlow()
    @Volatile private var runner: Job? = null
    private val executionJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val executionGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val unifiedExecutions = java.util.concurrent.ConcurrentHashMap<String, TaskExecution>()
    private val controlJobs = java.util.concurrent.ConcurrentHashMap.newKeySet<Job>()
    private var controlTail: Job? = null
    private val adoptionMutex = Mutex()
    private val projectionMutex = Mutex()
    /** Cache used by the synchronous public controls; the DB remains the authority. */
    @Volatile private var canonicalRows: List<TaskRecord> = emptyList()
    @Volatile private var sawCategoryPause = false

    @Volatile private var recovering = true
    private val recovery = scope.launch {
        try { container.startupReady.await() } catch (_: Exception) { recovering = false; return@launch }
        var readyToRun = false
        try {
            synchronized(this@ExportQueueCoordinator) {
                _paused.value = context.getSharedPreferences("export-settings", 0).getBoolean("queue-paused", false)
            }
            if (!update(_tasks.value)) return@launch
            for (task in _tasks.value.toList()) {
                runCatching { recoverPublication(task) }.onFailure { error ->
                    change(task.id) { it.copy(state = if (it.state == "DONE") "DONE" else "INTERRUPTED",
                        failure = "临时文件恢复失败，请重新授权目标目录后重试：${error.message}") }
                }
            }
            try { container.taskScheduler.ready.await() } catch (_: Exception) { return@launch }
            // Register every unfinished legacy row, including terminal FAILED history, before starting to submit.
            ensureLegacyAdopted(categoryPaused = _paused.value)
            // A legacy global pause must also hold canonical rows created by an earlier process.
            if (_paused.value) pauseCategoryCanonical()
            projectAll()
            // Cleanup after adoption so a DONE legacy row is only removed when canonical history is safe.
            for (task in _tasks.value.toList()) cleanCompleted(task.id)
            readyToRun = true
        } catch (error: Exception) {
            android.util.Log.e("ExportQueue", "导出队列恢复失败", error)
        } finally {
            recovering = false
            if (readyToRun) start()
        }
    }

    /** Projects canonical changes into the legacy journal. It never resumes or submits work itself. */
    private val projection = scope.launch {
        try { container.startupReady.await() } catch (_: Exception) { return@launch }
        container.taskScheduler.ready.await()
        container.taskScheduler.tasks.collect {
            if (recovering) return@collect
            // Re-read under the projection lock: a collected snapshot can be older than a
            // control command / claim that completed while this collector was suspended.
            projectAll()
            canonicalRows.filter { it.state in TaskScheduler.TERMINAL }.forEach { row ->
                legacyId(row)?.let { cleanCompleted(it) }
            }
        }
    }

    suspend fun enqueue(mangaId: String, chapterIds: List<String>): Int {
        check(_storageFailure.value == null) { _storageFailure.value!! }
        val target = container.mangaRepository.getBackfillTarget(mangaId) ?: error("漫画不存在")
        val destination = container.exportSettings.destination(target.manga.layoutMode)
            ?: error("请先在导出设置中选择${if (target.manga.layoutMode.name == "SINGLE_CHAPTER") "单章节" else "多章节"}路径")
        check(context.contentResolver.persistedUriPermissions.any { it.uri == destination && it.isWritePermission }) {
            "导出目录授权已失效，请在导出设置中重新选择"
        }
        val chapters = target.chapters.filter { it.chapterId in chapterIds }
        check(chapters.isNotEmpty()) { "没有可导出的章节" }
        val format = container.exportSettings.format.value
        val options = container.mangaRepository.translationSettings(mangaId)
        fun language(value: String?) = value?.let { raw ->
            com.lmreader.ui.translation.matchEngineLanguage(raw, container.translationModels.installedCatalog().languages + com.lmreader.ui.reader.translation.LocalPageTranslator.ocrSources)?.tag
                ?: runCatching { LocalTranslationLanguage.fromTag(raw).tag }.getOrNull()
        }
        val configuration = ExportConfiguration(options.effectiveBubbleRender(container.bubbleRenderPreferences.settings.first()),
            language(options.sourceLanguage.takeUnless { options.autoDetectSource }), language(options.targetLanguage)).encode()
        val fresh = chapters.map { chapter ->
            ExportTask(UUID.randomUUID().toString(), mangaId, chapter.chapterId,
                target.manga.displayName, chapter.title, target.sourceTreeUri, destination.toString(),
                target.manga.layoutMode.name == "SINGLE_CHAPTER", format, configurationSnapshot = configuration)
        }
        var added: List<ExportTask> = emptyList()
        synchronized(this) {
            val existing = _tasks.value.filter { it.state in listOf("PENDING", "RUNNING", "PAUSED") }
                .map { it.chapterId }.toSet()
            added = fresh.filterNot { it.chapterId in existing }
            val startNewBatch = added.isNotEmpty() && _tasks.value.isEmpty()
            check(update(_tasks.value + added.map { if (_paused.value && !startNewBatch) it.copy(state = "PAUSED") else it })) { _storageFailure.value!! }
            if (startNewBatch) {
                container.taskService.allowRetry()
                context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", false).commit()
                _paused.value = false
            }
        }
        if (added.isNotEmpty()) {
            runCatching { ensureLegacyAdopted(categoryPaused = _paused.value) }
                .onFailure { android.util.Log.e("ExportQueue", "无法登记导出任务", it) }
            start()
        }
        return added.size
    }

    @Synchronized fun pauseAll() {
        _paused.value = true
        context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", true).commit()
        update(_tasks.value.map { if (it.state in listOf("PENDING", "RUNNING")) it.copy(state = "PAUSED") else it })
        launchCommand {
            recovery.join()
            runner?.join()
            ensureLegacyAdopted(categoryPaused = true)
            pauseCategoryCanonical()
            projectAll()
        }
    }

    @Synchronized fun resumeAll() {
        if(_storageFailure.value != null) return
        val wasCategoryPaused = _paused.value
        _paused.value = false
        context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", false).commit()
        update(_tasks.value.map { task ->
            if (task.state == "PAUSED" && resumableByAllCategory(task.id)) task.copy(state = "PENDING", failure = null) else task
        })
        launchCommand {
            recovery.join()
            ensureLegacyAdopted(categoryPaused = wasCategoryPaused)
            val rows = refreshCanonical()
            for (row in rows) {
                if (row.state == "PAUSED" && row.pauseReason != null && row.pauseReason in CATEGORY_PAUSE_REASONS && !row.remoteOutcomeUnknown) {
                    container.taskScheduler.resume(row.taskId, onlyPauseReason = row.pauseReason)
                }
            }
            projectAll()
            start()
        }
    }

    @Synchronized fun pause(id: String) {
        change(id) { if (it.state in listOf("PENDING", "RUNNING")) it.copy(state = "PAUSED") else it }
        launchCommand {
            recovery.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            val row = cachedCanonical(id)
            if (row != null && row.state in ACTIVE_STATES && !row.userPaused) {
                container.taskScheduler.pause(row.taskId, pauseReason = "USER")
            }
            projectAll()
        }
    }

    @Synchronized fun resume(id: String) {
        if(_storageFailure.value != null) return
        val known = cachedCanonical(id)
        if (known?.pauseReason != "GLOBAL") {
            change(id) { if (it.state == "PAUSED") it.copy(state = "PENDING", failure = null) else it }
        }
        launchCommand {
            recovery.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            val row = cachedCanonical(id)
            if (row != null && !row.remoteOutcomeUnknown && row.pauseReason != "GLOBAL" &&
                row.state in setOf("PAUSED", "WAITING", "INTERRUPTED")) {
                container.taskScheduler.resume(row.taskId, onlyPauseReason = row.pauseReason)
            }
            projectAll()
            start()
        }
    }

    @Synchronized fun retry(id: String) {
        if(_storageFailure.value != null) return
        val known = cachedCanonical(id)
        if (known?.remoteOutcomeUnknown == true) return
        if (known != null) {
            change(id) { task ->
                if (task.state in listOf("FAILED", "INTERRUPTED")) {
                    val rebound = if (task.publicationPhase == null) container.exportSettings.destination(
                        if (task.singleChapter) LayoutMode.SINGLE_CHAPTER else LayoutMode.MULTI_CHAPTER)?.toString() else null
                    task.copy(state = "PENDING", failure = null, destinationTreeUri = rebound ?: task.destinationTreeUri)
                } else task
            }
        }
        launchCommand {
            recovery.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            val row = cachedCanonical(id)
            if (row != null && !row.remoteOutcomeUnknown &&
                (row.state in setOf("FAILED", "INTERRUPTED", "WAITING") || row.state == "PAUSED" && row.pauseReason == "USER")) {
                container.taskScheduler.resume(row.taskId, retryFailed = true)
            }
            projectAll()
            start()
        }
    }

    @Synchronized fun cancel(id: String) {
        val known = cachedCanonical(id)
        if (known == null || known.state !in TaskScheduler.TERMINAL) {
            change(id) { if (it.state != "DONE") it.copy(state = "CANCELLED") else it }
        }
        launchCommand {
            recovery.join()
            runner?.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            val row = cachedCanonical(id)
            if (row != null && row.state !in TaskScheduler.TERMINAL) container.taskScheduler.pause(row.taskId, cancel = true)
            change(id) { if (it.state != "DONE" && (row == null || row.state !in TaskScheduler.TERMINAL)) it.copy(state = "CANCELLED") else it }
            projectAll()
            cleanCancelledNow(listOf(id))
        }
    }

    @Synchronized fun pauseManga(mangaId: String) {
        update(_tasks.value.map {
            if (it.mangaId == mangaId && it.state in listOf("PENDING", "RUNNING")) it.copy(state = "PAUSED") else it
        })
        launchCommand {
            recovery.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            pauseCategoryCanonical(mangaId)
            projectAll()
        }
    }

    @Synchronized fun resumeManga(mangaId: String) {
        update(_tasks.value.map { task ->
            if (task.mangaId == mangaId && task.state == "PAUSED" && resumableByCategory(task.id, mangaId)) {
                task.copy(state = "PENDING", failure = null)
            } else task
        })
        launchCommand {
            recovery.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            val rows = refreshCanonical()
            for (row in rows) {
                val id = legacyId(row) ?: continue
                val task = live(id) ?: continue
                if (task.mangaId == mangaId && row.state == "PAUSED" && row.pauseReason == PAUSE_EXPORT_QUEUE && !row.remoteOutcomeUnknown) {
                    container.taskScheduler.resume(row.taskId, onlyPauseReason = PAUSE_EXPORT_QUEUE)
                }
            }
            projectAll()
            start()
        }
    }

    @Synchronized fun cancelManga(mangaId: String) {
        val cancelIds = mutableListOf<String>()
        update(_tasks.value.map { task ->
            if (task.mangaId == mangaId && task.state !in listOf("DONE", "CANCELLED") && cancellable(task.id)) {
                cancelIds += task.id
                task.copy(state = "CANCELLED")
            } else task
        })
        launchCommand {
            recovery.join()
            runner?.join()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            val rows = refreshCanonical()
            for (row in rows) {
                val id = legacyId(row) ?: continue
                if (id !in cancelIds) continue
                if (row.state !in TaskScheduler.TERMINAL) container.taskScheduler.pause(row.taskId, cancel = true)
            }
            projectAll()
            cleanCancelledNow(cancelIds)
        }
    }

    @Synchronized fun moveManga(mangaId: String, direction: Int) {
        val groups = _tasks.value.groupBy { it.mangaId }.toMutableMap()
        val ids = groups.keys.toMutableList()
        val from = ids.indexOf(mangaId)
        val to = from + direction
        if (from >= 0 && to in ids.indices) {
            ids.removeAt(from); ids.add(to, mangaId)
            update(ids.flatMap { groups.getValue(it) })
            syncCanonicalOrder()
        }
    }

    @Synchronized fun move(id: String, direction: Int) {
        val rows = _tasks.value.toMutableList()
        val from = rows.indexOfFirst { it.id == id }
        val to = from + direction
        if (from >= 0 && to in rows.indices && rows[from].state != "RUNNING" && rows[from].mangaId == rows[to].mangaId) {
            val row = rows.removeAt(from); rows.add(to, row); update(rows)
            syncCanonicalOrder()
        }
    }

    @Synchronized fun clearFinished() {
        launchCommand {
            recovery.join()
            container.taskScheduler.ready.await()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            refreshCanonical()
            val candidates = synchronized(this@ExportQueueCoordinator) {
                _tasks.value.filter { it.state in listOf("DONE", "CANCELLED") && it.publicationPhase == null }.map { it.id }
            }
            for (id in candidates) {
                awaitExecution(id)
                if (executionJobs[id]?.isActive == true) continue
                val task = live(id) ?: continue
                if (task.state !in listOf("DONE", "CANCELLED") || task.publicationPhase != null) continue
                val row = cachedCanonical(id)
                if (row != null && row.state !in TaskScheduler.TERMINAL) continue
                if (task.state == "DONE" && row != null && row.state != "SUCCEEDED" && row.state != "CANCELLED") continue
                if (!deleteOwnedRoot(id)) continue
                synchronized(this@ExportQueueCoordinator) {
                    update(_tasks.value.filterNot { it.id == id && it.state in listOf("DONE", "CANCELLED") && it.publicationPhase == null })
                }
            }
        }
    }

    suspend fun pauseAndAwait() {
        pauseAll()
        recovery.join()
        runner?.join()
        container.taskScheduler.ready.await()
        awaitControlJobs()
        // Legacy breakpoint commands are async; force any still-runnable export row to a safe pause.
        pauseCategoryCanonical()
        awaitControlsAndExecutions()
        projectAll()
    }

    suspend fun cleanTemporaryFiles() = withContext(Dispatchers.IO) {
        recovery.join()
        pauseAndAwait()
        val ids = synchronized(this@ExportQueueCoordinator) { _tasks.value.map { it.id } }
        for (id in ids) {
            awaitExecution(id)
            val task = live(id) ?: continue
            try { recoverPublication(task) } catch (error: Exception) {
                change(id) { it.copy(failure = "临时文件未清理：${error.message}") }
                continue
            }
            // Only DONE/CANCELLED snapshots are removed; paused/failed/interrupted snapshots stay retryable.
            cleanCompleted(id)
        }
        projectAll()
    }

    @Synchronized private fun change(id: String, block: (ExportTask) -> ExportTask): Boolean =
        update(_tasks.value.map { if (it.id == id) block(it) else it })

    /**
     * Keep the receipt until publication recovery and owned staging cleanup are complete. Canonical
     * terminal success is never removed here, so clearing the legacy row cannot lose the history.
     */
    private fun cleanCompleted(id: String, fromProcess: Boolean = false) {
        val task = live(id) ?: return
        if (task.state !in listOf("DONE", "CANCELLED") || task.publicationPhase != null) return
        val canonical = cachedCanonical(id)
        val executing = executionJobs[id]?.isActive == true
        // Direct process() calls are the test/legacy seam and are not driven by a canonical execution.
        val directProcess = fromProcess && unifiedExecutions[id] == null
        if (canonical == null) {
            if (executing) return
        } else if (canonical.state != "SUCCEEDED" && canonical.state != "CANCELLED") {
            // While canonical execution/publication is still live, deleting staging would race late publication.
            if (executing || !directProcess) return
        }
        if (!deleteOwnedRoot(id)) return
        if (task.state == "DONE" && (directProcess || canonical == null || canonical.state == "SUCCEEDED" || canonical.state == "CANCELLED")) {
            synchronized(this) {
                update(_tasks.value.filterNot { it.id == id && it.state == "DONE" && it.publicationPhase == null })
            }
        }
    }

    private fun requireChange(id: String, block: (ExportTask) -> ExportTask) {
        check(change(id, block)) { _storageFailure.value ?: "导出队列无法保存" }
    }
    @Synchronized private fun update(rows: List<ExportTask>): Boolean {
        if (_storageFailure.value != null) return false
        return try { save(rows); _tasks.value = rows; true }
        catch (error: Exception) {
            _storageFailure.value = "导出队列无法保存，请释放应用存储空间后重新打开：${error.message}"
            _paused.value = true
            false
        }
    }

    init {
        container.taskScheduler.register("EXPORT") { execution ->
            recovery.join()
            val payload = runCatching { JSONObject(execution.payload) }.getOrNull()
            val id = payload?.optString("legacyId")?.takeIf { it.isNotBlank() }
                ?: throw TaskWaiting("ACCESS", "导出任务缺少业务记录，无法继续；请重新创建导出任务")
            val task = live(id) ?: throw TaskWaiting("ACCESS", "导出记录不存在或已被清理；请重新创建导出任务")
            if (task.state == "DONE") return@register
            val executingJob = currentCoroutineContext().job
            executionJobs[id] = executingJob
            executionGenerations[id] = execution.initial.generation
            executingJob.invokeOnCompletion {
                executionJobs.remove(id, executingJob)
                executionGenerations.remove(id, execution.initial.generation)
            }
            unifiedExecutions[id] = execution
            try {
                execution.checkActive()
                synchronized(this@ExportQueueCoordinator) {
                    // Register the claim before exposing PENDING to the projection observer.
                    change(id) { it.copy(state = "PENDING", failure = null) }
                }
                ResourceGovernor.shared.use("io") { process(requireNotNull(live(id))) }
            } finally { unifiedExecutions.remove(id, execution) }
            execution.checkActive()
            val finished = live(id) ?: throw TaskWaiting("ACCESS", "导出记录不存在或已被清理；请重新创建导出任务")
            when (finished.state) {
                "DONE" -> execution.progress(finished.completedPages.toLong(), finished.totalPages.toLong(), "已完成")
                "FAILED" -> error(finished.failure ?: "导出失败")
                else -> throw TaskWaiting("PAUSED", finished.failure ?: "导出已暂停")
            }
        }
    }

    @Synchronized fun start() {
        if (recovering || _storageFailure.value != null || !container.taskService.canStart() || _paused.value || runner?.isCompleted == false) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            container.taskScheduler.ready.await()
            ensureLegacyAdopted(categoryPaused = false)
            val pending = synchronized(this@ExportQueueCoordinator) { _tasks.value.filter { it.state == "PENDING" }.map { it.id } }
            val canonical = refreshCanonical()
            for (id in pending) {
                if (canonical.any { legacyId(it) == id }) continue // Reuse target/history; never duplicate a failed row.
                if (container.taskScheduler.dao.active(exportKey(id)) != null) continue
                val stillPending = synchronized(this@ExportQueueCoordinator) {
                    !_paused.value && live(id)?.state == "PENDING"
                }
                if (!stillPending) continue
                val task = live(id) ?: continue
                container.taskScheduler.submit("EXPORT", "${task.mangaTitle} · ${task.chapterTitle}", "legacy-export", exportKey(id),
                    JSONObject().put("legacyId", task.id).toString())
            }
            projectAll()
        }
        runner = job
        job.invokeOnCompletion { synchronized(this) { if (runner === job) runner = null } }
        job.start()
    }

    private fun live(id: String) = _tasks.value.firstOrNull { it.id == id }
    private fun shouldContinue(id: String) = _storageFailure.value == null && live(id)?.state == "RUNNING"

    internal suspend fun process(task: ExportTask) {
        synchronized(this) {
            val state = live(task.id)?.state
            if (state != "PENDING" && !(state == "RUNNING" && unifiedExecutions[task.id] != null)) return
            if (!change(task.id) { it.copy(state = "RUNNING", failure = null) }) return
        }
        val root = ExportFiles.ownedRoot(staging, task.id)
        try {
            recoverPublication(live(task.id)!!)
            if (live(task.id)?.state == "DONE" || !shouldContinue(task.id)) return
            val snapshot = prepareSnapshot(task, root)
            val originals = File(root, "originals")
            val rendered = File(root, "rendered").apply { mkdirs() }
            val artifacts = ReaderPageArtifactStore(File(root, "translations"))
            val pages = snapshot.getJSONArray("pages")
            requireChange(task.id) { it.copy(totalPages = pages.length()) }
            for (index in 0 until pages.length()) {
                checkRunning(task)
                val page = pages.getJSONObject(index)
                val original = File(originals, index.toString())
                check(ExportFiles.hash(original) == page.getString("hash")) { "原图快照损坏，请取消任务并重新入队" }
                val output = File(rendered, page.getString("output"))
                if (!ExportFiles.intact(output)) {
                    val saved = artifacts.load(page.getString("id"), page.getString("hash"))
                    check(!page.getBoolean("translated") || saved != null) { "译文快照损坏" }
                    val configured = task.configurationSnapshot?.let(ExportConfiguration::decode)
                    ExportFiles.commit(output) { writePage(it, original, task.format, saved?.let { data -> configured?.let { data.copy(renderSettings = it.render) } ?: data }, page.getString("extension")) }
                    ExportFiles.receipt(output)
                }
                requireChange(task.id) { it.copy(completedPages = index + 1) }
                unifiedExecutions[task.id]?.progress((index + 1).toLong(), pages.length().toLong(), "导出 ${index + 1}/${pages.length()}")
                check(rendered.listFiles().orEmpty().sumOf { it.length() } <= 2_000_000_000L) { "章节导出超过 2 GB 上限" }
            }
            checkRunning(task)
            val output = if (task.format == ExportFormat.CBZ) File(root, "chapter.cbz").also { archive ->
                ExportFiles.commit(archive) { stream ->
                    // ZipOutputStream must finish before the owning stream is synced.
                    val shield = object : java.io.FilterOutputStream(stream) { override fun close() { flush() } }
                    ZipOutputStream(shield.buffered()).use { zip ->
                    for (index in 0 until pages.length()) {
                        checkRunning(task)
                        val filename = pages.getJSONObject(index).getString("output")
                        zip.putNextEntry(ZipEntry(filename))
                        File(rendered, filename).inputStream().use { copyLimited(it, zip, task) }
                        zip.closeEntry()
                    }
                    zip.finish(); zip.flush()
                    }
                }
                check(archive.length() <= 2_000_000_000L) { "章节导出文件超过 2 GB 上限" }
            } else rendered
            publish(task, output)
        } catch (waiting: TaskWaiting) {
            change(task.id) { if(it.state == "RUNNING") it.copy(state = "INTERRUPTED", failure = waiting.message) else it }
            throw waiting
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                change(task.id) { if (it.state == "RUNNING") it.copy(state = "INTERRUPTED", failure = "运行被中断，已保留快照，可手动重试") else it }
            }
            throw cancelled
        } catch (access: SecurityException) {
            change(task.id) { if(it.state == "RUNNING") it.copy(state = "INTERRUPTED", failure = access.message) else it }
            throw TaskWaiting("ACCESS", access.message ?: "导出文件或目录授权已失效，请重新授权")
        } catch (failure: Exception) {
            change(task.id) { if (it.state == "RUNNING") it.copy(state = "FAILED", failure = failure.message ?: "导出失败") else it }
        } finally {
            runCatching { recoverPublication(live(task.id) ?: task) }.onFailure { error ->
                change(task.id) { it.copy(failure = "临时文件未清理，请恢复目标目录授权后重试：${error.message}") }
            }
            cleanCompleted(task.id, fromProcess = true)
        }
    }

    private suspend fun prepareSnapshot(task: ExportTask, root: File): JSONObject {
        val marker = File(root, "snapshot.json")
        if (marker.isFile) return JSONObject(marker.readText()).also {
            require(it.getString("task") == task.id)
            check(it.optString("configurationSnapshot").takeIf { value -> value.isNotBlank() && value != "null" } == task.configurationSnapshot) { "导出配置与已保存快照不一致，请取消后重新创建任务" }
        }
        // An unfinished capture has never been used to publish output.
        root.deleteRecursively(); check(root.mkdirs()) { "无法创建导出快照" }
        val originals = File(root, "originals").apply { mkdirs() }
        val translations = File(root, "translations").apply { mkdirs() }
        val chapter = container.mangaRepository.getChapters(task.mangaId).firstOrNull { it.chapterId == task.chapterId }
            ?: error("章节已不存在")
        val opened = container.pageSourceFactory.open(task.sourceTreeUri, chapter)
        val source = (opened as? PageSourceOpenResult.Ready)?.source
            ?: throw TaskWaiting("ACCESS", (opened as PageSourceOpenResult.Unsupported).reason)
        val pages = source.pages(); require(pages.isNotEmpty()) { "章节没有可导出的页面" }
        val captured = JSONArray()
        val capturedTranslations = container.localPageTranslator.pageWriteMutex.let { mutex ->
            mutex.lock()
            try { container.localPageTranslator.artifacts.snapshot(pages.map { it.pageId }, translations) }
            finally { mutex.unlock() }
        }
            var bytes = 0L
            for ((index, page) in pages.withIndex()) {
                checkRunning(task)
                val original = File(originals, index.toString())
                source.open(page).use { input -> ExportFiles.commit(original) { output -> copyLimited(input, output, task) } }
                bytes += original.length(); check(bytes <= 2_000_000_000L) { "章节原图快照超过 2 GB 上限" }
                val hash = ExportFiles.hash(original)
                val saved = capturedTranslations[page.pageId]
                check(saved == null || saved.sourceSha256 == hash) { "页面原图已变化：${page.displayName}" }
                task.configurationSnapshot?.let(ExportConfiguration::decode)?.let { config ->
                    check(saved == null || (config.source == null || config.source == saved.source.tag) && (config.target == null || config.target == saved.target.tag)) {
                        "译文语言与导出任务创建时的设置不同，原成果已保留；请选择对应语言后重新创建导出任务"
                    }
                }
                val extension = page.displayName.substringAfterLast('.', "jpg").lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "jpg"
                val outputExtension = if (task.format == ExportFormat.CBZ) { if (saved != null) "png" else extension } else task.format.extension
                captured.put(JSONObject().put("id", page.pageId).put("hash", hash).put("extension", extension)
                    .put("translated", saved != null).put("output", "%05d.%s".format(index + 1, outputExtension))
                    .put("revision", saved?.revision).put("resultIdentity", saved?.resultIdentity)
                    .put("source", saved?.source?.tag).put("target", saved?.target?.tag).put("manuallyEdited", saved?.manuallyEdited))
            }
        val snapshot = JSONObject().put("task", task.id).put("pages", captured).put("configurationSnapshot", task.configurationSnapshot)
        ExportFiles.commit(marker) { it.write(snapshot.toString().toByteArray(Charsets.UTF_8)) }
        return snapshot
    }

    private fun writePage(output: java.io.OutputStream, original: File, format: ExportFormat,
        saved: com.lmreader.ui.reader.translation.ReaderPageTranslation?, extension: String) {
        val canCopyOriginal = format == ExportFormat.CBZ ||
            (format == ExportFormat.PNG && extension == "png") ||
            (format == ExportFormat.JPEG && extension in setOf("jpg", "jpeg"))
        if (canCopyOriginal && saved == null) { original.inputStream().use { it.copyTo(output) }; return }
        var bitmap = if (saved != null) decodePageAnalysisImage(context, original)
            else VisionImageDecoder.decode(context, Uri.fromFile(original), maxPixels = 16_000_000)
        try {
            if (saved != null && (bitmap.width != saved.width || bitmap.height != saved.height)) {
                val scaled = Bitmap.createScaledBitmap(bitmap, saved.width, saved.height, true)
                bitmap.recycle(); bitmap = scaled
            }
            val rendered = if (saved != null) BubbleMaskRenderer().render(bitmap, saved.regions, saved.renderSettings) else bitmap
            try {
                if (format == ExportFormat.JPEG) {
                    val opaque = Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(rendered, 0f, 0f, null) }
                        check(opaque.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "JPEG 编码失败" }
                    } finally { opaque.recycle() }
                } else check(rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            } finally { if (rendered !== bitmap) rendered.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun checkRunning(task: ExportTask) {
        if (!shouldContinue(task.id) || Thread.currentThread().isInterrupted || executionJobs[task.id]?.isActive == false) throw IOException("导出已暂停或取消")
    }
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, task: ExportTask) {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            checkRunning(task)
            val count = input.read(buffer); if (count < 0) break
            total += count; if (total > 64_000_000) throw IOException("单页超过 64 MB 导出上限")
            output.write(buffer, 0, count)
        }
    }

    private suspend fun publish(task: ExportTask, temporary: File) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(task.destinationTreeUri)) ?: throw TaskWaiting("ACCESS", "无法打开导出目录")
        if(!tree.canWrite()) throw TaskWaiting("ACCESS", "导出目录授权已失效，请重新选择目录")
        val base = safeName(if (task.singleChapter) task.mangaTitle else "${task.mangaTitle} - ${task.chapterTitle}")
        var filename = if (task.format == ExportFormat.CBZ) "$base.cbz" else base
        var serial = 2
        while (tree.findFile(filename) != null) { filename = if (task.format == ExportFormat.CBZ) "$base ($serial).cbz" else "$base ($serial)"; serial++ }
        checkRunning(task)
        // Reserve the deterministic token BEFORE creating the remote file. Even a crash
        // between createDocument and its return can be recovered by this exact name.
        requireChange(task.id) { it.copy(publicationName = filename, publicationPhase = "COPYING") }
        val token = ".lmreader-${task.id}.part"
        val output = (if (task.format == ExportFormat.CBZ) tree.createFile("application/octet-stream", token)
            else tree.createDirectory(token)) ?: error("无法创建导出临时文件")
        requireChange(task.id) { it.copy(temporaryUri = output.uri.toString()) }
        if (task.format == ExportFormat.CBZ) copyPublished(task, temporary, output)
        else temporary.listFiles().orEmpty().filterNot { it.name.endsWith(".sha256") }.sortedBy { it.name }.forEach { page ->
            checkRunning(task)
            val child = output.createFile(if (task.format == ExportFormat.PNG) "image/png" else "image/jpeg", page.name)
                ?: error("无法创建导出图片")
            copyPublished(task, page, child)
        }
        check(verifyPublished(task, output)) { "目标文件校验失败，可能空间不足或提供方写入不完整" }
        val commit = suspend {
        requireChange(task.id) { it.copy(publicationPhase = "READY") }
        synchronized(this@ExportQueueCoordinator) {
            checkRunning(task)
            // No UI control may interleave rename and the durable DONE receipt.
            check(tree.findFile(filename) == null) { "导出目标名称已被占用，请重试" }
            check(output.renameTo(filename)) { "目标目录不支持完成后重命名，请更换导出目录" }
            requireChange(task.id) { it.copy(state = "DONE", outputUri = output.uri.toString(),
                temporaryUri = null, publicationPhase = null, publicationName = null, failure = null) }
        }
        }
        // Copying remains cancellable. Only rename + the durable receipt own the short commit gate.
        val execution = unifiedExecutions[task.id]
        if(execution != null) execution.publish { commit() } else commit()
    }
    private fun copyPublished(task: ExportTask, sourceFile: File, targetFile: DocumentFile) {
        context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { target ->
            sourceFile.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) { checkRunning(task); val count = source.read(buffer); if (count < 0) break; target.write(buffer, 0, count) }
            }
            target.flush()
        } ?: error("无法写入导出文件")
    }
    private fun matches(file: DocumentFile, expected: File): Boolean {
        if (!expected.isFile) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; size += count
                if (size > expected.length()) return false; digest.update(buffer, 0, count) }
        } ?: return false
        return size == expected.length() && digest.digest().joinToString("") { "%02x".format(it) } == ExportFiles.hash(expected)
    }
    private fun verifyPublished(task: ExportTask, output: DocumentFile): Boolean {
        val root = ExportFiles.ownedRoot(staging, task.id)
        if (task.format == ExportFormat.CBZ) return matches(output, File(root, "chapter.cbz"))
        val expected = File(root, "rendered").listFiles().orEmpty().filterNot { it.name.endsWith(".sha256") || it.name.endsWith(".part") }
        val actual = output.listFiles().associateBy { it.name }
        return expected.isNotEmpty() && actual.size == expected.size && expected.all { file -> actual[file.name]?.let { matches(it, file) } == true }
    }
    private fun recoverPublication(task: ExportTask) {
        if (task.publicationPhase == null) return
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(task.destinationTreeUri)) ?: error("无法打开目标目录")
        check(tree.canWrite()) { "导出目录授权已失效" }
        val candidates = tree.listFiles()
        val output = task.temporaryUri?.let { uri -> candidates.firstOrNull { it.uri.toString() == uri } }
            ?: candidates.firstOrNull { it.name == ".lmreader-${task.id}.part" }
        val final = task.publicationName?.let { name -> candidates.firstOrNull { it.name == name } }
        // READY is written before rename. Providers may change the URI on rename.
        if (task.publicationPhase == "READY" && final != null && verifyPublished(task, final) &&
            (output == null || output.uri == final.uri)) {
            requireChange(task.id) { it.copy(state = "DONE", outputUri = final.uri.toString(), temporaryUri = null,
                publicationPhase = null, publicationName = null, failure = null) }
            return
        }
        if (output != null) {
            // Never delete a conflicting user file under the reserved final name.
            check(output.name == ".lmreader-${task.id}.part") { "临时文件名称已变化，保留供人工检查" }
            check(output.delete()) { "无法清理临时文件：${output.uri}" }
        }
        change(task.id) { it.copy(temporaryUri = null, publicationPhase = null, publicationName = null) }
    }

    private fun safeName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim(' ', '.').take(100).ifBlank { "未命名" }

    // --- Canonical bridge -----------------------------------------------------------------------

    private fun exportKey(id: String) = "export:$id"

    private fun legacyId(row: TaskRecord): String? {
        if (row.type != "EXPORT") return null
        return runCatching { JSONObject(row.payload).optString("legacyId").takeIf { it.isNotBlank() } }.getOrNull()
    }

    private fun cachedCanonical(id: String): TaskRecord? = canonicalRows.firstOrNull { legacyId(it) == id }

    private fun cancellable(id: String): Boolean {
        val row = cachedCanonical(id) ?: return true
        return row.state !in TaskScheduler.TERMINAL
    }

    /** Category resume only clears EXPORT_QUEUE pauses, never USER/GLOBAL/RESTORE intent. */
    private fun resumableByCategory(id: String, mangaId: String? = null): Boolean {
        val task = live(id) ?: return false
        if (mangaId != null && task.mangaId != mangaId) return false
        val row = cachedCanonical(id) ?: return true
        return row.pauseReason == PAUSE_EXPORT_QUEUE
    }

    /** The whole-queue resume also recognizes the pre-existing notification category pause. */
    private fun resumableByAllCategory(id: String): Boolean {
        val row = cachedCanonical(id) ?: return true
        return row.pauseReason != null && row.pauseReason in CATEGORY_PAUSE_REASONS
    }

    private suspend fun refreshCanonical(): List<TaskRecord> {
        container.taskScheduler.ready.await()
        val rows = container.taskScheduler.dao.all().filter { it.type == "EXPORT" }
        canonicalRows = rows
        return rows
    }

    /**
     * Registers legacy rows as canonical history, reusing legacyId. Durable completions and
     * failures are adopted in their terminal state and are never submitted as new work.
     */
    private suspend fun ensureLegacyAdopted(categoryPaused: Boolean = _paused.value) = adoptionMutex.withLock {
        container.taskScheduler.ready.await()
        val dao = container.taskScheduler.dao
        val existing = dao.all().filter { it.type == "EXPORT" }
        val known = existing.mapNotNull { row -> legacyId(row)?.let { it to row } }.toMap()
        val additions = mutableListOf<TaskRecord>()
        synchronized(this@ExportQueueCoordinator) {
            _tasks.value.forEachIndexed { index, task ->
                if (task.state !in ADOPTABLE_STATES) return@forEachIndexed
                if (task.id in known) return@forEachIndexed
                if (existing.any { it.activeKey == exportKey(task.id) }) return@forEachIndexed
                // pauseAll() may have changed PENDING to PAUSED after the argument was
                // evaluated. Read its category intent under the same monitor as the rows.
                additions += canonicalRowFor(task, index, categoryPaused || _paused.value)
            }
        }
        if (additions.isNotEmpty()) {
            container.taskScheduler.database.withTransaction { additions.forEach { dao.insert(it) } }
            container.taskScheduler.poke()
        }
        refreshCanonical()
    }

    private fun canonicalRowFor(task: ExportTask, index: Int, categoryPaused: Boolean): TaskRecord {
        val failed = task.state == "FAILED"
        val done = task.state == "DONE"
        val cancelled = task.state == "CANCELLED"
        val interrupted = task.state == "INTERRUPTED"
        val paused = task.state == "PAUSED" || (task.state == "PENDING" && categoryPaused)
        val state = when {
            done -> "SUCCEEDED"
            cancelled -> "CANCELLED"
            failed -> "FAILED"
            interrupted -> "INTERRUPTED"
            paused -> "PAUSED"
            else -> "QUEUED"
        }
        val reason = when {
            !paused -> null
            task.state == "PENDING" -> PAUSE_EXPORT_QUEUE
            categoryPaused -> PAUSE_EXPORT_QUEUE
            else -> "USER"
        }
        val key = exportKey(task.id)
        return TaskRecord(
            taskId = UUID.randomUUID().toString(), type = "EXPORT",
            title = "${task.mangaTitle} · ${task.chapterTitle}",
            ownerKey = "legacy-export",
            activeKey = if (state in TaskScheduler.TERMINAL) null else key,
            payload = JSONObject().put("legacyId", task.id).put("_goalKey", key).toString(),
            source = "LEGACY", priority = 0, orderIndex = index * 1000L,
            state = state, userPaused = paused, pauseReason = reason,
            completed = task.completedPages.toLong(), total = task.totalPages.toLong(),
            failure = if (state == "FAILED") task.failure else null,
            waitingReason = null, remoteOutcomeUnknown = false,
            recoverable = state !in TaskScheduler.TERMINAL,
            createdAt = System.currentTimeMillis())
    }

    private suspend fun projectAll() = projectionMutex.withLock { applyProjection(refreshCanonical()) }

    private fun applyProjection(rows: List<TaskRecord>) {
        val exports = rows.filter { it.type == "EXPORT" }
        if (exports.isEmpty()) return
        canonicalRows = exports
        synchronizeCategoryPause(exports)
        val byLegacy = exports.mapNotNull { row -> legacyId(row)?.let { it to row } }.toMap()
        if (byLegacy.isEmpty()) return
        synchronized(this@ExportQueueCoordinator) {
            var changed = false
            val next = _tasks.value.map { task ->
                val row = byLegacy[task.id] ?: return@map task
                val projected = project(task, row)
                if (projected != task) changed = true
                projected
            }
            if (changed) update(next)
        }
    }

    /** Notification controls pause the same export category through TaskScheduler directly. */
    private fun synchronizeCategoryPause(rows: List<TaskRecord>) {
        val active = rows.filter { it.state !in TaskScheduler.TERMINAL }
        val categoryPaused = active.any { it.state == "PAUSED" && it.pauseReason != null && it.pauseReason in CATEGORY_PAUSE_REASONS }
        if (categoryPaused) {
            sawCategoryPause = true
            if (!_paused.value) {
                _paused.value = true
                context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", true).apply()
            }
        } else if (sawCategoryPause && active.isNotEmpty() && _paused.value) {
            // The category pause was explicitly undone (or resumed from the notification).
            sawCategoryPause = false
            _paused.value = false
            context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", false).apply()
        } else if (!_paused.value) {
            sawCategoryPause = false
        }
    }

    private fun project(task: ExportTask, row: TaskRecord): ExportTask {
        val claimedGeneration = executionGenerations[task.id]
        if (claimedGeneration != null && row.generation < claimedGeneration) return task
        // A Room emission for the current claim can arrive after process() has moved its
        // publication journal ahead (or recorded an error). Keep that journal until the
        // executor reports its outcome to TaskStore; otherwise RUNNING can erase FAILED,
        // or prevent the PENDING -> RUNNING admission inside process().
        if (executionJobs[task.id]?.isActive == true && row.state in setOf("QUEUED", "RUNNING")) {
            return task.copy(completedPages = maxOf(task.completedPages, row.completed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
                totalPages = maxOf(task.totalPages, row.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }
        val mapped = when (row.state) {
            "QUEUED" -> "PENDING"
            "RUNNING" -> "RUNNING"
            "PAUSING" -> "RUNNING"
            "PAUSED" -> "PAUSED"
            "WAITING" -> "INTERRUPTED"
            "INTERRUPTED" -> "INTERRUPTED"
            "FAILED" -> "FAILED"
            "SUCCEEDED" -> "DONE"
            "CANCELLED", "CANCELLING" -> "CANCELLED"
            else -> task.state
        }
        // Publication already renamed the output; a stale in-flight canonical snapshot must not
        // downgrade that durable receipt.
        val state = if (task.state == "DONE" && mapped != "DONE") "DONE" else mapped
        val failure = when (state) {
            "FAILED" -> row.failure ?: task.failure
            "INTERRUPTED" -> row.failure ?: if (row.state == "WAITING") waitingMessage(row) else task.failure
            "PAUSED" -> task.failure
            "PENDING", "RUNNING", "DONE", "CANCELLED" -> null
            else -> task.failure
        }
        val completed = if (row.completed > 0) row.completed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else task.completedPages
        val total = if (row.total > 0) row.total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else task.totalPages
        return task.copy(state = state, failure = failure, completedPages = completed, totalPages = total)
    }

    private fun waitingMessage(row: TaskRecord): String = when (row.waitingReason) {
        "ACCESS" -> "缺少导出记录或目录授权，请恢复后重试"
        "PAUSED" -> "导出已暂停"
        else -> "导出任务等待恢复"
    }

    private suspend fun pauseCategoryCanonical(mangaId: String? = null) {
        container.taskScheduler.ready.await()
        val rows = refreshCanonical()
        for (row in rows) {
            if (row.state !in ACTIVE_STATES || row.userPaused) continue
            val id = legacyId(row) ?: continue
            val task = live(id) ?: continue
            if (mangaId != null && task.mangaId != mangaId) continue
            container.taskScheduler.pause(row.taskId, pauseReason = PAUSE_EXPORT_QUEUE)
        }
        refreshCanonical()
    }

    private fun syncCanonicalOrder() {
        val legacyOrder = synchronized(this) { _tasks.value.map { it.id } }
        launchCommand {
            recovery.join()
            container.taskScheduler.ready.await()
            ensureLegacyAdopted(categoryPaused = _paused.value)
            refreshCanonical()
            val byLegacy = canonicalRows.mapNotNull { row -> legacyId(row)?.let { id -> id to row.taskId } }.toMap()
            val desired = legacyOrder.mapNotNull { byLegacy[it] }
            if (desired.size > 1) {
                // Transform the current canonical root order into the legacy order using only
                // TaskScheduler.move; this reorders the ledger without restarting native/API work.
                var pass = 0
                while (pass++ <= desired.size) {
                    val roots = container.taskScheduler.dao.all()
                        .filter { it.parentTaskId == null && it.state !in TaskScheduler.TERMINAL && it.priority == 0 }
                        .map { it.taskId }
                    var moved = false
                    for (index in 1 until desired.size) {
                        val previous = roots.indexOf(desired[index - 1])
                        val current = roots.indexOf(desired[index])
                        if (previous >= 0 && current >= 0 && current < previous) {
                            container.taskScheduler.move(desired[index], previous - current)
                            moved = true
                            break
                        }
                    }
                    if (!moved) break
                }
            }
            projectAll()
        }
    }

    private suspend fun awaitExecution(id: String) {
        while (true) {
            val job = executionJobs[id] ?: return
            if (!job.isActive) { executionJobs.remove(id, job); return }
            job.join()
        }
    }

    private suspend fun cleanCancelledNow(ids: List<String>) {
        for (id in ids) {
            awaitExecution(id)
            val task = live(id) ?: continue
            if (task.state != "CANCELLED" || task.publicationPhase != null) continue
            val row = cachedCanonical(id)
            if (row != null && row.state !in TaskScheduler.TERMINAL) continue
            if (executionJobs[id]?.isActive == true) continue
            try { recoverPublication(task) } catch (error: Exception) {
                change(id) { it.copy(failure = "临时文件未清理：${error.message}") }
                continue
            }
            val current = live(id) ?: continue
            if (current.state == "CANCELLED" && current.publicationPhase == null) deleteOwnedRoot(id)
        }
    }

    private fun deleteOwnedRoot(id: String): Boolean {
        val root = ExportFiles.ownedRoot(staging, id)
        if (!root.exists()) return true
        if (root.deleteRecursively()) return true
        change(id) { it.copy(failure = "导出临时快照未清理，请在导出设置中清理临时文件") }
        return false
    }

    @Synchronized private fun launchCommand(block: suspend () -> Unit): Job {
        val preceding = controlTail
        val job = scope.launch(start = CoroutineStart.LAZY) {
            preceding?.join()
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("ExportQueue", "导出队列命令失败", error)
                runCatching { projectAll() }
            }
        }
        controlTail = job
        controlJobs.add(job)
        job.invokeOnCompletion { controlJobs.remove(job) }
        job.start()
        return job
    }

    internal suspend fun awaitControlJobs() {
        while (true) {
            val jobs = controlJobs.toList()
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    private suspend fun awaitControlsAndExecutions() {
        while (true) {
            val controls = controlJobs.toList()
            if (controls.isNotEmpty()) controls.joinAll()
            val executions = executionJobs.values.toList().filter { it.isActive }
            if (executions.isNotEmpty()) executions.joinAll()
            if (controlJobs.isEmpty() && executionJobs.values.none { it.isActive }) return
        }
    }

    private fun save(rows: List<ExportTask>) {
        val json = JSONArray().apply { rows.forEach { row -> put(JSONObject()
            .put("id", row.id).put("mangaId", row.mangaId).put("chapterId", row.chapterId)
            .put("mangaTitle", row.mangaTitle).put("chapterTitle", row.chapterTitle)
            .put("sourceTreeUri", row.sourceTreeUri).put("destinationTreeUri", row.destinationTreeUri)
            .put("singleChapter", row.singleChapter).put("format", row.format.name).put("state", row.state)
            .put("completedPages", row.completedPages).put("totalPages", row.totalPages)
            .put("outputUri", row.outputUri).put("failure", row.failure)
            .put("temporaryUri", row.temporaryUri).put("publicationName", row.publicationName).put("publicationPhase", row.publicationPhase)
            .put("configurationSnapshot", row.configurationSnapshot)) } }
        // AtomicFile.finishWrite only logs some commit failures. A publication
        // receipt must report failure before the next external mutation begins.
        ExportFiles.commit(stateFile) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun load(): List<ExportTask> = runCatching {
        if (!stateFile.exists() && !File(stateFile.path + ".bak").exists()) return emptyList()
        require(stateFile.length() <= 32_000_000) { "队列文件过大" }
        val file = android.util.AtomicFile(stateFile)
        val json = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        require(json.length() <= 10_000) { "队列任务过多" }
        (0 until json.length()).map { index -> json.getJSONObject(index).let { item ->
            ExportTask(item.getString("id"), item.getString("mangaId"), item.getString("chapterId"),
                item.getString("mangaTitle"), item.getString("chapterTitle"), item.getString("sourceTreeUri"),
                item.getString("destinationTreeUri"), item.getBoolean("singleChapter"),
                ExportFormat.valueOf(item.optString("format", "CBZ")),
                item.getString("state").let { if (it == "RUNNING") "INTERRUPTED" else it },
                item.getInt("completedPages"), item.getInt("totalPages"),
                item.optString("outputUri").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("failure").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("temporaryUri").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("publicationName").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("publicationPhase").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("configurationSnapshot").takeIf { it.isNotEmpty() && it != "null" }).also { task ->
                    task.configurationSnapshot?.let(ExportConfiguration::decode)
                    require(task.id.matches(Regex("[a-fA-F0-9-]{36}")) && task.completedPages >= 0 && task.totalPages >= 0)
                    require(task.state in setOf("PENDING", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED", "DONE", "CANCELLED"))
                    require(task.publicationPhase == null || task.publicationPhase in setOf("COPYING", "READY"))
                }
        } }
    }.getOrElse { error -> _storageFailure.value = "导出队列文件损坏，原文件已保留，请保留文件供排查：${error.message}"; emptyList() }

    private companion object {
        const val PAUSE_EXPORT_QUEUE = "EXPORT_QUEUE"
        const val PAUSE_EXPORT_NOTIFICATION = "EXPORT_NOTIFICATION"
        val CATEGORY_PAUSE_REASONS = setOf(PAUSE_EXPORT_QUEUE, PAUSE_EXPORT_NOTIFICATION)
        val ACTIVE_STATES = setOf("QUEUED", "RUNNING", "PAUSING", "WAITING", "INTERRUPTED")
        val ADOPTABLE_STATES = setOf("PENDING", "PAUSED", "FAILED", "INTERRUPTED", "DONE", "CANCELLED")
    }
}
