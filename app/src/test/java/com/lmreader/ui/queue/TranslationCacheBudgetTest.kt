package com.lmreader.ui.queue

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationCacheBudgetTest {
    @Test fun preparedOtherMangaCanReachItsApiBeforeCapacityIsDeclaredBlocked() = runBlocking {
        val budget = TranslationCacheBudget(32)
        val prepared = budget.acquire(16, "prepared-manga")
        prepared.resizeForPage(40); prepared.setCanAdvance(false)
        val next = async { budget.acquire(16, "next-manga") }
        yield(); assertFalse(next.isCompleted)
        prepared.setCanAdvance(true) // The collector has finished and reached its API.
        yield(); assertFalse(next.isCompleted)
        prepared.release()
        withTimeout(1000) { next.await() }.release()
    }
    @Test fun allRetainedMangaWaitingForAdmissionFormABlockedGraph() = runBlocking {
        val budget = TranslationCacheBudget(64)
        val a = budget.acquire(32, "a")
        val b = budget.acquire(32, "b")
        a.setCanAdvance(false); b.setCanAdvance(false)
        val nextB = async { budget.acquire(16, "b") }
        yield(); assertFalse(nextB.isCompleted)
        val failure = runCatching { budget.acquire(16, "a") }.exceptionOrNull()
        assertTrue(failure is TranslationCacheBudget.Blocked)
        assertEquals(64L, budget.bytes.value)
        a.release()
        withTimeout(1000) { nextB.await() }.release()
        b.release()
    }
    @Test fun retainedResultsWithoutAnyAdvancingConsumerFailInsteadOfDeadlocking() = runBlocking {
        val mb=1_048_576L
        val budget=TranslationCacheBudget(32*mb)
        val retained=budget.acquire(16*mb)
        retained.resizeForPage(40*mb);retained.setCanAdvance(false)
        try { budget.acquire(16*mb);fail("must identify the blocked graph") }
        catch(_: TranslationCacheBudget.Blocked) { assertEquals(40*mb,budget.bytes.value) }
        retained.release()
    }
    @Test fun anApiOrOtherMangaConsumerKeepsAdmissionWaitingUntilItReleases() = runBlocking {
        val mb=1_048_576L
        val budget=TranslationCacheBudget(32*mb)
        val retained=budget.acquire(16*mb)
        val api=budget.acquire(16*mb)
        retained.setCanAdvance(false)
        val next=async { budget.acquire(16*mb) }
        yield();assertFalse(next.isCompleted)
        api.release()
        withTimeout(1000) { next.await() }.release()
        retained.release()
    }
    @Test fun aWholePageMayExceedBudgetButAnotherSegMustWait() = runBlocking {
        val mb = 1_048_576L
        val budget = TranslationCacheBudget(32 * mb)
        val page = budget.acquire(16 * mb)
        page.resizeForPage(70 * mb) // All bubbles and OCR, never truncate to the configured budget.
        val next = async { budget.acquire(16 * mb) }
        yield(); assertFalse(next.isCompleted)
        assertEquals(70 * mb, budget.bytes.value)
        page.resizeForPage(80 * mb) // More OCR on the admitted page still completes.
        assertFalse(next.isCompleted)
        page.release()
        withTimeout(1000) { next.await() }.release()
        assertEquals(0, budget.bytes.value)
    }
    @Test fun loweringLimitKeepsInUsePagesAndBlocksAdmissionUntilTheyRelease() = runBlocking {
        val mb = 1_048_576L
        val budget = TranslationCacheBudget(64 * mb)
        val first = budget.acquire(32 * mb)
        val second = budget.acquire(32 * mb)
        budget.setLimit(32 * mb)
        val waiting = async { budget.acquire(32 * mb) }
        yield(); assertFalse(waiting.isCompleted)
        first.release(); yield(); assertFalse(waiting.isCompleted)
        second.release()
        val admitted = withTimeout(1000) { waiting.await() }
        assertEquals(32 * mb, budget.bytes.value)
        admitted.shrink(1024); assertEquals(1024, budget.bytes.value)
        admitted.release(); assertEquals(0, budget.bytes.value)
    }
}
