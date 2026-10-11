package com.lmreader.core.model

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrioritySessionPoolTest {
    @Test fun independentSlotsReallyOverlapButNeverExceedTheLimit() = runTest {
        val pool = PrioritySessionPool(3)
        val release = CompletableDeferred<Unit>()
        val slots = mutableListOf<Int>()
        val jobs = List(4) { launch { pool.use(2) { slots += it; release.await() } } }
        runCurrent()
        assertEquals(2, pool.active.value); assertEquals(2, slots.distinct().size)
        release.complete(Unit); jobs.joinAll(); assertEquals(0, pool.active.value); assertEquals(4, slots.size)
    }

    @Test fun equalPriorityIsFifoAndCancellationCannotLeakTicketsOrPermits() = runTest {
        val pool = PrioritySessionPool(1)
        val release = CompletableDeferred<Unit>()
        val holder = launch { pool.use(1) { release.await() } }
        runCurrent()
        val order = mutableListOf<Int>()
        val abandoned = launch { pool.use(1) { fail("Cancelled request must not run") } }
        val first = launch { pool.use(1) { order += 1 } }
        val second = launch { pool.use(1) { order += 2 } }
        runCurrent(); abandoned.cancelAndJoin(); release.complete(Unit)
        joinAll(holder, first, second); assertEquals(listOf(1, 2), order); assertEquals(0, pool.active.value)
        val running = launch { pool.use(1) { awaitCancellation() } }; runCurrent(); running.cancelAndJoin()
        pool.use(1) { assertEquals(1, pool.active.value) }; assertEquals(0, pool.active.value)
    }

    @Test fun tighterRouteMemoryLimitWaitsForActiveDecodersToFinish() = runTest {
        val pool = PrioritySessionPool(3)
        val release = CompletableDeferred<Unit>()
        val running = List(2) { launch { pool.use(2) { release.await() } } }
        runCurrent()
        var narrowRan = false
        val narrow = launch { pool.use(1) { narrowRan = true; assertEquals(1, pool.active.value) } }
        runCurrent(); assertFalse(narrowRan)
        release.complete(Unit); (running + narrow).joinAll(); assertTrue(narrowRan)
    }

    @Test fun invalidOrBusyPreferredSlotsFallBackToAvailableSlots() = runTest {
        val pool = PrioritySessionPool(2)
        val release = CompletableDeferred<Unit>()
        val first = launch { pool.use(2, listOf(0)) { assertEquals(0, it); release.await() } }
        runCurrent()
        pool.use(2, listOf(0, -1, 99)) { assertEquals(1, it) }
        release.complete(Unit); first.join()
        pool.use(2, emptyList()) { assertEquals(0, it) }
    }

    @Test fun automaticAllocationCountsPivotModelsAndReservesSystemMemory() {
        val mb = 1_048_576L
        assertEquals(4, machineTranslationParallelism(MachineTranslationResources(memoryMegabytes = 2048), 16, 8192 * mb, 300 * mb))
        assertEquals(1, machineTranslationParallelism(MachineTranslationResources(memoryMegabytes = 512), 8, 8192 * mb, 400 * mb))
        assertEquals(2, machineTranslationParallelism(MachineTranslationResources(8, 4096), 16, 1024 * mb, 200 * mb))
        assertEquals(1, machineTranslationParallelism(MachineTranslationResources(), 1, 0, 900 * mb))
    }
}
