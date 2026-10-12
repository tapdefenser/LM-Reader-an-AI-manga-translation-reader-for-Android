package com.lmreader.core.model

/** Source geometry at one capture instant, never the expanded translated text box. */
data class ScreenSourceRegion(val id: String, val bounds: PixelRect, val version: Long = 0,
    val template: ScreenObjectTemplate? = null)
data class ScreenMergedCrop(val bounds: PixelRect, val replaces: Set<String>, val preserves: List<ScreenSourceRegion> = emptyList(),
    val replacedSources: List<ScreenSourceRegion> = emptyList())

/** Membership follows detected source rectangles, never the enclosing crop rectangle.
 * Consequently a full-width exposed strip cannot pull unrelated old text into a merge. */
data class ScreenBrowseMergeGroup(val fresh: List<ScreenSourceRegion>, val old: List<ScreenSourceRegion>) {
    val bounds: PixelRect get() = (fresh + old).map { it.bounds }.reduce { a, b ->
        PixelRect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }
}

fun screenRegionDistance(a: PixelRect, b: PixelRect): Float {
    val x = maxOf(0f, a.left - b.right, b.left - a.right)
    val y = maxOf(0f, a.top - b.bottom, b.top - a.bottom)
    return kotlin.math.sqrt(x * x + y * y)
}

/** Crop packing is separate from pixel ownership. Exact rectangle subtraction
 * describes a mask, not a set of independent images: cutting each remainder
 * into its own image would also cut connected text along artificial seams. */
fun screenBrowseRecognitionCrops(fresh: List<PixelRect>, allowed: List<PixelRect>,
    contextPixels: Float): List<PixelRect> {
    require(contextPixels >= 0 && contextPixels.isFinite())
    fun union(a: PixelRect, b: PixelRect) = PixelRect(minOf(a.left, b.left), minOf(a.top, b.top),
        maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    val result = ArrayList<PixelRect>()
    for (scope in allowed) {
        val candidates = fresh.mapNotNull { area ->
            val l = maxOf(scope.left, area.left - contextPixels); val t = maxOf(scope.top, area.top - contextPixels)
            val r = minOf(scope.right, area.right + contextPixels); val b = minOf(scope.bottom, area.bottom + contextPixels)
            if (r > l && b > t) PixelRect(l, t, r, b) else null
        }.distinct().sortedByDescending { it.area }
        val crops = ArrayList<PixelRect>()
        for (candidate in candidates) {
            var crop = candidate
            var index = 0
            while (index < crops.size) {
                val previous = crops[index]
                if (screenRegionDistance(crop, previous) > 1f) { index++; continue }
                val joined = union(crop, previous)
                val overlap = maxOf(0f, minOf(crop.right, previous.right) - maxOf(crop.left, previous.left)) *
                    maxOf(0f, minOf(crop.bottom, previous.bottom) - maxOf(crop.top, previous.top))
                val occupied = crop.area + previous.area - overlap
                // A thin L-shaped exposure stays as two strips, rather than
                // accidentally sending the entire old page through SEG again.
                if (joined.area <= occupied * 1.75f) {
                    crop = joined; crops.removeAt(index); index = 0
                } else index++
            }
            crops += crop
        }
        result += crops
    }
    return result.distinct()
}

fun screenBrowseMergeGroups(fresh: List<ScreenSourceRegion>, old: List<ScreenSourceRegion>,
    distance: Float): List<ScreenBrowseMergeGroup> {
    require(distance >= 0 && distance.isFinite())
    val remaining = fresh.toMutableList()
    val groups = ArrayList<ScreenBrowseMergeGroup>()
    while (remaining.isNotEmpty()) {
        val members = mutableListOf(remaining.removeAt(0))
        val previous = linkedMapOf<String, ScreenSourceRegion>()
        var changed: Boolean
        do {
            changed = false
            for (region in old) if (region.id !in previous && (members + previous.values).any {
                    screenRegionDistance(it.bounds, region.bounds) <= distance }) {
                previous[region.id] = region; changed = true
            }
            val joined = remaining.filter { region -> (members + previous.values).any {
                screenRegionDistance(it.bounds, region.bounds) <= distance } }
            if (joined.isNotEmpty()) { members += joined; remaining.removeAll(joined.toSet()); changed = true }
        } while (changed)
        groups += ScreenBrowseMergeGroup(members, previous.values.toList())
    }
    return groups
}

/** A crop containing an intact old bubble does not justify repeating its OCR. */
fun screenSameSourceRegion(old: PixelRect, detected: PixelRect, appearance: ScreenObjectTemplate.Similarity?): Boolean {
    val width = minOf(old.right, detected.right) - maxOf(old.left, detected.left)
    val height = minOf(old.bottom, detected.bottom) - maxOf(old.top, detected.top)
    if (width <= 0 || height <= 0) return false
    val intersection = width * height
    val union = old.area + detected.area - intersection
    return union > 0 && intersection / union >= .90f && appearance != null &&
        appearance.visible >= .95f && appearance.correlation >= .97f
}

/** Empty detection or an unfinished sibling is not a replacement. Match
 * dependencies in the original crop coordinates, independent of later motion. */
fun screenReadyReplacements(crop: ScreenMergedCrop, detected: List<ScreenSourceRegion>, ready: Set<String>): Set<String> =
    crop.replacedSources.filter { old ->
        val candidates = detected.filter { it.bounds.intersects(old.bounds) }.map { it.id }
        candidates.isNotEmpty() && candidates.all { it in ready }
    }.map { it.id }.toSet()

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
                        if (complete || crop.preserves.any { it.id == region.id }) crop.preserves else crop.preserves + region,
                        if (!complete || crop.replacedSources.any { it.id == region.id }) crop.replacedSources else crop.replacedSources + region)
                    if (merged != crop) { crop = merged; changed = true }
                }
            }
            crops[i] = crop
        }
        outer@ for (i in crops.indices) for (j in i + 1 until crops.size) {
            if (crops[i].bounds.intersects(crops[j].bounds)) {
                crops[i] = ScreenMergedCrop(union(crops[i].bounds, crops[j].bounds), crops[i].replaces + crops[j].replaces,
                    (crops[i].preserves + crops[j].preserves).distinctBy { it.id },
                    (crops[i].replacedSources + crops[j].replacedSources).distinctBy { it.id })
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
