package com.lmreader.tasks

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.media.MediaTaskDatabase
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.core.model.TaskContext
import com.lmreader.di.AppContainer
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real Room transactions, fake workers: no model, network, media grant or foreground service. */
@RunWith(AndroidJUnit4::class)
class UnifiedTaskSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: MediaTaskDatabase
    private lateinit var workers: CoroutineScope
    private lateinit var scheduler: TaskScheduler
    private val preferences by lazy { context.getSharedPreferences("task-test-${UUID.randomUUID()}", 0) }
    private val startup = CompletableDeferred<Unit>()

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, MediaTaskDatabase::class.java).build()
        workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scheduler = TaskScheduler(AppContainer.from(context), db, workers, startup,
            canStart = { true }, acquire = { null }, release = {}, preferences = preferences)
    }

    @After fun cleanup() = runBlocking<Unit> {
        workers.coroutineContext[Job]!!.cancelAndJoin()
        db.close()
        preferences.edit().clear().commit()
    }

    private suspend fun start() { startup.complete(Unit); scheduler.ready.await() }
    private suspend fun waitState(id: String, state: String): TaskRecord = withTimeout(8_000) {
        while (true) {
            val row = db.tasks().get(id)
            if (row?.state == state) return@withTimeout row
            delay(10)
        }
        error("unreachable")
    }
    private suspend fun submit(key: String, priority: Int = 0, parent: String? = null) = scheduler.submit(
        if (parent == "PARENT") "VIDEO" else "TEST", key, "owner-$key", key, "{}", priority,
        parentTaskId = parent.takeUnless { it == "PARENT" })

    @Test fun duplicateSubmissionKeepsPersistentIdentity() = runBlocking {
        start()
        val first = submit("same-goal")
        val duplicates = coroutineScope { List(12) { async { submit("same-goal") } }.awaitAll() }
        assertTrue(duplicates.all { it.taskId == first.taskId })
        assertEquals(1, db.tasks().all().size)
    }

    @Test fun failedStartupReleasesUiWaitersButKeepsAdmissionClosed() = runBlocking {
        startup.completeExceptionally(IllegalStateException("restore fixture failed"))
        withTimeout(8000) { scheduler.ready.await() }
        assertTrue(scheduler.initializationFailure.value.orEmpty().contains("restore fixture failed"))
        val error = runCatching { submit("must-not-start") }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(db.tasks().all().isEmpty())
    }

    @Test fun retryingBranchRestoresParentIdentityBeforeAnotherSubmission() = runBlocking<Unit> {
        start()
        val parent = submit("video-parent", parent = "PARENT")
        val child = submit("video-child", parent = parent.taskId)
        db.withTransaction {
            db.tasks().update(child.copy(state = "FAILED", activeKey = null))
            db.tasks().update(parent.copy(state = "FAILED", activeKey = null))
        }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        scheduler.register("TEST") { entered.complete(Unit); finish.await() }
        scheduler.resume(child.taskId, retryFailed = true)
        withTimeout(8000) { entered.await() }
        assertEquals(parent.taskId, db.tasks().active("video-parent")?.taskId)
        assertEquals(parent.taskId, submit("video-parent", parent = "PARENT").taskId)
        assertEquals(2, db.tasks().all().size)
        finish.complete(Unit)
        waitState(parent.taskId, "SUCCEEDED")
    }

    @Test fun yieldingSafeUnitsRetainsTaskIdentityAndCheckpoint() = runBlocking {
        start()
        val calls = AtomicInteger()
        scheduler.register("TEST") { execution ->
            val n = calls.incrementAndGet()
            if(n == 1) { execution.progress(1, 2, "unit one", "committed-one"); throw TaskRequeue() }
            assertEquals("committed-one", execution.initial.checkpoint)
            execution.progress(2, 2, "unit two")
        }
        val task = submit("two-safe-units")
        val done = waitState(task.taskId, "SUCCEEDED")
        assertEquals(2, calls.get())
        assertEquals(2L, done.completed)
        assertEquals(1, db.tasks().all().size)
    }

    @Test fun conflictingResultRetriesUnknownResponsesAndIgnoresLateAttemptCallbacks() = runBlocking {
        start()
        val calls = AtomicInteger()
        var oldContext: TaskContext? = null
        scheduler.register("TEST") { execution ->
            val context = currentCoroutineContext()[TaskContext]!!
            if(calls.incrementAndGet() == 1) {
                oldContext = context
                for((id, outcome) in listOf("unknown-conflict" to "UNKNOWN", "received-conflict" to "RECEIVED")) {
                    context.onRemoteRequest(id, "conflicting-page", "PREPARED")
                    context.onRemoteRequest(id, "conflicting-page", "SENT")
                    context.onRemoteRequest(id, "conflicting-page", outcome)
                }
                assertTrue(db.tasks().get(execution.taskId)!!.remoteOutcomeUnknown)
                throw TaskRequeue(resultConflict = true)
            }
            assertEquals("RETRY_AUTHORIZED", db.tasks().request("unknown-conflict")!!.status)
            assertEquals("RETRY_AUTHORIZED", db.tasks().request("received-conflict")!!.status)
            oldContext!!.onRemoteRequest("unknown-conflict", "conflicting-page", "UNKNOWN")
            oldContext!!.onRemoteRequest("received-conflict", "conflicting-page", "RECEIVED")
            assertEquals("RETRY_AUTHORIZED", db.tasks().request("unknown-conflict")!!.status)
            assertEquals("RETRY_AUTHORIZED", db.tasks().request("received-conflict")!!.status)
            assertFalse(db.tasks().get(execution.taskId)!!.remoteOutcomeUnknown)
        }
        val task = submit("conflict-auto-retry")
        val finished = waitState(task.taskId, "SUCCEEDED")
        assertFalse(finished.remoteOutcomeUnknown)
        assertEquals(2, calls.get())
        assertEquals(1, db.tasks().all().size)
    }

    @Test fun ordinarySafeYieldStillWaitsForUnknownRemoteOutcome() = runBlocking {
        start()
        val calls = AtomicInteger()
        scheduler.register("TEST") {
            calls.incrementAndGet()
            val context = currentCoroutineContext()[TaskContext]!!
            context.onRemoteRequest("ordinary-unknown", "page", "PREPARED")
            context.onRemoteRequest("ordinary-unknown", "page", "SENT")
            context.onRemoteRequest("ordinary-unknown", "page", "UNKNOWN")
            throw TaskRequeue()
        }
        val task = submit("ordinary-unknown-yield")
        val waiting = waitState(task.taskId, "WAITING")
        assertEquals("REMOTE_UNKNOWN", waiting.waitingReason)
        assertEquals("UNKNOWN", db.tasks().request("ordinary-unknown")!!.status)
        assertEquals(1, calls.get())
    }

    @Test fun persistedInteractiveTaskWinsActualResourceAdmission() = runBlocking {
        val governor = ResourceGovernor(mapOf("native" to 1L))
        val held = governor.acquire(mapOf("native" to 1L))
        val first = CompletableDeferred<String>()
        val interactiveFinished = CompletableDeferred<Unit>()
        scheduler.setOrderMode("RESOURCES")
        scheduler.register("TEST") { execution ->
            assertEquals(execution.taskId, currentCoroutineContext()[TaskContext]?.taskId)
            governor.use("native") {
                first.complete(execution.taskId)
                if(execution.initial.priority == ResourceGovernor.INTERACTIVE) interactiveFinished.await()
            }
        }
        start()
        val ordinary = submit("ordinary-native")
        suspend fun awaiting(id: String) = withTimeout(8000) {
            while(db.tasks().get(id)?.waitingReason != "RESOURCE:native") delay(10)
        }
        awaiting(ordinary.taskId)
        val interactive = submit("interactive-native", ResourceGovernor.INTERACTIVE)
        awaiting(interactive.taskId)
        assertEquals("RUNNING", db.tasks().get(interactive.taskId)?.state)
        held.close()
        assertEquals(interactive.taskId, withTimeout(8000) { first.await() })
        assertEquals("RESOURCE:native", db.tasks().get(ordinary.taskId)?.waitingReason)
        interactiveFinished.complete(Unit)
        waitState(interactive.taskId, "SUCCEEDED")
        waitState(ordinary.taskId, "SUCCEEDED")
        assertTrue(governor.usage.value.isEmpty())
    }

    @Test fun pauseWaitsForNativeBoundaryAndRejectsLatePublication() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val nativeReturned = CompletableDeferred<Unit>()
        val rejected = CompletableDeferred<Boolean>()
        val calls = AtomicInteger()
        scheduler.register("TEST") { execution ->
            if (calls.incrementAndGet() == 1) {
                entered.complete(Unit)
                withContext(NonCancellable) {
                    nativeReturned.await()
                    rejected.complete(runCatching { execution.publish { error("must not publish") } }.isFailure)
                }
            }
        }
        start()
        val task = submit("native")
        entered.await()
        val pause = async { scheduler.pause(task.taskId) }
        waitState(task.taskId, "PAUSING")
        assertFalse(pause.isCompleted)
        nativeReturned.complete(Unit)
        pause.await()
        assertTrue(rejected.await())
        val paused = waitState(task.taskId, "PAUSED")
        scheduler.resume(task.taskId)
        val finished = waitState(task.taskId, "SUCCEEDED")
        assertTrue(finished.generation > paused.generation)
        assertEquals(2, calls.get())
    }
    @Test fun leavingTheCallerStillFinishesAnAcceptedPauseAtItsSafeBoundary() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val nativeReturned = CompletableDeferred<Unit>()
        scheduler.register("TEST") {
            entered.complete(Unit)
            withContext(NonCancellable) { nativeReturned.await() }
        }
        start()
        val task = submit("pause-outlives-caller")
        entered.await()
        val caller = launch { scheduler.pause(task.taskId) }
        waitState(task.taskId, "PAUSING")
        caller.cancel()
        nativeReturned.complete(Unit)
        caller.join()
        assertEquals("PAUSED", db.tasks().get(task.taskId)!!.state)
    }

    @Test fun perCardPauseDoesNotGateNewInteractiveOrOrdinaryTasks() = runBlocking<Unit> {
        start()
        val individual = submit("individual")
        scheduler.pause(individual.taskId)
        scheduler.register("TEST") { }
        val interactive = submit("interactive", ResourceGovernor.INTERACTIVE)
        waitState(interactive.taskId, "SUCCEEDED")
        assertEquals("PAUSED", db.tasks().get(individual.taskId)!!.state)
        assertEquals("USER", db.tasks().get(individual.taskId)!!.pauseReason)
        val ordinary = submit("ordinary")
        waitState(ordinary.taskId, "SUCCEEDED")
        assertEquals("PAUSED", db.tasks().get(individual.taskId)!!.state)
        scheduler.resume(individual.taskId)
        waitState(individual.taskId, "SUCCEEDED")
    }

    @Test fun cancelWaitsForCommitAlreadyHoldingPublicationLock() = runBlocking {
        val publishing = CompletableDeferred<Unit>()
        val finishCommit = CompletableDeferred<Unit>()
        val committed = AtomicInteger()
        scheduler.register("TEST") { execution ->
            execution.publish {
                publishing.complete(Unit)
                finishCommit.await()
                committed.incrementAndGet()
            }
            awaitCancellation()
        }
        start()
        val task = submit("atomic-commit")
        publishing.await()
        val cancel = async { scheduler.pause(task.taskId, cancel = true) }
        delay(80)
        assertFalse(cancel.isCompleted)
        finishCommit.complete(Unit)
        cancel.await()
        assertEquals(1, committed.get())
        assertNull(waitState(task.taskId, "CANCELLED").activeKey)
    }

    @Test fun recoverySeparatesUserPauseUnknownRemoteAndSafeSystemInterruption() = runBlocking {
        fun row(id: String, state: String = "RUNNING", paused: Boolean = false, unknown: Boolean = false) =
            TaskRecord(id, "TEST", id, id, id, "{}", state = state,
                userPaused = paused, pauseReason = if(paused) "USER" else null, remoteOutcomeUnknown = unknown)
        db.tasks().insert(row("safe"))
        db.tasks().insert(row("paused", paused = true))
        db.tasks().insert(row("unknown", unknown = true))
        db.tasks().insert(row("cancelling", state = "CANCELLING"))
        db.tasks().insert(row("failed", state = "FAILED"))
        start()
        assertEquals("QUEUED", db.tasks().get("safe")!!.state)
        assertEquals("PAUSED", db.tasks().get("paused")!!.state)
        assertEquals("INTERRUPTED", db.tasks().get("unknown")!!.state)
        assertEquals("CANCELLED", db.tasks().get("cancelling")!!.state)
        assertNull(db.tasks().get("cancelling")!!.activeKey)
        assertEquals("FAILED", db.tasks().get("failed")!!.state)
        scheduler.resume("unknown")
        assertEquals("INTERRUPTED", db.tasks().get("unknown")!!.state)
    }

    @Test fun publicationReceiptClosesRemoteRequestBeforeProgressCheckpoint() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val stop = CompletableDeferred<Unit>()
        scheduler.register("TEST") { execution ->
            val context = currentCoroutineContext()[TaskContext]!!
            context.onRemoteRequest("request", "page", "PREPARED")
            context.onRemoteRequest("request", "page", "SENT")
            context.onRemoteRequest("request", "page", "RECEIVED")
            assertTrue(db.tasks().get(execution.taskId)!!.remoteOutcomeUnknown)
            execution.publish { execution.commitScope("page") }
            published.complete(Unit)
            stop.await()
        }
        start()
        val task = submit("receipt")
        published.await()
        assertEquals("PUBLISHED", db.tasks().request("request")!!.status)
        assertFalse(db.tasks().get(task.taskId)!!.remoteOutcomeUnknown)
        assertEquals("{}", db.tasks().get(task.taskId)!!.checkpoint)
        scheduler.pause(task.taskId)
        assertEquals("PAUSED", db.tasks().get(task.taskId)!!.state)
    }

    @Test fun parentResumePreservesExplicitlyPausedBranch() = runBlocking {
        start()
        val parent = submit("parent", parent = "PARENT")
        val frame = submit("frame", parent = parent.taskId)
        val subtitle = submit("subtitle", parent = parent.taskId)
        scheduler.pause(subtitle.taskId)
        scheduler.pause(parent.taskId)
        assertEquals("USER", db.tasks().get(subtitle.taskId)!!.pauseReason)
        scheduler.resume(parent.taskId)
        assertEquals("QUEUED", db.tasks().get(frame.taskId)!!.state)
        assertEquals("PAUSED", db.tasks().get(subtitle.taskId)!!.state)
    }
}
