package com.lmreader.tasks

import com.lmreader.core.model.LibrarySource
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.core.model.ScanRunStatus
import com.lmreader.core.model.SourcePermissionState
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Durable admission for source-directory scans.
 *
 * [SourceScanRunner][com.lmreader.core.storage.scan.SourceScanRunner]'s per-source loop calls
 * [admit] instead of running the scan inline. The adapter submits one `SOURCE_SCAN` ledger row
 * and waits for its executor; that executor reloads the source and reconstructs the scan with
 * `runScanNow`, so an explicit TaskScreen resume works even after the original loop coroutine is
 * gone. The work closure passed to [admit] is deliberately ignored in production: running it
 * here would bypass the ledger, and calling `requestScan` from the executor would recurse.
 *
 * Construction must stay cheap. It only resolves [AppContainer.taskScheduler] (to register the
 * executor); `scanRunner` and `sourceRepository` are read inside [execute], after lazy wiring has
 * settled, so the runner's admission callback cannot recursively initialize itself.
 */
class SourceMaintenanceTasks(private val container: AppContainer) : TaskExecutor {

    init { container.taskScheduler.register(TYPE, this) }

    /**
     * Submits (or reuses) the row for this source/revision/force and waits for its durable
     * outcome. A PAUSED/WAITING/INTERRUPTED row resolves the wait without resuming itself: only
     * an explicit TaskScreen resume re-runs the executor. If the caller is cancelled (the
     * runner superseded the revision/force, or `cancelAll` ran), the matching task is cancelled
     * and its executor joined under [NonCancellable] before this function returns.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun admit(source: LibrarySource, force: Boolean, work: suspend () -> Unit) {
        val scheduler = container.taskScheduler
        scheduler.ready.await()
        val key = activeKey(source.sourceId, source.revision, force)
        var taskId: String? = null
        try {
            taskId = scheduler.submit(
                type = TYPE,
                title = "扫描来源 · ${source.displayName ?: source.displayPath}",
                ownerKey = "scan:${source.sourceId}",
                activeKey = key,
                payload = JSONObject()
                    .put("sourceId", source.sourceId)
                    .put("revision", source.revision)
                    .put("force", force)
                    .toString(),
                priority = ResourceGovernor.MAINTENANCE,
            ).taskId
            awaitOutcome(scheduler, taskId)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                // submit() may have committed the row just before cancellation removed the
                // coroutine; fall back to the unique active key so no orphan executor survives.
                val id = taskId ?: scheduler.dao.active(key)?.taskId
                if (id != null) scheduler.pause(id, cancel = true)
            }
            throw cancelled
        }
    }

    override suspend fun execute(execution: TaskExecution) {
        val payload = JSONObject(execution.payload)
        val sourceId = payload.getString("sourceId")
        val revision = payload.getLong("revision")
        val force = payload.optBoolean("force", false)

        val source = container.sourceRepository.getSource(sourceId)
            ?: throw TaskWaiting("ACCESS", "来源不存在或已被删除")
        if (source.revision != revision) {
            throw TaskWaiting("CONFIG_CHANGED", "来源配置已更新（revision=$revision），本次扫描已忽略")
        }

        execution.progress(0, 0, "正在扫描")
        ResourceGovernor.shared.use("io") { container.scanRunner.runScanNow(source, force) }

        // runScanNow() also returns after an obsolete revision or a failed scan. Never let that
        // fall through to TaskScheduler's implicit SUCCEEDED.
        val current = container.sourceRepository.getSource(sourceId)
            ?: throw TaskWaiting("ACCESS", "来源已在扫描期间删除")
        if (current.revision != revision) {
            throw TaskWaiting("CONFIG_CHANGED", "来源配置已更新（revision=$revision），本次扫描已忽略")
        }
        val state = container.scanRunner.states.value[sourceId]
            ?: throw IllegalStateException("扫描没有写入状态")
        when (state.status) {
            ScanRunStatus.COMPLETED -> {
                val discovered = state.discovered.toLong()
                // COMPLETED can still carry unreadable subpaths; surface that without turning a
                // completed scan into a failed task.
                val detail = state.lastError?.let { "；部分路径失败：$it" }.orEmpty()
                execution.progress(
                    discovered,
                    discovered,
                    "扫描完成：发现 ${state.discovered} 项，遍历 ${state.visited} 个目录$detail",
                )
            }

            ScanRunStatus.FAILED -> {
                val message = state.lastError ?: "扫描失败"
                // LOST/PARTIAL is recoverable by restoring directory access; other failures
                // (for example an index write failure) stay terminal so they are not hidden.
                if (current.permission == SourcePermissionState.LOST ||
                    current.permission == SourcePermissionState.PARTIAL
                ) {
                    throw TaskWaiting("ACCESS", message)
                }
                throw IllegalStateException(message)
            }

            ScanRunStatus.CANCELLED -> throw CancellationException("扫描已取消")
            ScanRunStatus.RUNNING, null -> throw IllegalStateException(
                "扫描没有返回终态（${state.status?.name ?: "无状态"}）",
            )
        }
    }

    /** No polling: `tasks` is the scheduler's StateFlow, so a terminal/paused row resolves it. */
    private suspend fun awaitOutcome(scheduler: TaskScheduler, taskId: String) {
        scheduler.tasks.first { rows ->
            val state = rows.firstOrNull { it.taskId == taskId }?.state
            state != null && state in STOPPED
        }
    }

    private fun activeKey(sourceId: String, revision: Long, force: Boolean): String =
        "scan:$sourceId:$revision:$force"

    private companion object {
        const val TYPE = "SOURCE_SCAN"

        /** Task-terminal states plus non-terminal states whose executor is no longer running. */
        val STOPPED: Set<String> = TaskScheduler.TERMINAL + setOf("PAUSED", "WAITING", "INTERRUPTED")
    }
}
