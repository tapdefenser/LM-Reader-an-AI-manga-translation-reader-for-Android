package com.lmreader.ui.video

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.database.tasks.TaskDao
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.TranslationWorkflow
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskExecution
import com.lmreader.tasks.TaskScheduler
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real Room + workflow orchestration for the injected subtitle audio producer and the
 * internal per-cue executor. The scheduler cannot start, so only the explicit
 * [VideoTasks.subtitles] call below drives work; no ASR, FGS, API or model download runs.
 *
 * The fake producer is also the proof of ordering: it waits for the first translation to be
 * durable before it is allowed to publish the second source cue or the final checkpoint.
 */
@RunWith(AndroidJUnit4::class)
class VideoSubtitleStreamingTest {
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

    @Test fun streamingProducerWaitsForFirstTranslationAndKeepsRawCheckpoint() = runBlocking {
        val project = install()
        val tasks = container.workDatabase.tasks()
        val dao = container.videoResults.results()
        val child = submitSubtitleTask(project, tasks)
        val running = child.copy(state = "RUNNING")
        tasks.update(running)

        val cue1 = fixtureCue(project, 0, "First cue")
        val cue2 = fixtureCue(project, 1, "Second cue")
        assertTrue(cue1.endMs <= project.durationMs)
        assertTrue(cue2.endMs <= project.durationMs)
        var sourceFinished = false

        val streaming = VideoTasks(container, prepareAudio = { _, videoProject, _, updates ->
            assertEquals(project.id, videoProject.id)
            assertEquals(project.sha256, videoProject.sha256)
            // Source callbacks stay outside any Room transaction: each write commits first.
            check(dao.addCue(cue1) != -1L)
            updates.onProgress("fixture recognition", checkpoint(0, prepared = false))
            updates.onBatch(listOf(cue1))

            // The recognition source is not allowed to finish until this cue's translation
            // has actually landed in Room. Room Flow + a real timeout, no sleeps.
            withTimeout(30_000) {
                dao.observeCues(project.id).first { rows ->
                    rows.any { it.cueId == cue1.cueId && it.translation == cue1.source }
                }
            }
            // The worker reports completion after its durable cue write; wait for that public
            // checkpoint too, so the assertion below is not racing the worker's progress call.
            withTimeout(30_000) {
                tasks.observe().first { rows ->
                    rows.firstOrNull { it.taskId == child.taskId }?.completed?.let { it >= 1L } == true
                }
            }

            val current = tasks.get(child.taskId)!!
            val raw = JSONObject(current.checkpoint)
            assertEquals(0L, raw.optLong("asrCommitted", -1L))
            assertFalse(raw.optBoolean("sourcePrepared", false))
            assertEquals(TRACK, raw.getString("sourceTrackId"))
            assertTrue(current.completed >= 1L)
            assertEquals(0L, current.total)
            assertEquals(cue1.source, dao.cue(project.id, cue1.cueId)!!.translation)

            // Only now may the source announce the next chunk and the final raw checkpoint.
            check(dao.addCue(cue2) != -1L)
            updates.onBatch(listOf(cue2))
            updates.onProgress("fixture recognition complete", checkpoint(1, prepared = true))
            sourceFinished = true
        })

        streaming.subtitles(TaskExecution(running, container.taskScheduler))

        assertTrue(sourceFinished)
        val first = dao.cue(project.id, cue1.cueId)!!
        val second = dao.cue(project.id, cue2.cueId)!!
        assertEquals(cue1.source, first.translation)
        assertEquals(cue2.source, second.translation)
        assertEquals(TRACK, first.trackId)
        assertEquals(TRACK, second.trackId)
        val finalTask = tasks.get(child.taskId)!!
        assertEquals(2L, finalTask.completed)
        assertEquals(2L, finalTask.total)
        val finalCheckpoint = JSONObject(finalTask.checkpoint)
        assertEquals(1L, finalCheckpoint.optLong("asrCommitted", -1L))
        assertTrue(finalCheckpoint.optBoolean("sourcePrepared"))
        assertEquals(TRACK, finalCheckpoint.getString("sourceTrackId"))
    }

    @Test fun cancelWhileRecognitionWaitsResumesFromCheckpointWithoutRetranslatingCueOne() = runBlocking {
        val project = install()
        val tasks = container.workDatabase.tasks()
        val dao = container.videoResults.results()
        val child = submitSubtitleTask(project, tasks)
        val running = child.copy(state = "RUNNING")
        tasks.update(running)

        val cue1 = fixtureCue(project, 0, "First cue")
        val cue2 = fixtureCue(project, 1, "Second cue")
        val firstDurable = CompletableDeferred<Unit>()
        val producerWaiting = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()

        val first = VideoTasks(container, prepareAudio = { _, videoProject, _, updates ->
            assertEquals(project.sha256, videoProject.sha256)
            check(dao.addCue(cue1) != -1L)
            updates.onProgress("fixture recognition", checkpoint(0, prepared = false))
            updates.onBatch(listOf(cue1))
            withTimeout(30_000) {
                dao.observeCues(project.id).first { rows ->
                    rows.any { it.cueId == cue1.cueId && it.translation == cue1.source }
                }
            }
            withTimeout(30_000) {
                tasks.observe().first { rows ->
                    rows.firstOrNull { it.taskId == child.taskId }?.completed?.let { it >= 1L } == true
                }
            }
            firstDurable.complete(Unit)
            producerWaiting.complete(Unit)
            // Structured cancellation of this await cancels subtitles' scope and joins workers.
            hold.await()
        })

        val job = launch { first.subtitles(TaskExecution(running, container.taskScheduler)) }
        try {
            withTimeout(30_000) { firstDurable.await() }
            withTimeout(30_000) { producerWaiting.await() }

            val beforeCancelCue = dao.cue(project.id, cue1.cueId)!!
            assertEquals(cue1.source, beforeCancelCue.translation)
            val beforeCancelTask = tasks.get(child.taskId)!!
            assertEquals(0L, JSONObject(beforeCancelTask.checkpoint).optLong("asrCommitted", -1L))
            assertFalse(JSONObject(beforeCancelTask.checkpoint).optBoolean("sourcePrepared", false))

            withTimeout(30_000) { job.cancelAndJoin() }

            // Both the cue and the raw source checkpoint survive the interruption.
            val durableCue = dao.cue(project.id, cue1.cueId)!!
            assertEquals(cue1.source, durableCue.translation)
            val cancelled = tasks.get(child.taskId)!!
            assertEquals("RUNNING", cancelled.state)
            val cancelledCheckpoint = JSONObject(cancelled.checkpoint)
            assertEquals(0L, cancelledCheckpoint.optLong("asrCommitted", -1L))
            assertFalse(cancelledCheckpoint.optBoolean("sourcePrepared", false))
            assertEquals(TRACK, cancelledCheckpoint.getString("sourceTrackId"))
            assertTrue(cancelled.completed >= 1L)

            // Resume the persisted generation with the newest checkpoint/completed fields.
            val resumed = cancelled.copy(generation = cancelled.generation + 1, state = "RUNNING")
            tasks.update(resumed)
            var resumedInitialCommitted = -1L
            var resumedInitialPrepared = true
            var resumedInitialCompleted = -1L
            val secondBatches = mutableListOf<List<VideoCueRecord>>()
            val second = VideoTasks(container, prepareAudio = { execution, videoProject, _, updates ->
                assertEquals(project.sha256, videoProject.sha256)
                val raw = JSONObject(execution.initial.checkpoint)
                resumedInitialCommitted = raw.optLong("asrCommitted", -1L)
                resumedInitialPrepared = raw.optBoolean("sourcePrepared", false)
                resumedInitialCompleted = execution.initial.completed
                // Only the second recognition chunk is emitted; cue 1 must come from Room.
                check(dao.addCue(cue2) != -1L)
                secondBatches.add(listOf(cue2))
                updates.onBatch(listOf(cue2))
                updates.onProgress("fixture recognition complete", checkpoint(1, prepared = true))
            })

            second.subtitles(TaskExecution(resumed, container.taskScheduler))

            assertEquals(0L, resumedInitialCommitted)
            assertFalse(resumedInitialPrepared)
            assertEquals(cancelled.completed, resumedInitialCompleted)
            assertEquals(listOf(cue2.cueId), secondBatches.single().map { it.cueId })

            val finalCue1 = dao.cue(project.id, cue1.cueId)!!
            val finalCue2 = dao.cue(project.id, cue2.cueId)!!
            assertEquals(cue1.source, finalCue1.translation)
            assertEquals(cue2.source, finalCue2.translation)
            assertEquals("cue 1 must not be retranslated after resume",
                beforeCancelCue.revision, finalCue1.revision)
            val finalTask = tasks.get(child.taskId)!!
            assertEquals(2L, finalTask.completed)
            assertEquals(2L, finalTask.total)
            val finalCheckpoint = JSONObject(finalTask.checkpoint)
            assertEquals(1L, finalCheckpoint.optLong("asrCommitted", -1L))
            assertTrue(finalCheckpoint.optBoolean("sourcePrepared"))
            assertEquals(TRACK, finalCheckpoint.getString("sourceTrackId"))
        } finally {
            // Always structured-cancel and join the fake producer, even on an assertion failure.
            runCatching { withTimeout(30_000) { job.cancelAndJoin() } }
        }
    }

    private suspend fun install(): VideoProject {
        val file = File(app.cacheDir, "subtitle-stream-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file) }
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        return container.videoTasks.ensureProject(inspected.id)
    }

    private suspend fun submitSubtitleTask(project: VideoProject, tasks: TaskDao): TaskRecord {
        val parentId = container.videoTasks.submit(project.id, VideoWorkOptions(
            source = "en", target = "en", mode = "SUBTITLE",
            frameWorkflowId = TranslationWorkflow.LOCAL_MACHINE_ID,
            subtitleWorkflowId = TranslationWorkflow.SUBTITLE_LOCAL.id,
            subtitleSource = "ASR_LOCAL",
        ))
        return tasks.all().single { it.type == "VIDEO_SUBTITLES" && it.parentTaskId == parentId }
    }

    private fun fixtureCue(project: VideoProject, index: Int, source: String): VideoCueRecord {
        val start = index * 1_200L
        val end = start + 1_200L
        return VideoCueRecord(
            videoId = project.id,
            cueId = VideoTasks.stableId("${project.sha256}:$TRACK:$start:$end:$source"),
            sourceHash = project.sha256,
            trackId = TRACK,
            startMs = start,
            endMs = end,
            source = source,
            revision = UUID.randomUUID().toString(),
        )
    }

    private fun checkpoint(committed: Long, prepared: Boolean): String = JSONObject()
        .put("asrCommitted", committed)
        .put("sourceTrackId", TRACK)
        .apply { if (prepared) put("sourcePrepared", true) }
        .toString()

    private companion object {
        const val TRACK = "fixture-speech"
    }
}
