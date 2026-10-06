package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.BubbleFillMode
import com.lmreader.core.model.BubbleRenderSettings
import kotlinx.coroutines.flow.map

/** Same DataStore as the other application settings; edits use the latest persisted value. */
class BubbleRenderPreferences(private val context: Context) {
    val settings = context.preferenceStore.data.map(::decode)
    suspend fun update(transform: (BubbleRenderSettings) -> BubbleRenderSettings) = context.preferenceStore.edit { prefs ->
        val value=transform(decode(prefs))
        prefs[MODE]=value.fillMode.name; prefs[OPACITY]=value.opacityPercent; prefs[PADDING]=value.textPaddingPercent
    }
    suspend fun reset() = update { BubbleRenderSettings() }
    private fun decode(prefs: Preferences) = BubbleRenderSettings(
        runCatching { BubbleFillMode.valueOf(prefs[MODE].orEmpty()) }.getOrDefault(BubbleFillMode.AUTO),
        (prefs[OPACITY] ?: BubbleRenderSettings().opacityPercent).coerceIn(0,100), (prefs[PADDING] ?: 8).coerceIn(0,20))
    private companion object {
        val MODE=stringPreferencesKey("bubble_fill_mode_v1")
        val OPACITY=intPreferencesKey("bubble_opacity_v1")
        val PADDING=intPreferencesKey("bubble_padding_v1")
    }
}
