package com.lmreader.tasks

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.os.SystemClock
import android.content.Intent
import android.provider.Settings
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.ExportTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** App-owned observation survives service detachment, so paused/results notifications remain useful. */
class TaskNotifications(private val container: AppContainer) {
    private val context = container.application
    internal val renderer = TaskNotificationRenderer(context) { container.generalPreferences.effectiveLanguageTag }
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val _notices = MutableStateFlow<Map<TaskQueue, TaskNotice>>(emptyMap())
    val notices = _notices.asStateFlow()
    private var observing = false
    private var delayedPublish: Job? = null
    private var lastPublish = 0L
    private var published = emptyMap<TaskQueue, TaskNotice>()
    private var summaryKey: Pair<Map<TaskQueue, TaskNotice>, Boolean>? = null
    private var previousTranslations = emptyList<ChapterTranslationEntity>()
    private val translation = TaskNoticeTracker(TaskQueue.TRANSLATION)
    private val export = TaskNoticeTracker(TaskQueue.EXPORT)
    private val chapterMetadata = mutableMapOf<String, TaskNoticeItem>()
    private val metadataRevision = mutableMapOf<String, Long>()
    private val mangaNames = mutableMapOf<String, String>()
    private data class TranslationInput(val rows: List<ChapterTranslationEntity>, val paused: Boolean, val priority: String?)
    private data class ExportInput(val rows: List<ExportTask>, val paused: Boolean)
    private data class Engines(val seg: Int, val ocr: Int, val api: Int, val leases: Int, val failure: String?)
    fun enabled() = manager.areNotificationsEnabled() && manager.getNotificationChannel(TaskNotificationRenderer.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun settingsIntent(): Intent = if(manager.areNotificationsEnabled() && manager.getNotificationChannel(TaskNotificationRenderer.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, TaskNotificationRenderer.CHANNEL)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    fun start() {
        if(observing) return
        observing = true; renderer.ensureChannel()
        val queue = container.translationQueue
        val translations = combine(queue.items, queue.paused, queue.priorityPage, queue.orderRevision) { rows, paused, priority, _ ->
            TranslationInput(queue.ordered(rows), paused, priority)
        }
        val exports = combine(container.exportQueue.tasks, container.exportQueue.paused, ::ExportInput)
        val engines = combine(queue.activeSeg, queue.activeOcr, queue.activeApi, container.taskService.leases, container.taskService.failure, ::Engines)
        container.backgroundScope.launch(Dispatchers.Main.immediate) {
            combine(translations, exports, engines) { t, e, a -> Triple(t, e, a) }.collect { (t, e, a) ->
                try {
                    val raw = previousTranslations
                    previousTranslations = t.rows
                    val removed = raw.filter { old -> t.rows.none { it.chapterId == old.chapterId && it.targetLanguage == old.targetLanguage } }
                    val terminal = if(removed.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                        container.database.translationDao().byChapters(removed.map { it.chapterId }.distinct()).filter { item ->
                            removed.any { it.chapterId == item.chapterId && it.targetLanguage == item.targetLanguage }
                        }
                    }
                    val rows = t.rows + terminal
                    val primary = rows.firstOrNull { it.state == "RUNNING" } ?: rows.firstOrNull { it.state == "PENDING" }
                        ?: rows.firstOrNull { it.state == "PAUSED" } ?: rows.firstOrNull { it.state in setOf("FAILED", "INTERRUPTED") } ?: terminal.lastOrNull()
                    if(primary != null && metadataRevision[primary.chapterId] != primary.updatedAt) withContext(Dispatchers.IO) {
                        val chapter = container.database.chapterDao().getById(primary.chapterId)
                        val manga = mangaNames[primary.mangaId] ?: container.mangaRepository.getCards(listOf(primary.mangaId)).firstOrNull()?.displayName.orEmpty()
                        mangaNames[primary.mangaId] = manga
                        chapterMetadata[primary.chapterId] = TaskNoticeItem(primary.chapterId, primary.state, manga, chapter?.title.orEmpty(), total = chapter?.pageCount ?: 0)
                        metadataRevision[primary.chapterId] = primary.updatedAt
                    }
                    val translated = rows.map { row -> (chapterMetadata[row.chapterId] ?: TaskNoticeItem(row.chapterId, row.state)).copy(
                        id = row.chapterId + ":" + row.targetLanguage, state = row.state, completed = row.translatedCount, error = row.failure) }
                    val exported = e.rows.map { TaskNoticeItem(it.id, it.state, it.mangaTitle, it.chapterTitle, it.completedPages, it.totalPages, it.failure) }
                    val tNotice = translation.update(translated, t.paused, t.priority)?.let { state -> state.copy(seg = if(state.working) a.seg else 0, ocr = if(state.working) a.ocr else 0, api = if(state.working) a.api else 0,
                        item = if(a.failure != null && state.phase in setOf(TaskNoticePhase.PAUSED, TaskNoticePhase.PAUSING)) state.item.copy(error = a.failure) else state.item) }
                    val eNotice = export.update(exported, e.paused)
                    val next = listOfNotNull(tNotice, eNotice).associateBy { it.queue }
                    val important = next.mapValues { it.value.phase } != _notices.value.mapValues { it.value.phase } ||
                        summaryKey?.second != (a.leases > 0)
                    _notices.value = next
                    delayedPublish?.cancel()
                    val remaining = (1000L - (SystemClock.elapsedRealtime() - lastPublish)).coerceAtLeast(0)
                    if(important || remaining == 0L) publish()
                    else delayedPublish = launch { delay(remaining); publish() }
                    val retained = (t.rows + terminal).map { it.chapterId }.toSet()
                    chapterMetadata.keys.retainAll(retained); metadataRevision.keys.retainAll(retained)
                    mangaNames.keys.retainAll(rows.map { it.mangaId }.toSet())
                } catch(cancelled: CancellationException) { throw cancelled }
                catch(error: Exception) { android.util.Log.e("TaskNotifications", "Unable to update task notification", error) }
            }
        }
    }
    /** Called on a permission grant/return from settings, even when progress has not changed. */
    fun refresh(includeResults: Boolean = false) {
        container.backgroundScope.launch(Dispatchers.Main.immediate) {
            renderer.ensureChannel()
            if(enabled()) {
                _notices.value.values.filter { includeResults || it.phase !in setOf(TaskNoticePhase.COMPLETED, TaskNoticePhase.FAILED) }.forEach(::post)
                if(includeResults && _notices.value.isNotEmpty() || _notices.value.values.any { it.working } || container.taskService.leases.value > 0) publishSummary(force = true)
            }
        }
    }
    @SuppressLint("MissingPermission") // Permission denial suppresses optional cards; it never blocks task execution.
    private fun post(notice: TaskNotice) { manager.notify(TaskNotificationRenderer.id(notice.queue), renderer.queue(notice)) }
    private fun publish() {
        lastPublish = SystemClock.elapsedRealtime()
        if(!enabled()) return
        val next = _notices.value
        TaskQueue.entries.forEach { queue ->
            val current = next[queue]
            if(current != published[queue]) {
                if(current == null) manager.cancel(TaskNotificationRenderer.id(queue)) else post(current)
            }
        }
        published = next; publishSummary()
    }
    @SuppressLint("MissingPermission")
    private fun publishSummary(force: Boolean = false) {
        val key = _notices.value to (container.taskService.leases.value > 0)
        if(!force && summaryKey == key) return
        summaryKey = key
        if(key.first.isEmpty() && !key.second) manager.cancel(TaskNotificationRenderer.FOREGROUND_ID)
        else manager.notify(TaskNotificationRenderer.FOREGROUND_ID, renderer.summary(key.first.values, key.second))
    }
}
