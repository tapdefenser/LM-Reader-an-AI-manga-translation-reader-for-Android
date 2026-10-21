package com.lmreader.tasks

import com.lmreader.core.model.ResourceGovernor
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Persist batches, while visible-cover metadata reads only take the common IO admission. */
class MetadataMaintenanceTasks(private val container: AppContainer) : TaskExecutor {
    init { container.taskScheduler.register("METADATA_FILL", this) }

    suspend fun admit(ids: List<String>): Int {
        if(ids.isEmpty()) return 0
        val selected = ids.distinct().sorted()
        val payload = JSONObject().put("mangaIds", JSONArray(selected)).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        val scheduler = container.taskScheduler
        val row = scheduler.submit("METADATA_FILL", "补全漫画元数据 · ${selected.size}", "metadata", "metadata:$digest",
            payload, priority = ResourceGovernor.MAINTENANCE)
        try {
            val rows = scheduler.tasks.first { list -> list.any { it.taskId == row.taskId &&
                it.state in TaskScheduler.TERMINAL + setOf("PAUSED", "WAITING", "INTERRUPTED") } }
            return rows.first { it.taskId == row.taskId }.checkpoint?.let { JSONObject(it).optInt("updated") } ?: 0
        } catch(cancelled: CancellationException) {
            withContext(NonCancellable) { scheduler.pause(row.taskId, cancel = true) }
            throw cancelled
        }
    }

    override suspend fun execute(execution: TaskExecution) {
        val array = JSONObject(execution.payload).getJSONArray("mangaIds")
        val ids = (0 until array.length()).map { array.getString(it) }
        val updated = container.backfillWorker.backfillDirect(ids) { done, changed ->
            execution.progress(done.toLong(), ids.size.toLong(), "补全元数据 $done/${ids.size}",
                JSONObject().put("updated", changed).toString())
        }
        execution.progress(ids.size.toLong(), ids.size.toLong(), "元数据补全完成 · $updated",
            JSONObject().put("updated", updated).toString())
    }
}
