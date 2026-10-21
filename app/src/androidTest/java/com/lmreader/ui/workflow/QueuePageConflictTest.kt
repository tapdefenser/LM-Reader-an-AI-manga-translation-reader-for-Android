package com.lmreader.ui.workflow

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.storage.reader.PageGeometry
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.vision.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.tasks.TaskExecutor
import com.lmreader.tasks.TaskScheduler
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.queue.MangaPagePipeline
import com.lmreader.ui.queue.QueuePageWork
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import com.lmreader.ui.reader.translation.PageTranslationRevisionConflict
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Generated pixels plus a deterministic SEG stub; no native model or network request. */
@RunWith(AndroidJUnit4::class)
class QueuePageConflictTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private val mainPage = ReaderPage("conflict-main", 0, "main.png", "main.png")
    private val sidePage = ReaderPage("conflict-side", 1, "side.png", "side.png")
    private val pages = listOf(mainPage, sidePage)
    private lateinit var bytes: ByteArray
    private val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.ENGLISH,
        "", BubbleRenderSettings(), .35f)
    private val program get() = WorkflowTemplates.blank()
    private val store get() = container.localPageTranslator.artifacts
    private val hash get() = ReaderPageArtifactStore.sha256(bytes)
    private val source = object : PageSource {
        override suspend fun pages() = pages
        override suspend fun open(page: ReaderPage) = bytes.inputStream()
        override suspend fun probe(page: ReaderPage) = PageGeometry(40, 50)
    }

    @Before fun setup() = runBlocking<Unit> {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app) {
            TaskScheduler(it, canStart = { true }, acquire = { null }, release = {})
        }
        container.startupReady.await()
        container.taskScheduler.ready.await()
        val bitmap = Bitmap.createBitmap(40, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
    }

    @After fun cleanup() = runBlocking<Unit> {
        if (::container.isInitialized) {
            runCatching { withTimeout(5_000) { container.taskScheduler.interrupt("TEST_CLEANUP") } }
            runCatching { closeContainer(container) }
        }
        if (::app.isInitialized) app.root.deleteRecursively()
        Unit
    }

    private fun host(
        expected: Map<String, String?> = emptyMap(),
        gate: (suspend () -> Unit)? = null,
        segments: AtomicInteger = AtomicInteger(),
        commits: AtomicInteger = AtomicInteger(),
        chapterPages: List<ReaderPage> = pages,
        overwrite: Boolean = false,
        commit: suspend (suspend () -> Unit) -> Unit = { it() },
    ) = object : AndroidWorkflowHost(app, container, "conflict-manga", "fixture",
        listOf(WorkflowChapterInput("conflict-chapter", "fixture", source, chapterPages)),
        settings, TranslationCacheBudget(32L * 1_048_576), overwriteExisting = overwrite,
        expectedRevisions = expected, restartConflictingPages = true, commitGate = commit) {
        override suspend fun segmentPage(pageId: String, bitmap: Bitmap, sha256: String, frame: WorkflowFrame): WorkflowPageSegmentation {
            segments.incrementAndGet()
            gate?.invoke()
            val regions = listOf(SegRegion("$pageId:region", RegionKind.BUBBLE, PixelRect(2f, 2f, 35f, 45f), 1f))
            return WorkflowPageSegmentation(SegResult(pageId, 40, 50, regions, 0, "fixture"), regions)
        }
        override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue =
            error("queue conflict test must not call an API")
        override suspend fun previewPage(frame: WorkflowFrame) {}
        override suspend fun pagePublished(frame: WorkflowFrame, saved: ReaderPageTranslation) {
            commits.incrementAndGet()
        }
    }

    private suspend fun firstResults(): Pair<ReaderPageTranslation, ReaderPageTranslation> {
        val host = host()
        try {
            WorkflowRuntime().execute(program, host)
            return host.published.getValue(mainPage.pageId) to host.published.getValue(sidePage.pageId)
        } finally {
            host.close()
        }
    }

    @Test fun legacyIdentityLessMainIsReplacedWhileSidePageResultStaysIntact() = runBlocking<Unit> {
        val (_, side) = firstResults()
        val old = store.save(mainPage.pageId, hash, LocalTranslationLanguage.JAPANESE,
            settings.target, 40, 50, emptyList(), emptyList(), 0)
        val segments = AtomicInteger()
        val host = host(segments = segments)
        try {
            WorkflowRuntime().execute(program, host)
            assertEquals("only the conflicting main page may be segmented", 1, segments.get())
            val current = requireNotNull(store.current(mainPage.pageId))
            assertNotEquals(old.revision, current.revision)
            assertNotNull("the replacement must carry a result identity", current.resultIdentity)
            val sideNow = requireNotNull(store.current(sidePage.pageId))
            assertEquals(side.revision, sideNow.revision)
            assertEquals(side.regions, sideNow.regions)
        } finally {
            host.close()
        }
    }

    @Test fun legacyAdmissionUsesTheAutomaticConflictPathForChangedRevisionAndSemantics() = runBlocking<Unit> {
        val (saved, side) = firstResults()
        val task = ChapterTranslationEntity("legacy-chapter", "conflict-manga", settings.target.tag,
            "PENDING", settings.source.tag, false, null, 1, null, 0, null, 1)
        for(changeRevision in listOf(true, false)) coroutineScope {
            val work = QueuePageWork(task, source, mainPage, settings.source, settings.target, settings.render,
                .35f, 0, resultIdentity = if(changeRevision) saved.resultIdentity else "different-semantics",
                baseRevision = if(changeRevision) "different-revision" else saved.revision)
            val pipeline = MangaPagePipeline(this, listOf(work), container.localPageTranslator,
                TranslationCacheBudget(32L * 1_048_576), 1, 1)
            try {
                val failure = runCatching { pipeline.await(mainPage.pageId) }.exceptionOrNull()
                assertTrue("Legacy admission must reach the queue's conflict retry handler: $failure",
                    failure is PageTranslationRevisionConflict)
                assertEquals(saved.revision, store.current(mainPage.pageId)!!.revision)
                assertEquals(side.revision, store.current(sidePage.pageId)!!.revision)
            } finally { pipeline.close() }
        }
    }

    @Test fun casConflictDuringFirstSegRequeuesThenResegmentsWithTheSameTask() = runBlocking<Unit> {
        val (beforeMain, beforeSide) = firstResults()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val segments = AtomicInteger()
        container.taskScheduler.register("TEST_CONFLICT", TaskExecutor { execution ->
            val attempt = attempts.incrementAndGet()
            val gate: (suspend () -> Unit)? = if (attempt == 1) ({
                // Fault injection: the conflicting attempt also has a response whose outcome was lost.
                val context = currentCoroutineContext()[TaskContext]!!
                context.onRemoteRequest("lost-conflict-response", mainPage.pageId, "PREPARED")
                context.onRemoteRequest("lost-conflict-response", mainPage.pageId, "SENT")
                context.onRemoteRequest("lost-conflict-response", mainPage.pageId, "UNKNOWN")
                entered.complete(Unit)
                release.await()
            }) else null
            val host = host(
                expected = mapOf(mainPage.pageId to beforeMain.revision),
                gate = gate,
                segments = segments,
                chapterPages = listOf(mainPage),
                overwrite = true,
                commit = { action -> execution.publish(action) },
            )
            try {
                WorkflowRuntime().execute(program, host)
            } finally {
                host.close()
            }
        })
        val task = container.taskScheduler.submit("TEST_CONFLICT", "page conflict", "test:owner", "test:active", "{}")
        var human: ReaderPageTranslation? = null
        try {
            withTimeout(15_000) { entered.await() }
            human = container.localPageTranslator.pageWriteMutex.withLock {
                store.saveEdits(beforeMain, beforeMain.regions.map { it.copy(translatedText = "human-during-seg") })
            }
            release.complete(Unit)
            withTimeout(20_000) {
                while (true) {
                    val state = container.workDatabase.tasks().get(task.taskId)?.state
                    if (state in setOf("SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED")) return@withTimeout
                    delay(25)
                }
            }
            val row = requireNotNull(container.workDatabase.tasks().get(task.taskId))
            assertEquals("conflict must requeue rather than fail the task", "SUCCEEDED", row.state)
            assertEquals(task.taskId, row.taskId)
            assertEquals("one conflicting attempt plus one clean attempt", 2, attempts.get())
            assertEquals("RETRY_AUTHORIZED", container.workDatabase.tasks().request("lost-conflict-response")!!.status)
            assertEquals("the main page must be segmented on both attempts", 2, segments.get())
            val edited = requireNotNull(human)
            assertEquals("human-during-seg", edited.regions.single().translatedText)
            val current = requireNotNull(store.current(mainPage.pageId))
            assertNotEquals(edited.revision, current.revision)
            assertNotEquals(beforeMain.revision, current.revision)
            assertNotNull(current.resultIdentity)
            val sideNow = requireNotNull(store.current(sidePage.pageId))
            assertEquals(beforeSide.revision, sideNow.revision)
            assertEquals(beforeSide.regions, sideNow.regions)
        } finally {
            release.complete(Unit)
        }
    }
}
