package com.lmreader.core.model

/** 坐标始终是传入组件的、已处理 EXIF 方向的图片像素；右/下边界可等于宽/高。 */
data class PixelPoint(val x: Float, val y: Float)
data class PixelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init { require(listOf(left, top, right, bottom).all { it.isFinite() } && right >= left && bottom >= top) }
    val width get() = right - left
    val height get() = bottom - top
    val area get() = width * height
    fun offset(x: Float, y: Float) = PixelRect(left + x, top + y, right + x, bottom + y)
}

enum class RegionKind { BUBBLE, FREE_TEXT }
/** Manga translation options choose which text to extract; old settings retain both kinds. */
enum class SegTextScope(val label: String) {
    BUBBLES("气泡"), FREE_TEXT("游离文字"), ALL("气泡+游离文字");
    fun includes(kind: RegionKind) = this == ALL || (this == BUBBLES) == (kind == RegionKind.BUBBLE)
    companion object {
        fun fromValue(value: String?) = if (value == null) ALL else entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("无效的 SEG 提取范围：$value")
    }
}
data class SegRegion(val id: String, val kind: RegionKind, val bounds: PixelRect, val confidence: Float,
                     val contour: List<PixelPoint> = emptyList(), val extractionBounds: PixelRect? = null)
enum class LocalOcrLanguage(val label: String) {
    JAPANESE("日语"), ENGLISH("英语"), CHINESE_SIMPLIFIED("简体中文"), CHINESE_TRADITIONAL("繁体中文"), KOREAN("韩语")
}
data class OcrLine(val id: String, val bounds: PixelRect, val text: String, val confidence: Float)
data class DetectedTextLine(val bounds: PixelRect, val confidence: Float)
data class SegResult(val imageId: String, val width: Int, val height: Int, val regions: List<SegRegion>,
                     val elapsedMillis: Long, val backend: String, val textLines: List<DetectedTextLine> = emptyList())
data class LocalOcrResult(val imageId: String, val width: Int, val height: Int, val language: LocalOcrLanguage,
                          val lines: List<OcrLine>, val elapsedMillis: Long) {
    val text get() = lines.joinToString("\n") { it.text }
    val translationText get() = joinOcrText(lines.map { it.text }, language)
}
data class VisionProgress(val stage: String, val completed: Int, val total: Int)

/** 无文字是成功的空列表；资产、形状和推理错误必须抛出异常，不能伪装成空页。 */
class LocalVisionException(message: String, cause: Throwable? = null) : Exception(message, cause)
