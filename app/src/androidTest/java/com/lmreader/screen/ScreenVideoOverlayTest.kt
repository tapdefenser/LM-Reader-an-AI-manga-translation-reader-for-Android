package com.lmreader.screen

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.MainActivity
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScreenVideoOverlayTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val screen = PixelRect(0f, 0f, 192f, 256f)
    private val bounds = PixelRect(20f, 40f, 140f, 100f)
    private fun text(id: String, translation: String = "Translated") = PageTranslatedRegion(
        PageTextRegion(id, RegionKind.FREE_TEXT, bounds, emptyList(), "Original", listOf(bounds)), translation)
    private suspend fun fixture(test: suspend (ScreenOverlay, Bitmap) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = Bitmap.createBitmap(192, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try { test(overlay, image) } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }
    @Test fun matchingSegMovesTheExistingDrawingAndKeepsItsText() = runBlocking<Unit> {
        fixture { overlay, image ->
            val old = overlay.prepare(image, listOf(text("old")), BubbleRenderSettings())
            withContext(Dispatchers.Main) {
                overlay.addVideo(old, ScreenTransform(), 1)
                assertTrue(overlay.promoteVideo(mapOf("old" to bounds.offset(6f, 4f)), 2))
                overlay.finishVideoArea(screen, 2, setOf("old"))
                assertEquals(mapOf("old" to bounds.offset(6f, 4f)), overlay.positions())
                assertEquals("Translated", overlay.sessionRows("video", 1).single().translatedText)
            }
        }
    }
    @Test fun emptyOrPendingNewResultsKeepOldTextUntilAReplacementIsFilled() = runBlocking<Unit> {
        fixture { overlay, image ->
            val old = overlay.prepare(image, listOf(text("old")), BubbleRenderSettings())
            val waiting = overlay.prepare(image, listOf(text("new", "")), BubbleRenderSettings())
            val filled = overlay.prepare(image, listOf(text("new", "New text")), BubbleRenderSettings())
            withContext(Dispatchers.Main) {
                overlay.addVideo(old, ScreenTransform(), 1); overlay.addVideo(waiting, ScreenTransform(), 2)
                assertEquals(setOf("old"), overlay.positions().keys)
                overlay.addVideo(filled, ScreenTransform(), 2); overlay.finishVideoArea(screen, 2, setOf("new"))
                assertEquals(setOf("new"), overlay.positions().keys)
            }
        }
    }
    @Test fun lateOriginTextCanFillTheNewerFrameWithoutJumpingBack() = runBlocking<Unit> {
        fixture { overlay, image ->
            val old = overlay.prepare(image, listOf(text("old")), BubbleRenderSettings())
            val late = overlay.prepare(image, listOf(text("old", "Late completed text")), BubbleRenderSettings())
            val target = bounds.offset(5f, 7f)
            withContext(Dispatchers.Main) {
                overlay.addVideo(old, ScreenTransform(), 1); overlay.promoteVideo(mapOf("old" to target), 3)
                overlay.addVideo(late, ScreenTransform(), 1)
                assertEquals("Translated", overlay.sessionRows("video", 1).single().translatedText)
                overlay.addVideo(late, screenVideoRegionTransform(bounds, target), 3)
                assertEquals(mapOf("old" to target), overlay.positions())
                assertEquals("Late completed text", overlay.sessionRows("video", 1).single().translatedText)
            }
        }
    }
}
