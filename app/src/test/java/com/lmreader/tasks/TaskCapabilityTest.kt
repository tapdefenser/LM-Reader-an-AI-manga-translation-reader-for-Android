package com.lmreader.tasks

import com.lmreader.ui.navigation.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class TaskCapabilityTest {
    @Test fun capabilityReasonsMapToTheirSettingsRoutes() {
        assertEquals(Routes.SETTINGS_API_ASR, capabilitySettingsRoute(TaskCapability.ASR.reason))
        assertEquals(Routes.SETTINGS_API_LOCAL, capabilitySettingsRoute(TaskCapability.TRANSLATION.reason))
        assertEquals(Routes.SETTINGS_API_OCR_LOCAL, capabilitySettingsRoute(TaskCapability.VISION.reason))
        assertEquals(Routes.SETTINGS_API, capabilitySettingsRoute(TaskCapability.API.reason))
    }

    @Test fun legacyAndNonCapabilityReasonsMapOnlyWhenRecognized() {
        assertEquals(Routes.SETTINGS_API, capabilitySettingsRoute("CAPABILITY"))
        assertEquals(Routes.SETTINGS_API, capabilitySettingsRoute("API_ACCESS"))
        assertNull(capabilitySettingsRoute("SYSTEM_LIMIT"))
        assertNull(capabilitySettingsRoute("REMOTE_UNKNOWN"))
        assertNull(capabilitySettingsRoute(null))
    }

    @Test fun requireTaskCapabilityPreservesCancellation() = runTest {
        val cancellation = CancellationException("cancelled")
        try {
            requireTaskCapability(TaskCapability.ASR, "Transcribe") { throw cancellation }
            fail("Expected CancellationException")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test fun requireTaskCapabilityPreservesExistingWaiting() = runTest {
        val waiting = TaskWaiting("SYSTEM_LIMIT", "服务暂不可用")
        try {
            requireTaskCapability(TaskCapability.API, "Request") { throw waiting }
            fail("Expected TaskWaiting")
        } catch (actual: TaskWaiting) {
            assertSame(waiting, actual)
        }
    }

    @Test fun requireTaskCapabilityWrapsFailureWithCapabilityContext() = runTest {
        val cause = IllegalStateException("boom")
        try {
            requireTaskCapability(TaskCapability.TRANSLATION, "Translate") { throw cause }
            fail("Expected TaskWaiting")
        } catch (actual: TaskWaiting) {
            assertEquals(TaskCapability.TRANSLATION.reason, actual.reason)
            assertEquals("Translate：boom", actual.message)
            assertSame(cause, actual.cause)
        }
    }
}
