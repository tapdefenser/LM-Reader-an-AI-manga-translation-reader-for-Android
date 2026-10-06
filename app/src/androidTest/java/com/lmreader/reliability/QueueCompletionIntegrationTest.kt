package com.lmreader.reliability

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.di.AppContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QueueCompletionIntegrationTest {
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        container = AppContainer(IsolatedApp(ApplicationProvider.getApplicationContext()))
        container.startupReady.await()
        seedRow(container, "library_sources", mapOf("sourceId" to "source", "kind" to "IMAGE_DIRECTORY", "treeUri" to "content://fixture/source", "mode" to "MULTI_CHAPTER", "permission" to "OK"))
        seedRow(container, "mangas", mapOf("mangaId" to "m", "sourceId" to "source", "anchorDocumentId" to "root", "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE"))
        for(id in listOf("c", "failed", "legacy")) seedRow(container, "chapters", mapOf("chapterId" to id, "mangaId" to "m", "documentId" to id, "kind" to "IMAGE_DIRECTORY"))
        seedRow(container, "chapter_translation", mapOf("chapterId" to "c", "mangaId" to "m",
            "targetLanguage" to "简体中文", "sourceLanguage" to "英语", "configSnapshot" to "{}",
            "state" to "RUNNING", "translatedCount" to 1))
        seedRow(container, "chapter_translation", mapOf("chapterId" to "failed", "mangaId" to "m",
            "targetLanguage" to "简体中文", "sourceLanguage" to "英语", "configSnapshot" to "{}", "state" to "FAILED"))
    }
    @After fun cleanup() = runBlocking { closeContainer(container) }
    @Test fun completionLeavesQueueButKeepsChapterMetadataAndOtherTasks() = runBlocking {
        val dao = container.database.translationDao()
        seedRow(container, "chapter_translation", mapOf("chapterId" to "legacy", "mangaId" to "m",
            "targetLanguage" to "简体中文", "state" to "DONE", "translatedCount" to 2))
        dao.deleteInvalidQueueItems()
        assertEquals(2, dao.byChapter("legacy").single().translatedCount)
        assertEquals(setOf("c", "failed"), dao.observeQueue().first().map { it.chapterId }.toSet())
        dao.setQueueState("c", "简体中文", "DONE", null, 4, 1234)
        val remaining = withTimeout(5000) { dao.observeQueue().first { rows -> rows.none { it.chapterId == "c" } } }
        assertEquals(listOf("failed"), remaining.map { it.chapterId })
        assertEquals(listOf("failed"), dao.queueSnapshot().map { it.chapterId })
        // Queue-wide cancellation cannot change the saved completion status.
        dao.cancelQueueItems(listOf("c", "failed"), 5678)
        val completed = dao.byChapter("c").single()
        assertEquals("DONE", completed.state)
        assertEquals(4, completed.translatedCount)
        assertEquals(1234L, completed.translatedAt)
        assertEquals("CANCELLED", dao.byChapter("failed").single().state)
        assertTrue(dao.observeQueue().first().isEmpty())
        // Explicitly clearing translations still invalidates the completed status.
        dao.pageCleared("c", 4, 6789)
        assertEquals("CANCELLED", dao.byChapter("c").single().state)
        assertEquals(0, dao.byChapter("c").single().translatedCount)
    }
}
