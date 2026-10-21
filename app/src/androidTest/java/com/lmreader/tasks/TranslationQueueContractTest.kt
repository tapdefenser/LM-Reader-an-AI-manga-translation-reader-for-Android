package com.lmreader.tasks

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.reliability.seedRow
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class TranslationQueueContractTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private lateinit var scheduler: TaskScheduler
    private lateinit var controller: TranslationQueueController
    @Volatile private var allowed = false
    @Before fun setup() = runBlocking<Unit> {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app, schedulerFactory = {
            TaskScheduler(it, canStart = { allowed }, acquire = { null }, release = {})
        })
        scheduler = container.taskScheduler
        controller = TranslationQueueController(container)
        container.startupReady.await(); scheduler.ready.await()
        container.translationQueue.awaitReady()
        container.videoTasks
    }
    @After fun cleanup() = runBlocking<Unit> {
        closeContainer(container); app.root.deleteRecursively()
    }
    private suspend fun task(id: String, type: String = "VIDEO_FRAMES", title: String = id,
        parent: String? = null, uri: Uri? = null): TaskRecord {
        val payload = JSONObject().put(if(type == "CHAPTER") "mangaId" else "videoId", id)
        uri?.let { payload.put("uri", it.toString()) }
        return scheduler.submit(type, title, "owner:$id:${parent ?: type}", "goal:$id:$type:${parent.orEmpty()}",
            payload.toString(), parentTaskId = parent)
    }
    private suspend fun await(predicate: suspend () -> Boolean) = withTimeout(8_000) {
        while(!predicate()) delay(10)
    }
    @Test fun projectionKeepsMangaChaptersAndFailedItemsButExcludesOtherWorkAndHistory() {
        fun row(id: String, type: String, state: String = "QUEUED") = TaskRecord(id, type, id, id, null,
            JSONObject().put("mangaId", "manga").put("videoId", id).toString(), state = state)
        val chapter = ChapterTranslationEntity("chapter", "manga", "zh-Hans", "PAUSED", "en", false, null,
            1L, null, 0, null, 1L)
        val rows = listOf(row("m", "CHAPTER"), row("video", "VIDEO"), row("export", "EXPORT"),
            row("model", "MODEL_INSTALL"), row("scan", "SOURCE_SCAN"), row("done", "VIDEO", "SUCCEEDED"),
            row("cancel", "VIDEO", "CANCELLED"), row("failed", "VIDEO_FRAMES", "FAILED"))
        val groups = translationQueueGroups(rows, listOf(chapter))
        assertEquals(setOf("manga:manga", "video:video", "video:failed"), groups.map { it.key }.toSet())
        assertEquals(listOf(chapter), groups.single { it.mangaId != null }.chapters)
        assertTrue(groups.flatMap { it.tasks }.any { it.state == "FAILED" })
    }
    @Test fun continueRestoresIndividualPausesAndInterruptionsWithoutRetryingFailuresOrOtherCategories() = runBlocking {
        val parent = task("parent", "VIDEO")
        val first = task("parent", parent = parent.taskId)
        val second = task("parent", "VIDEO_SUBTITLES", parent = parent.taskId)
        val failed = task("failed")
        val unknown = task("unknown")
        val export = task("export", "EXPORT")
        scheduler.pause(parent.taskId)
        scheduler.pause(first.taskId)
        scheduler.dao.update(scheduler.dao.get(second.taskId)!!.copy(state = "INTERRUPTED", userPaused = false))
        scheduler.dao.update(failed.copy(state = "FAILED", activeKey = null))
        scheduler.dao.update(unknown.copy(state = "PAUSED", userPaused = true, pauseReason = "USER", remoteOutcomeUnknown = true))
        scheduler.pause(export.taskId)
        controller.continueAll()
        assertEquals("QUEUED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("QUEUED", scheduler.dao.get(second.taskId)!!.state)
        assertEquals("FAILED", scheduler.dao.get(failed.taskId)!!.state)
        assertEquals("PAUSED", scheduler.dao.get(unknown.taskId)!!.state)
        assertEquals("PAUSED", scheduler.dao.get(export.taskId)!!.state)
        scheduler.pause(failed.taskId, cancel = true)
        assertEquals("CANCELLED", scheduler.dao.get(failed.taskId)!!.state)
        assertFalse(scheduler.dao.get(failed.taskId)!!.inTranslationQueue())
    }
    @Test fun sharedMoveChangesActualDispatchAndKeepsEveryBatchOfAMangaTogether() = runBlocking {
        val sequence = CopyOnWriteArrayList<String>()
        scheduler.setOrderMode("ORDER")
        scheduler.register("CHAPTER") { sequence.add(it.initial.title) }
        scheduler.register("VIDEO_FRAMES") { sequence.add(it.initial.title) }
        val a = task("manga", "CHAPTER", "manga batch one")
        // A second batch has a distinct persistent goal, but belongs to the same manga group.
        scheduler.submit("CHAPTER", "manga batch two", "owner:manga:CHAPTER", "other-batch", a.payload)
        val video = task("video", title = "video")
        scheduler.orderTranslationGroups(listOf("video:video", "manga:manga"))
        controller.moveGroup("manga:manga", -1)
        val generation = scheduler.dao.get(a.taskId)!!.generation
        allowed = true; scheduler.poke()
        await { sequence.size == 3 }
        assertEquals(listOf("manga batch one", "manga batch two", "video"), sequence.toList())
        assertEquals(generation + 1, scheduler.dao.get(a.taskId)!!.generation)
        await { scheduler.dao.get(video.taskId)?.state == "SUCCEEDED" }
        assertTrue(translationQueueGroups(scheduler.dao.all(), emptyList()).isEmpty())
    }
    @Test fun allSortDirectionsPersistSharedOrderAndUnknownModifiedTimesStayLast() = runBlocking {
        val early = File(app.cacheDir, "early.mp4").apply { writeText("fixture"); assertTrue(setLastModified(100_000)) }
        val late = File(app.cacheDir, "late.mp4").apply { writeText("fixture"); assertTrue(setLastModified(200_000)) }
        task("ten", title = "10", uri = Uri.fromFile(early))
        task("two", title = "2", uri = Uri.fromFile(late))
        task("a", title = "A")
        suspend fun names() = scheduler.dao.all().filter { it.parentTaskId == null }.map { it.title }
        controller.sort("自然数", true); assertEquals(listOf("2", "10", "A"), names())
        controller.sort("自然数", false); assertEquals(listOf("A", "10", "2"), names())
        controller.sort("首字母", true); assertEquals(listOf("10", "2", "A"), names())
        controller.sort("首字母", false); assertEquals(listOf("A", "2", "10"), names())
        controller.sort("修改时间", true); assertEquals(listOf("10", "2", "A"), names())
        controller.sort("修改时间", false); assertEquals(listOf("2", "10", "A"), names())
        container.queueOrder.reload()
        assertEquals(listOf("video:two", "video:ten", "video:a"), container.queueOrder.translationGroupOrder(listOf("video:a", "video:ten", "video:two")))
    }
    @Test fun bulkPauseAndStartActOnEachExistingCardWithoutHoldingNewSubmissions() = runBlocking {
        val first = task("first")
        val second = task("second")
        val failed = task("failed")
        scheduler.dao.update(failed.copy(state = "FAILED", activeKey = null))
        controller.pauseAll()
        assertEquals("PAUSED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("PAUSED", scheduler.dao.get(second.taskId)!!.state)
        controller.startTask(first.taskId)
        assertEquals("QUEUED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("PAUSED", scheduler.dao.get(second.taskId)!!.state)
        val fresh = task("fresh")
        assertEquals("QUEUED", scheduler.dao.get(fresh.taskId)!!.state)
        controller.pauseAll()
        assertEquals("PAUSED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("PAUSED", scheduler.dao.get(second.taskId)!!.state)
        assertEquals("PAUSED", scheduler.dao.get(fresh.taskId)!!.state)
        controller.continueAll()
        assertEquals("QUEUED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("QUEUED", scheduler.dao.get(second.taskId)!!.state)
        assertEquals("QUEUED", scheduler.dao.get(fresh.taskId)!!.state)
        assertEquals("FAILED", scheduler.dao.get(failed.taskId)!!.state)
    }
    @Test fun startingOneChapterProtectsTheOtherAndBulkStartRestoresInterruptionsOnly() = runBlocking {
        seedRow(container, "library_sources", mapOf("sourceId" to "chapter-source", "kind" to "IMAGE_DIRECTORY",
            "mode" to "MULTI_CHAPTER", "permission" to "OK", "treeUri" to "content://fixture/chapter-source"))
        seedRow(container, "mangas", mapOf("mangaId" to "chapter-manga", "sourceId" to "chapter-source",
            "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE"))
        for((n, id) in listOf("chapter-one", "chapter-two").withIndex()) {
            seedRow(container, "chapters", mapOf("chapterId" to id, "mangaId" to "chapter-manga",
                "documentId" to "chapter-document-$id", "kind" to "IMAGE_DIRECTORY", "title" to "Chapter ${n + 1}",
                "sortKey" to id, "pageCount" to 1, "position" to n + 1))
            container.database.translationDao().upsertAll(listOf(ChapterTranslationEntity(id, "chapter-manga", "en", "PAUSED",
                "zh-Hans", false, """{"sourceLanguage":"zh-Hans","targetLanguage":"en"}""", n + 1L, null, 0, null, n + 1L)))
        }
        val payload = JSONObject().put("mangaId", "chapter-manga")
            .put("chapterIds", JSONArray(listOf("chapter-one", "chapter-two")))
            .put("_pausedChapterIds", JSONArray(listOf("chapter-one", "chapter-two")))
        val root = scheduler.submit("CHAPTER", "Chapter Manga", "chapters:chapter-manga", "chapter-root",
            payload.toString())
        await { container.translationQueue.items.value.map { it.chapterId }.toSet() ==
            setOf("chapter-one", "chapter-two") }
        suspend fun protectedIds(id: String) = JSONObject(scheduler.dao.get(id)!!.payload)
            .optJSONArray("_pausedChapterIds")?.let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }.orEmpty()
        val first = container.translationQueue.items.value.single { it.chapterId == "chapter-one" }
        controller.startChapter(first)
        await { container.translationQueue.items.value.single { it.chapterId == "chapter-one" }.state == "PENDING" }
        assertEquals("PAUSED", container.translationQueue.items.value.single { it.chapterId == "chapter-two" }.state)
        assertEquals("QUEUED", scheduler.dao.get(root.taskId)!!.state)
        assertEquals(setOf("chapter-two"), protectedIds(root.taskId))
        controller.continueAll()
        await { container.translationQueue.items.value.map { it.chapterId to it.state }.toSet() ==
            setOf("chapter-one" to "PENDING", "chapter-two" to "PENDING") && protectedIds(root.taskId).isEmpty() }
        assertTrue(protectedIds(root.taskId).isEmpty())
        val chapterDao = container.database.translationDao()
        chapterDao.upsertAll(chapterDao.queueSnapshot().map { it.copy(
            state = if(it.chapterId == "chapter-one") "INTERRUPTED" else "FAILED") })
        scheduler.dao.update(scheduler.dao.get(root.taskId)!!.copy(state = "INTERRUPTED", userPaused = false))
        controller.continueAll()
        assertEquals("PENDING", chapterDao.byChapter("chapter-one").single().state)
        assertEquals("FAILED", chapterDao.byChapter("chapter-two").single().state)
        assertEquals("QUEUED", scheduler.dao.get(root.taskId)!!.state)
    }
    @Test fun cancelAllRemovesFailuresAndLeavesNoQueueWidePauseIntentForFutureSubmissions() = runBlocking {
        val first = task("first")
        val failed = task("failed")
        scheduler.dao.update(failed.copy(state = "FAILED", activeKey = null))
        controller.cancelAll()
        assertEquals("CANCELLED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("CANCELLED", scheduler.dao.get(failed.taskId)!!.state)
        controller.pauseAll()
        controller.cancelAll()
        assertEquals("CANCELLED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("CANCELLED", scheduler.dao.get(failed.taskId)!!.state)
        assertEquals("QUEUED", task("new-after-cancel").state)
    }
    @Test fun bulkCancellationPersistsEveryIntentBeforeWaitingForNativeCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val nativeDone = CompletableDeferred<Unit>()
        scheduler.register("VIDEO_FRAMES") {
            entered.complete(Unit)
            withContext(NonCancellable) { nativeDone.await() }
        }
        scheduler.register("VIDEO_SUBTITLES") { awaitCancellation() }
        val first = task("native")
        val second = task("later", "VIDEO_SUBTITLES")
        allowed = true; scheduler.poke(); entered.await()
        val command = async { controller.cancelAll() }
        try {
            await { scheduler.dao.get(first.taskId)?.state == "CANCELLING" }
            assertEquals("CANCELLING", scheduler.dao.get(second.taskId)!!.state)
            assertFalse(command.isCompleted)
        } finally { nativeDone.complete(Unit) }
        command.await()
        assertEquals("CANCELLED", scheduler.dao.get(first.taskId)!!.state)
        assertEquals("CANCELLED", scheduler.dao.get(second.taskId)!!.state)
    }
}
