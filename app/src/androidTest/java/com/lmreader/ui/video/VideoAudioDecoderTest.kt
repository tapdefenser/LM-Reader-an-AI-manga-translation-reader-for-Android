package com.lmreader.ui.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.ResourceGovernor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Real audio-only AAC/MP4 fixtures and the public VideoAudioDecoder API. */
@RunWith(AndroidJUnit4::class)
class VideoAudioDecoderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var root: File
    private lateinit var fixture: File

    @Before fun setup() = runBlocking {
        root = File(context.cacheDir, "video-audio-decoder-${UUID.randomUUID()}").apply { mkdirs() }
        fixture = File(root, "sine-35s.mp4")
        withContext(Dispatchers.IO) { encodeFixture(fixture, 35_000) }
    }

    @After fun cleanup() {
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test fun fullDecodeHasBoundedChunksExactOverlapAndExpectedEnd() = runBlocking {
        val chunks = mutableListOf<VideoAudioChunk>()
        val decoder = VideoAudioDecoder(context, Uri.fromFile(fixture).toString())
        try {
            decoder.decode { chunk ->
                // Full-chunk arrays are reused after the callback, so retain a private copy.
                chunks += chunk.copy(samples = chunk.samples.copyOf())
            }
        } finally {
            decoder.close()
        }

        assertTrue("expected at least two chunks, got ${chunks.size}", chunks.size >= 2)
        val first = chunks[0]
        val second = chunks[1]
        assertEquals(0L, first.index)
        assertEquals(0L, first.startMs)
        assertEquals(480_000, first.samples.size)
        assertTrue("chunks must stay bounded", chunks.all { it.samples.size in 1..480_000 })
        assertEquals(1L, second.index)
        assertTrue("second start ${second.startMs}", abs(second.startMs - 29_000L) <= 80L)
        assertTrue("second chunk has overlap plus new audio", second.samples.size > 16_000)
        assertArrayEquals(
            "the copied overlap must be sample-exact",
            first.samples.copyOfRange(464_000, 480_000),
            second.samples.copyOfRange(0, 16_000),
            0f,
        )

        for (chunk in chunks) {
            var energy = 0.0
            for (sample in chunk.samples) {
                assertTrue("non-finite sample in chunk ${chunk.index}", sample.isFinite())
                assertTrue("sample $sample outside [-1, 1]", sample in -1f..1f)
                energy += sample.toDouble() * sample
            }
            assertTrue("chunk ${chunk.index} has no energy", energy > 1.0)
        }

        val last = chunks.last()
        val endMs = last.startMs + last.samples.size.toLong() * 1_000L / 16_000L
        assertTrue("decoded end ${endMs}ms", abs(endMs - 35_000L) <= 100L)
    }

    @Test fun boundedRangeStartsAtRequestedOffsetAndLastsTwoSeconds() = runBlocking {
        val chunks = mutableListOf<VideoAudioChunk>()
        val decoder = VideoAudioDecoder(
            context, Uri.fromFile(fixture).toString(), startMs = 5_000, endMs = 7_000,
        )
        try {
            decoder.decode { chunk -> chunks += chunk.copy(samples = chunk.samples.copyOf()) }
        } finally {
            decoder.close()
        }

        assertTrue("range produced no audio", chunks.isNotEmpty())
        assertTrue("range start ${chunks.first().startMs}", abs(chunks.first().startMs - 5_000L) <= 100L)
        val totalSamples = chunks.sumOf { it.samples.size.toLong() }
        val durationMs = totalSamples * 1_000L / 16_000L
        assertTrue("range duration ${durationMs}ms", abs(durationMs - 2_000L) <= 100L)
    }

    @Test fun cancellationInsideFirstCallbackReleasesPermitAndNativeResources() = runBlocking {
        val baseline = decodeUsage()
        var callbacks = 0
        var cancelled = false
        val decoder = VideoAudioDecoder(context, Uri.fromFile(fixture).toString())
        try {
            decoder.decode {
                callbacks++
                throw CancellationException("cancelled from the first chunk callback")
            }
        } catch (expected: CancellationException) {
            cancelled = true
        } finally {
            decoder.close()
        }
        assertTrue("decode must propagate the callback cancellation", cancelled)
        assertEquals(1, callbacks)
        assertEquals("cancelled decode leaked a decode lease", baseline, decodeUsage())

        var replacementSamples = 0L
        val replacement = VideoAudioDecoder(
            context, Uri.fromFile(fixture).toString(), startMs = 5_000, endMs = 7_000,
        )
        try {
            withTimeout(15_000) {
                replacement.decode { replacementSamples += it.samples.size }
            }
        } finally {
            replacement.close()
        }
        val durationMs = replacementSamples * 1_000L / 16_000L
        assertTrue("replacement duration ${durationMs}ms", abs(durationMs - 2_000L) <= 100L)
        assertEquals("replacement decode leaked a decode lease", baseline, decodeUsage())
    }

    private fun decodeUsage(): Long =
        ResourceGovernor.shared.usage.value.sumOf { it.resources["decode"] ?: 0L }

    /** Audio-only 44.1 kHz stereo AAC-in-MP4, fed as bounded PCM16 buffers with a hard deadline. */
    private fun encodeFixture(file: File, durationMs: Int) {
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
