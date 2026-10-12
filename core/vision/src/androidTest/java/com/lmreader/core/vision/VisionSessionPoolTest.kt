package com.lmreader.core.vision

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.VisionExecutionSettings
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisionSessionPoolTest {
    private val cpu = VisionExecutionSettings(segGpu = false)

    @Test fun retiringModelsPreservesOccupiedInstanceAndReloadsAfterCompletion() = runBlocking {
        val pool = VisionSessionPool(ApplicationProvider.getApplicationContext(), "seg") {}
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val entered = CompletableDeferred<LocalVisionSession>()
        val finish = CompletableDeferred<Unit>()
        val work = async(Dispatchers.IO) { pool.use(1, cpu) { session ->
            session.segment("pool-retention", image)
            entered.complete(session)
            finish.await()
            assertTrue("seg" in session.residentModels())
        } }
        try {
            val original = withTimeout(20_000) { entered.await() }
            withTimeout(2_000) { pool.retain(0, false, false, emptySet()) }
            assertEquals(1, pool.activeTasks.value)
            assertTrue(pool.resources.value.any { it.inUse })
            finish.complete(Unit)
            withTimeout(5_000) { work.await() }
            assertTrue(pool.resources.value.isEmpty())
            pool.retain(1, true, false, emptySet())
            pool.use(1, cpu) { assertNotSame(original, it) }
        } finally {
            finish.complete(Unit); work.cancelAndJoin(); pool.release(); image.recycle()
        }
    }

    @Test fun backendChangeDoesNotWaitForOccupiedSessionAndCompatibleSessionsAreReused() = runBlocking {
        val pool = VisionSessionPool(ApplicationProvider.getApplicationContext(), "seg") {}
        val entered = CompletableDeferred<LocalVisionSession>()
        val finish = CompletableDeferred<Unit>()
        val work = async(Dispatchers.IO) { pool.use(2, cpu) { session ->
            entered.complete(session); finish.await()
        } }
        try {
            val original = withTimeout(5_000) { entered.await() }
            val gpu = cpu.copy(segGpu = true)
            withTimeout(2_000) { pool.configure(gpu) }
            val second = withTimeout(5_000) { pool.use(2, gpu) { it } }
            assertNotSame(original, second)
            finish.complete(Unit); withTimeout(5_000) { work.await() }
            val replacement = pool.use(1, gpu) { it }
            assertNotSame(original, replacement)
            pool.configure(gpu)
            assertSame(replacement, pool.use(1, gpu) { it })
        } finally { finish.complete(Unit); work.cancelAndJoin(); pool.release() }
    }

    @Test fun cancellingWarmupWorkersReturnsEverySlotForTheNextSession() = runBlocking {
        val pool = VisionSessionPool(ApplicationProvider.getApplicationContext(), "seg") {}
        val entered = List(2) { CompletableDeferred<Unit>() }
        val finish = CompletableDeferred<Unit>()
        val workers = entered.map { ready -> async(Dispatchers.IO) { pool.use(2, cpu) {
            ready.complete(Unit); finish.await()
        } } }
        try {
            withTimeout(5_000) { entered.forEach { it.await() } }
            withTimeout(2_000) { pool.release() }
            workers.forEach { it.cancel() }
            withTimeout(5_000) { workers.joinAll() }
            assertEquals(0, pool.activeTasks.value)
            pool.retain(2, true, false, emptySet())
            withTimeout(5_000) { pool.use(2, cpu) { } }
        } finally { workers.forEach { it.cancel() }; workers.joinAll(); pool.release() }
    }

    @Test fun releaseDoesNotEvictModelsLoadedByALaterLazyRequest() = runBlocking {
        val pool = VisionSessionPool(ApplicationProvider.getApplicationContext(), "seg") {}
        val image = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            pool.use(1, cpu) { it.segment("before-release", image) }
            pool.release()
            assertTrue(pool.resources.value.isEmpty())
            val reloaded = pool.use(1, cpu) { it.segment("after-release", image); it }
            assertTrue(pool.resources.value.isNotEmpty())
            assertSame(reloaded, pool.use(1, cpu) { it })
        } finally { pool.release(); image.recycle() }
    }
}
