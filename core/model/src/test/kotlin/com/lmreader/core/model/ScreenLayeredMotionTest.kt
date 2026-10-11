package com.lmreader.core.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class ScreenLayeredMotionTest {
    private fun art(): ScreenGrayFrame {
        val w = 192; val h = 256
        val random = java.util.Random(19732)
        val pixels = ByteArray(w * h) { 238.toByte() }
        repeat(350) {
            val x = random.nextInt(w - 15); val y = random.nextInt(h - 15)
            val color = random.nextInt(210).toByte()
            for (yy in y until y + 3 + random.nextInt(10)) for (xx in x until x + 3 + random.nextInt(10)) pixels[yy * w + xx] = color
        }
        return ScreenGrayFrame(w, h, pixels)
    }
    private fun warp(frame: ScreenGrayFrame, pose: ScreenTransform, fixed: List<PixelRect> = emptyList()): ScreenGrayFrame {
        val inverse = pose.inverse()
        return frame.copy(pixels = ByteArray(frame.pixels.size) { i ->
            val target = PixelPoint((i % frame.width).toFloat(), (i / frame.width).toFloat())
            if (fixed.any { target.x >= it.left && target.x < it.right && target.y >= it.top && target.y < it.bottom }) frame.pixels[i]
            else {
                val p = inverse.point(target)
                if (p.x < 0 || p.y < 0 || p.x >= frame.width - 1 || p.y >= frame.height - 1) 238.toByte()
                else {
                    val l = p.x.toInt(); val t = p.y.toInt(); val u = p.x - l; val v = p.y - t
                    ((frame.at(l, t) * (1 - u) + frame.at(l + 1, t) * u) * (1 - v) +
                        (frame.at(l, t + 1) * (1 - u) + frame.at(l + 1, t + 1) * u) * v).roundToInt().toByte()
                }
            }
        })
    }
    @Test fun fixedToolbarsCannotSuppressSimultaneousPanAndZoom() {
        val before = art()
        for (fixed in listOf(listOf(PixelRect(0f, 0f, 192f, 60f), PixelRect(0f, 190f, 192f, 256f)),
            listOf(PixelRect(0f, 0f, 192f, 80f), PixelRect(0f, 176f, 192f, 256f))))
        for (pose in listOf(ScreenTransform(1.12f, -10f, -12f), ScreenTransform(.88f, 10f, 14f), ScreenTransform(1f, -4f, -7f))) {
            val fit = ScreenMotionTracker.estimateModels(before, warp(before, pose, fixed))
            assertNotNull("Content pose $pose despite fixed UI", fit)
            assertEquals(pose.scale, fit!!.content.scale, .025f)
            assertEquals(pose.x, fit.content.x, 2.5f); assertEquals(pose.y, fit.content.y, 2.5f)
            assertTrue("Both motion layers must be represented", fit.fixedAreas.isNotEmpty())
        }
    }
    @Test fun regionTemplatesDistinguishAStationarySearchBoxFromZoomingArt() {
        val frame = art(); val bounds = PixelRect(0f, 0f, 192f, 256f)
        val search = PixelRect(40f, 200f, 150f, 244f); val content = PixelRect(40f, 75f, 130f, 140f)
        val objects = listOf(search, content).mapIndexed { i, area -> ScreenMotionObject("$i", 1, area, ScreenTransform(),
            ScreenObjectTemplate.capture(frame, bounds, area)) }
        assertTrue(objects.all { it.template != null })
        val pose = ScreenTransform(1.12f, -9f, -8f)
        val decisions = ScreenObjectTracker.evaluate(objects, warp(frame, pose, listOf(PixelRect(0f, 190f, 192f, 256f))), bounds, pose, 1)
        assertEquals(ScreenObjectAttachment.FIXED, decisions[0].attachment)
        assertEquals(ScreenTransform(), decisions[0].sourceToScreen)
        assertEquals(ScreenObjectAttachment.CONTENT, decisions[1].attachment)
        assertEquals(pose, decisions[1].sourceToScreen)
        val stable = objects[0].copy(attachment = ScreenObjectAttachment.FIXED, sampledSequence = 1)
        assertEquals("A static frame must not change layer ownership", ScreenObjectAttachment.FIXED,
            ScreenObjectTracker.evaluate(listOf(stable), frame, bounds, ScreenTransform(), 2).single().attachment)
    }
    @Test fun deletionRequiresTwoDistinctValidMismatchesAndPartialVisibilityIsPreserved() {
        val frame = art(); val bounds = PixelRect(0f, 0f, 192f, 256f); val source = PixelRect(40f, 75f, 140f, 150f)
        val obj = ScreenMotionObject("region", 2, source, ScreenTransform(), ScreenObjectTemplate.capture(frame, bounds, source))
        val random = java.util.Random(951)
        val different = frame.copy(pixels = ByteArray(frame.pixels.size) { random.nextInt(256).toByte() })
        val first = ScreenObjectTracker.evaluate(listOf(obj), different, bounds, ScreenTransform(), 10).single()
        assertEquals(1, first.failures); assertFalse(first.remove)
        val once = obj.copy(failures = first.failures, sampledSequence = 10)
        assertFalse(ScreenObjectTracker.evaluate(listOf(once), different, bounds, ScreenTransform(), 10).single().remove)
        assertTrue(ScreenObjectTracker.evaluate(listOf(once), different, bounds, ScreenTransform(), 11).single().remove)
        val outside = obj.copy(sourceToScreen = ScreenTransform(y = -120f))
        assertFalse(ScreenObjectTracker.evaluate(listOf(outside), different, bounds, ScreenTransform(), 12).single().remove)
    }
}
