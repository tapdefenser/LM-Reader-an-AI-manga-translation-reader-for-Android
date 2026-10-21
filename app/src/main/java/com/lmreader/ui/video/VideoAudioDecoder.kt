package com.lmreader.ui.video

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.lmreader.core.model.ResourceGovernor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/** Mono 16 kHz audio for offline ASR, sliced at one-second overlaps. */
data class VideoAudioChunk(val index: Long, val startMs: Long, val samples: FloatArray)

/**
 * Bounded, sequential audio extraction for offline ASR.
 *
 * The decoder never materializes the whole track. It keeps one output chunk, one one-second
 * overlap and at most one copied PCM output buffer. The caller must consume [VideoAudioChunk.samples]
 * during the callback (or copy it before retaining it): a full chunk array is reused after the
 * callback returns so that a decode never needs two whole chunks.
 */
class VideoAudioDecoder(
    context: Context,
    uri: String,
    trackIndex: Int = -1,
    private val startMs: Long = 0,
    private val endMs: Long = Long.MAX_VALUE,
) {
    private val appContext: Context = context.applicationContext ?: context
    private val sourceUri: String = uri
    private val requestedTrackIndex: Int = trackIndex
    private val startUs: Long = msToUs(startMs)
    private val endUs: Long = msToUs(endMs)

    @Volatile private var extractor: MediaExtractor? = null
    @Volatile private var codec: MediaCodec? = null
    private var trackInfo: AudioTrackInfo? = null
    private var outputSpec: OutputSpec? = null
    private var inputSampleRate: Int = 0
    private var inputChannels: Int = 0

    private var inputEnded = false
    private var outputEnded = false
    private var flushing = false
    private var rangeDone = false
    private var runActive = false
    private var runAnchorPtsUs = 0L
    private var pendingBlock: PcmBlock? = null
    private var pendingGap = false

    private val chunk = FloatArray(MAX_CHUNK_SAMPLES)
    private val overlap = FloatArray(OVERLAP_SAMPLES)
    private var chunkSize = 0
    private var chunkStartMs = 0L
    private var hasNewSamples = false
    private var pendingOverlap = false
    private val resampler = StreamingResampler()

    private val stateLock = Any()
    @Volatile private var closed = false
    private var decoding = false

    init {
        require(uri.isNotBlank()) { "视频地址为空" }
        require(trackIndex >= -1) { "音轨索引不能小于 -1" }
        require(startMs >= 0) { "开始时间不能为负" }
        require(endMs > startMs) { "结束时间必须晚于开始时间" }
    }

    /**
     * Decode [startMs], [endMs) sequentially and invoke [onChunk] for every deterministic chunk.
     *
     * The resource governor's `decode` lease is held only while filling a chunk/stage. It is
     * released before [onChunk] runs, so a long ASR call cannot block other media decoding.
     */
    suspend fun decode(onChunk: suspend (VideoAudioChunk) -> Unit) {
        synchronized(stateLock) {
            check(!closed) { "音频解码器已关闭" }
            check(!decoding) { "音频解码器不能重复或并发解码" }
            decoding = true
        }
        try {
            withContext(Dispatchers.IO) {
                var chunkIndex = 0L
                try {
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val stage = ResourceGovernor.shared.use("decode") { fillStage() }
                        when (stage) {
                            StageResult.FINISHED -> break
                            StageResult.FULL -> {
                                System.arraycopy(
                                    chunk, MAX_CHUNK_SAMPLES - OVERLAP_SAMPLES,
                                    overlap, 0, OVERLAP_SAMPLES,
                                )
                                onChunk(VideoAudioChunk(chunkIndex, chunkStartMs, chunk))
                                chunkIndex++
                                chunkSize = 0
                                hasNewSamples = false
                                pendingOverlap = true
                                chunkStartMs += STEP_MS
                            }
                            StageResult.PARTIAL -> {
                                val samples = chunk.copyOf(chunkSize)
                                onChunk(VideoAudioChunk(chunkIndex, chunkStartMs, samples))
                                chunkIndex++
                                chunkSize = 0
                                hasNewSamples = false
                                pendingOverlap = false
                                if (!pendingGap) break
                            }
                        }
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { releaseAll() }
                }
            }
        } finally {
            synchronized(stateLock) {
                decoding = false
                closed = true
            }
        }
    }

    /** Release extractor and codec; safe and idempotent. */
    suspend fun close() {
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(stateLock) { closed = true }
            releaseAll()
        }
    }

    private suspend fun fillStage(): StageResult {
        check(!closed) { "音频解码器已关闭" }
        if (codec == null) open()

        if (pendingGap) {
            pendingGap = false
            val block = pendingBlock
            pendingBlock = null
            discardChunk()
            resampler.reset()
            runActive = false
            if (block != null) {
                startRun(block)
                when (feedBlock(block)) {
                    FeedResult.FULL -> {
                        pendingBlock = block
                        return StageResult.FULL
                    }
                    FeedResult.RANGE -> {
                        rangeDone = true
                        return finishStage()
                    }
                    FeedResult.NEED_INPUT -> if (block.endOfStream) outputEnded = true
                }
            }
        }

        var idle = 0
        val info = MediaCodec.BufferInfo()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (rangeDone) return finishStage()

            if (outputEnded) {
                if (!flushing) flushing = true
                when (resampler.flush { value, produced -> appendOutput(value, outputTimeUs(produced)) }) {
                    FlushResult.FULL -> return StageResult.FULL
                    FlushResult.RANGE -> {
                        rangeDone = true
                        flushing = false
                        return finishStage()
                    }
                    FlushResult.DONE -> {
                        flushing = false
                        return finishStage()
                    }
                }
            }

            val pending = pendingBlock
            if (pending != null) {
                when (feedBlock(pending)) {
                    FeedResult.FULL -> return StageResult.FULL
                    FeedResult.RANGE -> {
                        rangeDone = true
                        return finishStage()
                    }
                    FeedResult.NEED_INPUT -> {
                        pendingBlock = null
                        idle = 0
                        if (pending.endOfStream) outputEnded = true
                    }
                }
                continue
            }

            var progressed = false
            if (!inputEnded) {
                if (queueInput()) progressed = true
                if (inputEnded) progressed = true
            }

            val decoder = requireNotNull(codec)
            val outputIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    progressed = true
                    var endOfStream = false
                    var block: PcmBlock? = null
                    try {
                        endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            block = readPcmBlock(outputIndex, info, endOfStream)
                        }
                    } finally {
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                    if (block != null && block.samples.isNotEmpty()) {
                        when (acceptBlock(block)) {
                            AcceptResult.CONSUMED -> if (endOfStream) outputEnded = true
                            AcceptResult.FULL -> return StageResult.FULL
                            AcceptResult.RANGE -> {
                                rangeDone = true
                                return finishStage()
                            }
                            AcceptResult.GAP -> return StageResult.PARTIAL
                        }
                    } else if (endOfStream) {
                        outputEnded = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    applyOutputFormat(decoder.outputFormat)
                    progressed = true
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> progressed = true
                else -> error("音频解码器返回未知状态：$outputIndex")
            }
            if (progressed) idle = 0
            else if (++idle > MAX_IDLE_POLLS) error("音频解码长时间没有输出，请检查文件或更换解码器")
        }
    }

    private fun acceptBlock(block: PcmBlock): AcceptResult {
        if (rangeDone) return AcceptResult.CONSUMED

        if (runActive) {
            val expected = expectedNextPtsUs()
            val delta = block.presentationTimeUs - expected
            val discontinuous = block.sampleRate != resampler.rate ||
                delta > DISCONTINUITY_US || delta < -DISCONTINUITY_US
            if (discontinuous) {
                if (hasNewSamples) {
                    pendingBlock = block
                    pendingGap = true
                    return AcceptResult.GAP
                }
                // Only overlap (already delivered) or nothing is pending. Never overlap a gap.
                discardChunk()
                resampler.reset()
                runActive = false
            }
        }
        if (!runActive) startRun(block)
        return when (feedBlock(block)) {
            FeedResult.NEED_INPUT -> AcceptResult.CONSUMED
            FeedResult.FULL -> {
                pendingBlock = block
                AcceptResult.FULL
            }
            FeedResult.RANGE -> AcceptResult.RANGE
        }
    }

    private fun startRun(block: PcmBlock) {
        resampler.start(block.sampleRate)
        runAnchorPtsUs = block.presentationTimeUs
        runActive = true
    }

    private fun expectedNextPtsUs(): Long =
        runAnchorPtsUs + samplesToUs(resampler.consumedSamples, resampler.rate)

    private fun feedBlock(block: PcmBlock): FeedResult =
        resampler.feed(block.samples, block.endOfStream) { value, produced ->
            appendOutput(value, outputTimeUs(produced))
        }

    private fun outputTimeUs(produced: Long): Long {
        val whole = produced / TARGET_RATE
        val remainder = produced % TARGET_RATE
        return runAnchorPtsUs + whole * 1_000_000L + remainder * 1_000_000L / TARGET_RATE
    }

    private fun appendOutput(value: Float, tUs: Long): SinkResult {
        if (tUs < startUs) return SinkResult.SKIP
        if (tUs >= endUs) {
            rangeDone = true
            return SinkResult.STOP_RANGE
        }
        if (chunkSize >= MAX_CHUNK_SAMPLES) return SinkResult.STOP_FULL_BEFORE
        val prependedOverlap = pendingOverlap
        if (prependedOverlap) {
            System.arraycopy(overlap, 0, chunk, 0, OVERLAP_SAMPLES)
            chunkSize = OVERLAP_SAMPLES
            pendingOverlap = false
        }
        if (!hasNewSamples) {
            // The overlap already carries the real chunk start; a fresh chunk starts at this PTS.
            if (!prependedOverlap) chunkStartMs = tUs / 1_000L
            hasNewSamples = true
        }
        chunk[chunkSize++] = value
        return if (chunkSize >= MAX_CHUNK_SAMPLES) SinkResult.STOP_FULL_AFTER
        else SinkResult.ACCEPTED
    }

    private fun finishStage(): StageResult {
        if (!hasNewSamples) {
            chunkSize = 0
            pendingOverlap = false
            return StageResult.FINISHED
        }
        return StageResult.PARTIAL
    }

    private fun discardChunk() {
        chunkSize = 0
        chunkStartMs = 0L
        hasNewSamples = false
        pendingOverlap = false
    }

    private fun open() {
        val source = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            source.setDataSource(appContext, Uri.parse(sourceUri), null)
            val selectedTrack = selectTrack(source)
            source.selectTrack(selectedTrack)
            val format = source.getTrackFormat(selectedTrack)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: error("所选轨道缺少音频编码信息")
            inputSampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            inputChannels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            trackInfo = AudioTrackInfo(
                index = selectedTrack,
                mime = mime,
                sampleRate = inputSampleRate,
                channels = inputChannels,
                bitrate = format.intOrNull(MediaFormat.KEY_BIT_RATE) ?: 0,
                durationUs = format.longOrNull(MediaFormat.KEY_DURATION) ?: 0L,
                maxInputSize = format.intOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0,
            )
            // Only a nonzero start needs a sync seek; the selected track never changes.
            if (startMs > 0) source.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            decoder = configureDecoder(source, selectedTrack, mime)
            extractor = source
            codec = decoder
        } catch (throwable: Throwable) {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { source.release() }
            throw throwable
        }
    }

    private fun selectTrack(source: MediaExtractor): Int {
        val count = source.trackCount
        val audioTracks = (0 until count).filter { index ->
            source.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        }
        if (audioTracks.isEmpty()) error("容器中没有音频轨，无法进行离线语音识别")
        if (requestedTrackIndex == -1) return audioTracks.first()
        require(requestedTrackIndex < count) {
            "音轨索引 $requestedTrackIndex 超出范围（共 $count 条轨道）"
        }
        val mime = source.getTrackFormat(requestedTrackIndex).getString(MediaFormat.KEY_MIME)
        require(mime?.startsWith("audio/") == true) {
            "所选轨道不是音频轨（索引 $requestedTrackIndex）"
        }
        return requestedTrackIndex
    }

    private fun configureDecoder(source: MediaExtractor, track: Int, mime: String): MediaCodec {
        var lastFailure: Throwable? = null
        for (encoding in intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val decoder = try {
                MediaCodec.createDecoderByType(mime)
            } catch (throwable: Throwable) {
                throw IllegalStateException("此音频编码没有可用的解码器：${trackDescription(track, mime)}", throwable)
            }
            try {
                val format = source.getTrackFormat(track)
                format.setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
                decoder.configure(format, null, null, 0)
                decoder.start()
                return decoder
            } catch (throwable: Throwable) {
                lastFailure = throwable
                runCatching { decoder.stop() }
                runCatching { decoder.release() }
            }
        }
        throw IllegalStateException(
            "音频解码器无法输出 PCM16/float：${trackDescription(track, mime)}",
            lastFailure,
        )
    }

    private fun trackDescription(track: Int, mime: String): String = trackInfo?.let {
        "track ${it.index} / ${it.mime} / ${it.sampleRate} Hz / ${it.channels} ch"
    } ?: "track $track / $mime"

    private fun applyOutputFormat(format: MediaFormat) {
        val rate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE)?.takeIf { it > 0 } ?: inputSampleRate
        val channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)?.takeIf { it > 0 } ?: inputChannels
        require(rate > 0) { "音频解码器未提供有效的输出采样率" }
        require(channels in 1..32) { "音频解码器输出声道数无效：$channels" }
        val encoding = format.intOrNull(MediaFormat.KEY_PCM_ENCODING)?.takeIf { it != 0 }
            ?: AudioFormat.ENCODING_PCM_16BIT
        ensureSupportedEncoding(encoding)
        outputSpec = OutputSpec(encoding, rate, channels)
    }

    private fun readPcmBlock(
        outputIndex: Int,
        info: MediaCodec.BufferInfo,
        endOfStream: Boolean,
    ): PcmBlock? {
        val decoder = requireNotNull(codec)
        val spec = outputSpec ?: run {
            applyOutputFormat(decoder.outputFormat)
            requireNotNull(outputSpec)
        }
        val buffer = decoder.getOutputBuffer(outputIndex)
            ?: error("音频解码器没有提供输出缓冲区")
        val start = info.offset.toLong()
        val length = info.size.toLong()
        val end = start + length
        require(start >= 0 && length >= 0 && end <= buffer.capacity().toLong()) {
            "音频解码器返回了无效的缓冲区范围"
        }
        if (length == 0L) {
            return PcmBlock(FloatArray(0), info.presentationTimeUs, spec.sampleRate, spec.channels, endOfStream)
        }
        val view = buffer.duplicate()
        view.position(start.toInt())
        view.limit(end.toInt())
        view.order(ByteOrder.LITTLE_ENDIAN)

        val bytesPerSample = when (spec.encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> error("音频解码器输出不支持的 PCM 编码：${encodingLabel(spec.encoding)}")
        }
        val frames = (length / bytesPerSample).toInt() / spec.channels
        if (frames <= 0) {
            return PcmBlock(FloatArray(0), info.presentationTimeUs, spec.sampleRate, spec.channels, endOfStream)
        }
        val mono = FloatArray(frames)
        if (spec.encoding == AudioFormat.ENCODING_PCM_16BIT) {
            val shorts = view.asShortBuffer()
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until spec.channels) {
                    sum += shorts.get() / 32768f
                }
                mono[frame] = (sum / spec.channels.toFloat()).coerceIn(-1f, 1f)
            }
        } else {
            val floats = view.asFloatBuffer()
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until spec.channels) {
                    val value = floats.get()
                    sum += if (value.isFinite()) value else 0f
                }
                mono[frame] = (sum / spec.channels.toFloat()).coerceIn(-1f, 1f)
            }
        }
        return PcmBlock(mono, info.presentationTimeUs, spec.sampleRate, spec.channels, endOfStream)
    }

    private fun queueInput(): Boolean {
        val decoder = requireNotNull(codec)
        val source = requireNotNull(extractor)
        val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return false
        val buffer = decoder.getInputBuffer(inputIndex)
            ?: error("音频解码器没有提供输入缓冲区")
        val size = source.readSampleData(buffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(
                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            inputEnded = true
        } else {
            decoder.queueInputBuffer(
                inputIndex, 0, size, source.sampleTime, source.sampleFlags,
            )
            source.advance()
        }
        return true
    }

    private fun ensureSupportedEncoding(encoding: Int) {
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT -> Unit
            else -> throw UnsupportedOperationException(
                "音频解码器输出不支持的 PCM 编码：${encodingLabel(encoding)}；仅支持 PCM16 或 float",
            )
        }
    }

    private fun releaseAll() {
        val (decoder, source) = synchronized(stateLock) {
            val currentDecoder = codec
            val currentSource = extractor
            codec = null
            extractor = null
            currentDecoder to currentSource
        }
        if (decoder != null) {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
        if (source != null) runCatching { source.release() }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.longOrNull(key: String): Long? =
        if (containsKey(key)) runCatching { getLong(key) }.getOrNull() else null

    private data class AudioTrackInfo(
        val index: Int,
        val mime: String,
        val sampleRate: Int,
        val channels: Int,
        val bitrate: Int,
        val durationUs: Long,
        val maxInputSize: Int,
    )

    private data class OutputSpec(val encoding: Int, val sampleRate: Int, val channels: Int)

    private data class PcmBlock(
        val samples: FloatArray,
        val presentationTimeUs: Long,
        val sampleRate: Int,
        val channels: Int,
        val endOfStream: Boolean,
    )

    private enum class StageResult { FULL, PARTIAL, FINISHED }
    private enum class FeedResult { NEED_INPUT, FULL, RANGE }
    private enum class FlushResult { DONE, FULL, RANGE }
    private enum class AcceptResult { CONSUMED, FULL, RANGE, GAP }
    private enum class SinkResult {
        ACCEPTED,
        SKIP,
        STOP_RANGE,
        STOP_FULL_BEFORE,
        STOP_FULL_AFTER,
    }

    /**
     * Streaming rational resampler. The position is an integer in units of 1/16000 of one source
     * sample, so the phase survives PCM buffer boundaries without floating-point accumulation.
     */
    private class StreamingResampler {
        var rate: Int = 0
            private set

        private var next = 0L
        private var base = 0L
        private var prior: Float? = null
        private var hasData = false
        var produced: Long = 0L
            private set

        val consumedSamples: Long get() = base

        fun start(sampleRate: Int) {
            rate = sampleRate
            reset()
        }

        fun reset() {
            next = 0L
            base = 0L
            prior = null
            hasData = false
            produced = 0L
        }

        fun feed(
            samples: FloatArray,
            endOfStream: Boolean,
            sink: (Float, Long) -> SinkResult,
        ): FeedResult {
            val size = samples.size
            if (size == 0) return FeedResult.NEED_INPUT
            var position = next / PHASE_SCALE
            var remainder = next % PHASE_SCALE
            while (position <= base + size - 1) {
                val left: Float
                var right: Float? = null
                if (position < base) {
                    check(position == base - 1) { "音频重采样相位丢失" }
                    left = prior ?: error("音频重采样缺少前一个样本")
                    right = samples[0]
                } else {
                    val index = (position - base).toInt()
                    left = samples[index]
                    if (index + 1 < size) right = samples[index + 1]
                }
                val atLast = position == base + size - 1
                if (atLast && right == null && remainder != 0L && !endOfStream) {
                    consume(samples)
                    return FeedResult.NEED_INPUT
                }
                val value = if (remainder == 0L || right == null) left
                else left + (right - left) * (remainder.toFloat() / PHASE_SCALE.toFloat())

                when (val result = sink(value, produced)) {
                    SinkResult.STOP_RANGE -> return FeedResult.RANGE
                    SinkResult.STOP_FULL_BEFORE -> return FeedResult.FULL
                    SinkResult.STOP_FULL_AFTER -> {
                        produced++
                        next += rate
                        return FeedResult.FULL
                    }
                    SinkResult.ACCEPTED, SinkResult.SKIP -> produced++
                }
                next += rate
                position = next / PHASE_SCALE
                remainder = next % PHASE_SCALE
            }
            consume(samples)
            return FeedResult.NEED_INPUT
        }

        fun flush(sink: (Float, Long) -> SinkResult): FlushResult {
            if (!hasData) return FlushResult.DONE
            var position = next / PHASE_SCALE
            while (position <= base - 1) {
                val value = prior ?: return FlushResult.DONE
                when (val result = sink(value, produced)) {
                    SinkResult.STOP_RANGE -> return FlushResult.RANGE
                    SinkResult.STOP_FULL_BEFORE -> return FlushResult.FULL
                    SinkResult.STOP_FULL_AFTER -> {
                        produced++
                        next += rate
                        return FlushResult.FULL
                    }
                    SinkResult.ACCEPTED, SinkResult.SKIP -> produced++
                }
                next += rate
                position = next / PHASE_SCALE
            }
            return FlushResult.DONE
        }

        private fun consume(samples: FloatArray) {
            base += samples.size
            prior = samples[samples.size - 1]
            hasData = true
        }
    }

    private companion object {
        const val TARGET_RATE = 16_000
        const val PHASE_SCALE = 16_000L
        const val MAX_CHUNK_SAMPLES = 480_000
        const val OVERLAP_SAMPLES = 16_000
        const val STEP_MS = 29_000L
        const val DISCONTINUITY_US = 10_000L
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 2_000

        fun msToUs(ms: Long): Long =
            if (ms >= Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else ms * 1_000L

        fun samplesToUs(samples: Long, rate: Int): Long {
            val whole = samples / rate
            val remainder = samples % rate
            return whole * 1_000_000L + remainder * 1_000_000L / rate
        }

        fun encodingLabel(encoding: Int): String = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
            AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
            AudioFormat.ENCODING_INVALID -> "invalid"
            else -> "encoding $encoding"
        }
    }
}
