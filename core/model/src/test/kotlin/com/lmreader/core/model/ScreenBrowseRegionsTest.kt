package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test

class ScreenBrowseRegionsTest {
    private val screen = PixelRect(0f, 0f, 400f, 800f)
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
