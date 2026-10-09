package com.lmreader.ui.reader.translation

import com.lmreader.core.model.*
import kotlin.math.*

sealed interface BubbleEditGesture {
    data class Begin(val id: String) : BubbleEditGesture
    data class Transform(val id: String, val bounds: PixelRect, val rotation: Float) : BubbleEditGesture
    data object End : BubbleEditGesture
}

fun rotateBubblePoint(point: PixelPoint, bounds: PixelRect, degrees: Float): PixelPoint {
    val angle = degrees * PI.toFloat() / 180
    val cx = (bounds.left + bounds.right) / 2; val cy = (bounds.top + bounds.bottom) / 2
    val x = point.x - cx; val y = point.y - cy
    return PixelPoint(cx + x * cos(angle) - y * sin(angle), cy + x * sin(angle) + y * cos(angle))
}

internal fun constrainBubbleBounds(bounds: PixelRect, width: Int, height: Int): PixelRect {
    val w = bounds.width.coerceIn(8f.coerceAtMost(width.toFloat()), width.toFloat())
    val h = bounds.height.coerceIn(8f.coerceAtMost(height.toFloat()), height.toFloat())
    val left = bounds.left.coerceIn(0f, width - w); val top = bounds.top.coerceIn(0f, height - h)
    return PixelRect(left, top, left + w, top + h)
}

/** Prevents a reverse-read page from being evicted when equal map values suppress an LRU update. */
internal fun <T> retainReaderPage(pages: Map<String, T>, id: String, value: T, current: String?, capacity: Int = 8): Map<String, T> {
    val updated = (pages - id) + (id to value)
    val retained = (listOfNotNull(current?.takeIf { it in updated }) + updated.keys.toList().asReversed()).distinct().take(capacity).toSet()
    return updated.filterKeys { it in retained }
}
