package com.lmreader.core.vision

import android.graphics.*
import com.lmreader.core.model.*
import kotlin.math.*

class SegRegionImage(val bitmap: Bitmap, val left: Int, val top: Int) : AutoCloseable {
    override fun close() = bitmap.recycle()
}

/** The same isolated region image is used by local OCR and image API attachments. */
fun cropSegRegion(image: Bitmap, region: SegRegion, pageRegions: List<SegRegion>): SegRegionImage {
    val area = region.extractionBounds ?: region.bounds
    val left = floor(area.left).toInt().coerceIn(0, image.width - 1)
    val top = floor(area.top).toInt().coerceIn(0, image.height - 1)
    val right = ceil(area.right).toInt().coerceIn(left + 1, image.width)
    val bottom = ceil(area.bottom).toInt().coerceIn(top + 1, image.height)
    val output = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888)
    try {
        // Text masks describe the overlay, not a safe glyph crop: tight contours can cut
        // off bold outlines and accents. Keep padded original pixels for OCR and VL.
        val allowed = if (region.kind == RegionKind.FREE_TEXT) Path().apply {
            addRect(area.left, area.top, area.right, area.bottom, Path.Direction.CW)
        } else regionPath(region)
        if (region.kind == RegionKind.FREE_TEXT) {
            // A caption's rectangle can intersect a balloon. Even free-text-only mode must
            // remove the balloon pixels using the unfiltered page detections.
            for (bubble in pageRegions.filter { it.kind == RegionKind.BUBBLE && overlap(it.bounds, area) > 0 }) {
                check(allowed.op(regionPath(bubble), Path.Op.DIFFERENCE))
            }
        }
        Canvas(output).apply {
            drawColor(Color.WHITE)
            translate(-left.toFloat(), -top.toFloat())
            clipPath(allowed)
            drawBitmap(image, 0f, 0f, null)
        }
        return SegRegionImage(output, left, top)
    } catch (failure: Throwable) { output.recycle(); throw failure }
}

private fun regionPath(region: SegRegion) = Path().apply {
    if (region.contour.size >= 3) {
        moveTo(region.contour.first().x, region.contour.first().y)
        region.contour.drop(1).forEach { lineTo(it.x, it.y) }; close()
    } else {
        val area = region.extractionBounds ?: region.bounds
        addRect(area.left, area.top, area.right, area.bottom, Path.Direction.CW)
    }
}

suspend fun LocalVisionEngine.recognizeRegion(imageId: String, image: Bitmap, language: LocalOcrLanguage,
    region: SegRegion, pageRegions: List<SegRegion>, textLines: List<DetectedTextLine>? = null,
    progress: (VisionProgress) -> Unit = {}): LocalOcrResult {
    return cropSegRegion(image, region, pageRegions).use { crop ->
        val localLines = textLines?.let { selectRegionTextLines(region, pageRegions, it) }
            ?.map { it.copy(bounds = it.bounds.offset(-crop.left.toFloat(), -crop.top.toFloat())) }
        val result = recognize(imageId, crop.bitmap, language, textLines = localLines, progress = progress)
        result.copy(width = image.width, height = image.height,
            lines = result.lines.map { it.copy(bounds = it.bounds.offset(crop.left.toFloat(), crop.top.toFloat())) })
    }
}
