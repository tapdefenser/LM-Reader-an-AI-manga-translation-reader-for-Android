package com.lmreader.screen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.accessibility.AccessibilityManager
import com.lmreader.core.model.PixelRect
import com.lmreader.core.model.ScreenApplicationWindow
import com.lmreader.core.model.ScreenApplicationWindows
import com.lmreader.core.model.ScreenTranslationMode
import com.lmreader.di.AppContainer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Window/scroll hints only. Normal touch gestures are delivered unchanged to the application. */
class ScreenAccessibilityService : AccessibilityService() {
    private val applicationWindows = ScreenApplicationWindows()
    private val modalWindowIds = mutableSetOf<Int>()
    private var contentWindowId = -1
    internal var contentWindowAvailable = false
        private set
    internal var contentPackage = ""
        private set
    internal var contentOcclusions: List<PixelRect> = emptyList()
        private set
    internal fun windowDiagnostics() = "content=$contentWindowId/$contentPackage occlusions=$contentOcclusions windows=" + windows.map { window ->
        val bounds = Rect(); window.getBoundsInScreen(bounds)
        "${window.id}/${window.type}/${window.layer} active=${window.isActive} focused=${window.isFocused} bounds=$bounds"
    }
    internal var systemExclusions: List<PixelRect> = emptyList()
        private set
    private fun refreshSystemExclusions() {
        // Cache actual visible window regions when windows change. Reading
        // accessibility windows on every following frame would add binder work.
        systemExclusions = buildList {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_SYSTEM && window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                if (Build.VERSION.SDK_INT >= 30 && window.displayId != android.view.Display.DEFAULT_DISPLAY) continue
                val bounds = Rect()
                if (Build.VERSION.SDK_INT >= 30) {
                    val region = Region(); window.getRegionInScreen(region)
                    val rectangles = RegionIterator(region)
                    while (rectangles.next(bounds)) if (!bounds.isEmpty) add(bounds.toPixelRect())
                } else {
                    window.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) add(bounds.toPixelRect())
                }
            }
        }
    }
    private fun refreshApplicationWindows(event: AccessibilityEvent? = null) {
        val applications = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
            (Build.VERSION.SDK_INT < 30 || it.displayId == android.view.Display.DEFAULT_DISPLAY) }
        modalWindowIds.retainAll(applications.map { it.id }.toSet())
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString()?.let { it.contains("Dialog", ignoreCase = true) || it.contains("PopupWindow", ignoreCase = true) } == true)
            modalWindowIds += event.windowId
        val choices = applications.mapNotNull { window ->
            val bounds = Rect(); window.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return@mapNotNull null
            val root = window.root
            val pkg = root?.packageName?.toString().orEmpty()
            @Suppress("DEPRECATION") root?.recycle()
            ScreenApplicationWindow(window.id, pkg, bounds.toPixelRect(), window.layer, window.isActive, window.isFocused,
                window.id in modalWindowIds)
        }
        if (AppContainer.from(this).screenTranslation.preferences.settings.value.mode != ScreenTranslationMode.BROWSE)
            applicationWindows.reset()
        val selection = applicationWindows.update(choices)
        contentWindowId = selection?.window?.id ?: -1
        contentWindowAvailable = choices.any { it.id == contentWindowId }
        contentPackage = selection?.window?.packageName.orEmpty()
        contentOcclusions = selection?.occlusions.orEmpty()
    }
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }
        instance = this
        refreshSystemExclusions()
        refreshApplicationWindows()
        AppContainer.from(this).screenTranslation.accessibilityChanged()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val controller = AppContainer.from(this).screenTranslation
        if (!controller.state.value.started || !controller.preferences.settings.value.usesAccessibility) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            refreshSystemExclusions(); refreshApplicationWindows(event)
        }
        if (event.packageName?.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> controller.interaction(event.windowId, action = true)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> controller.interaction(event.windowId, action = false)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> controller.scroll(event.windowId)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
                if (contentWindowId >= 0 && contentPackage.isNotBlank()) controller.windowChanged(contentWindowId, contentPackage)
        }
    }
    override fun onInterrupt() { AppContainer.from(this).screenTranslation.accessibilityInterrupted() }
    override fun onDestroy() {
        if (instance === this) instance = null
        AppContainer.from(this).screenTranslation.accessibilityChanged()
        AppContainer.from(this).screenTranslation.accessibilityInterrupted()
        super.onDestroy()
    }
    fun applicationWindow(): AccessibilityWindowInfo? {
        windows.firstOrNull { it.id == contentWindowId }?.let { return it }
        refreshApplicationWindows()
        return windows.firstOrNull { it.id == contentWindowId }
    }
    @androidx.annotation.RequiresApi(34)
    suspend fun capture(): CapturedScreen {
        check(Build.VERSION.SDK_INT >= 34) { "窗口截图需要 Android 14 或以上版本" }
        val window = applicationWindow() ?: throw ScreenCaptureException(ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)
        val bounds = Rect(); window.getBoundsInScreen(bounds)
        if (bounds.isEmpty) throw ScreenCaptureException(ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)
        return suspendCancellableCoroutine { continuation ->
            takeScreenshotOfWindow(window.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = try {
                        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace) ?: error("无法读取窗口截图")
                        try { hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("无法创建截图") } finally { hardware.recycle() }
                    } catch (failure: Throwable) { if (continuation.isActive) continuation.resumeWithException(failure); return }
                    finally { result.hardwareBuffer.close() }
                    continuation.resume(CapturedScreen(bitmap, bounds.toPixelRect(), window.id), onCancellation = { _, frame, _ -> frame.close() })
                }
                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resumeWithException(ScreenCaptureException(errorCode))
                }
            })
        }
    }
    companion object {
        @Volatile var instance: ScreenAccessibilityService? = null; private set
        /** Permission may be enabled before Android finishes binding the service. */
        fun isEnabled(context: Context): Boolean {
            if (context.getSystemService(AccessibilityManager::class.java)
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.let { service ->
                    service.packageName == context.packageName && service.name == ScreenAccessibilityService::class.java.name
                }
            }) return true
            val enabled = try { Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) }
                catch (_: SecurityException) { null }
            val component = ComponentName(context, ScreenAccessibilityService::class.java)
            return enabled?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
        }
    }
}

internal fun Rect.toPixelRect() = PixelRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
class ScreenCaptureException(val code: Int) : IllegalStateException(when (code) {
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截图过于频繁，正在等待下一帧"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "当前窗口正在切换，正在等待下一帧"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "系统暂时无法截图，正在重试"
    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前应用禁止截图"
    else -> "无法截取当前窗口，请重新启动屏幕翻译"
})
