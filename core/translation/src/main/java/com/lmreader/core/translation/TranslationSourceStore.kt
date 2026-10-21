package com.lmreader.core.translation

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.*
import kotlinx.coroutines.flow.map
import java.io.File

class TranslationSourceStore(context: Context) {
    private val store = PreferenceDataStoreFactory.create(produceFile = {
        File(context.applicationContext.noBackupFilesDir, "translation_sources.preferences_pb")
    })
    val settings = store.data.map { prefs -> TranslationSourceSettings(
        TranslationDownloadSource.fromStoredValue(prefs[SOURCE]),
        prefs[BASE] ?: "", prefs[WIFI] ?: true,
        TranslationModelEngine.fromStoredValueOrDefault(prefs[ENGINE]),
    ) }
    suspend fun save(settings: TranslationSourceSettings) {
        val base = if (settings.source == TranslationDownloadSource.CUSTOM) TranslationModelCatalog.validateCustomBase(settings.customBaseUrl) else settings.customBaseUrl.trim()
        store.edit { it[SOURCE] = settings.source.name; it[BASE] = base; it[WIFI] = settings.wifiOnly; it[ENGINE] = settings.engine.name }
    }
    private companion object {
        val SOURCE = stringPreferencesKey("source")
        val BASE = stringPreferencesKey("base")
        val WIFI = booleanPreferencesKey("wifi_only")
        val ENGINE = stringPreferencesKey("engine")
    }
}
