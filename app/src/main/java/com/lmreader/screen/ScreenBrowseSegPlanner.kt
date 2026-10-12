package com.lmreader.screen

import android.graphics.Bitmap
import com.lmreader.core.model.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.ceil
import kotlin.math.floor

/** Every rectangle is frozen in the pixels of this one clean screenshot. Following
 * may continue while this plan runs; it cannot change exclusion or merge membership. */
internal data class ScreenBrowseSegPlan(val segmentation: SegResult, val regions: List<SegRegion>,
    val replacement: ScreenMergedCrop, val searched: List<PixelRect>, val owned: List<PixelRect>,
    val firstPassInputs: List<PixelRect>, val mergeInputs: List<PixelRect>, val reused: Set<String>)

internal class ScreenBrowseSegPlanner(private val detect: suspend (Bitmap, String) -> Pair<SegResult, List<SegRegion>>) {
    suspend fun plan(pageId: String, image: Bitmap, fresh: List<PixelRect>, old: List<ScreenSourceRegion>,
        allowed: List<PixelRect>, distance: Float, contextPixels: Float = 64f,
        unchanged: (ScreenSourceRegion, SegRegion) -> Boolean): ScreenBrowseSegPlan {
        val viewport = PixelRect(0f, 0f, image.width.toFloat(), image.height.toFloat())
        val inputs = fresh.flatMap { area -> allowed.mapNotNull { it.intersection(area) } }
            .flatMap { screenExposedRegions(it, old.map { source -> source.bounds }) }
        val crops = screenBrowseRecognitionCrops(inputs, allowed, contextPixels)
        val first = ArrayList<PixelRect>(); val second = ArrayList<PixelRect>()
        var elapsed = 0L; val backends = linkedSetOf<String>()
        var number = 0
        suspend fun run(bounds: PixelRect, areas: List<PixelRect>, merge: Boolean): Pair<List<SegRegion>, List<DetectedTextLine>> {
            currentCoroutineContext().ensureActive()
            val clipped = bounds.intersection(viewport) ?: return emptyList<SegRegion>() to emptyList()
            val x = floor(clipped.left).toInt().coerceIn(0, image.width)
            val y = floor(clipped.top).toInt().coerceIn(0, image.height)
            val r = ceil(clipped.right).toInt().coerceIn(x, image.width)
            val b = ceil(clipped.bottom).toInt().coerceIn(y, image.height)
            if (r - x < 2 || b - y < 2) return emptyList<SegRegion>() to emptyList()
            val cropBounds = PixelRect(x.toFloat(), y.toFloat(), r.toFloat(), b.toFloat())
            val raw = Bitmap.createBitmap(image, x, y, r - x, b - y)
            val crop = maskedScreenCrop(raw, cropBounds, areas)
            try {
                if (merge) second += cropBounds else first += cropBounds
                val (seg, selected) = detect(crop, "$pageId:plan:${number++}")
                elapsed += seg.elapsedMillis; backends += seg.backend
                fun region(value: SegRegion) = value.copy(bounds = value.bounds.offset(x.toFloat(), y.toFloat()),
                    contour = value.contour.map { PixelPoint(it.x + x, it.y + y) },
                    extractionBounds = value.extractionBounds?.offset(x.toFloat(), y.toFloat()))
                return selected.map(::region).filter { value -> areas.any { it.intersects(value.bounds) } } to
                    seg.textLines.map { it.copy(bounds = it.bounds.offset(x.toFloat(), y.toFloat())) }
            } finally {
                if (crop !== raw && crop !== image) crop.recycle()
                if (raw !== image) raw.recycle()
            }
        }
        val discovered = ArrayList<SegRegion>(); val initialLines = ArrayList<DetectedTextLine>()
        val origins = ArrayList<Int>()
        for ((index, area) in crops.withIndex()) {
            val scope = allowed.mapNotNull { it.intersection(area) }
                .flatMap { screenExposedRegions(it, old.map { source -> source.bounds }) }
            val (regions, lines) = run(area, scope, false)
            val relevant = regions.filter { region -> inputs.any { it.intersects(region.bounds) } }
            discovered += relevant; origins += List(relevant.size) { index }; initialLines += lines
        }
        // Stable temporary IDs are used only by the planning graph. No OCR is
        // allowed to see these first-pass regions.
        val initial = discovered.mapIndexed { i, region -> region.copy(id = "$pageId:proposal:$i") }
        val groups = screenBrowseMergeGroups(initial.map { ScreenSourceRegion(it.id, it.bounds) }, old, distance)
        val result = ArrayList<SegRegion>(); val lines = ArrayList<DetectedTextLine>()
        val redetectedProposals = ArrayList<PixelRect>()
        val replacements = linkedMapOf<String, ScreenSourceRegion>(); val reused = linkedSetOf<String>()
        for (group in groups) {
            val proposalIndices = initial.withIndex().filter { it.value.id in group.fresh.map { source -> source.id }.toSet() }
                .map { origins[it.index] }.distinct()
            if (group.old.isEmpty() && proposalIndices.size <= 1) {
                result += initial.filter { it.id in group.fresh.map { freshRegion -> freshRegion.id }.toSet() }
                continue
            }
            val complete = group.old.filter { source -> viewport.intersection(source.bounds) == source.bounds &&
                screenExposedRegions(source.bounds, allowed).isEmpty() }
            val memberIds = complete.map { it.id }.toSet()
            val protected = old.filter { it.id !in memberIds }
            redetectedProposals += group.fresh.map { it.bounds }
            val bounds = PixelRect(group.bounds.left - contextPixels, group.bounds.top - contextPixels,
                group.bounds.right + contextPixels, group.bounds.bottom + contextPixels).intersection(viewport)!!
            val scope = allowed.mapNotNull { it.intersection(bounds) }
                .flatMap { screenExposedRegions(it, protected.map { source -> source.bounds }) }
            val (redetected, detectedLines) = run(bounds, scope, true)
            val retained = linkedSetOf<String>()
            val final = redetected.filter { candidate -> (group.fresh + group.old).any { it.bounds.intersects(candidate.bounds) } }.filter { candidate ->
                val previous = complete.firstOrNull { it.id !in retained && unchanged(it, candidate) }
                if (previous != null) { retained += previous.id; reused += previous.id; false } else true
            }.filter { candidate -> protected.none { it.bounds.intersects(candidate.bounds) } }
            // Empty re-detection cannot obsolete readable old output.
            complete.filter { source -> source.id !in retained && final.any { it.bounds.intersects(source.bounds) } }
                .forEach { replacements[it.id] = it }
            result += final; lines += detectedLines
        }
        // OCR consumes DET lines as well as SEG regions. A new region must not
        // retain the fragmented first-pass lines that its re-detection replaced.
        lines += initialLines.filter { line -> redetectedProposals.none { it.intersects(line.bounds) } }
        val finalRegions = result.mapIndexed { i, region -> region.copy(id = "$pageId:planned:$i") }
        val finalLines = lines.filter { line -> finalRegions.any { it.bounds.intersects(line.bounds) } }.distinct()
        // Whole-page OCR/API rows receive only owned pixels too. Reused old
        // bubbles and unrelated rectangles inside a merge bounding box stay out.
        val owned = (inputs + finalRegions.map { it.extractionBounds ?: it.bounds }).flatMap { area ->
            allowed.mapNotNull { it.intersection(area) }
        }.flatMap { screenExposedRegions(it, old.filter { source -> source.id !in replacements }.map { source -> source.bounds }) }
        return ScreenBrowseSegPlan(SegResult(pageId, image.width, image.height, finalRegions, elapsed,
            backends.joinToString("+"), finalLines), finalRegions,
            ScreenMergedCrop(viewport, replacements.keys, replacedSources = replacements.values.toList()), inputs, owned,
            first, second, reused)
    }
}

private fun PixelRect.intersection(other: PixelRect): PixelRect? {
    val left = maxOf(left, other.left); val top = maxOf(top, other.top)
    val right = minOf(right, other.right); val bottom = minOf(bottom, other.bottom)
    return if (right > left && bottom > top) PixelRect(left, top, right, bottom) else null
}
