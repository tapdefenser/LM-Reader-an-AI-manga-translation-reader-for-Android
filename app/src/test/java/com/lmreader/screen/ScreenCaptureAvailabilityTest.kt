package com.lmreader.screen

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ScreenCaptureAvailabilityTest {
    @Test fun acquisitionTimeoutDoesNotCancelSamplingAndANextFrameCanBeAcquired() = runTest {
        val never = CompletableDeferred<String>()
        val failure = assertFailsWith<ScreenFrameUnavailable> { awaitScreenFrame(100) { never.await() } }
        assertTrue(failure.isTemporaryScreenCaptureFailure())
        assertTrue(currentCoroutineContext().isActive)
        val next = async { delay(30); "next frame" }
        assertEquals("next frame", awaitScreenFrame(100) { next.await() })
    }
    @Test fun stoppingSamplingStillCancelsTheAcquisitionImmediately() = runTest {
        val entered = CompletableDeferred<Unit>()
        val job = async {
            awaitScreenFrame(2000) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await(); job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertEquals(0, testScheduler.currentTime)
    }
}
