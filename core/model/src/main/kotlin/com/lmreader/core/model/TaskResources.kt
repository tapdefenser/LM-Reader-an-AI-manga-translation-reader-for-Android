package com.lmreader.core.model

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

class TaskContext(val taskId: String, val generation: Long, val priority: Int,
    val onWaiting: suspend (String?) -> Unit = {},
    val onRemoteRequest: suspend (String, String, String) -> Unit = { _, _, _ -> }) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskContext>
}
class TaskPublicationScope(val scopeKey: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TaskPublicationScope>
}

/** Atomic multi-resource admission; a waiter never holds one permit while waiting for another. */
class ResourceGovernor(private val capacities: Map<String, Long> = DEFAULT_CAPACITIES,
    private val clock: () -> Long = System::currentTimeMillis) {
    data class Usage(val owner: String, val resources: Map<String, Long>)
    private data class Ticket(val order: Long, val owner: String, val priority: Int,
        val at: Long, val resources: Map<String, Long>)
    private val gate = Any()
    private val revision = MutableStateFlow(0L)
    private val waiting = mutableListOf<Ticket>()
    private val held = mutableMapOf<Long, Usage>()
    private var serial = 0L
    private val mutableUsage = MutableStateFlow<List<Usage>>(emptyList())
    val usage = mutableUsage.asStateFlow()
    init { require(capacities.values.all { it > 0 }) }
    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean()
        override fun close() { if(closed.compareAndSet(false, true)) release() }
    }
    /** A lease can follow pixels/session ownership beyond the coroutine that admitted them. */
    suspend fun acquire(resources: Map<String, Long>): Lease {
        require(resources.isNotEmpty() && resources.all { (key, n) -> n > 0 && n <= (capacities[key] ?: 1) })
        val context = currentCoroutineContext()[TaskContext]
        val priority = context?.priority ?: currentCoroutineContext()[InferencePriority]?.rank ?: 0
        val ticket = synchronized(gate) { Ticket(serial++, context?.taskId ?: "session", priority, clock(), resources).also {
            waiting += it; revision.update { v -> v + 1 }
        } }
        var acquired = false
        fun fits(candidate: Ticket) = candidate.resources.all { (key, count) ->
            held.values.sumOf { it.resources[key] ?: 0 } + count <= (capacities[key] ?: 1)
        }
        try {
            context?.onWaiting?.invoke("RESOURCE:${resources.keys.sorted().joinToString(",")}")
            while (!acquired) {
                currentCoroutineContext().ensureActive()
                val before = revision.value
                acquired = synchronized(gate) {
                    // Aging is bounded below interactive work; it prevents ordinary maintenance starvation.
                    val next = waiting.filter(::fits).maxWithOrNull(compareBy<Ticket> {
                        it.priority + ((clock() - it.at).coerceAtLeast(0) / 1000).coerceAtMost((95 - it.priority).coerceAtLeast(0).toLong()).toInt()
                    }.thenBy { -it.order })
                    if (next === ticket) {
                        waiting.remove(ticket); held[ticket.order] = Usage(ticket.owner, resources)
                        mutableUsage.value = held.values.toList(); revision.update { it + 1 }; true
                    } else false
                }
                if (!acquired) withTimeoutOrNull(1000) { revision.first { it != before } }
            }
            context?.onWaiting?.invoke(null)
            return Lease {
                synchronized(gate) {
                    held.remove(ticket.order)
                    mutableUsage.value = held.values.toList(); revision.update { it + 1 }
                }
            }
        } catch(error: Throwable) {
            synchronized(gate) {
                waiting.remove(ticket); held.remove(ticket.order)
                mutableUsage.value = held.values.toList(); revision.update { it + 1 }
            }
            throw error
        }
    }
    suspend fun <T> use(resources: Map<String, Long>, action: suspend () -> T): T {
        val lease = acquire(resources)
        try { return action() } finally { lease.close() }
    }
    suspend fun <T> use(resource: String, action: suspend () -> T): T = use(mapOf(resource to 1L), action)
    companion object {
        const val INTERACTIVE = 100
        const val SCREEN = 50
        const val NORMAL = 0
        const val MAINTENANCE = -20
        val DEFAULT_CAPACITIES = mapOf("vision" to 2L, "nmt" to 2L, "decode" to 1L,
            "io" to 2L, "asr" to 1L, "compute" to 2L, "playback" to 1L, "pixels" to 32_000_000L)
        /** All engines, including non-task previews, use this same process budget. */
        val shared = ResourceGovernor()
    }
}
