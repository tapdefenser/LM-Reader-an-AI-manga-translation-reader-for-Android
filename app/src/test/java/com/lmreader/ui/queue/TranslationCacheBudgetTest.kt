package com.lmreader.ui.queue

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TranslationCacheBudgetTest {
    @Test fun differentKindsShareGlobalCapacityAndReleaseTheirOwnAccount() = runBlocking {
        val global = TranslationCacheBudget(64)
        val chapters = TranslationCacheBudget(64, global)
        val video = TranslationCacheBudget(32, global)
        val page = chapters.acquire(48, "chapter")
        assertNull(video.tryAcquire(32, "video"))
        assertEquals(48L, global.bytes.value)
        val waiting = async { video.acquire(32, "video") }
        yield(); assertFalse(waiting.isCompleted)
        page.release()
        val frame = withTimeout(1000) { waiting.await() }
        assertEquals(0L, chapters.bytes.value)
        assertEquals(32L, video.bytes.value)
        assertEquals(32L, global.bytes.value)
        frame.release(); frame.release()
        assertEquals(0L, global.bytes.value)
    }
    @Test fun interactivePageWinsTheNextSharedMemoryAdmission() = runBlocking {
        val global = TranslationCacheBudget(32)
        val video = TranslationCacheBudget(32, global)
        val reader = TranslationCacheBudget(32, global)
        val held = video.acquire(32, "running")
        val ordinary = async { video.acquire(32, "ordinary") }
        yield()
        val interactive = async(com.lmreader.core.model.InferencePriority(100)) { reader.acquire(32, "interactive") }
        yield(); held.release()
        val first = withTimeout(1000) { interactive.await() }
        assertFalse(ordinary.isCompleted)
        first.release(); withTimeout(1000) { ordinary.await() }.release()
        assertEquals(0L, global.bytes.value)
    }
    @Test fun cancellingAChildWaitDoesNotLeakGlobalCapacity() = runBlocking {
        val global = TranslationCacheBudget(32)
        val video = TranslationCacheBudget(32, global)
        val reader = TranslationCacheBudget(32, global)
        val frame = video.acquire(32, "video")
        val waiting = async { reader.acquire(16, "reader") }
        yield(); waiting.cancelAndJoin(); frame.release()
        val page = reader.acquire(32, "reader")
        assertEquals(32L, global.bytes.value)
        page.release(); assertEquals(0L, global.bytes.value)
    }
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
    @Test fun acquirePageAdmitsOneCrossingPageThenWaitsUntilSpaceReturns() = runBlocking {
        val budget = TranslationCacheBudget(100)
        val first = budget.acquire(60, "first")
        val crossing = budget.acquirePage(60, "crossing")
        assertEquals(120L, budget.bytes.value)

        val waiting = async { budget.acquirePage(60, "waiting") }
        yield(); assertFalse(waiting.isCompleted)
        assertEquals(120L, budget.bytes.value)

        first.release()
        val admitted = withTimeout(1000) { waiting.await() }
        assertEquals(120L, budget.bytes.value)
        crossing.release()
        admitted.release()
        assertEquals(0L, budget.bytes.value)
    }
    @Test fun cancellingAPageWaiterLeavesOnlyTheAdmittedPagesAccounted() = runBlocking {
        val budget = TranslationCacheBudget(100)
        val held = budget.acquirePage(120, "held")
        assertEquals(120L, budget.bytes.value)

        val waiting = async { budget.acquirePage(40, "waiting") }
        yield(); assertFalse(waiting.isCompleted)
        withTimeout(1000) { waiting.cancelAndJoin() }
        assertEquals(120L, budget.bytes.value)

        held.release()
        assertEquals(0L, budget.bytes.value)
    }
    @Test fun videoAndMangaGroupsShareOneRootWithoutDoubleCountingPages() = runBlocking {
        val manga = TranslationCacheBudget(100)
        val video = TranslationCacheBudget(1000, manga)
        assertEquals(100L, video.limitBytes)

        val videoPage = video.acquirePage(60, "video")
        assertEquals(60L, manga.bytes.value)
        assertEquals(60L, video.bytes.value)

        val mangaPage = manga.acquirePage(60, "manga")
        assertEquals(120L, manga.bytes.value)
        assertEquals(60L, video.bytes.value)

        val waiting = async { video.acquirePage(20, "video-next") }
        yield(); assertFalse(waiting.isCompleted)
        videoPage.release()
        assertEquals(60L, manga.bytes.value)

        val next = withTimeout(1000) { waiting.await() }
        assertEquals(80L, manga.bytes.value)
        assertEquals(20L, video.bytes.value)

        next.release(); mangaPage.release()
        assertEquals(0L, manga.bytes.value)
        assertEquals(0L, video.bytes.value)
    }
}
