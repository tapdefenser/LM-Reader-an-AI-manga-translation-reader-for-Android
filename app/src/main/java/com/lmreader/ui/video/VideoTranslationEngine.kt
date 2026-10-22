package com.lmreader.ui.video

import android.graphics.Bitmap
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.vision.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.translation.*
import com.lmreader.ui.workflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

interface VideoFrameEngine {
    val identity: String
    suspend fun configure(settings: VideoFrameSettings) {}
    suspend fun prepare()
    suspend fun frame(bitmap: Bitmap, positionMs: Long): ReaderPageTranslation
    fun pageId(positionMs: Long): String = error("This frame engine does not expose offline page identity")
    suspend fun detect(bitmap: Bitmap, positionMs: Long): WorkflowPageSegmentation? = null
    suspend fun frame(bitmap: Bitmap, positionMs: Long, detection: WorkflowPageSegmentation?, retained: Set<String>): ReaderPageTranslation = frame(bitmap, positionMs)
    suspend fun close()
}
class VideoTranslationEngine(private val container: AppContainer, val project: VideoProject,
    val workflow: TranslationWorkflow, private val fixedProfiles: List<ApiProfile>? = null,
    private val analysisIdentity: String = "legacy",
    private val textScope: SegTextScope = SegTextScope.ALL) : VideoFrameEngine {
    private val owner = "video:${project.id}:${UUID.randomUUID()}"
    private val visionSettings = container.visionExecutionPreferences.settings.value
    // Use the same live session-pool settings as manga/screen translation.
    val vision = LocalVisionEngine(container.application, "video-analysis-cache/${project.id}") { container.visionExecutionPreferences.settings.value }
    private lateinit var settings: WorkflowRunSettings
    override lateinit var identity: String; private set
    private val budget = container.imageCacheBudget
    private var frameSettings: VideoFrameSettings? = null
    override suspend fun configure(settings: VideoFrameSettings) { frameSettings = settings }
    override suspend fun prepare() {
        val p = workflow.program
        val validation = WorkflowValidator.validate(p)
        require(validation.valid) { validation.issues.joinToString("；") { it.message } }
        val from = LocalTranslationLanguage.fromTag(project.source); val to = LocalTranslationLanguage.fromTag(project.target)
        val profiles = fixedProfiles ?: container.apiProfiles.profiles.first()
        // The captured program already contains the user's branch-wide API override.
        for (node in p.allNodes()) {
            try {
                if (node.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM)) {
                    val id = (node.inputs["profile"] as? WorkflowExpression.Text)?.value ?: error("请明确选择 API 配置")
                    val profile = profiles.firstOrNull { it.id == id } ?: error("缺少 API 配置，请先复制工作流并绑定 API")
                    require(profile.kind == ApiProfileKind.LLM) { "此节点需要 LLM API 配置" }
                    ApiProtocol.validate(profile)
                }
                if (node.kind == WorkflowKind.TRANSLATE) {
                    val routes = p.copy(rows = listOf(node)).localTranslationRoutes(from, to)
                    require(routes.isNotEmpty()) { "语言方向需明确绑定" }
                    routes.forEach { route -> container.translationModels.installedCatalog().route(route.source, route.target, route.engine) }
                }
            } catch (error: Exception) { throw IllegalStateException("${node.label.ifBlank { WorkflowLabels.kind(node.kind) }}：${error.message}", error) }
        }
        val languages = p.allNodes().filter { it.kind == WorkflowKind.OCR }.map { n ->
            val lang = when (val value = n.inputs["language"]) {
                WorkflowSystem.ref(WorkflowSystem.SOURCE) -> from
                WorkflowSystem.ref(WorkflowSystem.TARGET) -> to
                is WorkflowExpression.Text -> LocalTranslationLanguage.fromTag(value.value)
                else -> error("${n.label.ifBlank { "OCR" }}：语言需明确绑定")
            }
            LocalPageTranslator.ocrLanguage(lang)
        }.toSet()
        val frame = frameSettings ?: VideoFrameSettings(render = container.bubbleRenderPreferences.settings.first(),
            similarityPercent = container.screenTranslation.preferences.settings.value.videoSimilarityPercent)
        settings = WorkflowRunSettings(from, to, "", frame.render, frame.segThreshold, profiles,
            segTextScope = textScope, textDetectionThreshold = frame.textDetectionThreshold,
            freeTextMergeGapRatio = frame.freeTextMergeGapRatio)
        // Legacy queued tasks have no frame-settings snapshot and keep their prior
        // identity. Saving options must not invalidate or replace an existing task.
        val trackingIdentity = "video-surface-v3:$analysisIdentity" +
            frameSettings?.let { ":similarity=${it.similarityPercent}" }.orEmpty()
        identity = workflowResultIdentity(p, settings, container.translationModels.installedPacks.value.values.toList(),
            vision.modelIdentities, visionSettings, trackingIdentity)
        val routes = p.localTranslationRoutes(from, to)
        container.localTranslator.prepareEngineModels(owner, routes)
        if (p.inputScenario == WorkflowInputScenario.IMAGE) vision.reconfigure(p.uses(WorkflowKind.SEG), languages,
            needTextDetection = p.uses(WorkflowKind.SEG) || languages.isNotEmpty())
        vision.setPreprocessingCacheLimit(container.translationCachePreferences.megabytes.value.toLong() * 1_048_576)
    }
    override fun pageId(positionMs: Long) = "video:${project.id}:${project.sha256}:$positionMs:$identity"
    override suspend fun detect(bitmap: Bitmap, positionMs: Long): WorkflowPageSegmentation? {
        if(!workflow.program.uses(WorkflowKind.SEG)) return null
        val id = pageId(positionMs)
        val result = vision.segment(id, bitmap, settings.segThreshold, settings.textDetectionThreshold,
            sourceSha256 = bitmapHash(bitmap), scope = settings.segTextScope) {}
        return WorkflowPageSegmentation(result, selectSegRegions(result, settings.segTextScope, settings.freeTextMergeGapRatio))
    }
    override suspend fun frame(bitmap: Bitmap, positionMs: Long) = frame(bitmap, positionMs, null, emptySet())
    override suspend fun frame(bitmap: Bitmap, positionMs: Long, detection: WorkflowPageSegmentation?, retained: Set<String>): ReaderPageTranslation {
        require(workflow.program.inputScenario == WorkflowInputScenario.IMAGE)
        val id = pageId(positionMs)
        if(detection != null && detection.regions.none { it.id !in retained }) {
            return ReaderPageTranslation(id, UUID.randomUUID().toString(), java.io.File(container.applicationContext.cacheDir, "video-empty"),
                settings.source, settings.target, bitmapHash(bitmap), bitmap.width, bitmap.height, emptyList(), emptyList(), 0,
                settings.render, resultIdentity = identity)
        }
        val page = ReaderPage(id, 0, "${positionMs}ms", id)
        val source = object : PageSource {
            override suspend fun pages() = listOf(page)
            override suspend fun probe(page: ReaderPage) = PageGeometry(bitmap.width, bitmap.height)
            override suspend fun open(page: ReaderPage): InputStream = error("视频帧只在内存中可用")
        }
        // Whole-page API/OCR nodes must not receive retained source text either.
        val input = currentCoroutineContext()[VideoFrameInput]
        val inputBytes = (input?.sourceBytes ?: 0L) +
            (if (retained.isNotEmpty()) bitmap.allocationByteCount.toLong() else 0L)
        input?.lease?.resizeForPage(inputBytes + videoSegmentationBytes(detection))
        val working = if(retained.isNotEmpty()) bitmap.copy(Bitmap.Config.ARGB_8888, true) else bitmap
        try {
        if(working !== bitmap && detection != null) {
            val remaining = detection.regions.filterNot { it.id in retained }
            val excluded = detection.regions.filter { it.id in retained }.flatMap {
                screenExposedRegions(it.bounds, remaining.map { region -> region.bounds })
            }
            val bounds = PixelRect(0f, 0f, working.width.toFloat(), working.height.toFloat())
            com.lmreader.screen.maskedScreenCrop(working, bounds, screenExposedRegions(bounds, excluded), copy = false)
        }
        val hash = bitmapHash(working)
        val host = object : AndroidWorkflowHost(container.application, container, owner, project.name,
            listOf(WorkflowChapterInput("video-frame:${project.id}", "${positionMs}ms", source, listOf(page))), settings, budget,
            reusePages = false, requestOrigin = "本地视频画面", vision = vision, ephemeral = true,
            pageImages = mapOf(id to WorkflowPageImage(working, hash, input?.lease,
                if (input == null) working.allocationByteCount.toLong() else inputBytes,
                videoSegmentationBytes(detection)))) {
            override suspend fun segmentPage(pageId: String, bitmap: Bitmap, sha256: String, frame: WorkflowFrame): WorkflowPageSegmentation =
                detection?.copy(regions = detection.regions.filterNot { it.id in retained }, sha256 = hash)
                    ?: super.segmentPage(pageId, bitmap, sha256, frame)
        }
        try {
            WorkflowRuntime(workflow.parallelLimit, workflow.retries).execute(workflow.program, host)
            return host.published[id] ?: error("工作流没有生成画面成果")
        } finally { host.close() }
        } finally { if(working !== bitmap) working.recycle() }
    }
    suspend fun subtitles(cues: List<VideoSubtitleCue>, resources: VideoSentenceResources? = null,
        progress: suspend (VideoSubtitleCue, Int, Int) -> Unit): List<VideoSubtitleCue> {
        require(workflow.program.inputScenario == WorkflowInputScenario.TIMED_TEXT)
        // One stable entry at a time gives durable progress and cancellation without relying on append order.
        val translated = ArrayList<VideoSubtitleCue>()
        for ((index, cue) in cues.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (cue.manuallyEdited || cue.translation.isNotBlank()) { translated += cue; progress(cue, index + 1, cues.size); continue }
            val host = object : AndroidWorkflowHost(container.application, container, owner, project.name, emptyList(),
                settings, budget, reusePages = false, requestOrigin = "视频字幕", vision = vision, ephemeral = true) {
                override suspend fun textEntries() = listOf(cueRecord(cue))
                override suspend fun currentAudio() = WorkflowValue.Audio("sentence-audio:${cue.id}")
                override suspend fun currentImage() = WorkflowValue.Image("sentence-image:${cue.id}")
                override suspend fun encodeAudio(audio: WorkflowValue.Audio): com.lmreader.core.api.ApiAudio {
                    require(audio.key == "sentence-audio:${cue.id}") { "录音不属于当前句子" }
                    return requireNotNull(resources) { "该字幕没有可读取的原始录音" }.audio(cue)
                }
                override suspend fun encodeImage(image: WorkflowValue.Image): com.lmreader.core.api.ApiImage {
                    if(image.key != "sentence-image:${cue.id}") return super.encodeImage(image)
                    return requireNotNull(resources) { "该字幕没有可读取的原视频" }.image(cue)
                }
            }
            try {
                val result = withContext(TaskPublicationScope("cue:${cue.id}")) { WorkflowRuntime(workflow.parallelLimit, workflow.retries).execute(workflow.program, host) } as? WorkflowValue.ListValue
                    ?: error("字幕流程未返回列表")
                val record = result.items.singleOrNull() as? WorkflowValue.Record ?: error("字幕流程必须保留条目数量")
                val expected = cueRecord(cue)
                require(expected.fields.filterKeys { it != "translation" }.all { (key, value) -> record.fields[key] == value }) { "字幕流程改变了条目身份、时间或原文" }
                val text = (record.fields["translation"] as? WorkflowValue.Text)?.value ?: error("字幕译文类型不正确")
                val changed = cue.copy(translation = text)
                translated += changed
                progress(changed, index + 1, cues.size)
            } finally { host.close() }
        }
        return translated
    }
    private fun host(chapters: List<WorkflowChapterInput>, images: Map<String, WorkflowPageImage>) =
        AndroidWorkflowHost(container.application, container, "video:${project.id}", project.name, chapters, settings, budget,
            reusePages = false, requestOrigin = "本地视频画面", vision = vision, ephemeral = true, pageImages = images)
    override suspend fun close() = withContext(NonCancellable + Dispatchers.IO) { container.localTranslator.unpin(owner); vision.close() }
    companion object {
        fun cueRecord(c: VideoSubtitleCue) = WorkflowValue.Record(mapOf("id" to WorkflowValue.Text(c.id), "source" to WorkflowValue.Text(c.source),
            "translation" to WorkflowValue.Text(c.translation), "startMs" to WorkflowValue.Number(c.startMs.toDouble()), "endMs" to WorkflowValue.Number(c.endMs.toDouble())))
        fun bitmapHash(bitmap: Bitmap): String {
            val digest = MessageDigest.getInstance("SHA-256"); val row = IntArray(bitmap.width)
            val bytes = java.nio.ByteBuffer.allocate(bitmap.width * 4)
            for (y in 0 until bitmap.height) { bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1); bytes.clear(); row.forEach(bytes::putInt); digest.update(bytes.array()) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
