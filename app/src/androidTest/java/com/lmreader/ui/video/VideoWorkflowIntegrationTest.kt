package com.lmreader.ui.video

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class VideoWorkflowIntegrationTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    @Before fun setup() { app = IsolatedApp(ApplicationProvider.getApplicationContext()); container = AppContainer(app) }
    @After fun cleanup() = runBlocking { closeContainer(container); app.root.deleteRecursively(); Unit }
    private fun project(source: String = "en", target: String = "en") = VideoProject(UUID.randomUUID().toString(), "content://fixture/local", "fixture",
        "d".repeat(64), 0, 8000, 640, 360, 0, source = source, target = target)

    @Test fun productionImageWorkflowTranslatesGeneratedFrameWithoutAPIOrComicWrites() = runBlocking {
        val p = container.videoLibrary.add(project())
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).drawText("HELLO WORLD", 55f, 185f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 56f; typeface = Typeface.DEFAULT_BOLD })
        val before = VideoTranslationEngine.bitmapHash(bitmap)
        val run = VideoTranslationEngine(container, p, TranslationWorkflow.LOCAL_MACHINE)
        try {
            run.prepare()
            val result = run.frame(bitmap, 1000)
            assertEquals(640, result.width); assertEquals(360, result.height)
            assertTrue("Real detector/OCR should find generated text", result.regions.any { it.region.sourceText.isNotBlank() && it.translatedText.isNotBlank() })
            assertEquals(before, VideoTranslationEngine.bitmapHash(bitmap))
            assertNull(container.localPageTranslator.artifacts.current(result.pageId))
            VideoFrameProof.capture(bitmap, result).save(container.videoLibrary.proofs(p.id), result.pageId)
            container.videoLibrary.publishFrame(p.id, p.sha256, 1000, 1350, run.identity, result) {}
            assertNotNull(container.videoLibrary.artifacts(p.id).current(result.pageId))
            // Cross-language image output through the production host and real attachment transport,
            // using a loopback fixture rather than a paid provider.
            val server = MockWebServer(); server.start()
            val profile = ApiProfile("video-image", ApiProfileKind.LLM, "loopback", server.url("/v1").toString(), model = "fixture", retryCount = 0)
            container.apiProfiles.save(profile)
            val content = org.json.JSONObject().put("source", "HELLO WORLD").put("translation", "你好世界").toString()
            val response = org.json.JSONObject().put("choices", org.json.JSONArray().put(org.json.JSONObject().put("message", org.json.JSONObject().put("content", content))))
            // Real DET may split the generated line into several regions; each has one mapped answer.
            repeat(20) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString())) }
            val apiRun = VideoTranslationEngine(container, p.copy(target = "zh-Hans"), TranslationWorkflow.VISION_API.copy(program = WorkflowTemplates.visionApi(profile.id)))
            try {
                apiRun.prepare(); val apiResult = apiRun.frame(bitmap, 2000)
                assertTrue(apiResult.regions.any { it.translatedText == "你好世界" })
                assertTrue(server.takeRequest().body.readUtf8().contains("image_url"))
                assertEquals(before, VideoTranslationEngine.bitmapHash(bitmap))
            } finally { apiRun.close(); server.shutdown() }
        } finally { run.close(); bitmap.recycle() }
    }
    @Test fun pureLocalSubtitleWorkflowDoesNotNeedSegModelsOrAPIAndKeepsManualText() = runBlocking {
        val run = VideoTranslationEngine(container, project(), TranslationWorkflow.SUBTITLE_LOCAL)
        val cues = listOf(VideoSubtitleCue("one", 0, 1000, "Hello"), VideoSubtitleCue("two", 1000, 2000, "World", "人工", true))
        try {
            run.prepare()
            val output = run.subtitles(cues) { _, _, _ -> }
            assertEquals("Hello", output[0].translation); assertEquals(cues[1], output[1])
            assertTrue(container.localPageTranslator.artifacts.root.listFiles().orEmpty().isEmpty())
        } finally { run.close() }
    }
    @Test fun subtitleApiUsesRealTextRequestsResumesAfterCancellationAndRetainsHumanCue() = runBlocking {
        val server = MockWebServer(); server.start()
        val profile = ApiProfile("video-test", ApiProfileKind.LLM, "local fixture", server.url("/v1").toString(), model = "fixture", retryCount = 0)
        container.apiProfiles.save(profile)
        val workflow = TranslationWorkflow.SUBTITLE_API.copy(program = SubtitleWorkflowTemplates.program(profile.id))
        val cues = listOf(VideoSubtitleCue("one", 0, 1000, "Hello"), VideoSubtitleCue("two", 500, 2000, "World"), VideoSubtitleCue("manual", 2000, 3000, "Human", "人工", true))
        val p = container.videoLibrary.add(project(target = "zh-Hans").copy(cues = cues))
        val run = VideoTranslationEngine(container, p, workflow)
        fun reply(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"$text\"}}]}")
        server.enqueue(reply("你好")); server.enqueue(reply("世界"))
        try {
            run.prepare()
            try {
                run.subtitles(cues) { cue, _, _ ->
                    container.videoLibrary.change(p.id) { current -> current.copy(cues = current.cues.map { if (it.id == cue.id) cue else it }) }
                    throw CancellationException("stop after durable cue")
                }; fail("Expected cancellation")
            } catch (_: CancellationException) {}
            val reopened = VideoLibraryStore(container.videoLibrary.root).current(p.id)
            assertEquals("你好", reopened.cues[0].translation); assertEquals("", reopened.cues[1].translation)
            val result = run.subtitles(reopened.cues) { _, _, _ -> }
            assertEquals(listOf("你好", "世界", "人工"), result.map { it.translation })
            assertEquals(cues.map { it.id to (it.startMs to it.endMs) }, result.map { it.id to (it.startMs to it.endMs) })
            assertEquals(2, server.requestCount)
            repeat(2) { val body = server.takeRequest().body.readUtf8(); assertFalse(body.contains("image_url")); assertFalse(body.contains("inline_data")) }
        } finally { run.close(); server.shutdown() }
    }
}
