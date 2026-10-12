package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray

class ScreenTranslationPreferences(private val context: Context) {
    val settings = context.preferenceStore.data.map { decode(it[KEY]) }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, ScreenTranslationSettings())
    suspend fun current() = decode(context.preferenceStore.data.first()[KEY])
    suspend fun update(change: (ScreenTranslationSettings) -> ScreenTranslationSettings) = context.preferenceStore.edit {
        it[KEY] = encode(change(decode(it[KEY])))
    }
    companion object {
        private val KEY = stringPreferencesKey("screen_translation_options_v1")
        fun encodeRegions(selection: ScreenRegionSelection): JSONObject {
            fun array(regions: List<ScreenNormalizedRegion>) = JSONArray().also { values -> regions.forEach { region ->
                values.put(JSONArray().put(region.left).put(region.top).put(region.right).put(region.bottom))
            } }
            return JSONObject().put("name", selection.name).put("portrait", array(selection.portrait)).put("landscape", array(selection.landscape))
        }
        fun decodeRegions(value: JSONObject?): ScreenRegionSelection {
            if (value == null) return ScreenRegionSelection()
            fun array(key: String): List<ScreenNormalizedRegion> {
                val values = value.optJSONArray(key) ?: return emptyList()
                require(values.length() <= 8)
                return (0 until values.length()).map { i -> val r = values.getJSONArray(i)
                    ScreenNormalizedRegion(r.getDouble(0).toFloat(), r.getDouble(1).toFloat(), r.getDouble(2).toFloat(), r.getDouble(3).toFloat()) }
            }
            return ScreenRegionSelection(value.optString("name", ""), array("portrait"), array("landscape"))
        }
        fun encode(s: ScreenTranslationSettings): String {
            val t = s.translation; val r = t.effectiveBubbleRender(BubbleRenderSettings())
            return JSONObject().put("version", 4).put("mode", s.mode.name).put("source", t.sourceLanguage).put("target", t.targetLanguage)
                .put("workflow", t.workflowId).put("api", t.apiProfileId).put("style", t.customStyle)
                .put("seg", t.effectiveSegThreshold()).put("det", t.effectiveTextDetectionThreshold()).put("merge", t.effectiveFreeTextMergeGapRatio())
                .put("scope", t.segTextScope.name).put("fill", r.fillMode.name).put("opacity", r.opacityPercent)
                .put("padding", r.textPaddingPercent).put("font", r.font.name).put("fontScale", r.fontScalePercent).put("bold", r.bold).put("expansion", r.freeTextMaskExpansionPercent)
                .put("segCount", s.vision.segConcurrency).put("ocrCount", s.vision.ocrConcurrency).put("segGpu", s.vision.segGpu).put("ocrBackend", s.vision.ocrBackend.name)
                .put("cache", s.cacheMegabytes).put("accessibility", s.preferAccessibility).put("sample", s.sampleIntervalMillis).put("settle", s.settleMillis)
                .put("clear", s.manualClearMillis).put("margin", s.offscreenMarginPercent).put("edge", s.maximumCaptureEdge).put("overlap", s.overlapPixels)
                .put("videoTop", s.videoTopPercent).put("videoBottom", s.videoBottomPercent).put("change", s.changeThresholdPercent)
                .put("followFps", s.followFramesPerSecond).put("minimumNewArea", s.minimumNewAreaPixels)
                .put("trackingAlgorithm", s.trackingAlgorithm.name)
                .put("browseMergeDistance", s.browseMergeDistancePixels)
                .put("browseRegions", encodeRegions(s.browseRegions)).put("videoRegions", encodeRegions(s.videoRegions))
                .put("regionPresets", JSONArray().also { values -> s.regionPresets.forEach { values.put(encodeRegions(it)) } }).toString()
        }
        fun decode(text: String?): ScreenTranslationSettings = runCatching {
            if (text == null) return ScreenTranslationSettings()
            val j = JSONObject(text)
            fun str(key: String) = j.optString(key).takeIf { it.isNotBlank() && it != "null" }
            val mode = j.optString("mode", "MANUAL").let { if (it == "MANGA") ScreenTranslationMode.BROWSE else ScreenTranslationMode.valueOf(it) }
            ScreenTranslationSettings(mode,
                MangaTranslationSettings(sourceLanguage = str("source"), targetLanguage = str("target"), workflowId = str("workflow"), apiProfileId = str("api"), customStyle = str("style"),
                    segThreshold = j.optDouble("seg", .35).toFloat(), textDetectionThreshold = j.optDouble("det", .35).toFloat(), freeTextMergeGapRatio = j.optDouble("merge", .45).toFloat(),
                    segTextScope = SegTextScope.valueOf(j.optString("scope", "FREE_TEXT")), bubbleFillMode = BubbleFillMode.valueOf(j.optString("fill", "AUTO")),
                    bubbleOpacityPercent = j.optInt("opacity", 85), bubbleTextPaddingPercent = j.optInt("padding", 7), bubbleFont = BubbleFont.valueOf(j.optString("font", "SYSTEM")),
                    bubbleFontScalePercent = j.optInt("fontScale", 100), bubbleBold = j.optBoolean("bold", false), freeTextMaskExpansionPercent = j.optInt("expansion", 6)),
                VisionExecutionSettings(j.optInt("segCount", 0), j.optInt("ocrCount", 0), j.optBoolean("segGpu", true), OcrBackend.valueOf(j.optString("ocrBackend", "CPU"))),
                j.optInt("cache", 128), j.optBoolean("accessibility", false), j.optInt("sample", 250), j.optInt("settle", 180), j.optInt("clear", 120),
                j.optInt("margin", 25), j.optInt("edge", 1600), j.optInt("overlap", 64), j.optInt("videoTop", 0), j.optInt("videoBottom", 100), j.optInt("change", 4),
                j.optInt("followFps", 30), j.optInt("minimumNewArea", 16),
                runCatching { ScreenTrackingAlgorithm.valueOf(j.optString("trackingAlgorithm", "GLOBAL_AND_REGIONS")) }
                    .getOrDefault(ScreenTrackingAlgorithm.GLOBAL_AND_REGIONS),
                decodeRegions(j.optJSONObject("browseRegions")), decodeRegions(j.optJSONObject("videoRegions")),
                j.optJSONArray("regionPresets")?.let { values -> (0 until values.length()).map { decodeRegions(values.getJSONObject(it)) } }.orEmpty(),
                j.optInt("browseMergeDistance", 8))
        }.getOrDefault(ScreenTranslationSettings())
    }
}
