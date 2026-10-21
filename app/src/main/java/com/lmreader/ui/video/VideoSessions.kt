package com.lmreader.ui.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Restore and cache cleanup wait for native work and publication to finish. */
class VideoSessions {
    private data class Entry(val stop: suspend () -> Unit, val reload: () -> Unit)
    private val entries = linkedMapOf<String, Entry>()
    fun register(stop: suspend () -> Unit, reload: () -> Unit): String = UUID.randomUUID().toString().also { entries[it] = Entry(stop, reload) }
    fun remove(key: String) { entries.remove(key) }
    suspend fun stopAndAwait() = withContext(Dispatchers.Main.immediate) { entries.values.toList().forEach { it.stop() } }
    suspend fun reload() = withContext(Dispatchers.Main.immediate) { entries.values.toList().forEach { it.reload() } }
}
