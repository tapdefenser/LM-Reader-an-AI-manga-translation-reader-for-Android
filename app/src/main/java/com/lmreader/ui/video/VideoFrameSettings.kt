package com.lmreader.ui.video

import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.BubbleFont
import com.lmreader.core.model.BubbleRenderSettings
import com.lmreader.core.model.DEFAULT_FREE_TEXT_MERGE_GAP_RATIO
import org.json.JSONObject
import java.util.Locale

/**
 * Per-video overrides for frame recognition, region grouping and bubble rendering.
 *
 * These values live in the saved [VideoWorkOptions] draft. Saving options never rewrites work
 * that is already queued or running; a newly created frame task captures the saved values.
 */
data class VideoFrameSettings(
    val segThreshold: Float = .35f,
    val textDetectionThreshold: Float = .35f,
    val freeTextMergeGapRatio: Float = DEFAULT_FREE_TEXT_MERGE_GAP_RATIO,
    val similarityPercent: Int = 60,
    val render: BubbleRenderSettings = BubbleRenderSettings(),
) {
    init {
        require(segThreshold.isFinite() && segThreshold in 0f..1f) {
            "segThreshold 必须在 0..1 之间"
        }
        require(textDetectionThreshold.isFinite() && textDetectionThreshold in 0f..1f) {
            "textDetectionThreshold 必须在 0..1 之间"
        }
        require(freeTextMergeGapRatio.isFinite() && freeTextMergeGapRatio in 0f..2f) {
            "freeTextMergeGapRatio 必须在 0..2 之间"
        }
        require(similarityPercent in 1..100) {
            "similarityPercent 必须在 1..100 之间"
        }
    }

    fun json(): JSONObject = JSONObject()
        .put("segThreshold", segThreshold.toDouble())
        .put("textDetectionThreshold", textDetectionThreshold.toDouble())
        .put("freeTextMergeGapRatio", freeTextMergeGapRatio.toDouble())
        .put("similarityPercent", similarityPercent)
        .put("render", render.toJson())

    companion object {
        /**
         * Missing keys keep the compatible legacy defaults above. A key that is present but
         * malformed or outside the accepted range fails loudly instead of being silently clamped.
         */
        fun read(json: JSONObject): VideoFrameSettings = VideoFrameSettings(
            segThreshold = json.floatValue("segThreshold", .35f),
            textDetectionThreshold = json.floatValue("textDetectionThreshold", .35f),
            freeTextMergeGapRatio = json.floatValue("freeTextMergeGapRatio", DEFAULT_FREE_TEXT_MERGE_GAP_RATIO),
            similarityPercent = json.intValue("similarityPercent", 60),
            render = json.renderSettings(),
        )
    }
}

private fun BubbleRenderSettings.toJson(): JSONObject = JSONObject()
    .put("fillMode", fillMode.name)
    .put("opacityPercent", opacityPercent)
    .put("textPaddingPercent", textPaddingPercent)
    .put("font", font.name)
    .put("fontScalePercent", fontScalePercent)
    .put("bold", bold)
    .put("freeTextMaskExpansionPercent", freeTextMaskExpansionPercent)

private fun JSONObject.renderSettings(): BubbleRenderSettings {
    val render = renderObject()
    return BubbleRenderSettings(
        fillMode = BubbleFillMode.valueOf(
            render.enumValue("fillMode", BubbleFillMode.AUTO.name, BubbleFillMode.entries.map { it.name }),
        ),
        opacityPercent = render.intValue("opacityPercent", 85),
        textPaddingPercent = render.intValue("textPaddingPercent", 7),
        font = BubbleFont.valueOf(
            render.enumValue("font", BubbleFont.SYSTEM.name, BubbleFont.entries.map { it.name }),
        ),
        fontScalePercent = render.intValue("fontScalePercent", 100),
        bold = render.booleanValue("bold", false),
        freeTextMaskExpansionPercent = render.intValue("freeTextMaskExpansionPercent", 6),
    )
}

private fun JSONObject.renderObject(): JSONObject {
    if (!has("render") || isNull("render")) return JSONObject()
    return get("render") as? JSONObject ?: throw IllegalArgumentException("render 必须是对象")
}

private fun JSONObject.floatValue(key: String, fallback: Float): Float {
    if (!has(key) || isNull(key)) return fallback
    val value = (get(key) as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$key 必须是数字")
    if (!value.isFinite()) throw IllegalArgumentException("$key 必须是有限数字")
    return value.toFloat()
}

private fun JSONObject.intValue(key: String, fallback: Int): Int {
    if (!has(key) || isNull(key)) return fallback
    val value = (get(key) as? Number)?.toDouble()
        ?: throw IllegalArgumentException("$key 必须是整数")
    if (!value.isFinite() || value != value.toInt().toDouble()) {
        throw IllegalArgumentException("$key 必须是整数")
    }
    return value.toInt()
}

private fun JSONObject.booleanValue(key: String, fallback: Boolean): Boolean {
    if (!has(key) || isNull(key)) return fallback
    return get(key) as? Boolean ?: throw IllegalArgumentException("$key 必须是布尔值")
}

private fun JSONObject.enumValue(key: String, fallback: String, choices: List<String>): String {
    if (!has(key) || isNull(key)) return fallback
    val raw = get(key) as? String ?: throw IllegalArgumentException("$key 必须是字符串")
    val value = raw.trim().uppercase(Locale.ROOT)
    if (value !in choices) throw IllegalArgumentException("$key 取值无效: $raw")
    return value
}
