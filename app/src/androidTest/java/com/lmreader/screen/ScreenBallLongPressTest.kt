package com.lmreader.screen

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lmreader.MainActivity
import com.lmreader.core.model.PixelRect
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Drives the real overlay window with ordinary input injection; no translation engine is started. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class ScreenBallLongPressTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val context get() = instrumentation.targetContext
    private val longPressTimeout: Long get() = ViewConfiguration.getLongPressTimeout().toLong()

    private data class Press(val x: Float, val y: Float, val downTime: Long)

    private fun buttonBounds(overlay: ScreenOverlay): PixelRect {
        lateinit var bounds: PixelRect
        instrumentation.runOnMainSync { bounds = overlay.buttonBounds() }
        return bounds
    }

    private fun inject(action: Int, press: Press, x: Float = press.x, y: Float = press.y, requireDelivery: Boolean = true) {
        val event = MotionEvent.obtain(press.downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            val delivered = automation.injectInputEvent(event, true)
            if (requireDelivery) assertTrue("injected action $action", delivered)
        } finally { event.recycle() }
    }

    private fun pressDown(overlay: ScreenOverlay): Press {
        val bounds = buttonBounds(overlay)
        val press = Press((bounds.left + bounds.right) / 2f, (bounds.top + bounds.bottom) / 2f, SystemClock.uptimeMillis())
        inject(MotionEvent.ACTION_DOWN, press)
        return press
    }

    private suspend fun waitFor(message: String, condition: suspend () -> Boolean) {
        try {
            withTimeout(longPressTimeout + 4_000) { while (!condition()) delay(10) }
        } catch (_: TimeoutCancellationException) {
            fail(message)
        }
    }

    private suspend fun state(overlay: ScreenOverlay, inside: Boolean = false, ready: Boolean = true,
        busy: Boolean = false, active: Boolean = false, manual: Boolean = true) =
        withContext(Dispatchers.Main) { overlay.buttonState(inside, ready, busy, active, manual) }

    /** Uses the same preference save/restore shape as ScreenBallRotationTest. */
    private suspend fun withOverlay(x: Float = .5f, y: Float = .5f, tucked: Boolean = false,
        settings: () -> Unit = {}, tap: () -> Unit = {}, block: suspend (ScreenOverlay) -> Unit) {
        val prefs = context.getSharedPreferences("screen-ball-position", Context.MODE_PRIVATE)
        val original = prefs.all
        prefs.edit().putFloat("x", x).putFloat("y", y).putBoolean("tucked", tucked).commit()
        val overlay = withContext(Dispatchers.Main) {
            ScreenOverlay(context, false, settings, tap).also { it.buttonState(false, true, false, false, true) }
        }
        delay(200) // Let the overlay's first layout/inset callbacks settle before gesturing.
        try {
            block(overlay)
        } finally {
            withContext(Dispatchers.Main) { overlay.close() }
            val restored = prefs.edit()
            for (key in listOf("x", "y", "tucked")) when (val value = original[key]) {
                is Float -> restored.putFloat(key, value)
                is Boolean -> restored.putBoolean(key, value)
                else -> restored.remove(key)
            }
            restored.commit()
        }
    }

    @Test fun stationaryHoldFiresExactlyOnceBeforeReleaseAndSuppressesTap() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AtomicInteger()
            val taps = AtomicInteger()
            withOverlay(settings = { settings.incrementAndGet() }, tap = { taps.incrementAndGet() }) { overlay ->
                val press = pressDown(overlay)
                waitFor("long press did not fire while the finger was still down") { settings.get() == 1 }
                delay(150)
                assertEquals("long press must fire exactly once", 1, settings.get())
                inject(MotionEvent.ACTION_UP, press)
                delay(200)
                assertEquals("a consumed long press must not trigger a tap", 0, taps.get())
                assertEquals(1, settings.get())
            }
        }
    }

    @Test fun shortTapStillClicksAndDoesNotOpenSettings() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AtomicInteger()
            val taps = AtomicInteger()
            withOverlay(settings = { settings.incrementAndGet() }, tap = { taps.incrementAndGet() }) { overlay ->
                val press = pressDown(overlay)
                inject(MotionEvent.ACTION_UP, press)
                waitFor("a short tap must still click") { taps.get() == 1 }
                delay(longPressTimeout + 300)
                assertEquals("a short tap must not open settings", 0, settings.get())
                assertEquals(1, taps.get())
            }
        }
    }

    @Test fun dragBeyondTouchSlopCancelsPendingLongPress() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AtomicInteger()
            val taps = AtomicInteger()
            withOverlay(settings = { settings.incrementAndGet() }, tap = { taps.incrementAndGet() }) { overlay ->
                val press = pressDown(overlay)
                val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
                inject(MotionEvent.ACTION_MOVE, press, press.x + slop + 2f, press.y)
                delay(longPressTimeout + 300)
                assertEquals("a drag must cancel the pending long press", 0, settings.get())
                inject(MotionEvent.ACTION_UP, press)
                delay(200)
                assertEquals("a canceled long press must not trigger a tap", 0, taps.get())
            }
        }
    }

    @Test fun actionCancelCancelsPendingLongPress() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AtomicInteger()
            val taps = AtomicInteger()
            withOverlay(settings = { settings.incrementAndGet() }, tap = { taps.incrementAndGet() }) { overlay ->
                val press = pressDown(overlay)
                inject(MotionEvent.ACTION_CANCEL, press)
                delay(longPressTimeout + 300)
                assertEquals("ACTION_CANCEL must cancel the pending long press", 0, settings.get())
                assertEquals(0, taps.get())
            }
        }
    }

    @Test fun hidingOrClosingTheOverlayCancelsPendingLongPress() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val hiddenSettings = AtomicInteger()
            withOverlay(settings = { hiddenSettings.incrementAndGet() }) { overlay ->
                val press = pressDown(overlay)
                withContext(Dispatchers.Main) { overlay.visible(false) }
                delay(longPressTimeout + 300)
                assertEquals("hiding the overlay must cancel the pending long press", 0, hiddenSettings.get())
                inject(MotionEvent.ACTION_CANCEL, press, requireDelivery = false)
            }
            val closedSettings = AtomicInteger()
            withOverlay(settings = { closedSettings.incrementAndGet() }) { overlay ->
                val press = pressDown(overlay)
                withContext(Dispatchers.Main) { overlay.close() }
                delay(longPressTimeout + 300)
                assertEquals("closing the overlay must cancel the pending long press", 0, closedSettings.get())
                inject(MotionEvent.ACTION_CANCEL, press, requireDelivery = false)
            }
        }
    }

    @Test fun longPressWorksWhileStoppedLoadingOrManualBusy() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AtomicInteger()
            val taps = AtomicInteger()
            withOverlay(settings = { settings.incrementAndGet() }, tap = { taps.incrementAndGet() }) { overlay ->
                state(overlay, ready = false, busy = false, active = false, manual = true)
                var press = pressDown(overlay)
                waitFor("a stopped or loading ball must still open settings") { settings.get() == 1 }
                inject(MotionEvent.ACTION_UP, press)
                delay(150)

                state(overlay, ready = true, busy = true, active = false, manual = true)
                press = pressDown(overlay)
                waitFor("a manual-translation-busy ball must still open settings") { settings.get() == 2 }
                inject(MotionEvent.ACTION_UP, press)
                delay(200)
                assertEquals(2, settings.get())
                assertEquals(0, taps.get())
            }
        }
    }

    @Test fun tuckedBallLongPressOpensSettingsWithoutExpanding() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = AtomicInteger()
            val taps = AtomicInteger()
            withOverlay(tucked = true, settings = { settings.incrementAndGet() }, tap = { taps.incrementAndGet() }) { overlay ->
                val tab = ((48 * context.resources.displayMetrics.density).toInt() / 3).coerceAtLeast(1).toFloat()
                assertEquals(tab, buttonBounds(overlay).width, 1f)
                val press = pressDown(overlay)
                waitFor("a tucked ball long press must open settings") { settings.get() == 1 }
                inject(MotionEvent.ACTION_UP, press)
                delay(200)
                assertEquals("a consumed long press must not expand or tap a tucked ball", tab, buttonBounds(overlay).width, 1f)
                assertEquals(0, taps.get())
                assertEquals(1, settings.get())
            }
        }
    }

    @Test fun longPressOpensExistingSettingsActivityThroughController() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val container = AppContainer.from(context)
            container.startupReady.await()
            val taps = AtomicInteger()
            try {
                withOverlay(settings = { container.screenTranslation.openSettings(context) },
                    tap = { taps.incrementAndGet() }) { overlay ->
                    val press = pressDown(overlay)
                    waitFor("the existing settings activity did not open") { settingsActivities().isNotEmpty() }
                    inject(MotionEvent.ACTION_UP, press)
                    delay(200)
                    assertEquals(0, taps.get())
                }
            } finally {
                withContext(Dispatchers.Main) {
                    Stage.entries.filter { it != Stage.DESTROYED }.flatMap {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList()
                    }.filterIsInstance<ScreenTranslationSettingsActivity>().forEach { it.finish() }
                }
            }
        }
    }

    private suspend fun settingsActivities(): List<Activity> = withContext(Dispatchers.Main) {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<ScreenTranslationSettingsActivity>().toList()
    }
}
