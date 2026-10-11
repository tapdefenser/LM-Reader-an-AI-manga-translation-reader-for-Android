package com.lmreader.core.storage.settings

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lmreader.core.model.MachineTranslationResources
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MachineTranslationPreferences(private val context: Context) {
    private fun read(p: Preferences) = MachineTranslationResources((p[COUNT] ?: 0).coerceIn(0, 8), (p[MEMORY] ?: 512).coerceIn(128, 4096))
    val settings = context.preferenceStore.data.map(::read).stateIn(CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly, MachineTranslationResources())
    suspend fun update(change: (MachineTranslationResources) -> MachineTranslationResources) = context.preferenceStore.edit {
        val next = change(read(it)); it[COUNT] = next.concurrency; it[MEMORY] = next.memoryMegabytes
    }
    private companion object { val COUNT = intPreferencesKey("machine_translation_concurrency"); val MEMORY = intPreferencesKey("machine_translation_memory_mb") }
}
