package com.lmreader.ui.workflow

import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class WorkflowHostIntegrationTest {
    @Test fun repeatedSegInvalidatesOldCropsAndPublishesOnlyTheNewGeneration() = runBlocking<Unit> {
        val server = ImageApiFixture()
        container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "SEG replacement fixture",
            "http://127.0.0.1:${server.port}/v1", model = "fixture", retryCount = 0))
        val cache = TranslationCacheBudget(48L * 1_048_576)
        val generations = java.util.concurrent.ConcurrentHashMap<String, List<WorkflowValue.Record>>()
        val replacements = AtomicInteger()
        val custom = WorkflowVariable("fresh-bubbles", "新气泡列表", WorkflowType.list(WorkflowType.BUBBLE))
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "SEG replacement fixture",
            generatedChapters(1, AtomicInteger()), WorkflowRunSettings(LocalTranslationLanguage.ENGLISH,
                LocalTranslationLanguage.CHINESE_SIMPLIFIED, "", BubbleRenderSettings(), .35f), cache, reusePages = false) {
            override suspend fun segmentedPage(frame: WorkflowFrame) {
                val id = frame.identity(WorkflowSystem.PAGE)!!
                val bubbles = (frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("bubbles"))) as WorkflowValue.ListValue).items.map { it as WorkflowValue.Record }
                assertTrue(bubbles.isNotEmpty())
                assertTrue(bubbles.all { it.fields["source"] == WorkflowValue.Text("") && it.fields["translation"] == WorkflowValue.Text("") })
                assertTrue((frame.read(WorkflowRef(WorkflowSystem.PAGE, listOf("records"))) as WorkflowValue.ListValue).items.isEmpty())
                val old = generations.put(id, bubbles)
                if(old != null) {
                    replacements.incrementAndGet()
                    assertEquals(WorkflowValue.ListValue(bubbles), frame.read(WorkflowRef(custom.id)))
                    assertTrue(old.map { it.fields["id"] }.intersect(bubbles.map { it.fields["id"] }.toSet()).isEmpty())
                    val image = old.first().fields.getValue("image") as WorkflowValue.Image
                    try { super.request(WorkflowKind.OCR, mapOf("image" to image, "language" to WorkflowValue.Text("en")), frame); fail("Old OCR crop survived SEG") }
                    catch(failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("图片不存在")) }
                    try { super.api(WorkflowApiCall(profileId, listOf(WorkflowResolvedMessage("user", "Read old crop")), listOf(image), WorkflowType.TEXT, null), frame); fail("Old API crop survived SEG") }
                    catch(failure: IllegalStateException) { assertTrue(failure.message.orEmpty().contains("图片引用不存在")) }
                }
                super.segmentedPage(frame)
            }
            override suspend fun pagePreviewed(frame: WorkflowFrame, saved: ReaderPageTranslation) {
                assertTrue(saved.regions.all { it.region.sourceText.isEmpty() && it.translatedText.isEmpty() })
            }
        }
        val base = WorkflowTemplates.localMachine()
        val program = WorkflowEditing.update(base, "pages") { page -> page.copy(children = listOf(
            page.children.first(),
            page.children.last().copy(children = page.children.last().children.take(1) + WorkflowNode("old-text", WorkflowKind.SET,
                target = WorkflowRef(WorkflowSystem.BUBBLE, listOf("translation")), inputs = mapOf("value" to WorkflowExpression.Text("旧译文")))),
            WorkflowNode("fresh-declare", WorkflowKind.DECLARE, variable = custom),
            page.children.first().copy(id = "fresh-seg", target = WorkflowRef(custom.id)))) }
        try {
            withTimeout(180_000) { WorkflowRuntime().execute(program, host) }
            assertEquals(2, replacements.get())
            assertEquals(2, host.published.size)
            host.published.forEach { (id, saved) ->
                assertEquals(generations.getValue(id).map { (it.fields.getValue("id") as WorkflowValue.Text).value }, saved.regions.map { it.region.id })
                assertTrue(saved.regions.all { it.region.sourceText.isEmpty() && it.translatedText.isEmpty() })
                val persisted = container.localPageTranslator.artifacts.load(id, saved.sourceSha256)!!
                assertEquals(saved.regions, persisted.regions)
            }
            assertTrue(server.requests.isEmpty())
            assertEquals(0L, cache.bytes.value)
        } finally { host.close(); server.close() }
    }

    @Test fun imageListIsSentByOrdinaryAndStreamingApiWithAllActualAttachments() = runBlocking<Unit> {
        val reply = "[{\"source\":\"Generated text\",\"translation\":\"测试译文\"}]"
        val server = ImageApiFixture(reply)
        container.apiProfiles.save(ApiProfile(profileId, ApiProfileKind.LLM, "Image list fixture",
            "http://127.0.0.1:${server.port}/v1", model = "fixture", retryCount = 0))
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val host = AndroidWorkflowHost(container.applicationContext, container, mangaId, "Image list fixture",
            generatedChapters(1, AtomicInteger()).take(1), WorkflowRunSettings(LocalTranslationLanguage.ENGLISH,
                LocalTranslationLanguage.CHINESE_SIMPLIFIED, "", BubbleRenderSettings(), .35f), cache, reusePages = false)
        val pictures = WorkflowVariable("picture-list", "图片列表", WorkflowType.list(WorkflowType.IMAGE))
        val answer = WorkflowVariable("picture-answer", "对照列表", WorkflowType.list(WorkflowType.GLOSSARY_ENTRY))
        val inputs = mapOf("profile" to WorkflowExpression.Text(profileId), "prompt" to WorkflowExpression.Text("Read all attached images"),
            "images" to WorkflowSystem.ref(pictures.id))
        val program = WorkflowEditing.update(WorkflowTemplates.blank(), "pages") { page -> page.copy(children = page.children + listOf(
            WorkflowNode("pictures", WorkflowKind.DECLARE, variable = pictures),
            WorkflowNode("add-page", WorkflowKind.APPEND, target = WorkflowRef(pictures.id), inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image"))),
            WorkflowNode("add-bubble", WorkflowKind.APPEND, target = WorkflowRef(pictures.id), inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.PAGE, "bubbles", "0", "image"))),
            WorkflowNode("answer", WorkflowKind.DECLARE, variable = answer),
            WorkflowNode("list-api", WorkflowKind.API, target = WorkflowRef(answer.id), resultType = answer.type, inputs = inputs),
            WorkflowNode("list-stream", WorkflowKind.API_STREAM, target = WorkflowRef(answer.id), resultType = answer.type, inputs = inputs,
                variable = WorkflowVariable("picture-entry", "当前条目", WorkflowType.GLOSSARY_ENTRY)))) }
        try {
            withTimeout(120_000) { WorkflowRuntime().execute(WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(program)), host) }
            assertEquals(2, server.requests.size)
            server.requests.forEach { request ->
                val content = request.getJSONArray("messages").let { it.getJSONObject(it.length() - 1) }.getJSONArray("content")
                val urls = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "image_url" }
                    .map { it.getJSONObject("image_url").getString("url") }
                assertEquals(2, urls.size)
                assertTrue(urls[0].startsWith("data:image/jpeg;base64,"))
                assertTrue(urls[1].startsWith("data:image/png;base64,"))
                urls.forEachIndexed { index, url ->
                    val bytes = android.util.Base64.decode(url.substringAfter(','), android.util.Base64.DEFAULT)
                    val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    try {
                        if (index == 0) { assertEquals(800, image.width); assertEquals(1000, image.height) }
                        else assertTrue(image.width < 800 && image.height < 1000)
                    } finally { image.recycle() }
                }
                assertTrue(content.getJSONObject(0).getString("text").contains("已附带 2 张实际图片"))
            }
            val logs = container.apiLogs.records.value.filter { it.info.context.mangaId == mangaId }
            assertEquals(2, logs.size)
            assertTrue(logs.all { container.apiLogs.detail(it.id)?.outcome?.status == "SUCCESS" })
            assertEquals(0L, cache.bytes.value)
        } finally { host.close(); server.close() }
    }

    @Test fun realStructuredStreamBackfillsBeforeRequestFinishesAndRecordsMangaLog() = runBlocking<Unit> {
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Generated streaming test", "http://192.168.137.1:1234/v1",
            apiKey = "1", model = "gemma4-12b-qat-uncensored-hauhaucs-balanced@q4_k_m", parallelLimit = 2, retryCount = 0,
            parameters = AiParameters(temperature = .1, maxTokens = 4096))
        container.apiProfiles.save(profile)
        val chapters = generatedChapters(1, AtomicInteger()).take(1)
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, "", BubbleRenderSettings(), .35f, listOf(profile.copy(apiKey = "")))
        var streamEnded = false; var previews = 0
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated streaming fixture", chapters, settings, cache, reusePages = false) {
            override suspend fun apiStream(call: WorkflowApiCall, frame: WorkflowFrame, item: suspend (WorkflowValue, Int) -> Unit): WorkflowValue.ListValue =
                super.apiStream(call, frame, item).also { streamEnded = true }
            override suspend fun pagePreviewed(frame: WorkflowFrame, saved: com.lmreader.ui.reader.translation.ReaderPageTranslation) {
                assertFalse(streamEnded)
                assertFalse(container.localPageTranslator.artifacts.has(saved.pageId))
                assertTrue(saved.regions.isNotEmpty()); previews++
            }
        }
        var program = WorkflowReferenceTemplates.standard(profileId)
        program = WorkflowEditing.update(program, "pages") { page -> page.copy(children = page.children.filterNot { it.kind == WorkflowKind.APPLY_TRANSLATIONS }.map { node ->
            if(node.kind != WorkflowKind.API) node else node.copy(kind = WorkflowKind.API_STREAM, variable = WorkflowVariable("stream-entry", "当前条目", WorkflowType.TRANSLATION),
                children = listOf(WorkflowNode("stream-fill", WorkflowKind.APPLY_TRANSLATIONS, inputs = mapOf("items" to WorkflowSystem.ref("stream-entry")))))
        }) }
        try {
            withTimeout(240_000) { WorkflowRuntime().execute(program, host) }
            assertTrue(previews > 0); assertTrue(streamEnded); assertEquals(1, host.published.size)
            assertTrue(host.published.values.all { it.dataFile.extension == "json" })
            val log = container.apiLogs.records.value.first { it.info.context.mangaId == mangaId }
            val detail = requireNotNull(container.apiLogs.detail(log.id))
            assertEquals("Generated streaming fixture", detail.info.context.mangaName)
            assertEquals("SUCCESS", detail.outcome.status)
            assertTrue(detail.outcome.response.contains("bubbleId")); assertTrue(detail.info.request.contains("stream"))
            assertFalse(detail.info.request.contains("Authorization")); assertFalse(detail.info.request.contains("apiKey"))
            assertEquals(0L, cache.bytes.value)
        } finally { host.close() }
    }
    private val container = AppContainer.from(ApplicationProvider.getApplicationContext<android.content.Context>())
    private val token = UUID.randomUUID().toString()
    private val mangaId = "workflow-manga-$token"
    private val sourceId = "workflow-source-$token"
    private val profileId = "workflow-api-$token"
    private val pageIds = listOf("workflow-page-7-$token", "workflow-page-39-$token")
    private val extraPageIds = mutableListOf<String>()
    private var pausedBefore = true
    private var visionBefore: VisionExecutionSettings? = null
    @Before fun setup() = runBlocking {
        pausedBefore = container.translationQueue.paused.value
        container.translationQueue.pause(); container.translationQueue.awaitCurrentPage(); container.translationQueue.awaitResourceRelease()
        assumeTrue(container.database.translationDao().queueSnapshot().none { it.state in listOf("PENDING", "RUNNING") })
        visionBefore = container.visionExecutionPreferences.settings.value
        container.visionExecutionPreferences.update { it.copy(segConcurrency = 1, ocrConcurrency = 1, segGpu = false, ocrBackend = OcrBackend.CPU) }
        val db = container.database.openHelper.writableDatabase
        db.execSQL("INSERT INTO library_sources (sourceId, kind, treeUri, displayPath, recursive, mode, orderIndex, permission, revision) VALUES (?, 'IMAGE_DIRECTORY', ?, 'Generated workflow fixture', 1, 'MULTI_CHAPTER', 0, 'OK', 1)", arrayOf(sourceId, "content://fixture/$token"))
        db.execSQL("INSERT INTO mangas (mangaId, anchorDocumentId, sourceId, sourceKind, layoutMode, displayName, sortKey, sourceOrderIndex, hasMetadata, chapterCountKnown, availability, discoveryGeneration, discoveredAt, updatedAt, translationAutoDetectSource) VALUES (?, ?, ?, 'IMAGE_DIRECTORY', 'MULTI_CHAPTER', 'Generated workflow fixture', 'fixture', 0, 0, 1, 'AVAILABLE', 1, 0, 0, 0)", arrayOf(mangaId, token, sourceId))
    }
    @After fun cleanup() = runBlocking {
        container.apiProfiles.delete(profileId)
        (pageIds + extraPageIds).forEach { container.localPageTranslator.artifacts.delete(it) }
        val db = container.database.openHelper.writableDatabase
        db.execSQL("DELETE FROM mangas WHERE mangaId = ?", arrayOf(mangaId))
        db.execSQL("DELETE FROM library_sources WHERE sourceId = ?", arrayOf(sourceId))
        container.localPageTranslator.releaseModels()
        visionBefore?.let { original -> container.visionExecutionPreferences.update { original } }
        if(pausedBefore) container.translationQueue.pause() else container.translationQueue.resume()
    }
    @Test fun additionsAreAtomicAndExistingAutomaticEntryAlsoBeatsLaterManualEntry() = runBlocking {
        val repository = container.translationRepository
        repository.upsertGlossary(GlossaryEntry(mangaId, "Akira", "先到的译名", false, 1))
        coroutineScope { repeat(16) { index -> launch(Dispatchers.IO) { repository.upsertGlossary(GlossaryEntry(mangaId, "Akira", "后来-$index", index % 2 == 0, index.toLong() + 2)) } } }
        assertEquals("先到的译名", repository.glossary(mangaId).single().target)
        repository.editGlossary("Akira", GlossaryEntry(mangaId, "Akira", "明确编辑后的译名", true, 50))
        assertEquals("明确编辑后的译名", repository.glossary(mangaId).single().target)
    }
    @Test fun visionOnlyWorkflowDetectsTextForAllThreeScopesWithoutLoadingRecognition() = runBlocking<Unit> {
        val server = ImageApiFixture()
        container.apiProfiles.save(ApiProfile(profileId,ApiProfileKind.LLM,"Loopback image fixture",
            "http://127.0.0.1:${server.port}/v1",apiKey="fixture",model="fixture",retryCount=0))
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f }
            drawRect(20f, 20f, 780f, 980f, outline); drawOval(100f, 100f, 700f, 460f, outline)
            drawLine(470f, 454f, 520f, 530f, outline); drawLine(520f, 530f, 560f, 446f, outline)
            drawCircle(400f, 720f, 100f, outline)
            val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 43f; typeface = Typeface.DEFAULT_BOLD }
            drawText("OUTSIDE TEXT", 130f, 930f, pen)
            pen.textSize = 28f
            drawText("HELLO", 352f, 708f, pen); drawText("WORLD", 347f, 753f, pen)
        }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val bytes = output.toByteArray()
        val page = ReaderPage(pageIds.first(), 0, "generated-scope.png", pageIds.first())
        val source = object : PageSource {
            override suspend fun pages() = listOf(page)
            override suspend fun open(page: ReaderPage) = bytes.inputStream()
            override suspend fun probe(page: ReaderPage) = PageGeometry(800, 1000)
        }
        val chapters = listOf(WorkflowChapterInput("scope-chapter", "Generated scope fixture", source, listOf(page)))
        val program = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(WorkflowTemplates.visionApi(profileId)))
        assertFalse(program.uses(WorkflowKind.OCR))
        try { for (scope in SegTextScope.entries) {
            container.localVision.releaseModels()
            val cache = TranslationCacheBudget(32L * 1_048_576)
            val kinds = java.util.Collections.synchronizedList(mutableListOf<RegionKind>())
            val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
                "", BubbleRenderSettings(), .35f, segTextScope = scope)
            val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated scope fixture",
                chapters, settings, cache, reusePages = false) {
                override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                    val bubble = frame.read(WorkflowRef(WorkflowSystem.BUBBLE)) as WorkflowValue.Record
                    val id = (bubble.fields.getValue("id") as WorkflowValue.Text).value
                    val kind = RegionKind.valueOf((bubble.fields.getValue("kind") as WorkflowValue.Text).value)
                    assertEquals("bubble:$id", call.images.single().key)
                    assertEquals(WorkflowType.GLOSSARY_ENTRY, call.resultType)
                    val loaded = withTimeout(5000) { container.localVision.loadedResources.first { it.any { resource -> resource.id.endsWith(":det") } } }
                    assertTrue(loaded.any { it.kind == InferenceEngineKind.SEG })
                    assertTrue(loaded.any { it.kind == InferenceEngineKind.OCR && it.id.endsWith(":det") })
                    assertFalse(loaded.any { it.id.endsWith(":rec") || it.id.endsWith(":ko") })
                    assertEquals(0, container.localVision.activeOcr.value)
                    kinds += kind
                    return super.api(call,frame)
                }
            }
            try {
                withTimeout(120_000) { WorkflowRuntime().execute(program, host) }
                assertTrue("No API targets for $scope", kinds.isNotEmpty())
                assertTrue(kinds.all { scope.includes(it) })
                if (scope == SegTextScope.ALL) assertEquals(setOf(RegionKind.BUBBLE, RegionKind.FREE_TEXT), kinds.toSet())
                val published = host.published.values.single()
                assertEquals(kinds.size, published.regions.size)
                assertTrue(published.regions.all { it.region.sourceText.isNotBlank() && it.translatedText == "测试译文" })
                assertTrue(published.regions.all { it.region.textBounds.isNotEmpty() })
                assertEquals(kinds.size,server.requests.size)
                server.requests.forEach { request ->
                    val content=request.getJSONArray("messages").let { it.getJSONObject(it.length()-1) }.getJSONArray("content")
                    val url=(0 until content.length()).map { content.getJSONObject(it) }
                        .single { it.optString("type")=="image_url" }.getJSONObject("image_url").getString("url")
                    assertTrue(url.take(32),url.startsWith("data:image/png;base64,"))
                    val png=android.util.Base64.decode(url.substringAfter(','),android.util.Base64.DEFAULT)
                    assertArrayEquals(byteArrayOf(-119,80,78,71,13,10,26,10),png.take(8).toByteArray())
                    val crop=BitmapFactory.decodeByteArray(png,0,png.size)
                    try { assertTrue(crop.width<800 && crop.height<1000) } finally { crop.recycle() }
                }
                server.requests.clear()
                assertEquals(0L, cache.bytes.value)
                android.util.Log.i("SegWorkflow", "$scope: ${kinds.size} API crops, ${kinds.toSet()}, ${container.localVision.loadedResources.value}")
            } finally { host.close() }
        } } finally { server.close() }
    }

    /** Exercises actual image serialization over loopback, without consuming a user's API. */
    internal class ImageApiFixture(private val reply: String = org.json.JSONObject().put("source","Generated text").put("translation","测试译文").toString(),
        private val unsupportedSchema: Boolean = false) : AutoCloseable {
        private val server=java.net.ServerSocket(0)
        val port get()=server.localPort
        val requests=java.util.concurrent.ConcurrentLinkedQueue<org.json.JSONObject>()
        private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        @Volatile private var client: java.net.Socket?=null
        init { scope.launch {
            try { while(isActive) server.accept().use { socket ->
                client=socket; socket.soTimeout=10000
                val input=socket.getInputStream()
                fun line(): String {
                    val bytes=ByteArrayOutputStream()
                    while(true) { val b=input.read(); require(b>=0); if(b==10) break
                        require(bytes.size()<8192); if(b!=13) bytes.write(b) }
                    return bytes.toString("US-ASCII")
                }
                var length=0
                while(true) { val header=line(); if(header.isEmpty()) break
                    if(header.startsWith("Content-Length:",true)) length=header.substringAfter(':').trim().toInt() }
                require(length in 1..16_000_000)
                val body=ByteArray(length); var read=0
                while(read<length) { val n=input.read(body,read,length-read); require(n>0); read+=n }
                val request=org.json.JSONObject(String(body,Charsets.UTF_8))
                requests.add(request)
                val content=reply
                val unsupported=unsupportedSchema && request.has("response_format")
                val response=if(unsupported) org.json.JSONObject().put("error",org.json.JSONObject().put("message","Unsupported response_format json_schema")).toString().toByteArray(Charsets.UTF_8)
                    else org.json.JSONObject().put("choices",org.json.JSONArray().put(org.json.JSONObject()
                    .put("message",org.json.JSONObject().put("content",content)).put("finish_reason","stop")))
                    .toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write("HTTP/1.1 ${if(unsupported) "400 Bad Request" else "200 OK"}\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    write(response); flush()
                }
            } } catch (_: java.net.SocketException) { /* fixture shutdown */ }
        } }
        override fun close() { client?.close(); server.close(); scope.cancel() }
    }
    @Test fun structuredOutputFallsBackForUnsupportedServersAndPreservesExplicitParameters() = runBlocking {
        val server=ImageApiFixture(unsupportedSchema=true)
        try { for(explicit in listOf(false,true)) {
            container.apiProfiles.save(ApiProfile(profileId,ApiProfileKind.LLM,"Schema compatibility fixture","http://127.0.0.1:${server.port}/v1",model="fixture",retryCount=0,
                customParameters=if(explicit) "{\"response_format\":{\"type\":\"json_object\"}}" else "{}"))
            val host=AndroidWorkflowHost(container.applicationContext,container,mangaId,"Schema fixture",generatedChapters(1,AtomicInteger()).take(1),
                WorkflowRunSettings(LocalTranslationLanguage.ENGLISH,LocalTranslationLanguage.CHINESE_SIMPLIFIED,"",BubbleRenderSettings(),.35f),TranslationCacheBudget(32L*1_048_576),reusePages=false)
            server.requests.clear()
            try {
                if(explicit) {
                    try {WorkflowRuntime().execute(WorkflowTemplates.visionApi(profileId),host);fail("An explicit unsupported format must not be silently replaced")}
                    catch(failure:WorkflowExecutionFailure) {assertTrue(failure.message.orEmpty().contains("400"))}
                    assertTrue(server.requests.isNotEmpty());assertTrue(server.requests.all {it.has("response_format")})
                } else {
                    WorkflowRuntime().execute(WorkflowTemplates.visionApi(profileId),host)
                    val count=host.published.values.single().regions.size
                    assertEquals(count*2,server.requests.size)
                    assertEquals(count,server.requests.count {it.has("response_format")})
                    assertEquals(count,server.requests.count {!it.has("response_format")})
                }
            } finally {host.close()}
        } } finally {server.close()}
    }
    @Test fun realVisionApiRunsSavedProgramWithBilingualOutputAndChapterGlossary() = runBlocking<Unit> {
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Generated fixture test",
            "http://192.168.137.1:1234/v1", apiKey = "1", model = "gemma4-12b-qat-uncensored-hauhaucs-balanced@q4_k_m",
            parallelLimit = 2, retryCount = 0, parameters = AiParameters(temperature = 0.1, maxTokens = 2048))
        container.apiProfiles.save(profile)
        container.translationRepository.upsertGlossary(GlossaryEntry(mangaId, "Akira", "阿基拉", false, 1))
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(220, 220, 220))
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
            drawText("AKIRA, MEET ME AT", 125f, 270f, paint); drawText("MOON ACADEMY.", 145f, 345f, paint)
        }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val original = output.toByteArray(); val before = original.copyOf()
        val chapters = pageIds.mapIndexed { index, id ->
            val page = ReaderPage(id, 0, "generated.png", id)
            val source = object : PageSource {
                override suspend fun pages() = listOf(page)
                override suspend fun open(page: ReaderPage) = original.inputStream()
                override suspend fun probe(page: ReaderPage) = PageGeometry(800, 1000)
            }
            WorkflowChapterInput(if(index == 0) "7" else "39", "Synthetic chapter", source, listOf(page))
        }
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            "忠实翻译，中文自然", BubbleRenderSettings(), .35f, listOf(profile.copy(apiKey = "")))
        val readTexts = java.util.Collections.synchronizedList(mutableListOf<String>())
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated fixture", chapters, settings, cache, reusePages = false) {
            override suspend fun chapterFinished(frame: WorkflowFrame, complete: Boolean) {
                if(complete) readTexts += (frame.read(WorkflowRef("reread-results")) as WorkflowValue.ListValue).items.map { (it as WorkflowValue.Text).value }
            }
        }
        val peak = AtomicInteger()
        val observer = launch { container.apiClient.activeRequests.collect { peak.updateAndGet { old -> maxOf(old, it) } } }
        var edited = WorkflowTemplates.visionWithGlossary(profileId)
        edited = WorkflowEditing.update(edited, "pages") { it.copy(mode = WorkflowMode.SYNC) }
        edited = WorkflowEditing.insert(edited, WorkflowPosition("chapters", 0), WorkflowNode("images", WorkflowKind.DECLARE,
            variable = WorkflowVariable("saved-images", "图片列表", WorkflowType.list(WorkflowType.IMAGE))))
        repeat(2) { index -> edited = WorkflowEditing.insert(edited, WorkflowPosition("pages", 2 + index), WorkflowNode("remember-image-$index", WorkflowKind.APPEND,
            target = WorkflowRef("saved-images"), inputs = mapOf("value" to WorkflowSystem.ref(WorkflowSystem.PAGE, "image")))) }
        val end = edited.allNodes().first { it.kind == WorkflowKind.CHAPTERS }.children.size
        edited = WorkflowEditing.insert(edited, WorkflowPosition("chapters", end), WorkflowNode("results", WorkflowKind.DECLARE,
            variable = WorkflowVariable("reread-results", "重读结果", WorkflowType.list(WorkflowType.TEXT))))
        edited = WorkflowEditing.insert(edited, WorkflowPosition("chapters", end + 1), WorkflowNode("reread", WorkflowKind.EACH,
            variable = WorkflowVariable("reread-image", "已完成页图片", WorkflowType.IMAGE), collectTo = WorkflowRef("reread-results"),
            inputs = mapOf("items" to WorkflowSystem.ref("saved-images")), children = listOf(
                WorkflowNode("reread-text", WorkflowKind.DECLARE, variable = WorkflowVariable("reread-text-value", "读到的文字", WorkflowType.TEXT)),
                WorkflowNode("reread-ocr", WorkflowKind.OCR, target = WorkflowRef("reread-text-value"), inputs = mapOf("image" to WorkflowSystem.ref("reread-image"), "language" to WorkflowSystem.ref(WorkflowSystem.SOURCE))),
                WorkflowNode("reread-return", WorkflowKind.RETURN, inputs = mapOf("value" to WorkflowSystem.ref("reread-text-value"))))))
        val program = WorkflowProgramCodec.decode(WorkflowProgramCodec.encode(edited))
        try {
            withTimeout(240_000) { WorkflowRuntime(8).execute(program, host) }
            assertEquals(2, host.published.size)
            assertEquals(4, readTexts.size)
            assertTrue(readTexts.all { it.contains("AKIRA", true) })
            assertTrue(host.published.values.all { it.regions.isNotEmpty() && it.regions.any { r -> r.region.sourceText.isNotBlank() && r.translatedText.isNotBlank() } })
            assertTrue("API requests exceeded 2: ${peak.get()}", peak.get() in 1..2)
            assertEquals("阿基拉", container.translationRepository.glossary(mangaId).first { it.source == "Akira" }.target)
            assertTrue(container.translationRepository.glossary(mangaId).any { it.source.contains("moon", true) })
            assertArrayEquals(before, original)
            assertTrue(host.published.values.all { it.dataFile.extension == "json" })
            assertEquals(0L, cache.bytes.value)
            assertEquals(0, container.apiClient.activeRequests.value)
        } finally { observer.cancelAndJoin(); host.close() }
    }
    private fun generatedChapters(perChapter: Int, opens: AtomicInteger): List<WorkflowChapterInput> {
        val bitmap = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(220, 220, 220))
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            drawOval(50f, 100f, 750f, 540f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 6f })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 44f; typeface = Typeface.DEFAULT_BOLD }
            drawText("AKIRA, MEET ME AT", 125f, 270f, paint); drawText("MOON ACADEMY.", 145f, 345f, paint)
        }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val bytes = output.toByteArray()
        return pageIds.mapIndexed { chapterIndex, seed ->
            val pages = (1..perChapter).map { i ->
                val id = "$seed-$i"; extraPageIds += id
                ReaderPage(id, i - 1, "generated-$i.png", id)
            }
            val source = object : PageSource {
                override suspend fun pages() = pages
                override suspend fun open(page: ReaderPage): java.io.InputStream { opens.incrementAndGet(); return bytes.inputStream() }
                override suspend fun probe(page: ReaderPage) = PageGeometry(800, 1000)
            }
            WorkflowChapterInput(if(chapterIndex == 0) "7" else "39", "Synthetic chapter", source, pages)
        }
    }
    @Test fun realFullMangaTemplateRequestsApiOnceForTwoChapters() = runBlocking<Unit> {
        val profile = ApiProfile(profileId, ApiProfileKind.LLM, "Generated full manga test", "http://192.168.137.1:1234/v1",
            apiKey = "1", model = "gemma4-12b-qat-uncensored-hauhaucs-balanced@q4_k_m", parallelLimit = 2, retryCount = 0,
            parameters = AiParameters(temperature = 0.1, maxTokens = 4096))
        container.apiProfiles.save(profile)
        val opens = AtomicInteger(); val calls = AtomicInteger()
        val chapters = generatedChapters(1, opens)
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED,
            "忠实、自然", BubbleRenderSettings(), .35f, listOf(profile.copy(apiKey = "")))
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated full manga", chapters, settings, cache, reusePages = false) {
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                assertTrue(call.wholeManga); assertTrue(published.isEmpty())
                assertTrue(chapters.flatMap { it.pages }.none { container.localPageTranslator.artifacts.has(it.pageId) })
                assertEquals(2, opens.get()); calls.incrementAndGet()
                return super.api(call, frame)
            }
        }
        try {
            withTimeout(240_000) { WorkflowRuntime().execute(WorkflowReferenceTemplates.fullManga(profileId), host) }
            assertEquals(1, calls.get()); assertEquals(2, host.published.size)
            assertTrue(host.published.values.all { it.regions.isNotEmpty() && it.regions.any { r -> r.translatedText.isNotBlank() && r.region.sourceText.isNotBlank() } })
            assertTrue(host.published.values.all { it.dataFile.extension == "json" })
            assertTrue(container.translationRepository.glossary(mangaId).isEmpty())
            assertEquals(2, opens.get()); assertEquals(0L, cache.bytes.value)
        } finally { host.close() }
    }
    @Test fun fullMangaPreprocessingEvictsOnlyReadyBitmapsAndPublishesWithoutDecodingAgain() = runBlocking<Unit> {
        val opens = AtomicInteger(); val calls = AtomicInteger()
        val chapters = generatedChapters(6, opens)
        val cache = TranslationCacheBudget(32L * 1_048_576)
        val settings = WorkflowRunSettings(LocalTranslationLanguage.ENGLISH, LocalTranslationLanguage.CHINESE_SIMPLIFIED, "", BubbleRenderSettings(), .35f)
        val host = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, "Generated cache fixture", chapters, settings, cache, reusePages = false) {
            override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue {
                assertTrue(published.isEmpty()); assertEquals(12, opens.get()); assertTrue(cache.bytes.value in 1..32L * 1_048_576)
                calls.incrementAndGet()
                return WorkflowValue.ListValue(call.expectedItems!!.items.reversed().map { item ->
                    val row = item as WorkflowValue.Record
                    row.copy(fields = row.fields + ("translation" to text("合成译文")))
                })
            }
        }
        try {
            withTimeout(120_000) { WorkflowRuntime().execute(WorkflowReferenceTemplates.fullManga("fixture"), host) }
            assertEquals(1, calls.get()); assertEquals(12, host.published.size); assertEquals(12, opens.get())
            assertEquals(0L, cache.bytes.value)
        } finally { host.close() }
    }
}
