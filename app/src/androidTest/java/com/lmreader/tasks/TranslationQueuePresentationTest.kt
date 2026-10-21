package com.lmreader.tasks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.reliability.seedRow
import kotlinx.coroutines.runBlocking
import org.junit.*

class TranslationQueuePresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app, schedulerFactory = { TaskScheduler(it, canStart = { false }, acquire = { null }, release = {}) })
        container.startupReady.await(); container.taskScheduler.ready.await(); container.translationQueue.awaitReady()
        seedRow(container, "library_sources", mapOf("sourceId" to "source", "kind" to "IMAGE_DIRECTORY", "mode" to "MULTI_CHAPTER", "permission" to "OK"))
        seedRow(container, "mangas", mapOf("mangaId" to "manga", "sourceId" to "source", "sourceKind" to "IMAGE_DIRECTORY",
            "layoutMode" to "MULTI_CHAPTER", "displayName" to "漫画 2", "availability" to "AVAILABLE"))
        for(n in 1..2) seedRow(container, "chapters", mapOf("chapterId" to "chapter$n", "mangaId" to "manga", "documentId" to "chapter-document$n", "kind" to "IMAGE_DIRECTORY",
            "title" to "Chapter $n", "sortKey" to "chapter$n", "pageCount" to 2, "position" to n))
        container.database.translationDao().upsertAll((1..2).map { ChapterTranslationEntity("chapter$it", "manga", "zh-Hans", "PAUSED",
            "en", false, null, 1L, null, 0, null, 1L) })
        container.taskScheduler.submit("CHAPTER", "漫画 2", "chapters:manga", "manga", """{"mangaId":"manga","chapterIds":["chapter1","chapter2"]}""", userPaused = true)
        container.taskScheduler.submit("VIDEO", "视频 10", "video:video", "video", """{"videoId":"video"}""", userPaused = true)
        container.taskScheduler.submit("EXPORT", "SHOULD_NOT_SHOW_EXPORT", "export", "export", "{}")
        val done = container.taskScheduler.submit("VIDEO", "SHOULD_NOT_SHOW_DONE", "done", "done", """{"videoId":"done"}""")
        container.workDatabase.tasks().update(done.copy(state = "SUCCEEDED", activeKey = null))
    }
    @After fun cleanup() = runBlocking<Unit> { closeContainer(container); app.root.deleteRecursively() }
    private fun show() {
        compose.setContent { MaterialTheme { UnifiedTaskScreen(container, onBack = {}) } }
        compose.waitUntil(8000) { compose.onAllNodesWithText("漫画 2").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun mangaHasIndentedChapterCardsAndSharesOneTranslationOnlyListWithVideo() {
        show()
        compose.onNodeWithTag("translation-group:manga:manga").assertExists()
        compose.onNodeWithTag("translation-chapter:chapter1").assertExists()
        compose.onNodeWithTag("translation-chapter:chapter2").assertExists()
        val group = compose.onNodeWithTag("translation-group:manga:manga").fetchSemanticsNode().boundsInRoot
        val chapter = compose.onNodeWithTag("translation-chapter:chapter1").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(chapter.left > group.left)
        Assert.assertTrue(chapter.top >= group.bottom)
        compose.onNodeWithTag("translation-queue-list").performScrollToNode(hasText("视频 10"))
        compose.onNodeWithText("视频 10").assertExists()
        compose.onNodeWithText("SHOULD_NOT_SHOW_EXPORT").assertDoesNotExist()
        compose.onNodeWithText("SHOULD_NOT_SHOW_DONE").assertDoesNotExist()
        compose.onNodeWithText("翻译队列").assertExists()
        compose.onNodeWithText("维护").assertDoesNotExist()
    }
    @Test fun menuRestoresBulkActionsAndEachSortOpensDirectionSubmenu() {
        show()
        for(mode in listOf("自然数", "修改时间", "首字母")) {
            compose.onNodeWithContentDescription("队列菜单").performClick()
            compose.onNodeWithText("全部开始").assertIsEnabled()
            compose.onNodeWithText("全部暂停").assertIsEnabled()
            compose.onNodeWithText("全部取消").assertIsEnabled()
            compose.onNodeWithText(mode).performScrollTo().performClick()
            compose.onNodeWithText("升序").assertIsDisplayed()
            compose.onNodeWithText("降序").assertIsDisplayed().performClick()
            compose.waitForIdle()
        }
    }
    @Test fun videoResultsRemainAvailableFromExportQueue() {
        runBlocking {
            val task = container.taskScheduler.submit("VIDEO_EXPORT", "VIDEO_EXPORT_READY", "video-export", "video-export", "{}")
            container.workDatabase.tasks().update(task.copy(state = "SUCCEEDED", activeKey = null,
                checkpoint = """{"outputUri":"content://fixture/result.zip","mime":"application/zip"}"""))
        }
        compose.setContent { MaterialTheme { com.lmreader.ui.queue.ExportQueueScreen(container, onBack = {}, onSettings = {}) } }
        compose.waitUntil(8000) { compose.onAllNodesWithText("VIDEO_EXPORT_READY").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("VIDEO_EXPORT_READY").assertExists()
        compose.onNodeWithContentDescription("导出操作").performClick()
        compose.onNodeWithText("分享导出文件").assertExists()
    }
}
