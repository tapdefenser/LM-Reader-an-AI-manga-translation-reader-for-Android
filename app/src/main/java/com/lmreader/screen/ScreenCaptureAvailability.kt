package com.lmreader.screen

import android.accessibilityservice.AccessibilityService
import android.os.Build
import kotlinx.coroutines.withTimeoutOrNull

internal class ScreenFrameUnavailable : IllegalStateException("暂时没有新的屏幕画面，正在重试")

/** An acquisition deadline is recoverable; cancellation of the caller still propagates. */
internal suspend fun <T : Any> awaitScreenFrame(timeoutMillis: Long = 2000, acquire: suspend () -> T): T =
    withTimeoutOrNull(timeoutMillis) { acquire() } ?: throw ScreenFrameUnavailable()

internal fun Throwable.isTemporaryScreenCaptureFailure(): Boolean {
    if (this is ScreenFrameUnavailable) return true
    if (this !is ScreenCaptureException || Build.VERSION.SDK_INT < 30) return false
    return code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ||
        code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ||
        Build.VERSION.SDK_INT >= 34 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW
}
