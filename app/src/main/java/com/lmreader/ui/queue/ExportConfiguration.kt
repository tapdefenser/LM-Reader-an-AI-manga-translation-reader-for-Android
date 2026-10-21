package com.lmreader.ui.queue

import com.lmreader.core.model.*
import kotlinx.serialization.json.*

/** Captured when the user creates an export task, independent of subsequent reader settings. */
data class ExportConfiguration(val render: BubbleRenderSettings, val source: String? = null, val target: String? = null) {
    fun encode(): String = buildJsonObject {
        put("schema", 1); put("source", source?.let(::JsonPrimitive) ?: JsonNull); put("target", target?.let(::JsonPrimitive) ?: JsonNull)
        put("fillMode", render.fillMode.name); put("opacity", render.opacityPercent); put("padding", render.textPaddingPercent)
        put("font", render.font.name); put("fontScale", render.fontScalePercent); put("bold", render.bold)
        put("freeTextMaskExpansion", render.freeTextMaskExpansionPercent)
    }.toString()
    companion object {
        fun decode(value: String): ExportConfiguration {
            val json = Json.parseToJsonElement(value).jsonObject
            require(json.getValue("schema").jsonPrimitive.int == 1) { "Unsupported export configuration" }
            return ExportConfiguration(BubbleRenderSettings(BubbleFillMode.valueOf(json.getValue("fillMode").jsonPrimitive.content),
                json.getValue("opacity").jsonPrimitive.int, json.getValue("padding").jsonPrimitive.int,
                BubbleFont.valueOf(json.getValue("font").jsonPrimitive.content), json.getValue("fontScale").jsonPrimitive.int,
                json.getValue("bold").jsonPrimitive.boolean, json.getValue("freeTextMaskExpansion").jsonPrimitive.int),
                json["source"]?.jsonPrimitive?.contentOrNull, json["target"]?.jsonPrimitive?.contentOrNull)
        }
    }
}
