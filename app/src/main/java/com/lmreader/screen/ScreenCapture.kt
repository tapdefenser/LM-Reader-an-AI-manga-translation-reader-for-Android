package com.lmreader.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.view.WindowManager
import com.lmreader.core.model.PixelRect
import com.lmreader.core.model.ScreenGrayFrame
import com.lmreader.core.vision.GpuMotionSampler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

private fun trackingMask(width: Int, height: Int, area: PixelRect, excluded: List<PixelRect>): ByteArray? =
    excluded.takeIf { it.isNotEmpty() }?.let { rectangles -> ByteArray(width * height) { 1 }.also { mask ->
        rectangles.forEach { r ->
            val l = kotlin.math.floor((r.left - area.left) * width / area.width).toInt().coerceIn(0, width)
            val t = kotlin.math.floor((r.top - area.top) * height / area.height).toInt().coerceIn(0, height)
            val right = kotlin.math.ceil((r.right - area.left) * width / area.width).toInt().coerceIn(l, width)
            val bottom = kotlin.math.ceil((r.bottom - area.top) * height / area.height).toInt().coerceIn(t, height)
            for (y in t until bottom) java.util.Arrays.fill(mask, y * width + l, y * width + right, 0.toByte())
        }
    } }

internal data class CapturedMotion(val gray: ScreenGrayFrame, val bounds: PixelRect, val sequence: Long = 0, val capturedAt: Long = SystemClock.uptimeMillis()) {
    fun restricted(areas: List<PixelRect>): CapturedMotion {
        val mask = ByteArray(gray.pixels.size)
        val previous = gray.valid
        for (area in areas) {
            val l = kotlin.math.ceil((area.left - bounds.left) * gray.width / bounds.width).toInt().coerceIn(0, gray.width)
            val t = kotlin.math.ceil((area.top - bounds.top) * gray.height / bounds.height).toInt().coerceIn(0, gray.height)
            val r = kotlin.math.floor((area.right - bounds.left) * gray.width / bounds.width).toInt().coerceIn(l, gray.width)
            val b = kotlin.math.floor((area.bottom - bounds.top) * gray.height / bounds.height).toInt().coerceIn(t, gray.height)
            for (y in t until b) for (x in l until r) {
                val i = y * gray.width + x
                if (previous == null || previous[i] != 0.toByte()) mask[i] = 1
            }
        }
        return copy(gray = gray.copy(valid = mask))
    }
    fun excluding(rectangles: List<PixelRect>): CapturedMotion {
        val mask = trackingMask(gray.width, gray.height, bounds, rectangles) ?: return this
        gray.valid?.let { previous -> for (i in mask.indices) if (previous[i] == 0.toByte()) mask[i] = 0 }
        return copy(gray = gray.copy(valid = mask))
    }
}

/** Blank pixels outside the union before SEG/OCR; do not change the captured
 * original or create artificial edges in the motion sampler. */
internal fun maskedScreenCrop(image: Bitmap, crop: PixelRect, areas: List<PixelRect>): Bitmap {
    if (com.lmreader.core.model.screenExposedRegions(crop, areas).isEmpty()) return image
    val masked = image.copy(Bitmap.Config.ARGB_8888, true)!!
    val canvas = android.graphics.Canvas(masked)
    val outside = android.graphics.Path().apply {
        fillType = android.graphics.Path.FillType.EVEN_ODD
        addRect(0f, 0f, image.width.toFloat(), image.height.toFloat(), android.graphics.Path.Direction.CW)
        for (area in areas) {
            val l = ((maxOf(crop.left, area.left) - crop.left) * image.width / crop.width).coerceIn(0f, image.width.toFloat())
            val t = ((maxOf(crop.top, area.top) - crop.top) * image.height / crop.height).coerceIn(0f, image.height.toFloat())
            val r = ((minOf(crop.right, area.right) - crop.left) * image.width / crop.width).coerceIn(l, image.width.toFloat())
            val b = ((minOf(crop.bottom, area.bottom) - crop.top) * image.height / crop.height).coerceIn(t, image.height.toFloat())
            if (r > l && b > t) addRect(l, t, r, b, android.graphics.Path.Direction.CW)
        }
    }
    canvas.drawPath(outside, android.graphics.Paint().apply { color = android.graphics.Color.WHITE })
    return masked
}

data class CapturedScreen(val bitmap: Bitmap, val bounds: PixelRect, val windowId: Int = -1,
    val sequence: Long = 0, val capturedAt: Long = SystemClock.uptimeMillis(), private val release: (() -> Unit)? = null) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) { if (release != null) release.invoke() else if (!bitmap.isRecycled) bitmap.recycle() } }
    fun limited(edge: Int): CapturedScreen {
        val scale = minOf(1f, edge.toFloat() / maxOf(bitmap.width, bitmap.height))
        if (scale == 1f) return this
        val image = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(2), (bitmap.height * scale).roundToInt().coerceAtLeast(2), true)
        close(); return CapturedScreen(image, bounds, windowId, sequence, capturedAt)
    }
    fun gray(area: PixelRect = bounds, excluded: List<PixelRect> = emptyList()): ScreenGrayFrame {
        val left = ((area.left - bounds.left) * bitmap.width / bounds.width).toInt().coerceIn(0, bitmap.width - 1)
        val top = ((area.top - bounds.top) * bitmap.height / bounds.height).toInt().coerceIn(0, bitmap.height - 1)
        val right = ((area.right - bounds.left) * bitmap.width / bounds.width).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ((area.bottom - bounds.top) * bitmap.height / bounds.height).toInt().coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val width = 192; val height = (width * crop.height / crop.width).coerceIn(32, 384)
        var reduced = crop
        while (reduced.width > width * 2 && reduced.height > height * 2) {
            val next = Bitmap.createScaledBitmap(reduced, (reduced.width + 1) / 2, (reduced.height + 1) / 2, true)
            if (reduced !== crop && reduced !== bitmap) reduced.recycle()
            reduced = next
        }
        val small = Bitmap.createScaledBitmap(reduced, width, height, true)
        try {
            val colors = IntArray(width * height); small.getPixels(colors, 0, width, 0, 0, width, height)
            val valid = trackingMask(width, height, area, excluded)
            return ScreenGrayFrame(width, height, ByteArray(colors.size) { i ->
                val c = colors[i]; (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte()
            }, valid)
        } finally { if (small !== crop && small !== bitmap) small.recycle(); if (reduced !== small && reduced !== crop && reduced !== bitmap) reduced.recycle(); if (crop !== bitmap) crop.recycle() }
    }
}

/** One consent token creates exactly one virtual display; rotation resizes that display. */
internal class ProjectionScreenCapture(private val context: Context, private val projection: MediaProjection,
    private val edge: Int, private val stopped: () -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val gpuSampler = GpuMotionSampler()
    val motionBackend get() = gpuSampler.backend
    private class ImageSurface(val reader: ImageReader, val bounds: PixelRect) {
        val grayWidth = 192
        val grayHeight = (grayWidth * reader.height / reader.width).coerceIn(32, 384)
        // The sampling grid changes only on display resize, not on each frame.
        val sampleX = Array(grayWidth) { x -> IntArray(4) { n -> ((x + (n + .5f) / 4) * reader.width / grayWidth).toInt().coerceIn(0, reader.width - 1) } }
        val sampleY = Array(grayHeight) { y -> IntArray(4) { n -> ((y + (n + .5f) / 4) * reader.height / grayHeight).toInt().coerceIn(0, reader.height - 1) } }
        val users = java.util.concurrent.atomic.AtomicInteger()
        private val retired = java.util.concurrent.atomic.AtomicBoolean()
        private val closed = java.util.concurrent.atomic.AtomicBoolean()
        private fun closeIfIdle() { if (retired.get() && users.get() == 0 && closed.compareAndSet(false, true)) reader.close() }
        fun release() { users.decrementAndGet(); closeIfIdle() }
        fun retire() { reader.setOnImageAvailableListener(null, null); retired.set(true); closeIfIdle() }
    }
    private class ProjectedImage(val image: Image, val surface: ImageSurface, val sequence: Long,
        val capturedAt: Long = SystemClock.uptimeMillis()) : AutoCloseable {
        private val references = java.util.concurrent.atomic.AtomicInteger(1)
        @Volatile var motionGray: ScreenGrayFrame? = null
        fun retain(): ProjectedImage { references.incrementAndGet(); return this }
        override fun close() { if (references.decrementAndGet() == 0) try { image.close() } finally { surface.release() } }
    }
    private val frameLock = Any()
    private var latestImage: ProjectedImage? = null
    private val frameSequence = MutableStateFlow(0L)
    private var surface: ImageSurface? = null
    private var display: VirtualDisplay? = null
    private var width = 0; private var height = 0
    @Volatile private var closed = false
    var onContentChanged: (() -> Unit)? = null
    var contentVisible = true
        private set
    val sequence get() = frameSequence.value
    val bounds get() = PixelRect(0f, 0f, width.toFloat(), height.toFloat())
    private val callback = object : MediaProjection.Callback() {
        override fun onStop() { close(); stopped() }
        override fun onCapturedContentResize(width: Int, height: Int) { if (!closed) { resize(width, height); onContentChanged?.invoke() } }
        override fun onCapturedContentVisibilityChanged(isVisible: Boolean) {
            if (contentVisible != isVisible) { contentVisible = isVisible; onContentChanged?.invoke() }
        }
    }
    init {
        projection.registerCallback(callback, handler)
        val wm = context.getSystemService(WindowManager::class.java)
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        resize(metrics.widthPixels, metrics.heightPixels)
        val reader = surface!!.reader
        display = projection.createVirtualDisplay("LMReader-screen-translation", reader.width, reader.height,
            metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
    }
    private fun resize(w: Int, h: Int) {
        if (w <= 1 || h <= 1 || w == width && h == height) return
        width = w; height = h
        synchronized(frameLock) { latestImage.also { latestImage = null } }?.close()
        val scale = minOf(1f, edge.toFloat() / maxOf(w, h))
        // Latest owner + recognition + following can retain three images.
        // acquireLatestImage needs two additional slots to discard stale frames.
        val rw = (w * scale).roundToInt().coerceAtLeast(2); val rh = (h * scale).roundToInt().coerceAtLeast(2)
        val next = if (Build.VERSION.SDK_INT >= 29) runCatching {
            ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 5, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        }.getOrElse { ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 5) }
        else ImageReader.newInstance(rw, rh, PixelFormat.RGBA_8888, 5)
        val nextSurface = ImageSurface(next, bounds)
        next.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            nextSurface.users.incrementAndGet()
            val acquired = ProjectedImage(image, nextSurface, frameSequence.value + 1)
            val previous = synchronized(frameLock) {
                if (closed) { acquired.close(); return@setOnImageAvailableListener }
                latestImage.also { latestImage = acquired }
            }
            previous?.close()
            frameSequence.value = acquired.sequence
        }, handler)
        display?.resize(next.width, next.height, context.resources.displayMetrics.densityDpi)
        display?.surface = next.surface
        val previous = surface; surface = nextSurface; previous?.retire()
    }
    private suspend fun latest(after: Long = -1): ProjectedImage {
        return withTimeout(2000) {
            while (true) {
                synchronized(frameLock) {
                    check(!closed) { "屏幕录制已停止" }
                    latestImage?.takeIf { it.sequence > after }?.let { return@withTimeout it.retain() }
                }
                // A resize retires the last image without resetting the monotonic
                // sequence. Wait for an actual new image, not the previous counter.
                frameSequence.first { closed || synchronized(frameLock) { latestImage?.sequence?.let { it > after } == true } }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }
    suspend fun capture(after: Long = -1): CapturedScreen = withContext(Dispatchers.IO) {
        val acquired = latest(after); val image = acquired.image
        try {
            val plane = image.planes[0]; val bufferWidth = plane.rowStride / plane.pixelStride
            val padded = Bitmap.createBitmap(bufferWidth, image.height, Bitmap.Config.ARGB_8888)
            try {
                padded.copyPixelsFromBuffer(plane.buffer.duplicate())
                val actual = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                CapturedScreen(if (actual === padded) actual.copy(Bitmap.Config.ARGB_8888, false)!! else actual,
                    acquired.surface.bounds, sequence = acquired.sequence, capturedAt = acquired.capturedAt)
            } finally { padded.recycle() }
        } finally { acquired.close() }
    }
    /** Only sample a tiny grayscale frame; following never copies a full-screen Bitmap. */
    suspend fun motion(excluded: List<PixelRect>): CapturedMotion = withContext(Dispatchers.Default) {
        val acquired = latest(); val image = acquired.image
        try {
            acquired.motionGray?.let { gray -> return@withContext CapturedMotion(gray, acquired.surface.bounds, acquired.sequence, acquired.capturedAt).excluding(excluded) }
            val sampling = acquired.surface
            val width = sampling.grayWidth; val height = sampling.grayHeight
            fun cpu(): ByteArray {
            val plane = image.planes[0]; val buffer = plane.buffer.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val pixels = ByteArray(width * height)
            fun gray(x: Int, y: Int): Int {
                val index = y * plane.rowStride + x * plane.pixelStride
                val rgba = buffer.getInt(index)
                return ((rgba and 255) * 77 + (rgba ushr 8 and 255) * 150 + (rgba ushr 16 and 255) * 29) shr 8
            }
            // Area samples suppress thin-line aliasing. Single-point bilinear
            // downsampling makes the same artwork change shape during a pan.
            val sampleX = sampling.sampleX; val sampleY = sampling.sampleY
            for (y in 0 until height) {
                for (x in 0 until width) {
                    var sum = 0
                    for (sy in sampleY[y]) for (sx in sampleX[x]) sum += gray(sx, sy)
                    pixels[y * width + x] = (sum / 16).toByte()
                }
            }
            return pixels
            }
            val pixels = gpuSampler.sample(image, width, height, ::cpu)
            val area = acquired.surface.bounds
            val gray = ScreenGrayFrame(width, height, pixels)
            acquired.motionGray = gray
            CapturedMotion(gray, area, acquired.sequence, acquired.capturedAt).excluding(excluded)
        } finally { acquired.close() }
    }
    override fun close() {
        if (closed) return; closed = true
        gpuSampler.close()
        projection.unregisterCallback(callback); display?.release(); display = null
        surface?.retire(); surface = null
        synchronized(frameLock) { latestImage.also { latestImage = null } }?.close()
        frameSequence.value = Long.MAX_VALUE
        projection.stop()
    }
}
