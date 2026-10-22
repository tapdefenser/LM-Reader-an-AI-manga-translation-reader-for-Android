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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    @Test fun bubblesSentToApiLoadOnlySegDuringPreparationAndActualSegmentation() = runBlocking {
        val engine = imageEngine(SegTextScope.BUBBLES)
        withTimeout(120_000) { engine.prepare() }
        val prepared = awaitResources(engine) { it.hasSegmenter() }
        assertEquals(setOf("seg:0:seg"), prepared.ids())
        val detection = withTimeout(120_000) { engine.detect(bitmapWithText(), 0) }
        assertNotNull(detection)
        assertTrue(requireNotNull(detection).result.textLines.isEmpty())
        assertEquals(setOf("seg:0:seg"), awaitResources(engine) { it.hasSegmenter() }.ids())
    }

    @Test fun bubblesWithOcrLoadWorkflowModelsAndRecognizeInsideTheBubble() = runBlocking {
        val engine = VideoTranslationEngine(container, project().copy(target = "en"),
            TranslationWorkflow.LOCAL_MACHINE, textScope = SegTextScope.BUBBLES)
        engines += engine
        withTimeout(120_000) { engine.prepare() }
        val prepared = awaitResources(engine) { it.hasSegmenter() && it.hasDetector() && it.hasRecognizer() }
        assertEquals(setOf("seg:0:seg", "ocr:0:det", "ocr:0:rec"), prepared.ids())
        val bitmap = bitmapWithText()
        val region = SegRegion("${engine.pageId(0)}:seg:0", RegionKind.BUBBLE,
            PixelRect(16f, 16f, 312f, 160f), 1f)
        val detection = com.lmreader.ui.workflow.WorkflowPageSegmentation(
            SegResult(engine.pageId(0), bitmap.width, bitmap.height, listOf(region), 0, "fixture"), listOf(region))
        val result = withTimeout(120_000) { engine.frame(bitmap, 0, detection, emptySet()) }
        assertTrue("OCR must detect and recognize bubble text even when block SEG supplies no lines: ${result.regions}",
            result.regions.any { it.region.sourceText.contains("HELLO", ignoreCase = true) })
    }

    @Test fun twoVideoOwnersShareTheConfiguredSingleSegSlot() = runBlocking {
        val first = imageEngine(SegTextScope.BUBBLES)
        val second = imageEngine(SegTextScope.BUBBLES)
        withTimeout(120_000) { first.prepare(); second.prepare() }
        val active = CopyOnWriteArrayList<Int>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { first.vision.activeSeg.collect { active += it } }
        try {
            val bitmap = bitmapWithText()
            withTimeout(120_000) { coroutineScope {
                awaitAll(async { repeat(6) { first.detect(bitmap, 1000L + it) } },
                    async { repeat(6) { second.detect(bitmap, 2000L + it) } })
            } }
            assertTrue("The configured slot must actually execute work: $active", active.any { it == 1 })
            assertTrue("Owners must share the setting, not each receive a private slot: $active", active.all { it <= 1 })
            assertEquals(0, first.vision.activeSeg.value)
        } finally { watcher.cancelAndJoin() }
    }

    @Test fun warmingActiveSubsetPreservesTheRetainedUnionOfQueuedModels() = runBlocking {
        val engine = imageEngine(SegTextScope.FREE_TEXT)
        val union = VisionModelDemand(true, true, setOf(LocalOcrLanguage.ENGLISH))
        withTimeout(120_000) { engine.vision.reconfigure(union) }
        val original = awaitResources(engine) { it.hasSegmenter() && it.hasDetector() && it.hasRecognizer() }.ids()
        withTimeout(120_000) { engine.vision.warmModels(VisionModelDemand.blocks(SegTextScope.FREE_TEXT)) }
        assertEquals(original, awaitResources(engine) { it.hasSegmenter() && it.hasDetector() && it.hasRecognizer() }.ids())
    }

    @Test fun threeRealSegSlotsAcrossOwnersDrainToOneAfterGlobalLimitIsReduced() = runBlocking {
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 3) }
        withTimeout(10_000) { container.visionExecutionPreferences.settings.first { it.segConcurrency == 3 } }
        val owners = listOf(imageEngine(SegTextScope.BUBBLES), imageEngine(SegTextScope.BUBBLES))
        withTimeout(120_000) { owners.forEach { it.prepare() } }
        val entered = AtomicInteger()
        val firstThree = CountDownLatch(3)
        val release = CountDownLatch(1)
        val laterCounts = CopyOnWriteArrayList<Int>()
        val bitmap = bitmapWithText()
        coroutineScope {
            val jobs = List(5) { index -> async(Dispatchers.Default) {
                val once = AtomicBoolean()
                val vision = owners[index % owners.size].vision
                vision.segment("quota-$index", bitmap, scope = SegTextScope.BUBBLES) { progress ->
                    if (progress.stage == "气泡分割" && once.compareAndSet(false, true)) {
                        if (entered.incrementAndGet() <= 3) {
                            firstThree.countDown()
                            check(release.await(90, TimeUnit.SECONDS)) { "Test gate timed out" }
                        } else laterCounts += vision.activeSeg.value
                    }
                }
            } }
            try {
                assertTrue("Three actual model instances must enter before any finishes", withContext(Dispatchers.IO) {
                    firstThree.await(90, TimeUnit.SECONDS)
                })
                assertEquals(3, owners[0].vision.activeSeg.value)
                awaitResources(owners[0]) { resources -> resources.count { it.isSegmenter() } == 3 }
                container.visionExecutionPreferences.update { it.copy(segConcurrency = 1) }
                withTimeout(10_000) { container.visionExecutionPreferences.settings.first { it.segConcurrency == 1 } }
                owners[0].vision.configureExecution()
                assertEquals("An admitted call must remain owned until it returns", 3, owners[0].vision.activeSeg.value)
            } finally { release.countDown() }
            withTimeout(120_000) { jobs.awaitAll() }
        }
        assertEquals(2, laterCounts.size)
        assertTrue("Existing waiters must use the new shared limit: $laterCounts", laterCounts.all { it == 1 })
        assertEquals(0, owners[0].vision.activeSeg.value)
        assertEquals(1, awaitResources(owners[0]) { it.count { model -> model.isSegmenter() } == 1 }.count { it.isSegmenter() })
    }

    @Test fun screenAndEngineSettingsShareCountsWithoutReplacingBackendChoices() = runBlocking {
        val screen = com.lmreader.core.storage.settings.ScreenTranslationPreferences(app)
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 3, ocrConcurrency = 2) }
        assertEquals(3, screen.current().vision.segConcurrency)
        assertEquals(2, screen.current().vision.ocrConcurrency)
        screen.update { it.copy(vision = it.vision.copy(segConcurrency = 1, ocrConcurrency = 4, segGpu = true)) }
        val global = withTimeout(10_000) { container.visionExecutionPreferences.settings.first { it.segConcurrency == 1 && it.ocrConcurrency == 4 } }
        assertFalse(global.segGpu)
        assertTrue(screen.current().vision.segGpu)
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 2, ocrConcurrency = 1) }
        assertEquals(2, screen.current().vision.segConcurrency)
        assertEquals(1, screen.current().vision.ocrConcurrency)
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
