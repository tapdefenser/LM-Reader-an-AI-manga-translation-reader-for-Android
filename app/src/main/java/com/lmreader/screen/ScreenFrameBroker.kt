package com.lmreader.screen

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

internal class ScreenContentTemporarilyOccluded : IllegalStateException("底层窗口暂时被弹窗遮挡")

/** All recognition/tracking requests share one clean source and one window-screenshot rate gate. */
internal class ScreenFrameBroker(private val projection: ProjectionScreenCapture?,
    private val settings: () -> ScreenTranslationSettings, private val overlay: () -> ScreenOverlay?) : AutoCloseable {
    private val sourceLock = Mutex()
    private val windowLock = Mutex()
    private var lastWindowRequest = -334L
    private class WindowFrame(val frame: CapturedScreen) {
        var gray: ScreenGrayFrame? = null
        private val references = java.util.concurrent.atomic.AtomicInteger(1)
        fun borrow(epoch: Long): CapturedScreen {
            references.incrementAndGet()
            return CapturedScreen(frame.bitmap, frame.bounds, frame.windowId, (epoch shl 40) + frame.sequence, frame.capturedAt) { release() }
        }
        fun release() { if (references.decrementAndGet() == 0) frame.close() }
    }
    private var cachedWindow: WindowFrame? = null
    private var cleanProjection = false
    private var projectionChecked = false
    private var nextProjectionCheck = 0L
    private var projectedBounds: PixelRect? = null
    private var windowSequence = 0L
    private var sourceEpoch = 1L
    private var closed = false
    val backend get() = if (cleanProjection) "单应用录屏流" else if (settings().usesAccessibility && Build.VERSION.SDK_INT >= 34) "无障碍窗口截图" else "手动录屏截图"
    val streaming get() = cleanProjection && projection?.available == true && projection.contentVisible
    fun invalidate() { sourceEpoch++; projectionChecked = false; nextProjectionCheck = 0; cleanProjection = false; projectedBounds = null; cachedWindow?.release(); cachedWindow = null }
    private fun assist() = checkNotNull(ScreenAccessibilityService.instance) { "浏览和视频模式需要启用无障碍辅助" }

    private suspend fun window(fresh: Boolean = false): CapturedScreen = windowLock.withLock {
        check(!closed)
        check(Build.VERSION.SDK_INT >= 34) { "当前系统没有干净窗口截图能力，请使用手动模式" }
        val service = assist()
        val id = service.applicationWindow()?.id
        if (!service.contentWindowAvailable && service.contentOcclusions.isNotEmpty()) throw ScreenContentTemporarilyOccluded()
        cachedWindow?.takeIf { !fresh && it.frame.windowId == id && SystemClock.uptimeMillis() - it.frame.capturedAt < 334 }?.let { return@withLock it.borrow(sourceEpoch) }
        repeat(3) {
            delay((lastWindowRequest + 334 - SystemClock.uptimeMillis()).coerceAtLeast(0))
            lastWindowRequest = SystemClock.uptimeMillis()
            try {
                if (!service.contentWindowAvailable && service.contentOcclusions.isNotEmpty()) throw ScreenContentTemporarilyOccluded()
                val captured = if (Build.VERSION.SDK_INT >= 34) service.capture() else error("当前系统不支持窗口截图")
                if (service.applicationWindow()?.id != captured.windowId) { captured.close(); throw ScreenCaptureException(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) }
                val frame = CapturedScreen(captured.bitmap, captured.bounds, captured.windowId, ++windowSequence, captured.capturedAt) { captured.close() }
                check(!closed) { frame.close(); "屏幕截图已停止" }
                cachedWindow?.release(); cachedWindow = WindowFrame(frame)
                return@withLock cachedWindow!!.borrow(sourceEpoch)
            } catch (failure: ScreenCaptureException) {
                if (failure.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) {
                    service.applicationWindow() // refresh a transition which overtook this request
                    if (!service.contentWindowAvailable && service.contentOcclusions.isNotEmpty()) throw ScreenContentTemporarilyOccluded()
                }
                if (failure.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && failure.code != AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW || it == 2) throw failure
            }
        }
        error("无法取得窗口截图")
    }

    private suspend fun chooseContinuousSource() = sourceLock.withLock {
        check(!closed)
        assist()
        check(Build.VERSION.SDK_INT >= 34) { "浏览和视频模式需要单应用录屏或 Android 14 以上的窗口截图，请使用手动模式" }
        if (cleanProjection || projectionChecked || SystemClock.uptimeMillis() < nextProjectionCheck) return@withLock
        nextProjectionCheck = SystemClock.uptimeMillis() + 2000
        val revision = sourceEpoch
        val source = projection?.takeIf { it.available && it.contentVisible } ?: return@withLock
        overlay()?.awaitDraw()
        val reference = window()
        try {
            val raw = try { source.capture() } catch (failure: ScreenFrameUnavailable) { return@withLock }
            try {
                // A full-display grant includes our button. Never turn it into a
                // continuous hide/capture/show loop, even if its resolution looks correct.
                if (overlay()?.containsOwnButton(raw) == true) { if (revision == sourceEpoch) projectionChecked = true; return@withLock }
                val ratio = raw.bitmap.width.toFloat() / raw.bitmap.height
                val expected = reference.bitmap.width.toFloat() / reference.bitmap.height
                if (abs(ratio / expected - 1) > .03f) return@withLock
                val mapped = CapturedScreen(raw.bitmap, reference.bounds, reference.windowId) { }
                if (overlay()?.containsOwnButton(mapped) == true) { if (revision == sourceEpoch) projectionChecked = true; return@withLock }
                val agrees = withContext(Dispatchers.Default) {
                    val a = reference.gray(); val b = raw.gray()
                    // These captures are asynchronous. A pan/pinch between them
                    // is not evidence that the selected app stream is unsafe.
                    a.difference(b) < 3f || ScreenMotionTracker.estimate(a, b)?.let { pose ->
                        (ScreenMotionTracker.predictionError(a, b, pose) ?: 100f) < 8f
                    } == true
                }
                if (agrees && revision == sourceEpoch) { sourceEpoch++; cleanProjection = true; projectedBounds = reference.bounds }
            } finally { raw.close() }
        } finally { reference.close() }
    }

    suspend fun capture(monitoring: Boolean = false, fresh: Boolean = false, after: Long = -1): CapturedScreen {
        check(!closed)
        val config = settings()
        if (config.requiresAccessibility) {
            chooseContinuousSource()
            if (streaming) {
                val bounds = projectedBounds!!; val sourceStamp = sourceEpoch
                val raw = try { checkNotNull(projection).capture() } catch (failure: ScreenFrameUnavailable) {
                    deferProjection()
                    return window(fresh)
                }
                if (!assist().contentWindowAvailable && assist().contentOcclusions.isNotEmpty()) {
                    raw.close(); throw ScreenContentTemporarilyOccluded()
                }
                if (overlay()?.containsOwnButton(raw) == true) {
                    raw.close(); cleanProjection = false; sourceEpoch++
                    return window()
                }
                return CapturedScreen(raw.bitmap, bounds, assist().applicationWindow()?.id ?: -1,
                    (sourceStamp shl 40) + raw.sequence, raw.capturedAt) { raw.close() }
            }
            return window()
        }
        if (config.usesAccessibility && Build.VERSION.SDK_INT >= 34 && ScreenAccessibilityService.instance != null) return window(fresh)
        val source = checkNotNull(projection) { "屏幕截图授权已失效，请重新启动屏幕翻译" }
        if (monitoring) return source.capture(after) // post-publication baseline and later probes see the same visible overlay
        overlay()?.visible(false)
        return try { overlay()?.awaitDraw(); source.capture(after = source.sequence) }
        finally { overlay()?.visible(true) }
    }

    suspend fun motion(): CapturedMotion {
        check(!closed); chooseContinuousSource()
        if (streaming) {
            val bounds = projectedBounds!!; val sourceStamp = sourceEpoch
            val frame = try { checkNotNull(projection).motion(emptyList()) } catch (failure: ScreenFrameUnavailable) {
                deferProjection()
                return windowMotion()
            }
            if (!assist().contentWindowAvailable && assist().contentOcclusions.isNotEmpty()) throw ScreenContentTemporarilyOccluded()
            return frame.copy(bounds = bounds, sequence = (sourceStamp shl 40) + frame.sequence)
                .excluding(overlay()?.systemExclusions().orEmpty() + assist().contentOcclusions)
        }
        return windowMotion()
    }
    private fun deferProjection() {
        cleanProjection = false; projectedBounds = null; sourceEpoch++
        projectionChecked = false; nextProjectionCheck = SystemClock.uptimeMillis() + 2000
        android.util.Log.w("ScreenTranslation", "Capture stream unavailable; using accessibility window screenshots")
    }
    private suspend fun windowMotion(): CapturedMotion {
        val frame = window()
        try {
            val cached = cachedWindow?.takeIf { it.frame.bitmap === frame.bitmap }
            // Converting the recognition bitmap must never hold the source lock
            // or prevent tiny streaming motion samples from being acquired.
            val gray = cached?.gray ?: withContext(Dispatchers.Default) { frame.gray() }.also { cached?.gray = it }
            return CapturedMotion(gray, frame.bounds, frame.sequence, frame.capturedAt)
                .excluding(overlay()?.systemExclusions().orEmpty() + assist().contentOcclusions)
        }
        finally { frame.close() }
    }
    override fun close() { closed = true; cachedWindow?.release(); cachedWindow = null }
}
