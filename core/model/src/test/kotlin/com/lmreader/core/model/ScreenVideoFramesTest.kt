package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test

class ScreenVideoFramesTest {
    private val area = PixelRect(0f, 0f, 400f, 800f)
    private fun pattern(seed: Int) = ScreenVideoAppearance.capture { x, y ->
        if ((x * 71 + y * 139 + seed * (x + 13) * (y + 7)) % 17 < 5) 20 else 235
    }!!
    private fun region(id: String, seed: Int = 1, bounds: PixelRect = PixelRect(20f, 40f, 180f, 120f)) =
        ScreenVideoRegion(id, RegionKind.FREE_TEXT, bounds, pattern(seed))

    @Test fun fastNextSegWaitsOnlyTheRemainingDelayFromThePrecedingSeg() {
        val clock = ScreenVideoCadence(200)
        clock.captured(); clock.segmented(100)
        assertEquals(0L, clock.waitMillis(100)) // first SEG opens the second frame immediately
        clock.captured(); clock.segmented(150)
        assertEquals(150L, clock.waitMillis(150))
        assertEquals(0L, clock.waitMillis(300))
    }
    @Test fun slowNextSegUsesItsCompletionWithoutAddingAnotherFullDelay() {
        val clock = ScreenVideoCadence(200)
        clock.captured(); clock.segmented(100); clock.captured()
        clock.segmented(500)
        assertEquals(0L, clock.waitMillis(500))
    }
    @Test fun expiredDelayCannotOpenAnOccupiedTwoFrameWindow() {
        val clock = ScreenVideoCadence(200)
        val window = ScreenChapterWindow()
        val first = window.admit(1, ScreenTransform(), listOf(area)); clock.captured()
        first.preprocessingFinished(); clock.segmented(100)
        val second = window.admit(1, ScreenTransform(), listOf(area)); clock.captured()
        second.preprocessingFinished(); clock.segmented(150)
        assertEquals(0L, clock.waitMillis(900))
        assertFalse(window.canAdmit(true))
        window.complete(first, inOrder = true)
        assertTrue(window.canAdmit(true)); assertEquals(0L, clock.waitMillis(900))
    }
    @Test fun finishedSecondFrameKeepsItsSlotUntilTheFirstFrameIsFilled() {
        val window = ScreenChapterWindow()
        val first = window.admit(1, ScreenTransform(), listOf(area)); first.preprocessingFinished()
        val second = window.admit(1, ScreenTransform(), listOf(area)); second.preprocessingFinished()
        window.complete(second, inOrder = true)
        assertEquals(2, window.size); assertFalse(window.canAdmit(true))
        window.complete(first, inOrder = true)
        assertEquals(0, window.size); assertTrue(window.canAdmit(true))
    }
    @Test fun browseCompletionOrderIsNotChangedByVideoFifoRetirement() {
        val window = ScreenChapterWindow()
        val first = window.admit(1, ScreenTransform(), listOf(area)); first.preprocessingFinished()
        val second = window.admit(1, ScreenTransform(), listOf(area))
        window.complete(second)
        assertEquals(1, window.size); assertTrue(window.canAdmit(true))
    }
    @Test fun resettingSceneRemovesItsOldSegDeadline() {
        val clock = ScreenVideoCadence(200)
        clock.captured(); clock.segmented(100); clock.captured()
        clock.reset()
        assertEquals(0L, clock.waitMillis(110))
    }
    @Test fun identicalCorrespondingSegRegionReusesItsIdentityAfterASmallMove() {
        val previous = region("old")
        val fresh = region("fresh", bounds = previous.bounds.offset(4f, 3f))
        assertEquals(mapOf("fresh" to previous), screenVideoRegionMatches(listOf(previous), listOf(fresh), 60))
    }
    @Test fun backgroundOrRectangleAgreementCannotHideChangedSourceText() {
        val previous = region("old")
        assertTrue(screenVideoRegionMatches(listOf(previous), listOf(region("changed", 5)), 60).isEmpty())
        assertTrue(screenVideoRegionMatches(listOf(previous), listOf(previous.copy(id = "flat", appearance = null)), 60).isEmpty())
    }
    @Test fun identicalTextInAnotherLocationDoesNotStealTheOldBubble() {
        val previous = region("old")
        val elsewhere = region("fresh", bounds = previous.bounds.offset(0f, 300f))
        assertTrue(screenVideoRegionMatches(listOf(previous), listOf(elsewhere), 60).isEmpty())
    }
    @Test fun oneOldBubbleCanOnlyReplaceOneNewDetection() {
        val previous = region("old")
        val matches = screenVideoRegionMatches(listOf(previous), listOf(region("a"), region("b")), 60)
        assertEquals(1, matches.size); assertEquals(setOf("a"), matches.keys)
    }
    @Test fun similaritySettingIsIndependentAndOneHundredDisablesReuse() {
        assertEquals(60, ScreenTranslationSettings().videoSimilarityPercent)
        assertTrue(screenVideoRegionMatches(listOf(region("old")), listOf(region("fresh")), 100).isEmpty())
        assertEquals(4, ScreenTranslationSettings(videoSimilarityPercent = 95).changeThresholdPercent)
    }
    @Test fun radicallyDifferentSegContoursAreNotReusedEvenWithIdenticalSourceStrokes() {
        val previous = region("old")
        val b = previous.bounds
        val triangle = region("fresh").copy(contour = listOf(PixelPoint(b.left, b.top), PixelPoint(b.left + b.width / 4, b.top), PixelPoint(b.left, b.bottom)))
        assertTrue(screenVideoRegionMatches(listOf(previous), listOf(triangle), 60).isEmpty())
    }
    @Test fun retainedDrawingMovesToNewFrameBoundsWithoutChangingItsCoordinates() {
        val from = PixelRect(10f, 20f, 110f, 60f)
        val to = PixelRect(100f, 200f, 300f, 280f)
        assertEquals(to, screenVideoRegionTransform(from, to).bounds(from))
    }
}
