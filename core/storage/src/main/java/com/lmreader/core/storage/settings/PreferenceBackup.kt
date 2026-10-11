package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Use the live DataStore transaction, never replace its protobuf behind its cache. */
class PreferenceBackup(private val context: Context) {
    suspend fun snapshot(): JSONObject = JSONObject().apply {
        context.preferenceStore.data.first().asMap().forEach { (key, value) ->
            val type = when (value) { is String -> "string"; is Boolean -> "boolean"; is Int -> "int"; else -> error("Unsupported preference") }
            put(key.name, JSONObject().put("type", type).put("value", value))
        }
    }
    fun validate(json: JSONObject) {
        require(json.length() <= 1000)
        json.keys().forEach { key ->
            require(key.matches(Regex("[a-z0-9_]{1,100}")))
            val item = json.getJSONObject(key)
            require(types[key] == item.getString("type")) { "不支持的设置键或类型：$key" }
            when (item.getString("type")) {
                "string" -> require(item.get("value") is String && item.getString("value").length <= 1_000_000)
                "boolean" -> require(item.get("value") is Boolean)
                "int" -> require(item.get("value") is Number && item.getLong("value") in Int.MIN_VALUE..Int.MAX_VALUE)
                else -> error("Unsupported preference type")
            }
        }
    }
    private val types = buildMap {
        listOf("onboarding_completed", "chapter_sort_desc", "chapter_order_desc", "chapter_order_manual", "bookshelf_sort_desc",
            "translation_auto_detect_source", "vision_seg_gpu", "vision_ocr_hardware_acceleration").forEach { put(it, "boolean") }
        listOf("library_display_mode", "library_source_filter", "chapter_order_mode", "bookshelf_sort_mode", "translation_source_language",
            "translation_target_language", "translation_global_style", "bubble_fill_mode_v1", "reader_settings_v1", "vision_ocr_backend", "screen_translation_options_v1").forEach { put(it, "string") }
        listOf("bubble_opacity_v1", "bubble_padding_v1", "vision_seg_concurrency", "vision_ocr_concurrency", "translation_preprocess_cache_mb",
            "machine_translation_concurrency", "machine_translation_memory_mb").forEach { put(it, "int") }
    }
    suspend fun restore(json: JSONObject) {
        validate(json)
        context.preferenceStore.edit { prefs ->
            prefs.clear()
            json.keys().forEach { key ->
                val item = json.getJSONObject(key)
                when (item.getString("type")) {
                    "string" -> prefs[stringPreferencesKey(key)] = item.getString("value")
                    "boolean" -> prefs[booleanPreferencesKey(key)] = item.getBoolean("value")
                    "int" -> prefs[intPreferencesKey(key)] = item.getInt("value")
                }
            }
        }
    }
}
