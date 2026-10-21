package com.lmreader.ui.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.translation.asr.AsrBackend
import com.lmreader.core.translation.asr.AsrRuntimeCapabilities
import com.lmreader.core.translation.asr.WhisperNative
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native readiness and optional-driver failure handling; no model or recording is needed. */
@RunWith(AndroidJUnit4::class)
class AsrBackendNativeTest {
    @Test fun packagedNativeBackendsReportActualAvailabilityWithoutAModel() {
        val capabilities = AsrRuntimeCapabilities.parse(WhisperNative.capabilities())
        assertEquals(setOf(AsrBackend.CPU, AsrBackend.VULKAN, AsrBackend.OPENCL), capabilities.backends.map { it.backend }.toSet())
        assertTrue(capabilities[AsrBackend.CPU]!!.compiled)
        assertTrue(capabilities[AsrBackend.CPU]!!.available)
        assertTrue(capabilities[AsrBackend.VULKAN]!!.compiled)
        assertTrue(capabilities[AsrBackend.OPENCL]!!.compiled)
        for(capability in capabilities.backends) {
            if(capability.available) assertTrue(capability.devices.isNotEmpty())
            else assertFalse(capability.reason.isNullOrBlank())
        }
        // An absent OpenCL vendor driver must not prevent the CPU runtime from loading.
        assertThrows(IllegalArgumentException::class.java) {
            WhisperNative.create("/nonexistent/lmreader-fixture-model.bin", "CPU")
        }
    }
}
