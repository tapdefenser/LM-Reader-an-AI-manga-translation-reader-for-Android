package com.lmreader.screen

import android.content.Context
import com.lmreader.core.database.ScreenBrowseDatabase
import com.lmreader.core.database.ScreenBrowseRegionEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.UUID

/** One writer preserves scene commit order. Following never performs disk writes. */
internal class ScreenBrowseSessionStore(context: Context) {
    val sessionId = UUID.randomUUID().toString()
    private val snapshots = Channel<List<ScreenBrowseRegionEntity>>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val database by lazy { ScreenBrowseDatabase.build(context) }
    private val writer = scope.launch {
        database.regions().clear()
        for (rows in snapshots) database.regions().replaceScene(sessionId, rows)
    }
    fun submit(rows: List<ScreenBrowseRegionEntity>) { snapshots.trySend(rows) }
    suspend fun rows() = database.regions().regions(sessionId)
}
