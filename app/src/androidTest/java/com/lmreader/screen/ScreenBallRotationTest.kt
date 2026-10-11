package com.lmreader.screen

import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.MainActivity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Requires ordinary overlay access on the local test device; no translation engines are started. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class ScreenBallRotationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context get() = instrumentation.targetContext
    private val manager get() = context.getSystemService(WindowManager::class.java)

    @Suppress("DEPRECATION")
    private suspend fun rotate(rotation: Int) {
        assertTrue(automation.setRotation(rotation))
        withTimeout(8_000) {
            while (withContext(Dispatchers.Main) { manager.defaultDisplay.rotation != rotation }) delay(50)
        }
        delay(500) // Allow both the system's new insets and the delayed docking repair.
    }

    private suspend fun checkBounds(overlay: ScreenOverlay, left: Boolean, tucked: Boolean) {
        val bounds = withContext(Dispatchers.Main) { overlay.buttonBounds() }
        val metrics = manager.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val width = metrics.bounds.width(); val height = metrics.bounds.height()
        assertTrue("$bounds outside ${metrics.bounds}", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height)
        assertEquals(if (left) insets.left.toFloat() else (width - insets.right).toFloat(),
            if (left) bounds.left else bounds.right, 3f)
        val diameter = (48 * context.resources.displayMetrics.density).toInt()
        assertEquals(if (tucked) (diameter / 3).toFloat() else diameter.toFloat(), bounds.width, 1f)
        val expectedY = insets.top + .8f * (height - insets.top - insets.bottom - diameter)
        assertEquals(expectedY, bounds.top, 3f)
    }

    private fun tap(overlay: ScreenOverlay) {
        lateinit var bounds: com.lmreader.core.model.PixelRect
        instrumentation.runOnMainSync { bounds = overlay.buttonBounds() }
        val now = SystemClock.uptimeMillis()
        val x = (bounds.left + bounds.right) / 2; val y = (bounds.top + bounds.bottom) / 2
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0)
            try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    @Test fun rotationKeepsBothEdgesAndTuckedTargetsVisibleAndClickable() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use { verifyRotation(context, trusted = false) }
    }

    @Test fun accessibilityOverlayKeepsBothEdgesAndTuckedTargetsVisibleAndClickable() = runBlocking<Unit> {
        val configured = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        // Only rebind this test app, never disable another accessibility service.
        org.junit.Assume.assumeTrue(configured == "${context.packageName}/${ScreenAccessibilityService::class.java.name}")
        val originalEnabled = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                shell("settings put secure enabled_accessibility_services null")
                shell("settings put secure accessibility_enabled 0")
                delay(100)
                shell("settings put secure enabled_accessibility_services $configured")
                shell("settings put secure accessibility_enabled 1")
                withTimeout(15_000) { while (ScreenAccessibilityService.instance == null) delay(50) }
                verifyRotation(checkNotNull(ScreenAccessibilityService.instance), trusted = true)
            } finally {
                shell("settings put secure enabled_accessibility_services $configured")
                shell("settings put secure accessibility_enabled $originalEnabled")
            }
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private suspend fun verifyRotation(overlayContext: Context, trusted: Boolean) {
        val prefs = context.getSharedPreferences("screen-ball-position", Context.MODE_PRIVATE)
        val original = prefs.all
        @Suppress("DEPRECATION") val originalRotation = manager.defaultDisplay.rotation
        val autoRotation = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val userRotation = Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
        try {
            for (left in listOf(false, true)) for (tucked in listOf(false, true)) {
                    rotate(UiAutomation.ROTATION_FREEZE_0)
                    prefs.edit().putFloat("x", if (left) .02f else .98f).putFloat("y", .8f).putBoolean("tucked", tucked).commit()
                    var clicks = 0
                    val overlay = withContext(Dispatchers.Main) {
                        ScreenOverlay(overlayContext, trusted) { clicks++ }.also { it.buttonState(false, true, false, false, true) }
                    }
                    try {
                        rotate(UiAutomation.ROTATION_FREEZE_90)
                        checkBounds(overlay, left, tucked)
                        rotate(UiAutomation.ROTATION_FREEZE_0)
                        checkBounds(overlay, left, tucked)
                        tap(overlay)
                        if (tucked) { delay(200); tap(overlay) }
                        withTimeout(3_000) { while (withContext(Dispatchers.Main) { clicks == 0 }) delay(20) }
                        assertEquals(1, clicks)
                    } finally { withContext(Dispatchers.Main) { overlay.close() } }
            }
        } finally {
            withContext(Dispatchers.Main) {
                val restored = prefs.edit()
                for (key in listOf("x", "y", "tucked")) when (val value = original[key]) {
                    is Float -> restored.putFloat(key, value)
                    is Boolean -> restored.putBoolean(key, value)
                    else -> restored.remove(key)
                }
                restored.commit()
            }
            automation.setRotation(originalRotation)
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
            shell("settings put system user_rotation $userRotation")
            shell("settings put system accelerometer_rotation $autoRotation")
        }
    }
}
