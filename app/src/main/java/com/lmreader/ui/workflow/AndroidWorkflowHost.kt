package com.lmreader.ui.workflow

import com.lmreader.ui.i18n.Text

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.lmreader.core.api.*
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.PageSource
import com.lmreader.core.storage.reader.ReaderPage
import com.lmreader.core.workflow.*
import com.lmreader.core.vision.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.reader.translation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor

data class WorkflowChapterInput(val id: String, val name: String, val source: PageSource, val pages: List<ReaderPage>)
data class WorkflowRunSettings(val source: LocalTranslationLanguage, val target: LocalTranslationLanguage,
    val style: String, val render: BubbleRenderSettings, val segThreshold: Float, val apiProfiles: List<ApiProfile> = emptyList(),
    val segTextScope: SegTextScope = SegTextScope.ALL, val textDetectionThreshold: Float = .35f,
    val freeTextMergeGapRatio: Float = DEFAULT_FREE_TEXT_MERGE_GAP_RATIO)

/** One host owns a manga run. Image references survive page completion; decoded bitmaps use the SEG MB budget. */
open class AndroidWorkflowHost(private val context: Context, protected val container: AppContainer,
    private val mangaId: String, mangaName: String, private val chapterInputs: List<WorkflowChapterInput>,
    private val settings: WorkflowRunSettings, private val budget: TranslationCacheBudget,
    private val reusePages: Boolean = true, private val requestOrigin: String = "") : WorkflowRuntimeHost {
    override val manga = record("id" to text(mangaId), "name" to text(mangaName))
    override val sourceLanguage get() = settings.source.tag
    override val targetLanguage get() = settings.target.tag
    override val style get() = settings.style
    private val chapters = chapterInputs.associateBy { it.id }
    private val inputs = chapterInputs.flatMap { chapter -> chapter.pages.map { it.pageId to (chapter.source to it) } }.toMap()
    private data class ImageRef(val pageId: String, val area: PixelRect? = null)
    private class ImageState {
        val mutex = Mutex(); var bitmap: Bitmap? = null; var hash = ""; var lease: TranslationCacheBudget.Lease? = null
        var closed = false; var readers = 0; var seg: SegResult? = null; val started = System.nanoTime()
        var width = 0; var height = 0
        @Volatile var prepared = false
        var collectingConsumers = 0
        var releaseWhenIdle = false
        @Volatile var resultBytes = 0L
        val ocrBytes = ConcurrentHashMap<String, Long>()
        var cachePinned = false
    }
    private val states = ConcurrentHashMap<String, ImageState>()
    private val images = ConcurrentHashMap<String, ImageRef>()
    private val cached = ConcurrentHashMap<String, ReaderPageTranslation>()
    private val prepared = ConcurrentHashMap<String, WorkflowValue.Record>()
    private val waitingForBitmap = AtomicInteger()
    private val prefetchLock = Mutex()
    private val geometry = ConcurrentHashMap<String, SegRegion>()
    private val ocrBounds = ConcurrentHashMap<String, List<PixelRect>>()
    val published = ConcurrentHashMap<String, ReaderPageTranslation>()
    init { inputs.keys.forEach { images["page:$it"] = ImageRef(it) } }
    override fun parallelism(node: WorkflowNode): Int = WorkflowResourceCapacities(
        container.localVision.segConcurrency, container.localVision.ocrConcurrency, 1,
        settings.apiProfiles.associate { it.id to it.parallelLimit },
        (container.translationCachePreferences.megabytes.value / 16 /
            container.translationQueue.activeMangas.value.size.coerceAtLeast(1)).coerceAtLeast(1)).parallelism(node)
    override suspend fun chapters() = chapterInputs.mapIndexed { index, chapter -> record("id" to text(chapter.id),
        "name" to text(chapter.name), "index" to number(index + 1), "records" to list(emptyList())) }
    override suspend fun pages(chapter: WorkflowValue.Record) = chapters.getValue(chapter.string("id")).pages.mapIndexed { index, page ->
        record("id" to text(page.pageId), "name" to text(page.displayName), "number" to number(index + 1),
            "image" to WorkflowValue.Image("page:${page.pageId}"), "bubbles" to list(emptyList()), "records" to list(emptyList()))
    }
    override suspend fun glossary() = container.translationRepository.glossary(mangaId).associate { it.source to it.target }
    override suspend fun mergeGlossary(entries: Map<String, String>) {
        container.database.translationDao().insertGlossary(entries.map { (source, target) ->
            com.lmreader.core.database.entity.MangaGlossaryEntity(mangaId, source, target, false, System.currentTimeMillis())
        })
    }
    override suspend fun admitPage(frame: WorkflowFrame, job: Job): WorkflowPageAdmission {
        val id = requireNotNull(frame.identity(WorkflowSystem.PAGE))
        states[id]?.let { state -> state.mutex.withLock { state.prepared = false; state.lease?.setCanAdvance(true) } }
        prepared[id]?.let { frame.define(WorkflowSystem.PAGE, it, WorkflowSystem.pageType) }
        if (reusePages) {
            val (source, page) = inputs.getValue(id)
            container.localPageTranslator.cached(source, page, settings.render)?.let { saved ->
                cached[id] = saved
                setRecords(frame, saved.regions.map { regionRecord(it, id, pageNumber(frame)) })
                return WorkflowPageAdmission.CACHED
            }
        }
        return WorkflowPageAdmission.RUN
    }
    override suspend fun request(kind: WorkflowKind, inputs: Map<String, WorkflowValue>, frame: WorkflowFrame): WorkflowValue {
        fun value(key: String) = (inputs.getValue(key) as WorkflowValue.Text).value
        return when (kind) {
            WorkflowKind.SEG -> {
                val reference = images[(inputs.getValue("image") as WorkflowValue.Image).key] ?: error("图片不存在")
                require(reference.pageId == frame.identity(WorkflowSystem.PAGE) && reference.area == null) { "SEG 应处理本页图片" }
                progress(frame, PageTranslationProgress(PageTranslationStage.READING))
                val state = image(reference.pageId)
                try {
                val seg = container.localVision.segment(reference.pageId, state.bitmap!!, settings.segThreshold, settings.textDetectionThreshold, sourceSha256 = state.hash) { progress(frame, PageTranslationProgress(PageTranslationStage.SEGMENTING, it.completed, it.total)) }
                val regions = selectSegRegions(seg, settings.segTextScope, settings.freeTextMergeGapRatio)
                require(regions.size <= 1000) { "气泡过多，请分批处理" }
                // Invalidate only this page. A fresh generation prevents copied old iterators
                // from resolving to new geometry when the detector reuses an indexed ID.
                val generation = java.util.UUID.randomUUID().toString()
                images.entries.filter { it.value.pageId == reference.pageId && it.key.startsWith("bubble:") }.forEach { entry ->
                    if(images.remove(entry.key, entry.value)) {
                        val id = entry.key.removePrefix("bubble:")
                        geometry.remove(id); ocrBounds.remove(id)
                    }
                }
                cached.remove(reference.pageId); prepared.remove(reference.pageId); published.remove(reference.pageId)
                state.seg = seg
                state.mutex.withLock {
                    state.resultBytes = seg.regions.sumOf { 256L + it.contour.size * 8L } + seg.textLines.size * 48L
                    accountPage(state)
                }
                list(regions.mapIndexed { index, detected ->
                    val region = detected.copy(id = "${detected.id}:seg:$generation")
                    geometry[region.id] = region; images["bubble:${region.id}"] = ImageRef(reference.pageId, region.bounds)
                    record("id" to text(region.id), "index" to number(index + 1), "image" to WorkflowValue.Image("bubble:${region.id}"),
                        "source" to text(""), "translation" to text(""), "confidence" to WorkflowValue.Number(region.confidence.toDouble()), "kind" to text(region.kind.name))
                })
                } finally { releaseReader(state) }
            }
            WorkflowKind.OCR -> {
                val key = (inputs.getValue("image") as WorkflowValue.Image).key
                val reference = images[key] ?: error("图片不存在")
                val state = image(reference.pageId)
                try {
                val language = LocalPageTranslator.ocrLanguage(LocalTranslationLanguage.fromTag(value("language")))
                val region = key.takeIf { it.startsWith("bubble:") }?.removePrefix("bubble:")?.let { geometry[it] }
                val report: (VisionProgress) -> Unit = { progress(frame, PageTranslationProgress(PageTranslationStage.OCR, it.completed, it.total)) }
                val result = if (region != null) container.localVision.cachedRecognizeRegion(reference.pageId, state.hash, state.bitmap!!,
                    language, region, state.seg!!.regions, state.seg!!.textLines, report)
                else container.localVision.recognize(reference.pageId, state.bitmap!!, language,
                    reference.area?.let { kotlin.collections.listOf(it) }, state.seg?.textLines?.filter { line ->
                        reference.area?.let { area -> line.bounds.left >= area.left && line.bounds.top >= area.top && line.bounds.right <= area.right && line.bounds.bottom <= area.bottom } ?: true
                    }, report)
                if (key.startsWith("bubble:")) ocrBounds[key.removePrefix("bubble:")] = result.lines.map { it.bounds }
                state.mutex.withLock {
                    state.ocrBytes[key] = result.lines.sumOf { 96L + it.text.length * 2L }
                    accountPage(state)
                }
                text(result.translationText)
                } finally { releaseReader(state) }
            }
            WorkflowKind.TRANSLATE -> {
                frame.identity(WorkflowSystem.PAGE)?.let { parkBitmap(it) }
                val input = value("text")
                if (input.isBlank()) text("") else {
                    val source = LocalTranslationLanguage.fromTag(value("source")); val target = LocalTranslationLanguage.fromTag(value("target"))
                    val result = container.localTranslator.translate(source, target, kotlin.collections.listOf(LocalTranslationText("workflow", input))) { completed, total -> progress(frame, PageTranslationProgress(PageTranslationStage.TRANSLATING, completed, total)) }
                    text(result.items.single().translatedText)
                }
            }
            else -> error("未知引擎积木")
        }
    }
    override suspend fun api(call: WorkflowApiCall, frame: WorkflowFrame): WorkflowValue = apiRequest(call, frame)
    override suspend fun apiStream(call: WorkflowApiCall, frame: WorkflowFrame, item: suspend (WorkflowValue, Int) -> Unit): WorkflowValue.ListValue =
        apiRequest(call, frame, item) as WorkflowValue.ListValue
    private suspend fun apiRequest(call: WorkflowApiCall, frame: WorkflowFrame, item: (suspend (WorkflowValue, Int) -> Unit)? = null): WorkflowValue {
        val profiles = container.apiProfiles.profiles.first()
        val live = profiles.firstOrNull { it.id == call.profileId } ?: error("API 配置已删除，请重新选择")
        container.apiClient.configureProfiles(if(settings.apiProfiles.isNotEmpty()) settings.apiProfiles else profiles)
        val profile = settings.apiProfiles.firstOrNull { it.id == call.profileId }?.copy(apiKey = live.apiKey) ?: live
        val attachments = call.images.map { encodeImage(it) }
        (call.images.mapNotNull { images[it.key]?.pageId } + listOfNotNull(frame.identity(WorkflowSystem.PAGE))).distinct()
            .forEach { parkBitmap(it) }
        require(attachments.sumOf { it.base64.length } <= 16_000_000) { "API 图片附件超过 16 MB，请分批处理" }
        val example = schemaExample(call.resultType).let { structure ->
            call.expectedBubbleId?.let { structure.replace("\"bubbleId\":\"文本\"", "\"bubbleId\":" + org.json.JSONObject.quote(it)) } ?: structure
        }
        val shape = when(call.resultType.kind) {
            WorkflowDataKind.RECORD, WorkflowDataKind.DICTIONARY -> "只返回一个 JSON 对象，不要返回列表。"
            WorkflowDataKind.LIST -> "只返回一个完整 JSON 列表。"
            else -> "只返回完整 JSON。"
        }
        val schema = if (call.resultType == WorkflowType.TEXT) "" else "\n${shape}不要说明或 Markdown。所有字段必须提供且不能添加字段。输出结构：$example"
        val emptyRegionHint = if(call.resultType == WorkflowType.GLOSSARY_ENTRY && call.images.singleOrNull()?.key?.startsWith("bubble:") == true)
            "如果附件只有图案而没有可辨认文字，返回 {\"source\":\"\",\"translation\":\"\"}。" else ""
        val imageHint = if(attachments.isEmpty()) "" else "\n本次消息已附带 ${attachments.size} 张实际图片，内容可能是气泡或游离文字区域。请直接读取图片，不要要求用户再次上传。$emptyRegionHint"
        val messages = call.messages.mapIndexed { index, message -> ApiMessage(message.role,
            message.content + if (index == call.messages.lastIndex) imageHint + schema else "", if (index == call.messages.lastIndex) attachments else emptyList()) }
        val result = StringBuilder()
        val bubbleId = call.expectedBubbleId ?: call.images.singleOrNull()?.key?.takeIf { it.startsWith("bubble:") }?.removePrefix("bubble:")
        val parser = if(item != null) WorkflowJsonStream(call.resultType, call.expectedItems, bubbleId) else null
        val trace = ApiTraceContext(mangaId, manga.string("name"),
            runCatching { frame.text(WorkflowSystem.CHAPTER, "name") }.getOrDefault(""),
            runCatching { frame.text(WorkflowSystem.PAGE, "name") }.getOrDefault(""), listOf(requestOrigin, call.stepName).filter { it.isNotBlank() }.joinToString(" · "))
        val capture = ApiTraceCapture()
        var transportComplete = false
        var outputFailed = false
        val parameters = org.json.JSONObject(profile.customParameters.ifBlank { "{}" })
        val responseFormat = if(profile.format == ApiFormat.CHAT && !parameters.has("response_format")) WorkflowJsonSchema.responseFormat(call.resultType) else null
        val constrainedProfile = responseFormat?.let { profile.copy(customParameters = parameters.put("response_format", org.json.JSONObject(it)).toString()) } ?: profile
        val collecting = if(frame.identity(WorkflowSystem.PAGE) == null) states.values.filter { it.prepared } else emptyList()
        collecting.forEach { state -> state.mutex.withLock {
            state.collectingConsumers++
            state.lease?.setCanAdvance(true)
        } }
        waitingForApi(call, frame, true)
        try { return coroutineScope {
            val preparing = if (frame.identity(WorkflowSystem.PAGE) != null)
                launch(Dispatchers.IO) { if(awaitPrefetchPermission(frame)) presegmentAhead(frame) } else null
            try { withContext(trace + capture) {
            suspend fun collectResponse(requestProfile: ApiProfile) = container.apiClient.stream(requestProfile, messages).collect { event -> if(event is ApiStreamEvent.Text && !event.thinking) {
                require(result.length + event.value.length <= 4_000_000) { "API 输出超过 4 MB" }; result.append(event.value)
                if(parser != null) try { parser.append(event.value, requireNotNull(item)) }
                    catch(failure: IllegalArgumentException) { outputFailed = true; throw failure }
            } }
            try { collectResponse(constrainedProfile) } catch(failure: ApiException) {
                val unsupported = failure.httpCode in setOf(400, 422) && Regex("response_format|json_schema|json schema|structured output", RegexOption.IGNORE_CASE).containsMatchIn(failure.message.orEmpty())
                if(responseFormat != null && result.isEmpty() && unsupported) collectResponse(profile) else throw failure
            }
            transportComplete = true
            require(result.isNotBlank()) { "API 没有返回正文" }
            val parsed = if(parser != null) parser.finish()
                else WorkflowValueCodec.parseResponse(result.toString(), call.resultType, bubbleId, call.expectedItems)
            call.expectedCount?.let { WorkflowValueCodec.requireItemCount(parsed, it) }
            parsed
            } } finally { preparing?.cancelAndJoin() }
        } } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) {
            if(transportComplete || outputFailed) {
                val detail = (failure.message ?: "API 输出校验失败") + "\n本次请求已附带 ${attachments.size} 张图片。"
                withContext(NonCancellable) { capture.requestId.get()?.let { runCatching { container.apiLogs.recordOutputFailure(it, detail) } } }
                throw IllegalArgumentException(detail, failure)
            }
            throw failure
        } finally { withContext(NonCancellable) {
            waitingForApi(call, frame, false)
            collecting.forEach { state -> state.mutex.withLock {
                state.collectingConsumers--
                state.lease?.setCanAdvance(!state.prepared || state.collectingConsumers > 0)
            } }
        } }
    }
    /** Any API step (including a later API in the same workflow) wakes the scheduler. */
    protected open suspend fun waitingForApi(call: WorkflowApiCall, frame: WorkflowFrame, waiting: Boolean) {}
    protected open suspend fun awaitPrefetchPermission(frame: WorkflowFrame) = false
    protected open suspend fun canPrefetch(chapterId: String, pageId: String) = continueScheduling()
    private suspend fun presegmentAhead(frame: WorkflowFrame) {
        if (!prefetchLock.tryLock()) return
        try {
            val current = frame.identity(WorkflowSystem.PAGE) ?: return
            val pages = chapterInputs.flatMap { chapter -> chapter.pages.map { chapter.id to it } }
            val index = pages.indexOfFirst { it.second.pageId == current }
            if (index < 0) return
            for ((chapterId, page) in pages.drop(index + 1)) {
                currentCoroutineContext().ensureActive()
                if (!canPrefetch(chapterId, page.pageId)) break
                if (states.containsKey(page.pageId) || cached.containsKey(page.pageId) || published.containsKey(page.pageId)) continue
                if (reusePages && container.localPageTranslator.artifacts.has(page.pageId)) continue
                // Admission waits for budget. The current page's SEG and OCR lease
                // is never evicted to make space for speculative work.
                var state: ImageState? = null
                try {
                    val owned = image(page.pageId).also { state = it }
                    val seg = container.localVision.segment(page.pageId, owned.bitmap!!, settings.segThreshold,
                        settings.textDetectionThreshold, sourceSha256 = owned.hash)
                    owned.mutex.withLock {
                        owned.seg = seg
                        owned.resultBytes = seg.regions.sumOf { 256L + it.contour.size * 8L } + seg.textLines.size * 48L
                        accountPage(owned)
                    }
                } finally {
                    state?.let { releaseReader(it); parkBitmap(page.pageId) }
                }
            }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(_: Exception) { /* A speculative failure is retried through the real SEG row. */ }
        finally { prefetchLock.unlock() }
    }
    private suspend fun encodeImage(image: WorkflowValue.Image): ApiImage {
        val reference = images[image.key] ?: error("图片引用不存在")
        val state = this.image(reference.pageId)
        return try {
            val bitmap = state.bitmap ?: error("图片已释放")
            val region = image.key.takeIf { it.startsWith("bubble:") }?.removePrefix("bubble:")?.let { geometry[it] }
            val isolated = region?.let { cropSegRegion(bitmap, it, state.seg!!.regions) }
            val crop = isolated?.bitmap ?: reference.area?.let { area ->
                val left = floor(area.left).toInt().coerceIn(0, bitmap.width - 1)
                val top = floor(area.top).toInt().coerceIn(0, bitmap.height - 1)
                Bitmap.createBitmap(bitmap, left, top, (ceil(area.right).toInt() - left).coerceIn(1, bitmap.width - left),
                    (ceil(area.bottom).toInt() - top).coerceIn(1, bitmap.height - top))
            } ?: bitmap
            try {
                val output = ByteArrayOutputStream()
                val format = if (region != null) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(crop.compress(format, 92, output))
                ApiImage(if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg",
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } finally { releaseReader(state) }
    }
    private suspend fun image(id: String): ImageState {
        val state = states.computeIfAbsent(id) { ImageState() }
        state.mutex.withLock {
            if (state.bitmap == null) {
                state.releaseWhenIdle = false
                val lease = state.lease ?: acquireBitmapLease().also { state.lease = it }
                if (!state.cachePinned) {
                    container.localVision.preprocessingCache.markPendingPage(id)
                    container.localVision.preprocessingCache.retainPage(id); state.cachePinned = true
                }
                // Reloading the original for this admitted page cannot wait on its own results.
                lease.resizeForPage(16_000_000 + state.resultBytes + state.ocrBytes.values.sum())
                var temporary: File? = null
                try {
                    val file = File.createTempFile("workflow-source-", ".image", context.cacheDir).also { temporary = it }
                    val (source, page) = inputs.getValue(id)
                    val digest = MessageDigest.getInstance("SHA-256"); var total = 0L
                    source.open(page).use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer); if (count < 0) break
                            total += count; require(total <= 64_000_000) { "原图超过 64 MB" }
                            digest.update(buffer, 0, count); output.write(buffer, 0, count)
                        }
                    } }
                    state.bitmap = decodePageAnalysisImage(context, file)
            state.width = state.bitmap!!.width; state.height = state.bitmap!!.height
                    state.hash = digest.digest().joinToString("") { "%02x".format(it) }
                    state.lease = lease; accountPage(state)
                } catch (failure: Throwable) { withContext(NonCancellable) { release(state) }; throw failure }
                finally { temporary?.delete() }
            }
            state.readers++
        }
        return state
    }
    override suspend fun publishPage(frame: WorkflowFrame) {
        val id = requireNotNull(frame.identity(WorkflowSystem.PAGE))
        cached[id]?.let { saved ->
            container.localPageTranslator.pageWriteMutex.withLock {
                if (!canPublish(frame)) throw WorkflowPageStopped()
                published[id] = saved; pagePublished(frame, saved)
                container.localVision.preprocessingCache.commitPage(id)
            }
            return
        }
        val state = states[id] ?: error("本页没有执行 SEG，无法建立译文气泡")
        require(state.seg != null) { "本页没有执行 SEG，无法建立译文气泡" }
        val regions = translatedRegions(frame, id)
        setRecords(frame, regions.map { regionRecord(it, id, pageNumber(frame)) })
        progress(frame, PageTranslationProgress(PageTranslationStage.SAVING))
        container.localPageTranslator.pageWriteMutex.withLock {
            if (!canPublish(frame)) throw WorkflowPageStopped()
            val job = currentCoroutineContext()
            val saved = container.localPageTranslator.artifacts.save(id, state.hash, settings.source, settings.target,
                state.width, state.height, regions, emptyList(), (System.nanoTime() - state.started) / 1_000_000,
                settings.render, checkCancelled = { job.ensureActive() })
            published[id] = saved
            pagePublished(frame, saved)
            container.localVision.preprocessingCache.commitPage(id)
        }
    }
    override suspend fun previewPage(frame: WorkflowFrame) = container.localPageTranslator.pageWriteMutex.withLock {
        val id = frame.identity(WorkflowSystem.PAGE) ?: return@withLock
        val state = states[id] ?: return@withLock
        if(!canPublish(frame)) return@withLock
        val saved = ReaderPageTranslation(id, java.util.UUID.randomUUID().toString(), File(container.localPageTranslator.artifacts.root, "preview"),
            settings.source, settings.target, state.hash, state.width, state.height,
            translatedRegions(frame, id), emptyList(),
            (System.nanoTime() - state.started) / 1_000_000, settings.render, preview = true)
        pagePreviewed(frame, saved)
    }
    protected open suspend fun pagePreviewed(frame: WorkflowFrame, saved: ReaderPageTranslation) {}
    override suspend fun segmentedPage(frame: WorkflowFrame) = previewPage(frame)
    private suspend fun translatedRegions(frame: WorkflowFrame, id: String): List<PageTranslatedRegion> {
        val bubbles = (frame.read(WorkflowRef(WorkflowSystem.PAGE, kotlin.collections.listOf("bubbles"))) as WorkflowValue.ListValue).items
        val seg = states[id]?.seg ?: error("本页没有执行 SEG，无法建立译文气泡")
        require(bubbles.size <= 1000 && bubbles.map { (it as WorkflowValue.Record).string("id") }.distinct().size == bubbles.size) { "气泡列表重复或过多" }
        return bubbles.map { value ->
            val bubble = value as WorkflowValue.Record; val bubbleId = bubble.string("id")
            val area = geometry[bubbleId] ?: error("气泡几何必须来自 SEG")
            require(bubbleId.startsWith("$id:")) { "不能把其他页的气泡回填到本页" }
            PageTranslatedRegion(PageTextRegion(bubbleId, area.kind, area.bounds, area.contour,
                bubble.string("source"), ocrBounds[bubbleId] ?:
                    selectRegionTextLines(area, seg.regions, seg.textLines).map { it.bounds }), bubble.string("translation"))
        }
    }
    override suspend fun completePreparationPage(frame: WorkflowFrame): WorkflowValue.ListValue {
        val id = requireNotNull(frame.identity(WorkflowSystem.PAGE))
        if(isCached(frame)) { setRecords(frame, emptyList()); return list(emptyList()) }
        val state = states[id] ?: error("预处理需要 SEG")
        require(state.seg != null) { "预处理需要 SEG" }
        setRecords(frame, translatedRegions(frame, id).map { regionRecord(it, id, pageNumber(frame)) })
        prepared[id] = frame.read(WorkflowRef(WorkflowSystem.PAGE)) as WorkflowValue.Record
        return frame.read(WorkflowRef(WorkflowSystem.PAGE, kotlin.collections.listOf("records"))) as WorkflowValue.ListValue
    }
    override suspend fun closePreparationPage(frame: WorkflowFrame) {
        val id = frame.identity(WorkflowSystem.PAGE) ?: return
        states[id]?.let { state -> state.mutex.withLock {
            state.prepared = true
            // Only the reloadable image can be released. SEG/OCR and their lease
            // remain protected until this page is published or the run stops.
            if(state.readers == 0) releaseBitmap(state)
            state.lease?.setCanAdvance(state.collectingConsumers > 0)
        } }
    }
    private suspend fun acquireBitmapLease(): TranslationCacheBudget.Lease {
        budget.tryAcquire(16_000_000, mangaId)?.let { return it }
        waitingForBitmap.incrementAndGet()
        try {
            for(candidate in states.values) {
                if(!candidate.mutex.tryLock()) continue
                try {
                    if(candidate.prepared && candidate.readers == 0 && candidate.bitmap != null) releaseBitmap(candidate)
                } finally { candidate.mutex.unlock() }
                budget.tryAcquire(16_000_000, mangaId)?.let { return it }
            }
            return budget.acquire(16_000_000, mangaId)
        } finally { waitingForBitmap.decrementAndGet() }
    }
    protected open suspend fun canPublish(frame: WorkflowFrame) = true
    protected open fun progress(frame: WorkflowFrame, progress: PageTranslationProgress) {}
    protected fun isCached(frame: WorkflowFrame) = cached.containsKey(frame.identity(WorkflowSystem.PAGE))
    protected fun pageLabel(frame: WorkflowFrame) = frame.identity(WorkflowSystem.PAGE)?.let { inputs[it]?.second?.displayName } ?: "章节工作流"
    protected open suspend fun pagePublished(frame: WorkflowFrame, saved: ReaderPageTranslation) {}
    override suspend fun closePage(frame: WorkflowFrame) {
        frame.identity(WorkflowSystem.PAGE)?.let { id -> states[id]?.let { state -> state.mutex.withLock {
            state.closed = true
            if(state.readers == 0) release(state)
            if (state.cachePinned) { container.localVision.preprocessingCache.releasePage(id); state.cachePinned = false }
        } } }
    }
    private suspend fun releaseReader(state: ImageState) = withContext(NonCancellable) {
        state.mutex.withLock { check(state.readers > 0); state.readers--
            if(state.readers == 0) { if(state.closed) release(state) else if(state.releaseWhenIdle) releaseBitmap(state) }
        }
    }
    private suspend fun parkBitmap(pageId: String) = withContext(NonCancellable) {
        states[pageId]?.let { state -> state.mutex.withLock {
            state.releaseWhenIdle = true
            if (state.readers == 0) releaseBitmap(state)
        } }
    }
    private suspend fun accountPage(state: ImageState) {
        state.lease?.resizeForPage((state.bitmap?.allocationByteCount?.toLong() ?: 0L) + state.resultBytes + state.ocrBytes.values.sum())
    }
    private suspend fun releaseBitmap(state: ImageState) { state.bitmap?.recycle(); state.bitmap = null; accountPage(state) }
    private suspend fun release(state: ImageState) { state.bitmap?.recycle(); state.bitmap = null; state.lease?.release(); state.lease = null }
    suspend fun close() = withContext(NonCancellable) { states.forEach { (id, state) -> state.mutex.withLock {
        release(state)
        if (state.cachePinned) { container.localVision.preprocessingCache.releasePage(id); state.cachePinned = false }
    } } }
    private suspend fun pageNumber(frame: WorkflowFrame) = (frame.read(WorkflowRef(WorkflowSystem.PAGE, kotlin.collections.listOf("number"))) as WorkflowValue.Number).value
    private fun regionRecord(region: PageTranslatedRegion, id: String, number: Double) = record("bubbleId" to text(region.region.id),
        "source" to text(region.region.sourceText), "translation" to text(region.translatedText), "pageId" to text(id), "pageNumber" to WorkflowValue.Number(number))
    private fun setRecords(frame: WorkflowFrame, records: List<WorkflowValue>) = frame.write(WorkflowRef(WorkflowSystem.PAGE, kotlin.collections.listOf("records")), list(records))
    companion object {
        fun text(value: String) = WorkflowValue.Text(value)
        fun number(value: Int) = WorkflowValue.Number(value.toDouble())
        fun list(value: List<WorkflowValue>) = WorkflowValue.ListValue(value)
        fun record(vararg values: Pair<String, WorkflowValue>) = WorkflowValue.Record(values.toMap())
        fun schemaExample(type: WorkflowType): String = when (type.kind) {
            WorkflowDataKind.TEXT -> "\"文本\""; WorkflowDataKind.NUMBER -> "0"; WorkflowDataKind.BOOLEAN -> "true"
            WorkflowDataKind.LIST -> "[${schemaExample(type.element!!)}]"
            WorkflowDataKind.DICTIONARY -> "{\"原词\":\"译名\"}"
            WorkflowDataKind.RECORD -> type.fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${schemaExample(it.value)}" }
            else -> error("API 输出类型无效")
        }
    }
}
private fun WorkflowValue.Record.string(key: String) = (fields.getValue(key) as WorkflowValue.Text).value
