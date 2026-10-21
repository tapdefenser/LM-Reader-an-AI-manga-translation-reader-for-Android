package com.lmreader.ui.queue

import com.lmreader.core.model.InferencePriority
import com.lmreader.core.model.TaskContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Local request limits share one process account; in-use results survive limit changes. */
class TranslationCacheBudget(initialLimit: Long, shared: TranslationCacheBudget? = null) {
    class Blocked : IllegalStateException("预处理缓存已满，且没有可推进并释放缓存的翻译步骤。SEG/OCR 结果完整保留，请增加缓存或改为逐页、分批工作流后重试。")
    private val root: TranslationCacheBudget = shared?.root ?: this
    private val mutex = Mutex()
    private val leases = mutableSetOf<Lease>()
    private data class Waiter(val group: TranslationCacheBudget, val bytes: Long, val owner: String,
        val priority: Int, val order: Long, val at: Long = System.currentTimeMillis())
    private val waiting = mutableListOf<Waiter>()
    private var serial = 0L
    private val changed = MutableStateFlow(0L)
    private val mutableBytes = MutableStateFlow(0L)
    val bytes = mutableBytes.asStateFlow()
    @Volatile private var limit = initialLimit
    val limitBytes get() = minOf(limit, root.limit)
    init { require(initialLimit > 0) }
    fun setLimit(bytes: Long) { require(bytes >= 32L * 1_048_576); limit = bytes; root.changed.update { it + 1 } }
    private fun fits(group: TranslationCacheBudget, bytes: Long) =
        mutableBytes.value + bytes <= limit && (group === this || group.mutableBytes.value + bytes <= group.limit)
    private fun next(): Waiter? = waiting.filter { fits(it.group, it.bytes) }.maxWithOrNull(compareBy<Waiter> {
        it.priority + ((System.currentTimeMillis() - it.at).coerceAtLeast(0) / 1000).coerceAtMost((95 - it.priority).coerceAtLeast(0).toLong()).toInt()
    }.thenBy { -it.order })
    private fun account(group: TranslationCacheBudget, delta: Long) {
        mutableBytes.value += delta
        if(group !== this) group.mutableBytes.value += delta
        changed.update { it + 1 }
    }
    private fun grant(group: TranslationCacheBudget, bytes: Long, owner: String): Lease {
        account(group, bytes)
        return Lease(bytes, owner, group).also { leases += it }
    }
    suspend fun tryAcquire(bytes: Long, owner: String = "shared"): Lease? = root.mutex.withLock {
        require(bytes > 0)
        if(!root.fits(this, bytes) || root.next() != null) null else root.grant(this, bytes, owner)
    }
    suspend fun acquire(bytes: Long, owner: String = "shared"): Lease {
        require(bytes > 0)
        val context = currentCoroutineContext()[TaskContext]
        val priority = context?.priority ?: currentCoroutineContext()[InferencePriority]?.rank ?: 0
        val ticket = root.mutex.withLock { Waiter(this, bytes, owner, priority, root.serial++).also {
            root.waiting += it; root.changed.update { value -> value + 1 }
        } }
        try { while (true) {
            currentCoroutineContext().ensureActive()
            val revision = root.changed.value
            val granted = root.mutex.withLock {
                if (root.next() === ticket) {
                    root.waiting.remove(ticket); root.grant(this, bytes, owner)
                } else {
                    val retained = root.leases.filter { it.reserved > 0 }
                    val waitingOwners = root.waiting.map { it.owner }.toSet()
                    // A parked owner can still move to its next API/MT/publication
                    // step. Only an actual capacity wait closes that dependency.
                    // All manga share this graph; active consumers break cycles.
                    if (retained.isNotEmpty() && retained.none { it.canAdvance } &&
                        retained.all { it.owner in waitingOwners }) throw Blocked()
                    null
                }
            }
            if (granted != null) {
                try { context?.onWaiting?.invoke(null); return granted }
                catch(error: Throwable) { withContext(NonCancellable) { granted.release() }; throw error }
            }
            context?.onWaiting?.invoke("RESOURCE:image-memory")
            withTimeoutOrNull(1000) { root.changed.first { it != revision } }
        } } finally { withContext(NonCancellable) { root.mutex.withLock {
            root.waiting.remove(ticket); root.changed.update { it + 1 }
        } } }
    }
    inner class Lease internal constructor(internal var reserved: Long, internal val owner: String,
        private val group: TranslationCacheBudget) {
        internal var canAdvance = true
        private var released = false
        /** False when all consumers depend on admission completing (e.g. a list collector). */
        suspend fun setCanAdvance(value: Boolean) = root.mutex.withLock {
            canAdvance = value; root.changed.update { it + 1 }
        }
        /** An admitted page must finish producing every result, even when it exceeds the budget.
         * Only admission of another page waits for space; an owner never waits on itself. */
        suspend fun resizeForPage(bytes: Long) = root.mutex.withLock {
            require(bytes >= 0); check(!released)
            root.account(group, bytes - reserved); reserved = bytes
        }
        suspend fun shrink(bytes: Long) = root.mutex.withLock {
            require(bytes in 0..reserved)
            check(!released); root.account(group, bytes - reserved); reserved = bytes
        }
        suspend fun release() = root.mutex.withLock {
            if(!released) { root.account(group, -reserved); reserved = 0; released = true; root.leases -= this }
        }
    }
}
