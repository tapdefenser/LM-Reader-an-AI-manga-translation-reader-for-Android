package com.lmreader.core.vision

import com.lmreader.core.model.*
import kotlin.math.*

/** Match upstream's crop-level line detection only where page scaling can lose detail. */
internal fun textRefinementRegions(seg: SegResult): List<SegRegion> {
    val pageArea = seg.width.toFloat() * seg.height
    val pageGain = planTiles(seg.width, seg.height).minOf { 960f / max(it.width, it.height) }
    return selectSegRegions(seg).filter { region ->
        val area = region.extractionBounds ?: region.bounds
        if (area.area >= pageArea * .5f) false else {
            val lines = selectRegionTextLines(region, seg.regions, seg.textLines)
            lines.isEmpty() || lines.any { min(it.bounds.width, it.bounds.height) * pageGain < 20f }
        }
    }
}

/** Text detection supplies captions missed by the segmentation head without duplicating speech. */
internal fun supplementFreeTextRegions(seg: SegResult): SegResult {
    val bubbles = seg.regions.filter { it.kind == RegionKind.BUBBLE }
    val existing = seg.regions.filter { it.kind == RegionKind.FREE_TEXT }
    val lines = seg.textLines.filter { line -> line.bounds.area > 0 && regionOwner(line.bounds, bubbles) == null &&
        // A partial text-block prediction must not suppress the complete detected line.
        existing.none { overlap(it.bounds, line.bounds) / line.bounds.area >= .9f } }
    val groups = mutableListOf<MutableList<DetectedTextLine>>()
    fun adjacent(a: PixelRect, b: PixelRect): Boolean {
        val vertical = a.height > a.width * 1.5f
        if(vertical != (b.height > b.width * 1.5f)) return false
        return if(vertical) {
            val shared = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
            val gap = (max(a.left, b.left) - min(a.right, b.right)).coerceAtLeast(0f)
            shared >= min(a.height, b.height) * .5f && gap <= max(a.width, b.width) * 1.2f
        } else {
            val shared = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
            val gap = (max(a.top, b.top) - min(a.bottom, b.bottom)).coerceAtLeast(0f)
            shared >= min(a.width, b.width) * .5f && gap <= max(a.height, b.height) * 1.2f
        }
    }
    for(line in lines) {
        val touching = groups.filter { group -> group.any { adjacent(it.bounds, line.bounds) } }
        val joined = mutableListOf(line)
        touching.forEach { joined += it; groups.remove(it) }
        groups += joined
    }
    val added = groups.mapIndexed { index, group -> SegRegion("${seg.imageId}:det:$index", RegionKind.FREE_TEXT,
        PixelRect(group.minOf { it.bounds.left }, group.minOf { it.bounds.top }, group.maxOf { it.bounds.right }, group.maxOf { it.bounds.bottom }),
        group.minOf { it.confidence }, textLineContour(group.map { it.bounds })) }
    return seg.copy(regions = seg.regions + added)
}

/** A compact scanline envelope retains ragged paragraph edges for detector-only captions. */
internal fun textLineContour(lines: List<PixelRect>): List<PixelPoint> {
    val levels = lines.flatMap { listOf(it.top, it.bottom) }.distinct().sorted()
    val left = ArrayList<PixelPoint>(); val right = ArrayList<PixelPoint>()
    levels.zipWithNext().forEach { (top, bottom) ->
        val active = lines.filter { it.top < bottom && it.bottom > top }
        if (active.isNotEmpty()) {
            val x1 = active.minOf { it.left }; val x2 = active.maxOf { it.right }
            left += PixelPoint(x1, top); left += PixelPoint(x1, bottom)
            right += PixelPoint(x2, top); right += PixelPoint(x2, bottom)
        }
    }
    return left + right.asReversed()
}

/** Keep the ownership and line coordinates established by SEG; OCR only reads those lines. */
fun selectRegionTextLines(region: SegRegion, pageRegions: List<SegRegion>, lines: List<DetectedTextLine>): List<DetectedTextLine> {
    val crop = region.extractionBounds ?: region.bounds
    val bubbles = pageRegions.filter { it.kind == RegionKind.BUBBLE }
    return lines.filter { regionCoverage(region, it.bounds) >= .5f &&
        (region.kind == RegionKind.BUBBLE || regionOwner(it.bounds, bubbles) == null) }.mapNotNull { line ->
        val left = max(crop.left, line.bounds.left); val top = max(crop.top, line.bounds.top)
        val right = min(crop.right, line.bounds.right); val bottom = min(crop.bottom, line.bounds.bottom)
        if(right > left && bottom > top) line.copy(bounds = PixelRect(left, top, right, bottom)) else null
    }
}

/**
 * Keep text-block detections until ownership is resolved, as upstream's VlPageLayout does.
 * One merged balloon may contain several independent text blocks. They need separate OCR
 * crops and identities; removing all interior text detections loses that information.
 */
fun selectSegRegions(seg: SegResult, scope: SegTextScope = SegTextScope.ALL,
    freeTextMergeGapRatio: Float = DEFAULT_FREE_TEXT_MERGE_GAP_RATIO): List<SegRegion> {
    require(freeTextMergeGapRatio.isFinite() && freeTextMergeGapRatio in 0f..2f)
    val bubbles = seg.regions.filter { it.kind == RegionKind.BUBBLE && it.bounds.area > 0 }
    val texts = seg.regions.filter { it.kind == RegionKind.FREE_TEXT && it.bounds.area > 0 }
    val owners = texts.associate { it.id to regionOwner(it.bounds, bubbles) }
    val result = buildList {
        for (bubble in bubbles) {
            val members = mergeTextBlocks(texts.filter { owners[it.id]?.id == bubble.id })
            when {
                // A single text prediction can be incomplete. Keep the full balloon crop
                // so its other OCR lines are not silently lost.
                members.size == 1 -> add(bubble)
                members.size > 1 -> members.forEach { text ->
                    val bounds = paddedTextBounds(text.bounds, seg)
                    add(SegRegion("${bubble.id}:text:${text.id}", RegionKind.BUBBLE, bounds,
                        min(bubble.confidence, text.confidence), clipContour(bubble.contour, bounds), bounds))
                }
                // A coarse enclosing prediction must not duplicate its occupied child balloons.
                bubbles.any { child -> child.id != bubble.id && child.bounds.area < bubble.bounds.area &&
                    overlap(child.bounds, bubble.bounds) / child.bounds.area > .9f &&
                    owners.values.any { it?.id == child.id } } -> Unit
                else -> add(bubble)
            }
        }
        mergeTextBlocks(texts.filter { owners[it.id] == null }, freeTextMergeGapRatio).forEach { text ->
            addAll(splitFreeTextLines(text, seg, freeTextMergeGapRatio))
        }
    }
    return result.filter { scope.includes(it.kind) }.sortedWith(compareBy<SegRegion> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.id })
}

/** The dual head can predict a paragraph, its columns and fragments simultaneously.
 * Resolve those overlapping predictions before splitting a balloon into API/OCR crops.
 * Separate blocks in a connected balloon remain separate when their text does not join.
 */
private fun mergeTextBlocks(regions: List<SegRegion>, gapRatio: Float = 1.2f): List<SegRegion> {
    val groups = mutableListOf<MutableList<SegRegion>>()
    fun joins(a: PixelRect, b: PixelRect): Boolean {
        if (overlap(a,b) / min(a.area,b.area).coerceAtLeast(1f) >= if(gapRatio == 0f) .8f else .25f) return true
        val vertical = a.height > a.width * 1.5f
        if (vertical != (b.height > b.width * 1.5f)) return false
        val shared = if(vertical) min(a.bottom,b.bottom)-max(a.top,b.top) else min(a.right,b.right)-max(a.left,b.left)
        val length = if(vertical) min(a.height,b.height) else min(a.width,b.width)
        val gap = if(vertical) max(a.left,b.left)-min(a.right,b.right) else max(a.top,b.top)-min(a.bottom,b.bottom)
        val thickness = if(vertical) max(a.width,b.width) else max(a.height,b.height)
        return gapRatio > 0f && shared >= length*.5f && gap <= thickness*gapRatio
    }
    for(region in regions) {
        val touching=groups.filter {group -> group.any { joins(it.bounds,region.bounds) }}
        val joined=mutableListOf(region)
        touching.forEach {joined+=it;groups.remove(it)}
        groups+=joined
    }
    return groups.map { group ->
        if(group.size==1) group.single() else {
            val first=group.maxBy {it.confidence}
            first.copy(bounds=PixelRect(group.minOf {it.bounds.left},group.minOf {it.bounds.top},
                group.maxOf {it.bounds.right},group.maxOf {it.bounds.bottom}),
                contour=textLineContour(group.map {it.bounds}),extractionBounds=null)
        }
    }
}

/** Split even a coarse SEG paragraph using the detector's independent lines.
 * Bands meet halfway between lines so neither crop can acquire its neighbor's text;
 * retaining the original outer edges also preserves text missed by the line detector.
 */
private fun splitFreeTextLines(region: SegRegion, seg: SegResult, gapRatio: Float): List<SegRegion> {
    val lines = selectRegionTextLines(region, seg.regions, seg.textLines)
    val groups = mergeTextBlocks(lines.mapIndexed { index, line ->
        SegRegion("${region.id}:line:$index", RegionKind.FREE_TEXT, line.bounds, line.confidence)
    }, gapRatio)
    val outer = paddedTextBounds(region.bounds, seg)
    if(groups.size <= 1) return listOf(region.copy(bounds = outer, extractionBounds = outer))
    val vertical = lines.count { it.bounds.height > it.bounds.width * 1.5f } > lines.size / 2
    fun start(r: PixelRect) = if(vertical) r.left else r.top
    fun end(r: PixelRect) = if(vertical) r.right else r.bottom
    val sorted = groups.sortedWith(compareBy<SegRegion> { start(it.bounds) }.thenBy { it.id })
    // Side-by-side horizontal blocks cannot be partitioned into horizontal bands.
    val bands = sorted.zipWithNext().all { (a,b) ->
        val parallelOverlap = if(vertical) min(a.bounds.bottom,b.bounds.bottom)-max(a.bounds.top,b.bounds.top)
            else min(a.bounds.right,b.bounds.right)-max(a.bounds.left,b.bounds.left)
        val length = if(vertical) min(a.bounds.height,b.bounds.height) else min(a.bounds.width,b.bounds.width)
        val thickness = min(end(a.bounds)-start(a.bounds), end(b.bounds)-start(b.bounds))
        val centers = (start(b.bounds)+end(b.bounds)-start(a.bounds)-end(a.bounds))/2
        parallelOverlap >= length*.5f && centers >= thickness*.25f
    }
    return sorted.mapIndexed { index, group ->
        val bounds = if(bands) {
            val from = if(index == 0) start(outer) else (end(sorted[index-1].bounds) + start(group.bounds)) / 2
            val to = if(index == sorted.lastIndex) end(outer) else (end(group.bounds) + start(sorted[index+1].bounds)) / 2
            if(vertical) PixelRect(from, outer.top, to, outer.bottom) else PixelRect(outer.left, from, outer.right, to)
        } else group.bounds
        val contour = clipContour(region.contour, bounds)
        region.copy(id = group.id, bounds = bounds, contour = contour, extractionBounds = bounds,
            confidence = min(region.confidence, group.confidence))
    }
}

/** Polygon coverage prevents a caption in an irregular balloon's bounding box becoming speech. */
internal fun regionCoverage(region: SegRegion, bounds: PixelRect): Float {
    if (bounds.area <= 0) return 0f
    val boxCoverage = overlap(region.bounds, bounds) / bounds.area
    // A text mask may follow ink rather than the full DB line rectangle. It is useful
    // for rendering, but must not filter out a padded line or redirect it to a new target.
    if (region.kind == RegionKind.FREE_TEXT || region.contour.size < 3 || boxCoverage == 0f) return boxCoverage
    var inside = 0
    val samples = 7
    for (y in 0 until samples) for (x in 0 until samples) {
        if (polygonContains(region.contour, bounds.left + (x + .5f) * bounds.width / samples,
                bounds.top + (y + .5f) * bounds.height / samples)) inside++
    }
    return min(boxCoverage, inside.toFloat() / (samples * samples))
}

internal fun regionOwner(bounds: PixelRect, regions: List<SegRegion>): SegRegion? = regions
    .map { it to regionCoverage(it, bounds) }.filter { it.second >= .5f }
    .sortedWith(compareByDescending<Pair<SegRegion, Float>> { it.second }.thenBy { it.first.bounds.area })
    .firstOrNull()?.first

private fun paddedTextBounds(bounds: PixelRect, seg: SegResult): PixelRect {
    val pad = max(3f, min(bounds.width, bounds.height) * .08f)
    return PixelRect((bounds.left - pad).coerceAtLeast(0f), (bounds.top - pad).coerceAtLeast(0f),
        (bounds.right + pad).coerceAtMost(seg.width.toFloat()), (bounds.bottom + pad).coerceAtMost(seg.height.toFloat()))
}

/** Sutherland-Hodgman clipping keeps each child overlay inside its own crop. */
internal fun clipContour(contour: List<PixelPoint>, rect: PixelRect): List<PixelPoint> {
    var points = contour
    fun clip(inside: (PixelPoint) -> Boolean, crossing: (PixelPoint, PixelPoint) -> PixelPoint) {
        if (points.isEmpty()) return
        val input = points; val output = ArrayList<PixelPoint>()
        var previous = input.last()
        for (point in input) {
            if (inside(point) != inside(previous)) output += crossing(previous, point)
            if (inside(point)) output += point
            previous = point
        }
        points = output
    }
    fun atX(a: PixelPoint, b: PixelPoint, x: Float) = PixelPoint(x, a.y + (b.y - a.y) * (x - a.x) / (b.x - a.x))
    fun atY(a: PixelPoint, b: PixelPoint, y: Float) = PixelPoint(a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y), y)
    clip({ it.x >= rect.left }, { a, b -> atX(a, b, rect.left) })
    clip({ it.x <= rect.right }, { a, b -> atX(a, b, rect.right) })
    clip({ it.y >= rect.top }, { a, b -> atY(a, b, rect.top) })
    clip({ it.y <= rect.bottom }, { a, b -> atY(a, b, rect.bottom) })
    return points
}

/** Adjacent/connected balloons can overlap substantially without being duplicate predictions. */
internal fun duplicateSegBounds(a: PixelRect, b: PixelRect): Boolean {
    if (min(a.area, b.area) <= 0) return false
    val widthRatio = min(a.width, b.width) / max(a.width, b.width)
    val heightRatio = min(a.height, b.height) / max(a.height, b.height)
    val centered = abs((a.left + a.right) - (b.left + b.right)) / 2 < min(a.width, b.width) * .18f &&
        abs((a.top + a.bottom) - (b.top + b.bottom)) / 2 < min(a.height, b.height) * .18f
    return iou(a, b) > .85f || (widthRatio > .75f && heightRatio > .75f && centered &&
        overlap(a, b) / min(a.area, b.area) > .8f)
}

internal fun keepSegRegions(regions: List<SegRegion>): List<SegRegion> {
    val kept = ArrayList<SegRegion>()
    for (region in regions.sortedByDescending { it.confidence }) {
        if (kept.none { previous -> previous.kind == region.kind && duplicateSegBounds(previous.bounds, region.bounds) &&
                (previous.contour.size < 3 || region.contour.size < 3 ||
                    regionCoverage(previous, contourBounds(region.contour)) >= .7f &&
                    regionCoverage(region, contourBounds(previous.contour)) >= .7f) }) kept += region
    }
    return kept
}

internal fun contourBounds(points: List<PixelPoint>) = PixelRect(points.minOf { it.x }, points.minOf { it.y },
    points.maxOf { it.x }, points.maxOf { it.y })
