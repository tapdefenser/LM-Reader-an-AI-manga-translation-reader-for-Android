package com.lmreader.core.vision

import android.graphics.*
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled models, generated images, and an isolated instrumentation package. */
@RunWith(AndroidJUnit4::class)
class SegTextDetectionIntegrationTest {
    private fun engine() = LocalVisionEngine(ApplicationProvider.getApplicationContext()) {
        VisionExecutionSettings(segConcurrency = 1, ocrConcurrency = 1, segGpu = false, ocrBackend = OcrBackend.CPU)
    }
    private fun fixture(): Bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888).also { image ->
        Canvas(image).apply {
            drawColor(Color.WHITE)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f }
            drawRect(20f, 20f, 780f, 980f, pen)
            drawOval(100f, 100f, 700f, 460f, pen)
            drawLine(470f, 454f, 520f, 530f, pen); drawLine(520f, 530f, 560f, 446f, pen)
            drawCircle(400f, 720f, 100f, pen)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 43f; typeface = Typeface.DEFAULT_BOLD }
            drawText("OUTSIDE TEXT", 130f, 930f, paint)
            paint.textSize = 28f
            drawText("HELLO", 352f, 708f, paint); drawText("WORLD", 347f, 753f, paint)
        }
    }
    @Test fun allScopesDetectCaptionsWithoutOcrAndRecognitionReusesSegLines() = runBlocking<Unit> {
        val engine = engine(); val image = fixture()
        try {
            var detectionCountedAsOcr = false
            val seg = engine.segment("seg-only", image) { progress ->
                if (progress.stage.startsWith("文字行检测")) {
                    assertEquals(1, engine.activeOcr.value); assertEquals(0, engine.activeSeg.value)
                    detectionCountedAsOcr = true
                }
            }
            assertTrue(detectionCountedAsOcr)
            assertTrue(seg.textLines.isNotEmpty())
            val bubbles = selectSegRegions(seg, SegTextScope.BUBBLES)
            val free = selectSegRegions(seg, SegTextScope.FREE_TEXT)
            assertTrue("No speech bubble: ${seg.regions}", bubbles.isNotEmpty())
            assertTrue("No caption: ${seg.regions}", free.any { it.bounds.top > 650f })
            assertTrue(bubbles.all { it.kind == RegionKind.BUBBLE })
            assertTrue(free.all { it.kind == RegionKind.FREE_TEXT })
            assertEquals((bubbles + free).map { it.id }.toSet(), selectSegRegions(seg).map { it.id }.toSet())
            val loaded = withTimeout(5000) { engine.loadedResources.first { it.any { resource -> resource.id.endsWith(":det") } } }
            assertEquals(2, loaded.size)
            assertTrue(loaded.any { it.kind == InferenceEngineKind.SEG && it.id.startsWith("seg:") })
            assertTrue(loaded.any { it.kind == InferenceEngineKind.OCR && it.id.startsWith("ocr:") && it.id.endsWith(":det") })
            assertTrue(loaded.first { it.id.endsWith(":det") }.backend.contains("CPU"))
            engine.retainModels(true, emptySet())
            assertEquals(loaded.map { it.id }.toSet(), engine.loadedResources.first { it.size == 2 }.map { it.id }.toSet())
            val stages = mutableListOf<String>()
            val speechRegion = bubbles.first { region -> selectRegionTextLines(region,seg.regions,seg.textLines).any { it.bounds.top > 650f && it.bounds.bottom < 850f } }
            val speech = engine.recognizeRegion(seg.imageId, image, LocalOcrLanguage.ENGLISH, speechRegion, seg.regions, seg.textLines) { stages += it.stage }
            val caption = engine.recognizeRegion(seg.imageId, image, LocalOcrLanguage.ENGLISH, free.first { it.bounds.top > 650f }, seg.regions, seg.textLines) { stages += it.stage }
            Log.i("SegDetection", "bubbles=${bubbles.size}, free=${free.size}, speech=${speech.text}, caption=${caption.text}, resources=${engine.loadedResources.value}")
            assertTrue(speech.text, speech.text.contains("HELLO")); assertFalse(speech.text.contains("OUTSIDE"))
            assertTrue(caption.text, caption.text.contains("OUTSIDE")); assertFalse(caption.text.contains("HELLO"))
            assertTrue(caption.lines.all { it.bounds.top > 650f && it.bounds.right <= image.width && it.bounds.bottom <= image.height })
            assertTrue(stages.none { it.contains("检测") })
            val after = withTimeout(5000) { engine.loadedResources.first { it.any { resource -> resource.id.endsWith(":rec") } } }
            assertEquals(1, after.count { it.id.endsWith(":det") })
            assertEquals(setOf("det", "rec"), after.filter { it.kind == InferenceEngineKind.OCR }.map { it.id.substringAfterLast(":") }.toSet())
            engine.retainModels(true, emptySet())
            withTimeout(5000) { engine.loadedResources.first { it.size == 2 && it.none { resource -> resource.id.endsWith(":rec") } } }
            engine.releaseModels()
            withTimeout(5000) { engine.loadedResources.first { it.isEmpty() } }
        } finally { engine.releaseModels(); image.recycle() }
    }
    @Test fun standaloneOcrKeepsDetectionAndRecognitionInSharedOcrPool() = runBlocking<Unit> {
        val engine = engine(); val image = fixture()
        try {
            val result = engine.recognize("ocr-compat", image, LocalOcrLanguage.ENGLISH)
            assertTrue(result.text, result.text.contains("HELLO") && result.text.contains("OUTSIDE"))
            val resources = withTimeout(5000) { engine.loadedResources.first { it.size == 2 } }
            assertTrue(resources.any { it.id.startsWith("ocr:") && it.id.endsWith(":det") && it.kind == InferenceEngineKind.OCR })
            assertTrue(resources.any { it.id.startsWith("ocr:") && it.id.endsWith(":rec") && it.kind == InferenceEngineKind.OCR })
            engine.retainModels(false, setOf(LocalOcrLanguage.ENGLISH))
            assertEquals(2, engine.loadedResources.value.size)
        } finally { engine.releaseModels(); image.recycle() }
    }
}
