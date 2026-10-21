package com.lmreader.ui.video

import android.graphics.*
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class VideoPlaybackIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private var view: VideoPlaybackView? = null
    private val models = ViewModelStore()
    @Before fun setup() { app = IsolatedApp(ApplicationProvider.getApplicationContext()); container = AppContainer(app) }
    @After fun cleanup() = runBlocking {
        withContext(Dispatchers.Main) { view?.release(); models.clear() }
        container.videoSessions.stopAndAwait(); closeContainer(container); app.root.deleteRecursively(); Unit
    }
    private fun movie(rotation: Int = 0): VideoProject = runBlocking {
        val file = File(app.filesDir, "generated-$rotation.mp4")
        withContext(Dispatchers.IO) { VideoFixture.create(file, rotation) }
        container.videoLibrary.add(VideoMediaSource.inspect(app, Uri.fromFile(file), persist = false))
    }
    private fun show(p: VideoProject, discontinuity: () -> Unit = {}) {
        compose.setContent { AndroidView(factory = { VideoPlaybackView(it, p, discontinuity) { message -> error(message) }.also { view = it } }, modifier = Modifier.fillMaxSize()) }
        waitFor { view?.ready == true }
    }
    private fun waitFor(timeout: Long = 15000, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            var okay = false; compose.runOnIdle { okay = predicate() }
            if (okay) return
            Thread.sleep(80)
        }
        compose.runOnIdle { assertTrue("Timed out waiting for video state", predicate()) }
    }
    @Test fun actualLocalVideoPlaysSeeksChangesSpeedAndRestoresPositionWithoutChangingOriginal() {
        val p = movie(); val file = File(Uri.parse(p.uri).path!!); val before = file.readBytes()
        val invalidations = AtomicInteger()
        show(p) { invalidations.incrementAndGet() }
        compose.runOnIdle { view!!.play() }
        waitFor { view!!.positionMs > 800 }
        compose.runOnIdle { view!!.pause(); view!!.seek(5000); assertTrue(view!!.seeking) }
        waitFor { view!!.ready && view!!.positionMs >= 4500 }
        compose.runOnIdle { view!!.setSpeed(1.5f); view!!.play() }
        waitFor { view!!.positionMs > 6000 }
        compose.runOnIdle { view!!.pause(); container.videoLibrary.change(p.id) { it.copy(positionMs = view!!.positionMs) } }
        assertTrue(VideoLibraryStore(container.videoLibrary.root).current(p.id).positionMs >= 6000)
        assertTrue(invalidations.get() >= 3); assertArrayEquals(before, file.readBytes())
    }
    @Test fun rotatedVideoCaptureMatchesDisplayedAspectAndStillPlays() {
        val p = movie(90); assertEquals(90, p.rotation)
        show(p)
        compose.runOnIdle { view!!.play() }
        waitFor { view!!.positionMs > 500 }
        compose.runOnIdle {
            val frame = view!!.capture()!!
            try { assertTrue("Rotation must map to portrait source pixels", frame.height > frame.width) }
            finally { frame.recycle() }
        }
    }
    @Test fun continuousControllerPersistsMatchingFramesAndSeekDiscardsLateWork() = runBlocking {
        val p = movie(); val calls = AtomicInteger(); val firstStarted = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        lateinit var vm: VideoViewModel
        withContext(Dispatchers.Main) {
            vm = VideoViewModel(container, p.id) { _, _ -> object : VideoFrameEngine {
                override val identity = "c".repeat(64)
                override suspend fun prepare() {}
                override suspend fun close() {}
                override suspend fun frame(bitmap: Bitmap, positionMs: Long): ReaderPageTranslation {
                    val n = calls.incrementAndGet()
                    if (n == 1) { firstStarted.complete(Unit); withContext(NonCancellable) { release.await() } }
                    currentCoroutineContext().ensureActive()
                    return ReaderPageTranslation("video:${p.id}:$positionMs:$identity", UUID.randomUUID().toString(), File(app.cacheDir, "memory"),
                        LocalTranslationLanguage.JAPANESE, LocalTranslationLanguage.CHINESE_SIMPLIFIED, VideoTranslationEngine.bitmapHash(bitmap), bitmap.width, bitmap.height,
                        listOf(PageTranslatedRegion(PageTextRegion("video:${p.id}:$positionMs:$identity:region", RegionKind.FREE_TEXT,
                            PixelRect(bitmap.width * .1f, bitmap.height / 6f, bitmap.width * .875f, bitmap.height * .57f), emptyList(), "source", emptyList()), "translated")),
                        emptyList(), 0, BubbleRenderSettings(fillMode = BubbleFillMode.WHITE, opacityPercent = 100))
                }
            } }
            models.put("video", vm)
        }
        show(p, vm::invalidate)
        withContext(Dispatchers.Main) { vm.start(true); view!!.play() }
        val ticker = launch(Dispatchers.Main) { while (isActive) { vm.tick(view!!, true); delay(250) } }
        try {
            withTimeout(15000) { firstStarted.await() }
            withContext(Dispatchers.Main) { view!!.seek(5000) }
            release.complete(Unit)
            withTimeout(15000) { while (container.videoLibrary.current(p.id).frames.isEmpty()) {
                vm.state.value.error?.let { error("Controller failure: $it") }; delay(150)
            } }
            val rows = container.videoLibrary.current(p.id).frames
            assertTrue("Cancelled first frame must never be published", rows.all { it.atMs >= 4500 })
            delay(700)
            withContext(Dispatchers.Main) {
                assertTrue("Playback must actually draw the translated mask", whitePixels(view!!) > 10)
                view!!.seek(2000)
                assertEquals("A seek must immediately remove the old mask", 0, whitePixels(view!!))
                view!!.pause(); vm.stopAndAwait()
            }
            assertTrue(calls.get() in 2..4)
            val restored = VideoLibraryStore(container.videoLibrary.root)
            restored.validateResults(); assertTrue(restored.current(p.id).frames.isNotEmpty())
        } finally { release.complete(Unit); ticker.cancelAndJoin(); withContext(Dispatchers.Main) { vm.stopAndAwait() } }
    }
    @Test fun playerScreenOpensSubtitleEditorAndPersistsHumanCorrection() {
        val p = movie()
        container.videoLibrary.change(p.id) { it.copy(cues = listOf(VideoSubtitleCue("cue", 0, 1500, "Hello", "机器"))) }
        val config = android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.SIMPLIFIED_CHINESE) }
        val localized = android.view.ContextThemeWrapper(compose.activity, compose.activity.applicationInfo.theme).apply { applyOverrideConfiguration(config) }
        compose.setContent { CompositionLocalProvider(LocalContext provides localized) {
            VideoPlayerScreen(container, p.id, {}, {}, {})
        } }
        compose.onNodeWithText("校对字幕").performScrollTo().performClick()
        compose.onNodeWithText("编辑译文").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("机器")).performTextReplacement("人工修正")
        compose.onNodeWithText("保存校对").performClick()
        compose.waitUntil(5000) { container.videoLibrary.current(p.id).cues.single().translation == "人工修正" }
        assertTrue(VideoLibraryStore(container.videoLibrary.root).current(p.id).cues.single().manuallyEdited)
    }
    private fun whitePixels(view: VideoPlaybackView): Int {
        val bitmap = Bitmap.createBitmap((view.width / 4).coerceAtLeast(1), (view.height / 4).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            view.draw(Canvas(bitmap).apply { scale(.25f, .25f) })
            val pixels = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            pixels.count { it == Color.WHITE }
        } finally { bitmap.recycle() }
    }
}
