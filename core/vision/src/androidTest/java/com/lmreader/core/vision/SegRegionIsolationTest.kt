package com.lmreader.core.vision

import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Generated fixtures only; this test package does not open or modify the reader's library. */
@RunWith(AndroidJUnit4::class)
class SegRegionIsolationTest {
    @Test fun closeFreeTextRowsAndColumnsHaveIndependentApiAndOcrPixels() {
        for (vertical in listOf(false, true)) {
            fun orient(rect: PixelRect) = if (vertical) PixelRect(rect.top, rect.left, rect.bottom, rect.right) else rect
            val image = Bitmap.createBitmap(460, 460, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            val lines = listOf(orient(PixelRect(40f,35f,420f,65f)), orient(PixelRect(40f,73f,420f,103f)))
            val parent = SegRegion("caption", RegionKind.FREE_TEXT, orient(PixelRect(20f,20f,440f,130f)), .9f)
            val seg = SegResult("p", 460, 460, listOf(parent), 0, "fixture",
                textLines = lines.map { DetectedTextLine(it, .9f) })
            val canvas = Canvas(image)
            lines.forEachIndexed { index, rect ->
                canvas.drawRect(rect.left, rect.top, rect.right, rect.bottom,
                    Paint().apply { color = if (index == 0) Color.RED else Color.BLUE })
            }
            fun count(bitmap: Bitmap, color: Int): Int = (0 until bitmap.height).sumOf { y ->
                (0 until bitmap.width).count { x -> bitmap.getPixel(x, y) == color }
            }
            try {
                val separate = selectSegRegions(seg, SegTextScope.FREE_TEXT, 0f)
                assertEquals(2, separate.size)
                separate.forEachIndexed { index, region ->
                    cropSegRegion(image, region, seg.regions).use { crop ->
                        assertTrue(count(crop.bitmap, if (index == 0) Color.RED else Color.BLUE) > 0)
                        assertEquals(0, count(crop.bitmap, if (index == 0) Color.BLUE else Color.RED))
                    }
                    assertEquals(listOf(lines[index]), selectRegionTextLines(region, seg.regions, seg.textLines).map { it.bounds })
                }
                val joined = selectSegRegions(seg, SegTextScope.FREE_TEXT, .3f)
                assertEquals(1, joined.size)
                assertEquals(joined, selectSegRegions(seg, SegTextScope.FREE_TEXT))
                cropSegRegion(image, joined.single(), seg.regions).use { crop ->
                    assertTrue(count(crop.bitmap, Color.RED) > 0)
                    assertTrue(count(crop.bitmap, Color.BLUE) > 0)
                }
            } finally { image.recycle() }
        }
    }

    @Test fun freeTextCropKeepsBoldOutlineOutsideTightMask() {
        val image = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(image).drawRect(22f,22f,78f,78f,Paint().apply { color=Color.BLACK })
        val region = SegRegion("bold",RegionKind.FREE_TEXT,PixelRect(20f,20f,80f,80f),.9f,
            listOf(PixelPoint(30f,30f),PixelPoint(70f,30f),PixelPoint(70f,70f),PixelPoint(30f,70f)))
        try {
            cropSegRegion(image,region,listOf(region)).use { crop ->
                assertEquals(Color.BLACK,crop.bitmap.getPixel(3,3))
                assertEquals(Color.BLACK,crop.bitmap.getPixel(57,57))
                assertEquals(Color.WHITE,crop.bitmap.getPixel(0,0))
            }
        } finally { image.recycle() }
    }
    @Test fun realSegAndOcrRetainBothTextsInConnectedBalloonFixture() = runBlocking {
        val image = Bitmap.createBitmap(1000, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.LTGRAY)
        val balloon = Path().apply { addOval(40f, 50f, 600f, 410f, Path.Direction.CW) }
        val second = Path().apply { addOval(410f, 280f, 970f, 650f, Path.Direction.CW) }
        balloon.op(second, Path.Op.UNION)
        canvas.drawPath(balloon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        canvas.drawPath(balloon, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("HELLO FRIEND", 115f, 200f, paint)
        canvas.drawText("SEE YOU", 530f, 440f, paint)
        canvas.drawText("TOMORROW", 490f, 505f, paint)
        val engine = LocalVisionEngine(ApplicationProvider.getApplicationContext()) {
            VisionExecutionSettings(segGpu = false, ocrBackend = OcrBackend.CPU, segConcurrency = 1, ocrConcurrency = 1)
        }
        try {
            val seg = engine.segment("connected-fixture", image)
            val targets = selectSegRegions(seg, SegTextScope.BUBBLES)
            val recognized = targets.map { engine.recognizeRegion(seg.imageId, image, LocalOcrLanguage.ENGLISH, it, seg.regions, seg.textLines).translationText }
            Log.i("SegIsolation", "raw=${seg.regions}; targets=${targets.size}; recognized=$recognized")
            assertTrue("No detected balloon targets", targets.isNotEmpty())
            assertTrue("First text missed: $recognized", recognized.any { it.contains("HELLO") && it.contains("FRIEND") })
            assertTrue("Second text missed: $recognized", recognized.any { it.contains("SEE YOU") && it.contains("TOMORROW") })
            assertTrue("Connected texts combined: $recognized", recognized.none { it.contains("HELLO") && it.contains("TOMORROW") })
        } finally { engine.releaseModels(); image.recycle() }
    }
    @Test fun connectedBubbleTargetsHaveSeparatePixelsAndOcr() = runBlocking {
        val image = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        val bounds = PixelRect(20f, 20f, 780f, 480f)
        val contour = listOf(PixelPoint(20f, 20f), PixelPoint(780f, 20f), PixelPoint(780f, 480f), PixelPoint(20f, 480f))
        val bubble = SegRegion("connected", RegionKind.BUBBLE, bounds, .95f, contour)
        val first = SegRegion("first", RegionKind.FREE_TEXT, PixelRect(70f, 100f, 330f, 200f), .95f)
        val second = SegRegion("second", RegionKind.FREE_TEXT, PixelRect(450f, 270f, 730f, 370f), .95f)
        val raw = listOf(bubble, first, second)
        val targets = selectSegRegions(SegResult("p", 800, 500, raw, 0, "test"), SegTextScope.BUBBLES)
        val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 58f; typeface = Typeface.DEFAULT_BOLD }
        canvas.drawText("HELLO", 90f, 175f, paint); canvas.drawText("WORLD", 470f, 345f, paint)
        val engine = LocalVisionEngine(ApplicationProvider.getApplicationContext()) {
            VisionExecutionSettings(segGpu = false, ocrBackend = OcrBackend.CPU, ocrConcurrency = 1)
        }
        try {
            assertEquals(2, targets.size)
            val a = engine.recognizeRegion("p", image, LocalOcrLanguage.ENGLISH, targets[0], raw)
            val b = engine.recognizeRegion("p", image, LocalOcrLanguage.ENGLISH, targets[1], raw)
            assertTrue(a.translationText.contains("HELLO")); assertFalse(a.translationText.contains("WORLD"))
            assertTrue(b.translationText.contains("WORLD")); assertFalse(b.translationText.contains("HELLO"))
            assertEquals(image.width, b.width); assertEquals(image.height, b.height)
            assertTrue(b.lines.all { it.bounds.left > 400f && it.bounds.top > 200f })
        } finally { engine.releaseModels(); image.recycle() }
    }

    @Test fun freeTextCropExcludesBalloonPixelsUsingContour() {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val bubble = SegRegion("b", RegionKind.BUBBLE, PixelRect(0f, 0f, 160f, 160f), .9f,
            listOf(PixelPoint(0f, 0f), PixelPoint(160f, 0f), PixelPoint(0f, 160f)))
        val free = SegRegion("f", RegionKind.FREE_TEXT, PixelRect(60f, 60f, 200f, 200f), .9f)
        try {
            cropSegRegion(image, free, listOf(bubble, free)).use { crop ->
                assertEquals(Color.WHITE, crop.bitmap.getPixel(1, 1))
                assertEquals(Color.BLACK, crop.bitmap.getPixel(90, 90))
            }
            // Selection is free-text-only, but the raw bubble still masks its own pixels.
            assertEquals(listOf("f"), selectSegRegions(SegResult("p", 200, 200, listOf(bubble, free), 0, "test"), SegTextScope.FREE_TEXT).map { it.id })
        } finally { image.recycle() }
    }

    @Test fun bubbleCropWhitesOutCaptionOutsideIrregularContour() {
        val image = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val bubble = SegRegion("b", RegionKind.BUBBLE, PixelRect(0f, 0f, 200f, 200f), .9f,
            listOf(PixelPoint(0f, 0f), PixelPoint(200f, 0f), PixelPoint(0f, 200f)))
        try {
            cropSegRegion(image, bubble, listOf(bubble)).use { crop ->
                assertEquals(Color.BLACK, crop.bitmap.getPixel(40, 40))
                assertEquals(Color.WHITE, crop.bitmap.getPixel(160, 160))
            }
            assertEquals(Color.BLACK, image.getPixel(160, 160))
        } finally { image.recycle() }
    }
}
