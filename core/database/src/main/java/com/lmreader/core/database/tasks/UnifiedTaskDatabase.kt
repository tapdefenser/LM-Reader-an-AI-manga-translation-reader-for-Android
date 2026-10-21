package com.lmreader.core.database.tasks

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Durable work intents. Business payloads and publication journals remain with their owners. */
@Entity(tableName = "tasks", indices = [Index(value = ["activeKey"], unique = true),
    Index(value = ["state", "priority", "orderIndex"]), Index("parentTaskId"), Index("ownerKey")])
data class TaskRecord(
    @PrimaryKey val taskId: String,
    val type: String,
    val title: String,
    val ownerKey: String,
    val activeKey: String?,
    val payload: String,
    val parentTaskId: String? = null,
    val source: String = "USER",
    val priority: Int = 0,
    val orderIndex: Long = 0,
    val state: String = "QUEUED",
    val userPaused: Boolean = false,
    val pauseReason: String? = null,
    val generation: Long = 0,
    val checkpoint: String = "{}",
    val completed: Long = 0,
    val total: Long = 0,
    val stage: String = "",
    val waitingReason: String? = null,
    val failure: String? = null,
    val remoteOutcomeUnknown: Boolean = false,
    val recoverable: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Entity(tableName = "task_requests", indices = [Index(value = ["taskId", "scopeKey", "status"])])
data class TaskRequestRecord(@PrimaryKey val requestId: String, val taskId: String, val generation: Long,
    val scopeKey: String, val status: String)

@Dao
abstract class TaskDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun addRequest(request: TaskRequestRecord)
    @Query("UPDATE task_requests SET status=:status WHERE requestId=:id") abstract suspend fun requestStatus(id: String, status: String): Int
    @Query("SELECT * FROM task_requests WHERE requestId=:id") abstract suspend fun request(id: String): TaskRequestRecord?
    @Query("UPDATE task_requests SET status='PUBLISHED' WHERE taskId=:taskId AND scopeKey=:scopeKey AND status='RECEIVED'")
    abstract suspend fun commitRequests(taskId: String, scopeKey: String)
    @Query("UPDATE task_requests SET status='RETRY_AUTHORIZED' WHERE taskId=:taskId AND status IN ('SENT','RECEIVED','UNKNOWN')")
    abstract suspend fun authorizeRequestRetry(taskId: String)
    @Query("SELECT COUNT(*) FROM task_requests WHERE taskId=:taskId AND status='SENT'")
    abstract suspend fun inFlightRequests(taskId: String): Int
    @Query("UPDATE task_requests SET status='PUBLISHED' WHERE taskId=:taskId AND status='RECEIVED'") abstract suspend fun commitAllRequests(taskId: String)
    @Query("SELECT COUNT(*) FROM task_requests WHERE taskId=:id AND status IN ('SENT','RECEIVED','UNKNOWN')") abstract suspend fun unresolvedRequests(id: String): Int
    @Query("UPDATE tasks SET remoteOutcomeUnknown=:unknown WHERE taskId=:id") abstract suspend fun requestUncertainty(id: String, unknown: Boolean)
    @Query("SELECT * FROM tasks ORDER BY priority DESC, orderIndex, createdAt, taskId")
    abstract fun observe(): Flow<List<TaskRecord>>
    @Query("SELECT * FROM tasks ORDER BY priority DESC, orderIndex, createdAt, taskId")
    abstract suspend fun all(): List<TaskRecord>
    @Query("SELECT * FROM tasks WHERE taskId=:id") abstract suspend fun get(id: String): TaskRecord?
    @Query("SELECT * FROM tasks WHERE activeKey=:key LIMIT 1") abstract suspend fun active(key: String): TaskRecord?
    @Insert(onConflict = OnConflictStrategy.IGNORE) abstract suspend fun insert(row: TaskRecord): Long
    @Update abstract suspend fun update(row: TaskRecord)
    @Query("UPDATE tasks SET state=:state, activeKey=CASE WHEN :terminal THEN NULL ELSE activeKey END, failure=:failure, waitingReason=:reason, updatedAt=:now WHERE taskId=:id AND generation=:generation AND state='RUNNING'")
    abstract suspend fun finish(id: String, generation: Long, state: String, terminal: Boolean,
        failure: String?, reason: String?, now: Long): Int
    @Query("UPDATE tasks SET checkpoint=COALESCE(:checkpoint,checkpoint),completed=:completed,total=:total,stage=:stage,updatedAt=:now WHERE taskId=:id AND generation=:generation AND state='RUNNING'")
    abstract suspend fun progress(id: String, generation: Long, checkpoint: String?, completed: Long,
        total: Long, stage: String, now: Long): Int
    @Query("UPDATE tasks SET remoteOutcomeUnknown=:unknown WHERE taskId=:id AND generation=:generation")
    abstract suspend fun remoteOutcome(id: String, generation: Long, unknown: Boolean)
    @Query("UPDATE tasks SET waitingReason=:reason WHERE taskId=:id AND generation=:generation AND state='RUNNING'")
    abstract suspend fun waiting(id: String, generation: Long, reason: String?)
    @Query("UPDATE tasks SET state=CASE WHEN state='CANCELLING' THEN 'CANCELLED' WHEN userPaused THEN 'PAUSED' ELSE 'INTERRUPTED' END, activeKey=CASE WHEN state='CANCELLING' THEN NULL ELSE activeKey END, waitingReason='SYSTEM_INTERRUPTED',generation=generation+1,updatedAt=:now WHERE state IN ('RUNNING','PAUSING','CANCELLING')")
    abstract suspend fun interruptLeases(now: Long)
    @Transaction open suspend fun submit(row: TaskRecord): TaskRecord {
        row.activeKey?.let { active(it)?.let { existing -> return existing } }
        if (insert(row) == -1L) return requireNotNull(row.activeKey?.let { active(it) })
        return row
    }
    @Transaction open suspend fun claim(id: String): TaskRecord? {
        val row = get(id) ?: return null
        if (row.state != "QUEUED" || row.userPaused) return null
        return row.copy(state = "RUNNING", generation = row.generation + 1,
            waitingReason = null, failure = null, updatedAt = System.currentTimeMillis()).also { update(it) }
    }
}

/** Kept separate from the manga schema: installing this feature never rewrites manga tables. */
@Database(entities = [TaskRecord::class, TaskRequestRecord::class], version = 1, exportSchema = true)
abstract class UnifiedTaskDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    companion object {
        fun build(context: Context) = Room.databaseBuilder(context.applicationContext,
            UnifiedTaskDatabase::class.java, "unified-tasks.db").build()
    }
}
