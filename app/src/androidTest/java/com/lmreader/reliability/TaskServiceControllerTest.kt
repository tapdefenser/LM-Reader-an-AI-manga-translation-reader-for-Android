package com.lmreader.reliability

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskServiceController
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaskServiceControllerTest {
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        container = AppContainer(IsolatedApp(ApplicationProvider.getApplicationContext()))
        container.startupReady.await()
    }
    @After fun cleanup() = runBlocking { closeContainer(container) }
    @Test fun serviceStopCancelsWritersAndPersistsPauseInBothQueues() = runBlocking(Dispatchers.IO) {
        lateinit var controller: TaskServiceController
        var starts = 0
        controller = TaskServiceController(container) { starts++; controller.serviceReady() }
        controller.setVisible(true)
        val acquired = CompletableDeferred<Unit>()
        val writer = launch {
            val lease = controller.acquire()
            try { acquired.complete(Unit); awaitCancellation() } finally { controller.release(lease) }
        }
        acquired.await()
        assertFalse(controller.stopIfIdle { error("active writer") })
        controller.setVisible(false)
        assertTrue(controller.canStart())
        controller.serviceStopped("deadline")
        writer.join()
        assertTrue(writer.isCancelled); assertTrue(container.translationQueue.paused.value); assertTrue(container.exportQueue.paused.value)
        assertFalse(controller.canStart()); controller.setVisible(true); controller.allowRetry(); assertTrue(controller.canStart())
        assertEquals(1, starts); assertEquals(0, controller.leases.value)
    }
    @Test fun deniedForegroundStartDoesNotAdmitWriters() = runBlocking(Dispatchers.IO) {
        val controller = TaskServiceController(container) { throw IllegalStateException("platform denied") }
        controller.setVisible(true)
        var refused = false
        try { controller.acquire() } catch (_: Exception) { refused = true }
        assertTrue(refused); assertFalse(controller.canStart()); assertEquals(0, controller.leases.value); assertNotNull(controller.failure.value)
    }
    @Test fun notificationResumeRequiresAnAcknowledgedForegroundOwnerAndRecoversAFailedStart() = runBlocking {
        var starts = 0
        val controller = TaskServiceController(container) { starts++; throw IllegalStateException("initial denial") }
        controller.setVisible(true)
        runCatching { controller.acquire() }
        controller.setVisible(false)
        val owner = Any()
        assertTrue(runCatching { controller.allowNotificationResume(owner) }.isFailure)
        assertFalse(controller.canStart())
        controller.serviceReady(owner)
        controller.allowNotificationResume(owner)
        val lease = controller.acquire()
        assertEquals(1, starts); assertTrue(controller.canStart()); assertEquals(1, controller.leases.value)
        controller.release(lease)
        assertTrue(controller.stopIfIdle {})
        assertFalse(controller.canStart())
    }
}
