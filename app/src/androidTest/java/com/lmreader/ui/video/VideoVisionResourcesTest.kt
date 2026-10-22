package com.lmreader.ui.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Resource-demand contracts for [VideoTranslationEngine.prepare].
 *
 * These tests use the real engine, the real shared [com.lmreader.core.vision.LocalVisionEngine]
 * pools and the real model assets. The fixed API profile points at a closed loopback port:
 * prepare/detect never issue an HTTP request, so no provider is contacted.
 */
@RunWith(AndroidJUnit4::class)
class VideoVisionResourcesTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private var previousVision: VisionExecutionSettings? = null
    private val engines = mutableListOf<VideoTranslationEngine>()
    private val bitmaps = mutableListOf<Bitmap>()

    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app)
        previousVision = withTimeout(10_000) { container.visionExecutionPreferences.settings.first() }
        container.visionExecutionPreferences.update {
            it.copy(segGpu = false, ocrBackend = OcrBackend.CPU, segConcurrency = 1, ocrConcurrency = 1)
        }
        withTimeout(10_000) {
            container.visionExecutionPreferences.settings.first {
                !it.segGpu && it.ocrBackend == OcrBackend.CPU && it.segConcurrency == 1 && it.ocrConcurrency == 1
            }
        }
        Unit
    }

    @After fun cleanup() = runBlocking {
        withContext(NonCancellable) {
            engines.toList().forEach { engine -> runCatching { engine.close() } }
            engines.clear()
            bitmaps.forEach { bitmap -> runCatching { if (!bitmap.isRecycled) bitmap.recycle() } }
            bitmaps.clear()
            previousVision?.let { original ->
                runCatching { container.visionExecutionPreferences.update { original } }
            }
            if (::container.isInitialized) {
                runCatching { container.localVision.close() }
                runCatching { closeContainer(container) }
            }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
        Unit
    }

    @Test fun freeTextPrepareLoadsDetectorOnlyAndRealDetectAddsNoBubbleSeg() = runBlocking {
        val engine = imageEngine(SegTextScope.FREE_TEXT)
        val bitmap = bitmapWithText()
        withTimeout(120_000) { engine.prepare() }

        val prepared = awaitResources(engine) { it.hasDetector() }
        assertEquals("FREE_TEXT must warm exactly one detector: $prepared", 1, prepared.count { it.isDetector() })
        assertEquals(setOf("ocr:0:det"), prepared.filter { it.isDetector() }.map { it.id }.toSet())
        assertFalse("FREE_TEXT must not warm the bubble SEG model: $prepared", prepared.hasSegmenter())
        assertFalse("VISION_API has no OCR node, so no recognizer is needed: $prepared", prepared.hasRecognizer())

        val detection = withTimeout(120_000) { engine.detect(bitmap, 0) }
        assertNotNull("The VISION_API SEG node must expose detection", detection)
        val segmentation = requireNotNull(detection)
        assertTrue("FREE_TEXT detection may only produce free-text regions: ${segmentation.regions}",
            segmentation.regions.all { it.kind == RegionKind.FREE_TEXT })

        val afterDetection = awaitResources(engine) { it.hasDetector() }
        assertEquals(setOf("ocr:0:det"), afterDetection.ids())
        assertFalse("Real DET-only detection must not load bubble SEG: $afterDetection", afterDetection.hasSegmenter())
        assertFalse(afterDetection.hasRecognizer())
    }

    @Test fun freeTextReprepareRemovesSeededSegAndRetainsDetectorIdentityAcrossRepeats() = runBlocking {
        val engine = imageEngine(SegTextScope.FREE_TEXT)
        withTimeout(120_000) { engine.vision.reconfigure(true, emptySet(), true) }
        val seeded = awaitResources(engine) { it.hasSegmenter() && it.hasDetector() }
        val segId = seeded.single { it.isSegmenter() }.id
        val detId = seeded.single { it.isDetector() }.id
        assertEquals(setOf(segId, detId), seeded.ids())
        assertFalse(seeded.hasRecognizer())

        withTimeout(120_000) { engine.prepare() }
        val reconciled = awaitResources(engine) { it.hasDetector() && !it.hasSegmenter() }
        assertEquals(setOf(detId), reconciled.ids())
        assertFalse(reconciled.hasRecognizer())

        val observed = CopyOnWriteArrayList<List<LoadedEngineResource>>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.vision.loadedResources.collect { resources -> observed += resources }
        }
        try {
            withTimeout(120_000) { engine.prepare() }
            assertEquals(setOf(detId), awaitResources(engine) { it.hasDetector() }.ids())
            withTimeout(120_000) { engine.prepare() }
            assertEquals(setOf(detId), awaitResources(engine) { it.hasDetector() }.ids())
            assertTrue("Repeated FREE_TEXT prepare must not drop or replace DET: $observed",
                observed.isNotEmpty() && observed.all { it.ids() == setOf(detId) })
        } finally {
            watcher.cancelAndJoin()
        }
    }

    @Test fun concurrentAllAndFreeTextOwnersKeepSegUntilTheAllOwnerCloses() = runBlocking {
        val all = imageEngine(SegTextScope.ALL)
        val freeText = imageEngine(SegTextScope.FREE_TEXT)
        withTimeout(180_000) {
            coroutineScope {
                awaitAll(async { all.prepare() }, async { freeText.prepare() })
            }
        }
        val shared = awaitResources(freeText) { it.hasSegmenter() && it.hasDetector() }
        val segId = shared.single { it.isSegmenter() }.id
        val detId = shared.single { it.isDetector() }.id
        assertEquals(setOf(segId, detId), shared.ids())
        assertFalse(shared.hasRecognizer())

        val probe = container.localVision
        withTimeout(30_000) { probe.loadedResources.first { it.hasSegmenter() && it.hasDetector() } }

        all.close()
        val afterAll = withTimeout(60_000) {
            freeText.vision.loadedResources.first { !it.hasSegmenter() && it.hasDetector() }
        }
        assertEquals(setOf(detId), afterAll.ids())

        freeText.close()
        val cleared = withTimeout(60_000) { probe.loadedResources.first { it.isEmpty() } }
        assertTrue("Closing the last owner must clear every shared vision model: $cleared", cleared.isEmpty())
    }

    @Test fun imageLessSubtitleDemandIsZeroAndDoesNotDrainImageOwnerModels() = runBlocking {
        val subtitleOnly = subtitleEngine()
        val subtitleDemand = CopyOnWriteArrayList<List<LoadedEngineResource>>()
        val subtitleWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            subtitleOnly.vision.loadedResources.collect { resources -> subtitleDemand += resources }
        }
        try {
            withTimeout(120_000) { subtitleOnly.prepare() }
            assertTrue("TIMED_TEXT must demand no vision model: $subtitleDemand",
                subtitleDemand.isNotEmpty() && subtitleDemand.all { it.isEmpty() })
            assertTrue("TIMED_TEXT leaves the shared probe empty: ${subtitleOnly.vision.loadedResources.value}",
                subtitleOnly.vision.loadedResources.value.isEmpty())
        } finally {
            subtitleWatcher.cancelAndJoin()
        }
        subtitleOnly.close()

        val image = imageEngine(SegTextScope.ALL)
        withTimeout(120_000) { image.prepare() }
        val imageModels = awaitResources(image) { it.hasSegmenter() && it.hasDetector() }
        val segId = imageModels.single { it.isSegmenter() }.id
        val detId = imageModels.single { it.isDetector() }.id

        val probe = container.localVision
        withTimeout(30_000) { probe.loadedResources.first { it.hasSegmenter() && it.hasDetector() } }

        val subtitle = subtitleEngine()
        withTimeout(120_000) { subtitle.prepare() }
        val preserving = awaitResources(image) { it.hasSegmenter() && it.hasDetector() }
        assertEquals(setOf(segId, detId), preserving.ids())
        assertEquals(setOf(segId, detId), awaitResources(subtitle) { it.hasSegmenter() && it.hasDetector() }.ids())

        subtitle.close()
        val afterSubtitle = awaitResources(image) { it.hasSegmenter() && it.hasDetector() }
        assertEquals(setOf(segId, detId), afterSubtitle.ids())

        image.close()
        val cleared = withTimeout(60_000) { probe.loadedResources.first { it.isEmpty() } }
        assertTrue("Closing the image owner must clear its shared models: $cleared", cleared.isEmpty())
    }

    private fun imageEngine(scope: SegTextScope): VideoTranslationEngine {
        val engine = VideoTranslationEngine(container, project(),
            TranslationWorkflow.VISION_API.copy(program = WorkflowTemplates.visionApi(PROFILE_ID)),
            listOf(PROFILE), textScope = scope)
        engines += engine
        return engine
    }

    private fun subtitleEngine(): VideoTranslationEngine {
        val engine = VideoTranslationEngine(container, project(),
            TranslationWorkflow.SUBTITLE_API.copy(program = SubtitleWorkflowTemplates.program(PROFILE_ID)),
            listOf(PROFILE))
        engines += engine
        return engine
    }

    private suspend fun awaitResources(engine: VideoTranslationEngine,
        predicate: (List<LoadedEngineResource>) -> Boolean): List<LoadedEngineResource> =
        withTimeout(30_000) { engine.vision.loadedResources.first(predicate) }

    private fun List<LoadedEngineResource>.ids() = map { it.id }.toSet()
    private fun List<LoadedEngineResource>.hasSegmenter() = any { it.isSegmenter() }
    private fun List<LoadedEngineResource>.hasDetector() = any { it.isDetector() }
    private fun List<LoadedEngineResource>.hasRecognizer() = any { it.isRecognizer() }
    private fun LoadedEngineResource.isSegmenter() = kind == InferenceEngineKind.SEG && id.endsWith(":seg")
    private fun LoadedEngineResource.isDetector() = kind == InferenceEngineKind.OCR && id.endsWith(":det")
    private fun LoadedEngineResource.isRecognizer() = id.endsWith(":rec") || id.endsWith(":ko")

    private fun project() = VideoProject(UUID.randomUUID().toString(), "content://fixture/local", "fixture",
        "d".repeat(64), 0, 8000, 320, 180, 0, source = "en", target = "zh-Hans")

    private fun bitmapWithText(): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("HELLO VISION", 30f, 100f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
        })
        bitmaps += bitmap
        return bitmap
    }

    private companion object {
        const val PROFILE_ID = "fixture"
        val PROFILE = ApiProfile(PROFILE_ID, ApiProfileKind.LLM, "loopback fixture",
            "http://127.0.0.1:9/v1", model = "fixture", retryCount = 0)
    }
}
