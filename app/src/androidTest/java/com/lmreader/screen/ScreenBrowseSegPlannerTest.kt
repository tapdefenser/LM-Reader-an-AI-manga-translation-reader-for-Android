package com.lmreader.screen

import android.graphics.*
import com.lmreader.core.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ScreenBrowseSegPlannerTest {
    private val screen = PixelRect(0f, 0f, 400f, 800f)
    private fun region(bounds: PixelRect) = SegRegion("temporary", RegionKind.FREE_TEXT, bounds, 1f)
    private fun detected(image: Bitmap, id: String, regions: List<SegRegion>) =
        SegResult(id, image.width, image.height, regions, 1, "fixture") to regions

    @Test fun scrollingOnlySendsTheNewStripToSegWithoutExpandingItIntoOldText() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val fresh = PixelRect(0f, 600f, 400f, 800f)
        val old = ScreenSourceRegion("good-old", PixelRect(50f, 300f, 190f, 430f))
        var calls = 0
        try {
            val result = ScreenBrowseSegPlanner { tile, id ->
                calls++; assertEquals(400, tile.width); assertEquals(200, tile.height)
                detected(tile, id, listOf(region(PixelRect(40f, 30f, 190f, 90f))))
            }.plan("page", image, listOf(fresh), listOf(old), listOf(screen), 8f, contextPixels = 0f) { _, _ -> false }
            assertEquals(1, calls); assertEquals(listOf(fresh), result.firstPassInputs)
            assertTrue(result.mergeInputs.isEmpty()); assertTrue(result.replacement.replaces.isEmpty())
            assertEquals(PixelRect(40f, 630f, 190f, 690f), result.regions.single().bounds)
        } finally { image.recycle() }
    }
    @Test fun existingSegIsExcludedAtItsMovedAndZoomedLocationEvenBeforeTranslationCompletes() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val old = ScreenSourceRegion("pending", ScreenTransform(1.5f, -50f, -300f).bounds(PixelRect(100f, 300f, 200f, 350f)))
        var calls = 0
        try {
            val result = ScreenBrowseSegPlanner { tile, id ->
                calls++
                assertEquals(400, tile.width); assertEquals(800, tile.height)
                assertEquals(Color.WHITE, tile.getPixel(old.bounds.left.toInt() + 10, old.bounds.top.toInt() + 10))
                assertEquals(Color.BLACK, tile.getPixel(10, 10))
                detected(tile, id, emptyList())
            }
                .plan("page", image, listOf(screen), listOf(old), listOf(screen), 8f) { _, _ -> false }
            assertEquals("A hole is a mask rather than four separate SEG images", 1, calls)
            assertEquals(listOf(screen), result.firstPassInputs)
            assertFalse(result.searched.any { it.intersects(old.bounds) })
            assertEquals(screen.area - old.bounds.area, result.searched.sumOf { it.area.toDouble() }.toFloat(), .1f)
            assertTrue(result.regions.isEmpty()); assertTrue(result.replacement.replaces.isEmpty())
        } finally { image.recycle() }
    }
    @Test fun touchingNewTextRestoresTheWholeOriginalCropAndOnlyFinalSegReachesOcr() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        Canvas(image).apply { drawColor(Color.WHITE); drawRect(50f, 200f, 280f, 250f, Paint().apply { color = Color.BLACK }) }
        val old = ScreenSourceRegion("old-fragment", PixelRect(50f, 200f, 170f, 250f))
        val fresh = PixelRect(170f, 200f, 280f, 250f)
        var call = 0
        try {
            val result = ScreenBrowseSegPlanner { tile, id ->
                call++
                if (call == 1) {
                    assertEquals(110, tile.width)
                    detected(tile, id, listOf(region(PixelRect(0f, 0f, 110f, 50f))))
                } else {
                    assertEquals(230, tile.width); assertEquals(Color.BLACK, tile.getPixel(30, 20))
                    detected(tile, id, listOf(region(PixelRect(0f, 0f, 230f, 50f))))
                }
            }.plan("page", image, listOf(fresh), listOf(old), listOf(screen), 8f, contextPixels = 0f) { _, _ -> false }
            assertEquals(2, call); assertEquals(setOf(old.id), result.replacement.replaces)
            assertEquals(listOf(PixelRect(50f, 200f, 280f, 250f)), result.mergeInputs)
            assertEquals(PixelRect(50f, 200f, 280f, 250f), result.regions.single().bounds)
            assertTrue(result.regions.none { it.id.contains("proposal") })
        } finally { image.recycle() }
    }
    @Test fun intactOldBubbleInAMergeCropIsReusedAndExcludedFromWholePageOcrInput() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val old = ScreenSourceRegion("already-good", PixelRect(50f, 200f, 170f, 250f))
        val fresh = PixelRect(170f, 245f, 280f, 300f)
        var call = 0
        try {
            val result = ScreenBrowseSegPlanner { tile, id ->
                call++
                detected(tile, id, if (call == 1) listOf(region(PixelRect(0f, 0f, 110f, 55f))) else
                    listOf(region(PixelRect(0f, 0f, 120f, 50f)), region(PixelRect(120f, 45f, 230f, 100f))))
            }.plan("page", image, listOf(fresh), listOf(old), listOf(screen), 8f, contextPixels = 0f) { source, new -> source.bounds == new.bounds }
            assertEquals(2, call); assertEquals(setOf(old.id), result.reused)
            assertTrue(result.replacement.replaces.isEmpty()); assertEquals(1, result.regions.size)
            assertFalse(result.owned.any { it.intersects(old.bounds) })
        } finally { image.recycle() }
    }
    @Test fun emptyMergedSegDoesNotRetireOldText() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val old = ScreenSourceRegion("old", PixelRect(50f, 200f, 170f, 250f))
        var call = 0
        try {
            val result = ScreenBrowseSegPlanner { tile, id ->
                call++; detected(tile, id, if (call == 1) listOf(region(PixelRect(0f, 0f, 110f, 50f))) else emptyList())
            }.plan("page", image, listOf(PixelRect(170f, 200f, 280f, 250f)), listOf(old), listOf(screen), 8f, contextPixels = 0f) { _, _ -> false }
            assertTrue(result.replacement.replaces.isEmpty()); assertTrue(result.regions.isEmpty())
        } finally { image.recycle() }
    }

    @Test fun mergedRegionOverlappingFreshTilesKeepsBothPartsOfTheOriginalForOcr() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val old = ScreenSourceRegion("old", PixelRect(50f, 200f, 170f, 250f))
        try {
            val plan = ScreenBrowseSegPlanner { tile, id ->
                detected(tile, id, listOf(region(PixelRect(0f, 0f, tile.width.toFloat(), tile.height.toFloat()))))
            }.plan("page", image, listOf(PixelRect(170f, 200f, 280f, 250f)), listOf(old), listOf(screen), 8f, contextPixels = 0f) { _, _ -> false }
            val ocrInput = maskedScreenCrop(image, screen, plan.owned)
            try {
                assertEquals("The restored old part stays readable", Color.BLACK, ocrInput.getPixel(100, 225))
                assertEquals("Overlapping ownership must not erase new source pixels", Color.BLACK, ocrInput.getPixel(220, 225))
                assertEquals("Unowned screen content stays excluded", Color.WHITE, ocrInput.getPixel(350, 700))
            } finally { if (ocrInput !== image) ocrInput.recycle() }
        } finally { image.recycle() }
    }

    private fun ink(tile: Bitmap, id: String): Pair<SegResult, List<SegRegion>> {
        var l = tile.width; var t = tile.height; var r = 0; var b = 0
        for (y in 0 until tile.height) for (x in 0 until tile.width) if (tile.getPixel(x, y) == Color.BLACK) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
        }
        val regions = if (r > l && b > t) listOf(region(PixelRect(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()))) else emptyList()
        val (seg, selected) = detected(tile, id, regions)
        return seg.copy(textLines = regions.map { DetectedTextLine(it.bounds, 1f) }) to selected
    }

    @Test fun touchingFreshRemaindersAreOneImageInsteadOfCuttingATextLineInTwo() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val text = PixelRect(130f, 610f, 290f, 660f)
        Canvas(image).drawRect(text.left, text.top, text.right, text.bottom, Paint().apply { color = Color.BLACK })
        try {
            val plan = ScreenBrowseSegPlanner(::ink).plan("page", image,
                listOf(PixelRect(0f, 600f, 200f, 800f), PixelRect(200f, 600f, 400f, 800f)), emptyList(),
                listOf(screen), 8f, contextPixels = 0f) { _, _ -> false }
            assertEquals(listOf(PixelRect(0f, 600f, 400f, 800f)), plan.firstPassInputs)
            assertEquals(text, plan.regions.single().bounds)
            assertTrue(plan.mergeInputs.isEmpty())
        } finally { image.recycle() }
    }

    @Test fun diagonalExposureStaysSmallButTextCrossingTheTwoStripsIsRedetectedFromWholePixels() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val text = PixelRect(270f, 680f, 370f, 750f)
        Canvas(image).drawRect(text.left, text.top, text.right, text.bottom, Paint().apply { color = Color.BLACK })
        try {
            val plan = ScreenBrowseSegPlanner(::ink).plan("page", image,
                listOf(PixelRect(0f, 700f, 400f, 800f), PixelRect(300f, 0f, 400f, 700f)), emptyList(),
                listOf(screen), 8f, contextPixels = 0f) { _, _ -> false }
            assertEquals(2, plan.firstPassInputs.size)
            assertTrue(plan.firstPassInputs.sumOf { it.area.toDouble() } < screen.area * .5)
            assertEquals(listOf(text), plan.mergeInputs)
            assertEquals(text, plan.regions.single().bounds)
            assertEquals(listOf(text), plan.segmentation.textLines.map { it.bounds })
            assertTrue(plan.replacement.replaces.isEmpty())
        } finally { image.recycle() }
    }

    @Test fun contextRestoresUnrecognizedTextAcrossCoverageEdgeWithoutReintroducingOldSeg() = runBlocking {
        val image = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val text = PixelRect(40f, 570f, 180f, 650f)
        val old = ScreenSourceRegion("good-old", PixelRect(260f, 550f, 350f, 590f))
        val paint = Paint().apply { color = Color.BLACK }
        Canvas(image).apply { drawRect(text.left, text.top, text.right, text.bottom, paint)
            drawRect(old.bounds.left, old.bounds.top, old.bounds.right, old.bounds.bottom, paint) }
        try {
            val plan = ScreenBrowseSegPlanner { tile, id ->
                assertEquals(Color.WHITE, tile.getPixel(300, 30))
                ink(tile, id)
            }.plan("page", image, listOf(PixelRect(0f, 600f, 400f, 800f)), listOf(old), listOf(screen), 8f) { _, _ -> false }
            assertEquals(listOf(PixelRect(0f, 536f, 400f, 800f)), plan.firstPassInputs)
            assertEquals(text, plan.regions.single().bounds)
            assertFalse(plan.owned.any { it.intersects(old.bounds) })
            assertTrue(screenExposedRegions(text, plan.owned).isEmpty())
            assertTrue(plan.replacement.replaces.isEmpty())
        } finally { image.recycle() }
    }
}
