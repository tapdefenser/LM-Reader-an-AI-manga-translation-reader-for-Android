package com.lmreader.ui.workflow

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.reliability.IsolatedApp
import com.lmreader.reliability.closeContainer
import com.lmreader.reliability.seedRow
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.reader.translation.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Generated pixels and a deterministic SEG stub; no native models or real API requests. */
@RunWith(AndroidJUnit4::class)
class WorkflowResultProtectionTest {
    private lateinit var app: IsolatedApp
    private lateinit var container: AppContainer
    private val page = ReaderPage("protected-page", 0, "page.png", "page.png")
    private lateinit var bytes: ByteArray
    private val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.ENGLISH,
        "", BubbleRenderSettings(), .35f)
    private val program get() = WorkflowTemplates.blank()
    private val store get() = container.localPageTranslator.artifacts
    private val hash get() = ReaderPageArtifactStore.sha256(bytes)
    private val source = object : PageSource {
        override suspend fun pages() = listOf(page)
        override suspend fun open(page: ReaderPage) = bytes.inputStream()
        override suspend fun probe(page: ReaderPage) = PageGeometry(40, 50)
    }

    @Before fun setup() = runBlocking {
        app = IsolatedApp(ApplicationProvider.getApplicationContext())
        container = AppContainer(app); container.startupReady.await()
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage()
        val bitmap = Bitmap.createBitmap(40, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
    }
    @After fun cleanup() = runBlocking { closeContainer(container) }
    private fun protectionTest(block: suspend CoroutineScope.() -> Unit): Unit = runBlocking { withTimeout(20_000, block) }
    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit) {
        try { block() } catch (failure: Throwable) { if (failure is T) return else throw failure }
        fail("Expected ${T::class.java.simpleName}")
    }

    private fun host(options: WorkflowRunSettings = settings, overwrite: Boolean = false, expected: Map<String, String?> = emptyMap(),
        segments: AtomicInteger = AtomicInteger(), requests: AtomicInteger = AtomicInteger(), commits: AtomicInteger = AtomicInteger(),
        segmentGate: (suspend () -> Unit)? = null, publishGate: (suspend () -> Boolean)? = null) =
        object : AndroidWorkflowHost(app, container, "protected-manga", "fixture", listOf(WorkflowChapterInput("protected-chapter", "fixture", source, listOf(page))),
            options, TranslationCacheBudget(32L * 1_048_576), overwriteExisting = overwrite, expectedRevisions = expected) {
            override suspend fun segmentPage(pageId: String, bitmap: Bitmap, sha256: String, frame: WorkflowFrame): WorkflowPageSegmentation {
                segments.incrementAndGet(); segmentGate?.invoke()
                val regions = listOf(SegRegion("$pageId:region", RegionKind.BUBBLE, PixelRect(2f, 2f, 35f, 45f), 1f))
                return WorkflowPageSegmentation(SegResult(pageId, 40, 50, regions, 0, "fixture"), regions)
            }
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue { requests.incrementAndGet(); return WorkflowValue.Text("unexpected") }
            override suspend fun previewPage(frame: WorkflowFrame) {}
            override suspend fun canPublish(frame: WorkflowFrame): Boolean = publishGate?.invoke() ?: true
            override suspend fun pagePublished(frame: WorkflowFrame, saved: ReaderPageTranslation) { commits.incrementAndGet() }
        }

    private suspend fun firstResult(): ReaderPageTranslation {
        val host = host()
        try { WorkflowRuntime().execute(program, host); return host.published.getValue(page.pageId) }
        finally { host.close() }
    }

    @Test fun matchingHumanResultSurvivesStyleChangeAndProgramRestoreWithoutRecognition() = protectionTest {
        val first = firstResult()
        val edited = store.saveEdits(first, first.regions.map { it.copy(translatedText = "human") })
        assertTrue(edited.manuallyEdited); assertNotNull(edited.resultIdentity)
        val segments = AtomicInteger()
        val host = host(settings.copy(render = BubbleRenderSettings(fontScalePercent = 150)), segments = segments)
        try {
            val restored = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program))
            WorkflowRuntime().execute(restored, host)
            assertEquals(0, segments.get())
            assertEquals(edited.revision, host.published.getValue(page.pageId).revision)
            assertEquals("human", store.current(page.pageId)!!.regions.single().translatedText)
        } finally { host.close() }
    }

    @Test fun changedLanguageWorkflowAndLegacyIdentityStopBeforeAnyGlobalRequest() = protectionTest {
        val first = firstResult()
        val result = WorkflowVariable("result", "result", WorkflowType.TEXT)
        val global = WorkflowEditing.insert(WorkflowEditing.insert(program, WorkflowPosition("manga", 0),
            WorkflowNode("result", WorkflowKind.DECLARE, variable = result)), WorkflowPosition("manga", 1),
            WorkflowNode("request", WorkflowKind.API, target = WorkflowRef(result.id), inputs = mapOf(
                "profile" to WorkflowExpression.Text("fixture"), "prompt" to WorkflowExpression.Text("translate"))))
        for ((p, options) in listOf(program to settings.copy(target = LocalTranslationLanguage.JAPANESE), global to settings)) {
            val requests = AtomicInteger(); val host = host(options, requests = requests)
            try {
                expectFailure<IllegalStateException> { WorkflowRuntime().execute(p, host) }
                assertEquals(0, requests.get()); assertEquals(first.revision, store.current(page.pageId)!!.revision)
            } finally { host.close() }
        }
        val legacy = store.save(page.pageId, hash, settings.source, settings.target, 40, 50, first.regions, emptyList(), 0)
        val host = host()
        try {
            expectFailure<IllegalStateException> { WorkflowRuntime().execute(program, host) }
            assertEquals(legacy.revision, store.load(page.pageId, hash)!!.revision)
            assertNull(store.current(page.pageId)!!.resultIdentity)
        } finally { host.close() }
    }

    @Test fun lateMachineResultCannotOverwriteManualRevisionSavedDuringSegmentation() = protectionTest {
        val before = firstResult(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val host = host(overwrite = true, expected = mapOf(page.pageId to before.revision), segmentGate = { entered.complete(Unit); release.await() })
        try {
            supervisorScope {
                val running = async { WorkflowRuntime().execute(program, host) }
                entered.await()
                val human = container.localPageTranslator.pageWriteMutex.run { lock(); try { store.saveEdits(before, before.regions.map { it.copy(translatedText = "new human") }) } finally { unlock() } }
                release.complete(Unit)
                expectFailure<WorkflowExecutionFailure> { running.await() }
                assertEquals(human.revision, store.current(page.pageId)!!.revision)
                assertEquals("new human", store.current(page.pageId)!!.regions.single().translatedText)
                assertTrue(host.published.isEmpty())
            }
        } finally { release.complete(Unit); host.close() }
    }

    @Test fun manualResultCreatedAfterEmptyAdmissionCannotBeReplaced() = protectionTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val host = host(segmentGate = { entered.complete(Unit); release.await() })
        try {
            supervisorScope {
                val running = async { WorkflowRuntime().execute(program, host) }; entered.await()
                val human = store.save(page.pageId, hash, settings.source, settings.target, 40, 50, emptyList(), emptyList(), 0, manuallyEdited = true)
                release.complete(Unit)
                expectFailure<WorkflowExecutionFailure> { running.await() }
                assertEquals(human.revision, store.current(page.pageId)!!.revision)
            }
        } finally { release.complete(Unit); host.close() }
    }

    private suspend fun cancellationTask(): ChapterTranslationEntity {
        seedRow(container, "library_sources", mapOf("sourceId" to "protected-source", "kind" to "IMAGE_DIRECTORY", "mode" to "MULTI_CHAPTER", "permission" to "OK"))
        seedRow(container, "mangas", mapOf("mangaId" to "protected-manga", "sourceId" to "protected-source",
            "sourceKind" to "IMAGE_DIRECTORY", "layoutMode" to "MULTI_CHAPTER", "availability" to "AVAILABLE"))
        seedRow(container, "chapters", mapOf("chapterId" to "protected-chapter", "mangaId" to "protected-manga", "documentId" to "protected-document", "kind" to "IMAGE_DIRECTORY"))
        val at = System.currentTimeMillis()
        val task = ChapterTranslationEntity("protected-chapter", "protected-manga", "en", "RUNNING", "en", false, "{}", at, null, 0, null, at)
        container.database.translationDao().upsertAll(listOf(task))
        return task
    }

    @Test fun cancellationAcknowledgedBeforeNativeReturnPreventsPublication() = protectionTest {
        val task = cancellationTask(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val commits = AtomicInteger()
        val host = host(commits = commits, segmentGate = { entered.complete(Unit); release.await() }, publishGate = {
            container.database.translationDao().byChapter(task.chapterId).first().state != "CANCELLED"
        })
        try {
            val running = async { WorkflowRuntime().execute(program, host) }; entered.await()
            container.translationQueue.cancel(task).join()
            release.complete(Unit)
            running.await()
            assertNull(store.current(page.pageId)); assertEquals(0, commits.get())
        } finally { release.complete(Unit); host.close() }
    }

    @Test fun cancellationWaitsForAnAlreadyEnteredAtomicPublication() = protectionTest {
        val task = cancellationTask(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val commits = AtomicInteger()
        val host = host(commits = commits, publishGate = { entered.complete(Unit); release.await(); true })
        try {
            val running = async { WorkflowRuntime().execute(program, host) }; entered.await()
            val cancelling = container.translationQueue.cancel(task)
            yield(); assertFalse(cancelling.isCompleted)
            release.complete(Unit); running.await(); cancelling.join()
            assertNotNull(store.current(page.pageId)); assertEquals(1, commits.get())
            assertEquals("CANCELLED", container.database.translationDao().byChapter(task.chapterId).first().state)
        } finally { release.complete(Unit); host.close() }
    }

    @Test fun semanticConfigurationChangedDuringSegmentationDoesNotPublishStaleIdentity() = protectionTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val host = host(segmentGate = { entered.complete(Unit); release.await() })
        try {
            supervisorScope {
                val running = async { WorkflowRuntime().execute(program, host) }; entered.await()
                container.visionExecutionPreferences.update { it.copy(segGpu = !it.segGpu) }
                release.complete(Unit)
                expectFailure<WorkflowExecutionFailure> { running.await() }
                assertNull(store.current(page.pageId))
            }
        } finally { release.complete(Unit); host.close() }
    }

    @Test fun completedChapterCanQueueNewConfigurationWithoutInheritingOldCompletionOrDeletingResults() = protectionTest {
        val before = firstResult()
        val old = cancellationTask().copy(state = "DONE", translatedAt = 42, translatedCount = 5)
        container.database.translationDao().upsertAll(listOf(old))
        val unchanged = TranslationRequest("en", "en", false, "{}", old.updatedAt + 1)
        assertEquals(0, container.translationRepository.enqueue(old.mangaId, listOf(old.chapterId), unchanged))
        val changed = unchanged.copy(targetLanguage = "ja", configSnapshot = "{\"schema\":2,\"changed\":true}")
        assertEquals(1, container.translationRepository.enqueue(old.mangaId, listOf(old.chapterId), changed))
        val queued = container.database.translationDao().byChapter(old.chapterId).first()
        assertEquals("PENDING", queued.state); assertEquals("ja", queued.targetLanguage)
        assertEquals(0, queued.translatedCount); assertNull(queued.translatedAt)
        assertEquals(before.revision, store.current(page.pageId)!!.revision)
        assertEquals(0, container.translationRepository.enqueue(old.mangaId, listOf(old.chapterId), unchanged))
        assertEquals(changed.configSnapshot, container.database.translationDao().byChapter(old.chapterId).first().configSnapshot)
    }

    @Test fun styleOnlyReplayOfAllMatchingPagesDoesNotRepeatGlobalApiOrOtherSideEffects() = protectionTest {
        val variable = WorkflowVariable("result", "result", WorkflowType.TEXT)
        val global = WorkflowEditing.insert(WorkflowEditing.insert(program, WorkflowPosition("manga", 0),
            WorkflowNode("result", WorkflowKind.DECLARE, variable = variable)), WorkflowPosition("manga", 1),
            WorkflowNode("request", WorkflowKind.API, target = WorkflowRef(variable.id), inputs = mapOf(
                "profile" to WorkflowExpression.Text("fixture"), "prompt" to WorkflowExpression.Text("translate"))))
        val firstRequests = AtomicInteger(); val first = host(requests = firstRequests)
        try { WorkflowRuntime().execute(global, first); assertEquals(1, firstRequests.get()) } finally { first.close() }
        val revision = store.current(page.pageId)!!.revision
        val requests = AtomicInteger(); val segments = AtomicInteger()
        val second = host(settings.copy(render = BubbleRenderSettings(fontScalePercent = 150)), requests = requests, segments = segments)
        try {
            WorkflowRuntime().execute(global, second)
            assertEquals(0, requests.get()); assertEquals(0, segments.get())
            assertEquals(revision, second.published.getValue(page.pageId).revision)
        } finally { second.close() }
    }
}
