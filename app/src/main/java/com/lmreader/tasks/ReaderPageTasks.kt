package com.lmreader.tasks

import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.model.*
import com.lmreader.core.vision.LocalVisionEngine
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.reader.ReaderItem
import com.lmreader.ui.reader.translation.*
import com.lmreader.ui.workflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest

/** Reconstructible single-page work, independent of the reader's ViewModel lifetime. */
class ReaderPageTasks(private val container: AppContainer) : TaskExecutor {
    init { container.taskScheduler.register(TYPE, this) }
    suspend fun submit(mangaId: String, item: ReaderItem.PageItem, render: BubbleRenderSettings,
        expectedRevision: String?): String {
        val options = container.mangaRepository.translationSettings(mangaId)
        require(translationSetupComplete(options.sourceLanguage, options.targetLanguage, options.autoDetectSource)) {
            "请先填写漫画的原文语言和目标语言"
        }
        val workflow = container.translationWorkflows.find(options.workflowId) ?: error("所选翻译工作流已删除")
        val category = container.shelfRepository.categoryIdOf(mangaId)?.let { id ->
            container.shelfRepository.observeCategories().first().firstOrNull { it.categoryId == id }?.customStyle
        }
        val installed = container.translationModels.installedCatalog().languages + LocalPageTranslator.ocrSources
        fun tag(value: String) = com.lmreader.ui.translation.matchEngineLanguage(value, installed)?.tag
            ?: LocalTranslationLanguage.fromTag(value).tag
        val snapshot = translationTaskSnapshot(workflow, options, tag(requireNotNull(options.sourceLanguage)),
            tag(requireNotNull(options.targetLanguage)), resolveTranslationStyle(options, category,
                container.preferences.translationGlobalStyle.first()), render, container.apiProfiles.profiles.first(), allowMissingCapabilities = true)
        val payload = JSONObject().put("mangaId", mangaId).put("chapterId", item.chapterId)
            .put("pageId", item.page.pageId).put("expectedRevision", expectedRevision)
            .put("snapshot", JSONObject(snapshot)).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        container.shelfRepository.ensureOnShelf(mangaId)
        return container.taskScheduler.submit(TYPE, "${item.chapter.title} · ${item.page.displayName}",
            "reader-page:${item.page.pageId}", "reader-page:${item.page.pageId}:$digest", payload,
            priority = ResourceGovernor.INTERACTIVE).taskId
    }
    override suspend fun execute(execution: TaskExecution) {
        val payload = JSONObject(execution.payload)
        val mangaId = payload.getString("mangaId")
        val chapterId = payload.getString("chapterId")
        val pageId = payload.getString("pageId")
        val json = payload.getJSONObject("snapshot")
        val manga = container.mangaRepository.getBackfillTarget(mangaId) ?: throw TaskWaiting("ACCESS", "漫画或来源不存在，请重新定位")
        val chapter = manga.chapters.firstOrNull { it.chapterId == chapterId } ?: throw TaskWaiting("ACCESS", "原章节不存在")
        val opened = container.pageSourceFactory.open(manga.sourceTreeUri, chapter)
        val source = (opened as? PageSourceOpenResult.Ready)?.source ?: throw TaskWaiting("ACCESS", "原章节无法读取，请恢复目录授权")
        val page = source.pages().firstOrNull { it.pageId == pageId } ?: throw TaskWaiting("ACCESS", "原页面已变化，请重新选择")
        val receipt = container.localPageTranslator.artifacts.current(pageId)
        if (receipt?.producerTaskId == execution.taskId) {
            val digest = MessageDigest.getInstance("SHA-256")
            ResourceGovernor.shared.use("io") { source.open(page).use { input ->
                val buffer = ByteArray(65_536)
                while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if(n < 0) break; digest.update(buffer, 0, n) }
            } }
            if(digest.digest().joinToString("") { "%02x".format(it) } == receipt.sourceSha256) {
                execution.progress(1, 1, "已完成", JSONObject().put("publishedRevision", receipt.revision).toString())
                return
            }
            execution.publish { container.localPageTranslator.pageWriteMutex.withLock {
                container.localPageTranslator.artifacts.delete(pageId)
                container.translationQueue.pageUpdates.emit(pageId)
            } }
        }
        val program = WorkflowProgramCodec.decode(json.getJSONObject("program").toString())
        require(program.uses(WorkflowKind.SEG)) { "工作流需要 SEG 生成气泡" }
        val from = LocalTranslationLanguage.fromTag(json.getString("sourceLanguage"))
        val to = LocalTranslationLanguage.fromTag(json.getString("targetLanguage"))
        val render = BubbleRenderSettings(BubbleFillMode.valueOf(json.getString("fillMode")), json.getInt("opacity"), json.getInt("padding"),
            BubbleFont.valueOf(json.getString("font")), json.getInt("fontScale"), json.getBoolean("bold"), json.optInt("freeTextMaskExpansion", 6))
        val settings = WorkflowRunSettings(from, to, json.getString("style"), render, json.getDouble("segThreshold").toFloat(),
            ApiProfileCodec.decode(json.getJSONObject("apiProfiles").toString()), SegTextScope.fromValue(json.optString("segTextScope")),
            json.optDouble("textDetectionThreshold", .35).toFloat(), json.optDouble("freeTextMergeGapRatio", 1.2).toFloat())
        val routes = program.localTranslationRoutes(from, to)
        requireWorkflowCapabilities(container, program, from, to, settings.apiProfiles)
        try { routes.forEach { container.translationModels.installedCatalog().route(it.source, it.target, it.engine) } }
        catch (e: Exception) { throw TaskWaiting("CAPABILITY", "本地翻译模型：${e.message}") }
        execution.progress(0, 1, "正在准备")
        // Preparation belongs to this task too: a chapter-wide mutex would hide
        // interactive priority from the native resource queues.
        val owner = "reader-page:${execution.taskId}"
        val visionSettings = container.visionExecutionPreferences.settings.value
        val vision = LocalVisionEngine(container.application) { visionSettings }
        var host: AndroidWorkflowHost? = null
        try {
            val ocrLanguages = program.allNodes().filter { it.kind == WorkflowKind.OCR }.map { node ->
                val language = when(val binding = node.inputs["language"]) {
                    WorkflowSystem.ref(WorkflowSystem.SOURCE) -> from
                    WorkflowSystem.ref(WorkflowSystem.TARGET) -> to
                    is WorkflowExpression.Text -> LocalTranslationLanguage.fromTag(binding.value)
                    else -> throw TaskWaiting("CAPABILITY", "${node.label}：OCR 语言需要明确绑定")
                }
                LocalPageTranslator.ocrLanguage(language)
            }.toSet()
            container.localTranslator.prepareEngineModels(owner, routes)
            vision.reconfigure(settings.segTextScope != SegTextScope.FREE_TEXT, ocrLanguages, needTextDetection = true)
            vision.setPreprocessingCacheLimit(container.translationCachePreferences.megabytes.value.toLong() * 1_048_576)
            val name = container.mangaRepository.getCards(listOf(mangaId)).firstOrNull()?.displayName ?: mangaId
            val progressScope = CoroutineScope(currentCoroutineContext())
            val progressJobs = java.util.concurrent.ConcurrentLinkedQueue<Job>()
            val pageHost = object : AndroidWorkflowHost(container.applicationContext, container, mangaId, name,
                listOf(WorkflowChapterInput(chapterId, chapter.title, source, listOf(page))), settings,
                TranslationCacheBudget(container.translationCachePreferences.megabytes.value.toLong() * 1_048_576, container.imageCacheBudget),
                reusePages = false, requestOrigin = "单页重译", overwriteExisting = true, vision = vision,
                expectedRevisions = mapOf(pageId to payload.optString("expectedRevision").takeIf { it.isNotBlank() && it != "null" }),
                restartConflictingPages = true,
                commitGate = { action -> execution.publish(action) }) {
                override suspend fun canPublish(frame: WorkflowFrame): Boolean { execution.checkActive(); return true }
                override fun step(node: WorkflowNode, frame: WorkflowFrame) {
                    progressJobs.add(progressScope.launch { execution.progress(0, 1, node.label.ifBlank { WorkflowLabels.kind(node.kind) }) })
                }
                override suspend fun pagePublished(frame: WorkflowFrame, saved: ReaderPageTranslation) {
                    container.translationQueue.includePage(chapterId, pageId)
                    container.translationQueue.pageUpdates.emit(pageId)
                }
            }
            host = pageHost
            // A crash after atomic publication is reconciled before any workflow/API is reissued.
            WorkflowRuntime(json.getInt("parallelLimit"), json.getInt("retries")).execute(program, pageHost)
            progressJobs.toList().joinAll()
            val saved = pageHost.published[pageId] ?: error("工作流没有生成本页译文")
            execution.progress(1, 1, "已完成", JSONObject().put("publishedRevision", saved.revision).toString())
        } finally { withContext(NonCancellable) {
            try { host?.close() } finally { container.localTranslator.unpin(owner); vision.close() }
        } }
    }
    companion object { const val TYPE = "READER_PAGE" }
}
