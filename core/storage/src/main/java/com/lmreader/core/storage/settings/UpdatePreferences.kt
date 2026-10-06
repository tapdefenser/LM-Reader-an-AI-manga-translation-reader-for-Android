package com.lmreader.core.storage.settings

import android.content.Context
import com.lmreader.core.model.UpdateCheckFrequency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class UpdatePreferences(context: Context) {
    val store = context.applicationContext.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    private val _frequency = MutableStateFlow(runCatching {
        UpdateCheckFrequency.valueOf(store.getString("frequency", UpdateCheckFrequency.DAILY.name)!!)
    }.getOrDefault(UpdateCheckFrequency.DAILY))
    val frequency = _frequency.asStateFlow()
    val lastAttempt get() = store.getLong("last-attempt", 0)
    fun attempted(at: Long) { store.edit().putLong("last-attempt", at).apply() }
    fun setFrequency(value: UpdateCheckFrequency) {
        store.edit().putString("frequency", value.name).apply(); _frequency.value = value
    }
}
