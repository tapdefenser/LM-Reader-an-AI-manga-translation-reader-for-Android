package com.lmreader.tasks

import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.core.model.TaskContext
import com.lmreader.core.translation.asr.AsrWorkScheduler
import com.lmreader.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Admission adapter for ASR/NMT model installation.
 *
 * [schedule] never executes an import/download closure in the manager's own coroutine when a
 * durable scheduler is attached: it submits one `MODEL_INSTALL` row per reason, waits for the
 * executor, and reports the task result back to the manager. The closure itself is handed over
 * in memory; after process death the executor reconstructs the action from the persisted
 * operation/id/identity, and an import without a live closure becomes `WAITING: ACCESS`.
 *
 * The container must not access the model managers while constructing/registering this adapter.
 * The executor resolves them lazily only when a task actually runs; this avoids lazy-init
 * recursion through `AppContainer.asrModels`/`translationModels`.
 */
class ModelInstallationTasks(private val container: AppContainer) : AsrWorkScheduler, TaskExecutor {
    private data class Spec(val family: String, val operation: String, val modelId: String) {
        val ownerKey get() = "model:$family:$modelId"
    }

    private class Pending(val block: suspend () -> Any?) {
        val result = CompletableDeferred<Any?>()
    }

    /** Live import closures (and the download closures that share the same routing). */
    private val pending = ConcurrentHashMap<String, Pending>()

    init { container.taskScheduler.register(TYPE, this) }

    override suspend fun <T> schedule(reason: String, block: suspend () -> T): T =
        schedule(reason, null, block)

    override suspend fun <T> schedule(
        reason: String,
        expectedIdentity: String?,
        block: suspend () -> T,
    ): T {
        // Already inside a claimed installation task: run the action directly instead of
        // enqueueing a recursive second row.
        if (currentCoroutineContext()[TaskContext] != null) return block()

        container.taskScheduler.ready.await()
        val spec = spec(reason)
        val identity = expectedIdentity ?: currentIdentity(spec)
        val payload = payload(reason, spec, identity)
        val entry = Pending { block() }
        val running = pending.putIfAbsent(reason, entry)
        if (running != null) {
            @Suppress("UNCHECKED_CAST")
            return running.result.await() as T
        }

        var taskId: String? = null
        try {
            var record: TaskRecord? = container.taskScheduler.dao.active(reason)
            val existing = record
            if (existing != null && JSONObject(existing.payload).optString("expectedIdentity") != identity) {
                if (existing.state in IN_FLIGHT) {
                    error("该模型已有进行中的安装任务，请先在任务页处理")
                }
                // A stale paused/waiting goal must become terminal before the same activeKey
                // can represent the refreshed descriptor.
                withContext(NonCancellable) {
                    container.taskScheduler.pause(existing.taskId, cancel = true)
                }
                record = null
            }
            if (record == null) {
                record = container.taskScheduler.submit(
                    type = TYPE,
                    title = title(spec),
                    ownerKey = spec.ownerKey,
                    activeKey = reason,
                    payload = payload.toString(),
                    priority = ResourceGovernor.NORMAL,
                )
            } else {
                val row = record
                if (row.state in RESUMABLE) container.taskScheduler.resume(row.taskId)
            }
            val row = requireNotNull(record)
            taskId = row.taskId
            @Suppress("UNCHECKED_CAST")
            return awaitOutcome(row.taskId, entry) as T
        } catch (waiting: TaskWaiting) {
            // The task store records the durable wait; the manager should stop being busy but
            // must not overwrite the task with a pause.
            throw CancellationException(waiting.message ?: "模型安装等待条件")
        } catch (cancelled: CancellationException) {
            taskId?.let { settle(it) }
            throw cancelled
        } finally {
            pending.remove(reason, entry)
        }
    }

    override suspend fun pause(reason: String): Boolean = command(reason, cancel = false)

    override suspend fun cancel(reason: String): Boolean = command(reason, cancel = true)

    private suspend fun command(reason: String, cancel: Boolean): Boolean {
        container.taskScheduler.ready.await()
        val row = container.taskScheduler.dao.active(reason) ?: return false
        withContext(NonCancellable) {
            container.taskScheduler.pause(row.taskId, cancel = cancel)
        }
        return true
    }

    override suspend fun execute(execution: TaskExecution) {
        val payload = JSONObject(execution.payload)
        val reason = payload.optString("reason").ifBlank { execution.initial.activeKey.orEmpty() }
        // Keep the entry until the waiting manager removes it, so a duplicate schedule during
        // this run shares the same result instead of waiting forever on a fresh deferred.
        val entry = pending[reason]
        try {
            validate(payload)
            val result = entry?.block?.invoke() ?: recover(payload)
            entry?.result?.complete(result)
        } catch (cancelled: CancellationException) {
            entry?.result?.completeExceptionally(cancelled)
            throw cancelled
        } catch (failure: Exception) {
            entry?.result?.completeExceptionally(failure)
            throw failure
        }
    }

    /**
     * Downloads recover from the managers' public `downloadNow`; imports can only resume with a
     * live `InputStream` closure. Pretending a consumed/closed stream is resumable would create
     * a silent partial installation, so recovery asks the user to choose the file again.
     */
    private suspend fun recover(payload: JSONObject) {
        val spec = spec(payload.getString("reason"))
        val expected = payload.getString("expectedIdentity")
        when (spec.family) {
            FAMILY_ASR -> when (spec.operation) {
                OP_DOWNLOAD -> container.asrModels.downloadNow(spec.modelId)
                else -> throw TaskWaiting("ACCESS", "导入文件已失效，请重新选择文件")
            }
            FAMILY_NMT -> when (spec.operation) {
                OP_DOWNLOAD -> container.translationModels.downloadNow(spec.modelId, expected)
                else -> throw TaskWaiting("ACCESS", "导入文件已失效，请重新选择文件")
            }
            else -> error("未知模型类型：${spec.family}")
        }
    }

    private suspend fun validate(payload: JSONObject) {
        val reason = payload.getString("reason")
        val spec = spec(reason)
        val expected = payload.getString("expectedIdentity")
        when (spec.family) {
            FAMILY_ASR -> {
                val model = container.asrModels.model(spec.modelId)
                if (model == null || model.identity != expected ||
                    model.sha256 != payload.optString("expectedSha256") ||
                    model.bytes != payload.optLong("expectedBytes", -1L)) {
                    throw TaskWaiting("CAPABILITY", "ASR 模型目录已改变，请重新选择")
                }
            }
            FAMILY_NMT -> {
                // Recovery can be dispatched before the manager's DataStore/catalog init has
                // selected the source; wait for that first selection so the identity check and
                // the download use the same catalog.
                container.translationModels.awaitCatalogReady()
                if (container.translationModels.resolvePack(spec.modelId, expected) == null) {
                    throw TaskWaiting("CAPABILITY", "语言包目录已改变，请重新选择")
                }
            }
            else -> throw TaskWaiting("CAPABILITY", "未知模型类型：${spec.family}")
        }
    }

    private suspend fun <T> awaitOutcome(taskId: String, entry: Pending): T {
        return coroutineScope {
            val watcher = launch {
                container.taskScheduler.tasks.collect { rows ->
                    val row = rows.firstOrNull { it.taskId == taskId } ?: return@collect
                    when (row.state) {
                        "PAUSED", "WAITING", "INTERRUPTED", "CANCELLED" ->
                            entry.result.completeExceptionally(
                                CancellationException(row.failure ?: row.waitingReason ?: "模型安装已停止"))
                        "FAILED" -> entry.result.completeExceptionally(
                            IllegalStateException(row.failure ?: "模型安装失败"))
                    }
                }
            }
            try {
                @Suppress("UNCHECKED_CAST")
                entry.result.await() as T
            } finally {
                watcher.cancel()
            }
        }
    }

    /**
     * A TaskScreen pause/cancel resolves the caller's wait without a manager coroutine. When
     * the caller itself is cancelled, wait until the executor has released the file before the
     * import owner closes its stream. Never turn a durable WAITING task into PAUSED.
     */
    private suspend fun settle(taskId: String) = withContext(NonCancellable) {
        while (true) {
            val row = container.taskScheduler.dao.get(taskId) ?: return@withContext
            when (row.state) {
                "QUEUED", "RUNNING" -> {
                    container.taskScheduler.pause(taskId)
                    continue
                }
                "PAUSING", "CANCELLING" -> {
                    delay(20)
                    continue
                }
                else -> return@withContext
            }
        }
    }

    private fun currentIdentity(spec: Spec): String = when (spec.family) {
        FAMILY_ASR -> container.asrModels.model(spec.modelId)?.identity
            ?: error("未知 ASR 模型：${spec.modelId}")
        FAMILY_NMT -> container.translationModels.resolvePack(spec.modelId)?.identity
            ?: error("未知语言包：${spec.modelId}")
        else -> error("未知模型类型：${spec.family}")
    }

    private fun payload(reason: String, spec: Spec, identity: String): JSONObject {
        val json = JSONObject()
            .put("reason", reason)
            .put("family", spec.family)
            .put("operation", spec.operation)
            .put("modelId", spec.modelId)
            .put("expectedIdentity", identity)
        if (spec.family == FAMILY_ASR) {
            val model = container.asrModels.model(spec.modelId)
                ?: error("未知 ASR 模型：${spec.modelId}")
            json.put("expectedSha256", model.sha256).put("expectedBytes", model.bytes)
        }
        return json
    }

    private fun title(spec: Spec): String = when (spec.family) {
        FAMILY_ASR -> "ASR 模型安装 · ${spec.modelId}"
        FAMILY_NMT -> "本地翻译模型安装 · ${spec.modelId}"
        else -> "模型安装 · ${spec.modelId}"
    }

    private fun spec(reason: String): Spec {
        val separator = reason.indexOf(':')
        require(separator > 0 && separator < reason.lastIndex) { "模型安装任务原因无效：$reason" }
        val head = reason.substring(0, separator)
        val modelId = reason.substring(separator + 1)
        val family = when {
            head.startsWith("$FAMILY_ASR-") -> FAMILY_ASR
            head.startsWith("$FAMILY_NMT-") -> FAMILY_NMT
            else -> error("未知模型安装任务：$reason")
        }
        val operation = head.substringAfter('-')
        require(operation == OP_DOWNLOAD || operation == OP_IMPORT) {
            "未知模型安装操作：$reason"
        }
        return Spec(family, operation, modelId)
    }

    companion object {
        const val TYPE = "MODEL_INSTALL"
        private const val FAMILY_ASR = "asr"
        private const val FAMILY_NMT = "nmt"
        private const val OP_DOWNLOAD = "download"
        private const val OP_IMPORT = "import"
        private val IN_FLIGHT = setOf("QUEUED", "RUNNING", "PAUSING", "CANCELLING")
        private val RESUMABLE = setOf("PAUSED", "WAITING", "INTERRUPTED")
    }
}
