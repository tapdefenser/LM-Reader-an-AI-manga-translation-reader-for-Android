package com.lmreader.ui.video

import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.BubbleFont
import com.lmreader.core.model.BubbleRenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VideoFrameSettingsTest {
    @Test fun defaultsMatchComicAndScreenLegacyValues() {
        val settings = VideoFrameSettings()
        assertEquals(.35f, settings.segThreshold, 0f)
        assertEquals(.35f, settings.textDetectionThreshold, 0f)
        assertEquals(.45f, settings.freeTextMergeGapRatio, 0f)
        assertEquals(60, settings.similarityPercent)
        assertEquals(BubbleRenderSettings(), settings.render)
    }

    @Test fun customValuesAreAcceptedAcrossTheFullComicRanges() {
        val settings = VideoFrameSettings(
            segThreshold = 0f,
            textDetectionThreshold = 1f,
            freeTextMergeGapRatio = 2f,
            similarityPercent = 1,
            render = BubbleRenderSettings(
                fillMode = BubbleFillMode.WHITE,
                opacityPercent = 100,
                textPaddingPercent = 20,
                font = BubbleFont.SERIF,
                fontScalePercent = 150,
                bold = true,
                freeTextMaskExpansionPercent = 20,
            ),
        )
        assertEquals(0f, settings.segThreshold, 0f)
        assertEquals(1f, settings.textDetectionThreshold, 0f)
        assertEquals(2f, settings.freeTextMergeGapRatio, 0f)
        assertEquals(1, settings.similarityPercent)
        assertEquals(BubbleFont.SERIF, settings.render.font)
    }

    @Test fun outOfRangeValuesFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { VideoFrameSettings(segThreshold = 1.1f) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameSettings(segThreshold = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameSettings(textDetectionThreshold = -.1f) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameSettings(freeTextMergeGapRatio = 2.1f) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameSettings(similarityPercent = 0) }
        assertThrows(IllegalArgumentException::class.java) { VideoFrameSettings(similarityPercent = 101) }
        assertThrows(IllegalArgumentException::class.java) {
            VideoFrameSettings(render = BubbleRenderSettings(opacityPercent = 101))
        }
    }
}
