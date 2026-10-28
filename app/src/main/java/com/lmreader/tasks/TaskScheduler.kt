package com.lmreader.tasks

import com.lmreader.core.database.tasks.*
import androidx.room.withTransaction
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class TaskWaiting(val reason: String, message: String, cause: Throwable? = null) : Exception(message, cause)
/** A completed safe unit yields the same durable intent to the dispatcher. */
class TaskRequeue(val resultConflict: Boolean = false) : com.lmreader.core.workflow.WorkflowRestartRequested()
fun interface TaskExecutor { suspend fun execute(execution: TaskExecution) }

class TaskExecution internal constructor(val initial: TaskRecord, private val scheduler: TaskScheduler) {
    val taskId get() = initial.taskId
    val payload get() = initial.payload
    suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        val row = scheduler.dao.get(taskId)
        check(row?.generation == initial.generation && row.state == "RUNNING") { "任务执行代次已失效" }
    }
    /** Call inside business publication's own CAS/commit lock, before publishing. */
    suspend fun <T> publish(action: suspend () -> T): T = scheduler.lock(taskId).withLock {
        checkActive(); action()
    }
    suspend fun progress(completed: Long, total: Long, stage: String, checkpoint: String? = null) {
        checkActive()
        scheduler.dao.progress(taskId, initial.generation, checkpoint, completed, total, stage, System.currentTimeMillis())
    }
    suspend fun remoteOutcome(unknown: Boolean) { scheduler.dao.remoteOutcome(taskId, initial.generation, unknown) }
    fun networkStage(active: Boolean) { scheduler.networkStage(taskId, active) }
    suspend fun commitScope(scope: String) { scheduler.remoteEvent(initial, "", scope, "COMMITTED") }
}

/** Sole dispatcher for migrated types; view models only submit commands and observe this ledger. */
class TaskScheduler(private val container: AppContainer,
    private val databaseOverride: com.lmreader.core.database.media.MediaTaskDatabase? = null,
    private val scopeOverride: CoroutineScope? = null,
    private val startup: Deferred<Unit> = container.startupReady,
    private val canStart: () -> Boolean = { container.taskService.canStart() },
    private val acquire: suspend (Boolean) -> String? = { container.taskService.acquire(it) },
    private val release: (String) -> Unit = container.taskService::release,
    private val preferences: android.content.SharedPreferences = container.applicationContext.getSharedPreferences("unified-task-settings", 0),
) {
    val database get() = databaseOverride ?: container.workDatabase
    internal val dao get() = database.tasks()
    private val scope = scopeOverride ?: container.backgroundScope
    private val executors = ConcurrentHashMap<String, TaskExecutor>()
    private val externalHosts = ConcurrentHashMap<String, () -> Boolean>()
    private val active = ConcurrentHashMap<String, Job>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val commandLocks = ConcurrentHashMap<String, Mutex>()
    private val dispatchGate = Mutex()
    internal val translationCommands = Mutex()
    @Volatile private var translationAdmissionHeld = false
    private val barrierGate = Mutex()
    @Volatile private var barrier = false
    internal fun lock(id: String) = locks.computeIfAbsent(id) { Mutex() }
    private val wake = MutableStateFlow(0L)
    private val settings = preferences
    private val mutableOrderMode = MutableStateFlow(settings.getString("order-mode", null)
        ?: container.queueOrder.schedulingPriority().name)
    val orderMode = mutableOrderMode.asStateFlow()
    private val mutableTasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val tasks = mutableTasks.asStateFlow()
    private val mutableInitializationFailure = MutableStateFlow<String?>(null)
    val initializationFailure = mutableInitializationFailure.asStateFlow()
    val ready = CompletableDeferred<Unit>()
    init {
        scope.launch {
            try {
            startup.await()
            dao.interruptLeases(System.currentTimeMillis())
            migrateLegacyPauseFlags(initial = true)
            // Only system-interrupted, safely restartable tasks resume. Explicit pauses remain durable.
            dao.all().filter { it.state == "INTERRUPTED" && !it.userPaused && it.recoverable && !it.remoteOutcomeUnknown }
                .forEach { dao.update(it.copy(state = "QUEUED")) }
            ready.complete(Unit)
            dao.observe().collect { mutableTasks.value = it; reconcileParents(it); wake.update { v -> v + 1 } }
            } catch(cancelled: CancellationException) { ready.cancel(cancelled); throw cancelled }
            catch(error: Exception) {
                mutableInitializationFailure.value = "任务数据初始化失败：${error.message ?: error.javaClass.simpleName}"
                // Release UI waiters, while keeping admission closed until a successful restart.
                ready.complete(Unit)
            }
        }
        scope.launch {
            ready.await()
            wake.collect { dispatchGate.withLock { if(!barrier) dispatch() } }
        }
    }
    fun register(type: String, executor: TaskExecutor) { executors[type] = executor; poke() }
    /** Projection/playback own platform-specific services; they still share this ledger and admission. */
    fun registerExternal(type: String, available: () -> Boolean, executor: TaskExecutor) {
        externalHosts[type] = available; register(type, executor)
    }
    private suspend fun reconcileParents(rows: List<TaskRecord>) {
        for(id in rows.filter { it.type == "VIDEO" }.map { it.taskId }) lock(id).withLock { database.withTransaction {
            val parent = dao.get(id) ?: return@withTransaction
            if(parent.state in setOf("CANCELLED", "PAUSING", "CANCELLING") || parent.userPaused) return@withTransaction
            val children = dao.all().filter { it.parentTaskId == parent.taskId }
            if(children.isEmpty()) return@withTransaction
            val states = children.map { it.state }
            val state = when {
                states.all { it == "SUCCEEDED" } -> "SUCCEEDED"
                states.any { it == "RUNNING" } -> "RUNNING"
                states.any { it == "QUEUED" } -> "QUEUED"
                states.all { it in TERMINAL } -> if(states.any { it == "FAILED" }) "FAILED" else "CANCELLED"
                states.all { it == "PAUSED" } -> "PAUSED"
                else -> "WAITING"
            }
            val goal = parent.activeKey ?: org.json.JSONObject(parent.payload).optString("_goalKey").takeIf { it.isNotBlank() }
            val activeGoal = goal?.let { dao.active(it) }
            val next = parent.copy(state = state, completed = children.sumOf { it.completed },
                total = if(children.all { it.total > 0 }) children.sumOf { it.total } else 0,
                stage = children.joinToString(" · ") { "${it.type.removePrefix("VIDEO_")} ${taskStateLabel(it.state)}" },
                failure = children.filter { it.state == "FAILED" }.joinToString("；") { it.failure.orEmpty() }.takeIf { it.isNotBlank() },
                activeKey = if(state in TERMINAL) null else goal?.takeIf { activeGoal == null || activeGoal.taskId == parent.taskId })
            if(next != parent) dao.update(next)
        } }
    }
    fun poke() { wake.update { it + 1 } }
    internal fun networkStage(taskId: String, active: Boolean) { container.taskService.networkStage(taskId, active) }
    suspend fun submit(type: String, title: String, ownerKey: String, activeKey: String,
        payload: String, priority: Int = 0, parentTaskId: String? = null, recoverable: Boolean = true,
        userPaused: Boolean = false): TaskRecord {
        ready.await()
        check(initializationFailure.value == null) { initializationFailure.value.orEmpty() }
        val savedPayload = org.json.JSONObject(payload).put("_goalKey", activeKey).toString()
        val held = userPaused
        return dao.submit(TaskRecord(UUID.randomUUID().toString(), type, title, ownerKey, activeKey, savedPayload,
            parentTaskId = parentTaskId, priority = priority, orderIndex = System.currentTimeMillis(),
            recoverable = recoverable, state = if (held) "PAUSED" else "QUEUED", userPaused = held,
            pauseReason = if(userPaused) "USER" else null)).also { poke() }
    }
    private suspend fun dispatch() {
        if(initializationFailure.value != null) return
        applyTranslationOrder()
        val rows = dao.all()
        val orderedRoot = if(orderMode.value == "ORDER") rows.firstOrNull {
            it.parentTaskId == null && it.state !in TERMINAL && !it.userPaused && it.priority == 0 &&
                !(it.isTranslation() && translationAdmissionHeld)
        } else null
        val occupied = rows.filter { active.containsKey(it.taskId) }.map { it.ownerKey }.toMutableSet()
        for (candidate in rows) {
            if(orderedRoot != null && candidate.priority == 0 &&
                (if(orderedRoot.isTranslation()) !candidate.isTranslation() || candidate.translationGroupKey() != orderedRoot.translationGroupKey()
                else (candidate.parentTaskId ?: candidate.taskId) != orderedRoot.taskId)) continue
            if(externalHosts[candidate.type]?.invoke() != true && !canStart()) continue
            if (active.size >= 8 && candidate.priority < ResourceGovernor.INTERACTIVE) continue
            if (candidate.priority >= ResourceGovernor.INTERACTIVE && rows.count { active.containsKey(it.taskId) && it.priority >= ResourceGovernor.INTERACTIVE } >= 2) continue
            if (candidate.state != "QUEUED" || candidate.userPaused || active.containsKey(candidate.taskId) || candidate.ownerKey in occupied) continue
            if (candidate.isTranslation() && translationAdmissionHeld) continue
            val executor = executors[candidate.type] ?: continue
            val claimed = dao.claim(candidate.taskId) ?: continue
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var lease: String? = null
                try {
                    if(claimed.type != "MEDIA_SCAN" && !externalHosts.containsKey(claimed.type)) {
                        lease = try { acquire(claimed.type in setOf("VIDEO_FRAMES", "VIDEO_SUBTITLES")) }
                        catch(error: TimeoutCancellationException) { throw TaskWaiting("SYSTEM_LIMIT", "后台服务启动超时，请打开应用后继续任务") }
                        catch(cancelled: CancellationException) { throw cancelled }
                        catch(error: Exception) { throw TaskWaiting("SYSTEM_LIMIT", error.message ?: "系统暂时不允许启动后台服务，请打开应用后继续任务") }
                    }
                    lease?.let { container.taskService.bindTask(it, claimed.taskId) }
                    val context = TaskContext(claimed.taskId, claimed.generation, claimed.priority, onWaiting = { reason ->
                        dao.waiting(claimed.taskId, claimed.generation, reason)
                    }, onRemoteRequest = { id, scope, status -> remoteEvent(claimed, id, scope, status) })
                    withContext(context + InferencePriority(claimed.priority)) { executor.execute(TaskExecution(claimed, this@TaskScheduler)) }
                    remoteEvent(claimed, "", "*", "TASK_COMMITTED")
                    dao.finish(claimed.taskId, claimed.generation, "SUCCEEDED", true, null, null, System.currentTimeMillis())
                } catch (yielded: TaskRequeue) {
                    val uncertain = database.withTransaction {
                        val current = dao.get(claimed.taskId)
                        if (yielded.resultConflict && current?.generation == claimed.generation &&
                            current.state == "RUNNING" && !current.userPaused) {
                            // User policy: a result conflict restarts the attempt even after an unknown response.
                            // Children have joined at this boundary; completed pages remain reusable.
                            dao.authorizeRequestRetry(claimed.taskId)
                            dao.remoteOutcome(claimed.taskId, claimed.generation, false)
                        }
                        dao.get(claimed.taskId)?.remoteOutcomeUnknown == true
                    }
                    dao.finish(claimed.taskId, claimed.generation, if(uncertain) "WAITING" else "QUEUED", false,
                        null, if(uncertain) "REMOTE_UNKNOWN" else null, System.currentTimeMillis())
                } catch (waiting: TaskWaiting) {
                    android.util.Log.w("LMReaderTasks", "Task ${claimed.taskId} (${claimed.type}) waiting: ${waiting.reason}")
                    dao.finish(claimed.taskId, claimed.generation, "WAITING", false, waiting.message, waiting.reason, System.currentTimeMillis())
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        dao.finish(claimed.taskId, claimed.generation, "INTERRUPTED", false, null, "SYSTEM_INTERRUPTED", System.currentTimeMillis())
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    android.util.Log.e("LMReaderTasks", "Task ${claimed.taskId} (${claimed.type}) failed: ${failure.javaClass.simpleName}")
                    val uncertain = dao.get(claimed.taskId)?.remoteOutcomeUnknown == true
                    val authorization = (failure as? com.lmreader.core.api.ApiException)?.httpCode in setOf(401, 403)
                    dao.finish(claimed.taskId, claimed.generation, if(uncertain || authorization) "WAITING" else "FAILED", !uncertain && !authorization,
                        failure.message ?: failure.javaClass.simpleName, if(uncertain) "REMOTE_UNKNOWN" else if(authorization) "API_ACCESS" else null, System.currentTimeMillis())
                } finally {
                    lease?.let(release)
                }
            }
            active[claimed.taskId] = job; occupied += claimed.ownerKey
            job.invokeOnCompletion {
                active.remove(claimed.taskId, job); poke()
                if(claimed.type == "CHAPTER") container.translationQueue.start()
                if(claimed.type == "EXPORT") container.exportQueue.start()
            }
            job.start()
        }
    }
    internal suspend fun remoteEvent(task: TaskRecord, id: String, scope: String, event: String) = database.withTransaction {
        when(event) {
            "PREPARED" -> dao.addRequest(TaskRequestRecord(id, task.taskId, task.generation, scope, "PREPARED"))
            "COMMITTED" -> dao.commitRequests(task.taskId, scope)
            "TASK_COMMITTED" -> dao.commitAllRequests(task.taskId)
            else -> {
                val previous = dao.request(id) ?: return@withTransaction
                // A late callback from an abandoned attempt must not revoke the persisted retry decision.
                if (previous.status != "RETRY_AUTHORIZED")
                    dao.requestStatus(id, if(event == "UNKNOWN" && previous.status == "PREPARED") "REJECTED" else event)
            }
        }
        dao.requestUncertainty(task.taskId, dao.unresolvedRequests(task.taskId) > 0)
        networkStage(task.taskId, dao.inFlightRequests(task.taskId) > 0)
    }
    suspend fun pause(id: String, cancel: Boolean = false, pauseReason: String = "USER") =
        withContext(NonCancellable) { commandLocks.computeIfAbsent(id) { Mutex() }.withLock { pauseLocked(id, cancel, pauseReason) } }
    private suspend fun pauseLocked(id: String, cancel: Boolean, pauseReason: String) {
        ready.await()
        lock(id).withLock {
            val row = dao.get(id) ?: return
            if (row.state in TERMINAL && !(cancel && row.state == "FAILED")) return
            if (!cancel && row.userPaused) return
            dao.update(row.copy(state = if (cancel) "CANCELLING" else "PAUSING", userPaused = !cancel,
                pauseReason = if (cancel) null else pauseReason, generation = row.generation + 1, waitingReason = null))
        }
        active[id]?.cancelAndJoin()
        lock(id).withLock {
            val row = dao.get(id) ?: return
            dao.update(row.copy(state = if (cancel) "CANCELLED" else "PAUSED",
                activeKey = if (cancel) null else row.activeKey))
        }
        // Parent commands apply to every child; publishing remains protected by each child's generation.
        dao.all().filter { it.parentTaskId == id && (it.state !in TERMINAL || cancel && it.state == "FAILED") }.forEach {
            pause(it.taskId, cancel, if(pauseReason == "GLOBAL") "GLOBAL" else "PARENT:$id")
        }
        poke()
    }
    suspend fun resume(id: String, retryFailed: Boolean = false, onlyPauseReason: String? = null) =
        commandLocks.computeIfAbsent(id) { Mutex() }.withLock { resumeLocked(id, retryFailed, onlyPauseReason) }
    private suspend fun resumeLocked(id: String, retryFailed: Boolean, onlyPauseReason: String?) {
        ready.await(); container.taskService.allowRetry()
        lock(id).withLock {
            val row = dao.get(id) ?: return
            if(onlyPauseReason != null && row.pauseReason != onlyPauseReason) return
            if (row.remoteOutcomeUnknown) return
            if (row.state in setOf("PAUSED", "WAITING", "INTERRUPTED") || retryFailed && row.state == "FAILED") database.withTransaction {
                val goal = row.activeKey ?: org.json.JSONObject(row.payload).optString("_goalKey").takeIf { it.isNotBlank() }
                check(goal == null || dao.active(goal)?.taskId.let { it == null || it == id }) { "同一目标已有活动任务，请在任务页继续已有任务" }
                row.parentTaskId?.let { parentId -> dao.get(parentId)?.takeIf { it.type == "VIDEO" }?.let { parent ->
                    check(parent.state !in setOf("CANCELLED", "CANCELLING", "PAUSING")) { "父任务正在停止或已取消，请重新创建任务" }
                    val parentGoal = parent.activeKey ?: org.json.JSONObject(parent.payload).optString("_goalKey").takeIf { it.isNotBlank() }
                    check(parentGoal == null || dao.active(parentGoal)?.taskId.let { it == null || it == parentId }) { "同一视频已有活动任务，请继续已有任务" }
                    dao.update(parent.copy(state = "QUEUED", activeKey = parentGoal, userPaused = false, pauseReason = null, failure = null))
                } }
                val payload = org.json.JSONObject(row.payload).apply { if(retryFailed) put("_retryFailed", true) }.toString()
                dao.update(row.copy(state = "QUEUED", activeKey = goal, payload = payload,
                    userPaused = false, pauseReason = null, failure = null, waitingReason = null))
            }
        }
        dao.all().filter { it.parentTaskId == id && (it.state != "PAUSED" || it.pauseReason == "PARENT:$id" || onlyPauseReason == "GLOBAL" && it.pauseReason == "GLOBAL") }
            .forEach { resume(it.taskId, retryFailed, onlyPauseReason) }
        poke()
    }
    fun reloadPreferences() {
        mutableOrderMode.value = settings.getString("order-mode", null) ?: container.queueOrder.schedulingPriority().name
        scope.launch { ready.await(); dispatchGate.withLock { migrateLegacyPauseFlags(initial = false) }; poke() }
    }
    fun setOrderMode(value: String) {
        require(value in setOf("ORDER", "RESOURCES"))
        settings.edit().putString("order-mode", value).commit(); mutableOrderMode.value = value; poke()
    }
    /** Convert obsolete queue holds to existing-card pauses once; future submissions stay runnable. */
    private suspend fun migrateLegacyPauseFlags(initial: Boolean) {
        val held = settings.getBoolean("paused", false) || settings.getBoolean("translations-paused", false) ||
            initial && container.queueOrder.isPaused()
        if(held) for(row in dao.all().filter { it.isTranslation() && it.state !in TERMINAL }) {
            if(initial) dao.update(row.copy(state = "PAUSED", userPaused = true, pauseReason = "USER", generation = row.generation + 1))
            else pause(row.taskId)
        }
        settings.edit().remove("paused").remove("translations-paused").commit()
    }
    /** Cancel-all briefly closes admission without creating a new persistent user pause. */
    internal suspend fun <T> withTranslationAdmissionHeld(action: suspend () -> T): T {
        dispatchGate.withLock { translationAdmissionHeld = true }
        try { return action() }
        finally { withContext(NonCancellable) { dispatchGate.withLock { translationAdmissionHeld = false }; poke() } }
    }
    /** Persist the whole cancel intent before waiting for any one native call to return. */
    internal suspend fun markCancelling(ids: List<String>) {
        val acquired = mutableListOf<Mutex>()
        try {
            for(id in ids.distinct().sorted()) lock(id).also { it.lock(); acquired += it }
            database.withTransaction {
                for(id in ids) dao.get(id)?.takeIf { it.state !in REMOVED_FROM_TRANSLATION_QUEUE }?.let { row ->
                    dao.update(row.copy(state = "CANCELLING", userPaused = false, pauseReason = null,
                        waitingReason = null, generation = row.generation + 1))
                }
            }
        } finally { acquired.asReversed().forEach { it.unlock() } }
    }
    suspend fun orderTranslationGroups(keys: List<String>) {
        ready.await()
        dispatchGate.withLock {
            container.queueOrder.sortTranslationGroups(keys)
            applyTranslationOrder()
        }
        poke()
    }
    /** Reorder translation slots only; other categories keep their relative positions. */
    private suspend fun applyTranslationOrder() = database.withTransaction {
        val rows = dao.all()
        val roots = rows.filter { it.parentTaskId == null && it.priority == 0 && it.state !in REMOVED_FROM_TRANSLATION_QUEUE }
        val translations = roots.filter { it.isTranslation() }.groupBy { it.translationGroupKey() }
        val keys = container.queueOrder.translationGroupOrder(translations.keys.toList())
        val desired = keys.flatMap { translations[it].orEmpty() }.iterator()
        val ordered = roots.map { if(it.isTranslation()) desired.next() else it }
        ordered.forEachIndexed { index, root ->
            val next = index * 1000L
            if(root.orderIndex != next) dao.update(root.copy(orderIndex = next))
            rows.filter { it.parentTaskId == root.taskId }.forEachIndexed { childIndex, child ->
                val childOrder = next + childIndex + 1
                if(child.orderIndex != childOrder) dao.update(child.copy(orderIndex = childOrder))
            }
        }
    }
    suspend fun move(id: String, delta: Int) = database.withTransaction {
        val rows = dao.all()
        val current = rows.firstOrNull { it.taskId == id } ?: return@withTransaction
        if(current.parentTaskId != null) {
            val siblings = rows.filter { it.parentTaskId == current.parentTaskId && it.state !in REMOVED_FROM_TRANSLATION_QUEUE }.toMutableList()
            val from = siblings.indexOfFirst { it.taskId == id }; val to = from + delta
            if(from >= 0 && to in siblings.indices) {
                siblings.add(to, siblings.removeAt(from))
                val base = rows.firstOrNull { it.taskId == current.parentTaskId }?.orderIndex ?: 0L
                siblings.forEachIndexed { i, child -> dao.update(child.copy(orderIndex = base + i + 1)) }
            }
            poke(); return@withTransaction
        }
        val roots = rows.filter { it.parentTaskId == null && it.priority == current.priority && it.state !in TERMINAL }.toMutableList()
        val from = roots.indexOfFirst { it.taskId == id }; val to = from + delta
        if(from < 0 || to !in roots.indices) return@withTransaction
        roots.add(to, roots.removeAt(from))
        roots.forEachIndexed { index, row ->
            dao.update(row.copy(orderIndex = index * 1000L))
            rows.filter { it.parentTaskId == row.taskId }.forEach { dao.update(it.copy(orderIndex = index * 1000L + 1)) }
        }
        poke()
    }
    /** Explicitly authorized in the product after explaining possible duplicate billing. */
    suspend fun retryUnknown(id: String) {
        lock(id).withLock {
            val row = dao.get(id) ?: return
            check(!active.containsKey(id)) { "请先等待正在执行的请求结束" }
            check(row.remoteOutcomeUnknown) { "没有待核对的远端请求" }
            database.withTransaction { dao.authorizeRequestRetry(id); dao.requestUncertainty(id, false) }
        }
        resume(id, retryFailed = true)
    }
    suspend fun interrupt(reason: String) {
        ready.await()
        for ((id, job) in active.toMap()) {
            lock(id).withLock { dao.get(id)?.takeIf { it.state == "RUNNING" }?.let { dao.update(it.copy(state = "INTERRUPTED",
                generation = it.generation + 1, waitingReason = reason)) } }
            job.cancelAndJoin()
        }
    }
    suspend fun <T> withBarrier(action: suspend () -> T): T = barrierGate.withLock {
        ready.await()
        dispatchGate.withLock { barrier = true }
        try { interrupt("DATA_BARRIER"); action() }
        finally {
            withContext(NonCancellable) {
                dao.all().filter { it.state == "INTERRUPTED" && it.waitingReason == "DATA_BARRIER" && !it.userPaused && !it.remoteOutcomeUnknown }
                    .forEach { dao.update(it.copy(state = "QUEUED", waitingReason = null)) }
                dispatchGate.withLock { barrier = false }; poke()
            }
        }
    }
    companion object { val TERMINAL = setOf("CANCELLED", "SUCCEEDED", "FAILED") }
}
