package com.lmreader.ui.queue

import android.graphics.*
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.lmreader.core.database.entity.ChapterTranslationEntity
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.workflow.translationTaskSnapshot
import com.lmreader.core.workflow.WorkflowEditing
import com.lmreader.core.workflow.WorkflowPosition
import com.lmreader.core.workflow.WorkflowValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.json.JSONArray
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Real model scheduling against generated pages in an isolated, uniquely named fixture. */
@RunWith(AndroidJUnit4::class)
class QueueSchedulingIntegrationTest {
    @get:Rule val activity = ActivityScenarioRule(ComponentActivity::class.java)
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val container = AppContainer.from(context)
    private val token = UUID.randomUUID().toString()
    private val sourceId = "scheduling-source-$token"
    private val mangaA = "scheduling-a-$token"
    private val mangaB = "scheduling-b-$token"
    private val mangaC = "scheduling-c-$token"
    private val mangaD = "scheduling-d-$token"
    private val fixtureMangas get() = listOf(mangaA,mangaB,mangaC,mangaD)
    private val a1 = "scheduling-a1-$token"
    private val a2 = "scheduling-a2-$token"
    private val b1 = "scheduling-b1-$token"
    private val c1 = "scheduling-c1-$token"
    private val d1 = "scheduling-d1-$token"
    private val ids = listOf(a1, a2, b1, c1, d1)
    private val root = File(Environment.getExternalStorageDirectory(), "LMReaderScheduleFixture-$token")
    private var pausedBefore = true
    private lateinit var preferencesBefore: VisionExecutionSettings
    private var cacheBefore = 128
    private var priorityBefore = TranslationSchedulingPriority.RESOURCES
    private val pages = mutableListOf<Pair<String, File>>()
    private val apiProfileId = "scheduling-api-$token"

    @Before fun setup() = runBlocking {
        container.startupReady.await()
        container.taskService.setVisible(true)
        container.taskService.allowRetry()
        assumeTrue(container.treeAccess.usesDirectFileAccess())
        assumeTrue(runCatching { container.translationModels.installedCatalog().route(LocalTranslationLanguage.ENGLISH,
            LocalTranslationLanguage.CHINESE_SIMPLIFIED) }.isSuccess)
        pausedBefore = container.translationQueue.paused.value
        priorityBefore = container.translationQueue.schedulingPriority.value
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage()
        container.translationQueue.awaitResourceRelease()
        container.translationQueue.setSchedulingPriority(TranslationSchedulingPriority.ORDER)
        container.apiProfiles.delete(apiProfileId)
        assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING") })
        preferencesBefore = container.visionExecutionPreferences.settings.first()
        cacheBefore = container.translationCachePreferences.megabytes.first()
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 1, ocrConcurrency = 1, segGpu = false, ocrBackend = com.lmreader.core.model.OcrBackend.CPU) }
        container.translationCachePreferences.setMegabytes(32)
        assertTrue(root.mkdirs())
        val db = container.database.openHelper.writableDatabase
        val uri = "content://com.android.externalstorage.documents/tree/primary%3A${root.name}"
        db.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES (?, 'IMAGE_DIRECTORY', ?, ?, 1, 'MULTI_CHAPTER', 0, 'OK', 1)", arrayOf(sourceId, uri, root.absolutePath))
        for (manga in fixtureMangas) db.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'Scheduling fixture', 'fixture', 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)", arrayOf(manga, File(root, manga).absolutePath, sourceId))
        for ((chapter, manga) in listOf(a1 to mangaA, a2 to mangaA, b1 to mangaB, c1 to mangaC, d1 to mangaD)) {
            val folder = File(root, chapter).apply { assertTrue(mkdirs()) }
            repeat(if (chapter == a1) 2 else 1) { ordinal ->
                val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawOval(80f, 90f, 720f, 410f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f })
                    drawText("HELLO WORLD", 180f, 260f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 52f; typeface = Typeface.DEFAULT_BOLD })
                }
                val file = File(folder, "$ordinal.png")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                pages += StableId.pageId(chapter, file.absolutePath) to file
            }
            db.execSQL("INSERT INTO chapters (chapterId, mangaId, documentId, kind, title, sortKey, position, contentRevision, discoveredAt) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', ?, ?, 0, 1, 0)", arrayOf(chapter, manga, folder.absolutePath, chapter, chapter))
        }
    }
    @After fun cleanup() = runBlocking {
        container.translationQueue.pause()
        container.database.translationDao().cancelQueueItems(ids, System.currentTimeMillis())
        container.translationQueue.awaitCurrentPage()
        container.translationQueue.awaitResourceRelease()
        for ((id, _) in pages) {
            container.localPageTranslator.artifacts.delete(id)
            container.localVision.preprocessingCache.commitPage(id)
        }
        container.apiProfiles.delete(apiProfileId)
        val db = container.database.openHelper.writableDatabase
        for (manga in fixtureMangas) db.execSQL("DELETE FROM mangas WHERE mangaId = ?", arrayOf(manga))
        db.execSQL("DELETE FROM library_sources WHERE sourceId = ?", arrayOf(sourceId))
        if (::preferencesBefore.isInitialized) container.visionExecutionPreferences.update { preferencesBefore }
        container.translationCachePreferences.setMegabytes(cacheBefore)
        container.translationQueue.setSchedulingPriority(priorityBefore)
        if (pausedBefore) container.translationQueue.pause() else container.translationQueue.resume()
        container.taskService.setVisible(false)
        if (root.canonicalFile.parentFile == Environment.getExternalStorageDirectory().canonicalFile && root.name == "LMReaderScheduleFixture-$token") root.deleteRecursively()
    }
    @Test fun reorderSwitchesAfterPageAndPauseCancelAndClearKeepOneTranslation() = runBlocking {
        val original = pages.associate { it.first to it.second.readBytes() }
        val settings = MangaTranslationSettings(sourceLanguage = "en", targetLanguage = "zh-Hans")
        val snapshot = translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE, settings, "en", "zh-Hans", "", BubbleRenderSettings())
        val at = System.currentTimeMillis()
        val tasks = listOf(a1 to mangaA, a2 to mangaA, b1 to mangaB).map { (chapter, manga) ->
            ChapterTranslationEntity(chapter, manga, "zh-Hans", "PENDING", "en", false, snapshot, at, null, 0, null, at)
        }
        val dao = container.database.translationDao()
        dao.upsertAll(tasks)
        container.translationQueue.sortManga(listOf(mangaA, mangaB))
        container.translationQueue.sortChapters(mangaA, listOf(a1, a2))
        container.translationQueue.setChapterPaused(tasks[1], true).join()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observer = launch { container.translationQueue.pageUpdates.collect { if (it in original) events += it } }
        yield(); container.translationQueue.resume()
        withTimeout(30_000) { container.translationQueue.currentPage.first { it == pages[0].first } }
        container.translationQueue.topManga(mangaB)
        withTimeout(120_000) { dao.observeQueue().map { dao.byChapters(ids) }.first { rows -> listOf(a1, b1).all { id -> rows.any { it.chapterId == id && it.state in listOf("DONE", "FAILED") } } } }
        container.translationQueue.awaitCurrentPage()
        withTimeout(5000) { container.translationQueue.loadedResources.first { it.isNotEmpty() } }
        // A paused chapter is still queued; engines remain resident until the global pause.
        container.translationQueue.pause(); container.translationQueue.awaitResourceRelease()
        withTimeout(5000) { container.translationQueue.loadedResources.first { it.isEmpty() } }
        val finished = dao.byChapters(ids).associateBy { it.chapterId }
        assertEquals(finished[a1]?.failure, "DONE", finished[a1]?.state)
        assertEquals(finished[b1]?.failure, "DONE", finished[b1]?.state)
        assertEquals("PAUSED", finished[a2]?.state)
        assertEquals(listOf(pages[0].first, pages[3].first, pages[1].first), events.toList())
        assertEquals(0L, container.translationQueue.cacheBytes.value)
        container.translationQueue.cancel(tasks[1]).join()
        assertFalse(dao.queueSnapshot().any { it.chapterId == a2 })
        // Manga language preferences were never set: clearing must still succeed.
        assertFalse(translationSetupComplete(container.mangaRepository.translationSettings(mangaA).sourceLanguage,
            container.mangaRepository.translationSettings(mangaA).targetLanguage, false))
        assertEquals(1, container.translationQueue.clearTranslations(mangaA, listOf(a1)))
        assertTrue(dao.byChapter(a1).isEmpty())
        assertFalse(container.localPageTranslator.artifacts.has(pages[0].first))
        assertFalse(container.localPageTranslator.artifacts.has(pages[1].first))
        for ((id, file) in pages) assertArrayEquals(original[id], file.readBytes())
        observer.cancelAndJoin()
    }
    @Test fun clearingInFlightPageExcludesItAndOtherPagesFinishThenUnload() = runBlocking {
        val settings = MangaTranslationSettings(sourceLanguage = "en", targetLanguage = "zh-Hans")
        val snapshot = translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE, settings, "en", "zh-Hans", "", BubbleRenderSettings())
        val at = System.currentTimeMillis()
        val dao = container.database.translationDao()
        dao.upsertAll(listOf(ChapterTranslationEntity(a1, mangaA, "zh-Hans", "PENDING", "en", false, snapshot, at, null, 0, null, at)))
        val steps = java.util.Collections.synchronizedList(mutableListOf<com.lmreader.ui.reader.translation.PageTranslationStage>())
        val progressObserver = launch { container.translationQueue.currentStep.filterNotNull().collect { steps += it.progress.stage } }
        container.translationQueue.resume()
        withTimeout(30_000) { container.translationQueue.currentPage.first { it == pages[0].first } }
        container.translationQueue.clearPage(a1, pages[0].first)
        withTimeout(90_000) {
            dao.observeQueue().first { rows -> rows.none { it.chapterId == a1 && it.state in listOf("PENDING", "RUNNING") } }
        }
        container.translationQueue.awaitCurrentPage()
        container.translationQueue.awaitResourceRelease()
        assertFalse(container.localPageTranslator.artifacts.has(pages[0].first))
        assertTrue(container.localPageTranslator.artifacts.has(pages[1].first))
        assertEquals("CANCELLED", dao.byChapter(a1).single().state)
        assertEquals(1, dao.byChapter(a1).single().translatedCount)
        assertTrue(steps.isNotEmpty())
        assertEquals(0, container.translationQueue.activeSeg.value)
        assertEquals(0, container.translationQueue.activeOcr.value)
        assertEquals(0, container.translationQueue.activeApi.value)
        withTimeout(5000) { container.translationQueue.loadedResources.first { it.isEmpty() } }
        progressObserver.cancelAndJoin()
    }
    @Test fun switchingMangaKeepsSharedModelsAndUnloadsUnusedLanguages() = runBlocking<Unit> {
        val japanese = LocalTranslationLanguage.JAPANESE to LocalTranslationLanguage.CHINESE_SIMPLIFIED
        val korean = LocalTranslationLanguage.KOREAN to LocalTranslationLanguage.CHINESE_SIMPLIFIED
        assumeTrue(runCatching { container.translationModels.installedCatalog().route(japanese.first, japanese.second) }.isSuccess)
        assumeTrue(runCatching { container.translationModels.installedCatalog().route(korean.first, korean.second) }.isSuccess)
        val engine = container.localTranslator
        container.translationQueue.prepareMangaResources(setOf(japanese))
        engine.translate(japanese.first, japanese.second, listOf(LocalTranslationText("synthetic-ja", "今日はいい天気です。")))
        assertEquals(2, engine.loadedResources.value.size)
        val shared = engine.loadedResources.value.single { it.id.startsWith("en-zh-Hans-") }.id
        container.translationQueue.prepareMangaResources(setOf(korean))
        assertEquals(listOf(shared), engine.loadedResources.value.map { it.id })
        engine.translate(korean.first, korean.second, listOf(LocalTranslationText("synthetic-ko", "오늘 날씨가 좋네요.")))
        assertEquals(2, engine.loadedResources.value.size)
        assertFalse(engine.loadedResources.value.any { it.id.startsWith("ja-en-") })
        val image = BitmapFactory.decodeFile(pages[0].second.absolutePath)
        try {
            var sawSeg = false
            var sawDetection = false
            container.localVision.segment("generated", image) {
                val seg = container.localVision.activeSeg.value
                val ocr = container.localVision.activeOcr.value
                assertTrue(seg + ocr in 0..1)
                if(seg == 1) sawSeg = true
                if(ocr == 1) sawDetection = true
            }
            assertTrue(sawSeg && sawDetection)
            container.localVision.recognize("generated", image, LocalOcrLanguage.ENGLISH) { assertEquals(1, container.localVision.activeOcr.value) }
            container.localVision.recognize("generated", image, LocalOcrLanguage.KOREAN)
            container.localVision.retainModels(true, setOf(LocalOcrLanguage.KOREAN))
            val kept = withTimeout(5000) { container.localVision.loadedResources.first { resources ->
                resources.any { it.id.endsWith(":ko") } && resources.none { it.id.endsWith(":rec") }
            } }
            assertTrue(kept.any { it.kind == InferenceEngineKind.SEG })
            assertTrue(kept.any { it.id.endsWith(":det") })
        } finally { image.recycle() }
        container.translationQueue.pause(); container.translationQueue.awaitResourceRelease()
        withTimeout(5000) { container.translationQueue.loadedResources.first { it.isEmpty() } }
    }
    @Test fun chapterReorderDoesNotWaitOnOldBufferedPagesUnderSmallBudget() = runBlocking {
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 2, ocrConcurrency = 2) }
        val settings = MangaTranslationSettings(sourceLanguage = "en", targetLanguage = "zh-Hans")
        val snapshot = translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE, settings, "en", "zh-Hans", "", BubbleRenderSettings())
        val at = System.currentTimeMillis()
        val dao = container.database.translationDao()
        dao.upsertAll(listOf(a1, a2).map { chapter -> ChapterTranslationEntity(chapter, mangaA, "zh-Hans", "PENDING",
            "en", false, snapshot, at, null, 0, null, at) })
        container.translationQueue.sortChapters(mangaA, listOf(a1, a2))
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observer = launch { container.translationQueue.pageUpdates.collect { if (it in pages.map { item -> item.first }) events += it } }
        yield(); container.translationQueue.resume()
        withTimeout(30_000) { container.translationQueue.currentPage.first { it == pages[0].first } }
        container.translationQueue.sortChapters(mangaA, listOf(a2, a1))
        withTimeout(90_000) { dao.observeQueue().map { dao.byChapters(ids) }.first { rows -> listOf(a1, a2).all { id -> rows.any { it.chapterId == id && it.state in listOf("DONE", "FAILED") } } } }
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage()
        val finished = dao.byChapters(ids).associateBy { it.chapterId }
        assertEquals(finished[a1]?.failure, "DONE", finished[a1]?.state)
        assertEquals(finished[a2]?.failure, "DONE", finished[a2]?.state)
        assertEquals(listOf(pages[0].first, pages[2].first, pages[1].first), events.toList())
        assertEquals(0L, container.translationQueue.cacheBytes.value)
        observer.cancelAndJoin()
    }
    @Test fun cachedPrefixOfNewHeadDoesNotReleasePriorityToOldChapter() = runBlocking {
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 2, ocrConcurrency = 2) }
        val extra = File(pages[2].second.parentFile, "1.png")
        extra.writeBytes(pages[2].second.readBytes())
        val extraId = StableId.pageId(a2, extra.absolutePath)
        pages += extraId to extra
        val cachedId = pages[0].first
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pages[0].second.readBytes()).joinToString("") { "%02x".format(it) }
        container.localPageTranslator.artifacts.save(cachedId, hash, LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            800, 1000, emptyList(), emptyList(), 0, BubbleRenderSettings())
        val settings = MangaTranslationSettings(sourceLanguage = "en", targetLanguage = "zh-Hans")
        val snapshot = translationTaskSnapshot(TranslationWorkflow.LOCAL_MACHINE, settings, "en", "zh-Hans", "", BubbleRenderSettings())
        val at = System.currentTimeMillis()
        val dao = container.database.translationDao()
        dao.upsertAll(listOf(a1, a2).map { chapter -> ChapterTranslationEntity(chapter, mangaA, "zh-Hans", "PENDING",
            "en", false, snapshot, at, null, 0, null, at) })
        container.translationQueue.sortChapters(mangaA, listOf(a2, a1))
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observer = launch { container.translationQueue.pageUpdates.collect { if (it in pages.map { p -> p.first }) events += it } }
        yield(); container.translationQueue.resume()
        withTimeout(30_000) { container.translationQueue.currentPage.first { it == pages[2].first } }
        container.translationQueue.sortChapters(mangaA, listOf(a1, a2))
        withTimeout(90_000) { dao.observeQueue().map { dao.byChapters(ids) }.first { rows -> listOf(a1, a2).all { id -> rows.any { it.chapterId == id && it.state in listOf("DONE", "FAILED") } } } }
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage()
        val finished = dao.byChapters(ids).associateBy { it.chapterId }
        assertEquals(finished[a1]?.failure, "DONE", finished[a1]?.state)
        assertEquals(finished[a2]?.failure, "DONE", finished[a2]?.state)
        assertEquals(listOf(pages[2].first, pages[1].first, extraId), events.toList())
        assertEquals(0L, container.translationQueue.cacheBytes.value)
        observer.cancelAndJoin()
    }
    private suspend fun fullTasks(server: DelayedApiServer, includeNextManga: Boolean = false) {
        val profile = ApiProfile(apiProfileId, ApiProfileKind.LLM, "Isolated queue test", "http://127.0.0.1:${server.port}/v1", apiKey = "1", model = "fixture", parallelLimit = 2, retryCount = 0)
        container.apiProfiles.save(profile)
        val full = TranslationWorkflow.FULL_MANGA_API.copy(id = "fixture", builtIn = false, program = WorkflowReferenceTemplates.fullManga(apiProfileId))
        val settings = MangaTranslationSettings(sourceLanguage = "en", targetLanguage = "zh-Hans")
        val snapshot = translationTaskSnapshot(full, settings, "en", "zh-Hans", "", BubbleRenderSettings(), listOf(profile))
        val at = System.currentTimeMillis()
        val ids = listOf(a1 to mangaA, a2 to mangaA) + if(includeNextManga) listOf(b1 to mangaB) else emptyList()
        container.database.translationDao().upsertAll(ids.map { (chapter, manga) -> ChapterTranslationEntity(chapter, manga, "zh-Hans", "PENDING", "en", false, snapshot, at, null, 0, null, at) })
        container.translationQueue.sortManga(listOf(mangaA, mangaB))
        container.translationQueue.sortChapters(mangaA, listOf(a1, a2))
    }
    @Test fun resourcesPriorityAdvancesOtherApiAndLocalMangaWhileSameApiWaits() = runBlocking {
        val serverA=DelayedApiServer(pageRequests=true)
        val serverB=DelayedApiServer(pageRequests=true)
        val bProfileId="$apiProfileId-b"
        try {
            container.translationQueue.setSchedulingPriority(TranslationSchedulingPriority.RESOURCES)
            container.translationCachePreferences.setMegabytes(128)
            val profileA=ApiProfile(apiProfileId,ApiProfileKind.LLM,"A","http://127.0.0.1:${serverA.port}/v1",model="fixture",parallelLimit=2,retryCount=0)
            val profileB=profileA.copy(id=bProfileId,name="B",url="http://127.0.0.1:${serverB.port}/v1")
            container.apiProfiles.save(profileA);container.apiProfiles.save(profileB)
            val base=WorkflowReferenceTemplates.standard(profileA.id)
            val firstApi=base.allNodes().single { it.kind==WorkflowKind.API }
            val second=firstApi.copy(id="second-api",inputs=firstApi.inputs+("profile" to WorkflowExpression.Text(profileB.id)))
            val multi=WorkflowEditing.insert(base,WorkflowPosition("pages",5),second)
            assertTrue(WorkflowValidator.validate(multi).valid)
            val apiA=TranslationWorkflow.STANDARD_API.copy(id="fixture-a",builtIn=false,program=multi)
            val apiB=apiA.copy(id="fixture-b",program=WorkflowReferenceTemplates.standard(profileB.id))
            val apiD=apiA.copy(id="fixture-d",program=base)
            val settings=MangaTranslationSettings(sourceLanguage="en",targetLanguage="zh-Hans")
            val at=System.currentTimeMillis()
            val tasks=listOf(Triple(a1,mangaA,apiA),Triple(b1,mangaB,apiB),Triple(c1,mangaC,TranslationWorkflow.LOCAL_MACHINE),Triple(d1,mangaD,apiD))
            val dao=container.database.translationDao()
            dao.upsertAll(tasks.map { (chapter,manga,workflow) -> ChapterTranslationEntity(chapter,manga,"zh-Hans","PENDING","en",false,
                translationTaskSnapshot(workflow,settings,"en","zh-Hans","",BubbleRenderSettings(),listOf(profileA,profileB)),at,null,0,null,at) })
            container.translationQueue.sortManga(fixtureMangas)
            container.translationQueue.resume()
            withTimeout(90_000) { serverA.requested.await();serverB.requested.await()
                dao.observeQueue().first { dao.byChapter(c1).firstOrNull()?.state=="DONE" }
            }
            assertEquals("PENDING",dao.byChapter(d1).single().state)
            assertTrue(dao.byChapter(a1).single().state=="RUNNING")
            assertTrue(dao.byChapter(b1).single().state=="RUNNING")
            assertTrue(container.localPageTranslator.artifacts.has(pages[4].first))
            serverA.reply.complete(Unit);serverB.reply.complete(Unit)
            withTimeout(90_000) { dao.observeQueue().first { rows -> rows.none { it.chapterId in listOf(a1,b1,c1,d1) && it.state in listOf("PENDING","RUNNING") } } }
            for((chapter,_,_) in tasks) assertEquals(dao.byChapter(chapter).single().failure,"DONE",dao.byChapter(chapter).single().state)
        } finally {
            serverA.reply.complete(Unit);serverB.reply.complete(Unit)
            container.translationQueue.pause();container.translationQueue.awaitCurrentPage()
            container.apiProfiles.delete(bProfileId);serverA.close();serverB.close()
        }
    }
    @Test fun waitingOnApiPresegmentsTheNextPageAndItsRealSegReusesTheCache() = runBlocking {
        val server=DelayedApiServer(pageRequests=true)
        try {
            container.translationQueue.setSchedulingPriority(TranslationSchedulingPriority.RESOURCES)
            val profile=ApiProfile(apiProfileId,ApiProfileKind.LLM,"Prefetch","http://127.0.0.1:${server.port}/v1",model="fixture",parallelLimit=1,retryCount=0)
            container.apiProfiles.save(profile)
            val program=WorkflowEditing.update(WorkflowReferenceTemplates.standard(profile.id),"pages") { it.copy(mode=WorkflowMode.SYNC) }
            assertTrue(WorkflowValidator.validate(program).valid)
            val workflow=TranslationWorkflow.STANDARD_API.copy(id="prefetch-fixture",builtIn=false,program=program)
            val at=System.currentTimeMillis()
            val dao=container.database.translationDao()
            dao.upsertAll(listOf(ChapterTranslationEntity(a1,mangaA,"zh-Hans","PENDING","en",false,
                translationTaskSnapshot(workflow,MangaTranslationSettings(sourceLanguage="en",targetLanguage="zh-Hans"),"en","zh-Hans","",BubbleRenderSettings(),listOf(profile)),at,null,0,null,at)))
            val hits=container.localVision.preprocessingCache.hits.value
            container.translationQueue.resume()
            withTimeout(60_000) { server.requested.await() }
            withTimeout(60_000) { while(true) {
                val directory=File(context.filesDir,"vision-preprocessing")
                val ready=runCatching {
                    val pins=JSONObject(File(directory,"pending.json").readText())
                    pins.keys().asSequence().any { pins.getString(it)==pages[1].first && File(directory,"$it.json").isFile }
                }.getOrDefault(false)
                if(ready && container.translationQueue.activeSeg.value==0 && container.translationQueue.activeOcr.value==0) break
                delay(50)
            } }
            assertFalse(container.localPageTranslator.artifacts.has(pages[1].first))
            server.reply.complete(Unit)
            withTimeout(90_000) { dao.observeQueue().first { it.none { row -> row.chapterId==a1 && row.state in listOf("PENDING","RUNNING") } } }
            assertEquals(dao.byChapter(a1).single().failure,"DONE",dao.byChapter(a1).single().state)
            assertTrue(container.localVision.preprocessingCache.hits.value>hits)
        } finally { server.reply.complete(Unit);server.close() }
    }
    @Test fun fullApiKeepsOneRequestAcrossReorderAndPauseThenSavesInNewChapterOrder() = runBlocking {
        val server = DelayedApiServer()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val observer = launch { container.translationQueue.pageUpdates.collect { if(it in pages.map { p -> p.first }) events += it } }
        try {
            fullTasks(server, includeNextManga = true)
            container.translationQueue.resume()
            withTimeout(60_000) { server.requested.await() }
            assertTrue(pages.none { container.localPageTranslator.artifacts.has(it.first) })
            container.translationQueue.sortChapters(mangaA, listOf(a2, a1))
            container.translationQueue.topManga(mangaB)
            container.translationQueue.pause()
            assertEquals(1, server.calls.get())
            server.reply.complete(Unit)
            withTimeout(60_000) { container.translationQueue.awaitCurrentPage(); container.translationQueue.awaitResourceRelease() }
            val rows = container.database.translationDao().byChapters(ids).associateBy { it.chapterId }
            assertEquals(rows[a1]?.failure, "DONE", rows[a1]?.state)
            assertEquals(rows[a2]?.failure, "DONE", rows[a2]?.state)
            assertEquals("PENDING", rows[b1]?.state)
            assertEquals(listOf(pages[2].first, pages[0].first, pages[1].first), events.toList())
            assertEquals(1, server.calls.get())
            assertTrue(container.translationQueue.paused.value)
            assertEquals(0L, container.translationQueue.cacheBytes.value)
            assertEquals(0, container.translationQueue.activeApi.value)
            assertTrue(container.translationQueue.loadedResources.value.isEmpty())
        } finally { server.close(); observer.cancelAndJoin() }
    }
    @Test fun cancellingFullMangaAbortsItsOutstandingApiWithoutWaitingForReply() = runBlocking {
        val server = DelayedApiServer()
        try {
            fullTasks(server)
            container.translationQueue.resume()
            withTimeout(60_000) { server.requested.await() }
            container.translationQueue.cancelManga(mangaA).join()
            withTimeout(5000) { container.translationQueue.awaitCurrentPage(); container.translationQueue.awaitResourceRelease() }
            assertEquals(1, server.calls.get())
            assertTrue(pages.none { container.localPageTranslator.artifacts.has(it.first) })
            assertEquals(0, container.translationQueue.activeApi.value)
            assertEquals(0L, container.translationQueue.cacheBytes.value)
            assertTrue(container.database.translationDao().queueSnapshot().none { it.mangaId == mangaA })
        } finally { server.close() }
    }
    /** Local protocol fixture, never sends an image or prompt to a user service. */
    private class DelayedApiServer(private val pageRequests: Boolean = false) {
        private val server = ServerSocket(0)
        val port get() = server.localPort
        val calls = AtomicInteger()
        val requested = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<Unit>()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var client: java.net.Socket? = null
        init { scope.launch {
            try {
                while(isActive) server.accept().use { socket ->
                    client = socket
                    val input = socket.getInputStream()
                    fun headerLine(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while(true) {
                            val b = input.read(); require(b >= 0); if(b == 10) break
                            require(bytes.size() < 8192); if(b != 13) bytes.write(b)
                        }
                        return bytes.toString("US-ASCII")
                    }
                    var contentLength = 0
                    while(true) {
                        val line = headerLine()
                        if(line.isEmpty()) break
                        if(line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                    }
                    require(contentLength in 1..2_000_000)
                    val body = ByteArray(contentLength); var count = 0
                    while(count < contentLength) { val n = input.read(body, count, contentLength - count); require(n > 0); count += n }
                    val prompt = JSONObject(String(body, Charsets.UTF_8)).getJSONArray("messages").getJSONObject(0).getString("content")
                    val marker = if(pageRequests) "输入：" else "所有气泡按章节和页顺序提供："
                    val start = prompt.indexOf(marker) + marker.length
                    val end = prompt.indexOf(if(pageRequests) "。返回完整 JSON 列表" else "。只返回完整 JSON 列表", start)
                    val originals = JSONArray(prompt.substring(start, end))
                    val translated = JSONArray()
                    for(i in originals.length() - 1 downTo 0) translated.put(originals.getJSONObject(i).put("translation", "合成队列译文"))
                    calls.incrementAndGet(); requested.complete(Unit); reply.await()
                    val response = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", translated.toString())).put("finish_reason", "stop"))).toString().toByteArray(Charsets.UTF_8)
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    output.write(response); output.flush()
                }
            } catch(failure: Exception) { if(!requested.isCompleted) requested.completeExceptionally(failure) }
        } }
        fun close() { client?.close(); server.close(); scope.cancel() }
    }
}
