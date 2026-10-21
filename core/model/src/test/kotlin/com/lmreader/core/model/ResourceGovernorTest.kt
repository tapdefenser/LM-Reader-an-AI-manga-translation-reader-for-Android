package com.lmreader.core.model

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResourceGovernorTest {
    @Test fun interactiveTakesNextFreeResourceWithoutCancellingAdmittedWork() = runTest {
        val governor = ResourceGovernor(mapOf("native" to 1L)) { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>(); val order = mutableListOf<String>()
        val holder = launch { governor.use("native") { order += "admitted"; release.await(); order += "finished" } }
        runCurrent()
        val ordinary = launch { governor.use("native") { order += "ordinary" } }
        runCurrent()
        val interactive = launch(TaskContext("interactive", 1, ResourceGovernor.INTERACTIVE)) { governor.use("native") { order += "interactive" } }
        runCurrent(); assertEquals(listOf("admitted"), order)
        release.complete(Unit); joinAll(holder, ordinary, interactive)
        assertEquals(listOf("admitted", "finished", "interactive", "ordinary"), order)
        assertTrue(governor.usage.value.isEmpty())
    }
    @Test fun multiResourceWaiterDoesNotReservePartialResources() = runTest {
        val governor = ResourceGovernor(mapOf("decode" to 1L, "pixels" to 10L)) { testScheduler.currentTime }
        val release = CompletableDeferred<Unit>()
        val holder = launch { governor.use("decode") { release.await() } }; runCurrent()
        var blockedRan = false
        val blocked = launch { governor.use(mapOf("decode" to 1L, "pixels" to 10L)) { blockedRan = true } }; runCurrent()
        governor.use(mapOf("pixels" to 10L)) { assertFalse(blockedRan) }
        release.complete(Unit); joinAll(holder, blocked); assertTrue(blockedRan)
    }
    @Test fun cancelledWaitersAndOwnersReleaseAllAccounting() = runTest {
        val governor = ResourceGovernor(mapOf("native" to 1L)) { testScheduler.currentTime }
        val holder = launch { governor.use("native") { awaitCancellation() } }; runCurrent()
        val abandoned = launch { governor.use("native") { fail("cancelled waiter ran") } }; runCurrent()
        abandoned.cancelAndJoin(); holder.cancelAndJoin()
        governor.use("native") { assertEquals(1, governor.usage.value.size) }
        assertTrue(governor.usage.value.isEmpty())
    }
    @Test fun unrelatedResourceCanRunWhileInteractiveWaitsOnBusyNativeCall() = runTest {
        val governor = ResourceGovernor(mapOf("native" to 1L, "io" to 1L)) { testScheduler.currentTime }
        val holder = launch { governor.use("native") { awaitCancellation() } }; runCurrent()
        val urgent = launch(InferencePriority.READER) { governor.use("native") { } }; runCurrent()
        governor.use("io") { assertEquals(2, governor.usage.value.size) }
        holder.cancelAndJoin(); urgent.join()
    }
    @Test fun invalidOrImpossibleBudgetsFailBeforeWaiting() = runTest {
        val governor = ResourceGovernor(mapOf("pixels" to 10L))
        try { governor.use(mapOf("pixels" to 11L)) { fail() }; fail() } catch(_: IllegalArgumentException) { }
        try { governor.use(mapOf("pixels" to 0L)) { fail() }; fail() } catch(_: IllegalArgumentException) { }
        assertTrue(governor.usage.value.isEmpty())
    }
    @Test fun ownedLeaseOutlivesAdmissionCoroutineAndReleasesExactlyOnce() = runTest {
        val governor = ResourceGovernor(mapOf("pixels" to 10L))
        val lease = async { governor.acquire(mapOf("pixels" to 10L)) }.await()
        assertEquals(10L, governor.usage.value.single().resources["pixels"])
        var nextRan = false
        val next = launch { governor.use("pixels") { nextRan = true } }
        runCurrent(); assertFalse(nextRan)
        lease.close(); lease.close()
        next.join()
        assertTrue(nextRan)
        assertTrue(governor.usage.value.isEmpty())
    }
}
