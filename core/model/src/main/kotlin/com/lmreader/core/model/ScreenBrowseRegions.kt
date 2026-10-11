package com.lmreader.core.model

/** Source geometry at one capture instant, never the expanded translated text box. */
data class ScreenSourceRegion(val id: String, val bounds: PixelRect, val version: Long = 0)
data class ScreenMergedCrop(val bounds: PixelRect, val replaces: Set<String>, val preserves: List<ScreenSourceRegion> = emptyList())

/** Merge pixels before detection/OCR. Closure includes old regions and other new crops
 * reached by the enlarged rectangle; all bounds refer to the same clean frame. */
fun screenMergedCrops(newAreas: List<PixelRect>, old: List<ScreenSourceRegion>, viewport: PixelRect,
    contextPixels: Float, allowedAreas: List<PixelRect> = listOf(viewport)): List<ScreenMergedCrop> {
    fun clipped(r: PixelRect): PixelRect? {
        val l = maxOf(viewport.left, r.left); val t = maxOf(viewport.top, r.top)
        val right = minOf(viewport.right, r.right); val bottom = minOf(viewport.bottom, r.bottom)
        return if (right > l && bottom > t) PixelRect(l, t, right, bottom) else null
    }
    fun union(a: PixelRect, b: PixelRect) = PixelRect(minOf(a.left, b.left), minOf(a.top, b.top),
        maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    val sources = old.groupBy { it.id }.values.map { regions -> regions.first().copy(
        bounds = regions.map { it.bounds }.reduce(::union), version = regions.maxOf { it.version }) }
    val crops = newAreas.mapNotNull { area -> clipped(PixelRect(area.left - contextPixels, area.top - contextPixels,
        area.right + contextPixels, area.bottom + contextPixels))?.let { ScreenMergedCrop(it, emptySet()) } }.toMutableList()
    var changed = true
    while (changed) {
        changed = false
        for (i in crops.indices) {
            var crop = crops[i]
            for (region in sources) {
                val visible = clipped(region.bounds) ?: continue
                if (allowedAreas.none { it.intersects(visible) }) continue
                if (visible.intersects(crop.bounds)) {
                    val complete = visible == region.bounds && screenExposedRegions(region.bounds, allowedAreas).isEmpty()
                    val merged = ScreenMergedCrop(union(crop.bounds, visible), if (complete) crop.replaces + region.id else crop.replaces,
                        if (complete || crop.preserves.any { it.id == region.id }) crop.preserves else crop.preserves + region)
                    if (merged != crop) { crop = merged; changed = true }
                }
            }
            crops[i] = crop
        }
        outer@ for (i in crops.indices) for (j in i + 1 until crops.size) {
            if (crops[i].bounds.intersects(crops[j].bounds)) {
                crops[i] = ScreenMergedCrop(union(crops[i].bounds, crops[j].bounds), crops[i].replaces + crops[j].replaces,
                    (crops[i].preserves + crops[j].preserves).distinctBy { it.id })
                crops.removeAt(j); changed = true; break@outer
            }
        }
    }
    return crops
}

/** A newer committed crop owns its pixels. Older in-flight work may still publish
 * unrelated regions, but cannot resurrect text replaced by that crop. */
class ScreenRegionPublication {
    private data class Entry(val bounds: PixelRect, val version: Long)
    private val content = ArrayList<Entry>()
    private val fixed = ArrayList<Entry>()
    fun clear() { content.clear(); fixed.clear() }
    fun accepts(bounds: PixelRect, version: Long, screenFixed: Boolean = false): Boolean =
        (if (screenFixed) fixed else content).none { it.version > version && it.bounds.intersects(bounds) }
    fun commit(bounds: PixelRect, version: Long, screenFixed: Boolean = false) {
        val entries = if (screenFixed) fixed else content
        if (!accepts(bounds, version, screenFixed)) return
        val older = entries.filter { it.version <= version }
        val remaining = older.flatMap { entry -> screenExposedRegions(entry.bounds, bounds).map { Entry(it, entry.version) } }
        entries.removeAll(older.toSet()); entries.addAll(remaining); entries += Entry(bounds, version)
    }
    fun retain(contentArea: PixelRect, screenArea: PixelRect) {
        content.removeAll { !it.bounds.intersects(contentArea) }; fixed.removeAll { !it.bounds.intersects(screenArea) }
    }
}

/** Stability belongs to fresh measured content poses, independent of render smoothing,
 * touch events, OCR capacity and downstream API/MT waits. */
class ScreenBrowseStability {
    private var sequence = Long.MIN_VALUE
    private var lastGoodAt = Long.MIN_VALUE
    private var movedAt = 0L
    private var reliable = false
    fun reset(now: Long = 0) { sequence = Long.MIN_VALUE; lastGoodAt = Long.MIN_VALUE; movedAt = now; reliable = false }
    fun observe(frameSequence: Long, capturedAt: Long, moved: Boolean, good: Boolean) {
        if (frameSequence == sequence) {
            if (reliable && good) lastGoodAt = capturedAt // latest stream image can legitimately be unchanged
            return
        }
        if (frameSequence < sequence) return
        if (sequence == Long.MIN_VALUE) movedAt = capturedAt
        sequence = frameSequence; reliable = good
        if (good) lastGoodAt = capturedAt
        if (moved || !good) movedAt = capturedAt
    }
    fun waitMillis(now: Long, settleMillis: Int): Long = (movedAt + settleMillis - now).coerceAtLeast(0)
    fun ready(now: Long, settleMillis: Int, maximumFrameAge: Long): Boolean = reliable &&
        now - lastGoodAt <= maximumFrameAge && waitMillis(now, settleMillis) == 0L
}
