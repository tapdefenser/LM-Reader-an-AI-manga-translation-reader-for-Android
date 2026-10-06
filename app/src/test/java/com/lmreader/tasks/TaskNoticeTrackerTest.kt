package com.lmreader.tasks

import org.junit.Test
import kotlin.test.*

class TaskNoticeTrackerTest {
    private fun item(id: String, state: String, completed: Int = 0) = TaskNoticeItem(id, state, "Manga", id, completed, 4)
    @Test fun completionRequiresRealDoneAndIsKeptAfterRemoval() {
        val tracker = TaskNoticeTracker(TaskQueue.TRANSLATION)
        tracker.update(listOf(item("a", "RUNNING", 2), item("b", "PENDING")), false)
        val next = tracker.update(listOf(item("a", "DONE", 4), item("b", "RUNNING", 1)), false)!!
        assertEquals(TaskNoticePhase.RUNNING, next.phase); assertEquals("b", next.item.id)
        assertEquals(1, next.remaining)
        val result = tracker.update(listOf(item("b", "DONE", 4)), false)!!
        assertEquals(TaskNoticePhase.COMPLETED, result.phase); assertEquals(2, result.completedChapters)
        assertEquals(8, result.item.completed)
        assertEquals(result, tracker.update(emptyList(), false))
    }
    @Test fun cancellationAndUnknownDisappearanceNeverBecomeCompletion() {
        for(terminal in listOf(emptyList(), listOf(item("a", "CANCELLED")))) {
            val tracker = TaskNoticeTracker(TaskQueue.TRANSLATION)
            tracker.update(listOf(item("a", "RUNNING", 2)), false)
            assertNull(tracker.update(terminal, false))
        }
    }
    @Test fun pauseKeepsActualProgressAndFailuresAreNotResumedAsSuccess() {
        val tracker = TaskNoticeTracker(TaskQueue.EXPORT)
        assertEquals(TaskNoticePhase.PAUSING, tracker.update(listOf(item("a", "RUNNING", 2)), true)!!.phase)
        val paused = tracker.update(listOf(item("a", "PAUSED", 2)), true)!!
        assertEquals(TaskNoticePhase.PAUSED, paused.phase); assertEquals(2, paused.item.completed)
        assertEquals(TaskNoticePhase.WAITING, tracker.update(listOf(item("a", "PENDING", 2)), false)!!.phase)
        val failed = tracker.update(listOf(item("a", "FAILED", 2).copy(error = "storage unavailable")), false)!!
        assertEquals(TaskNoticePhase.FAILED, failed.phase); assertEquals(1, failed.failures)
        assertEquals("storage unavailable", failed.item.error)
    }
    @Test fun oldCompletedExportsAreIgnoredAndRetryStartsAFreshResult() {
        val tracker = TaskNoticeTracker(TaskQueue.EXPORT)
        assertNull(tracker.update(listOf(item("old", "DONE", 4)), false))
        tracker.update(listOf(item("old", "DONE", 4), item("new", "RUNNING", 1)), false)
        assertEquals(1, tracker.update(listOf(item("old", "DONE", 4), item("new", "DONE", 4)), false)!!.completedChapters)
        tracker.update(listOf(item("old", "DONE", 4), item("new", "PENDING")), false)
        assertEquals(1, tracker.update(listOf(item("old", "DONE", 4), item("new", "DONE", 4)), false)!!.completedChapters)
    }
    @Test fun priorityPageHasItsOwnBusyNoticeAndDoesNotClaimSuccessWithoutAResult() {
        val tracker = TaskNoticeTracker(TaskQueue.TRANSLATION)
        val page = tracker.update(emptyList(), true, "page.png")!!
        assertTrue(page.working); assertTrue(page.singlePage); assertEquals("page.png", page.item.chapter)
        assertNull(tracker.update(emptyList(), true))
    }
}
