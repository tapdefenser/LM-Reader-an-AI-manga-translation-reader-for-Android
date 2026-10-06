package com.lmreader.tasks

import android.content.Intent
import androidx.core.content.ContextCompat
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Each runner owns a lease until its last write/cleanup has finished. */
class TaskServiceController(private val container: AppContainer,
    private val startForegroundService: () -> Unit = {
        ContextCompat.startForegroundService(container.application, Intent(container.application, QueueTaskService::class.java))
    },
) {
    private val jobs = mutableMapOf<String, Job>()
    private var ready = CompletableDeferred<Unit>()
    private var requested = false
    private var visible = false
    private var blocked = false
    private var serviceOwner: Any? = null
    private val _failure = MutableStateFlow<String?>(null)
    val failure = _failure.asStateFlow()
    private val _leases = MutableStateFlow(0)
    val leases = _leases.asStateFlow()

    @Synchronized fun setVisible(value: Boolean) { visible = value }
    @Synchronized fun canStart() = !blocked && (visible || requested)
    @Synchronized fun allowRetry() { if (visible) { blocked = false; _failure.value = null } }
    /** A user notification action may resume work only after its actual service is foreground. */
    @Synchronized fun allowNotificationResume(owner: Any) {
        check(serviceOwner === owner) { "后台服务尚未就绪" }
        ready = CompletableDeferred<Unit>().apply { complete(Unit) }
        blocked = false; requested = true; _failure.value = null
    }
    suspend fun acquire(): String {
        val id = UUID.randomUUID().toString()
        val job = currentCoroutineContext().job
        val startup = synchronized(this) {
            check(!blocked) { _failure.value ?: "后台任务已停止，请在队列中手动继续" }
            if (!requested) {
                check(visible) { "请打开应用后手动继续任务" }
                ready = CompletableDeferred(); requested = true
                try { startForegroundService() }
                catch (error: Exception) {
                    requested = false; blocked = true; _failure.value = "无法启动后台任务，请打开应用后重试"
                    throw IllegalStateException(_failure.value, error)
                }
            }
            jobs[id] = job; _leases.value = jobs.size
            ready
        }
        try { withTimeout(10_000) { startup.await() }; return id }
        catch (error: Exception) {
            if (error !is CancellationException || error is TimeoutCancellationException) synchronized(this) {
                blocked = true; _failure.value = "后台任务启动失败，请手动继续"; requested = false
            }
            release(id); throw error
        }
    }
    @Synchronized fun release(id: String) { jobs.remove(id); _leases.value = jobs.size }
    suspend fun cancelAndAwait() {
        val active = synchronized(this) { jobs.values.toList() }
        active.forEach { it.cancel() }; active.forEach { it.join() }
    }
    @Synchronized fun serviceReady(owner: Any? = null) { serviceOwner = owner; ready.complete(Unit) }
    @Synchronized fun failStartup(error: Exception) {
        requested = false; blocked = true; _failure.value = "后台服务启动失败，请打开应用后手动继续"
        ready.completeExceptionally(error)
    }
    @Synchronized fun stopIfIdle(stop: () -> Unit): Boolean {
        if (jobs.isNotEmpty()) return false
        requested = false; stop(); return true
    }
    fun serviceStopped(reason: String?, owner: Any? = null) {
        val active = synchronized(this) {
            if (owner != null && serviceOwner !== owner) return
            if (!ready.isCompleted) return // An old service may be destroyed while a new start is pending.
            requested = false; serviceOwner = null
            if (reason == null || jobs.isEmpty()) return
            blocked = true; _failure.value = reason
            ready.completeExceptionally(IllegalStateException(reason))
            jobs.values.toList()
        }
        // Queue controls acquire their own lock before consulting this controller.
        // Never hold the controller lock while calling back into a queue.
        runCatching { container.translationQueue.pause() }.onFailure {
            android.util.Log.e("TaskService", "无法保存翻译暂停状态", it)
        }
        runCatching { container.exportQueue.pauseAll() }.onFailure {
            android.util.Log.e("TaskService", "无法保存导出暂停状态", it)
        }
        active.forEach { it.cancel(CancellationException(reason)) }
    }
}
