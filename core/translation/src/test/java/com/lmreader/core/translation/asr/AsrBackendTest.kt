package com.lmreader.core.translation.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrBackendTest {
    @Test fun backendWireIdsAreStableAndUnknownFallsBackToAuto() {
        assertEquals(AsrBackend.AUTO, AsrBackend.fromWire(null))
        assertEquals(AsrBackend.AUTO, AsrBackend.fromWire(""))
        assertEquals(AsrBackend.AUTO, AsrBackend.fromWire("something-new"))
        assertEquals(AsrBackend.CPU, AsrBackend.fromWire("cpu"))
        assertEquals(AsrBackend.VULKAN, AsrBackend.fromWire(" Vulkan "))
        assertEquals(AsrBackend.OPENCL, AsrBackend.fromWire("opencl"))
        assertEquals("VULKAN", AsrBackend.VULKAN.wireId)
    }

    @Test fun sessionIdentityIncludesBackendAndThreads() {
        val cpu = AsrSessionKey("whisper-base-multilingual", "80da2d8", "a".repeat(64), AsrBackend.CPU, 2)
        val vulkan = cpu.copy(backend = AsrBackend.VULKAN)
        val fourThreads = cpu.copy(threads = 4)
        assertTrue(cpu.identity.contains("/CPU/2"))
        assertTrue(vulkan.identity.contains("/VULKAN/2"))
        assertTrue(fourThreads.identity.contains("/CPU/4"))
        assertFalse(cpu == vulkan)
        assertFalse(cpu == fourThreads)
    }

    @Test fun capabilitiesParseCompiledButUnavailableBackendHonestly() {
        val capabilities = AsrRuntimeCapabilities.parse(
            """
            {"backends":[
              {"id":"CPU","compiled":true,"available":true,"devices":[
                {"name":"CPU","description":"whisper.cpp CPU","type":"CPU","memoryFreeBytes":1,"memoryTotalBytes":2}
              ]},
              {"id":"VULKAN","compiled":true,"available":true,"devices":[
                {"name":"Vulkan0","description":"Adreno","type":"GPU","memoryFreeBytes":0,"memoryTotalBytes":0}
              ]},
              {"id":"OPENCL","compiled":false,"available":false,"devices":[],"reason":"未编译"}
            ]}
            """.trimIndent(),
        )
        assertEquals(3, capabilities.backends.size)
        assertEquals("Adreno", capabilities.availableDevice(AsrBackend.VULKAN)?.description)
        assertTrue(capabilities[AsrBackend.CPU]?.available == true)
        assertFalse(capabilities[AsrBackend.OPENCL]?.compiled == true)
        assertFalse(capabilities[AsrBackend.OPENCL]?.available == true)
        assertEquals("未编译", capabilities[AsrBackend.OPENCL]?.reason)
        assertNull(capabilities.availableDevice(AsrBackend.OPENCL))
    }

    @Test fun loadInfoKeepsRequestedAndActualBackendSeparate() {
        val info = AsrLoadedSessionInfo.parse(
            """
            {"requestedBackend":"AUTO","actualBackend":"CPU","device":"CPU",
             "deviceDescription":"whisper.cpp CPU","fallbackReason":"Vulkan：初始化失败"}
            """.trimIndent(),
        )
        assertEquals(AsrBackend.AUTO, info.requestedBackend)
        assertEquals(AsrBackend.CPU, info.actualBackend)
        assertTrue(info.usedCpuFallback)
        assertEquals("Vulkan：初始化失败", info.fallbackReason)

        val gpu = AsrLoadedSessionInfo.parse(
            """
            {"requestedBackend":"VULKAN","actualBackend":"VULKAN","device":"Vulkan0",
             "deviceDescription":"Adreno","fallbackReason":null}
            """.trimIndent(),
        )
        assertEquals(AsrBackend.VULKAN, gpu.actualBackend)
        assertFalse(gpu.usedCpuFallback)
        assertNull(gpu.fallbackReason)
    }

    @Test fun vulkanRestrictionOnlyBlocksConfirmedDevice() {
        assertNotNull(asrBackendRestriction(AsrBackend.VULKAN, "24031PN0DC"))
        assertNotNull(asrBackendRestriction(AsrBackend.VULKAN, "24031pn0dc"))
        assertNull(asrBackendRestriction(AsrBackend.AUTO, "24031PN0DC"))
        assertNull(asrBackendRestriction(AsrBackend.CPU, "24031PN0DC"))
        assertNull(asrBackendRestriction(AsrBackend.OPENCL, "24031PN0DC"))
        assertNull(asrBackendRestriction(AsrBackend.VULKAN, "unrelated-model"))
    }
}
