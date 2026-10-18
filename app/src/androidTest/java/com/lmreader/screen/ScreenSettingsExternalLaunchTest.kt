package com.lmreader.screen

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lmreader.MainActivity
import com.lmreader.R
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.io.File

/** Actual cross-app windows and notification taps: a dialog must never raise the reader task. */
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
class ScreenSettingsExternalLaunchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

    private suspend fun waitFor(message: String, condition: suspend () -> Boolean) {
        try { withTimeout(10_000) { while (!condition()) delay(50) } }
        catch (_: TimeoutCancellationException) { fail(message) }
    }

    private suspend fun dialogs() = withContext(Dispatchers.Main) {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<ScreenTranslationSettingsActivity>().toList()
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun visibleWindow(component: String): Boolean {
        val windows = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows"))
            .use { String(it.readBytes(), Charsets.UTF_8) }
        // A modal dialog can hide the background app from accessibility's interactive-window list
        // while its surface is still visible. Inspect the real WindowManager state instead.
        return windows.split(Regex("(?m)(?=^  Window #\\d+ Window\\{)")).any { window ->
            window.lineSequence().firstOrNull()?.contains(component) == true &&
                Regex("(?m)^\\s+isOnScreen=true$").containsMatchIn(window) &&
                Regex("(?m)^\\s+isVisible=true$").containsMatchIn(window)
        }
    }

    private fun touch(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try { assertTrue(automation.injectInputEvent(event, true)) } finally { event.recycle() }
    }

    private fun findText(node: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? {
        node ?: return null
        if (node.text?.toString() == text) return node
        for (index in 0 until node.childCount) findText(node.getChild(index), text)?.let { return it }
        return null
    }

    private fun evidence(name: String) {
        val directory = File(context.getExternalFilesDir(null), "external-screen-settings").apply { mkdirs() }
        automation.takeScreenshot()?.let { bitmap ->
            try { File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
        for (service in listOf("window windows", "activity activities")) {
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys $service")).use {
                File(directory, "$name-${service.substringBefore(' ')}.log").writeBytes(it.readBytes())
            }
        }
    }

    @Test fun notificationOpensOverOtherAppWithReaderInBackground() = runBlocking<Unit> {
        verifyOutside(warmReader = true, fromBall = false)
    }

    @Test fun longPressOpensOverOtherAppWithReaderInBackground() = runBlocking<Unit> {
        verifyOutside(warmReader = true, fromBall = true)
    }

    @Test fun notificationDoesNotCreateReaderWhenOnlyTheExternalAppIsOpen() = runBlocking<Unit> {
        verifyOutside(warmReader = false, fromBall = false)
    }

    private suspend fun verifyOutside(warmReader: Boolean, fromBall: Boolean) {
        val application = context.applicationContext as Application
        val mainStarts = AtomicInteger()
        val observer = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (activity is MainActivity) mainStarts.incrementAndGet() }
            override fun onActivityResumed(activity: Activity) { if (activity is MainActivity) mainStarts.incrementAndGet() }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        val notifications = context.getSystemService(NotificationManager::class.java)
        val channel = "external-settings-regression"
        val existingChannel = notifications.getNotificationChannel(channel)
        val ballPreferences = context.getSharedPreferences("screen-ball-position", Context.MODE_PRIVATE)
        val ballPosition = ballPreferences.all
        var main: ActivityScenario<MainActivity>? = null
        var mainTask = -1
        var overlay: ScreenOverlay? = null
        AppContainer.from(context).startupReady.await()
        try {
            if (warmReader) {
                main = ActivityScenario.launch(MainActivity::class.java)
                main.onActivity { mainTask = it.taskId }
            } else {
                withContext(Dispatchers.Main) {
                    Stage.entries.filter { it != Stage.DESTROYED }.flatMap {
                        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList()
                    }.filterIsInstance<MainActivity>().forEach { it.finish() }
                }
            }
            val externalPackage = instrumentation.context.packageName
            context.startActivity(Intent().setComponent(ComponentName(externalPackage, ScreenFixtureActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            waitFor("the separate fixture app did not open") {
                automation.rootInActiveWindow?.packageName?.toString() == externalPackage
            }
            val evidenceName = "${if (warmReader) "warm" else "cold"}-${if (fromBall) "ball" else "notification"}"
            evidence("$evidenceName-before")
            application.registerActivityLifecycleCallbacks(observer)
            if (fromBall) {
                ballPreferences.edit().putFloat("x", .5f).putFloat("y", .5f).putBoolean("tucked", false).commit()
                overlay = withContext(Dispatchers.Main) {
                    ScreenOverlay(context, false, { AppContainer.from(context).screenTranslation.openSettings(context) }, {})
                        .also { it.buttonState(false, true, false, false, true) }
                }
                delay(300)
                val bounds = withContext(Dispatchers.Main) { checkNotNull(overlay).buttonBounds() }
                val x = (bounds.left + bounds.right) / 2f
                val y = (bounds.top + bounds.bottom) / 2f
                val down = SystemClock.uptimeMillis()
                touch(MotionEvent.ACTION_DOWN, x, y, down)
                try {
                    delay(ViewConfiguration.getLongPressTimeout().toLong() + 100)
                    waitFor("the ball did not open the settings dialog") { dialogs().isNotEmpty() }
                } finally { touch(MotionEvent.ACTION_UP, x, y, down) }
                withContext(Dispatchers.Main) { checkNotNull(overlay).close(); overlay = null }
            } else {
                notifications.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_DEFAULT))
                val title = "Screen settings external launch regression"
                notifications.notify(7319, NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.lmreader_ic_task).setContentTitle(title)
                    .setContentIntent(ScreenTranslationSettingsActivity.pendingIntent(context)).setAutoCancel(true).build())
                shell("cmd statusbar expand-notifications")
                var bounds: Rect? = null
                waitFor("the settings notification did not appear") {
                    findText(automation.rootInActiveWindow, title)?.let { node ->
                        bounds = Rect().also(node::getBoundsInScreen); true
                    } ?: false
                }
                val down = SystemClock.uptimeMillis()
                val target = checkNotNull(bounds)
                touch(MotionEvent.ACTION_DOWN, target.exactCenterX(), target.exactCenterY(), down)
                touch(MotionEvent.ACTION_UP, target.exactCenterX(), target.exactCenterY(), down)
                waitFor("the notification did not open the settings dialog") { dialogs().isNotEmpty() }
            }
            waitFor("the notification shade did not close over the settings dialog") {
                automation.rootInActiveWindow?.packageName?.toString() == context.packageName
            }
            delay(350) // Wait for the notification launch animation before saving visual evidence.
            val dialog = dialogs().single()
            withContext(Dispatchers.Main) {
                assertTrue("the dialog must be the root of its own task", dialog.isTaskRoot)
                if (warmReader) assertNotEquals("the reader task was reused", mainTask, dialog.taskId)
            }
            assertEquals("opening settings must not start/resume MainActivity", 0, mainStarts.get())
            evidence("$evidenceName-dialog")
            waitFor("the external app disappeared behind the dialog") {
                visibleWindow("$externalPackage/${ScreenFixtureActivity::class.java.name}")
            }
            assertFalse("the reader window must remain hidden", visibleWindow("${context.packageName}/${MainActivity::class.java.name}"))
            withContext(Dispatchers.Main) { AppContainer.from(context).screenTranslation.openSettings(context) }
            delay(300)
            assertSame("reopening must reuse the existing dialog", dialog, dialogs().single())
            shell("input keyevent 4")
            waitFor("closing the dialog did not return to the external app") {
                dialogs().isEmpty() && automation.rootInActiveWindow?.packageName?.toString() == externalPackage
            }
            assertEquals("closing settings must not raise MainActivity", 0, mainStarts.get())
        } finally {
            application.unregisterActivityLifecycleCallbacks(observer)
            withContext(Dispatchers.Main) {
                overlay?.close()
                Stage.entries.filter { it != Stage.DESTROYED }.flatMap {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it).toList()
                }.filterIsInstance<ScreenTranslationSettingsActivity>().forEach { it.finish() }
            }
            notifications.cancel(7319)
            if (existingChannel == null) notifications.deleteNotificationChannel(channel)
            val restore = ballPreferences.edit()
            for (key in listOf("x", "y", "tucked")) when (val value = ballPosition[key]) {
                is Float -> restore.putFloat(key, value)
                is Boolean -> restore.putBoolean(key, value)
                else -> restore.remove(key)
            }
            restore.commit()
            main?.close()
        }
    }
}
