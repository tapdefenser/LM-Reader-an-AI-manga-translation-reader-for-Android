package com.lmreader.core.storage.settings

import com.lmreader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ScreenTranslationPreferencesTest {
    @Test fun completeSettingsRoundTripIncludingIndependentScreenResources() {
        val translation = MangaTranslationSettings(sourceLanguage = "ja", targetLanguage = "zh-Hans", workflowId = "fixture", apiProfileId = "override", customStyle = "polite",
            segThreshold = .4f, textDetectionThreshold = .6f, freeTextMergeGapRatio = .7f, segTextScope = SegTextScope.FREE_TEXT,
            bubbleFillMode = BubbleFillMode.WHITE, bubbleOpacityPercent = 72, bubbleTextPaddingPercent = 11, bubbleFont = BubbleFont.SERIF,
            bubbleFontScalePercent = 123, bubbleBold = true, freeTextMaskExpansionPercent = 12)
        val settings = ScreenTranslationSettings(ScreenTranslationMode.VIDEO, translation, VisionExecutionSettings(3, 4, false, OcrBackend.NNAPI),
            256, true, 150, 340, 90, 80, 1920, 120, 50, 95, 8, 60, 48, ScreenTrackingAlgorithm.GLOBAL)
        assertEquals(settings, ScreenTranslationPreferences.decode(ScreenTranslationPreferences.encode(settings)))
    }

    @Test fun legacyFollowAndPrefetchOptionsDoNotChangeOtherSettings() {
        val old = org.json.JSONObject(ScreenTranslationPreferences.encode(ScreenTranslationSettings()))
            .put("mode", "MANGA").put("follow", "unknown-old-mode").put("prefetch", false).put("sample", 200)
        old.remove("version")
        val migrated = ScreenTranslationPreferences.decode(old.toString())
        assertEquals(ScreenTranslationMode.BROWSE, migrated.mode)
        assertEquals(200, migrated.sampleIntervalMillis)
        assertTrue(migrated.requiresAccessibility)
        assertEquals(ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS, migrated.trackingAlgorithm)
        val written = ScreenTranslationPreferences.encode(migrated)
        assertFalse(org.json.JSONObject(written).has("follow"))
        assertFalse(org.json.JSONObject(written).has("prefetch"))
    }

    @Test fun malformedCaptureConfigReturnsValidDefaults() {
        assertEquals(ScreenTranslationSettings(), ScreenTranslationPreferences.decode("invalid"))
        val json = org.json.JSONObject(ScreenTranslationPreferences.encode(ScreenTranslationSettings())).put("videoTop", 99).put("videoBottom", 10)
        assertEquals(ScreenTranslationSettings(), ScreenTranslationPreferences.decode(json.toString()))
        assertEquals(ScreenTranslationSettings(), ScreenTranslationPreferences.decode(null))
    }
    @Test fun unknownTrackingAlgorithmPreservesSavedLanguagesAndIntervals() {
        val json = org.json.JSONObject(ScreenTranslationPreferences.encode(ScreenTranslationSettings(sampleIntervalMillis = 200)))
            .put("trackingAlgorithm", "future-value")
        val decoded = ScreenTranslationPreferences.decode(json.toString())
        assertEquals(200, decoded.sampleIntervalMillis)
        assertEquals(ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS, decoded.trackingAlgorithm)
    }
    @Test fun presetsAndBothModesRoundTripWithIndependentOrientations() {
        val browse = ScreenRegionSelection("manga", listOf(ScreenNormalizedRegion(.1f, .2f, .8f, .9f)))
        val video = ScreenRegionSelection("subtitles", landscape = listOf(ScreenNormalizedRegion(.2f, .6f, .9f, .95f)))
        val settings = ScreenTranslationSettings(browseRegions = browse, videoRegions = video, regionPresets = listOf(browse, video))
        val decoded = ScreenTranslationPreferences.decode(ScreenTranslationPreferences.encode(settings))
        assertEquals(browse, decoded.browseRegions); assertEquals(video, decoded.videoRegions)
        assertEquals(settings.regionPresets, decoded.regionPresets)
    }
}
