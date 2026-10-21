package com.lmreader.ui.queue

import com.lmreader.core.model.*
import org.junit.Test
import kotlin.test.*

class ExportConfigurationTest {
    @Test fun capturedConfigurationSurvivesSerializationAndLaterStyleChanges() {
        val captured = ExportConfiguration(BubbleRenderSettings(BubbleFillMode.WHITE, 80, 12, fontScalePercent = 150, bold = true,
            freeTextMaskExpansionPercent = 14), "en", "zh-Hans")
        val persisted = captured.encode()
        val later = captured.copy(render = captured.render.copy(fontScalePercent = 80), target = "ja")
        assertEquals(captured, ExportConfiguration.decode(persisted))
        assertNotEquals(later, ExportConfiguration.decode(persisted))
    }

    @Test fun absentLanguageRemainsUnknownAndUnsupportedSnapshotIsRejected() {
        val captured = ExportConfiguration(BubbleRenderSettings())
        assertEquals(captured, ExportConfiguration.decode(captured.encode()))
        assertFails { ExportConfiguration.decode(captured.encode().replace("\"schema\":1", "\"schema\":9")) }
    }
}
