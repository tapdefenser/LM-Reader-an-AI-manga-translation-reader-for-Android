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
import com.lmreader.di.AppContainer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Window/scroll hints only. Normal touch gestures are delivered unchanged to the application. */
class ScreenAccessibilityService : AccessibilityService() {
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
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }
        instance = this
        refreshSystemExclusions()
        AppContainer.from(this).screenTranslation.accessibilityChanged()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val controller = AppContainer.from(this).screenTranslation
        if (!controller.state.value.started || !controller.preferences.settings.value.usesAccessibility) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) refreshSystemExclusions()
        if (event.packageName?.toString() == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> controller.interaction(event.windowId, action = true)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> controller.interaction(event.windowId, action = false)
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> controller.scroll(event.windowId)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> controller.windowChanged(event.windowId, event.packageName?.toString().orEmpty())
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> applicationWindow()?.let { window ->
                val root = window.root
                val pkg = root?.packageName?.toString().orEmpty()
                @Suppress("DEPRECATION") root?.recycle()
                if (pkg.isNotBlank()) controller.windowChanged(window.id, pkg)
            }
        }
    }
    override fun onInterrupt() { AppContainer.from(this).screenTranslation.accessibilityInterrupted() }
    override fun onDestroy() {
        if (instance === this) instance = null
        AppContainer.from(this).screenTranslation.accessibilityChanged()
        AppContainer.from(this).screenTranslation.accessibilityInterrupted()
        super.onDestroy()
    }
    fun applicationWindow(): AccessibilityWindowInfo? = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        .firstOrNull { it.isActive } ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused }
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
    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前应用禁止截图"
    else -> "无法截取当前窗口，请重新启动屏幕翻译"
})
