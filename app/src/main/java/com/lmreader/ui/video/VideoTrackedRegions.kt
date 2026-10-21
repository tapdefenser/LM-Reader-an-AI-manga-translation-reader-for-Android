package com.lmreader.ui.video

import android.graphics.Bitmap
import com.lmreader.core.database.media.VideoRegionObservation
import com.lmreader.core.model.*
import org.json.JSONArray
import java.util.Base64

/** Small original-pixel observations outlive decoded frames; translations belong to the track. */
internal fun videoObservation(videoId: String, hash: String, identity: String, sample: Long, track: String,
    targetUs: Long, ptsUs: Long, untilUs: Long, bitmap: Bitmap, region: SegRegion): VideoRegionObservation {
    val raw = ByteArray(ScreenVideoAppearance.EDGE * ScreenVideoAppearance.EDGE) { i ->
        val x = (region.bounds.left + (i % 32 + .5f) * region.bounds.width / 32).toInt().coerceIn(0, bitmap.width - 1)
        val y = (region.bounds.top + (i / 32 + .5f) * region.bounds.height / 32).toInt().coerceIn(0, bitmap.height - 1)
        val color = bitmap.getPixel(x, y)
        (((color shr 16 and 255) * 77 + (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8).toByte()
    }
    val contour = JSONArray().also { points -> region.contour.forEach { points.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble())) } }
    return VideoRegionObservation(videoId, hash, identity, sample, track, targetUs, ptsUs, untilUs,
        bitmap.width, bitmap.height, region.kind.name, region.bounds.left, region.bounds.top, region.bounds.right,
        region.bounds.bottom, contour.toString(), Base64.getEncoder().encodeToString(raw))
}

internal fun VideoRegionObservation.bounds() = PixelRect(left, top, right, bottom)
internal fun VideoRegionObservation.points(): List<PixelPoint> {
    val points = JSONArray(contour)
    return (0 until points.length()).map { points.getJSONArray(it).let { p -> PixelPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } }
}
internal fun VideoRegionObservation.screenRegion(): ScreenVideoRegion {
    val raw = appearance?.let { Base64.getDecoder().decode(it) }
    val proof = raw?.takeIf { it.size == 32 * 32 }?.let { bytes ->
        ScreenVideoAppearance.capture { x, y -> bytes[y * 32 + x].toInt() and 255 }
    }
    return ScreenVideoRegion(trackId, RegionKind.valueOf(kind), bounds(), proof, points())
}

internal fun VideoRegionObservation.matches(bitmap: Bitmap, similarity: Int): Boolean {
    val old = screenRegion()
    val fresh = old.copy(appearance = ScreenVideoAppearance.capture { x, y ->
        val px = ((left + (x + .5f) * (right - left) / 32) * bitmap.width / width).toInt().coerceIn(0, bitmap.width - 1)
        val py = ((top + (y + .5f) * (bottom - top) / 32) * bitmap.height / height).toInt().coerceIn(0, bitmap.height - 1)
        val color = bitmap.getPixel(px, py)
        ((color shr 16 and 255) * 77 + (color shr 8 and 255) * 150 + (color and 255) * 29) shr 8
    })
    return screenVideoRegionMatches(listOf(old), listOf(fresh), similarity).isNotEmpty()
}

/** Apply observed source motion to the saved geometry, preserving a human's relative adjustment. */
internal fun moveVideoRegion(region: PageTranslatedRegion, anchor: VideoRegionObservation?, current: VideoRegionObservation,
    width: Int, height: Int): PageTranslatedRegion {
    fun scaled(row: VideoRegionObservation) = PixelRect(row.left * width / row.width, row.top * height / row.height,
        row.right * width / row.width, row.bottom * height / row.height)
    val from = anchor?.let(::scaled) ?: region.region.bounds
    val to = scaled(current)
    if(from.area <= 0 || to.area <= 0) return region
    val transform = screenVideoRegionTransform(from, to)
    return region.copy(region = region.region.copy(bounds = transform.bounds(region.region.bounds),
        contour = region.region.contour.map(transform::point), textBounds = region.region.textBounds.map(transform::bounds)))
}
