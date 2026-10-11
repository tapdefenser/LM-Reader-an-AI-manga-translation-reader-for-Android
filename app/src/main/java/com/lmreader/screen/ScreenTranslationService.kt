package com.lmreader.screen

import android.app.*
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.lmreader.R
import com.lmreader.di.AppContainer
import com.lmreader.core.model.InferenceEngineKind
import com.lmreader.core.storage.settings.GeneralPreferences
import com.lmreader.ui.i18n.UiTextTranslations
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ScreenTranslationService : Service() {
    private val container by lazy { AppContainer.from(this) }
    private val controller get() = container.screenTranslation
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false
    private var lastNotification = ""
    private var lastNotificationTime = 0L
    private var notificationJob: Job? = null
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == Intent.ACTION_SCREEN_OFF) controller.stop("屏幕已关闭，屏幕翻译已停止") }
    }
    private val localized get() =
        createConfigurationContext(android.content.res.Configuration(resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(GeneralPreferences(this@ScreenTranslationService).effectiveLanguageTag))
        })
    private fun label(id: Int) = localized.getString(id)
    override fun onCreate() {
        super.onCreate()
        androidx.core.content.ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> controller.stop()
            PREPARE -> if (running) controller.prepareEngine() else stopSelf()
            TOGGLE_ENGINE -> if (running) controller.toggleEngine() else stopSelf()
            START, START_ACCESSIBILITY -> {
                if (running) return START_NOT_STICKY
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, label(R.string.screen_translation_settings), NotificationManager.IMPORTANCE_LOW))
                    val projectionMode = intent.action == START
                    ServiceCompat.startForeground(this, NOTICE, notice(), if (Build.VERSION.SDK_INT >= 34)
                        if (projectionMode) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        else if (projectionMode && Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
                    val capture = if (projectionMode) {
                        @Suppress("DEPRECATION") val result = intent.getParcelableExtra<Intent>("capture") ?: error("截图授权已失效，请重新启动屏幕翻译")
                        val projection = requireNotNull(getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, result))
                        ProjectionScreenCapture(this, projection, controller.preferences.settings.value.maximumCaptureEdge) { controller.stop("屏幕截图会话已结束") }
                    } else null
                    controller.attach(this, capture); running = true
                    scope.launch { merge(controller.state.map { Unit }, controller.vision.loadedResources.map { Unit },
                        container.localTranslator.loadedResources.map { Unit }, container.apiClient.activeRequests.map { Unit },
                        controller.vision.preprocessingCache.bytes.map { Unit }, controller.apiResources.map { Unit }).collect { refreshNotice() } }
                    refreshNotice()
                } catch (failure: Throwable) {
                    controller.stop(failure.message ?: "无法启动屏幕翻译"); finishSession()
                }
            }
            else -> if (!running) stopSelf()
        }
        return START_NOT_STICKY
    }
    fun refreshNotice() {
        if (!running) return
        if (notificationJob?.isActive == true) return
        notificationJob = scope.launch(start = CoroutineStart.LAZY) {
            delay((lastNotificationTime + 500 - SystemClock.uptimeMillis()).coerceAtLeast(0))
            if (running) publishNotice()
        }.also { it.start() }
    }
    private fun publishNotice() {
        val current = controller.state.value
        val resources = controller.vision.loadedResources.value + container.localTranslator.loadedResources.value
        val signature = "$current:$resources:${container.apiClient.activeRequests.value}:${controller.vision.preprocessingCache.bytes.value}:${controller.apiResources.value}:${localized.resources.configuration.locales}"
        if (signature == lastNotification) return
        lastNotification = signature
        lastNotificationTime = SystemClock.uptimeMillis()
        getSystemService(NotificationManager::class.java).notify(NOTICE, notice())
    }
    private fun notice(): Notification {
        val resources = controller.vision.loadedResources.value + container.localTranslator.loadedResources.value
        fun translated(text: String) = UiTextTranslations.translate(localized, text)
        val counts = InferenceEngineKind.entries.joinToString(" · ") { kind ->
            "${if (kind == InferenceEngineKind.TRANSLATION) translated("机翻") else kind.name} ${resources.count { it.kind == kind && it.inUse }}/${resources.count { it.kind == kind }}"
        } + " · API ${container.apiClient.activeRequests.value}"
        val cache = controller.vision.preprocessingCache.bytes.value / 1_048_576
        val chapters = controller.state.value.activeChapters
        val detail = listOf(translated("资源：正在使用/已加载"), counts, localized.resources.getQuantityString(R.plurals.screen_translation_pipeline_count, chapters, chapters),
            resources.map { "${translated(it.name)} (${it.backend})" }.distinct().joinToString("\n"), controller.apiResources.value.joinToString("\n"),
            translated("屏幕预处理缓存：$cache MB"), translated(controller.state.value.status)).filter { it.isNotBlank() }.joinToString("\n")
        fun serviceAction(action: String, request: Int) = PendingIntent.getService(this, request, Intent(this, ScreenTranslationService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val settings = PendingIntent.getActivity(this, 3103, Intent(this, ScreenTranslationSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopEngine = controller.state.value.engine in setOf(ScreenEngineState.LOADING, ScreenEngineState.READY, ScreenEngineState.STOPPING)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.lmreader_ic_task)
            .setContentTitle(label(R.string.screen_translation_notification_title)).setContentText(counts)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setContentIntent(settings).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, label(if (stopEngine) R.string.screen_translation_stop_engine else R.string.screen_translation_prepare_engine), serviceAction(TOGGLE_ENGINE, 3101))
            .addAction(0, label(R.string.screen_translation_settings), settings)
            .build()
    }
    internal fun finishSession() { running = false; ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { if (running) controller.stop(); unregisterReceiver(screenOff); scope.cancel(); super.onDestroy() }
    companion object {
        const val START = "com.lmreader.screen.START"
        const val START_ACCESSIBILITY = "com.lmreader.screen.START_ACCESSIBILITY"
        const val STOP = "com.lmreader.screen.STOP"
        const val PREPARE = "com.lmreader.screen.PREPARE"
        const val TOGGLE_ENGINE = "com.lmreader.screen.TOGGLE_ENGINE"
        private const val CHANNEL = "screen-translation"
        private const val NOTICE = 3100
    }
}
