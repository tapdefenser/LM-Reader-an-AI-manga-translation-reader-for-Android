package com.lmreader.core.model

/** Geometry is in the EXIF-corrected analysis image, never in reader viewport coordinates. */
data class PageTextRegion(
    val id: String,
    val kind: RegionKind,
    val bounds: PixelRect,
    val contour: List<PixelPoint>,
    val sourceText: String,
    val textBounds: List<PixelRect>,
)

data class PageTranslatedRegion(val region: PageTextRegion, val translatedText: String,
    val fontScalePercent: Int = 100, val rotationDegrees: Float = 0f) {
    init { require(fontScalePercent in 25..400 && rotationDegrees.isFinite()) }
}

/** OCR text is metadata; only geometry changes require preparing masks from original pixels again. */
fun PageTextRegion.renderGeometry() = copy(sourceText = "")

enum class BubbleFillMode { AUTO, WHITE }
enum class BubbleFont { SYSTEM, SANS_SERIF, SERIF, MONOSPACE }

data class BubbleRenderSettings(
    val fillMode: BubbleFillMode = BubbleFillMode.AUTO,
    val opacityPercent: Int = 85,
    val textPaddingPercent: Int = 7,
    val font: BubbleFont = BubbleFont.SYSTEM,
    val fontScalePercent: Int = 100,
    val bold: Boolean = false,
    val freeTextMaskExpansionPercent: Int = 6,
) {
    init { require(opacityPercent in 0..100 && textPaddingPercent in 0..20 && fontScalePercent in 50..150 && freeTextMaskExpansionPercent in 0..20) }
}
