package com.lmreader.core.model

enum class ScreenTranslationMode { MANUAL, BROWSE, VIDEO }
enum class ScreenTrackingAlgorithm { GLOBAL, GLOBAL_AND_REGIONS }

data class ScreenTranslationSettings(
    val mode: ScreenTranslationMode = ScreenTranslationMode.MANUAL,
    val translation: MangaTranslationSettings = MangaTranslationSettings(segTextScope = SegTextScope.FREE_TEXT),
    val vision: VisionExecutionSettings = VisionExecutionSettings(),
    val cacheMegabytes: Int = 128,
    val preferAccessibility: Boolean = false,
    val sampleIntervalMillis: Int = 250,
    val settleMillis: Int = 180,
    val manualClearMillis: Int = 120,
    val offscreenMarginPercent: Int = 25,
    val maximumCaptureEdge: Int = 1600,
    val overlapPixels: Int = 64,
    val videoTopPercent: Int = 0,
    val videoBottomPercent: Int = 100,
    val changeThresholdPercent: Int = 4,
    val followFramesPerSecond: Int = 30,
    val minimumNewAreaPixels: Int = 16,
    val trackingAlgorithm: ScreenTrackingAlgorithm = ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS,
    val browseRegions: ScreenRegionSelection = ScreenRegionSelection(),
    val videoRegions: ScreenRegionSelection = ScreenRegionSelection(),
    val regionPresets: List<ScreenRegionSelection> = emptyList(),
) {
    init {
        require(cacheMegabytes in 32..1024 && sampleIntervalMillis in 100..2000)
        require(settleMillis in 0..2000 && manualClearMillis in 0..1000)
        require(offscreenMarginPercent in 0..200 && maximumCaptureEdge in 480..2560 && overlapPixels in 0..256)
        require(videoTopPercent in 0..95 && videoBottomPercent in 5..100 && videoTopPercent < videoBottomPercent)
        require(changeThresholdPercent in 1..30)
        require(followFramesPerSecond in 5..60)
        require(minimumNewAreaPixels in 2..128)
    }
    val requiresAccessibility get() = mode != ScreenTranslationMode.MANUAL
    val usesAccessibility get() = requiresAccessibility || preferAccessibility
}

/** Two admitted frames, including preprocessing and all downstream workflow work. */
class ScreenChapterWindow {
    class Chapter internal constructor(val number: Long, val epoch: Long, val capturedAt: ScreenTransform, val areas: List<PixelRect>) {
        private val consumers = java.util.concurrent.atomic.AtomicInteger()
        val consumingResource get() = consumers.get() > 0
        fun consumerStarted() { consumers.incrementAndGet() }
        fun consumerFinished() { check(consumers.decrementAndGet() >= 0) }
        @Volatile var visionReady = false
            private set
        fun preprocessingFinished() { visionReady = true }
        val contentAreas = areas.map(capturedAt.inverse()::bounds)
    }
    private var nextNumber = 0L
    private val entries = linkedMapOf<Long, Chapter>()
    val chapters get() = entries.values.toList()
    val size get() = entries.size
    fun canAdmit(cacheAvailable: Boolean): Boolean = cacheAvailable &&
        (entries.isEmpty() || entries.size == 1 && entries.values.first().visionReady)
    fun admit(epoch: Long, capturedAt: ScreenTransform, areas: List<PixelRect>): Chapter {
        check(entries.size < 2); require(areas.isNotEmpty())
        return Chapter(++nextNumber, epoch, capturedAt, areas).also { entries[it.number] = it }
    }
    fun complete(chapter: Chapter) { entries.remove(chapter.number) }
    fun clear() { entries.clear() }
    fun reserved(epoch: Long, atCapture: ScreenTransform): List<PixelRect> = entries.values.filter { it.epoch == epoch }
        .flatMap { it.contentAreas.map(atCapture::bounds) }
}

/** Clock supplied by the caller: notifications never move a capture deadline earlier. */
class ScreenFrameCadence(private val intervalMillis: Long) {
    init { require(intervalMillis > 0) }
    private var submittedAt: Long? = null
    private var probedAt: Long? = null
    fun waitMillis(now: Long): Long = maxOf(submittedAt?.plus(intervalMillis) ?: now,
        probedAt?.plus(intervalMillis) ?: now).minus(now).coerceAtLeast(0)
    fun probed(now: Long) { probedAt = now }
    fun submitted(now: Long) { submittedAt = now; probedAt = now }
}

/** Publication is monotonic within a scene; completing old work still releases its own resources. */
class ScreenPublicationOrder {
    private var epoch = Long.MIN_VALUE
    private var last = Long.MIN_VALUE
    fun accept(resultEpoch: Long, sequence: Long, currentEpoch: Long): Boolean {
        if (resultEpoch != currentEpoch) return false
        if (epoch != currentEpoch) { epoch = currentEpoch; last = Long.MIN_VALUE }
        if (sequence < last) return false
        last = sequence
        return true
    }
}

/** Filter the accumulated pose, so successive sub-dead-zone zoom steps are never lost. */
class ScreenMotionSmoother(private val alpha: Float = .4f, private val deadZone: Float = .008f) {
    private var shown = ScreenTransform()
    private var zooming = false
    fun reset(pose: ScreenTransform = ScreenTransform()) { shown = pose; zooming = false }
    fun update(raw: ScreenTransform, center: PixelPoint): ScreenTransform {
        val error = raw.scale / shown.scale - 1f
        if (kotlin.math.abs(error) >= deadZone) zooming = true
        val scale = if (!zooming) shown.scale else if (kotlin.math.abs(error) < .001f) {
            zooming = false; raw.scale
        } else shown.scale + alpha * (raw.scale - shown.scale)
        val contentX = (center.x - raw.x) / raw.scale
        val contentY = (center.y - raw.y) / raw.scale
        return ScreenTransform(scale, center.x - contentX * scale, center.y - contentY * scale).also { shown = it }
    }
}

/** Retain bubbles outside the viewport until they leave the expanded screen completely. */
fun screenRetentionBounds(viewport: PixelRect, marginPercent: Int): PixelRect {
    val x = viewport.width * marginPercent / 100f; val y = viewport.height * marginPercent / 100f
    return PixelRect(viewport.left - x, viewport.top - y, viewport.right + x, viewport.bottom + y)
}

fun PixelRect.intersects(other: PixelRect) = left < other.right && right > other.left && top < other.bottom && bottom > other.top

data class ScreenTransform(val scale: Float = 1f, val x: Float = 0f, val y: Float = 0f) {
    init { require(scale.isFinite() && scale > 0 && x.isFinite() && y.isFinite()) }
    fun point(p: PixelPoint) = PixelPoint(p.x * scale + x, p.y * scale + y)
    fun bounds(r: PixelRect) = PixelRect(r.left * scale + x, r.top * scale + y, r.right * scale + x, r.bottom * scale + y)
    fun then(next: ScreenTransform) = ScreenTransform(scale * next.scale, x * next.scale + next.x, y * next.scale + next.y)
    fun inverse() = ScreenTransform(1 / scale, -x / scale, -y / scale)
}

/** Exact subtraction. In particular, an L-shaped union must not hide its missing corner. */
fun screenExposedRegions(viewport: PixelRect, covered: PixelRect?): List<PixelRect> {
    if (covered == null || !covered.intersects(viewport)) return listOf(viewport)
    val l = maxOf(viewport.left, covered.left); val t = maxOf(viewport.top, covered.top)
    val r = minOf(viewport.right, covered.right); val b = minOf(viewport.bottom, covered.bottom)
    return listOf(PixelRect(viewport.left, viewport.top, viewport.right, t), PixelRect(viewport.left, b, viewport.right, viewport.bottom),
        PixelRect(viewport.left, t, l, b), PixelRect(r, t, viewport.right, b)).filter { it.width > 0 && it.height > 0 }
}

fun screenExposedRegions(viewport: PixelRect, covered: List<PixelRect>): List<PixelRect> {
    var exposed = listOf(viewport)
    for (rectangle in covered) {
        if (exposed.isEmpty()) break
        exposed = exposed.flatMap { screenExposedRegions(it, rectangle) }
    }
    return exposed
}

/** Coverage lives in content coordinates: pan/zoom never rewrites all the rectangles. */
class ScreenCoverage {
    private val rectangles = ArrayList<PixelRect>()
    fun clear() = rectangles.clear()
    fun removeContent(areas: List<PixelRect>) {
        val remaining = rectangles.flatMap { screenExposedRegions(it, areas) }
        rectangles.clear(); rectangles.addAll(remaining)
    }
    fun exposed(viewport: PixelRect, contentToScreen: ScreenTransform): List<PixelRect> =
        screenExposedRegions(contentToScreen.inverse().bounds(viewport), rectangles).map(contentToScreen::bounds)
            .filter { it.width >= 1f && it.height >= 1f } // subpixel strips cannot enter SEG/OCR

    fun add(screenArea: PixelRect, atCapture: ScreenTransform) {
        rectangles += screenExposedRegions(atCapture.inverse().bounds(screenArea), rectangles)
        // Joining rectangles is legal only when their shared edge spans both
        // rectangles completely. Never approximate their union by its bounds.
        var joined = true
        while (joined) {
            joined = false
            outer@ for (i in rectangles.indices) for (j in i + 1 until rectangles.size) {
                val a = rectangles[i]; val b = rectangles[j]
                val horizontal = a.top == b.top && a.bottom == b.bottom && (a.right == b.left || b.right == a.left)
                val vertical = a.left == b.left && a.right == b.right && (a.bottom == b.top || b.bottom == a.top)
                if (horizontal || vertical) {
                    rectangles[i] = PixelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
                    rectangles.removeAt(j); joined = true; break@outer
                }
            }
        }
    }

    /** Called at recognition cadence, not on every following frame. */
    fun retain(screenArea: PixelRect, contentToScreen: ScreenTransform) {
        val retained = contentToScreen.inverse().bounds(screenArea)
        for (i in rectangles.lastIndex downTo 0) {
            val r = rectangles[i]
            if (!r.intersects(retained)) rectangles.removeAt(i)
            else rectangles[i] = PixelRect(maxOf(r.left, retained.left), maxOf(r.top, retained.top), minOf(r.right, retained.right), minOf(r.bottom, retained.bottom))
        }
    }
}
