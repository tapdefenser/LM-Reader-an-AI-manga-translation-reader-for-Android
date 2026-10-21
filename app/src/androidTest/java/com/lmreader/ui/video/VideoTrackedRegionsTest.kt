package com.lmreader.ui.video

import com.lmreader.ui.workflow.WorkflowPageSegmentation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.media.VideoFrameIndex
import com.lmreader.core.database.media.VideoRegionInterval
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.screen.screenVideoRegions
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Focused contracts for the video tracked-region pipeline.
 *
 * These tests never decode a video and never call a paid provider. SEG is supplied as a
 * deterministic [WorkflowPageSegmentation] so only the region-observation, matching,
 * frame-overload, artifact and Room paths are exercised. The API is a loopback
 * [MockWebServer] fixture.
 */
@RunWith(AndroidJUnit4::class)
class VideoTrackedRegionsTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var server: MockWebServer
    private val bitmaps = mutableListOf<Bitmap>()
    private val engines = mutableListOf<VideoTranslationEngine>()

    @Before fun setup() {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app)
        server = MockWebServer()
        server.start()
    }

    @After fun cleanup() = runBlocking {
        engines.forEach { engine -> runCatching { engine.close() } }
        runCatching { server.shutdown() }
        bitmaps.forEach { bitmap -> runCatching { bitmap.recycle() } }
        if (::container.isInitialized) runCatching { closeContainer(container) }
        if (::app.isInitialized) app.root.deleteRecursively()
    }

    private fun project(source: String = "en", target: String = "zh-Hans") = VideoProject(
        UUID.randomUUID().toString(), "content://fixture/local", "fixture",
        "d".repeat(64), 0, 8000, 320, 180, 0, source = source, target = target)

    private suspend fun profile(): ApiProfile {
        val profile = ApiProfile("video-track-${UUID.randomUUID()}", ApiProfileKind.LLM, "loopback fixture",
            server.url("/v1").toString(), model = "fixture", retryCount = 0)
        container.apiProfiles.save(profile)
        return profile
    }

    private fun workflow(profileId: String) =
        TranslationWorkflow.VISION_API.copy(program = WorkflowTemplates.visionApi(profileId))

    private suspend fun preparedEngine(project: VideoProject, profileId: String,
        scope: SegTextScope = SegTextScope.ALL): VideoTranslationEngine {
        val engine = VideoTranslationEngine(container, project, workflow(profileId), textScope = scope)
        engines += engine
        withTimeout(120_000) { engine.prepare() }
        return engine
    }

    /** Small, high-contrast generated source. [dx]/[dy] move all strokes; [inverted] changes the source. */
    private fun textBitmap(text: String, dx: Int = 0, dy: Int = 0, inverted: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (inverted) Color.BLACK else Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (inverted) Color.WHITE else Color.BLACK
            textSize = 44f
            typeface = Typeface.DEFAULT_BOLD
        }
        Canvas(bitmap).drawText(text, 55f + dx, 102f + dy, paint)
        bitmaps += bitmap
        return bitmap
    }

    private fun contour(bounds: PixelRect) = listOf(
        PixelPoint(bounds.left, bounds.top), PixelPoint(bounds.right, bounds.top),
        PixelPoint(bounds.right, bounds.bottom), PixelPoint(bounds.left, bounds.bottom))

    private fun detection(pageId: String, bounds: PixelRect,
        kind: RegionKind = RegionKind.BUBBLE): WorkflowPageSegmentation {
        val region = SegRegion("$pageId:bubble-0", kind, bounds, 1f, contour(bounds))
        val result = SegResult(pageId, 320, 180, listOf(region), 1, "generated")
        return WorkflowPageSegmentation(result, listOf(region))
    }

    private fun reply(source: String, translation: String): MockResponse {
        val content = JSONObject().put("source", source).put("translation", translation).toString()
        val body = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", content)))).toString()
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    @Test fun matchedSourceStrokeKeepsOneIntervalAcrossDistinctSampleArtifacts() = runBlocking {
        val apiProfile = profile()
        val video = container.videoLibrary.add(project())
        val engine = preparedEngine(video, apiProfile.id)
        val region = PixelRect(40f, 56f, 230f, 118f)
        val moved = region.offset(5f, 4f)

        val first = textBitmap("HELLO")
        val firstHash = VideoTranslationEngine.bitmapHash(first)
        val firstPage = engine.pageId(0)
        val firstDetection = detection(firstPage, region)
        server.enqueue(reply("HELLO", "你好"))
        val saved = withTimeout(60_000) { engine.frame(first, 0, firstDetection, emptySet()) }
        assertEquals("one queued response must translate the one detected region", 1, server.requestCount)
        assertEquals(1, saved.regions.size)
        assertEquals(region, saved.regions.single().region.bounds)
        assertEquals("你好", saved.regions.single().translatedText)
        assertEquals("masking the source must not mutate it", firstHash, VideoTranslationEngine.bitmapHash(first))

        // One observation per sample, both tracked back to the same stable track id.
        val track = "track-${UUID.randomUUID()}"
        val anchorObservation = videoObservation(video.id, video.sha256, engine.identity, 0, track,
            0, 0, 1_000_000, first, firstDetection.regions.single())

        val second = textBitmap("HELLO", dx = 5, dy = 4)
        val secondHash = VideoTranslationEngine.bitmapHash(second)
        val secondPage = engine.pageId(1000)
        val secondDetection = detection(secondPage, moved)
        val fresh = screenVideoRegions(second, secondDetection.regions, ScreenTransform())
        val matches = screenVideoRegionMatches(listOf(anchorObservation.screenRegion()), fresh, 60)
        assertEquals("moved source strokes must still match", setOf(secondDetection.regions.single().id), matches.keys)
        assertEquals(track, matches.values.single().id)
        // The current sample's observation is built before the frame call, as the producer does.
        val currentObservation = videoObservation(video.id, video.sha256, engine.identity, 1, track,
            1_000_000, 1_000_000, 2_000_000, second, secondDetection.regions.single())

        val empty = withTimeout(60_000) { engine.frame(second, 1000, secondDetection, matches.keys) }
        assertTrue("a fully retained sample must not publish new regions", empty.regions.isEmpty())
        assertEquals("matched source strokes must not call the workflow again", 1, server.requestCount)
        assertEquals("retained-sample masking must not mutate the source", secondHash,
            VideoTranslationEngine.bitmapHash(second))

        // An obviously changed source (inverted ink and different text) must not match.
        val changed = textBitmap("WORLD987", dx = 5, dy = 4, inverted = true)
        val changedHash = VideoTranslationEngine.bitmapHash(changed)
        val changedPage = engine.pageId(2000)
        val changedDetection = detection(changedPage, moved)
        val changedFresh = screenVideoRegions(changed, changedDetection.regions, ScreenTransform())
        val changedMatches = screenVideoRegionMatches(listOf(currentObservation.screenRegion()), changedFresh, 60)
        assertTrue("a changed source must not be accepted as the tracked region", changedMatches.isEmpty())
        server.enqueue(reply("WORLD987", "另译"))
        val changedSaved = withTimeout(60_000) { engine.frame(changed, 2000, changedDetection, changedMatches.keys) }
        assertEquals("the changed region must be translated exactly once more", 2, server.requestCount)
        assertEquals(1, changedSaved.regions.size)
        assertEquals("另译", changedSaved.regions.single().translatedText)
        assertEquals(changedHash, VideoTranslationEngine.bitmapHash(changed))
        repeat(2) { assertTrue(server.takeRequest().body.readUtf8().contains("image_url")) }

        // Persist two real, distinct sample artifacts: the translated anchor and an empty newest sample.
        val anchor = container.videoLibrary.publishFrame(video.id, video.sha256, 0, 1000, engine.identity, saved) {}
        val emptySample = container.videoLibrary.publishFrame(video.id, video.sha256, 1000, 2000,
            engine.identity, empty) {}
        val artifacts = container.videoLibrary.artifacts(video.id)
        val anchorArtifact = requireNotNull(artifacts.current(anchor.pageId))
        val emptyArtifact = requireNotNull(artifacts.current(emptySample.pageId))
        assertTrue(emptyArtifact.regions.isEmpty())
        assertNotEquals(anchor.pageId, emptySample.pageId)

        // Persist the same durable rows the tasks producer writes: one interval, two observations.
        val dao = container.workDatabase.results()
        val interval = VideoRegionInterval(intervalId = track, videoId = video.id, sourceHash = video.sha256,
            pageId = anchor.pageId, regionId = anchorArtifact.regions.single().region.id, startMs = 0, endMs = 1000,
            revision = UUID.randomUUID().toString(), trackId = track)
        assertNotEquals(-1L, dao.addRegion(interval))
        dao.saveRegion(interval.copy(endMs = 2000, observedEndMs = 2000), interval.revision)
        dao.putFrame(VideoFrameIndex(video.id, video.sha256, engine.identity, 0, 0, 0, 1_000_000,
            anchor.pageId, anchorArtifact.revision, false, "test"))
        dao.putFrame(VideoFrameIndex(video.id, video.sha256, engine.identity, 1, 1_000_000, 1_000_000,
            2_000_000, emptySample.pageId, emptyArtifact.revision, true, "test"))
        dao.putObservations(listOf(anchorObservation, currentObservation))

        val single = dao.trackRegions(video.id, track)
        assertEquals("the track must keep exactly one interval", 1, single.size)
        assertEquals(2000L, single.single().endMs)
        assertEquals(1, dao.sampleObservations(video.id, video.sha256, engine.identity, 0).size)
        assertEquals(1, dao.sampleObservations(video.id, video.sha256, engine.identity, 1).size)
        assertEquals(1_000_000L, dao.sampleObservations(video.id, video.sha256, engine.identity, 0).single().untilUs)
        assertEquals(1_000_000L, dao.sampleObservations(video.id, video.sha256, engine.identity, 1).single().ptsUs)

        val latest = dao.framesAt(video.id, video.sha256, 1_500_000).single()
        assertEquals(emptySample.pageId, latest.pageId)
        assertTrue(dao.pageRegions(latest.pageId).isEmpty())

        val atMiddle = dao.regionsAtIdentity(video.id, video.sha256, engine.identity, 1500)
        assertEquals("the anchor interval must remain visible at 1.5 s", 1, atMiddle.size)
        assertEquals(anchor.pageId, atMiddle.single().pageId)
        assertEquals(track, atMiddle.single().trackId)
        assertTrue(dao.regionsAtIdentity(video.id, video.sha256, engine.identity, 1999).isNotEmpty())
        assertTrue("the interval must end at 2 s", dao.regionsAtIdentity(video.id, video.sha256,
            engine.identity, 2000).isEmpty())
        assertTrue(dao.regionsAtIdentity(video.id, video.sha256, engine.identity, 2500).isEmpty())
        assertEquals(1L, dao.observationAt(video.id, video.sha256, engine.identity, track, 1_500_000)!!.sampleIndex)
    }

    @Test fun movedObservationFollowsSavedGeometryAndProofRejectsChangedSource() {
        val videoId = UUID.randomUUID().toString()
        val identity = "c".repeat(64)
        val hash = "e".repeat(64)
        val pageId = "video:$videoId:$hash:1000:$identity"
        val anchorRect = PixelRect(40f, 56f, 230f, 118f)
        val movedRect = anchorRect.offset(6f, 5f)

        val original = textBitmap("HELLO")
        val movedBitmap = textBitmap("HELLO", dx = 6, dy = 5)
        val changedBitmap = textBitmap("WORLD987", dx = 6, dy = 5, inverted = true)
        val anchorObservation = videoObservation(videoId, hash, identity, 0, "track", 0, 0, 1_000_000,
            original, SegRegion("$pageId:bubble-0", RegionKind.BUBBLE, anchorRect, 1f, contour(anchorRect)))
        val movedObservation = videoObservation(videoId, hash, identity, 1, "track", 1_000_000, 1_000_000,
            2_000_000, movedBitmap, SegRegion("$pageId:bubble-0", RegionKind.BUBBLE, movedRect, 1f, contour(movedRect)))

        val savedBounds = anchorRect.offset(2f, 1f)
        val savedTextBounds = anchorRect.offset(3f, 2f)
        val savedRegion = PageTranslatedRegion(
            PageTextRegion("$pageId:bubble-0:seg:fixture", RegionKind.BUBBLE, savedBounds, contour(savedBounds),
                "HELLO", listOf(savedTextBounds)), "你好")
        val saved = ReaderPageTranslation(pageId, UUID.randomUUID().toString(), File(app.cacheDir, "proof-source"),
            LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, hash, 320, 180,
            listOf(savedRegion), emptyList(), 1)

        val movedRegion = moveVideoRegion(savedRegion, anchorObservation, movedObservation, 320, 180)
        assertEquals(savedBounds.offset(6f, 5f), movedRegion.region.bounds)
        assertEquals(savedTextBounds.offset(6f, 5f), movedRegion.region.textBounds.single())
        val movedContour = contour(savedBounds).map { PixelPoint(it.x + 6f, it.y + 5f) }
        assertEquals(movedContour, movedRegion.region.contour)
        assertEquals("the human adjustment relative to the anchor bounds must be preserved",
            2f, movedRegion.region.bounds.left - movedRect.left, .001f)
        assertEquals("the human text-box adjustment must be preserved",
            3f, movedRegion.region.textBounds.single().left - movedRect.left, .001f)

        val movedSaved = saved.copy(regions = listOf(movedRegion))
        val proof = VideoFrameProof.capture(original, saved)
        assertTrue("the source-stroke proof must follow the observed movement", proof.matches(movedBitmap, movedSaved, 60))
        assertFalse("the proof must reject a changed source", proof.matches(changedBitmap, movedSaved, 60))
        assertTrue(movedObservation.matches(movedBitmap, 60))
        assertFalse(movedObservation.matches(changedBitmap, 60))
    }

    @Test fun deletedIntervalStaysFilteredWithNewObservationForSameTrack() = runBlocking {
        val video = container.videoLibrary.add(project())
        val identity = "a".repeat(64)
        val hash = video.sha256
        val pageId = "video:${video.id}:$hash:1000:$identity"
        val bounds = PixelRect(40f, 56f, 230f, 118f)
        val region = PageTranslatedRegion(PageTextRegion("$pageId:bubble-0:seg:fixture", RegionKind.BUBBLE,
            bounds, contour(bounds), "HELLO", listOf(bounds)), "你好")
        val artifact = container.videoLibrary.artifacts(video.id).save(pageId = pageId, hash = hash,
            source = LocalTranslationLanguage.ENGLISH, target = LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            width = 320, height = 180, regions = listOf(region), packs = emptyList(), elapsed = 1,
            resultIdentity = identity, requireAbsent = true)

        val bitmap = textBitmap("HELLO")
        val track = "deleted-track"
        val dao = container.workDatabase.results()
        dao.putFrame(VideoFrameIndex(video.id, hash, identity, 0, 0, 0, 2_000_000,
            pageId, artifact.revision, false, "test"))
        assertNotEquals(-1L, dao.addRegion(VideoRegionInterval(intervalId = track, videoId = video.id,
            sourceHash = hash, pageId = pageId, regionId = region.region.id, startMs = 0, endMs = 2000,
            deleted = true, revision = UUID.randomUUID().toString(), trackId = track)))
        dao.putObservations(listOf(videoObservation(video.id, hash, identity, 0, track, 0, 0, 2_000_000,
            bitmap, SegRegion("$pageId:bubble-0", RegionKind.BUBBLE, bounds, 1f, contour(bounds)))))

        assertTrue("a deleted interval must stay filtered even with a same-track observation",
            dao.regionsAtIdentity(video.id, hash, identity, 1500).isEmpty())
        assertTrue(dao.regionsAt(video.id, hash, 1500).isEmpty())
        assertEquals(1, dao.sampleObservations(video.id, hash, identity, 0).size)
        assertEquals(track, dao.observationAt(video.id, hash, identity, track, 1_500_000)!!.trackId)
        assertTrue(dao.region(track)!!.deleted)
    }

    @Test fun segTextScopeChangesEngineIdentityWithoutServerCalls() = runBlocking {
        val apiProfile = profile()
        val video = container.videoLibrary.add(project())
        val bubbles = preparedEngine(video, apiProfile.id, SegTextScope.BUBBLES)
        val freeText = preparedEngine(video, apiProfile.id, SegTextScope.FREE_TEXT)

        assertTrue(bubbles.identity.matches(Regex("[a-f0-9]{64}")))
        assertTrue(freeText.identity.matches(Regex("[a-f0-9]{64}")))
        assertNotEquals("BUBBLES and FREE_TEXT are different result semantics", bubbles.identity, freeText.identity)
        assertEquals("preparing engines must not call the API", 0, server.requestCount)
    }
}
