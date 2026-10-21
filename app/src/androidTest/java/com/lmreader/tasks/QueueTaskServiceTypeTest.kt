package com.lmreader.tasks

import android.content.pm.ServiceInfo
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Regression coverage for the Android 15+ promotion path: media processing is a real
 * service type (0x2000), not TYPE_NONE, and ordinary leases refresh the live type mask.
 */
@SdkSuppress(minSdkVersion = 35)
@RunWith(AndroidJUnit4::class)
class QueueTaskServiceTypeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer
    private lateinit var controller: TaskServiceController
    private val owner = "QueueTaskServiceTypeTest-${UUID.randomUUID()}"
    private val acquired = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun setup() = runBlocking {
        assumeTrue("Requires Android 15+", Build.VERSION.SDK_INT >= 35)
        container = AppContainer.from(ApplicationProvider.getApplicationContext())
        withTimeout(15_000) {
            container.startupReady.await()
            container.taskScheduler.ready.await()
        }
        controller = container.taskService
        assumeTrue("Expected an idle service", controller.leases.value == 0)
        assumeTrue(
            "Expected no nonterminal queue work",
            container.taskScheduler.tasks.value.none { it.state !in TaskScheduler.TERMINAL },
        )
        controller.setVisible(true, owner)
    }

    @After fun cleanup() {
        if (::controller.isInitialized) {
            acquired.forEach { controller.release(it) }
            controller.setVisible(false, owner)
        }
        scope.cancel()
    }

    @Test fun mediaOnlyLeaseIsMediaProcessingAndOrdinaryLeasesRefreshMixedTypes() = runBlocking {
        val media = acquireHeld(processMedia = true)
        awaitTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, controller.activeForegroundTypes)
        assertNotEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE, controller.activeForegroundTypes)

        val ordinary = acquireHeld(processMedia = false)
        awaitTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            controller.activeForegroundTypes,
        )

        controller.release(ordinary)
        awaitTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING, controller.activeForegroundTypes)

        controller.release(media)
        awaitIdle()
        assertEquals(0, controller.leases.value)
        assertEquals(0, controller.activeForegroundTypes)
    }

    @Test fun networkStageBindingRefreshesPureMediaToMixedAndBack() = runBlocking {
        var fakeTask: String? = null
        val media = acquireHeld(processMedia = true)
        awaitTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        try {
            fakeTask = UUID.randomUUID().toString()
            controller.bindTask(media, fakeTask)
            controller.networkStage(fakeTask, true)
            awaitTypes(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )

            controller.networkStage(fakeTask, false)
            awaitTypes(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        } finally {
            fakeTask?.let { controller.networkStage(it, false) }
        }
        controller.release(media)
        awaitIdle()
    }

    private suspend fun acquireHeld(processMedia: Boolean): String {
        val result = CompletableDeferred<String>()
        scope.launch {
            var lease: String? = null
            try {
                lease = controller.acquire(processMedia)
                result.complete(lease)
                awaitCancellation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                result.completeExceptionally(error)
            } finally {
                lease?.let(controller::release)
            }
        }
        return withTimeout(15_000) { result.await() }.also { acquired += it }
    }

    private suspend fun awaitTypes(expected: Int) = withTimeout(8_000) {
        while (controller.activeForegroundTypes != expected) delay(20)
    }

    private suspend fun awaitIdle() = withTimeout(8_000) {
        while (controller.leases.value != 0 || controller.activeForegroundTypes != 0) delay(20)
    }
}
