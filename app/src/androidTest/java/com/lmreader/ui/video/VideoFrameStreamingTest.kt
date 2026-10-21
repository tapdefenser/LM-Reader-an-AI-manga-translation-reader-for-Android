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
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskExecution
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.workflow.WorkflowPageSegmentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

private val FIXED_IDENTITY = "0123456789abcdef".repeat(4)

/**
 * Offline regression for the real frame producer. A real fixture MP4 is decoded and the
 * production Room/artifact/cancellation paths run; only the language model is replaced by a
 * fake engine, so no network, model download or scheduler dispatch is involved.
 */
@RunWith(AndroidJUnit4::class)
class VideoFrameStreamingTest {
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

    @Test fun repeatedActualPtsIsTranslatedOnceAndEverySampleKeepsItsArtifact() = runBlocking {
        val project = install()
        val tasks = container.workDatabase.tasks()
        val dao = container.workDatabase.results()
        val child = submitFrameTask(project, 12, 0, 1_000)
        val running = child.copy(state = "RUNNING")
        tasks.update(running)

        val engine = FakeFrameEngine(project, blockFirst166 = true)
        val pipeline = VideoTasks(container, createFrameEngine = { _, _, _, _, _ -> engine })
        withTimeout(60_000) { pipeline.frames(TaskExecution(running, container.taskScheduler)) }

        assertTrue("the second 166ms sample must observe the blocked shared frame", engine.second166Observed.isCompleted)
        val rows = dao.frames(project.id, project.sha256)
        assertEquals("every planned sample must yield one durable frame row", 12, rows.size)
        assertEquals((0L until 12L).toSet(), rows.map { it.sampleIndex }.toSet())
        val task = tasks.get(child.taskId)!!
        assertEquals(12L, task.completed)
        assertEquals(12L, task.total)
        assertEquals(11L, JSONObject(task.checkpoint).optLong("committed", -1L))

        val repeated = rows.groupBy { it.ptsUs }.filterValues { it.size > 1 }
        assertTrue("12 fps over the 6 fps fixture must repeat actual PTS", repeated.isNotEmpty())
        repeated.values.forEach { group ->
            assertEquals("repeated PTS must share one page artifact", 1, group.map { it.pageId }.distinct().size)
            assertEquals("repeated PTS must share one artifact revision", 1, group.map { it.resultRevision }.distinct().size)
        }
        val calls = engine.frameCalls.toList().groupingBy { it }.eachCount()
        assertEquals("the engine must translate each unique actual PTS once",
            rows.map { it.ptsUs / 1000 }.toSet(), calls.keys)
        assertTrue("no unique actual PTS may be translated twice", calls.values.all { it == 1 })
        assertEquals("the blocked 166ms presentation must be detected twice",
            2, engine.detectCalls.count { it == 166L })

        val artifacts = container.videoLibrary.artifacts(project.id)
        rows.forEach { row ->
            val artifact = requireNotNull(artifacts.current(row.pageId)) { "missing artifact for ${row.pageId}" }
            assertEquals(160, artifact.width)
            assertEquals(120, artifact.height)
            assertTrue("source hash must be the raw bitmap hash", artifact.sourceSha256.matches(Regex("[a-f0-9]{64}")))
            assertTrue("every sample artifact must keep its translation", artifact.regions.isNotEmpty())
            assertEquals(1, artifact.regions.size)
            artifact.regions.forEach { translated ->
                assertTrue(translated.region.id.startsWith(row.pageId + ":"))
                assertTrue(translated.translatedText.isNotBlank())
                val bounds = translated.region.bounds
                assertTrue("region bounds must be inside the 160x120 source",
                    bounds.left >= 0f && bounds.top >= 0f && bounds.right <= artifact.width.toFloat() &&
                        bounds.bottom <= artifact.height.toFloat() && bounds.width > 0f && bounds.height > 0f)
            }
        }
        assertTrue("the pipeline must close its fake engine", engine.closed)
    }

    @Test fun cancellationKeepsDurableLaterSampleAndResumeSkipsItWithoutRepublishing() = runBlocking {
        val project = install()
        val tasks = container.workDatabase.tasks()
        val dao = container.workDatabase.results()
        val child = submitFrameTask(project, 1, 0, 3_000)
        val running = child.copy(state = "RUNNING")
        tasks.update(running)

        val firstEngine = FakeFrameEngine(project, blockFirst0 = true)
        val pipeline = VideoTasks(container, createFrameEngine = { _, _, _, _, _ -> firstEngine })
        val job = launch { pipeline.frames(TaskExecution(running, container.taskScheduler)) }
        try {
            // Room Flow is the durability barrier; sample 1 must be committed while sample 0 is
            // still held in the fake frame, so no polling or sleep is needed.
            withTimeout(30_000) {
                dao.observeRegions(project.id, project.sha256).first {
                    dao.sample(project.id, project.sha256, FIXED_IDENTITY, 1L) != null
                }
            }
            withTimeout(10_000) { firstEngine.first0Entered.await() }
            val durable1 = requireNotNull(dao.sample(project.id, project.sha256, FIXED_IDENTITY, 1L))
            assertNull("the blocked first sample must stay unpublished",
                dao.sample(project.id, project.sha256, FIXED_IDENTITY, 0L))
            val blockedTask = tasks.get(child.taskId)!!
            assertTrue("checkpoint must not pass the blocked sample",
                JSONObject(blockedTask.checkpoint).optLong("committed", -1L) <= 0L)
            assertEquals(0L, blockedTask.completed)
            assertFalse("the first sample must still be held", firstEngine.first0Released.isCompleted)

            withTimeout(30_000) { job.cancelAndJoin() }
            assertTrue("cancellation must unblock the held frame", firstEngine.first0Released.isCompleted)
            assertTrue("cancellation must close the engine", firstEngine.closed)

            val afterCancel = requireNotNull(dao.sample(project.id, project.sha256, FIXED_IDENTITY, 1L))
            assertEquals("the already durable sample must keep its revision",
                durable1.resultRevision, afterCancel.resultRevision)
            val sample1Revision = afterCancel.resultRevision
            val sample1PositionMs = afterCancel.ptsUs / 1000

            val persisted = tasks.get(child.taskId)!!
            val resumed = persisted.copy(generation = persisted.generation + 1, state = "RUNNING")
            tasks.update(resumed)
            val secondEngine = FakeFrameEngine(project)
            val resumedPipeline = VideoTasks(container, createFrameEngine = { _, _, _, _, _ -> secondEngine })
            withTimeout(60_000) { resumedPipeline.frames(TaskExecution(resumed, container.taskScheduler)) }

            assertEquals("the fresh engine must keep the same result identity", FIXED_IDENTITY, secondEngine.identity)
            assertFalse("the durable sample must be skipped, not executed again",
                secondEngine.frameCalls.contains(sample1PositionMs))
            assertFalse("the durable sample must not be detected again",
                secondEngine.detectCalls.contains(sample1PositionMs))
            val finalRows = dao.frames(project.id, project.sha256)
            assertEquals("every planned sample must eventually persist", 3, finalRows.size)
            assertEquals(setOf(0L, 1L, 2L), finalRows.map { it.sampleIndex }.toSet())
            assertEquals("the skipped sample must keep its revision", sample1Revision,
                finalRows.single { it.sampleIndex == 1L }.resultRevision)
            val finalTask = tasks.get(child.taskId)!!
            assertEquals(3L, finalTask.completed)
            assertEquals(3L, finalTask.total)
            assertEquals(2L, JSONObject(finalTask.checkpoint).optLong("committed", -1L))
        } finally {
            runCatching { withTimeout(30_000) { job.cancelAndJoin() } }
            firstEngine.release()
        }
    }

    private suspend fun install(): VideoProject {
        val file = File(app.cacheDir, "frame-stream-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file) }
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        return container.videoTasks.ensureProject(inspected.id)
    }

    private suspend fun submitFrameTask(project: VideoProject, fpsNumerator: Int,
        startMs: Long, endMs: Long): TaskRecord {
        val parentId = container.videoTasks.submit(project.id, VideoWorkOptions(
            source = "en", target = "zh-Hans", mode = "FRAME",
            frameWorkflowId = TranslationWorkflow.LOCAL_MACHINE_ID,
            fpsNumerator = fpsNumerator, fpsDenominator = 1, startMs = startMs, endMs = endMs,
        ))
        return container.workDatabase.tasks().all().single {
            it.type == "VIDEO_FRAMES" && it.parentTaskId == parentId
        }
    }

    private class FakeFrameEngine(
        private val project: VideoProject,
        private val blockFirst166: Boolean = false,
        private val blockFirst0: Boolean = false,
    ) : VideoFrameEngine {
        override var identity: String = ""
            private set

        val frameCalls = CopyOnWriteArrayList<Long>()
        val detectCalls = CopyOnWriteArrayList<Long>()
        val first0Entered = CompletableDeferred<Unit>()
        val first0Released = CompletableDeferred<Unit>()
        val second166Observed = CompletableDeferred<Unit>()
        private val first166 = AtomicBoolean()
        private val first0 = AtomicBoolean()
        private val first166Entered = CompletableDeferred<Unit>()
        private val first166Hold = CompletableDeferred<Unit>()
        private val first0Hold = CompletableDeferred<Unit>()
        var closed = false
            private set

        override suspend fun prepare() { identity = FIXED_IDENTITY }

        override fun pageId(positionMs: Long) = "video:${project.id}:${project.sha256}:$positionMs:$identity"

        override suspend fun detect(bitmap: Bitmap, positionMs: Long): WorkflowPageSegmentation? {
            detectCalls.add(positionMs)
            if (blockFirst166 && positionMs == 166L && detectCalls.count { it == 166L } == 2) {
                withTimeout(30_000) { first166Entered.await() }
                first166Hold.complete(Unit)
                second166Observed.complete(Unit)
            }
            return null
        }

        override suspend fun frame(bitmap: Bitmap, positionMs: Long): ReaderPageTranslation {
            frameCalls.add(positionMs)
            if (blockFirst166 && positionMs == 166L && first166.compareAndSet(false, true)) {
                first166Entered.complete(Unit)
                first166Hold.await()
            }
            if (blockFirst0 && positionMs == 0L && first0.compareAndSet(false, true)) {
                first0Entered.complete(Unit)
                try { first0Hold.await() } finally { first0Released.complete(Unit) }
            }
            val id = pageId(positionMs)
            val bounds = PixelRect(20f, 22f, 132f, 66f)
            return ReaderPageTranslation(
                pageId = id,
                revision = UUID.randomUUID().toString(),
                dataFile = File("frame-fixture"),
                source = LocalTranslationLanguage.ENGLISH,
                target = LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                sourceSha256 = VideoTranslationEngine.bitmapHash(bitmap),
                width = bitmap.width,
                height = bitmap.height,
                regions = listOf(PageTranslatedRegion(
                    PageTextRegion("$id:fixture", RegionKind.FREE_TEXT, bounds, emptyList(),
                        "fixture source", emptyList()),
                    "译文",
                )),
                modelPacks = emptyList(),
                elapsedMillis = 1L,
                resultIdentity = identity,
            )
        }

        fun release() {
            first166Hold.complete(Unit)
            first0Hold.complete(Unit)
        }

        override suspend fun close() { closed = true }
    }
}
