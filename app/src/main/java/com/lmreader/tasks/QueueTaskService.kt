package com.lmreader.tasks

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.lmreader.R
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(kotlinx.coroutines.FlowPreview::class)
class QueueTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container by lazy { AppContainer.from(this) }
    private val localized by lazy {
        val tag = container.generalPreferences.effectiveLanguageTag
        createConfigurationContext(android.content.res.Configuration(resources.configuration).apply {
            setLocales(android.os.LocaleList.forLanguageTags(tag))
        })
    }
    private fun label(id: Int, vararg args: Any): String = localized.getString(id, *args)
    private var observing = false
    private var startupFailed = false
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        container.taskNotifications.start()
        if (!promote()) return
        wakeLock = getSystemService(android.os.PowerManager::class.java)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LMReader:ChapterTasks")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (startupFailed) { stopSelf(); return START_NOT_STICKY }
        // A new lease may arrive while an idle instance is waiting for stopSelf.
        // Re-promote and acknowledge every start, including reused service instances.
        if (!promote()) return START_NOT_STICKY
        when(intent?.action) {
            TaskNotificationRenderer.action(TaskQueue.TRANSLATION, false) -> scope.launch { TranslationQueueController(container).pauseAll() }
            TaskNotificationRenderer.action(TaskQueue.EXPORT, false) -> scope.launch {
                container.exportQueue.pauseAll()
                container.taskScheduler.tasks.value.filter { it.type == "VIDEO_EXPORT" && it.state !in TaskScheduler.TERMINAL }
                    .forEach { container.taskScheduler.pause(it.taskId, pauseReason = "EXPORT_NOTIFICATION") }
            }
            TaskNotificationRenderer.action(TaskQueue.TRANSLATION, true) -> {
                resumeNotification(TaskQueue.TRANSLATION)
            }
            TaskNotificationRenderer.action(TaskQueue.EXPORT, true) -> {
                resumeNotification(TaskQueue.EXPORT)
            }
            PAUSE -> scope.launch { TranslationQueueController(container).pauseAll() }
        }
        if (!observing) {
            observing = true
            scope.launch {
                container.taskService.leases.debounce(300).collect { leases ->
                    if (leases == 0) container.taskService.stopIfIdle {
                        val keep = container.taskNotifications.notices.value.isNotEmpty()
                        ServiceCompat.stopForeground(this@QueueTaskService, if(!keep)
                            ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH)
                        if(keep) container.taskNotifications.refresh(includeResults = true)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.taskService.serviceStopped(label(R.string.lmreader_task_timeout), this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }
    override fun onDestroy() {
        container.taskService.serviceStopped(label(R.string.lmreader_task_stopped), this)
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun resumeNotification(queue: TaskQueue) {
        container.taskService.allowNotificationResume(this)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val lease = container.taskService.acquire()
            try {
                if(queue == TaskQueue.TRANSLATION) TranslationQueueController(container).continueAll()
                else {
                    container.exportQueue.resumeAll()
                    container.exportQueue.awaitControlJobs()
                    container.taskScheduler.tasks.value.filter { it.type == "VIDEO_EXPORT" && it.pauseReason == "EXPORT_NOTIFICATION" }
                        .forEach { container.taskScheduler.resume(it.taskId, onlyPauseReason = "EXPORT_NOTIFICATION") }
                }
            } finally { container.taskService.release(lease) }
        }
    }
    private fun promote(): Boolean {
        return try {
            val notification = container.taskNotifications.renderer.summary(container.taskNotifications.notices.value.values, true)
            val types = if (android.os.Build.VERSION.SDK_INT >= 35 && container.taskService.mediaProcessingActive)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or (if(container.taskService.dataSyncActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // ServiceCompat's API 34 mask excludes MEDIA_PROCESSING (0x2000), even on
            // newer platforms. A media-only lease would consequently become TYPE_NONE.
            if (android.os.Build.VERSION.SDK_INT >= 35) startForeground(TaskNotificationRenderer.FOREGROUND_ID, notification, types)
            else ServiceCompat.startForeground(this, TaskNotificationRenderer.FOREGROUND_ID, notification, types)
            container.taskService.serviceReady(this); true
        } catch (error: Exception) {
            android.util.Log.e("LMReaderTasks", "Foreground task service promotion failed", error)
            startupFailed = true; container.taskService.failStartup(error); stopSelf(); false
        }
    }
    internal fun refreshForegroundTypes() { if(!startupFailed) promote() }
    companion object { private const val PAUSE = "com.lmreader.tasks.PAUSE" }
}
