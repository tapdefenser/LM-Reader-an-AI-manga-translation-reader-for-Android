package com.lmreader.ui.reader.translation

import com.lmreader.core.model.*

/** A per-page draft. Reader edit mode itself is independent of this page snapshot. */
data class PageBubbleDraft(
    val saved: ReaderPageTranslation,
    val regions: List<PageTranslatedRegion> = saved.regions,
    val selectedId: String? = null,
    val undo: List<List<PageTranslatedRegion>> = emptyList(),
    val gestureStart: List<PageTranslatedRegion>? = null,
) {
    val dirty get() = regions != saved.regions
    val selected get() = regions.firstOrNull { it.region.id == selectedId }

    fun select(id: String?) = copy(selectedId = id?.takeIf { candidate -> regions.any { it.region.id == candidate } })
    fun editText(text: String): PageBubbleDraft {
        require(text.length <= 16384)
        val id = selected?.region?.id ?: return this
        return change(regions.map { if (it.region.id == id) it.copy(translatedText = text) else it })
    }
    fun deleteSelected(): PageBubbleDraft {
        val id = selected?.region?.id ?: return this
        return change(regions.filterNot { it.region.id == id }).copy(selectedId = null)
    }
    fun addBubble(): PageBubbleDraft {
        require(regions.size < 1000)
        val width = saved.width.toFloat(); val height = saved.height.toFloat()
        val bounds = PixelRect(width * .3f, height * .4f, width * .7f, height * .6f)
        val id = saved.pageId + ":manual:" + java.util.UUID.randomUUID()
        val bubble = PageTranslatedRegion(PageTextRegion(id, RegionKind.BUBBLE, bounds, emptyList(), "", emptyList()), "")
        return change(regions + bubble).select(id)
    }
    fun scaleFont(delta: Int): PageBubbleDraft {
        val id = selected?.region?.id ?: return this
        return change(regions.map { if (it.region.id == id) it.copy(fontScalePercent = (it.fontScalePercent + delta).coerceIn(25, 400)) else it })
    }
    fun beginTransform(id: String) = select(id).copy(gestureStart = regions)
    fun transform(id: String, bounds: PixelRect, rotation: Float): PageBubbleDraft {
        val original = (gestureStart ?: regions).firstOrNull { it.region.id == id } ?: return this
        val next = constrainBubbleBounds(bounds, saved.width, saved.height)
        val old = original.region.bounds
        fun point(p: PixelPoint) = PixelPoint(
            (next.left + (p.x - old.left) * next.width / old.width).coerceIn(0f, saved.width.toFloat()),
            (next.top + (p.y - old.top) * next.height / old.height).coerceIn(0f, saved.height.toFloat()))
        fun rect(r: PixelRect): PixelRect {
            val from = point(PixelPoint(r.left, r.top)); val to = point(PixelPoint(r.right, r.bottom))
            return PixelRect(from.x.coerceIn(0f, saved.width.toFloat()), from.y.coerceIn(0f, saved.height.toFloat()),
                to.x.coerceIn(0f, saved.width.toFloat()), to.y.coerceIn(0f, saved.height.toFloat()))
        }
        val changed = original.copy(region = original.region.copy(bounds = next, contour = original.region.contour.map(::point),
            textBounds = original.region.textBounds.map(::rect)), rotationDegrees = ((rotation + 180) % 360 + 360) % 360 - 180)
        val result = regions.map { if (it.region.id == id) changed else it }
        return if (gestureStart == null) change(result) else copy(regions = result)
    }
    fun finishTransform(): PageBubbleDraft {
        val before = gestureStart ?: return this
        return copy(gestureStart = null, undo = if (before == regions) undo else (undo + listOf(before)).takeLast(20))
    }
    fun undoChange(): PageBubbleDraft {
        val previous = undo.lastOrNull() ?: return this
        return copy(regions = previous, undo = undo.dropLast(1), gestureStart = null).select(selectedId)
    }
    fun discarded() = PageBubbleDraft(saved)
    private fun change(next: List<PageTranslatedRegion>) = if (next == regions) this else
        copy(regions = next, undo = (undo + listOf(regions)).takeLast(20))
}
