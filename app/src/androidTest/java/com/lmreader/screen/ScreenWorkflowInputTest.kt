package com.lmreader.screen

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.vision.LocalVisionEngine
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.workflow.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ScreenWorkflowInputTest {
    @Test fun intersectingSourceCropRunsFreshOcrOnTheCompleteOriginalSentence() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = "screen-merge-${UUID.randomUUID()}"
        val page = ReaderPage(id, 0, "fixture", id)
        val original = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
        Canvas(original).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
            drawText("The rabbit is blue.", 60f, 180f, paint)
            drawText("Good morning, my friend!", 60f, 300f, paint)
        }
        val plan = screenMergedCrops(listOf(PixelRect(300f, 0f, 800f, 500f)),
            listOf(ScreenSourceRegion("old-fragment", PixelRect(50f, 135f, 290f, 185f))), PixelRect(0f, 0f, 800f, 500f), 64f).single()
        assertEquals(setOf("old-fragment"), plan.replaces)
        val image = Bitmap.createBitmap(original, plan.bounds.left.toInt(), plan.bounds.top.toInt(), plan.bounds.width.toInt(), plan.bounds.height.toInt())
        val source = object : PageSource {
            override suspend fun pages() = listOf(page)
            override suspend fun open(page: ReaderPage): java.io.InputStream = error("Merged image must remain a borrowed clean crop")
            override suspend fun probe(page: ReaderPage) = PageGeometry(image.width, image.height)
        }
        val vision = LocalVisionEngine(context, id) { VisionExecutionSettings(1, 1, false, OcrBackend.CPU) }
        val texts = java.util.concurrent.CopyOnWriteArrayList<String>()
        val detected = AtomicBoolean()
        val program = WorkflowTemplates.localMachine()
        val host = object : AndroidWorkflowHost(context, AppContainer.from(context), id, "fixture",
            listOf(WorkflowChapterInput("chapter", "fixture", source, listOf(page))),
            WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.ENGLISH, "", BubbleRenderSettings(), .35f,
                segTextScope = SegTextScope.FREE_TEXT), TranslationCacheBudget(128L * 1_048_576), reusePages = false, vision = vision,
            ephemeral = true, pageImages = mapOf(id to WorkflowPageImage(image, "1".repeat(64))),
            screenPreparation = screenVisionPreparation(program, "en", "en")) {
            override suspend fun regionsDetected(pageId: String, regions: List<SegRegion>) { detected.set(true); assertTrue(regions.isNotEmpty()) }
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
                if (kind != WorkflowKind.TRANSLATE) return super.request(kind, inputs, frame)
                assertTrue(detected.get())
                texts += (inputs.getValue("text") as WorkflowValue.Text).value
                return WorkflowValue.Text("Translated")
            }
        }
        try {
            withTimeout(60_000) { WorkflowRuntime().execute(program, host) }
            assertTrue("The complete sentence must be recognized from pixels: $texts", texts.any { it.lowercase().contains("the rabbit is blue") })
            assertTrue(texts.any { it.lowercase().contains("good morning") })
            assertTrue(host.published.getValue(id).regions.size >= 2)
        } finally {
            host.close(); if (image !== original) image.recycle(); original.recycle(); vision.releaseModels()
            val cache = File(context.filesDir, id)
            check(cache.canonicalFile.parentFile == context.filesDir.canonicalFile); cache.deleteRecursively()
        }
    }
    @Test fun borrowedPageIsNeverReopenedAndTranslationCanOverlapRemainingOcr() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val id = "screen-input-${UUID.randomUUID()}"
        val page = ReaderPage(id, 0, "fixture", id)
        val source = object : PageSource {
            override suspend fun pages() = listOf(page)
            override suspend fun open(page: ReaderPage): java.io.InputStream = error("Borrowed screenshot was reopened")
            override suspend fun probe(page: ReaderPage) = PageGeometry(800, 1200)
        }
        val image = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 40f }
        repeat(12) { canvas.drawText("Hello world number $it", 70f, 80f + it * 80f, paint) }
        val vision = LocalVisionEngine(context, id) { VisionExecutionSettings(1, 1, false, OcrBackend.CPU) }
        val ready = AtomicBoolean(); val overlapped = AtomicBoolean()
        val program = WorkflowTemplates.localMachine()
        val host = object : AndroidWorkflowHost(context, AppContainer.from(context), id, "fixture",
            listOf(WorkflowChapterInput("chapter", "fixture", source, listOf(page))),
            WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.ENGLISH, "", BubbleRenderSettings(), .35f,
                segTextScope = SegTextScope.FREE_TEXT, freeTextMergeGapRatio = 0f), TranslationCacheBudget(128L * 1_048_576),
            reusePages = false, vision = vision, ephemeral = true, pageImages = mapOf(id to WorkflowPageImage(image, "0".repeat(64))),
            screenPreparation = screenVisionPreparation(program, "en", "en")) {
            override suspend fun visionPrepared(pageId: String) { ready.set(true) }
            override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
                if (kind != WorkflowKind.TRANSLATE) return super.request(kind, inputs, frame)
                if (!ready.get()) overlapped.set(true)
                delay(80)
                return WorkflowValue.Text("Translated")
            }
        }
        try {
            withTimeout(60_000) { WorkflowRuntime().execute(program, host) }
            assertTrue(host.published.getValue(id).regions.size >= 4)
            assertTrue("Completed OCR regions should enter MT before all remaining regions finish", overlapped.get())
            assertTrue(ready.get())
            host.close()
            assertFalse("Only the screenshot owner may recycle it", image.isRecycled)
            assertFalse(vision.loadedResources.value.any { it.kind == InferenceEngineKind.SEG })
        } finally {
            host.close(); image.recycle(); vision.releaseModels()
            val cache = File(context.filesDir, id)
            check(cache.canonicalFile.parentFile == context.filesDir.canonicalFile); cache.deleteRecursively()
        }
    }
}
