package com.lmreader.core.model

data class ScreenApplicationWindow(val id: Int, val packageName: String, val bounds: PixelRect,
    val layer: Int, val active: Boolean = false, val focused: Boolean = false, val modal: Boolean = false)
data class ScreenApplicationWindowSelection(val window: ScreenApplicationWindow, val occlusions: List<PixelRect>)

/** A dialog owns focus, but is not a new underlying page. Android may omit its
 * untouchable parent from the interactive window list, so keep the last content
 * window during a modal overlay. A new full-size nonmodal window is navigation. */
class ScreenApplicationWindows {
    private var selected: ScreenApplicationWindow? = null
    fun update(windows: List<ScreenApplicationWindow>): ScreenApplicationWindowSelection? {
        val front = windows.filter { it.active || it.focused }.maxByOrNull { it.layer }
            ?: windows.maxByOrNull { it.layer }
            ?: return selected?.let { ScreenApplicationWindowSelection(it, listOf(it.bounds)) }
        val visiblePrevious = windows.firstOrNull { it.id == selected?.id }?.let { current ->
            if (current.packageName.isBlank()) current.copy(packageName = selected!!.packageName) else current
        }
        val previous = visiblePrevious ?: selected
        val dialog = previous != null && front.id != previous.id &&
            (visiblePrevious == null || front.layer > previous.layer) &&
            front.bounds.intersects(previous.bounds) &&
            (front.modal || visiblePrevious != null && front.packageName == previous.packageName || front.bounds.area < previous.bounds.area * .85f)
        val content = if (dialog) checkNotNull(previous) else front
        selected = content
        val occlusions = windows.filter { it.id != content.id && (visiblePrevious == null && dialog || it.layer > content.layer) }
            .mapNotNull { it.bounds.intersection(content.bounds) }
        return ScreenApplicationWindowSelection(content, occlusions)
    }
    fun reset() { selected = null }
}

private fun PixelRect.intersection(other: PixelRect): PixelRect? = if (!intersects(other)) null else
    PixelRect(maxOf(left, other.left), maxOf(top, other.top), minOf(right, other.right), minOf(bottom, other.bottom))
