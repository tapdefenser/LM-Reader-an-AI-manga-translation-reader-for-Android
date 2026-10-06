package com.lmreader.tasks

import android.app.Notification
import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.lmreader.di.AppContainer
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.reliability.seedRow
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class TaskNotificationsIntegrationTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    @Before fun permit() {
        if(android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
    }
    @After fun cleanup() { manager.cancelAll() }
    @Test fun independentCardsContainRealProgressAndResultsRemainDismissible() {
        val renderer = TaskNotificationRenderer(context)
        renderer.ensureChannel()
        val translation = TaskNotice(TaskQueue.TRANSLATION, TaskNoticePhase.RUNNING, TaskNoticeItem("t", "RUNNING", "Generated manga", "Chapter 1", 3, 7), 2, seg = 1, ocr = 2, api = 3)
        val export = TaskNotice(TaskQueue.EXPORT, TaskNoticePhase.RUNNING, TaskNoticeItem("e", "RUNNING", "Other fixture", "Chapter 2", 1, 4), 1)
        val t = renderer.queue(translation); val e = renderer.queue(export)
        manager.notify(TaskNotificationRenderer.id(TaskQueue.TRANSLATION), t)
        manager.notify(TaskNotificationRenderer.id(TaskQueue.EXPORT), e)
        manager.notify(TaskNotificationRenderer.FOREGROUND_ID, renderer.summary(listOf(translation, export), true))
        val posted = manager.activeNotifications.associate { it.id to it.notification }
        assertEquals(3, posted.size)
        assertEquals(7, t.extras.getInt(Notification.EXTRA_PROGRESS_MAX)); assertEquals(3, t.extras.getInt(Notification.EXTRA_PROGRESS))
        assertEquals(4, e.extras.getInt(Notification.EXTRA_PROGRESS_MAX)); assertEquals(1, e.extras.getInt(Notification.EXTRA_PROGRESS))
        assertTrue(t.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Generated manga"))
        assertTrue(t.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString().contains("API 3"))
        assertNotEquals(t.contentIntent, e.contentIntent)
        assertNotEquals(t.actions.first().actionIntent, e.actions.first().actionIntent)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("cmd statusbar expand-notifications").close()
        android.os.SystemClock.sleep(2500)
        val snapshot = checkNotNull(automation.takeScreenshot())
        try { java.io.File(context.filesDir, "notification-progress.png").outputStream().use { snapshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { snapshot.recycle(); automation.executeShellCommand("cmd statusbar collapse").close() }
        val result = renderer.queue(export.copy(phase = TaskNoticePhase.COMPLETED, remaining = 0, item = export.item.copy(completed = 4), completedChapters = 1))
        manager.notify(TaskNotificationRenderer.id(TaskQueue.EXPORT), result)
        assertEquals(0, result.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(result.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(1, result.actions.size)
        val failed = renderer.queue(export.copy(phase = TaskNoticePhase.FAILED, item = export.item.copy(error = "Generated storage error"), failures = 1))
        assertTrue(failed.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Generated storage error"))
        assertEquals(0, failed.flags and Notification.FLAG_ONGOING_EVENT)
    }
    @Test fun actualNotificationButtonsControlOnlyTheirOwnQueue() = runBlocking {
        val container = AppContainer.from(context)
        container.startupReady.await()
        val oldT = container.translationQueue.paused.value; val oldE = container.exportQueue.paused.value
        container.taskService.setVisible(true); container.taskService.allowRetry()
        container.translationQueue.resume(); container.exportQueue.resumeAll()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val acquired = CompletableDeferred<Unit>()
        val holder = scope.launch {
            val lease = container.taskService.acquire()
            try { acquired.complete(Unit); awaitCancellation() } finally { container.taskService.release(lease) }
        }
        try {
            withTimeout(15_000) { acquired.await() }
            val renderer = container.taskNotifications.renderer
            fun card(queue: TaskQueue, phase: TaskNoticePhase) = renderer.queue(TaskNotice(queue, phase, TaskNoticeItem("generated", "RUNNING"), 1))
            card(TaskQueue.TRANSLATION, TaskNoticePhase.RUNNING).actions.first().actionIntent.send()
            withTimeout(5000) { while(!container.translationQueue.paused.value) delay(50) }
            assertFalse(container.exportQueue.paused.value)
            container.taskService.setVisible(false)
            card(TaskQueue.TRANSLATION, TaskNoticePhase.PAUSED).actions.first().actionIntent.send()
            withTimeout(5000) { while(container.translationQueue.paused.value) delay(50) }
            assertFalse(container.exportQueue.paused.value)
            card(TaskQueue.EXPORT, TaskNoticePhase.RUNNING).actions.first().actionIntent.send()
            withTimeout(5000) { while(!container.exportQueue.paused.value) delay(50) }
            assertFalse(container.translationQueue.paused.value)
            card(TaskQueue.EXPORT, TaskNoticePhase.PAUSED).actions.first().actionIntent.send()
            withTimeout(5000) { while(container.exportQueue.paused.value) delay(50) }
        } finally {
            holder.cancelAndJoin(); scope.cancel()
            if(oldT) container.translationQueue.pause() else container.translationQueue.resume()
            if(oldE) container.exportQueue.pauseAll() else container.exportQueue.resumeAll()
            container.taskService.setVisible(false)
        }
    }
    @Test fun actualQueueCompletionRemainsAfterTheForegroundServiceStopsAndCancellationDoesNotClaimSuccess() = runBlocking {
        val container = AppContainer.from(context)
        container.startupReady.await()
        val queue = container.translationQueue
        val oldPaused = queue.paused.value
        queue.pauseAndAwait()
        val token = "notification-${UUID.randomUUID()}"
        val sql = container.database.openHelper.writableDatabase
        val dao = container.database.translationDao()
        seedRow(container, "library_sources", mapOf("sourceId" to token, "kind" to "IMAGE_DIRECTORY", "treeUri" to "content://fixture/$token", "mode" to "MULTI_CHAPTER", "permission" to "OK"))
        seedRow(container, "mangas", mapOf("mangaId" to token, "sourceId" to token, "anchorDocumentId" to token, "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE", "displayName" to "Notification observer fixture"))
        val ids = listOf("$token-a", "$token-b", "$token-failed")
        ids.forEach { id -> seedRow(container, "chapters", mapOf("chapterId" to id, "mangaId" to token, "documentId" to id, "kind" to "IMAGE_DIRECTORY", "title" to "Generated chapter", "pageCount" to 4)) }
        suspend fun seed(id: String, state: String) = dao.upsertAll(listOf(ChapterTranslationEntity(
            chapterId = id, mangaId = token, targetLanguage = "zh-Hans", sourceLanguage = "en",
            autoDetectSource = false, configSnapshot = "{}", state = state, translatedCount = 2,
            queuedAt = System.currentTimeMillis(), translatedAt = null, failure = null, updatedAt = System.currentTimeMillis()
        )))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var holder: Job? = null
        var checkpoint = "queued paused row"
        try {
            seed(ids[0], "PAUSED")
            withTimeout(5000) { queue.items.first { it.any { row -> row.chapterId == ids[0] } } }
            container.taskService.setVisible(true); container.taskService.allowRetry()
            val acquired = CompletableDeferred<Unit>()
            holder = scope.launch {
                val lease = container.taskService.acquire()
                try { acquired.complete(Unit); awaitCancellation() } finally { container.taskService.release(lease) }
            }
            withTimeout(15000) { acquired.await() }
            checkpoint = "paused notification state"
            withTimeout(5000) { container.taskNotifications.notices.first { it[TaskQueue.TRANSLATION]?.phase == TaskNoticePhase.PAUSED } }
            checkpoint = "posted paused card"
            val pausedCard = withTimeout(5000) {
                var card: Notification? = null
                while(card == null) { card = manager.activeNotifications.firstOrNull { it.id == TaskNotificationRenderer.id(TaskQueue.TRANSLATION) }?.notification; if(card == null) delay(50) }
                card
            }
            assertEquals(4, pausedCard.extras.getInt(Notification.EXTRA_PROGRESS_MAX)); assertEquals(2, pausedCard.extras.getInt(Notification.EXTRA_PROGRESS))
            assertTrue(pausedCard.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Notification observer fixture"))
            dao.setQueueState(ids[0], "zh-Hans", "DONE", null, 4, System.currentTimeMillis())
            checkpoint = "completed notification state"
            withTimeout(5000) { container.taskNotifications.notices.first { it[TaskQueue.TRANSLATION]?.phase == TaskNoticePhase.COMPLETED } }
            container.taskService.setVisible(false)
            holder.cancelAndJoin(); holder = null
            checkpoint = "foreground service idle"
            withTimeout(5000) { while(container.taskService.canStart()) delay(50) }
            val result = manager.activeNotifications.single { it.id == TaskNotificationRenderer.id(TaskQueue.TRANSLATION) }.notification
            assertEquals(4, result.extras.getInt(Notification.EXTRA_PROGRESS)); assertEquals(0, result.flags and Notification.FLAG_ONGOING_EVENT)
            assertEquals(0, manager.activeNotifications.single { it.id == TaskNotificationRenderer.FOREGROUND_ID }.notification.flags and Notification.FLAG_ONGOING_EVENT)
            seed(ids[1], "PAUSED")
            checkpoint = "second paused state"
            withTimeout(5000) { container.taskNotifications.notices.first { it[TaskQueue.TRANSLATION]?.phase == TaskNoticePhase.PAUSED } }
            dao.cancelQueueItems(listOf(ids[1]), System.currentTimeMillis())
            checkpoint = "canceled notification removal"
            withTimeout(5000) { container.taskNotifications.notices.first { it[TaskQueue.TRANSLATION] == null } }
            // A notification resume restarts paused chapters, retaining failed chapters for explicit retry.
            seed(ids[1], "PAUSED"); seed(ids[2], "FAILED")
            checkpoint = "two queued rows"
            withTimeout(5000) { queue.items.first { rows -> rows.count { it.mangaId == token } == 2 } }
            val oldFailure = dao.byChapter(ids[2]).single()
            checkpoint = "resume card state"
            withTimeout(5000) { container.taskNotifications.notices.first { it[TaskQueue.TRANSLATION]?.phase == TaskNoticePhase.PAUSED } }
            checkpoint = "posted resume card"
            val expectedResume = container.taskNotifications.renderer.queue(checkNotNull(container.taskNotifications.notices.value[TaskQueue.TRANSLATION])).actions.first().actionIntent
            val resumeCard = withTimeout(5000) {
                var card: Notification? = null
                while(card == null) {
                    card = manager.activeNotifications.firstOrNull { it.id == TaskNotificationRenderer.id(TaskQueue.TRANSLATION) && it.notification.actions?.firstOrNull()?.actionIntent == expectedResume }?.notification
                    if(card == null) delay(50)
                }
                card
            }
            resumeCard.actions.first().actionIntent.send()
            checkpoint = "background runner admitted"
            // Invalid generated snapshot fails only after the background runner is admitted.
            withTimeout(10000) { queue.items.first { rows -> rows.any { it.chapterId == ids[1] && it.state == "FAILED" } } }
            assertEquals("FAILED", dao.byChapter(ids[1]).single().state)
            assertEquals("FAILED", dao.byChapter(ids[2]).single().state)
            assertEquals(oldFailure.updatedAt, dao.byChapter(ids[2]).single().updatedAt)
        } catch(error: Exception) {
            throw AssertionError("$checkpoint: rows=${queue.items.value.map { it.chapterId to it.state }} notices=${container.taskNotifications.notices.value}", error)
        } finally {
            holder?.cancelAndJoin(); scope.cancel()
            queue.pauseAndAwait()
            dao.deleteChapters(ids)
            sql.execSQL("DELETE FROM mangas WHERE mangaId=?", arrayOf(token))
            sql.execSQL("DELETE FROM library_sources WHERE sourceId=?", arrayOf(token))
            if(!oldPaused) queue.resume()
            container.taskService.setVisible(false)
        }
    }
}
