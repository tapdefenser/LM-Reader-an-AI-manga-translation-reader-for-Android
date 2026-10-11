package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ScreenTranslationTest {
    private val viewport = PixelRect(0f, 0f, 400f, 800f)

    @Test fun screenChapterWindowPrefetchesOnlyAfterAllVisionIsReadyAndKeepsBothInputs() {
        val window = ScreenChapterWindow()
        val first = window.admit(1, ScreenTransform(), listOf(viewport))
        assertFalse(window.canAdmit(true))
        first.consumerStarted()
        assertFalse("One region entering MT does not make the page ready", window.canAdmit(true))
        first.preprocessingFinished()
        assertFalse(window.canAdmit(false)) // oversize page remains intact
        assertEquals(listOf(viewport), window.reserved(1, ScreenTransform()))
        assertTrue(window.canAdmit(true))
        val next = window.admit(1, ScreenTransform(y = -200f), listOf(PixelRect(0f, 600f, 400f, 800f)))
        assertFalse(window.canAdmit(true))
        assertEquals(2, window.size)
        // A scene change makes old areas irrelevant to the new image without
        // releasing either unfinished workflow chapter.
        assertTrue(window.reserved(2, ScreenTransform()).isEmpty())
        assertEquals(2, window.size)
        assertTrue(window.reserved(1, ScreenTransform(2f)).contains(ScreenTransform(2f).bounds(viewport)))
        first.consumerFinished(); window.complete(first)
        assertEquals(listOf(PixelRect(0f, 800f, 400f, 1000f)), window.reserved(1, ScreenTransform()))
        assertFalse(window.canAdmit(true))
        next.preprocessingFinished(); assertTrue(window.canAdmit(true))
        window.complete(next)
        assertTrue(window.canAdmit(true)); assertEquals(0, window.size)
    }

    @Test fun interval200AndTranslation500WaitForSlotWithoutAnExtraInterval() {
        val clock = ScreenFrameCadence(200)
        val window = ScreenChapterWindow()
        assertEquals(0L, clock.waitMillis(0))
        val first = window.admit(1, ScreenTransform(), listOf(viewport)); clock.submitted(0)
        first.preprocessingFinished()
        assertEquals(100L, clock.waitMillis(100))
        assertEquals(0L, clock.waitMillis(200)); assertTrue(window.canAdmit(true))
        val second = window.admit(1, ScreenTransform(), listOf(viewport)); clock.submitted(200)
        second.preprocessingFinished()
        assertEquals(0L, clock.waitMillis(400)); assertFalse(window.canAdmit(true))
        window.complete(first)
        assertTrue(window.canAdmit(true)); assertEquals(0L, clock.waitMillis(500))
        // A fast next completion still respects the most recent submission.
        clock.submitted(500); window.complete(second)
        assertEquals(150L, clock.waitMillis(550))
    }

    @Test fun slowPreprocessingAndUnchangedProbesNeverCauseABusyCaptureLoop() {
        val clock = ScreenFrameCadence(200)
        val window = ScreenChapterWindow()
        val first = window.admit(1, ScreenTransform(), listOf(viewport)); clock.submitted(0)
        assertEquals(0L, clock.waitMillis(350)); assertFalse(window.canAdmit(true))
        first.preprocessingFinished(); assertTrue(window.canAdmit(true))
        assertEquals(0L, clock.waitMillis(350))
        clock.probed(350) // same image: no chapter admitted
        assertEquals(199L, clock.waitMillis(351)); assertEquals(0L, clock.waitMillis(550))
    }

    @Test fun publicationRejectsOutOfOrderFramesAndSceneChanges() {
        val order = ScreenPublicationOrder()
        assertTrue(order.accept(1, 2, 1))
        assertFalse(order.accept(1, 1, 1))
        assertFalse(order.accept(1, 3, 2))
        assertTrue(order.accept(2, 1, 2))
        assertFalse(order.accept(1, 100, 2))
    }

    @Test fun slowZoomAccumulatesWhileStationaryNoiseDoesNotBreathe() {
        val filter = ScreenMotionSmoother()
        val center = PixelPoint(200f, 400f)
        repeat(40) { i -> assertEquals(1f, filter.update(ScreenTransform(if (i % 2 == 0) 1.003f else .997f), center).scale, 0f) }
        var shown = ScreenTransform()
        repeat(30) { i -> shown = filter.update(ScreenTransform(1.001f.pow(i + 1), -i * 3f, -i * 5f), center) }
        assertTrue("Small per-frame zooms must not disappear forever", shown.scale > 1.02f)
        repeat(20) { shown = filter.update(ScreenTransform(1.04f, -90f, -150f), center) }
        assertEquals(1.04f, shown.scale, .001f)
    }

    @Test fun retainedBubblesUseAnExpandedScreenNotVisibleAreaRatio() {
        val retained = screenRetentionBounds(viewport, 25)
        assertEquals(PixelRect(-100f, -200f, 500f, 1000f), retained)
        assertTrue(PixelRect(20f, -210f, 180f, -190f).intersects(retained))
        assertFalse(PixelRect(20f, -250f, 180f, -201f).intersects(retained))
        assertTrue(PixelRect(490f, 400f, 540f, 500f).intersects(retained))
        assertFalse(PixelRect(501f, 400f, 550f, 500f).intersects(retained))
    }

    @Test fun forwardReverseScrollAndZoomIdentifyOnlyNewStrips() {
        assertEquals(listOf(PixelRect(0f, 650f, 400f, 800f)), screenExposedRegions(viewport, ScreenTransform(y = -150f).bounds(viewport)))
        assertEquals(listOf(PixelRect(0f, 0f, 400f, 150f)), screenExposedRegions(viewport, ScreenTransform(y = 150f).bounds(viewport)))
        assertTrue(screenExposedRegions(viewport, ScreenTransform(2f, -200f, -400f).bounds(viewport)).isEmpty())
        assertEquals(listOf(viewport), screenExposedRegions(viewport, PixelRect(1000f, 0f, 1400f, 800f)))
        val reduced = ScreenTransform(.5f, 100f, 200f).bounds(viewport)
        val pieces = screenExposedRegions(viewport, reduced)
        assertEquals(viewport.area - reduced.area, pieces.sumOf { it.area.toDouble() }.toFloat(), .01f)
        pieces.indices.forEach { i -> pieces.drop(i + 1).forEach { assertFalse(pieces[i].intersects(it)) } }
    }

    @Test fun inFlightOutputFollowsScrollAndZoomSinceCapture() {
        val atCapture = ScreenTransform(1.2f, 100f, -300f)
        val sinceCapture = ScreenTransform(.8f, -40f, 200f)
        val current = atCapture.then(sinceCapture)
        val recovered = atCapture.inverse().then(current)
        val point = PixelPoint(180f, 400f)
        assertEquals(sinceCapture.point(point).x, recovered.point(point).x, .001f)
        assertEquals(sinceCapture.point(point).y, recovered.point(point).y, .001f)
    }

    @Test fun contentCoveragePreservesDiagonalHolesAndReusesZoomedWork() {
        val coverage = ScreenCoverage()
        coverage.add(PixelRect(0f, 0f, 200f, 400f), ScreenTransform())
        coverage.add(PixelRect(200f, 200f, 400f, 600f), ScreenTransform())
        val unseen = coverage.exposed(viewport, ScreenTransform())
        assertTrue("Diagonal movement must leave the upper right corner exposed", unseen.any { it.left <= 250f && it.right >= 300f && it.top == 0f })
        assertEquals(viewport.area - 160_000f, unseen.sumOf { it.area.toDouble() }.toFloat(), .01f)
        val zoom = ScreenTransform(1.5f, -80f, -200f)
        val zoomed = coverage.exposed(zoom.bounds(viewport), zoom)
        assertEquals(unseen.sumOf { it.area.toDouble() } * 2.25, zoomed.sumOf { it.area.toDouble() }, .1)
        // Add a captured strip while the screen moves; it is recorded in the
        // same content coordinates and not reapplied at the new screen position.
        zoomed.forEach { coverage.add(it, zoom) }
        assertTrue(coverage.exposed(viewport, ScreenTransform()).isEmpty())
        assertTrue(coverage.exposed(zoom.bounds(viewport), zoom).isEmpty())
        coverage.clear()
        assertEquals(listOf(viewport), coverage.exposed(viewport, ScreenTransform()))
    }

    private fun textured(): ScreenGrayFrame {
        val w = 192; val h = 256; val pixels = ByteArray(w * h) { 245.toByte() }
        val random = java.util.Random(8401)
        repeat(150) {
            val x = random.nextInt(w - 24); val y = random.nextInt(h - 24)
            val rw = random.nextInt(14) + 4; val rh = random.nextInt(14) + 4; val color = random.nextInt(200)
            for (yy in y until y + rh) for (xx in x until x + rw) pixels[yy * w + xx] = color.toByte()
        }
        return ScreenGrayFrame(w, h, pixels)
    }
    private fun transform(frame: ScreenGrayFrame, s: Float, dx: Float, dy: Float): ScreenGrayFrame =
        ScreenGrayFrame(frame.width, frame.height, ByteArray(frame.pixels.size) { index ->
            val x = (index % frame.width - dx) / s; val y = (index / frame.width - dy) / s
            if (x < 0 || x >= frame.width - 1 || y < 0 || y >= frame.height - 1) 245.toByte()
            else {
                val l = floor(x).toInt(); val t = floor(y).toInt(); val u = x - l; val v = y - t
                ((frame.at(l, t) * (1 - u) + frame.at(l + 1, t) * u) * (1 - v) +
                    (frame.at(l, t + 1) * (1 - u) + frame.at(l + 1, t + 1) * u) * v).roundToInt().toByte()
            }
        })

    @Test fun fallbackTracksBothScrollDirectionsAndPinchScaling() {
        val before = textured()
        listOf(Triple(1f, -7f, -35f), Triple(1f, 13f, 27f), Triple(1.25f, -25f, -30f), Triple(.8f, 20f, 30f)).forEach { (s, x, y) ->
            val motion = ScreenMotionTracker.estimate(before, transform(before, s, x, y))
            assertNotNull("Expected scale=$s translation=($x,$y)", motion)
            assertEquals(s, motion!!.scale, .04f); assertEquals(x, motion.x, 4f); assertEquals(y, motion.y, 4f)
        }
        // Wide translated bubbles leave only narrow strips of visible art.
        // Small-window flow must still follow them without using overlay ink.
        val mask = ByteArray(before.pixels.size) { i -> if (i % before.width in 45..174 && i / before.width in 35..200) 0 else 1 }
        val masked = before.copy(valid = mask)
        listOf(Triple(1f, 0f, -3f), Triple(1.08f, -6f, -9f)).forEach { (s, x, y) ->
            val motion = ScreenMotionTracker.estimate(masked, transform(before, s, x, y).copy(valid = mask))
            assertNotNull("Masked motion scale=$s translation=($x,$y)", motion)
            assertEquals(s, motion!!.scale, .025f); assertEquals(x, motion.x, 1.8f); assertEquals(y, motion.y, 1.8f)
        }
        val oneFrameLateMask = ScreenMotionTracker.estimate(before, transform(before, 1f, 0f, -3f).copy(valid = mask))
        assertNotNull("A newly added overlay must be excluded in both frames", oneFrameLateMask)
        assertEquals(-3f, oneFrameLateMask!!.y, 1.8f)
        val pan = transform(before, 1f, -6f, -9f)
        val predictedError = ScreenMotionTracker.predictionError(before, pan, ScreenTransform(1f, -6f, -9f))
        assertNotNull(predictedError)
        assertTrue("Correct scroll hints should skip the expensive matcher", predictedError!! < 1f)
    }

    @Test fun blankOrUnrelatedScenesNeverInventMotion() {
        val blank = ScreenGrayFrame(192, 256, ByteArray(192 * 256))
        assertNull(ScreenMotionTracker.estimate(blank, blank))
        val random = java.util.Random(500)
        val noise = ScreenGrayFrame(192, 256, ByteArray(192 * 256) { random.nextInt(256).toByte() })
        assertNull(ScreenMotionTracker.estimate(textured(), noise))
        assertEquals(0f, blank.difference(blank), 0f)
        assertEquals(100f, blank.difference(ScreenGrayFrame(192, 256, ByteArray(192 * 256) { 255.toByte() })), .001f)
    }
}
