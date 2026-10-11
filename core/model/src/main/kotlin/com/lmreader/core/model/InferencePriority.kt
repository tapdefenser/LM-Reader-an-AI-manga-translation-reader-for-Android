package com.lmreader.core.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Priority is inherited by workflow branches and HTTP retry attempts. */
class InferencePriority(val rank: Int) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InferencePriority> {
        val SCREEN = InferencePriority(2)
        val READER = InferencePriority(1)
    }
}

/** Higher priority takes the next free session; an admitted native call is never interrupted. */
class PrioritySessionPool(private val maximum: Int = 8) {
    init { require(maximum > 0) }
    private data class Waiting(val order: Long, val priority: Int, val limit: Int, val preferred: List<Int>)
    private val lock = Mutex()
    private val revision = MutableStateFlow(0L)
    private val occupied = BooleanArray(maximum)
    private val waiting = mutableListOf<Waiting>()
    private var sequence = 0L
    private val activeCount = MutableStateFlow(0)
    val active = activeCount.asStateFlow()

    suspend fun <T> use(limit: Int, preferred: List<Int> = occupied.indices.toList(), action: suspend (Int) -> T): T {
        require(limit in 1..maximum)
        val priority = currentCoroutineContext()[InferencePriority]?.rank ?: 0
        val choices = (preferred.filter { it in occupied.indices } + occupied.indices).distinct()
        val ticket = lock.withLock { Waiting(sequence++, priority, limit, choices).also { waiting += it; revision.update { v -> v + 1 } } }
        var slot = -1
        try {
            while (slot < 0) {
                currentCoroutineContext().ensureActive()
                val before = revision.value
                slot = lock.withLock {
                    val next = waiting.filter { active.value < it.limit }.maxWithOrNull(compareBy<Waiting> { it.priority }.thenBy { -it.order })
                    val free = ticket.preferred.firstOrNull { it in occupied.indices && !occupied[it] }
                    if (next === ticket && free != null) {
                        waiting.remove(ticket); occupied[free] = true; activeCount.update { it + 1 }
                        revision.update { it + 1 }; free
                    } else -1
                }
                if (slot < 0) revision.first { it != before }
            }
            return action(slot)
        } finally {
            withContext(NonCancellable) { lock.withLock {
                waiting.remove(ticket)
                if (slot >= 0) { occupied[slot] = false; activeCount.update { it - 1 } }
                revision.update { it + 1 }
            } }
        }
    }
}

data class MachineTranslationResources(val concurrency: Int = 0, val memoryMegabytes: Int = 512) {
    init { require(concurrency in 0..8 && memoryMegabytes in 128..4096) }
}

/** A route includes both models of a pivot, and each session has independent native workspace. */
fun machineTranslationParallelism(settings: MachineTranslationResources, cores: Int, availableBytes: Long, routeBytes: Long): Int {
    val memory = minOf(settings.memoryMegabytes.toLong() * 1_048_576, availableBytes / 2)
    val slots = (memory / routeBytes.coerceAtLeast(1)).coerceIn(1, 8).toInt()
    return minOf(slots, if (settings.concurrency == 0) (cores / 2).coerceIn(1, 4) else settings.concurrency)
}
