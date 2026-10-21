package com.lmreader.ui.video

import android.graphics.Bitmap
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.*
import com.lmreader.core.storage.reader.*
import com.lmreader.core.vision.*
import com.lmreader.core.workflow.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.TranslationCacheBudget
import com.lmreader.ui.reader.translation.*
import com.lmreader.ui.workflow.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

interface VideoFrameEngine {
    val identity: String
    suspend fun prepare()
    suspend fun frame(bitmap: Bitmap, positionMs: Long): ReaderPageTranslation
    suspend fun close()
}
class VideoTranslationEngine(private val container: AppContainer, val project: VideoProject,
    val workflow: TranslationWorkflow, private val fixedProfiles: List<ApiProfile>? = null,
    private val analysisIdentity: String = "legacy") : VideoFrameEngine {
    private val owner = "video:${project.id}:${UUID.randomUUID()}"
    private val visionSettings = container.visionExecutionPreferences.settings.value
    val vision = LocalVisionEngine(container.application, "video-analysis-cache/${project.id}") { visionSettings }
    private lateinit var settings: WorkflowRunSettings
    override lateinit var identity: String; private set
    private val budget = TranslationCacheBudget(64L * 1_048_576, container.imageCacheBudget)
    override suspend fun prepare() {
        val p = workflow.program
        val validation = WorkflowValidator.validate(p)
        require(validation.valid) { validation.issues.joinToString("；") { it.message } }
        val from = LocalTranslationLanguage.fromTag(project.source); val to = LocalTranslationLanguage.fromTag(project.target)
        val profiles = fixedProfiles ?: container.apiProfiles.profiles.first()
        // Literal node bindings are retained. A video selection never blindly overrides every API node.
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
        settings = WorkflowRunSettings(from, to, "", container.bubbleRenderPreferences.settings.first(), .35f, profiles)
        identity = workflowResultIdentity(p, settings, container.translationModels.installedPacks.value.values.toList(),
            vision.modelIdentities, visionSettings, "video-surface-v2:$analysisIdentity")
        val routes = p.localTranslationRoutes(from, to)
        container.localTranslator.prepareEngineModels(owner, routes)
        if (p.inputScenario == WorkflowInputScenario.IMAGE) vision.reconfigure(p.uses(WorkflowKind.SEG), languages,
            needTextDetection = p.uses(WorkflowKind.SEG) || languages.isNotEmpty())
        vision.setPreprocessingCacheLimit(32L * 1_048_576)
    }
    override suspend fun frame(bitmap: Bitmap, positionMs: Long): ReaderPageTranslation {
        require(workflow.program.inputScenario == WorkflowInputScenario.IMAGE)
        val id = "video:${project.id}:${project.sha256}:$positionMs:$identity"
        val page = ReaderPage(id, 0, "${positionMs}ms", id)
        val source = object : PageSource {
            override suspend fun pages() = listOf(page)
            override suspend fun probe(page: ReaderPage) = PageGeometry(bitmap.width, bitmap.height)
            override suspend fun open(page: ReaderPage): InputStream = error("视频帧只在内存中可用")
        }
        val hash = bitmapHash(bitmap)
        val host = host(listOf(WorkflowChapterInput("video-frame:${project.id}", "${positionMs}ms", source, listOf(page))),
            mapOf(id to WorkflowPageImage(bitmap, hash)))
        try {
            WorkflowRuntime(workflow.parallelLimit, workflow.retries).execute(workflow.program, host)
            return host.published[id] ?: error("工作流没有生成画面成果")
        } finally { host.close() }
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
