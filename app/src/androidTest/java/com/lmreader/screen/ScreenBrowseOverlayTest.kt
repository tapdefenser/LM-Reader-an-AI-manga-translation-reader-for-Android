package com.lmreader.screen

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.MainActivity
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ScreenBrowseOverlayTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val screen = PixelRect(0f, 0f, 192f, 256f)
    private fun source(): Bitmap {
        val random = java.util.Random(6211)
        val colors = IntArray(192 * 256) { 0xff000000.toInt() or random.nextInt(0xffffff) }
        return Bitmap.createBitmap(colors, 192, 256, Bitmap.Config.ARGB_8888)
    }
    private fun text(id: String, bounds: PixelRect) = PageTranslatedRegion(
        PageTextRegion(id, RegionKind.FREE_TEXT, bounds, emptyList(), "Original", listOf(bounds)), "Translated")

    @Test fun mergedPublicationIsAtomicAndLateOldWorkCannotRestoreDeletedRegions() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source()
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                val inside = PixelRect(70f, 110f, 130f, 150f); val outside = PixelRect(10f, 20f, 60f, 60f)
                val first = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(text("old", inside), text("outside", outside)), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.replaceBrowse(first, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    1, ScreenMergedCrop(screen, emptySet()), emptyList()) }
                assertEquals(2, withContext(Dispatchers.Main) { overlay.count })
                val crop = ScreenMergedCrop(PixelRect(60f, 90f, 170f, 180f), setOf("old"))
                val merged = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(text("merged", crop.bounds)), BubbleRenderSettings()) }
                assertTrue("Preparing a replacement retains the old result", withContext(Dispatchers.Main) { "old" in overlay.positions() })
                withContext(Dispatchers.Main) { overlay.replaceBrowse(merged, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 2, crop, emptyList()) }
                assertEquals(setOf("outside", "merged"), withContext(Dispatchers.Main) { overlay.positions().keys })
                withContext(Dispatchers.Main) { overlay.replaceBrowse(first, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    1, ScreenMergedCrop(screen, emptySet()), emptyList()) }
                assertEquals(setOf("outside", "merged"), withContext(Dispatchers.Main) { overlay.positions().keys })
                assertEquals("Repeated publication cannot duplicate an unchanged object", 2, withContext(Dispatchers.Main) { overlay.count })
                val partial = ScreenMergedCrop(PixelRect(0f, 0f, 192f, 40f), emptySet(), listOf(ScreenSourceRegion("outside", outside)))
                val fragment = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(text("fragment", PixelRect(10f, 20f, 45f, 35f))), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.replaceBrowse(fragment, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    3, partial, emptyList(), screenExposedRegions(partial.bounds, outside)) }
                assertEquals("A clipped crop cannot replace complete old text with a fragment", setOf("outside", "merged"),
                    withContext(Dispatchers.Main) { overlay.positions().keys })
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }

    @Test fun contentZoomAndFixedSearchAreResolvedBeforeDrawingAndStaleDecisionsAreIgnored() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source()
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                val body = PixelRect(40f, 80f, 120f, 140f); val search = PixelRect(40f, 200f, 150f, 244f)
                val prepared = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(text("body", body), text("search", search)), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.replaceBrowse(prepared, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    1, ScreenMergedCrop(screen, emptySet()), emptyList()) }
                val pose = ScreenTransform(1.2f, -10f, -15f)
                val objects = withContext(Dispatchers.Main) { overlay.motionObjects(ScreenTransform()) }
                val decisions = objects.map { obj -> ScreenObjectDecision(obj.id, obj.revision,
                    if (obj.id == "search") ScreenTransform() else pose,
                    if (obj.id == "search") ScreenObjectAttachment.FIXED else ScreenObjectAttachment.CONTENT, 0, 1) }
                val invalidated = withContext(Dispatchers.Main) { overlay.follow(pose, pose, screen, 25, decisions) }
                assertTrue(invalidated.contains(search))
                val positions = withContext(Dispatchers.Main) { overlay.positions() }
                assertEquals(search, positions["search"]); assertEquals(pose.bounds(body), positions["body"])
                val stale = decisions.map { it.copy(sourceToScreen = ScreenTransform(y = 900f)) }
                withContext(Dispatchers.Main) { overlay.follow(pose, pose, screen, 25, stale) }
                assertEquals(positions, withContext(Dispatchers.Main) { overlay.positions() })
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }
}
