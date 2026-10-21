package com.lmreader.ui.video

import android.net.Uri
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.R
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.database.media.VideoFrameIndex
import com.lmreader.core.database.media.VideoRegionInterval
import com.lmreader.core.database.media.VideoSettingsRecord
import com.lmreader.core.model.LocalTranslationLanguage
import com.lmreader.core.model.PageTextRegion
import com.lmreader.core.model.PageTranslatedRegion
import com.lmreader.core.model.PixelPoint
import com.lmreader.core.model.PixelRect
import com.lmreader.core.model.RegionKind
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.reader.TapAwareSubsamplingImageView
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.video.editor.VideoBubbleEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Real-frame UI acceptance for the shared bubble toolbar and the in-editor cue transport.
 *
 * The fixture is generated locally, the scheduler can never start a task, and no API or
 * ASR model is touched. A single real ExoPlayer is created on the activity UI thread and
 * handed to [VideoBubbleEditor]; the editor must reuse it rather than create another.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class VideoEditorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var videoFile: File
    private lateinit var project: VideoProject
    private lateinit var videoId: String
    private var player: ExoPlayer? = null
    private val seekMs = AtomicLong(-1L)

    @Before fun setup() = runBlocking<Unit> {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app) {
            TaskScheduler(it, canStart = { false }, acquire = { null }, release = {})
        }
        container.startupReady.await()
        container.taskScheduler.ready.await()

        videoFile = File(app.cacheDir, "editor-${UUID.randomUUID()}.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(videoFile) }
        val inspected = VideoMediaSource.inspect(app, Uri.fromFile(videoFile), persist = false)
        container.videoCatalog.importLegacy(inspected.id, inspected.uri, inspected.name, inspected.sha256,
            inspected.bytes, inspected.durationMs, inspected.width, inspected.height, inspected.rotation, 0)
        videoId = inspected.id
        project = container.videoTasks.ensureProject(videoId)
        seekMs.set(-1L)

        // Create on the activity main thread; release happens on the same thread in cleanup.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            player = ExoPlayer.Builder(app).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
                prepare()
            }
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        // Destroy the Compose dialog first so the editor detaches the TextureView;
        // then release the same player instance on the activity main thread.
        runCatching { compose.activityRule.scenario.close() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            runCatching { player?.release() }
            player = null
        }
        if (::container.isInitialized) {
            runCatching { withTimeout(5_000) { container.taskScheduler.interrupt("TEST_CLEANUP") } }
            runCatching { closeContainer(container) }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
        Unit
    }

    /**
     * Two machine artifacts anchored at different source times, plus a newer empty
     * sample that is the first hit at 1500 ms. Both live intervals must still be editable.
     */
    @Test fun editPublishesOneAtomicManualSnapshotForAllActiveAnchors() = runBlocking<Unit> {
        val seed = seedAnchors()
        val dao = container.videoResults.results()
        val artifacts = container.videoLibrary.artifacts(videoId)

        val newest = withContext(Dispatchers.IO) {
            dao.sample(videoId, project.sha256, IDENTITY, 1L)
        }
        assertEquals(seed.pageLatest, newest?.pageId)
        assertTrue(artifacts.current(seed.pageLatest)!!.regions.isEmpty())
        val firstAtPlayhead = withContext(Dispatchers.IO) {
            dao.framesAt(videoId, project.sha256, 1_500_000L)
        }.first { it.identity == IDENTITY }
        assertEquals("the empty sample must be the newest candidate", seed.pageLatest, firstAtPlayhead.pageId)

        showEditor()

        // Espresso reads the live AndroidView in the dialog, not guessed Compose geometry.
        val editable = awaitEditableRegions()
        assertEquals(setOf("OLD_A_SOURCE", "OLD_B_SOURCE"), editable.map { it.region.sourceText }.toSet())
        val editedRegionId = editable.single { it.region.sourceText == "OLD_A_SOURCE" }.region.id
        assertNotEquals(seed.regionAId, editedRegionId)
        captureEditor("bubbles")

        clickRegionCenter(editable.first { it.region.id == editedRegionId })
        awaitSelected(editedRegionId)

        val editDescription = compose.activity.getString(R.string.reader_bubble_text)
        val saveDescription = compose.activity.getString(R.string.reader_bubble_save)
        val applyDescription = compose.activity.getString(R.string.reader_bubble_apply)
        awaitEnabled(hasContentDescription(editDescription))
        compose.onNodeWithContentDescription(editDescription).performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("OLD_A"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction() and hasText("OLD_A")).performTextReplacement("newA")
        compose.onNodeWithText(applyDescription).performClick()
        awaitEnabled(hasContentDescription(saveDescription))
        compose.onNodeWithContentDescription(saveDescription).assertIsEnabled().performClick()

        compose.waitUntil(TIMEOUT) {
            readDb {
                val a = dao.region(seed.intervalAId)
                val b = dao.region(seed.intervalBId)
                a != null && b != null && a.pageId != seed.pageA && a.pageId == b.pageId
            }
        }

        val rowA = readDb { dao.region(seed.intervalAId)!! }
        val rowB = readDb { dao.region(seed.intervalBId)!! }
        assertNotEquals(seed.pageA, rowA.pageId)
        assertTrue("expected one new manual page, got ${rowA.pageId}",
            rowA.pageId.startsWith("video:$videoId:manual:"))
        assertEquals("both retained bubbles must point at the same manual snapshot", rowA.pageId, rowB.pageId)
        assertEquals(1_500_000L, rowA.anchorUs ?: -1L)
        assertEquals(1_500_000L, rowB.anchorUs ?: -1L)
        assertFalse("unchanged machine timing must stay automatic", rowA.manualTiming)
        assertFalse("unchanged machine timing must stay automatic", rowB.manualTiming)
        assertEquals("original A interval preserved", 0L, rowA.startMs)
        assertEquals("original A interval preserved", 6_000L, rowA.endMs)
        assertEquals("original B interval preserved", 1_000L, rowB.startMs)
        assertEquals("original B interval preserved", 7_000L, rowB.endMs)

        val saved = artifacts.current(rowA.pageId) ?: error("manual snapshot artifact missing")
        assertEquals("both retained bubbles belong to the snapshot", 2, saved.regions.size)
        assertEquals("newA", saved.regions.single { it.region.id == rowA.regionId }.translatedText)
        assertEquals("OLD_B", saved.regions.single { it.region.id == rowB.regionId }.translatedText)

        val originalA = artifacts.current(seed.pageA)!!
        val originalB = artifacts.current(seed.pageB)!!
        assertEquals("machine artifact A revision must not change", seed.originalA.revision, originalA.revision)
        assertEquals("machine artifact A data must not change", seed.originalA.regions, originalA.regions)
        assertEquals("machine artifact B revision must not change", seed.originalB.revision, originalB.revision)
        assertEquals("machine artifact B data must not change", seed.originalB.regions, originalB.regions)
    }

    /** Cue navigation and transport use the same real player, including a video-only file. */
    @Test fun cueEditorAndTransportSeekToStartsAndActuallyPlay() = runBlocking<Unit> {
        withContext(Dispatchers.IO) {
            val dao = container.videoResults.results()
            check(dao.addCue(VideoCueRecord(videoId, "cue-1", project.sha256, "manual", 0L, 2_500L,
                "source-one", "译文一", revision = UUID.randomUUID().toString())) != -1L)
            check(dao.addCue(VideoCueRecord(videoId, "cue-2", project.sha256, "manual", 3_000L, 7_500L,
                "source-two", "译文二", revision = UUID.randomUUID().toString())) != -1L)
            check(dao.addCue(VideoCueRecord(videoId, "cue-3", project.sha256, "manual", 3_000L, 7_500L,
                "source-three", "译文三", revision = UUID.randomUUID().toString())) != -1L)
        }
        showEditor()

        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("译文一"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasSetTextAction() and hasText("译文一")).assertExists()
        compose.onNode(hasSetTextAction() and hasText("译文一")).performScrollTo()
        captureEditor("subtitles")

        awaitEnabled(hasText("切换原文"))
        compose.onNodeWithText("切换原文").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("source-one"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        awaitEnabled(hasText("切换译文"))
        compose.onNodeWithText("切换译文").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("译文一"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The right triangle seeks to the next cue's START, never to a midpoint.
        awaitEnabled(hasText("▷"))
        compose.onNodeWithText("▷").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) { seekMs.get() == 3_000L }
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("译文二"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        awaitEnabled(hasText("▷"))
        compose.onNodeWithText("▷").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("译文三")).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(3_000L, seekMs.get())
        awaitEnabled(hasText("◁"))
        compose.onNodeWithText("◁").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("译文二")).fetchSemanticsNodes().isNotEmpty()
        }

        // Wait for the frame reload before the transport can accept the next seek.
        awaitEnabled(hasContentDescription("播放"))
        compose.onNode(hasSetTextAction() and hasText("当前位置")).performScrollTo().performTextReplacement("5")
        compose.onNodeWithText("跳转").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) { seekMs.get() == 5_000L }
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasSetTextAction() and hasText("00:00:05.000"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        awaitEnabled(hasContentDescription("播放"))
        compose.onNodeWithContentDescription("播放").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            onMain { player?.let { it.isPlaying && it.currentPosition >= 5_000L } == true }
        }
        awaitEnabled(hasContentDescription("暂停"))
        compose.onNodeWithContentDescription("暂停").performScrollTo().performClick()
        compose.waitUntil(TIMEOUT) {
            onMain { player?.let { !it.isPlaying && it.currentPosition >= 5_000L } == true }
        }
    }

    private fun showEditor() {
        compose.setContent {
            MaterialTheme {
                VideoBubbleEditor(
                    container = container,
                    project = project,
                    positionMs = 1_500L,
                    onSeek = { seekMs.set(it) },
                    onDismiss = {},
                    player = player,
                )
            }
        }
    }

    private data class AnchorSeed(
        val pageA: String,
        val pageB: String,
        val pageLatest: String,
        val regionAId: String,
        val regionBId: String,
        val intervalAId: String,
        val intervalBId: String,
        val originalA: ReaderPageTranslation,
        val originalB: ReaderPageTranslation,
    )

    private suspend fun seedAnchors(): AnchorSeed = withContext(Dispatchers.IO) {
        val artifacts = container.videoLibrary.artifacts(videoId)
        val pageA = "video:$videoId:pageA"
        val pageB = "video:$videoId:pageB"
        val pageLatest = "video:$videoId:pageLatest"
        val regionAId = "$pageA:regionA"
        val regionBId = "$pageB:regionB"
        val japanese = LocalTranslationLanguage.JAPANESE
        val chinese = LocalTranslationLanguage.CHINESE_SIMPLIFIED

        val originalA = artifacts.save(
            pageId = pageA, hash = project.sha256, source = japanese, target = chinese,
            width = project.width, height = project.height,
            regions = listOf(translatedRegion(pageA, "regionA",
                PixelRect(20f, 20f, 70f, 50f), "OLD_A_SOURCE", "OLD_A")),
            packs = emptyList(), elapsed = 0L, resultIdentity = IDENTITY, producerTaskId = "ui-test",
        )
        val originalB = artifacts.save(
            pageId = pageB, hash = project.sha256, source = japanese, target = chinese,
            width = project.width, height = project.height,
            regions = listOf(translatedRegion(pageB, "regionB",
                PixelRect(95f, 65f, 145f, 100f), "OLD_B_SOURCE", "OLD_B")),
            packs = emptyList(), elapsed = 0L, resultIdentity = IDENTITY, producerTaskId = "ui-test",
        )
        val latest = artifacts.save(
            pageId = pageLatest, hash = project.sha256, source = japanese, target = chinese,
            width = project.width, height = project.height,
            regions = emptyList(), packs = emptyList(), elapsed = 0L,
            resultIdentity = IDENTITY, producerTaskId = "ui-test",
        )

        val dao = container.videoResults.results()
        // Different target anchors for A and B; sample 1 is the empty newest candidate.
        dao.putFrame(VideoFrameIndex(videoId, project.sha256, IDENTITY, 0L,
            0L, 0L, 6_000_000L, pageA, originalA.revision, false, "ui-test"))
        dao.putFrame(VideoFrameIndex(videoId, project.sha256, IDENTITY, 1L,
            1_500_000L, 1_000_000L, 2_000_000L, pageLatest, latest.revision, true, "ui-test"))
        dao.putFrame(VideoFrameIndex(videoId, project.sha256, IDENTITY, 2L,
            1_000_000L, 1_000_000L, 7_000_000L, pageB, originalB.revision, false, "ui-test"))

        val intervalAId = UUID.randomUUID().toString()
        val intervalBId = UUID.randomUUID().toString()
        dao.addRegion(VideoRegionInterval(
            intervalId = intervalAId, videoId = videoId, sourceHash = project.sha256,
            pageId = pageA, regionId = regionAId, startMs = 0L, endMs = 6_000L,
            observedStartMs = 0L, observedEndMs = 6_000L, manualTiming = false, deleted = false,
            revision = UUID.randomUUID().toString(), trackId = "track-a-$videoId", anchorUs = null,
        ))
        dao.addRegion(VideoRegionInterval(
            intervalId = intervalBId, videoId = videoId, sourceHash = project.sha256,
            pageId = pageB, regionId = regionBId, startMs = 1_000L, endMs = 7_000L,
            observedStartMs = 1_000L, observedEndMs = 7_000L, manualTiming = false, deleted = false,
            revision = UUID.randomUUID().toString(), trackId = "track-b-$videoId", anchorUs = null,
        ))
        dao.putSettings(VideoSettingsRecord(
            videoId = videoId,
            json = JSONObject().put("frameIdentity", IDENTITY).toString(),
            style = "{}",
            revision = UUID.randomUUID().toString(),
            migrated = true,
        ))

        AnchorSeed(pageA, pageB, pageLatest, regionAId, regionBId, intervalAId, intervalBId,
            originalA, originalB)
    }

    private fun translatedRegion(
        pageId: String,
        suffix: String,
        bounds: PixelRect,
        source: String,
        translation: String,
    ): PageTranslatedRegion = PageTranslatedRegion(
        PageTextRegion(
            id = "$pageId:$suffix",
            kind = RegionKind.BUBBLE,
            bounds = bounds,
            contour = listOf(
                PixelPoint(bounds.left, bounds.top),
                PixelPoint(bounds.right, bounds.top),
                PixelPoint(bounds.right, bounds.bottom),
                PixelPoint(bounds.left, bounds.bottom),
            ),
            sourceText = source,
            textBounds = listOf(bounds),
        ),
        translatedText = translation,
    )

    private fun awaitEditableRegions(): List<PageTranslatedRegion> {
        var regions: List<PageTranslatedRegion> = emptyList()
        var lastFailure: Throwable? = null
        try {
            compose.waitUntil(TIMEOUT) {
                val attempt = runCatching { readEditableRegions() }
                lastFailure = attempt.exceptionOrNull()
                regions = attempt.getOrDefault(emptyList())
                regions.size >= 2
            }
        } catch (failure: Throwable) {
            runCatching { captureEditor("bubbles-failure") }
            throw AssertionError("Editor not ready: regions=${regions.size}; last view failure=$lastFailure", failure)
        }
        return regions
    }

    private fun readEditableRegions(): List<PageTranslatedRegion> {
        val regions = AtomicReference<List<PageTranslatedRegion>>(emptyList())
        tapViewAssertion { view ->
            check(view.isReady) { "bubble canvas is not ready yet" }
            check(view.overlay != null) { "bubble overlay is not ready yet" }
            val fitted = minOf(view.width.toFloat() / view.sWidth, view.height.toFloat() / view.sHeight)
            check(kotlin.math.abs(view.scale - fitted) < .03f) { "paused frame must fit its viewport: ${view.scale} != $fitted" }
            regions.set(view.editableRegions.toList())
        }
        return regions.get()
    }

    private fun awaitSelected(id: String) {
        compose.waitUntil(TIMEOUT) {
            runCatching { readSelectedBubble() }.getOrDefault(null) == id
        }
    }

    private fun readSelectedBubble(): String? {
        val selected = AtomicReference<String?>(null)
        tapViewAssertion { view -> selected.set(view.selectedBubble) }
        return selected.get()
    }

    /** Click the exact center of [region] after mapping source pixels through the view. */
    private fun clickRegionCenter(region: PageTranslatedRegion) {
        val screen = AtomicReference<FloatArray?>(null)
        tapViewAssertion { view ->
            val bounds = region.region.bounds
            val point = checkNotNull(view.sourceToViewCoord(
                (bounds.left + bounds.right) / 2f,
                (bounds.top + bounds.bottom) / 2f,
            )) { "region center is outside the visible frame" }
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            screen.set(floatArrayOf(location[0] + point.x, location[1] + point.y))
        }
        val coordinates = checkNotNull(screen.get()) { "region center was not resolved" }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val now = SystemClock.uptimeMillis()
        for((action, time) in listOf(MotionEvent.ACTION_DOWN to now, MotionEvent.ACTION_UP to now + 50)) {
            val event = MotionEvent.obtain(now, time, action, coordinates[0], coordinates[1], 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
        }
    }

    /** View assertions run on the main thread; the test only reads the captured values. */
    private fun tapViewAssertion(assertion: (TapAwareSubsamplingImageView) -> Unit) {
        onMain {
            fun find(view: View): TapAwareSubsamplingImageView? {
                if(view is TapAwareSubsamplingImageView && view.isShown) return view
                if(view is ViewGroup) for(index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            val image = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::find)
                ?: throw AssertionError("bubble canvas not found")
            assertion(image)
        }
    }

    private fun awaitEnabled(matcher: SemanticsMatcher) {
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(matcher).fetchSemanticsNodes()
                .any { node -> !node.config.contains(SemanticsProperties.Disabled) }
        }
    }

    private fun captureEditor(label: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val orientation = if(context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(context.getExternalFilesDir("video-validation"), "editor-$orientation-$label.png").outputStream().use {
                check(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }

    private fun <T> readDb(block: suspend () -> T): T = runBlocking(Dispatchers.IO) { block() }

    private fun <T> onMain(block: () -> T): T {
        var value: Any? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { value = block() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private companion object {
        const val TIMEOUT = 30_000L
        val IDENTITY: String = "e".repeat(64)
    }
}
