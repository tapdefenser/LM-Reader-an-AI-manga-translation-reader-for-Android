package com.lmreader.screen

import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.core.model.*
import com.lmreader.core.storage.settings.ScreenTranslationPreferences
import java.io.File
import java.util.UUID
import org.junit.Test
import org.junit.Assert.*

class ScreenPendingFramesTest {
    @Test fun failedFrameSurvivesRestartAndBudgetPressureUntilItIsPublished() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "screen-pending-fixture-${UUID.randomUUID()}")
        try {
            val store = ScreenPendingFrames(root)
            val id = "screen:${UUID.randomUUID()}"
            store.writeImage(id) { it.write(ByteArray(256 * 1024) { 47 }) }
            val frame = PendingScreenFrame(id, store.file(id), PixelRect(0f, 50f, 1080f, 2300f),
                ScreenTranslationSettings(translation = MangaTranslationSettings(sourceLanguage = "ja", targetLanguage = "zh-Hans", workflowId = "original")), "{\"schema\":2,\"retries\":3}")
            store.admit(frame)
            assertEquals(1, store.count.value)
            val restarted = ScreenPendingFrames(root)
            assertTrue(restarted.bytes() > 256 * 1024)
            val restored = restarted.latest()!!
            assertEquals(frame.pageId, restored.pageId); assertEquals(frame.bounds, restored.bounds); assertEquals(frame.snapshot, restored.snapshot)
            assertEquals(ScreenTranslationPreferences.encode(frame.settings), ScreenTranslationPreferences.encode(restored.settings))
            assertTrue(store.file(id).isFile)
            restarted.complete(id)
            assertEquals(0, restarted.count.value); assertNull(restarted.latest()); assertFalse(store.file(id).exists())
        } finally { check(root.canonicalFile.parentFile == cache.canonicalFile); root.deleteRecursively() }
    }

    @Test fun onlyNeverAdmittedOrphansAreCleanedAndAtomicAdmissionCanRecover() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val root = File(cache, "screen-pending-fixture-${UUID.randomUUID()}")
        try {
            val store = ScreenPendingFrames(root)
            val orphan = "screen:${UUID.randomUUID()}"; store.writeImage(orphan) { it.write(byteArrayOf(1, 2, 3)) }
            val unfinished = File(root, "${UUID.randomUUID()}.png.new").apply { writeBytes(byteArrayOf(9)) }
            val admitted = "screen:${UUID.randomUUID()}"; store.writeImage(admitted) { it.write(byteArrayOf(4, 5, 6)) }
            store.admit(PendingScreenFrame(admitted, store.file(admitted), PixelRect(0f, 0f, 300f, 400f), ScreenTranslationSettings(), "{}"))
            val journal = File(root, store.file(admitted).nameWithoutExtension + ".json")
            assertTrue(journal.renameTo(File(root, journal.name + ".bak")))
            val reopened = ScreenPendingFrames(root)
            assertFalse(store.file(orphan).exists()); assertTrue(store.file(admitted).exists())
            assertFalse(unfinished.exists())
            // AtomicFile.openRead restores the durable .bak journal.
            assertEquals(admitted, reopened.latest()?.pageId)
        } finally { check(root.canonicalFile.parentFile == cache.canonicalFile); root.deleteRecursively() }
    }
}
