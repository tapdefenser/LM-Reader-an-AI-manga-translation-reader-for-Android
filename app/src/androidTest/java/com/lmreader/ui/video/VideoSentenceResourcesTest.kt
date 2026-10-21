package com.lmreader.ui.video

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.core.model.VideoSubtitleCue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Real generated media, real decoders and the public sentence-resource API. No model or network. */
@RunWith(AndroidJUnit4::class)
class VideoSentenceResourcesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var video: File
    private lateinit var audio: File

    @Before fun setup() = runBlocking<Unit> {
        root = File(context.cacheDir, "video-sentence-${UUID.randomUUID()}").apply { mkdirs() }
        video = File(root, "changing.mp4")
        audio = File(root, "tone.mp4")
        withContext(Dispatchers.IO) {
            VideoFixture.create(video)
            encodeAudioFixture(audio, 3_000)
        }
    }

    @After fun cleanup() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun imageIsTheMidpointFrameAndReleasesDecoderAndPixelLeases() = runBlocking<Unit> {
        val cue = VideoSubtitleCue("midpoint", 2_000, 4_000, "source")
        val baseline = usage()
        val uri = Uri.fromFile(video).toString()
        val resource = VideoSentenceResources(context, uri, -1)
        val apiImage = resource.image(cue)
        val actual = requireNotNull(BitmapFactory.decodeByteArray(
            Base64.decode(apiImage.base64, Base64.DEFAULT), 0, Base64.decode(apiImage.base64, Base64.DEFAULT).size))
        val decoder = VideoFrameDecoder(context, uri)
        try {
            val expected = requireNotNull(decoder.frameAt(VideoSentenceResources.midpointMs(cue) * 1_000))
            try {
                assertEquals("image must use the source resolution", expected.bitmap.width, actual.width)
                assertEquals("image must use the source resolution", expected.bitmap.height, actual.height)
                for ((x, y) in listOf(5 to 5, 50 to 40, 25 to 40, 100 to 10, 150 to 100)) {
                    val a = actual.getPixel(x, y)
                    val e = expected.bitmap.getPixel(x, y)
                    val delta = maxOf(
                        abs((a shr 16 and 255) - (e shr 16 and 255)),
                        abs((a shr 8 and 255) - (e shr 8 and 255)),
                        abs((a and 255) - (e and 255)),
                    )
                    assertTrue("midpoint pixel ($x,$y) differs by $delta", delta <= 48)
                }
                val ink = actual.getPixel(50, 40)
                val background = actual.getPixel(5, 5)
                assertTrue("the decoded image must contain the generated strokes",
                    abs((ink and 255) - (background and 255)) > 80)
            } finally {
                expected.close()
            }
        } finally {
            decoder.close()
            actual.recycle()
        }
        assertEquals("image leaked decoder/pixel leases", baseline, usage())
    }

    @Test fun audioIsRebuiltFromTheRequestedBoundedRangeAs16kMonoPcmWav() = runBlocking<Unit> {
        val cue = VideoSubtitleCue("bounded", 1_200, 1_800, "source")
        val baseline = usage()
        val apiAudio = VideoSentenceResources(context, Uri.fromFile(audio).toString(), 0).audio(cue)
        val wav = Base64.decode(apiAudio.base64, Base64.DEFAULT)
        assertEquals("audio/wav", apiAudio.mimeType)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))
        val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, buffer.getShort(20).toInt())
        assertEquals(1, buffer.getShort(22).toInt())
        assertEquals(16_000, buffer.getInt(24))
        assertEquals(32_000, buffer.getInt(28))
        assertEquals(2, buffer.getShort(32).toInt())
        assertEquals(16, buffer.getShort(34).toInt())
        val dataSize = buffer.getInt(40)
        assertEquals("header plus data must cover the whole WAV", 44 + dataSize, wav.size)
        val durationMs = dataSize / 2L * 1_000L / 16_000L
        assertTrue("bounded duration ${durationMs}ms", abs(durationMs - 600L) <= 160L)
        var peak = 0
        var offset = 44
        while (offset + 1 < wav.size) {
            peak = maxOf(peak, abs(buffer.getShort(offset).toInt()))
            offset += 2
        }
        assertTrue("the sentence audio must not be silent", peak > 1_000)
        assertEquals("audio leaked decoder leases", baseline, usage())
    }

    @Test fun cueLongerThanTwoMinutesIsRejectedBeforeDecoding() = runBlocking<Unit> {
        val baseline = usage()
        val cue = VideoSubtitleCue("too-long", 0, 120_001, "source")
        val failure = runCatching {
            VideoSentenceResources(context, Uri.fromFile(audio).toString(), 0).audio(cue)
        }.exceptionOrNull()
        assertTrue("expected IllegalArgumentException, got $failure", failure is IllegalArgumentException)
        assertEquals("rejected cue must not touch the decoder", baseline, usage())
    }

    private fun usage(): Pair<Long, Long> = ResourceGovernor.shared.usage.value.let { usage ->
        usage.sumOf { it.resources["decode"] ?: 0L } to usage.sumOf { it.resources["pixels"] ?: 0L }
    }

    /** Same bounded AAC/MP4 fixture helper as VideoAudioDecoderTest; that test is not modified. */
    private fun encodeAudioFixture(file: File, durationMs: Int) {
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
                val frames = minOf(
                    buffer.capacity() / (channels * 2),
                    (totalFrames - fedFrames).toInt(),
                )
                for (offset in 0 until frames) {
                    val frame = fedFrames + offset
                    val pcm = (sin(2.0 * PI * 440.0 * frame / sampleRate) * Short.MAX_VALUE * 0.6)
                        .toInt().toShort()
                    repeat(channels) { buffer.putShort(pcm) }
                }
                codec.queueInputBuffer(
                    input, 0, frames * channels * 2, fedFrames * 1_000_000L / sampleRate, 0,
                )
                fedFrames += frames
                drain(false)
            }

            var input = codec.dequeueInputBuffer(10_000L)
            while (input < 0) {
                drain(false)
                input = codec.dequeueInputBuffer(10_000L)
            }
            codec.queueInputBuffer(
                input, 0, 0, totalFrames * 1_000_000L / sampleRate,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
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
