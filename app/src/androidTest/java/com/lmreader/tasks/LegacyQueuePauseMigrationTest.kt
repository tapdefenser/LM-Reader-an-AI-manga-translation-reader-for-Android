package com.lmreader.tasks

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.reliability.seedRow
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyQueuePauseMigrationTest {
    @Test fun obsoleteGlobalHoldBecomesExistingCardPausesAndOneChapterCanStartAlone() = runBlocking<Unit> {
        val app = IsolatedApp(ApplicationProvider.getApplicationContext())
        val gate = CompletableDeferred<Unit>()
        val container = AppContainer(app, schedulerFactory = {
            TaskScheduler(it, startup = gate, canStart = { false }, acquire = { null }, release = {})
        })
        try {
            val scheduler = container.taskScheduler
            container.startupReady.await()
            seedRow(container, "library_sources", mapOf("sourceId" to "source", "kind" to "IMAGE_DIRECTORY", "mode" to "MULTI_CHAPTER", "permission" to "OK"))
            seedRow(container, "mangas", mapOf("mangaId" to "manga", "sourceId" to "source", "sourceKind" to "IMAGE_DIRECTORY",
                "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE", "displayName" to "Legacy manga"))
            for(n in 1..2) seedRow(container, "chapters", mapOf("chapterId" to "chapter$n", "mangaId" to "manga", "documentId" to "document$n",
                "kind" to "IMAGE_DIRECTORY", "title" to "Chapter $n", "sortKey" to "chapter$n"))
            val dao = container.database.translationDao()
            dao.upsertAll((1..2).map { ChapterTranslationEntity("chapter$it", "manga", "zh-Hans", "PENDING", "en", false,
                "{}", 1L, null, 0, null, 1L) })
            scheduler.dao.insert(TaskRecord("legacy-root", "CHAPTER", "Legacy manga", "chapters:manga", "legacy-goal",
                """{"mangaId":"manga","chapterIds":["chapter1","chapter2"],"_pausedChapterIds":[],"_goalKey":"legacy-goal"}"""))
            val preferences = app.getSharedPreferences("unified-task-settings", 0)
            preferences.edit().putBoolean("paused", true).commit()
            gate.complete(Unit)
            withTimeout(8000) { scheduler.ready.await(); container.translationQueue.awaitReady() }
            assertEquals("PAUSED", scheduler.dao.get("legacy-root")!!.state)
            assertTrue(dao.queueSnapshot().all { it.state == "PAUSED" })
            assertFalse(preferences.contains("paused"))
            assertFalse(preferences.contains("translations-paused"))
            assertFalse(container.queueOrder.isPaused())
            val fresh = scheduler.submit("VIDEO", "Fresh video", "fresh", "fresh", """{"videoId":"fresh"}""")
            assertEquals("QUEUED", fresh.state)
            val controller = TranslationQueueController(container)
            controller.startChapter(dao.byChapter("chapter1").single())
            assertEquals("PENDING", dao.byChapter("chapter1").single().state)
            assertEquals("PAUSED", dao.byChapter("chapter2").single().state)
            assertEquals("QUEUED", scheduler.dao.get("legacy-root")!!.state)
            val protected = JSONObject(scheduler.dao.get("legacy-root")!!.payload).getJSONArray("_pausedChapterIds")
            assertEquals(listOf("chapter2"), (0 until protected.length()).map { protected.getString(it) })
        } finally {
            gate.complete(Unit)
            closeContainer(container); app.root.deleteRecursively()
        }
    }
}
