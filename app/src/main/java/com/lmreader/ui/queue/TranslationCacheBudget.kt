package com.lmreader.ui.queue

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Accounts decoded bitmaps and ready OCR text; lowering the limit never evicts an in-use page. */
class TranslationCacheBudget(initialLimit: Long) {
    class Blocked : IllegalStateException("预处理缓存已满，且没有可推进并释放缓存的翻译步骤。SEG/OCR 结果完整保留，请增加缓存或改为逐页、分批工作流后重试。")
    private val mutex = Mutex()
    private val leases = mutableSetOf<Lease>()
    private val waitingOwners = mutableMapOf<String, Int>()
    private val changed = MutableStateFlow(0L)
    private val mutableBytes = MutableStateFlow(0L)
    val bytes = mutableBytes.asStateFlow()
    @Volatile private var limit = initialLimit
    val limitBytes get() = limit
    fun setLimit(bytes: Long) { require(bytes >= 32L * 1_048_576); limit = bytes; changed.update { it + 1 } }
    suspend fun tryAcquire(bytes: Long, owner: String = "shared"): Lease? = mutex.withLock {
        require(bytes > 0)
        if(mutableBytes.value + bytes > limit) null
        else { mutableBytes.value += bytes; Lease(bytes, owner).also { leases += it } }
    }
    suspend fun acquire(bytes: Long, owner: String = "shared"): Lease {
        require(bytes > 0)
        var waiting = false
        try { while (true) {
            val revision = changed.value
            val granted = mutex.withLock {
                if (mutableBytes.value + bytes <= limit) {
                    mutableBytes.value += bytes; Lease(bytes, owner).also { leases += it }
                } else {
                    if (!waiting) {
                        waitingOwners[owner] = (waitingOwners[owner] ?: 0) + 1
                        waiting = true; changed.update { it + 1 }
                    }
                    val retained = leases.filter { it.reserved > 0 }
                    // A parked owner can still move to its next API/MT/publication
                    // step. Only an actual capacity wait closes that dependency.
                    // All manga share this graph; active consumers break cycles.
                    if (retained.isNotEmpty() && retained.none { it.canAdvance } &&
                        retained.all { it.owner in waitingOwners }) throw Blocked()
                    null
                }
            }
            if (granted != null) return granted
            changed.first { it != revision }
        } } finally { if (waiting) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { mutex.withLock {
            val count = waitingOwners.getValue(owner) - 1
            if (count == 0) waitingOwners.remove(owner) else waitingOwners[owner] = count
            changed.update { it + 1 }
        } } }
    }
    inner class Lease internal constructor(internal var reserved: Long, internal val owner: String) {
        internal var canAdvance = true
        /** False when all consumers depend on admission completing (e.g. a list collector). */
        suspend fun setCanAdvance(value: Boolean) = mutex.withLock {
            canAdvance = value; changed.update { it + 1 }
        }
        /** An admitted page must finish producing every result, even when it exceeds the budget.
         * Only admission of another page waits for space; an owner never waits on itself. */
        suspend fun resizeForPage(bytes: Long) = mutex.withLock {
            require(bytes >= 0)
            mutableBytes.value += bytes - reserved; reserved = bytes; changed.update { it + 1 }
        }
        suspend fun shrink(bytes: Long) = mutex.withLock {
            require(bytes in 0..reserved)
            mutableBytes.value -= reserved - bytes; reserved = bytes; changed.update { it + 1 }
        }
        suspend fun release() = mutex.withLock {
            mutableBytes.value -= reserved; reserved = 0; leases -= this; changed.update { it + 1 }
        }
    }
}
