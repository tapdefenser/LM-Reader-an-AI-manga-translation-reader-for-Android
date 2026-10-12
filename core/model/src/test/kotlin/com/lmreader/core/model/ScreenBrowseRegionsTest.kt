package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test

class ScreenBrowseRegionsTest {
    private val screen = PixelRect(0f, 0f, 400f, 800f)
    @Test fun fragmentedOwnershipIsPackedWhileThinLShapedExposuresStayBounded() {
        val cut = screenExposedRegions(screen, PixelRect(80f, 200f, 230f, 400f))
        assertEquals(listOf(screen), screenBrowseRecognitionCrops(cut, listOf(screen), 0f))
        val strips = listOf(PixelRect(0f, 700f, 400f, 800f), PixelRect(300f, 0f, 400f, 700f))
        assertEquals(2, screenBrowseRecognitionCrops(strips, listOf(screen), 0f).size)
        val thin = listOf(PixelRect(0f, 700f, 8f, 800f), PixelRect(8f, 700f, 16f, 800f))
        assertEquals(listOf(PixelRect(0f, 700f, 16f, 800f)), screenBrowseRecognitionCrops(thin, listOf(screen), 0f))
    }
    @Test fun contextNeverBridgesTwoIndependentSelectedAreas() {
        val scopes = listOf(PixelRect(0f, 0f, 80f, 800f), PixelRect(320f, 0f, 400f, 800f))
        assertEquals(scopes, screenBrowseRecognitionCrops(listOf(screen), scopes, 64f))
    }
    @Test fun mergeMembershipDoesNotFollowTheBoundingRectangle() {
        val fresh = listOf(ScreenSourceRegion("new", PixelRect(100f, 600f, 200f, 660f)))
        val old = listOf(ScreenSourceRegion("near", PixelRect(100f, 530f, 200f, 595f)),
            ScreenSourceRegion("far", PixelRect(5f, 600f, 50f, 700f)))
        val groups = screenBrowseMergeGroups(fresh, old, 8f)
        assertEquals(listOf("near"), groups.single().old.map { it.id })
        assertFalse(groups.single().bounds.intersects(old[1].bounds))
    }
    @Test fun zeroThresholdIncludesTouchingButNotSeparatedSourceFrames() {
        val fresh = listOf(ScreenSourceRegion("new", PixelRect(100f, 300f, 200f, 350f)))
        val old = listOf(ScreenSourceRegion("touch", PixelRect(100f, 250f, 200f, 300f)),
            ScreenSourceRegion("gap", PixelRect(210f, 300f, 300f, 350f)))
        assertEquals(setOf("touch"), screenBrowseMergeGroups(fresh, old, 0f).single().old.map { it.id }.toSet())
        assertEquals(setOf("touch", "gap"), screenBrowseMergeGroups(fresh, old, 10f).single().old.map { it.id }.toSet())
    }
    @Test fun movedAndZoomedSourceGeometryDeterminesMergeMembership() {
        val old = PixelRect(100f, 300f, 200f, 350f)
        val pose = ScreenTransform(1.5f, -50f, -450f)
        val moved = pose.bounds(old)
        val new = ScreenSourceRegion("new", PixelRect(moved.left, moved.bottom, moved.right, moved.bottom + 50f))
        assertTrue(screenBrowseMergeGroups(listOf(new), listOf(ScreenSourceRegion("old", old)), 0f).single().old.isEmpty())
        assertEquals(listOf("old"), screenBrowseMergeGroups(listOf(new), listOf(ScreenSourceRegion("old", moved)), 0f).single().old.map { it.id })
    }
    @Test fun unchangedReuseRequiresBothGeometryAndStrongVisibleAppearance() {
        val bounds = PixelRect(30f, 40f, 200f, 100f)
        val good = ScreenObjectTemplate.Similarity(0f, .99f, 1f)
        assertTrue(screenSameSourceRegion(bounds, bounds, good))
        assertFalse(screenSameSourceRegion(bounds, bounds, good.copy(correlation = .8f)))
        assertFalse(screenSameSourceRegion(bounds, bounds, good.copy(visible = .7f)))
        assertFalse(screenSameSourceRegion(bounds, bounds.offset(100f, 0f), good))
        assertFalse(screenSameSourceRegion(bounds, bounds, null))
    }
    @Test fun replacementsWaitForEveryDetectedFragmentAndNeverDeleteForEmptyOutput() {
        val a = ScreenSourceRegion("old-a", PixelRect(0f, 0f, 100f, 100f))
        val b = ScreenSourceRegion("old-b", PixelRect(200f, 200f, 300f, 300f))
        val crop = ScreenMergedCrop(screen, setOf(a.id, b.id), replacedSources = listOf(a, b))
        val detected = listOf(ScreenSourceRegion("a1", PixelRect(0f, 0f, 100f, 50f)),
            ScreenSourceRegion("a2", PixelRect(0f, 50f, 100f, 100f)), ScreenSourceRegion("b1", b.bounds))
        assertTrue(screenReadyReplacements(crop, emptyList(), emptySet()).isEmpty())
        assertTrue(screenReadyReplacements(crop, detected, emptySet()).isEmpty())
        assertEquals(setOf("old-b"), screenReadyReplacements(crop, detected, setOf("a1", "b1")))
        assertEquals(setOf("old-a", "old-b"), screenReadyReplacements(crop, detected, setOf("a1", "a2", "b1")))
    }
    @Test fun mergedPixelsIncludeTransitiveOldRegionsAndOtherNewCrops() {
        val old = listOf(ScreenSourceRegion("a", PixelRect(80f, 450f, 190f, 600f)),
            ScreenSourceRegion("b", PixelRect(180f, 400f, 250f, 470f)),
            ScreenSourceRegion("unrelated", PixelRect(10f, 20f, 70f, 80f)))
        val crops = screenMergedCrops(listOf(PixelRect(100f, 580f, 200f, 680f), PixelRect(230f, 420f, 300f, 450f)), old, screen, 8f)
        assertEquals(1, crops.size)
        assertEquals(setOf("a", "b"), crops.single().replaces)
        assertEquals(PixelRect(80f, 400f, 308f, 688f), crops.single().bounds)
    }
    @Test fun mergeNeverInventsPixelsBeyondTheCleanFrameAndUsesSourceGeometry() {
        val crop = screenMergedCrops(listOf(PixelRect(0f, 0f, 400f, 50f)),
            listOf(ScreenSourceRegion("partly-visible", PixelRect(80f, -120f, 300f, 120f))), screen, 32f).single()
        assertEquals(PixelRect(0f, 0f, 400f, 120f), crop.bounds)
        assertTrue(crop.replaces.isEmpty())
        assertEquals(listOf("partly-visible"), crop.preserves.map { it.id })
        val separate = screenMergedCrops(listOf(PixelRect(0f, 700f, 80f, 800f)),
            listOf(ScreenSourceRegion("source", PixelRect(100f, 650f, 200f, 750f))), screen, 0f)
        assertTrue(separate.single().replaces.isEmpty())
    }
    @Test fun oldPendingResultsCannotResurrectMergedTextButCanPublishElsewhere() {
        val order = ScreenRegionPublication()
        val merged = PixelRect(100f, 300f, 300f, 600f)
        assertTrue("An unfinished newer crop does not hide old output", order.accepts(merged, 1))
        order.commit(merged, 2)
        assertFalse(order.accepts(PixelRect(120f, 500f, 220f, 650f), 1))
        assertTrue(order.accepts(PixelRect(0f, 0f, 100f, 100f), 1))
        assertTrue(order.accepts(merged, 2))
        assertTrue("Screen-fixed ownership is independent", order.accepts(merged, 1, screenFixed = true))
        order.commit(merged, 3, screenFixed = true)
        assertFalse(order.accepts(merged, 2, screenFixed = true))
        order.clear(); assertTrue(order.accepts(merged, 1))
    }
    @Test fun continuousMotionDoesNotRequireFingerReleaseAndOnlyRecognitionWaits() {
        val gate = ScreenBrowseStability()
        repeat(20) { frame ->
            gate.observe(frame.toLong(), frame * 30L, moved = true, good = true)
            assertFalse(gate.ready(frame * 30L, 180, 1200))
        }
        // Holding a finger still is irrelevant to the measured content motion.
        gate.observe(20, 600, moved = false, good = true)
        assertEquals(150L, gate.waitMillis(600, 180))
        assertFalse(gate.ready(749, 180, 1200)); assertTrue(gate.ready(750, 180, 1200))
        gate.observe(21, 760, moved = false, good = false)
        assertFalse(gate.ready(1000, 180, 1200))
        gate.observe(22, 1010, moved = false, good = true)
        assertTrue(gate.ready(1010, 180, 1200))
        gate.observe(22, 2500, moved = false, good = true) // no new image from a static app
        assertTrue(gate.ready(2500, 180, 1200))
    }
    @Test fun fixedUiPixelsAreNotMarkedAsRecognizedContentAfterScrolling() {
        val coverage = ScreenCoverage()
        coverage.add(screen, ScreenTransform())
        val fixedSource = PixelRect(0f, 700f, 400f, 800f)
        coverage.removeContent(listOf(fixedSource))
        val pose = ScreenTransform(y = -100f)
        val exposed = coverage.exposed(screen, pose).flatMap { screenExposedRegions(it, fixedSource) }
        assertTrue(exposed.contains(PixelRect(0f, 600f, 400f, 700f)))
        assertFalse(exposed.any { it.intersects(fixedSource) })
    }
}
