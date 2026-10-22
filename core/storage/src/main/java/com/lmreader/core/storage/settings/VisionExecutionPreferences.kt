package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.VisionExecutionSettings
import com.lmreader.core.model.OcrBackend
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class VisionExecutionPreferences(private val context: Context) {
    val settings = context.preferenceStore.data.map { read(it) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, VisionExecutionSettings())
    suspend fun update(change: (VisionExecutionSettings) -> VisionExecutionSettings) = context.preferenceStore.edit { p ->
        write(p, change(read(p)))
    }
    internal companion object {
        private val SEG_COUNT = intPreferencesKey("vision_seg_concurrency")
        private val OCR_COUNT = intPreferencesKey("vision_ocr_concurrency")
        private val SEG_GPU = booleanPreferencesKey("vision_seg_gpu")
        private val OCR_ACCEL = booleanPreferencesKey("vision_ocr_hardware_acceleration")
        private val OCR_BACKEND = stringPreferencesKey("vision_ocr_backend")
        fun read(p: Preferences): VisionExecutionSettings {
            val legacy = if (p[SEG_COUNT] == null || p[OCR_COUNT] == null)
                ScreenTranslationPreferences.decode(p[ScreenTranslationPreferences.KEY]).vision else null
            return VisionExecutionSettings(
                segConcurrency = (p[SEG_COUNT] ?: legacy?.segConcurrency ?: 0).coerceIn(0, 8),
                ocrConcurrency = (p[OCR_COUNT] ?: legacy?.ocrConcurrency ?: 0).coerceIn(0, 8),
                segGpu = p[SEG_GPU] ?: true,
                ocrBackend = OcrBackend.entries.firstOrNull { it.name == p[OCR_BACKEND] }
                    ?: if (p[OCR_ACCEL] == true) OcrBackend.AUTO else OcrBackend.CPU)
        }
        fun write(p: MutablePreferences, next: VisionExecutionSettings) {
            p[SEG_COUNT] = next.segConcurrency; p[OCR_COUNT] = next.ocrConcurrency
            p[SEG_GPU] = next.segGpu; p[OCR_BACKEND] = next.ocrBackend.name
            p.remove(OCR_ACCEL)
        }
    }
}
