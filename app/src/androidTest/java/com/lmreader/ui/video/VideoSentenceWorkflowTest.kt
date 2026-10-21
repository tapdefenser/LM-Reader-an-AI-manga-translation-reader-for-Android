package com.lmreader.ui.video

import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** End-to-end sentence workflow over one generated MP4 carrying both video and audio samples. */
@RunWith(AndroidJUnit4::class)
class VideoSentenceWorkflowTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var root: File

    @Before fun setup() = runBlocking<Unit> {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app)
        root = File(app.cacheDir, "sentence-workflow-${UUID.randomUUID()}").apply { mkdirs() }
    }

    @After fun cleanup() = runBlocking<Unit> {
        closeContainer(container)
        app.root.deleteRecursively()
        Unit
    }

    @Test fun subtitleWorkflowSendsSentenceAudioAndMidpointImageFromMergedMedia() = runBlocking<Unit> {
        val baseline = usage()
        val video = File(root, "video.mp4")
        val audio = File(root, "audio.mp4")
        val merged = File(root, "merged.mp4")
        withContext(Dispatchers.IO) {
            VideoFixture.create(video)
            encodeAudio(audio, 3_000)
            merge(video, audio, merged)
        }
        val mergedUri = Uri.fromFile(merged).toString()
        val project = container.videoLibrary.add(VideoProject(
            UUID.randomUUID().toString(), mergedUri, "merged", sha256(merged), merged.length(),
            8_000, 160, 120, 0, source = "en", target = "zh-Hans"))
        val cue = VideoSubtitleCue("sentence-one", 600, 1_200, "Hello")
        val server = MockWebServer().apply { start() }
        val profile = ApiProfile("sentence-workflow", ApiProfileKind.LLM, "loopback fixture",
            server.url("/v1").toString(), format = ApiFormat.CHAT, model = "fixture", retryCount = 0)
        container.apiProfiles.save(profile)
        val workflow = TranslationWorkflow.SUBTITLE_API.copy(program = sentenceProgram(profile.id))
        val engine = VideoTranslationEngine(container, project, workflow, listOf(profile))
        try {
            assertFalse(workflow.program.uses(WorkflowKind.SEG))
            assertFalse(workflow.program.uses(WorkflowKind.OCR))
            assertFalse(workflow.program.uses(WorkflowKind.TRANSLATE))
            val loadedBefore = container.localTranslator.loadedResources.value
            engine.prepare()
            assertEquals("prepare must not load a machine-translation model", loadedBefore,
                container.localTranslator.loadedResources.value)
            assertTrue("prepare must not create OCR artifacts",
                container.localPageTranslator.artifacts.root.listFiles().orEmpty().isEmpty())

            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你好世界\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
            var progressCalls = 0
            val output = engine.subtitles(listOf(cue), VideoSentenceResources(app, mergedUri, 1)) { _, done, total ->
                progressCalls++
                assertEquals(1, done)
                assertEquals(1, total)
            }
            assertEquals(1, progressCalls)
            assertEquals(1, server.requestCount)
            val result = output.single()
            assertEquals(cue.id, result.id)
            assertEquals(cue.source, result.source)
            assertEquals(cue.startMs, result.startMs)
            assertEquals(cue.endMs, result.endMs)
            assertEquals("你好世界", result.translation)

            val request = server.takeRequest()
            val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
            val content = messages.getJSONObject(messages.length() - 1).getJSONArray("content")
            var audioBase64: String? = null
            var imageUrl: String? = null
            for (index in 0 until content.length()) {
                val item = content.getJSONObject(index)
                when (item.getString("type")) {
                    "input_audio" -> {
                        val input = item.getJSONObject("input_audio")
                        assertEquals("wav", input.getString("format"))
                        audioBase64 = input.getString("data")
                    }
                    "image_url" -> imageUrl = item.getJSONObject("image_url").getString("url")
                }
            }
            val wav = Base64.decode(requireNotNull(audioBase64), Base64.DEFAULT)
            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
            assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
            val wavBuffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1, wavBuffer.getShort(20).toInt())
            assertEquals(1, wavBuffer.getShort(22).toInt())
            assertEquals(16_000, wavBuffer.getInt(24))
            assertEquals(16, wavBuffer.getShort(34).toInt())
            val dataSize = wavBuffer.getInt(40)
            assertEquals(44 + dataSize, wav.size)
            val durationMs = dataSize / 2L * 1_000L / 16_000L
            assertTrue("sentence recording duration ${durationMs}ms", abs(durationMs - 600L) <= 160L)
            var peak = 0
            var offset = 44
            while (offset + 1 < wav.size) {
                peak = maxOf(peak, abs(wavBuffer.getShort(offset).toInt()))
                offset += 2
            }
            assertTrue("sentence recording must not be silent", peak > 1_000)

            val url = requireNotNull(imageUrl)
            assertTrue(url.startsWith("data:image/jpeg;base64,"))
            val jpeg = Base64.decode(url.substringAfter(','), Base64.DEFAULT)
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            try {
                assertEquals(160, bitmap.width)
                assertEquals(120, bitmap.height)
                var ink = 0
                for (y in 22..66) for (x in 20..132) {
                    val pixel = bitmap.getPixel(x, y)
                    if ((pixel shr 16 and 255) + (pixel shr 8 and 255) + (pixel and 255) < 300) ink++
                }
                assertTrue("midpoint frame must contain fixture strokes, found $ink dark pixels", ink > 50)
                assertTrue("fixture stroke must contrast with the background",
                    abs((bitmap.getPixel(50, 40) and 255) - (bitmap.getPixel(5, 5) and 255)) > 80)
            } finally { bitmap.recycle() }

            val logged = requireNotNull(container.apiLogs.detail(container.apiLogs.records.value.single().id))
            assertFalse("API log must not contain the actual audio base64",
                logged.info.request.contains(requireNotNull(audioBase64)))
            assertTrue("API log should retain only attachment metadata", logged.info.request.contains("[音频附件"))
        } finally {
            engine.close()
            server.shutdown()
            root.deleteRecursively()
        }
        assertEquals("decode/pixels leases must return to baseline", baseline, usage())
    }

    /** Keep the standard subtitle program and only add current audio/image inputs to its API node. */
    private fun sentenceProgram(profileId: String): WorkflowProgram {
        val base = SubtitleWorkflowTemplates.program(profileId)
        fun patch(node: WorkflowNode): WorkflowNode = node.copy(
            inputs = if (node.kind == WorkflowKind.API) node.inputs + mapOf(
                "audios" to WorkflowSystem.ref(WorkflowSystem.CURRENT_AUDIO),
                "images" to WorkflowSystem.ref(WorkflowSystem.CURRENT_IMAGE)) else node.inputs,
            children = node.children.map { patch(it) },
            otherwise = node.otherwise.map { patch(it) })
        return base.copy(rows = base.rows.map { patch(it) })
    }

    private fun usage(): Pair<Long, Long> = ResourceGovernor.shared.usage.value.let { usage ->
        usage.sumOf { it.resources["decode"] ?: 0L } to usage.sumOf { it.resources["pixels"] ?: 0L }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Copy real samples from both fixtures; video is track 0 and audio is track 1. */
    private fun merge(video: File, audio: File, output: File) {
        val videoSource = MediaExtractor()
        val audioSource = MediaExtractor()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            videoSource.setDataSource(video.absolutePath)
            audioSource.setDataSource(audio.absolutePath)
            val videoTrack = (0 until videoSource.trackCount).first { index ->
                videoSource.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            val audioTrack = (0 until audioSource.trackCount).first { index ->
                audioSource.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            videoSource.selectTrack(videoTrack)
            audioSource.selectTrack(audioTrack)
            val outVideo = muxer.addTrack(videoSource.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audioSource.getTrackFormat(audioTrack))
            check(outVideo == 0 && outAudio == 1) { "merged tracks must be video 0/audio 1" }
            muxer.start()
            started = true
            copySamples(videoSource, muxer, outVideo)
            copySamples(audioSource, muxer, outAudio)
        } finally {
            videoSource.release()
            audioSource.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        check(output.length() > 0) { "merged fixture was not written" }
    }

    private fun copySamples(source: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buffer = ByteBuffer.allocateDirect(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = source.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            info.set(0, size, source.sampleTime, source.sampleFlags)
            muxer.writeSampleData(track, buffer, info)
            source.advance()
        }
    }

    /** Same bounded AAC/MP4 fixture helper as VideoAudioDecoderTest; that test is not modified. */
    private fun encodeAudio(file: File, durationMs: Int) {
        val sampleRate = 44_100
        val channels = 2
        val totalFrames = sampleRate.toLong() * durationMs / 1_000L
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var track = -1
        val info = MediaCodec.BufferInfo()
        val deadline = System.currentTimeMillis() + 60_000L
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            fun drain(ending: Boolean): Boolean {
                while (true) {
                    val output = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            started = true
                        }
                        output >= 0 -> {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                check(started) { "AAC encoder emitted audio before its output format" }
                                val buffer = requireNotNull(codec.getOutputBuffer(output))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buffer, info)
                            }
                            val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(output, false)
                            if (eos) return true
                        }
                        output == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!ending) return false
                    }
                    check(System.currentTimeMillis() <= deadline) { "AAC encoder timed out" }
                }
            }

            var fedFrames = 0L
            while (fedFrames < totalFrames) {
                val input = codec.dequeueInputBuffer(10_000L)
                if (input < 0) {
                    drain(false)
                    continue
                }
                val buffer = requireNotNull(codec.getInputBuffer(input))
                buffer.clear()
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val frames = minOf(buffer.capacity() / (channels * 2), (totalFrames - fedFrames).toInt())
                for (offset in 0 until frames) {
                    val frame = fedFrames + offset
                    val pcm = (sin(2.0 * PI * 440.0 * frame / sampleRate) * Short.MAX_VALUE * 0.6).toInt().toShort()
                    repeat(channels) { buffer.putShort(pcm) }
                }
                codec.queueInputBuffer(input, 0, frames * channels * 2, fedFrames * 1_000_000L / sampleRate, 0)
                fedFrames += frames
                drain(false)
            }

            var input = codec.dequeueInputBuffer(10_000L)
            while (input < 0) {
                drain(false)
                input = codec.dequeueInputBuffer(10_000L)
            }
            codec.queueInputBuffer(input, 0, 0, totalFrames * 1_000_000L / sampleRate,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            check(drain(true)) { "AAC encoder did not report end of stream" }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        check(file.length() > 0) { "AAC/MP4 fixture was not written" }
    }
}
