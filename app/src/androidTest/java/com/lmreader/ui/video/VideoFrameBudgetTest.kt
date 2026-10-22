package com.lmreader.ui.video

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.PageTextRegion
import com.lmreader.core.model.PageTranslatedRegion
import com.lmreader.core.model.PixelRect
import com.lmreader.core.model.RegionKind
import com.lmreader.core.model.TranslationWorkflow
import com.lmreader.core.model.VideoSamplingPlan
import com.lmreader.core.model.VideoSamplingRate
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskExecution
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.workflow.WorkflowPageSegmentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private const val FRAME_BUDGET_LIMIT = 32L * 1_048_576
private val FRAME_TEST_IDENTITY = "0123456789abcdef".repeat(4)

/**
 * Real [VideoTasks.frames] producer, real Room/artifact/cancellation paths, and a real fixture
 * MP4 used only for project/source verification. The frame source and translation engine are
 * fakes, so no SEG, OCR, API, model download, ASR or device player runs here.
 *
 * The fake source deliberately keeps no cache: every call returns a fresh frame with exactly the
 * requested PTS. It does keep object references so the test can prove every bitmap was recycled.
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameBudgetTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer

    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app) { TaskScheduler(it, canStart = { false }, acquire = { null }, release = {}) }
        container.startupReady.await()
        container.taskScheduler.ready.await()
    }

    @After fun cleanup() = runBlocking {
        if (::container.isInitialized) {
            runCatching { withTimeout(5_000) { container.taskScheduler.interrupt("TEST_CLEANUP") } }
            runCatching { closeContainer(container) }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
    }

    @Test fun sixHundredPlannedFramesStopAtTheSharedBudgetAndDrainToZero() = runBlocking {
        val project = install()
        useSharedBudget()
        val plan = VideoSamplingPlan(project.durationMs, VideoSamplingRate(75), 0, project.durationMs)
        assertTrue("the fixture must plan at least 600 samples", plan.sampleCount >= 600)
        val expectedTargets = List(plan.sampleCount.toInt()) { plan.targetUs(it.toLong()) }
        assertTrue("the fixture must plan at least 600 samples", expectedTargets.size >= 600)

        val tasks = container.workDatabase.tasks()
        val child = submitFrameTask(project, 75, project.durationMs)
        val running = child.copy(state = "RUNNING")
        tasks.update(running)

        val source = FakeFrameSource()
        val engine = FakeFrameEngine(project, blockFrames = true)
        val pipeline = VideoTasks(container,
            createFrameEngine = { _, _, _, _, _ -> engine },
            createFrameSource = { p, options ->
                source.projectSeen = p
                source.optionsSeen = options
                source
            })
        val run = async {
            withTimeout(240_000) { pipeline.frames(TaskExecution(running, container.taskScheduler)) }
        }
        try {
            // The producer starts its next admission only after this frame has shrunk its lease to
            // the real bitmap size, so reaching the cap is an observable admission barrier.
            withTimeout(30_000) { container.imageCacheBudget.bytes.first { it >= FRAME_BUDGET_LIMIT } }
            val decodedAtPressure = source.decodedCount
            withTimeout(30_000) { engine.waitingFrames.first { it == decodedAtPressure } }

            val admittedBytes = container.imageCacheBudget.bytes.value
            assertTrue("the shared budget must be saturated before the assertion", admittedBytes >= FRAME_BUDGET_LIMIT)
            assertTrue("admitted accounting must not exceed the cap by more than one input",
                admittedBytes <= FRAME_BUDGET_LIMIT + source.frameBytes)
            assertTrue("prefetch must not load all 600 samples", decodedAtPressure < expectedTargets.size)
            assertTrue("prefetch must stop near one budget, not walk the whole plan", decodedAtPressure <= 40)
            assertEquals("every admitted frame must be waiting in the frame worker", decodedAtPressure, engine.frameCalls.size)
            assertTrue("admitted pixels must still be live while the workers are gated", source.liveBytes > 0)
            assertTrue("live pixels must stay inside the budget plus one admitted input",
                source.liveBytes <= FRAME_BUDGET_LIMIT + source.frameBytes)
            assertTrue("no engine call may finish while the gate is held", !run.isCompleted)
        } finally {
            engine.releaseAll()
        }
        withTimeout(240_000) { run.await() }

        assertEquals("the fake source must see every planned PTS exactly once", expectedTargets, source.requests.toList())
        assertEquals("planned PTS must be distinct", expectedTargets.size, source.requests.distinct().size)
        assertEquals("every sample must reach the engine", expectedTargets.size, engine.frameCalls.size)
        assertEquals("the shared budget must return to baseline", 0L, container.imageCacheBudget.bytes.value)
        assertEquals("no admitted bitmap may stay live", 0L, source.liveBytes)
        assertEquals("the source must have produced one bitmap per planned sample", expectedTargets.size, source.bitmaps.size)
        assertTrue("every fake bitmap must be recycled", source.bitmaps.all { it.isRecycled })
        assertTrue("the fake frame source must be closed", source.closed)
        assertTrue("the fake engine must be closed", engine.closed)
        assertNotNull("configure() must receive frame settings from the production runner", engine.configured)

        val seen = requireNotNull(source.projectSeen)
        assertEquals(project.id, seen.id)
        assertEquals(project.sha256, seen.sha256)
        assertEquals(project.durationMs, seen.durationMs)
        assertTrue("the project URI must still point at the real fixture",
            File(Uri.parse(seen.uri).path!!).isFile)
        assertEquals(75, source.optionsSeen!!.fpsNumerator)
        assertNotNull("submit() must capture frame settings for the runner", source.optionsSeen!!.frameSettings)

        val rows = container.workDatabase.results().frames(project.id, project.sha256)
        assertEquals(expectedTargets.size, rows.size)
        assertEquals(expectedTargets.size, rows.map { it.sampleIndex }.distinct().size)
        val task = tasks.get(child.taskId)!!
        assertEquals(expectedTargets.size.toLong(), task.completed)
        assertEquals(expectedTargets.size.toLong(), task.total)
        assertEquals((expectedTargets.size - 1).toLong(), JSONObject(task.checkpoint).optLong("committed", -1L))
    }

    @Test fun cancellationBeforeTheFrameWorkerStartsReleasesTheAdmittedInputAndBudget() = runBlocking {
        val project = install()
        useSharedBudget()
        val tasks = container.workDatabase.tasks()
        val child = submitFrameTask(project, 75, 1_000)
        val running = child.copy(state = "RUNNING")
        tasks.update(running)

        val source = FakeFrameSource()
        val engine = FakeFrameEngine(project, blockDetect = true)
        val pipeline = VideoTasks(container,
            createFrameEngine = { _, _, _, _, _ -> engine },
            createFrameSource = { p, options ->
                source.projectSeen = p
                source.optionsSeen = options
                source
            })
        val run = async { pipeline.frames(TaskExecution(running, container.taskScheduler)) }
        try {
            withTimeout(30_000) { engine.detectEntered.await() }
            assertEquals("the admitted sample must own one decoded input", 1, source.decodedCount)
            // detect() is called before the lease is shrunk to the decoded bitmap size, so the
            // one outstanding reservation is the production acquire-before-decode reservation.
            val reservation = inputReservation(requireNotNull(source.projectSeen), requireNotNull(source.optionsSeen))
            assertEquals("the admitted input must be accounted once before decode",
                reservation, container.imageCacheBudget.bytes.value)
            assertTrue("cancellation must happen before the frame worker starts", engine.frameCalls.isEmpty())
            assertFalse("the admitted bitmap must be live before cancellation", source.bitmaps.single().isRecycled)
        } finally {
            withTimeout(10_000) { run.cancelAndJoin() }
        }

        assertTrue("the blocked detect gate must observe cancellation", engine.detectReleased.isCompleted)
        assertEquals("the abandoned input must release its shared lease", 0L, container.imageCacheBudget.bytes.value)
        assertEquals("the abandoned input must release its live pixels", 0L, source.liveBytes)
        assertTrue("the abandoned bitmap must be recycled", source.bitmaps.single().isRecycled)
        assertTrue("the fake frame source must be closed after cancellation", source.closed)
        assertTrue("the fake engine must be closed after cancellation", engine.closed)

        // The same root must be immediately reusable at its full limit: no stale accounting.
        val baseline = withTimeout(1_000) { container.imageCacheBudget.acquire(FRAME_BUDGET_LIMIT, "budget-baseline") }
        baseline.release()
        assertEquals(0L, container.imageCacheBudget.bytes.value)
    }

    @Test fun memoryFailureReleasesInputsAndBecomesARecoverableWaitingReason() = runBlocking {
        val project = install()
        useSharedBudget()
        val child = submitFrameTask(project, 75, 1_000).copy(state = "RUNNING")
        container.workDatabase.tasks().update(child)
        val source = FakeFrameSource()
        val engine = FakeFrameEngine(project, failMemory = true)
        val pipeline = VideoTasks(container, createFrameEngine = { _, _, _, _, _ -> engine },
            createFrameSource = { _, _ -> source })
        val failure = runCatching { withTimeout(30_000) { pipeline.frames(TaskExecution(child, container.taskScheduler)) } }.exceptionOrNull()
        assertTrue(failure is com.lmreader.tasks.TaskWaiting)
        assertEquals("MEMORY", (failure as com.lmreader.tasks.TaskWaiting).reason)
        assertEquals(0L, container.imageCacheBudget.bytes.value)
        assertTrue(source.bitmaps.all { it.isRecycled })
        assertTrue(source.closed && engine.closed)
    }

    @Test fun producerKeepsOneTrackThroughMatchingSamplesAndClosesItOnlyOnChange() = runBlocking {
        val project = install()
        useSharedBudget()
        val child = submitFrameTask(project, 1, 4_000).copy(state = "RUNNING")
        container.workDatabase.tasks().update(child)
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val source = object : VideoFrameSource {
            override suspend fun frameAt(targetUs: Long): DecodedVideoFrame {
                val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
                android.graphics.Canvas(bitmap).apply {
                    if(targetUs < 3_000_000) for(x in 24..104 step 16) drawRect(x.toFloat(), 24f, x + 6f, 72f, paint)
                    else for(y in 24..72 step 16) drawRect(24f, y.toFloat(), 110f, y + 6f, paint)
                }
                bitmaps += bitmap
                return DecodedVideoFrame(bitmap, targetUs)
            }
            override suspend fun close() = Unit
        }
        val delegate = FakeFrameEngine(project)
        var translated = 0
        val engine = object : VideoFrameEngine {
            override val identity get() = delegate.identity
            override suspend fun prepare() = delegate.prepare()
            override fun pageId(positionMs: Long) = delegate.pageId(positionMs)
            override suspend fun detect(bitmap: Bitmap, positionMs: Long): WorkflowPageSegmentation {
                val bounds = PixelRect(16f, 16f, 120f, 80f)
                val region = com.lmreader.core.model.SegRegion("${pageId(positionMs)}:seg:0", RegionKind.BUBBLE, bounds, 1f)
                return WorkflowPageSegmentation(com.lmreader.core.model.SegResult(pageId(positionMs), 160, 120,
                    listOf(region), 0, "fixture"), listOf(region))
            }
            override suspend fun frame(bitmap: Bitmap, positionMs: Long) = delegate.frame(bitmap, positionMs)
            override suspend fun frame(bitmap: Bitmap, positionMs: Long, detection: WorkflowPageSegmentation?, retained: Set<String>): ReaderPageTranslation {
                val base = delegate.frame(bitmap, positionMs)
                val region = requireNotNull(detection).regions.single()
                if(region.id in retained) return base.copy(regions = emptyList())
                translated++
                return base.copy(regions = listOf(PageTranslatedRegion(PageTextRegion("${region.id}:seg:fixture", region.kind,
                    region.bounds, region.contour, "source", listOf(region.bounds)), "固定译文")))
            }
            override suspend fun close() = delegate.close()
        }
        val pipeline = VideoTasks(container, createFrameEngine = { _, _, _, _, _ -> engine }, createFrameSource = { _, _ -> source })
        withTimeout(60_000) { pipeline.frames(TaskExecution(child, container.taskScheduler)) }
        assertEquals("only the initial region and changed source run translation", 2, translated)
        val dao = container.workDatabase.results()
        val initial = dao.sample(project.id, project.sha256, engine.identity, 0)!!
        assertTrue(container.videoLibrary.artifacts(project.id).current(dao.sample(project.id, project.sha256, engine.identity, 1)!!.pageId)!!.regions.isEmpty())
        assertEquals(initial.pageId, loadVideoMaskPlans(container, project.id, project.sha256, engine.identity, 1500).single().key.pageId)
        assertEquals(initial.pageId, loadVideoMaskPlans(container, project.id, project.sha256, engine.identity, 2500).single().key.pageId)
        assertEquals(3000L, dao.regions(project.id, project.sha256).first { it.pageId == initial.pageId }.endMs)
        assertEquals(engine.pageId(3000), loadVideoMaskPlans(container, project.id, project.sha256, engine.identity, 3500).single().key.pageId)
        assertEquals(0L, container.imageCacheBudget.bytes.value)
        assertTrue(bitmaps.all { it.isRecycled })
    }

    private suspend fun useSharedBudget() {
        container.translationCachePreferences.setMegabytes(32)
        withTimeout(10_000) { container.translationCachePreferences.megabytes.first { it == 32 } }
        container.imageCacheBudget.setLimit(FRAME_BUDGET_LIMIT)
    }

    private fun inputReservation(project: VideoProject, options: VideoWorkOptions): Long {
        val scaled = minOf(1.0, options.maxDimension.toDouble() / maxOf(project.width, project.height).coerceAtLeast(1))
        return (project.width.toDouble() * project.height * scaled * scaled * 12).toLong().coerceAtLeast(1)
    }

    private suspend fun install(): VideoProject {
        val file = File(app.cacheDir, "frame-budget-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file) }
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        return container.videoTasks.ensureProject(inspected.id)
    }

    private suspend fun submitFrameTask(project: VideoProject, fpsNumerator: Int,
        endMs: Long): TaskRecord {
        val parentId = container.videoTasks.submit(project.id, VideoWorkOptions(
            source = "en", target = "zh-Hans", mode = "FRAME",
            frameWorkflowId = TranslationWorkflow.LOCAL_MACHINE_ID,
            fpsNumerator = fpsNumerator, fpsDenominator = 1, startMs = 0, endMs = endMs,
        ))
        return container.workDatabase.tasks().all().single {
            it.type == "VIDEO_FRAMES" && it.parentTaskId == parentId
        }
    }

    private class FakeFrameSource(
        private val size: Int = 512,
    ) : VideoFrameSource {
        // References only; no bitmap is retained as a decoder cache. Recycle state is the evidence.
        val bitmaps = CopyOnWriteArrayList<Bitmap>()
        val requests = CopyOnWriteArrayList<Long>()
        val frameBytes: Long = size.toLong() * size * 4
        var projectSeen: VideoProject? = null
        var optionsSeen: VideoWorkOptions? = null
        var closed = false
            private set

        val decodedCount get() = requests.size
        val liveBytes get() = bitmaps.filterNot { it.isRecycled }.sumOf { it.allocationByteCount.toLong() }

        override suspend fun frameAt(targetUs: Long): DecodedVideoFrame? {
            requests += targetUs
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmaps += bitmap
            return DecodedVideoFrame(bitmap, targetUs)
        }

        override suspend fun close() {
            closed = true
        }
    }

    private class FakeFrameEngine(
        private val project: VideoProject,
        private val blockFrames: Boolean = false,
        private val blockDetect: Boolean = false,
        private val failMemory: Boolean = false,
    ) : VideoFrameEngine {
        override var identity: String = ""
            private set

        val frameCalls = CopyOnWriteArrayList<Long>()
        val detectCalls = CopyOnWriteArrayList<Long>()
        val waitingFrames = MutableStateFlow(0)
        val detectEntered = CompletableDeferred<Unit>()
        val detectReleased = CompletableDeferred<Unit>()
        private val detectGate = AtomicBoolean()
        private val detectHold = CompletableDeferred<Unit>()
        private val frameHold = CompletableDeferred<Unit>()
        var configured: VideoFrameSettings? = null
            private set
        var closed = false
            private set

        override suspend fun configure(settings: VideoFrameSettings) {
            configured = settings
        }

        override suspend fun prepare() {
            identity = FRAME_TEST_IDENTITY
        }

        override fun pageId(positionMs: Long) =
            "video:${project.id}:${project.sha256}:$positionMs:$identity"

        override suspend fun detect(bitmap: Bitmap, positionMs: Long): WorkflowPageSegmentation? {
            detectCalls += positionMs
            if (blockDetect && detectGate.compareAndSet(false, true)) {
                detectEntered.complete(Unit)
                try {
                    detectHold.await()
                } finally {
                    detectReleased.complete(Unit)
                }
            }
            return null
        }

        override suspend fun frame(bitmap: Bitmap, positionMs: Long): ReaderPageTranslation {
            if(failMemory) throw OutOfMemoryError("injected frame allocation failure")
            frameCalls += positionMs
            if (blockFrames) {
                waitingFrames.update { it + 1 }
                try {
                    frameHold.await()
                } finally {
                    waitingFrames.update { it - 1 }
                }
            }
            val id = pageId(positionMs)
            return ReaderPageTranslation(
                pageId = id,
                revision = UUID.randomUUID().toString(),
                dataFile = File("frame-fixture"),
                source = LocalTranslationLanguage.ENGLISH,
                target = LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                sourceSha256 = "0".repeat(64),
                width = bitmap.width,
                height = bitmap.height,
                regions = listOf(PageTranslatedRegion(
                    PageTextRegion("$id:fixture", RegionKind.FREE_TEXT, PixelRect(20f, 22f, 132f, 66f),
                        emptyList(), "fixture source", emptyList()),
                    "译文",
                )),
                modelPacks = emptyList(),
                elapsedMillis = 1L,
                resultIdentity = identity,
            )
        }

        fun releaseAll() {
            frameHold.complete(Unit)
            detectHold.complete(Unit)
        }

        override suspend fun close() {
            closed = true
        }
    }
}
