package com.lmreader.core.model

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** SEG of the next frame overlaps the delay that started at the preceding SEG completion.
 * Capacity is a separate gate: an expired deadline never reserves or captures another image. */
class ScreenVideoCadence(private val delayMillis: Long) {
    init { require(delayMillis >= 0) }
    private var precedingSegAt: Long? = null
    private var nextCaptureAt = Long.MIN_VALUE
    fun captured() { nextCaptureAt = precedingSegAt?.plus(delayMillis) ?: Long.MIN_VALUE }
    fun segmented(now: Long) { precedingSegAt = now }
    fun waitMillis(now: Long) = if (nextCaptureAt == Long.MIN_VALUE) 0L else (nextCaptureAt - now).coerceAtLeast(0)
    fun reset() { precedingSegAt = null; nextCaptureAt = Long.MIN_VALUE }
}

/** Contrast-normalized source strokes inside a detected SEG region, never translated pixels
 * or whole-screen differences. Foreground agreement rejects a matching empty background. */
class ScreenVideoAppearance private constructor(private val values: FloatArray, private val ink: BooleanArray) {
    fun similarity(other: ScreenVideoAppearance): Float {
        var dot = 0f; var aa = 0f; var bb = 0f; var common = 0; var total = 0
        for (i in values.indices) {
            dot += values[i] * other.values[i]; aa += values[i] * values[i]; bb += other.values[i] * other.values[i]
            if (ink[i] && other.ink[i]) common++
            if (ink[i]) total++
            if (other.ink[i]) total++
        }
        if (aa <= 0 || bb <= 0 || total == 0) return 0f
        val correlation = (dot / sqrt(aa * bb)).coerceIn(0f, 1f)
        return min(correlation * correlation, 2f * common / total)
    }
    companion object {
        const val EDGE = 32
        fun capture(sample: (Int, Int) -> Int): ScreenVideoAppearance? {
            val pixels = FloatArray(EDGE * EDGE) { sample(it % EDGE, it / EDGE).coerceIn(0, 255).toFloat() }
            val mean = pixels.average().toFloat()
            val ink = BooleanArray(pixels.size) { pixels[it] < mean - 8 }
            val deviation = sqrt(pixels.sumOf { ((it - mean) * (it - mean)).toDouble() } / pixels.size).toFloat()
            if (deviation < 4 || ink.count { it } < 12) return null
            return ScreenVideoAppearance(FloatArray(pixels.size) { (pixels[it] - mean) / deviation }, ink)
        }
    }
}

data class ScreenVideoRegion(val id: String, val kind: RegionKind, val bounds: PixelRect,
    val appearance: ScreenVideoAppearance?, val contour: List<PixelPoint> = emptyList())

/** One-to-one correspondence. Similar text elsewhere on the screen is not the same subtitle.
 * Shape and source strokes must both exceed the video's independent similarity threshold. */
fun screenVideoRegionMatches(previous: List<ScreenVideoRegion>, current: List<ScreenVideoRegion>,
    thresholdPercent: Int): Map<String, ScreenVideoRegion> {
    require(thresholdPercent in 1..100)
    data class Candidate(val old: ScreenVideoRegion, val fresh: ScreenVideoRegion, val score: Float)
    val candidates = current.flatMap { fresh -> previous.mapNotNull { old ->
        if (old.kind != fresh.kind || old.appearance == null || fresh.appearance == null ||
            old.bounds.area <= 0 || fresh.bounds.area <= 0) return@mapNotNull null
        val intersection = max(0f, min(old.bounds.right, fresh.bounds.right) - max(old.bounds.left, fresh.bounds.left)) *
            max(0f, min(old.bounds.bottom, fresh.bounds.bottom) - max(old.bounds.top, fresh.bounds.top))
        if (intersection < min(old.bounds.area, fresh.bounds.area) * .5f) return@mapNotNull null
        val size = min(min(old.bounds.width, fresh.bounds.width) / max(old.bounds.width, fresh.bounds.width),
            min(old.bounds.height, fresh.bounds.height) / max(old.bounds.height, fresh.bounds.height))
        if (size < .7f) return@mapNotNull null
        val score = min(size, min(old.appearance.similarity(fresh.appearance), videoContourSimilarity(old, fresh)))
        if (score * 100 > thresholdPercent) Candidate(old, fresh, score) else null
    } }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.fresh.id }.thenBy { it.old.id })
    val used = hashSetOf<String>(); val result = linkedMapOf<String, ScreenVideoRegion>()
    candidates.forEach { if (it.old.id !in used && it.fresh.id !in result) { used += it.old.id; result[it.fresh.id] = it.old } }
    return result
}

private fun videoContourSimilarity(a: ScreenVideoRegion, b: ScreenVideoRegion): Float {
    if (a.contour.size < 3 && b.contour.size < 3) return 1f
    fun inside(region: ScreenVideoRegion, u: Float, v: Float): Boolean {
        if (region.contour.size < 3) return true
        val x = region.bounds.left + u * region.bounds.width; val y = region.bounds.top + v * region.bounds.height
        var hit = false; var j = region.contour.lastIndex
        for (i in region.contour.indices) {
            val p = region.contour[i]; val q = region.contour[j]
            if ((p.y > y) != (q.y > y) && x < (q.x - p.x) * (y - p.y) / (q.y - p.y) + p.x) hit = !hit
            j = i
        }
        return hit
    }
    var intersection = 0; var union = 0
    repeat(16) { y -> repeat(16) { x ->
        val first = inside(a, (x + .5f) / 16, (y + .5f) / 16)
        val second = inside(b, (x + .5f) / 16, (y + .5f) / 16)
        if (first && second) intersection++
        if (first || second) union++
    } }
    return if (union == 0) 0f else intersection.toFloat() / union
}

/** Map a retained bubble's already prepared drawing onto its SEG bounds in the next frame. */
fun screenVideoRegionTransform(source: PixelRect, target: PixelRect): ScreenTransform {
    require(source.area > 0 && target.area > 0)
    val scale = min(target.width / source.width, target.height / source.height)
    return ScreenTransform(scale, (target.left + target.right - (source.left + source.right) * scale) / 2,
        (target.top + target.bottom - (source.top + source.bottom) * scale) / 2)
}
