package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ScreenBrowseCoverageTrackerTest {
    private val bounds = PixelRect(0f, 0f, 192f, 256f)
    private fun art(seed: Long = 143): ScreenGrayFrame {
        val random = java.util.Random(seed)
        val grid = IntArray(25 * 33) { random.nextInt(220) + 18 }
        return ScreenGrayFrame(192, 256, ByteArray(192 * 256) { i ->
            val x = i % 192; val y = i / 192; val l = x / 8; val t = y / 8
            val u = (x % 8) / 8f; val v = (y % 8) / 8f
            ((grid[t * 25 + l] * (1 - u) + grid[t * 25 + l + 1] * u) * (1 - v) +
                (grid[(t + 1) * 25 + l] * (1 - u) + grid[(t + 1) * 25 + l + 1] * u) * v).roundToInt().toByte()
        })
    }
    private fun warp(frame: ScreenGrayFrame, pose: ScreenTransform): ScreenGrayFrame {
        val inverse = pose.inverse()
        return frame.copy(pixels = ByteArray(frame.pixels.size) { i ->
            val p = inverse.point(PixelPoint((i % frame.width).toFloat(), (i / frame.width).toFloat()))
            if (p.x < 0 || p.y < 0 || p.x >= frame.width - 1 || p.y >= frame.height - 1) 238.toByte()
            else {
                val l = floor(p.x).toInt(); val t = floor(p.y).toInt(); val u = p.x - l; val v = p.y - t
                ((frame.at(l, t) * (1 - u) + frame.at(l + 1, t) * u) * (1 - v) +
                    (frame.at(l, t + 1) * (1 - u) + frame.at(l + 1, t + 1) * u) * v).roundToInt().toByte()
            }
        })
    }
    @Test fun changedBlockNeedsTwoDistinctStableFramesAndReleasesItsCoverage() {
        val tracker = ScreenBrowseCoverageTracker()
        tracker.add(listOf(bounds), art(), bounds, ScreenTransform(), 1)
        val unrelated = art(872)
        assertTrue(tracker.evaluate(unrelated, bounds, ScreenTransform(), 2, true).isEmpty())
        assertTrue(tracker.evaluate(unrelated, bounds, ScreenTransform(), 2, true).isEmpty())
        val changed = tracker.evaluate(unrelated, bounds, ScreenTransform(), 3, true)
        assertEquals(listOf(bounds), changed)
        val coverage = ScreenCoverage().apply { add(bounds, ScreenTransform()) }
        coverage.removeContent(changed)
        assertEquals(listOf(bounds), coverage.exposed(bounds, ScreenTransform()))
        assertTrue(tracker.evaluate(unrelated, bounds, ScreenTransform(), 4, true).isEmpty())
    }
    @Test fun panZoomAndUniformTintDoNotClearCoverageIncludingMostlyOffscreenBlocks() {
        val original = art()
        for (pose in listOf(ScreenTransform(1.12f, -15f, -28f), ScreenTransform(1.1f, -100f, -120f))) {
            val tracker = ScreenBrowseCoverageTracker()
            tracker.add(listOf(bounds), original, bounds, ScreenTransform(), 1)
            val moved = warp(original, pose)
            val tinted = moved.copy(pixels = ByteArray(moved.pixels.size) { i ->
                ((moved.pixels[i].toInt() and 255) * .35f + 40).roundToInt().toByte()
            })
            for (sequence in 2L..4L) assertTrue("pose=$pose", tracker.evaluate(tinted, bounds, pose, sequence, true).isEmpty())
            val unrelated = art(872)
            assertTrue(tracker.evaluate(unrelated, bounds, pose, 5, true).isEmpty())
            assertEquals(listOf(bounds), tracker.evaluate(unrelated, bounds, pose, 6, true))
        }
    }
    @Test fun unreliableMotionAndOcclusionResetWholeBlockRejection() {
        val tracker = ScreenBrowseCoverageTracker()
        tracker.add(listOf(bounds), art(), bounds, ScreenTransform(), 1)
        val unrelated = art(872)
        assertTrue(tracker.evaluate(unrelated, bounds, ScreenTransform(), 2, true).isEmpty())
        assertTrue(tracker.evaluate(unrelated, bounds, ScreenTransform(), 3, false).isEmpty())
        val masked = unrelated.copy(valid = ByteArray(unrelated.pixels.size) { i -> if (i % 192 < 48) 1 else 0 })
        assertTrue(tracker.evaluate(masked, bounds, ScreenTransform(), 4, true).isEmpty())
        assertTrue(tracker.evaluate(unrelated, bounds, ScreenTransform(), 5, true).isEmpty())
        assertEquals(listOf(bounds), tracker.evaluate(unrelated, bounds, ScreenTransform(), 6, true))
    }
    @Test fun aSmallUnchangedToolbarDoesNotHideAnOtherwiseChangedBlock() {
        val original = art(); val changed = art(872)
        original.pixels.copyInto(changed.pixels, endIndex = 192 * 64)
        val tracker = ScreenBrowseCoverageTracker()
        tracker.add(listOf(bounds), original, bounds, ScreenTransform(), 1)
        assertTrue(tracker.evaluate(changed, bounds, ScreenTransform(), 2, true).isEmpty())
        assertEquals(listOf(bounds), tracker.evaluate(changed, bounds, ScreenTransform(), 3, true))
    }
}
