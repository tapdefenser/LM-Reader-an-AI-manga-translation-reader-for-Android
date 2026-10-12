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

    @Test fun failedReplacementWithdrawsEmptyMasksAndRestoresReadableOldTextToTracking() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source(); val bounds = PixelRect(20f, 40f, 140f, 140f)
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                val old = overlay.prepare(image, listOf(text("old", bounds)), BubbleRenderSettings())
                val pending = overlay.prepare(image, listOf(text("failed", bounds).copy(translatedText = "")), BubbleRenderSettings(), true)
                withContext(Dispatchers.Main) {
                    overlay.replaceBrowse(old, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 1,
                        ScreenMergedCrop(screen, emptySet()), emptyList())
                    overlay.replaceBrowse(pending, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 2,
                        ScreenMergedCrop(screen, setOf("old"), replacedSources = listOf(ScreenSourceRegion("old", bounds, 1))), emptyList())
                    assertEquals(setOf("failed"), overlay.motionObjects(ScreenTransform()).map { it.id }.toSet())
                    assertEquals(setOf("old"), overlay.discardFailedEmpty(setOf("failed")))
                    assertEquals(setOf("old"), overlay.positions().keys)
                    assertEquals(setOf("old"), overlay.motionObjects(ScreenTransform()).map { it.id }.toSet())
                    assertEquals(setOf("old"), overlay.sourceRegions(ScreenTransform()).map { it.id }.toSet())
                    assertEquals("FILLED", overlay.sessionRows("session", 1).single().status)
                    assertEquals("Translated", overlay.sessionRows("session", 1).single().translatedText)
                }
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }

    @Test fun supersededEmptyMaskIsWithdrawnAndCannotBeFilledByLateOldWork() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source()
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            val oldBounds = PixelRect(20f, 40f, 130f, 100f)
            val newBounds = PixelRect(20f, 40f, 160f, 120f)
            try {
                val old = overlay.prepare(image, listOf(text("old", oldBounds).copy(translatedText = "")), BubbleRenderSettings(), true)
                val next = overlay.prepare(image, listOf(text("new", newBounds).copy(translatedText = "")), BubbleRenderSettings(), true)
                withContext(Dispatchers.Main) {
                    overlay.replaceBrowse(old, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 1,
                        ScreenMergedCrop(screen, emptySet()), emptyList())
                    val cancelled = overlay.replaceBrowse(next, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 2,
                        ScreenMergedCrop(screen, setOf("old"), replacedSources = listOf(ScreenSourceRegion("old", oldBounds, 1))), emptyList())
                    assertEquals(setOf("old"), cancelled)
                    assertEquals(setOf("new"), overlay.positions().keys)
                    assertEquals("PENDING", overlay.sessionRows("session", 1).single().status)
                    assertEquals(setOf("new"), overlay.motionObjects(ScreenTransform()).map { it.id }.toSet())
                }
                val late = overlay.prepare(image, listOf(text("old", oldBounds)), BubbleRenderSettings())
                withContext(Dispatchers.Main) {
                    overlay.replaceBrowse(late, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 1,
                        ScreenMergedCrop(screen, emptySet()), emptyList())
                    assertEquals(setOf("new"), overlay.positions().keys)
                }
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }
    @Test fun repeatedMergesRedirectOldTextToTheFinalMaskAndRetireItOnlyAfterFinalFill() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source(); val bounds = PixelRect(20f, 40f, 140f, 140f)
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                suspend fun publish(id: String, version: Long, old: String?, filled: Boolean) {
                    val prepared = withContext(Dispatchers.Default) { overlay.prepare(image,
                        listOf(text(id, bounds).let { if (filled) it else it.copy(translatedText = "") }), BubbleRenderSettings(), true) }
                    withContext(Dispatchers.Main) { overlay.replaceBrowse(prepared, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                        version, ScreenMergedCrop(screen, setOfNotNull(old), replacedSources = old?.let { listOf(ScreenSourceRegion(it, bounds, version - 1)) }.orEmpty()), emptyList()) }
                }
                publish("readable", 1, null, true); publish("intermediate", 2, "readable", false)
                withContext(Dispatchers.Main) {
                    val old = overlay.sessionRows("session", 1).first { it.id == "readable" }
                    assertEquals("OBSOLETE", old.status); assertEquals("Translated", old.translatedText)
                    assertEquals("[\"intermediate\"]", old.successors)
                    assertEquals(setOf("intermediate"), overlay.sourceRegions(ScreenTransform()).map { it.id }.toSet())
                }
                publish("final", 3, "intermediate", false)
                withContext(Dispatchers.Main) {
                    assertEquals(setOf("readable", "final"), overlay.positions().keys)
                    assertEquals("[\"final\"]", overlay.sessionRows("session", 1).first { it.id == "readable" }.successors)
                }
                publish("final", 3, "intermediate", true)
                withContext(Dispatchers.Main) {
                    assertEquals(setOf("final"), overlay.positions().keys)
                    assertEquals("FILLED", overlay.sessionRows("session", 1).single().status)
                }
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }

    @Test fun largeStationaryControlsDoNotMakeMovingContentLookLikeAnOpaquePanel() = runBlocking<Unit> {
        val pixels = ByteArray(192 * 256).also { java.util.Random(8511).nextBytes(it) }
        val before = ScreenGrayFrame(192, 256, pixels)
        val after = before.copy(pixels = ByteArray(pixels.size) { i ->
            val y = i / 192
            if (y < 64 || y >= 192) pixels[i] else pixels[(y + 8) * 192 + i % 192]
        })
        val tracker = ScreenBrowseTracker {}
        val now = android.os.SystemClock.uptimeMillis()
        tracker.register(CapturedMotion(before, screen, 1, now - 33), 1)
        val observed = tracker.register(CapturedMotion(after, screen, 2, now), 1)
        assertTrue("A stationary header/footer must not mask the scrolling body", tracker.visualOcclusions.isEmpty())
        assertTrue(observed.reliable)
        assertEquals(-8f, observed.capturePose.y, 1f)
    }

    @Test fun videoPartialBackfillsKeepOldTextAndCompletionRemovesOnlyItsOwnLeftovers() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source()
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                val area = PixelRect(0f, 80f, 192f, 256f)
                val initial = listOf(text("old-top", PixelRect(20f, 100f, 100f, 130f)),
                    text("old-bottom", PixelRect(20f, 170f, 100f, 210f)), text("outside", PixelRect(20f, 10f, 100f, 40f)))
                val ready = listOf(text("new-top", PixelRect(20f, 100f, 100f, 130f)))
                val future = listOf(text("future-bottom", PixelRect(20f, 215f, 100f, 245f)))
                val first = withContext(Dispatchers.Default) { overlay.prepare(image, initial, BubbleRenderSettings()) }
                val partial = withContext(Dispatchers.Default) { overlay.prepare(image, ready, BubbleRenderSettings()) }
                val newer = withContext(Dispatchers.Default) { overlay.prepare(image, future, BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.add(first, ScreenTransform(), 1); overlay.add(partial, ScreenTransform(), 2) }
                assertEquals("A pending sibling keeps the old text", setOf("new-top", "old-bottom", "outside"),
                    withContext(Dispatchers.Main) { overlay.positions(false).keys })
                withContext(Dispatchers.Main) { overlay.add(newer, ScreenTransform(), 3); overlay.finishVideoArea(area, 2, setOf("new-top")) }
                assertEquals("Completion preserves ready text, unrelated areas and newer results", setOf("new-top", "future-bottom", "outside"),
                    withContext(Dispatchers.Main) { overlay.positions(false).keys })
                withContext(Dispatchers.Main) { overlay.finishVideoArea(area, 3, emptySet()) }
                assertEquals("A complete empty frame clears its old subtitles", setOf("outside"),
                    withContext(Dispatchers.Main) { overlay.positions(false).keys })
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }

    @Test fun detectionMasksTrackImmediatelyAndSplitReplacementsFillWithoutRewindingTheirPose() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source()
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                val old = PixelRect(40f, 60f, 140f, 160f)
                val first = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(text("old", old)), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.replaceBrowse(first, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    1, ScreenMergedCrop(screen, emptySet()), emptyList()) }
                val a = text("new-a", PixelRect(40f, 60f, 140f, 110f))
                val b = text("new-b", PixelRect(40f, 110f, 140f, 160f))
                val plan = listOf(ScreenSourceRegion(a.region.id, a.region.bounds, 2), ScreenSourceRegion(b.region.id, b.region.bounds, 2))
                val crop = ScreenMergedCrop(old, setOf("old"), replacedSources = listOf(ScreenSourceRegion("old", old, 1)))
                val masks = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(a, b).map { it.copy(translatedText = "") }, BubbleRenderSettings(), showEmpty = true) }
                withContext(Dispatchers.Main) {
                    overlay.protectReplacements("page", crop.replaces)
                    overlay.replaceBrowse(masks, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(), 2, crop, emptyList(), detected = plan)
                }
                assertEquals(setOf("old", "new-a", "new-b"), withContext(Dispatchers.Main) { overlay.positions().keys })
                assertEquals(setOf("old"), withContext(Dispatchers.Main) { overlay.positions(false).keys })
                val zoom = ScreenTransform(1.15f, -8f, -15f)
                withContext(Dispatchers.Main) { overlay.follow(zoom, zoom, screen, 25, emptyList()) }
                val atZoom = withContext(Dispatchers.Main) { overlay.positions() }
                val stale = withContext(Dispatchers.Main) { overlay.motionObjects(zoom).first { it.id == "new-a" } }
                val readyA = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(a), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.replaceBrowse(readyA, ScreenTransform(), ScreenTransform(), zoom, zoom, 2, crop, emptyList(), detected = plan) }
                assertEquals("Text must fill the already tracked mask", atZoom["new-a"], withContext(Dispatchers.Main) { overlay.positions()["new-a"] })
                assertEquals(setOf("old", "new-a"), withContext(Dispatchers.Main) { overlay.positions(false).keys })
                withContext(Dispatchers.Main) { overlay.follow(zoom, zoom, screen, 25, listOf(
                    ScreenObjectDecision(stale.id, stale.revision, ScreenTransform(), ScreenObjectAttachment.CONTENT, 2, 7, remove = true))) }
                assertTrue("An in-flight decision cannot erase a newly filled object", withContext(Dispatchers.Main) { "new-a" in overlay.positions(false) })
                val readyB = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(b), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) {
                    overlay.replaceBrowse(readyB, ScreenTransform(), ScreenTransform(), zoom, zoom, 2, crop, emptyList(), detected = plan)
                    overlay.releaseReplacements("page")
                }
                assertEquals(setOf("new-a", "new-b"), withContext(Dispatchers.Main) { overlay.positions(false).keys })
                withContext(Dispatchers.Main) { overlay.replaceBrowse(emptyList(), ScreenTransform(), zoom, zoom, zoom, 3,
                    ScreenMergedCrop(screen, setOf("new-a", "new-b")), emptyList()) }
                assertEquals("Empty OCR output cannot erase preceding translations", setOf("new-a", "new-b"),
                    withContext(Dispatchers.Main) { overlay.positions(false).keys })
                withContext(Dispatchers.Main) { overlay.replaceBrowse(first, ScreenTransform(), ScreenTransform(), zoom, zoom, 1,
                    ScreenMergedCrop(screen, emptySet()), emptyList()) }
                assertFalse(withContext(Dispatchers.Main) { "old" in overlay.positions() })
            } finally { withContext(Dispatchers.Main) { overlay.close() }; image.recycle() }
        }
    }

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

    @Test fun similarityRemovalInvalidatesCoverageAndLateTextCannotResurrectIt() = runBlocking<Unit> {
        ActivityScenario.launch(MainActivity::class.java).use {
            val image = source()
            val overlay = withContext(Dispatchers.Main) { ScreenOverlay(context, false) {} }
            try {
                val area = PixelRect(40f, 80f, 120f, 140f)
                val prepared = withContext(Dispatchers.Default) { overlay.prepare(image, listOf(text("bad", area)), BubbleRenderSettings()) }
                withContext(Dispatchers.Main) { overlay.replaceBrowse(prepared, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    1, ScreenMergedCrop(screen, emptySet()), emptyList()) }
                val obj = withContext(Dispatchers.Main) { overlay.motionObjects(ScreenTransform()).single() }
                val removed = mutableSetOf<String>()
                val invalidated = withContext(Dispatchers.Main) { overlay.follow(ScreenTransform(), ScreenTransform(), screen, 25,
                    listOf(ScreenObjectDecision(obj.id, obj.revision, ScreenTransform(), ScreenObjectAttachment.CONTENT, 2, 2, remove = true))) { removed += it } }
                assertEquals(setOf("bad"), removed)
                assertTrue(invalidated.contains(area))
                withContext(Dispatchers.Main) { overlay.replaceBrowse(prepared, ScreenTransform(), ScreenTransform(), ScreenTransform(), ScreenTransform(),
                    1, ScreenMergedCrop(screen, emptySet()), emptyList()) }
                assertTrue(withContext(Dispatchers.Main) { overlay.positions().isEmpty() })
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
