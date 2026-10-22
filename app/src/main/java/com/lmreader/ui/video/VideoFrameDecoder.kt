package com.lmreader.ui.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.media.*
import android.net.Uri
import com.lmreader.core.model.ResourceGovernor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** A shared pixel lease; the pipeline admits inputs through the common preprocessing budget. */
class DecodedVideoFrame internal constructor(val bitmap: Bitmap, val ptsUs: Long, private val pixels: ResourceGovernor.Lease? = null) {
    private val references = AtomicInteger(1)
    internal fun retain(): DecodedVideoFrame { check(references.incrementAndGet() > 1); return this }
    fun close() { if(references.decrementAndGet() == 0) { bitmap.recycle(); pixels?.close() } }
}

/** Sequential MediaCodec decoding in media time; playback speed/seek never drives this decoder. */
interface VideoFrameSource {
    suspend fun frameAt(targetUs: Long): DecodedVideoFrame?
    suspend fun close()
}
class VideoFrameDecoder(private val context: Context, private val uri: String, private val maxDimension: Int = 1280) : VideoFrameSource {
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var inputEnded = false
    private var outputEnded = false
    private var rotation = 0
    private var colorStandard = MediaFormat.COLOR_STANDARD_BT601_NTSC
    private var fullRange = false
    private var cached: DecodedVideoFrame? = null
    private var lastRequested = -1L
    private var pendingIndex = -1
    private var pendingPts = 0L
    private val gate = Mutex()
    var colorSamplingReliable: Boolean = true
        private set
    init { require(maxDimension in 64..2048) }
    private fun open(startUs: Long) {
        val source = MediaExtractor()
        try {
            source.setDataSource(context, Uri.parse(uri), null)
            val track = (0 until source.trackCount).firstOrNull { source.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                ?: error("容器中没有可解码视频轨")
            source.selectTrack(track)
            val format = source.getTrackFormat(track)
            val transfer = if(format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else MediaFormat.COLOR_TRANSFER_SDR_VIDEO
            colorSamplingReliable = transfer !in setOf(MediaFormat.COLOR_TRANSFER_ST2084, MediaFormat.COLOR_TRANSFER_HLG)
            updateColorFormat(format)
            if(!colorSamplingReliable && android.os.Build.VERSION.SDK_INT >= 31) format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            rotation = if(format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
            // Rotation is applied to the analysis bitmap, exactly once.
            format.setInteger(MediaFormat.KEY_ROTATION, 0)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            if(startUs > 0) source.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try { decoder.configure(format, null, null, 0); decoder.start() }
            catch(e: Throwable) { decoder.release(); throw e }
            extractor = source; codec = decoder
        } catch(e: Throwable) { source.release(); throw e }
    }
    override suspend fun frameAt(targetUs: Long): DecodedVideoFrame? = gate.withLock { frameAtUnlocked(targetUs) }
    private suspend fun frameAtUnlocked(targetUs: Long): DecodedVideoFrame? = withContext(Dispatchers.IO) {
        require(targetUs >= 0)
        ResourceGovernor.shared.use("decode") {
            if(targetUs < lastRequested) reset()
            lastRequested = targetUs
            cached?.takeIf { it.ptsUs >= targetUs }?.let { return@use it.retain() }
            if(outputEnded) return@use cached?.retain()
            if(codec == null) open(targetUs)
            val decoder = requireNotNull(codec); val source = requireNotNull(extractor)
            val info = MediaCodec.BufferInfo()
            var idle = 0
            while(true) {
                currentCoroutineContext().ensureActive()
                if(!inputEnded) {
                    val input = decoder.dequeueInputBuffer(10_000)
                    if(input >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(input)); val count = source.readSampleData(buffer, 0)
                        if(count < 0) { decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(input, 0, count, source.sampleTime, source.sampleFlags); source.advance() }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 10_000)
                if(output >= 0) {
                    idle = 0
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if(info.size > 0) {
                        if(pendingIndex >= 0) decoder.releaseOutputBuffer(pendingIndex, false)
                        pendingIndex = output; pendingPts = info.presentationTimeUs
                        if(pendingPts >= targetUs || eos) {
                            val frame = convertPending(decoder)
                            outputEnded = eos
                            return@use frame.retain()
                        }
                    } else decoder.releaseOutputBuffer(output, false)
                    if(eos) {
                        outputEnded = true
                        if(pendingIndex >= 0) return@use convertPending(decoder).retain()
                        return@use cached?.retain()
                    }
                } else if(output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    updateColorFormat(decoder.outputFormat)
                } else if(output == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    check(++idle < 1500) { "视频解码长时间没有输出，请重试或更换文件" }
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }
    }
    private suspend fun convertPending(decoder: MediaCodec): DecodedVideoFrame {
        val index = pendingIndex; pendingIndex = -1
        try {
            val image = decoder.getOutputImage(index) ?: error("此解码器不提供可读取画面，请更换兼容编码")
            cached?.close()
            cached = null
            val crop = image.cropRect
            val scaledPixels = (crop.width().toDouble() * crop.height() * minOf(1.0,
                maxDimension.toDouble() / maxOf(crop.width(), crop.height())).let { it * it }).toLong().coerceIn(4, 4_000_000)
            // Covers the retained bitmap plus conversion/rotation scratch; closing the old
            // decoder reference before admission avoids retaining a cache while waiting for itself.
            val lease = try { ResourceGovernor.shared.acquire(mapOf("pixels" to scaledPixels * 3)) }
                catch(e: Throwable) { image.close(); throw e }
            try {
                val bitmap = image.use { toBitmap(it) }
                return DecodedVideoFrame(bitmap, pendingPts, lease).also { cached = it }
            } catch(e: Throwable) { lease.close(); throw e }
        } finally { decoder.releaseOutputBuffer(index, false) }
    }
    private fun toBitmap(image: Image): Bitmap {
        require(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3) { "不支持的离屏解码色彩格式" }
        val crop = image.cropRect
        val scale = minOf(1.0, maxDimension.toDouble() / maxOf(crop.width(), crop.height()), kotlin.math.sqrt(4_000_000.0 / (crop.width().toDouble() * crop.height())))
        val width = (crop.width() * scale).toInt().coerceAtLeast(2)
        val height = (crop.height() * scale).toInt().coerceAtLeast(2)
        val pixels = IntArray(width * height)
        val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
        val coefficients = when(colorStandard) {
            MediaFormat.COLOR_STANDARD_BT709 -> if(fullRange) intArrayOf(1613, 192, 479, 1900) else intArrayOf(1836, 218, 546, 2163)
            MediaFormat.COLOR_STANDARD_BT2020 -> if(fullRange) intArrayOf(1510, 169, 585, 1927) else intArrayOf(1719, 192, 665, 2193)
            else -> if(fullRange) intArrayOf(1436, 352, 731, 1815) else intArrayOf(1635, 401, 833, 2066)
        }
        fun sample(plane: Image.Plane, x: Int, y: Int): Int = plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
        for(y in 0 until height) {
            val sy = crop.top + y * crop.height() / height
            for(x in 0 until width) {
                val sx = crop.left + x * crop.width() / width
                val rawY = sample(yPlane, sx, sy)
                val yy = if(fullRange) rawY * 1024 else (rawY - 16).coerceAtLeast(0) * 1192
                val uu = sample(uPlane, sx / 2, sy / 2) - 128; val vv = sample(vPlane, sx / 2, sy / 2) - 128
                val r = ((yy + coefficients[0] * vv + 512) shr 10).coerceIn(0, 255)
                val g = ((yy - coefficients[1] * uu - coefficients[2] * vv + 512) shr 10).coerceIn(0, 255)
                val b = ((yy + coefficients[3] * uu + 512) shr 10).coerceIn(0, 255)
                pixels[y * width + x] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        if(rotation % 360 == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, width, height, Matrix().apply { postRotate(rotation.toFloat()) }, true).also { bitmap.recycle() }
    }
    private fun updateColorFormat(format: MediaFormat) {
        if(format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) colorStandard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
        else colorSamplingReliable = false
        if(format.containsKey(MediaFormat.KEY_COLOR_RANGE)) fullRange = format.getInteger(MediaFormat.KEY_COLOR_RANGE) == MediaFormat.COLOR_RANGE_FULL
    }
    private fun reset() {
        val decoder = codec; codec = null
        if(decoder != null) {
            runCatching { if(pendingIndex >= 0) decoder.releaseOutputBuffer(pendingIndex, false); decoder.stop() }
            runCatching { decoder.release() }
        }
        pendingIndex = -1; extractor?.release(); extractor = null
        cached?.close(); cached = null; inputEnded = false; outputEnded = false; lastRequested = -1
    }
    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) { gate.withLock { reset() } }
}
