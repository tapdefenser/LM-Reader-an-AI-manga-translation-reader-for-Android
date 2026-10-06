package com.lmreader.tasks

import android.app.NotificationManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import com.lmreader.MainActivity
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

/** Run first on a fresh isolated test installation, before granting notifications for other cases. */
@SdkSuppress(minSdkVersion = 33)
class TaskNotificationPermissionTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private fun denyButton(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if(node == null) return null
        if(node.viewIdResourceName?.endsWith("/permission_deny_button") == true) return node
        for(index in 0 until node.childCount) denyButton(node.getChild(index))?.let { return it }
        return null
    }
    @Test fun firstTaskRequestsPermissionOnceAndDenialDoesNotStopBackgroundWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = context.getSystemService(NotificationManager::class.java)
        assumeFalse("Run this case before granting the isolated app notification permission", manager.areNotificationsEnabled())
        val container = AppContainer.from(context)
        container.startupReady.await()
        val preferences = context.getSharedPreferences("task-notification-permission", 0)
        preferences.edit().putBoolean("requested", false).commit()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val acquired = CompletableDeferred<Unit>()
        val holder = scope.launch {
            val lease = container.taskService.acquire()
            try { acquired.complete(Unit); awaitCancellation() } finally { container.taskService.release(lease) }
        }
        try {
            withTimeout(15_000) { acquired.await() }
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val deny = withTimeout(15_000) {
                var button: AccessibilityNodeInfo? = null
                while(button == null) { button = denyButton(automation.rootInActiveWindow); if(button == null) delay(100) }
                button
            }
            assertTrue(preferences.getBoolean("requested", false))
            assertTrue(deny.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            delay(1000)
            assertFalse(manager.areNotificationsEnabled())
            assertTrue(holder.isActive); assertEquals(1, container.taskService.leases.value)
            assertNull(denyButton(automation.rootInActiveWindow))
        } finally { holder.cancelAndJoin(); scope.cancel() }
    }
}
