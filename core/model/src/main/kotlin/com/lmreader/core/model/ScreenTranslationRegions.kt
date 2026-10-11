package com.lmreader.core.model

data class ScreenNormalizedRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } && right > left && bottom > top) }
    fun pixels(display: PixelRect) = PixelRect(display.left + left * display.width, display.top + top * display.height,
        display.left + right * display.width, display.top + bottom * display.height)
}

/** A named preset stores both orientations. Modes hold independent copies of
 * the selected preset; saving a preset does not silently edit another mode. */
data class ScreenRegionSelection(val name: String = "", val portrait: List<ScreenNormalizedRegion> = emptyList(),
    val landscape: List<ScreenNormalizedRegion> = emptyList()) {
    init { require(name.length <= 80 && portrait.size <= 8 && landscape.size <= 8) }
    fun areas(display: PixelRect, window: PixelRect): List<PixelRect> {
        val regions = if (display.width > display.height) landscape else portrait
        if (regions.isEmpty()) return listOf(window)
        val result = ArrayList<PixelRect>()
        for (r in regions) {
            val p = r.pixels(display)
            if (!p.intersects(window)) continue
            val clipped = PixelRect(maxOf(p.left, window.left), maxOf(p.top, window.top), minOf(p.right, window.right), minOf(p.bottom, window.bottom))
            result += screenExposedRegions(clipped, result)
        }
        return result
    }
}

fun ScreenTranslationSettings.regionSelection(mode: ScreenTranslationMode = this.mode) =
    if (mode == ScreenTranslationMode.VIDEO) videoRegions else browseRegions

fun ScreenTranslationSettings.withRegionSelection(mode: ScreenTranslationMode, selection: ScreenRegionSelection) =
    if (mode == ScreenTranslationMode.VIDEO) copy(videoRegions = selection, videoTopPercent = 0, videoBottomPercent = 100)
    else copy(browseRegions = selection)
