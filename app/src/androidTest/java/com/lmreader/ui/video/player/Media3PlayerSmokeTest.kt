package com.lmreader.ui.video.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.video.VideoFixture
import com.lmreader.ui.video.VideoMediaSource
import com.lmreader.ui.video.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real activity, MediaSessionService and MediaController against two app-owned fixture videos. */
@RunWith(AndroidJUnit4::class)
class Media3PlayerSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var container: AppContainer
    private lateinit var fixtureDir: File
    private lateinit var firstFile: File
    private lateinit var secondFile: File
    private lateinit var first: VideoProject
    private lateinit var second: VideoProject
    private var baselineModels = 0
    private var firstHash = ""
    private var secondHash = ""

    @Before fun setup() = runBlocking {
        container = AppContainer.from(context)
        container.startupReady.await()
        fixtureDir = File(context.cacheDir, "media3-smoke-${UUID.randomUUID()}").apply { mkdirs() }
        firstFile = File(fixtureDir, "first.mp4")
        secondFile = File(fixtureDir, "second.mp4")
        withContext(Dispatchers.IO) {
            VideoFixture.create(firstFile)
            VideoFixture.create(secondFile, rotation = 90)
        }
        first = install(firstFile)
        second = install(secondFile)
        assertNotEquals("fixtures must be distinct", first.sha256, second.sha256)
        firstHash = ReaderPageArtifactStore.hashFile(firstFile)
        secondHash = ReaderPageArtifactStore.hashFile(secondFile)
        baselineModels = modelCount()
    }

    private suspend fun install(file: File): VideoProject {
        val project = VideoMediaSource.inspect(context, Uri.fromFile(file), persist = false)
        container.videoCatalog.importLegacy(project.id, project.uri, project.name, project.sha256, project.bytes,
            project.durationMs, project.width, project.height, project.rotation, 0)
        return project
    }

    private fun modelCount() = container.localVision.loadedResources.value.size +
        container.localTranslator.loadedResources.value.size + container.localAsr.loadedResources.value.size

    @After fun cleanup() = runBlocking {
        if (::container.isInitialized) {
            if (::first.isInitialized) container.videoCatalog.database.videoCatalogDao().removeVerifiedDuplicate(first.id)
            if (::second.isInitialized) container.videoCatalog.database.videoCatalogDao().removeVerifiedDuplicate(second.id)
        }
        if (::fixtureDir.isInitialized) fixtureDir.deleteRecursively()
    }

    @Test fun activityServicePauseSeekSpeedNextKeepsSourcesAndModelsIntact() = runBlocking {
        var scenario: ActivityScenario<VideoPlayerActivity>? = null
        var controller: MediaController? = null
        try {
            container.taskScheduler.withBarrier {
                scenario = ActivityScenario.launch(
                    VideoPlayerActivity.openIntent(context, first.id, listOf(first.id, second.id))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                try {
                    controller = connect()
                    val player = controller!!
                    waitPlayer("first video did not reach READY/playing") {
                        player.currentMediaItem?.mediaId == first.id && player.playbackState == Player.STATE_READY && player.isPlaying
                    }
                    onMain { player.pause(); player.seekTo(2500L); player.setPlaybackSpeed(1.5f) }
                    waitPlayer("pause/seek/speed was not applied") {
                        !player.isPlaying && abs(player.currentPosition - 2500L) <= 1000L &&
                            abs(player.playbackParameters.speed - 1.5f) < 0.01f
                    }
                    // The activity saves the current position on a 5s ticker; wait for that durable row.
                    withTimeout(10_000) {
                        while (container.videoCatalog.find(first.id)?.positionMs?.let { it in 1500L..3500L } != true) delay(50)
                    }
                    onMain { player.seekToNextMediaItem() }
                    waitPlayer("playlist did not advance to the second item") {
                        player.currentMediaItem?.mediaId == second.id && player.playbackState == Player.STATE_READY
                    }
                    assertEquals(second.id, onMain { player.currentMediaItem?.mediaId })
                    val saved = requireNotNull(container.videoCatalog.find(first.id)).positionMs
                    assertTrue("first position was not persisted: $saved", saved in 1500L..3500L)
                } finally {
                    controller?.let { c -> runCatching { onMain { c.release() } } }
                    controller = null
                    runCatching { scenario?.close() }
                    scenario = null
                }
            // Keep unrelated tasks stopped until the engine ownership assertion completes.
            assertEquals(firstHash, ReaderPageArtifactStore.hashFile(firstFile))
            assertEquals(secondHash, ReaderPageArtifactStore.hashFile(secondFile))
            assertEquals(firstHash, requireNotNull(container.videoCatalog.find(first.id)).sourceHash)
            assertEquals(secondHash, requireNotNull(container.videoCatalog.find(second.id)).sourceHash)
            assertEquals(baselineModels, modelCount())
            }
        } finally {
            controller?.let { c -> runCatching { onMain { c.release() } } }
            runCatching { scenario?.close() }
            container.videoCatalog.database.videoCatalogDao().removeVerifiedDuplicate(first.id)
            container.videoCatalog.database.videoCatalogDao().removeVerifiedDuplicate(second.id)
        }
    }

    private fun connect(): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        onMain {
            future = MediaController.Builder(
                context,
                SessionToken(context, ComponentName(context, VideoPlaybackService::class.java)),
            ).buildAsync()
        }
        return future.get(15, TimeUnit.SECONDS)
    }

    private suspend fun waitPlayer(message: String, timeoutMs: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (onMain(predicate)) return
            delay(50)
        }
        assertTrue(message, onMain(predicate))
    }

    private fun <T> onMain(block: () -> T): T {
        val box = arrayOfNulls<Any>(1)
        instrumentation.runOnMainSync { box[0] = block() }
        @Suppress("UNCHECKED_CAST") return box[0] as T
    }
}
