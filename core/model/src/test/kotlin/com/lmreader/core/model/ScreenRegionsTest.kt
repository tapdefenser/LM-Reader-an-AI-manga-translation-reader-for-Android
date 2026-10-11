package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test

class ScreenRegionsTest {
    @Test fun orientationsHaveSeparateRegionsAndAnEmptyOrientationMeansFullScreen() {
        val portrait = PixelRect(0f, 0f, 1000f, 2000f); val landscape = PixelRect(0f, 0f, 2000f, 1000f)
        val scheme = ScreenRegionSelection("reading", listOf(ScreenNormalizedRegion(.1f, .2f, .8f, .9f)))
        assertEquals(listOf(PixelRect(100f, 400f, 800f, 1800f)), scheme.areas(portrait, portrait))
        assertEquals(listOf(landscape), scheme.areas(landscape, landscape))
    }
    @Test fun modesLoadCopiesAndSavingALibraryPresetDoesNotChangeTheirSelections() {
        val browse = ScreenRegionSelection("browse", listOf(ScreenNormalizedRegion(.1f, .1f, .8f, .8f)))
        val video = ScreenRegionSelection("video", landscape = listOf(ScreenNormalizedRegion(.1f, .6f, .9f, .9f)))
        val original = ScreenTranslationSettings(browseRegions = browse, videoRegions = video, regionPresets = listOf(browse, video))
        val loaded = original.withRegionSelection(ScreenTranslationMode.BROWSE, video)
        assertEquals(video, loaded.browseRegions); assertEquals(video, loaded.videoRegions)
        val changed = loaded.copy(regionPresets = listOf(video.copy(landscape = emptyList())))
        assertEquals(video, changed.browseRegions); assertEquals(video, changed.videoRegions)
        assertEquals(browse, original.browseRegions)
    }
    @Test fun overlappingRectanglesFormAnExactUnionInsideTheActualAppWindow() {
        val display = PixelRect(0f, 0f, 1000f, 2000f)
        val scheme = ScreenRegionSelection(portrait = listOf(ScreenNormalizedRegion(0f, 0f, .6f, .5f),
            ScreenNormalizedRegion(.4f, .25f, 1f, .75f)))
        val areas = scheme.areas(display, PixelRect(100f, 100f, 900f, 1800f))
        assertEquals(850_000f, areas.sumOf { it.area.toDouble() }.toFloat(), 1f)
        areas.forEachIndexed { i, area -> areas.drop(i + 1).forEach { assertFalse(area.intersects(it)) } }
        assertTrue(areas.all { it.left >= 100 && it.top >= 100 && it.right <= 900 && it.bottom <= 1800 })
        assertTrue(scheme.areas(display, PixelRect(0f, 1600f, 1000f, 1800f)).isEmpty())
    }
    @Test fun ninthRectangleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ScreenRegionSelection(portrait = List(9) { ScreenNormalizedRegion(.1f, .1f, .2f, .2f) }) }
    }
}
