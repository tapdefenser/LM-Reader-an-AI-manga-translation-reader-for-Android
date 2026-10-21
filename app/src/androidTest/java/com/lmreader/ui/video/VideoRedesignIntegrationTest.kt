package com.lmreader.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.media.MediaTaskDatabase
import com.lmreader.core.database.media.VideoCatalogEntry
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.database.media.VideoFrameIndex
import com.lmreader.core.database.media.VideoRegionInterval
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.database.tasks.TaskRequestRecord
import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.PageTextRegion
import com.lmreader.core.model.PageTranslatedRegion
import com.lmreader.core.model.PixelRect
import com.lmreader.core.model.RegionKind
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.settings.backup.MediaTaskBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real decoders, Room databases and file artifacts. No production playback view and no engine. */
@RunWith(AndroidJUnit4::class)
class VideoRedesignIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private val databases = mutableListOf<MediaTaskDatabase>()

    @Before fun setup() {
        root = File(context.cacheDir, "video-redesign-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() = runBlocking {
        databases.forEach { it.close() }
        if (::root.isInitialized) root.deleteRecursively()
        Unit
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, MediaTaskDatabase::class.java)
        .build().also { databases += it }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun decodeUsage(): Pair<Long, Long> = ResourceGovernor.shared.usage.value.let { usage ->
        usage.sumOf { it.resources["decode"] ?: 0L } to usage.sumOf { it.resources["pixels"] ?: 0L }
    }

    @Test fun sequentialOneFpsPlanIsMonotonicRefCountedAndReleased() = runBlocking {
        val file = File(root, "sequential.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file) }
        val baseline = decodeUsage()
        val decoder = VideoFrameDecoder(context, Uri.fromFile(file).toString())
        var eos: DecodedVideoFrame? = null
        try {
            var previousPts = -1L
            for (target in (0..7).map { it * 1_000_000L }) {
                val frame = requireNotNull(decoder.frameAt(target))
                try {
                    assertTrue("PTS ${frame.ptsUs} is before target $target", frame.ptsUs >= target)
                    assertTrue("PTS must not go backwards", frame.ptsUs >= previousPts)
                    previousPts = frame.ptsUs
                } finally {
                    frame.close()
                }
            }

            // Repeating the current target must return the same decoded pixels with a safe lease count.
            val first = requireNotNull(decoder.frameAt(7_000_000L))
            val second = requireNotNull(decoder.frameAt(7_000_000L))
            val sharedPixels = first.bitmap
            assertSame(sharedPixels, second.bitmap)
            first.close()
            assertFalse("One closed lease must not recycle shared pixels", sharedPixels.isRecycled)
            second.close()
            assertFalse("The decoder cache still owns the pixels", sharedPixels.isRecycled)

            // A backwards request resets the sequential decoder; the old cache is released and decoding resumes.
            val backwards = requireNotNull(decoder.frameAt(1_000_000L))
            assertTrue(backwards.ptsUs >= 1_000_000L)
            assertTrue("Reset must release the replaced cache", sharedPixels.isRecycled)
            backwards.close()

            // At EOS the last frame may be earlier than the requested fixture end, but never later.
            val last = requireNotNull(decoder.frameAt(8_000_000L))
            eos = last
            assertTrue("EOS frame ${last.ptsUs} outside the generated source", last.ptsUs in 7_000_000L..8_000_000L)
            last.close()
        } finally {
            decoder.close()
        }
        assertTrue("Closing the decoder must recycle its cached bitmap", eos!!.bitmap.isRecycled)
        assertEquals("Decoder must not leave decode/pixel leases", baseline, decodeUsage())
    }

    @Test fun rotationNinetyAnalysisBitmapIsPortraitAndSourceBytesStayUnchanged() = runBlocking {
        val file = File(root, "rotated.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file, 90) }
        val before = ReaderPageArtifactStore.hashFile(file)
        val decoder = VideoFrameDecoder(context, Uri.fromFile(file).toString())
        try {
            val frame = requireNotNull(decoder.frameAt(1_000_000L))
            try {
                assertEquals(120, frame.bitmap.width)
                assertEquals(160, frame.bitmap.height)
                assertTrue("Rotation 90 must produce portrait analysis pixels", frame.bitmap.height > frame.bitmap.width)
            } finally {
                frame.close()
            }
        } finally {
            decoder.close()
        }
        assertEquals("Decoding analysis must never rewrite the source", before, ReaderPageArtifactStore.hashFile(file))
    }

    @Test fun snapshotValidatesBitmapArtifactHashDistinctFromContainerHash() = runBlocking(Dispatchers.IO) {
        val videos = File(root, "videos").apply { mkdirs() }
        val db = database()
        val store = VideoLibraryStore(videos)
        val containerHash = sha("container")
        val project = store.add(VideoProject(UUID.randomUUID().toString(), "content://local/original", "fixture.mp4",
            containerHash, 100, 8000, 160, 120, 0))
        val identity = sha("frame")
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(20f, 22f, 132f, 66f, Paint().apply { color = Color.BLACK })
        }
        try {
            val pageId = "video:${project.id}:1000:$identity"
            val region = PageTranslatedRegion(
                PageTextRegion("$pageId:r", RegionKind.FREE_TEXT, PixelRect(20f, 22f, 132f, 66f),
                    emptyList(), "source", emptyList()), "译文",
            )
            val bitmapHash = VideoTranslationEngine.bitmapHash(bitmap)
            val saved = ReaderPageTranslation(
                pageId = pageId, revision = "unused", dataFile = File(root, "unused"),
                source = LocalTranslationLanguage.JAPANESE, target = LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                sourceSha256 = bitmapHash, width = 160, height = 120,
                regions = listOf(region), modelPacks = emptyList(), elapsedMillis = 10,
                renderSettings = BubbleRenderSettings(fillMode = BubbleFillMode.WHITE),
                resultIdentity = identity,
            )
            VideoFrameProof.capture(bitmap, saved).save(store.proofs(project.id), pageId)
            store.publishFrame(project.id, project.sha256, 1000, 1350, identity, saved) {}
            val artifact = requireNotNull(store.artifacts(project.id).current(pageId))
            assertNotEquals("Artifact source hash must be the decoded-pixel hash", project.sha256, artifact.sourceSha256)
            assertEquals(bitmapHash, artifact.sourceSha256)

            val taskId = UUID.randomUUID().toString()
            db.videoCatalogDao().insertIgnore(VideoCatalogEntry(
                videoId = project.id, uri = project.uri, volume = "local", mediaId = 1, parentPath = "/",
                name = project.name, bytes = project.bytes, durationMs = project.durationMs,
                width = project.width, height = project.height, rotation = project.rotation,
                dateAdded = 1, generation = 1, onShelf = true, positionMs = 0, playCount = 0,
                sourceHash = project.sha256, sourceRevision = "rev", migrationComplete = true,
            ))
            // The index row names the container source header; the artifact keeps the decoded-pixel hash.
            db.results().putFrame(VideoFrameIndex(
                videoId = project.id, sourceHash = project.sha256, identity = identity, sampleIndex = 0,
                targetUs = 1_000_000, ptsUs = 1_000_000, untilUs = 1_350_000, pageId = pageId,
                resultRevision = artifact.revision, noText = false, producerTaskId = taskId,
            ))
            db.results().addRegion(VideoRegionInterval(
                intervalId = UUID.randomUUID().toString(), videoId = project.id, sourceHash = project.sha256,
                pageId = pageId, regionId = region.region.id, startMs = 1000, endMs = 1350,
                manualTiming = true, revision = UUID.randomUUID().toString(),
            ))
            db.results().addCue(VideoCueRecord(
                videoId = project.id, cueId = "manual", sourceHash = project.sha256, trackId = "track",
                startMs = 0, endMs = 800, source = "hello", translation = "人工",
                manuallyEdited = true, revision = UUID.randomUUID().toString(),
            ))
            db.results().addCue(VideoCueRecord(
                videoId = project.id, cueId = "deleted", sourceHash = project.sha256, trackId = "track",
                startMs = 0, endMs = 800, source = "old", translation = "",
                deleted = true, revision = UUID.randomUUID().toString(),
            ))
            db.tasks().insert(TaskRecord(
                taskId = taskId, type = "VIDEO", title = "restore", ownerKey = project.id,
                activeKey = "active-$taskId", payload = "{}", state = "QUEUED", remoteOutcomeUnknown = true,
            ))
            val requestId = UUID.randomUUID().toString()
            db.tasks().addRequest(TaskRequestRecord(requestId, taskId, 0, "scope", "UNKNOWN"))

            val storedFrame = db.results().frames(project.id, project.sha256).single()
            val headerFrame = store.current(project.id).frames.single()
            assertEquals(1, db.results().regions(project.id, project.sha256).size)
            val cues = db.results().cues(project.id)
            assertEquals(2, cues.size)
            assertTrue(cues.single { it.cueId == "manual" }.manuallyEdited)
            assertTrue(cues.single { it.cueId == "deleted" }.deleted)
            assertEquals("UNKNOWN", db.tasks().request(requestId)?.status)
            assertEquals(1_000_000L, storedFrame.ptsUs)
            assertEquals(artifact.revision, storedFrame.resultRevision)
            assertEquals(1000L, headerFrame.atMs)
            assertEquals(1350L, headerFrame.untilMs)
            assertEquals(identity, headerFrame.identity)

            val snapshot = File(root, "snapshot").apply { mkdirs() }
            MediaTaskBackup.snapshot(db.openHelper.writableDatabase, snapshot, true)
            MediaTaskBackup.validate(db.openHelper.writableDatabase, snapshot, videos, semantic = true)
            MediaTaskBackup.validateVideoLibrary(videos)
            store.validateResults()
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun mergeKeepsLocalStateAndRollbackRestoresTaskRequestsExactly() = runBlocking(Dispatchers.IO) {
        val backupDir = File(root, "incoming").apply { mkdirs() }
        val rollbackDir = File(root, "rollback").apply { mkdirs() }
        val incomingVideos = File(root, "incoming-videos").apply { mkdirs() }
        val localVideos = File(root, "local-videos").apply { mkdirs() }
        val source = database()
        val target = database()

        val localContainer = sha("local-container")
        val localStore = VideoLibraryStore(localVideos)
        val local = localStore.add(VideoProject(UUID.randomUUID().toString(), "content://local/original",
            "local.mp4", localContainer, 100, 8000, 160, 120, 0, positionMs = 2500))
        val localEntry = VideoCatalogEntry(
            videoId = local.id, uri = local.uri, volume = "local", mediaId = 1, parentPath = "/",
            name = local.name, bytes = local.bytes, durationMs = local.durationMs, width = 160, height = 120,
            rotation = 0, dateAdded = 1, generation = 1, onShelf = true, categoryId = 7,
            positionMs = 2500, playCount = 3, sourceHash = localContainer, sourceRevision = "local-rev",
            migrationComplete = true,
        )
        target.videoCatalogDao().insertIgnore(localEntry)
        val localCue = VideoCueRecord(
            videoId = local.id, cueId = "local-cue", sourceHash = localContainer, trackId = "track",
            startMs = 0, endMs = 1000, source = "local", translation = "人工修订",
            manuallyEdited = true, revision = "local-cue-rev",
        )
        target.results().addCue(localCue)
        val localTaskId = UUID.randomUUID().toString()
        val localTask = TaskRecord(localTaskId, "VIDEO", "local", local.id, "local-active", "{}", state = "QUEUED")
        target.tasks().insert(localTask)
        val localRequest = TaskRequestRecord(UUID.randomUUID().toString(), localTaskId, 0, "local-scope", "PREPARED")
        target.tasks().addRequest(localRequest)
        MediaTaskBackup.snapshot(target.openHelper.writableDatabase, rollbackDir, true)

        val importedContainer = sha("imported-container")
        val importedStore = VideoLibraryStore(incomingVideos)
        val imported = importedStore.add(VideoProject(UUID.randomUUID().toString(), "content://imported/original",
            "imported.mp4", importedContainer, 200, 8000, 160, 120, 0, positionMs = 4000))
        importedStore.add(local.copy())
        source.videoCatalogDao().insertIgnore(localEntry)
        source.videoCatalogDao().insertIgnore(VideoCatalogEntry(
            videoId = imported.id, uri = imported.uri, volume = "imported", mediaId = 99, parentPath = "/",
            name = imported.name, bytes = imported.bytes, durationMs = imported.durationMs, width = 160, height = 120,
            rotation = 0, dateAdded = 2, generation = 2, onShelf = true, categoryId = 9,
            positionMs = 4000, playCount = 8, sourceHash = importedContainer, sourceRevision = "imported-rev",
        ))
        // A stale remote copy of the local manual cue must never overwrite the local text/revision.
        source.results().addCue(localCue.copy(translation = "旧版本", manuallyEdited = false, revision = "stale-rev"))
        val importedTaskId = UUID.randomUUID().toString()
        source.tasks().insert(TaskRecord(
            importedTaskId, "VIDEO", "remote unknown", imported.id, "imported-active", "{}",
            state = "RUNNING", generation = 2, remoteOutcomeUnknown = true, recoverable = true,
        ))
        val importedRequest = TaskRequestRecord(UUID.randomUUID().toString(), importedTaskId, 2, "imported-scope", "UNKNOWN")
        source.tasks().addRequest(importedRequest)
        MediaTaskBackup.snapshot(source.openHelper.writableDatabase, backupDir, true)
        MediaTaskBackup.validate(source.openHelper.writableDatabase, backupDir, incomingVideos, semantic = true)

        MediaTaskBackup.mergeVideoLibrary(incomingVideos, localVideos)
        MediaTaskBackup.restore(target.openHelper.writableDatabase, backupDir, true, localVideos)

        assertEquals(localEntry, target.videoCatalogDao().byId(local.id))
        assertEquals(localCue, target.results().cue(local.id, "local-cue"))
        assertEquals(localTask, target.tasks().get(localTaskId))
        val importedRow = requireNotNull(target.videoCatalogDao().byId(imported.id))
        assertFalse("Imported media is not automatically accessible", importedRow.accessible)
        assertEquals(MediaTaskBackup.RESTORE_ACCESS_REASON, importedRow.accessReason)
        assertEquals(0L, importedRow.mediaId)
        assertTrue(importedRow.migrationComplete)

        val importedTask = requireNotNull(target.tasks().get(importedTaskId))
        assertEquals("INTERRUPTED", importedTask.state)
        assertTrue(importedTask.userPaused)
        assertFalse(importedTask.recoverable)
        assertEquals("ACCESS", importedTask.waitingReason)
        assertNull("Paused/interrupted restored work must not be claimable", target.tasks().claim(importedTaskId))
        assertEquals("UNKNOWN", target.tasks().request(importedRequest.requestId)?.status)
        assertEquals(1, target.tasks().unresolvedRequests(importedTaskId))
        assertEquals(localRequest, target.tasks().request(localRequest.requestId))

        // Make the local request diverge before rollback so exact restoration is observable.
        target.tasks().requestStatus(localRequest.requestId, "SENT")
        assertEquals("SENT", target.tasks().request(localRequest.requestId)?.status)

        MediaTaskBackup.restore(target.openHelper.writableDatabase, rollbackDir, false, localVideos)
        assertEquals("Rollback must restore task_requests exactly", localRequest, target.tasks().request(localRequest.requestId))
        assertEquals(localTask, target.tasks().get(localTaskId))
        assertNull(target.tasks().request(importedRequest.requestId))
        assertNull(target.tasks().get(importedTaskId))
        assertNull(target.videoCatalogDao().byId(imported.id))
        assertEquals(localEntry, target.videoCatalogDao().byId(local.id))
        assertEquals(localCue, target.results().cue(local.id, "local-cue"))
    }
}
