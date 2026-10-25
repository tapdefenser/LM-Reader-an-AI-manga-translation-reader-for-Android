package com.lmreader.ui.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.vision.BubbleMaskRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inspect actual glyph pixels: AUTO is evaluated independently on light and dark regions. */
@RunWith(AndroidJUnit4::class)
class VideoMaskTextRenderingTest {
    @Test fun blackWhiteAndAutomaticRenderExpectedGlyphsWithoutChangingSourcePixels() {
        val source = Bitmap.createBitmap(480, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        Canvas(source).drawRect(240f, 0f, 480f, 200f, android.graphics.Paint().apply { color = Color.BLACK })
        val regions = listOf(20f to 220f, 260f to 460f).mapIndexed { index, (left, right) ->
            PageTranslatedRegion(PageTextRegion("region-$index", RegionKind.BUBBLE,
                PixelRect(left, 20f, right, 180f), emptyList(), "source", emptyList()), "TEST")
        }
        val prepared = BubbleMaskRenderer().prepareSource(source, regions.map { it.region })
        try {
            for (mode in BubbleTextColorMode.entries) {
                val output = Bitmap.createBitmap(480, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
                try {
                    prepared.layout(regions, BubbleRenderSettings(), textColorMode = mode).draw(Canvas(output), drawMask = false)
                    for (index in regions.indices) {
                        val expected = when (mode) {
                            BubbleTextColorMode.BLACK -> Color.BLACK
                            BubbleTextColorMode.WHITE -> Color.WHITE
                            BubbleTextColorMode.AUTO -> if(index == 0) Color.BLACK else Color.WHITE
                        }
                        val left = if(index == 0) 20 else 260
                        val ink = (20 until 180).sumOf { y -> (left until left + 200).count { x -> output.getPixel(x, y) == expected } }
                        val wrong = (20 until 180).sumOf { y -> (left until left + 200).count { x -> output.getPixel(x, y) == if(expected == Color.BLACK) Color.WHITE else Color.BLACK } }
                        assertTrue("$mode region $index must draw visible expected glyphs", ink > 50)
                        assertEquals("$mode region $index must not use the opposite text color", 0, wrong)
                    }
                } finally { output.recycle() }
            }
            assertEquals(Color.WHITE, source.getPixel(100, 100))
            assertEquals(Color.BLACK, source.getPixel(350, 100))
        } finally { source.recycle() }
    }
}
