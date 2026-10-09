package com.lmreader.tasks

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.core.app.NotificationCompat
import com.lmreader.MainActivity
import com.lmreader.R
import com.lmreader.core.storage.settings.GeneralPreferences

internal class TaskNotificationRenderer(private val context: Context,
    private val languageTag: () -> String = GeneralPreferences(context).let { preferences -> { preferences.effectiveLanguageTag } }) {
    private var localeTag = ""
    private var localized = context
    private fun label(id: Int, vararg args: Any): String {
        val tag = languageTag()
        if(tag != localeTag) {
            localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            }); localeTag = tag
        }
        return localized.getString(id, *args)
    }
    fun ensureChannel() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, label(R.string.lmreader_task_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = label(R.string.lmreader_task_channel_description)
            })
    }
    private fun name(queue: TaskQueue) = label(if(queue == TaskQueue.TRANSLATION) R.string.lmreader_task_translation else R.string.lmreader_task_export)
    private fun title(notice: TaskNotice): String = listOf(label(when(notice.phase) {
        TaskNoticePhase.WAITING -> R.string.lmreader_task_waiting
        TaskNoticePhase.RUNNING -> R.string.lmreader_task_running
        TaskNoticePhase.PAUSING -> R.string.lmreader_task_pausing
        TaskNoticePhase.PAUSED -> R.string.lmreader_task_paused
        TaskNoticePhase.FAILED -> R.string.lmreader_task_failed
        TaskNoticePhase.COMPLETED -> R.string.lmreader_task_completed
    }, if(notice.singlePage) label(R.string.lmreader_task_single_page) else name(notice.queue)),
        notice.item.manga, notice.item.chapter,
        if(notice.item.total > 0) "${notice.item.completed.coerceIn(0, notice.item.total)}/${notice.item.total}" else "")
        .filter(String::isNotBlank).joinToString(" · ")
    private fun progress(notice: TaskNotice): String = when {
        notice.item.total > 0 -> label(R.string.lmreader_task_pages, notice.item.completed.coerceIn(0, notice.item.total), notice.item.total)
        notice.item.completed > 0 -> label(R.string.lmreader_task_saved_pages, notice.item.completed)
        notice.working -> label(R.string.lmreader_task_preparing)
        else -> ""
    }
    private fun details(notice: TaskNotice): String = listOfNotNull(
        notice.item.manga.takeIf { it.isNotBlank() }, notice.item.chapter.takeIf { it.isNotBlank() },
        progress(notice).takeIf { it.isNotBlank() },
        if(notice.remaining > 0 && !notice.singlePage) label(R.string.lmreader_task_remaining, notice.remaining) else null,
        if(notice.failures > 0) label(R.string.lmreader_task_failures, notice.failures) else null,
        if(notice.phase == TaskNoticePhase.COMPLETED && notice.completedChapters > 0) label(R.string.lmreader_task_finished_chapters, notice.completedChapters) else null,
        notice.item.error?.takeIf { it.isNotBlank() }?.take(400)
    ).joinToString("\n")
    private fun open(queue: TaskQueue): PendingIntent = PendingIntent.getActivity(context, id(queue),
        Intent(context, MainActivity::class.java).putExtra("open-queue", queue.key)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), FLAGS)
    private fun control(queue: TaskQueue, resume: Boolean): PendingIntent = PendingIntent.getForegroundService(context,
        id(queue) * 10 + if(resume) 1 else 0, Intent(context, QueueTaskService::class.java).setAction(action(queue, resume)), FLAGS)
    private fun base() = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.lmreader_ic_task)
        .setGroup(GROUP).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
    fun queue(notice: TaskNotice): Notification {
        val text = details(notice)
        val builder = base().setContentTitle(title(notice)).setContentText(text.replace('\n', ' '))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open(notice.queue))
            .setOngoing(notice.working).setAutoCancel(!notice.working)
            .setCategory(if(notice.working) NotificationCompat.CATEGORY_PROGRESS else NotificationCompat.CATEGORY_STATUS)
        if(notice.item.total > 0) builder.setProgress(notice.item.total, notice.item.completed.coerceIn(0, notice.item.total), false)
        else if(notice.working) builder.setProgress(0, 0, true)
        if(notice.queue == TaskQueue.TRANSLATION && notice.working) builder.setSubText(label(R.string.lmreader_task_concurrency, notice.seg, notice.ocr, notice.api))
        val resume = notice.phase in setOf(TaskNoticePhase.PAUSED, TaskNoticePhase.PAUSING)
        if(notice.working || resume) builder.addAction(0, label(if(resume) R.string.lmreader_task_resume_one else R.string.lmreader_task_pause_one), control(notice.queue, resume))
        builder.addAction(0, label(R.string.lmreader_task_open_queue), open(notice.queue))
        return builder.build()
    }
    fun summary(notices: Collection<TaskNotice>, ongoing: Boolean): Notification {
        val ordered = notices.sortedBy { it.queue.ordinal }
        val primary = ordered.firstOrNull { it.working } ?: ordered.firstOrNull()
        val lines = ordered.map { title(it) + " · " + progress(it) }
        val builder = base().setGroupSummary(true).setOngoing(ongoing).setAutoCancel(!ongoing)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(primary?.let(::title) ?: if(ongoing) label(R.string.lmreader_task_title) else label(R.string.lmreader_task_channel))
            .setContentText(lines.joinToString(" · ").ifBlank { label(R.string.lmreader_task_starting) })
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) } })
            .setContentIntent(open(primary?.queue ?: TaskQueue.TRANSLATION))
        ordered.forEach { builder.addAction(0, name(it.queue), open(it.queue)) }
        if(ordered.size == 1 && primary != null && primary.item.total > 0)
            builder.setProgress(primary.item.total, primary.item.completed.coerceIn(0, primary.item.total), false)
        return builder.build()
    }
    companion object {
        const val CHANNEL = "chapter-tasks"
        const val FOREGROUND_ID = 2101
        private const val GROUP = "lmreader-task-queues"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        fun id(queue: TaskQueue) = if(queue == TaskQueue.TRANSLATION) 2102 else 2103
        fun action(queue: TaskQueue, resume: Boolean) = "com.lmreader.tasks.${queue.name}_${if(resume) "RESUME" else "PAUSE"}"
    }
}
