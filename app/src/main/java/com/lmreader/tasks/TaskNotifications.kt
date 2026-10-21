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
        observing=true;renderer.ensureChannel()
        container.translationQueue; container.exportQueue; container.readerPageTasks; container.videoTasks
        container.backgroundScope.launch(Dispatchers.Main.immediate) {
            combine(container.taskScheduler.tasks,container.taskService.leases) { rows,leases -> rows to leases }
                .collect { (rows,leases) ->
                    val primary=rows.filter {it.parentTaskId==null && it.type!="MEDIA_SCAN"}
                    fun item(row:com.lmreader.core.database.tasks.TaskRecord)=TaskNoticeItem(row.taskId,when(row.state) {
                        "QUEUED"->"PENDING";"SUCCEEDED"->"DONE";"WAITING"->"PAUSED";else->row.state
                    },row.title,row.waitingReason?.let(::waitingReasonLabel) ?: row.stage,
                        row.completed.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),row.total.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),row.failure)
                    val next=listOfNotNull(
                        translation.update(primary.filter {it.isTranslation()}.map(::item),false),
                        export.update(primary.filter {it.type.contains("EXPORT")}.map(::item),container.exportQueue.paused.value)).associateBy {it.queue}
                    val important=next.mapValues {it.value.phase}!=_notices.value.mapValues {it.value.phase} || summaryKey?.second!=(leases>0)
                    _notices.value=next;delayedPublish?.cancel()
                    val remaining=(1000L-(SystemClock.elapsedRealtime()-lastPublish)).coerceAtLeast(0)
                    if(important || remaining==0L) publish() else delayedPublish=launch {delay(remaining);publish()}
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
