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
    private fun tint(frame: ScreenGrayFrame, opacity: Float, color: Int = 0) = frame.copy(pixels =
        ByteArray(frame.pixels.size) { i -> ((frame.pixels[i].toInt() and 255) * (1 - opacity) + color * opacity).roundToInt().toByte() })

    @Test fun solidTranslucentMasksCanAppearDisappearAndChangeDuringPanAndZoom() {
        val original = art()
        val fixed = listOf(PixelRect(0f, 0f, 192f, 60f), PixelRect(0f, 190f, 192f, 256f))
        // Black dimming, blue's luminance, and a light card, including removal
        // and changes between two already tinted frames.
        for ((opacity, color) in listOf(.65f to 0, .7f to 55, .65f to 220)) {
            for (pose in listOf(ScreenTransform(), ScreenTransform(1.1f, -8f, -10f), ScreenTransform(.9f, 8f, 10f))) {
                val transformed = warp(original, pose, fixed)
                val cases = listOf(original to tint(transformed, opacity, color),
                    tint(original, opacity, color) to transformed,
                    tint(original, .35f, 55) to tint(transformed, opacity, color))
                for ((before, after) in cases) {
                    val fit = ScreenMotionTracker.estimateModels(before, after)
                    assertNotNull("Mask $opacity/$color and pose $pose", fit)
                    assertEquals(pose.scale, fit!!.content.scale, .025f)
                    assertEquals(pose.x, fit.content.x, 2.5f); assertEquals(pose.y, fit.content.y, 2.5f)
                    if (pose != ScreenTransform()) assertTrue(fit.fixedAreas.isNotEmpty())
                }
            }
        }
        assertTrue("Capture source confirmation must tolerate a uniform card",
            ScreenMotionTracker.predictionError(original, tint(original, .7f, 55), ScreenTransform())!! < 1f)
    }

    @Test fun maskingOwnOverlaysDoesNotPolluteTheContrastStatistics() {
        val original = art()
        val valid = ByteArray(original.pixels.size) { i -> if (i % original.width in 45..174 && i / original.width in 35..200) 0 else 1 }
        val pose = ScreenTransform(1.07f, -5f, -7f)
        val after = tint(warp(original, pose), .6f, 55).copy(valid = valid)
        for (i in after.pixels.indices) if (valid[i] == 0.toByte()) after.pixels[i] = if (i % 2 == 0) 0 else 255.toByte()
        val fit = ScreenMotionTracker.estimateModels(original, after)
        assertNotNull(fit)
        assertEquals(pose.scale, fit!!.content.scale, .025f)
        assertEquals(pose.x, fit.content.x, 2f); assertEquals(pose.y, fit.content.y, 2f)
    }

    @Test fun darkeningDoesNotEraseOrReattachStationaryAndMovingObjects() {
        val frame = art(); val bounds = PixelRect(0f, 0f, 192f, 256f)
        val search = PixelRect(40f, 200f, 150f, 244f); val content = PixelRect(40f, 75f, 130f, 140f)
        val pose = ScreenTransform(1.12f, -9f, -8f)
        for (dimmedReference in listOf(false, true)) {
            val reference = if (dimmedReference) tint(frame, .7f, 55) else frame
            val objects = listOf(search, content).mapIndexed { i, area -> ScreenMotionObject("$i", 1, area, ScreenTransform(),
                ScreenObjectTemplate.capture(reference, bounds, area)) }
            assertTrue(objects.all { it.template != null })
            val current = warp(frame, pose, listOf(PixelRect(0f, 190f, 192f, 256f)))
            val decisions = ScreenObjectTracker.evaluate(objects, if (dimmedReference) current else tint(current, .7f, 55), bounds, pose, 1)
            assertEquals(ScreenObjectAttachment.FIXED, decisions[0].attachment)
            assertEquals(ScreenTransform(), decisions[0].sourceToScreen)
            assertEquals(ScreenObjectAttachment.CONTENT, decisions[1].attachment)
            assertEquals(pose, decisions[1].sourceToScreen)
            assertTrue(decisions.all { !it.remove && it.failures == 0 })
        }
    }

    @Test fun flatOpaqueAndDifferentTintedScenesRemainUntrackable() {
        val frame = art()
        val random = java.util.Random(6395)
        val unrelated = frame.copy(pixels = ByteArray(frame.pixels.size) { random.nextInt(256).toByte() })
        assertNull(ScreenMotionTracker.estimate(frame, tint(unrelated, .7f, 55)))
        assertNull(ScreenMotionTracker.estimate(frame, tint(frame, 1f, 55)))
        assertNull(ScreenMotionTracker.estimate(frame, tint(frame, .98f)))
    }

    @Test fun realDownsampledThinStrokesSurviveTintRemovalAndShrink() {
        // Own Android fixture, captured through the actual 192px stream sampler.
        // Unlike warping an already reduced image, zoom also changes antialiasing.
        fun read(name: String): ScreenGrayFrame {
            val base = "/screen-motion/tint-shrink-$name"
            val bytes = javaClass.getResourceAsStream("$base.pgm")!!.use { it.readBytes() }
            var offset = 0
            repeat(3) { while (bytes[offset++] != '\n'.code.toByte()) Unit }
            val size = String(bytes, 0, offset).lines()[1].split(' ').map(String::toInt)
            val mask = javaClass.getResourceAsStream("$base.mask")!!.use { it.readBytes() }
            return ScreenGrayFrame(size[0], size[1], bytes.copyOfRange(offset, bytes.size), mask)
        }
        val before = read("before"); val after = read("after")
        val fit = ScreenMotionTracker.estimateModels(before, after)
        assertNotNull("Tint removal plus an abrupt shrink must retain the content pose", fit)
        assertEquals(.92f / 1.12f, fit!!.content.scale, .02f)
        assertEquals(0f, fit.content.x, 1.5f); assertEquals(21f, fit.content.y, 1.5f)
        assertTrue("The fixed search bar remains a separate layer", fit.fixedAreas.isNotEmpty())
    }

    @Test fun rareInkOnAnOtherwiseBlankPageKeepsItsTemplateUnderATint() {
        val width = 192; val height = 256
        val pixels = ByteArray(width * height) { 238.toByte() }
        for (y in 112..128) for (x in 85..113) if (x % 4 == 0 || y % 5 == 0) pixels[y * width + x] = 30
        val frame = ScreenGrayFrame(width, height, pixels)
        val bounds = PixelRect(0f, 0f, width.toFloat(), height.toFloat())
        val objectBounds = PixelRect(80f, 108f, 120f, 133f)
        val template = ScreenObjectTemplate.capture(frame, bounds, objectBounds)
        assertNotNull("Less than 1% ink is still useful texture", template)
        assertTrue(template!!.compare(tint(frame, .7f, 55), bounds, objectBounds)!!.matches)
        val tintedTemplate = ScreenObjectTemplate.capture(tint(frame, .7f, 55), bounds, objectBounds)
        assertNotNull(tintedTemplate)
        assertTrue(tintedTemplate!!.compare(frame, bounds, objectBounds)!!.matches)
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
