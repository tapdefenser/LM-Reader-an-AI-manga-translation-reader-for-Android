package com.lmreader.ui.video

import android.net.Uri
import androidx.room.withTransaction
import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.database.media.*
import com.lmreader.core.database.tasks.TaskRecord
import com.lmreader.core.model.*
import com.lmreader.core.workflow.WorkflowProgramCodec
import com.lmreader.core.workflow.WorkflowResultIdentity
import com.lmreader.di.AppContainer
import com.lmreader.tasks.*
import com.lmreader.ui.workflow.withApiOverride
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class VideoWorkOptions(val source: String = "ja", val target: String = "zh-Hans", val mode: String = "BOTH",
    val frameWorkflowId: String = TranslationWorkflow.LOCAL_MACHINE_ID, val subtitleWorkflowId: String = "builtin.subtitle-local",
    val fpsNumerator: Int = 1, val fpsDenominator: Int = 1, val startMs: Long = 0, val endMs: Long = 0,
    val subtitleSource: String = "ASR_LOCAL", val audioTrack: Int = -1, val subtitleTrack: Int = -1,
    val asrModelId: String? = null, val audioApiProfileId: String? = null, val audioApiEndpoint: String = "",
    val maxDimension: Int = 1280, val frameApiProfileId: String? = null, val subtitleApiProfileId: String? = null,
    val segTextScope: SegTextScope = SegTextScope.ALL, val frameSettings: VideoFrameSettings? = null) {
    fun json() = JSONObject().put("source", source).put("target", target).put("mode", mode)
        .put("frameWorkflowId", frameWorkflowId).put("subtitleWorkflowId", subtitleWorkflowId)
        .put("fpsNumerator", fpsNumerator).put("fpsDenominator", fpsDenominator).put("startMs", startMs).put("endMs", endMs)
        .put("subtitleSource", subtitleSource).put("audioTrack", audioTrack).put("subtitleTrack", subtitleTrack)
        .put("asrModelId", asrModelId).put("audioApiProfileId", audioApiProfileId).put("audioApiEndpoint", audioApiEndpoint).put("maxDimension", maxDimension)
        .put("frameApiProfileId", frameApiProfileId).put("subtitleApiProfileId", subtitleApiProfileId).put("segTextScope", segTextScope.name)
        .put("frameSettings", frameSettings?.json())
    companion object { fun read(j: JSONObject) = VideoWorkOptions(j.optString("source", "ja"), j.optString("target", "zh-Hans"), j.optString("mode", "BOTH"),
        j.optString("frameWorkflowId", TranslationWorkflow.LOCAL_MACHINE_ID), j.optString("subtitleWorkflowId", "builtin.subtitle-local"),
        j.optInt("fpsNumerator", 1), j.optInt("fpsDenominator", 1), j.optLong("startMs"), j.optLong("endMs"),
        j.optString("subtitleSource", "ASR_LOCAL"), j.optInt("audioTrack", -1), j.optInt("subtitleTrack", -1),
        j.optString("asrModelId").takeUnless { it.isBlank() || it == "null" }, j.optString("audioApiProfileId").takeUnless { it.isBlank() || it == "null" },
        j.optString("audioApiEndpoint"), j.optInt("maxDimension", 1280),
        j.optString("frameApiProfileId").takeUnless { it.isBlank() || it == "null" },
        j.optString("subtitleApiProfileId").takeUnless { it.isBlank() || it == "null" },
        SegTextScope.fromValue(j.optString("segTextScope").takeIf { it.isNotBlank() }),
        j.optJSONObject("frameSettings")?.let(VideoFrameSettings::read)) }
}

/** Application-owned offline executors. No player or ViewModel owns this work. */
class VideoTasks internal constructor(private val container: AppContainer,
    private val prepareAudio: suspend (TaskExecution, VideoProject, VideoWorkOptions, VideoSourceUpdates) -> Unit = { e, p, o, updates ->
        container.videoAudio.prepareSource(e, p, o, updates)
    },
    private val createFrameEngine: (VideoProject, TranslationWorkflow, List<ApiProfile>, String, SegTextScope) -> VideoFrameEngine = { p, w, profiles, identity, scope ->
        VideoTranslationEngine(container, p, w, profiles, identity, scope)
    }, private val createFrameSource: (VideoProject, VideoWorkOptions) -> VideoFrameSource = { project, options ->
        VideoFrameDecoder(container.applicationContext, project.uri, options.maxDimension)
    }) {
    private val db get() = container.workDatabase
    private val dao get() = db.results()
    private val sourceMutex = Mutex()
    private val mutableVerified = MutableStateFlow<Map<String, String>>(emptyMap())
    val verifiedSources = mutableVerified.asStateFlow()
    private val writes = ConcurrentHashMap<String, Mutex>()
    suspend fun <T> snapshot(id: String, block: suspend () -> T): T = writes.computeIfAbsent(id) { Mutex() }.withLock { block() }
    init {
        container.taskScheduler.register("VIDEO_FRAMES") { frames(it) }
        container.taskScheduler.register("VIDEO_SUBTITLES") { subtitles(it) }
        container.taskScheduler.register("VIDEO_VERIFY") { execution ->
            execution.progress(0, 0, "正在校验原视频，完成后显示已保存成果")
            checkedProject(JSONObject(execution.payload))
            execution.progress(1, 1, "原视频内容已核对")
        }
    }
    suspend fun options(id: String): VideoWorkOptions = (dao.settings(id)?.let { VideoWorkOptions.read(JSONObject(it.json)) }
        ?: container.videoLibrary.projects.value.firstOrNull { it.id == id }?.let { VideoWorkOptions(it.source, it.target, frameWorkflowId = it.workflowId, subtitleWorkflowId = it.subtitleWorkflowId) }
        ?: VideoWorkOptions()).let { it.copy(frameSettings = it.frameSettings ?: defaultFrameSettings()) }
    private suspend fun defaultFrameSettings() = VideoFrameSettings(
        similarityPercent = container.screenTranslation.preferences.settings.value.videoSimilarityPercent,
        render = container.bubbleRenderPreferences.settings.first())
    /** Saving a draft never changes task snapshots, queue state, shelf membership or result identity. */
    suspend fun saveOptions(id: String, options: VideoWorkOptions,
        maskTextColor: BubbleTextColorMode? = null) = snapshot(id) {
        require(options.mode in setOf("FRAME", "SUBTITLE", "BOTH"))
        require(options.maxDimension in 64..2048)
        VideoSamplingRate(options.fpsNumerator, options.fpsDenominator)
        val video = container.videoCatalog.find(id) ?: error("视频不存在")
        val end = options.endMs.takeIf { it > 0 } ?: video.durationMs
        require(options.startMs >= 0 && end > options.startMs && (video.durationMs <= 0 || end <= video.durationMs)) { "请选择有效的处理时间范围" }
        val saved = options.copy(frameSettings = options.frameSettings ?: defaultFrameSettings()).json()
        db.withTransaction {
            val old = dao.settings(id) ?: VideoSettingsRecord(id)
            val merged = JSONObject(old.json)
            saved.keys().forEach { key -> merged.put(key, saved.get(key)) }
            dao.putSettings(old.copy(json = merged.toString(),
                style = maskTextColor?.let { withVideoMaskTextColor(old.style, it) } ?: old.style,
                revision = UUID.randomUUID().toString()))
        }
        Unit
    }
    suspend fun existingProject(id: String): VideoProject? = container.videoLibrary.projects.value.firstOrNull { it.id == id }?.also { migrate(it) }
    suspend fun verifyForPlayback(id: String) {
        val video = container.videoCatalog.find(id) ?: return
        if(mutableVerified.value[id] == video.sourceRevision) return
        val epoch = JSONObject(dao.settings(id)?.json ?: "{}").optString("resultEpoch")
        container.taskScheduler.submit("VIDEO_VERIFY", "核对视频成果来源 · ${video.name}", "video-verify:$id", "video-verify:$id:${video.sourceRevision}:$epoch",
            JSONObject().put("videoId", id).put("uri", video.uri).put("sourceRevision", video.sourceRevision)
                .put("resultEpoch", epoch).toString(), priority = ResourceGovernor.INTERACTIVE)
    }
    suspend fun inspectSource(uri: Uri): VideoProject = ResourceGovernor.shared.use("io") {
        VideoMediaSource.inspect(container.applicationContext, uri, persist = true)
    }
    suspend fun bindSource(id: String, candidate: VideoProject, allowNewVersion: Boolean) = withEditBarrier(id) {
        sourceMutex.withLock {
            val old = existingProject(id)
            check(old == null || old.sha256 == candidate.sha256 || allowNewVersion) { "所选文件内容与原视频不同，请明确建立新源版本" }
            val historical = container.videoLibrary.sourceHeader(id, candidate.sha256)
            val next = (historical ?: candidate.copy(id = id, source = old?.source ?: candidate.source,
                target = old?.target ?: candidate.target)).copy(uri = candidate.uri, name = candidate.name)
            try {
                container.videoLibrary.replaceSource(next)
                container.videoCatalog.bindVerified(id, next) {
                    val row = dao.settings(id) ?: VideoSettingsRecord(id)
                    dao.putSettings(row.copy(json = JSONObject(row.json).put("resultEpoch", UUID.randomUUID().toString()).toString(), migrated = true,
                        revision = UUID.randomUUID().toString()))
                }
                container.videoCatalog.find(id)?.let { mutableVerified.value = mutableVerified.value + (id to it.sourceRevision) }
            } catch(error: Throwable) { if(old != null) container.videoLibrary.replaceSource(old); throw error }
        }
    }
    /** Configuration + shelf + unique intent are committed in the same Room transaction. */
    suspend fun submit(id: String, options: VideoWorkOptions, frameAtUs: Long? = null): String {
        val video = container.videoCatalog.completeMetadata(id) ?: error("视频不存在")
        val speech = options.mode != "FRAME" && frameAtUs == null
        val tracks = if(speech) container.videoAudio.tracks(video.uri) else emptyList()
        // Recognition is a fixed local stage. Only the ASR settings page selects its model.
        // Keep the captured model stable for an in-flight task; new submissions follow Settings.
        val resolved = options.copy(subtitleSource = "ASR_LOCAL", audioApiProfileId = null, audioApiEndpoint = "",
            audioTrack = if(speech && options.audioTrack < 0) tracks.firstOrNull { it.type == "AUDIO" }?.index ?: -1 else options.audioTrack,
            subtitleTrack = -1, asrModelId = if(speech) container.asrModels.selectedId.value else null,
            frameSettings = options.frameSettings ?: defaultFrameSettings())
        return submitResolved(id, resolved, frameAtUs)
    }
    suspend fun replace(id: String, options: VideoWorkOptions): String {
        container.taskScheduler.ready.await()
        container.taskScheduler.dao.all().filter { it.ownerKey.startsWith("video:$id") && it.state !in TaskScheduler.TERMINAL }
            .forEach { container.taskScheduler.pause(it.taskId, cancel = true) }
        return submit(id, options)
    }
    private suspend fun submitResolved(id: String, options: VideoWorkOptions, frameAtUs: Long?): String {
        container.taskScheduler.ready.await(); container.taskService.allowRetry()
        val video = container.videoCatalog.find(id) ?: error("视频不存在")
        require(options.mode in setOf("FRAME", "SUBTITLE", "BOTH"))
        val end = options.endMs.takeIf { it > 0 } ?: video.durationMs
        require(frameAtUs == null || frameAtUs in 0 until video.durationMs * 1000) { "本画面时间超出视频范围，请重新定位" }
        require(options.startMs >= 0 && end > options.startMs && (video.durationMs <= 0 || end <= video.durationMs)) { "请选择有效的处理时间范围" }
        val snapshots = JSONObject()
        if(options.mode != "SUBTITLE" || frameAtUs != null) snapshots.put("frameWorkflow", workflowSnapshot(options.frameWorkflowId, options.frameApiProfileId))
        if(options.mode != "FRAME" && frameAtUs == null) snapshots.put("subtitleWorkflow", workflowSnapshot(options.subtitleWorkflowId, options.subtitleApiProfileId))
        val profiles = container.apiProfiles.profiles.first()
        val resultEpoch = JSONObject(dao.settings(id)?.json ?: "{}").optString("resultEpoch")
        val payload = snapshots.put("videoId", id).put("uri", video.uri).put("sourceRevision", video.sourceRevision)
            .put("resultEpoch", resultEpoch).put("options", options.copy(endMs = end).json()).put("apiProfiles", JSONObject(ApiProfileCodec.encode(profiles.map { it.copy(apiKey = "") })))
        frameAtUs?.let { require(it >= 0); payload.put("frameAtUs", it) }
        // API choices are branch bindings, not base semantics; each overridden program carries its own node config.
        val semantics = JSONObject(options.copy(endMs = end).json().toString()).apply {
            remove("frameWorkflowId"); remove("subtitleWorkflowId")
            remove("frameApiProfileId"); remove("subtitleApiProfileId")
        }
        for(name in listOf("frameWorkflow", "subtitleWorkflow")) snapshots.optJSONObject(name)?.let { w ->
            val program = WorkflowProgramCodec.decode(w.getJSONObject("program").toString())
            val usedIds = program.allNodes().filter { it.kind in setOf(WorkflowKind.API, WorkflowKind.API_STREAM) }
                .mapNotNull { (it.inputs["profile"] as? WorkflowExpression.Text)?.value }.toSet()
            val apiConfig = profiles.filter { it.id in usedIds }.associate { p -> p.id to JSONObject(ApiProfileCodec.encode(listOf(p.copy(apiKey = ""))))
                .getJSONArray("profiles").getJSONObject(0).apply { listOf("name", "parallelLimit", "retryCount", "timeoutSeconds").forEach(::remove) }.toString() }
            semantics.put(name, WorkflowResultIdentity.compute(program, options.source, options.target, "", apiConfig))
        }
        if(options.mode != "FRAME" && frameAtUs == null && options.subtitleSource == "ASR_API") {
            profiles.firstOrNull { it.id == options.audioApiProfileId }?.let { profile ->
                val config = JSONObject(ApiProfileCodec.encode(listOf(profile.copy(apiKey = "")))).getJSONArray("profiles").getJSONObject(0)
                listOf("name", "parallelLimit", "retryCount", "timeoutSeconds").forEach(config::remove)
                semantics.put("audioApi", config)
            }
        }
        val digest = hash("$id:${video.sourceRevision}:$resultEpoch:${frameAtUs ?: -1}:${semantics}")
        val key = "video:$id:$digest"
        payload.put("_goalKey", key)
        return db.withTransaction {
            db.videoCatalogDao().addToShelf(id)
            val oldSettings = dao.settings(id) ?: VideoSettingsRecord(id)
            dao.putSettings(oldSettings.copy(json = options.json().put("resultEpoch", resultEpoch).toString(), revision = UUID.randomUUID().toString()))
            if(frameAtUs != null) container.taskScheduler.submit("VIDEO_FRAMES", "翻译本画面 · ${video.name}", "video:$id:frame:$frameAtUs", key,
                payload.toString(), priority = ResourceGovernor.INTERACTIVE).taskId
            else {
                val parent = db.tasks().submit(TaskRecord(UUID.randomUUID().toString(), "VIDEO", video.name, "video:$id", key, payload.toString(), orderIndex = System.currentTimeMillis()))
                if(options.mode != "SUBTITLE") container.taskScheduler.submit("VIDEO_FRAMES", "${video.name} · 画面", "video:$id:frames", "$key:frames", payload.toString(), parentTaskId = parent.taskId)
                if(options.mode != "FRAME") container.taskScheduler.submit("VIDEO_SUBTITLES", "${video.name} · 字幕", "video:$id:subtitles", "$key:subtitles", payload.toString(), parentTaskId = parent.taskId)
                parent.taskId
            }
        }.also { container.taskScheduler.poke() }
    }
    suspend fun toggle(id: String): String? {
        val running = container.taskScheduler.dao.all().firstOrNull { it.ownerKey == "video:$id" && it.state !in TaskScheduler.TERMINAL }
        if(running != null) {
            if(running.state in setOf("RUNNING", "QUEUED")) container.taskScheduler.pause(running.taskId)
            else container.taskScheduler.resume(running.taskId)
            return running.taskId
        }
        return submit(id, options(id))
    }
    suspend fun <T> withEditBarrier(videoId: String, block: suspend () -> T): T {
        container.taskScheduler.ready.await()
        val self = currentCoroutineContext()[TaskContext]?.taskId
        val writers = container.taskScheduler.dao.all().filter { it.taskId != self && it.ownerKey.startsWith("video:$videoId") && it.state !in TaskScheduler.TERMINAL }
        // Preserve the explicit pause intent. Continuing after an edit requires a user command.
        writers.forEach { container.taskScheduler.pause(it.taskId) }
        return snapshot(videoId, block)
    }
    suspend fun clear(id: String, frames: Boolean, subtitles: Boolean, startMs: Long, endMs: Long) = withEditBarrier(id) {
        require(startMs >= 0 && endMs > startMs)
        val project = ensureProject(id)
        db.withTransaction {
        if(frames) {
            dao.clearFrameIndex(id, startMs * 1000, endMs * 1000)
            dao.regions(id, project.sha256).filter { !it.deleted && it.startMs < endMs && it.endMs > startMs }.forEach { row ->
                for(part in partitionVideoTime(row.startMs, row.endMs, startMs, endMs)) {
                    val next = row.copy(intervalId = if(part.cleared) row.intervalId else stableId("${row.intervalId}:${part.startMs}:${part.endMs}"),
                        startMs = part.startMs, endMs = part.endMs, deleted = part.cleared, manualTiming = true,
                        revision = if(part.cleared) row.revision else UUID.randomUUID().toString())
                    if(part.cleared) dao.saveRegion(next, row.revision) else check(dao.addRegion(next) != -1L)
                }
            }
        }
        if(subtitles) dao.cues(id).filter { !it.deleted && it.startMs < endMs && it.endMs > startMs }.forEach { row ->
            for(part in partitionVideoTime(row.startMs, row.endMs, startMs, endMs)) {
                val cue = row.copy(cueId = if(part.cleared) row.cueId else stableId("${row.cueId}:${part.startMs}:${part.endMs}"),
                    startMs = part.startMs, endMs = part.endMs, translation = if(part.cleared) "" else row.translation,
                    manuallyEdited = if(part.cleared) false else row.manuallyEdited, identity = if(part.cleared) null else row.identity)
                dao.saveCue(cue, if(part.cleared) row.revision else null)
            }
        }
        val settings = dao.settings(id) ?: VideoSettingsRecord(id)
        dao.putSettings(settings.copy(json = JSONObject(settings.json).put("resultEpoch", UUID.randomUUID().toString()).toString(), revision = UUID.randomUUID().toString()))
        }
    }
    suspend fun ensureProject(id: String): VideoProject = sourceMutex.withLock {
        val video = container.videoCatalog.find(id) ?: error("视频已移除")
        if(!video.accessible) throw TaskWaiting("ACCESS", video.accessReason ?: "原视频暂不可访问")
        val old = container.videoLibrary.projects.value.firstOrNull { it.id == id }
        val project = if(old != null && video.sourceHash == old.sha256 && old.uri == video.uri && mutableVerified.value[id] == video.sourceRevision) old else {
            val task = currentCoroutineContext()[TaskContext]
            val inspected = try {
                ResourceGovernor.shared.use("io") { VideoMediaSource.inspect(container.applicationContext, Uri.parse(video.uri), persist = false,
                    onProgress = { task?.onWaiting?.invoke("VERIFY:$it/${video.bytes}") }) }
            } finally { task?.onWaiting?.invoke(null) }
            if(old != null) {
                if(old.sha256 != inspected.sha256) throw TaskWaiting("SOURCE_CHANGED", "原文件内容已替换，旧成果已保留；请重新定位原文件或明确建立新源版本")
                container.videoLibrary.change(id) { it.copy(uri = video.uri, name = video.name) }
            } else container.videoLibrary.addOwned(inspected.copy(id = id, positionMs = video.positionMs))
        }
        check(db.videoCatalogDao().verifiedSource(id, video.sourceRevision, project.sha256) == 1) { "源文件在校验时发生变化，请重试" }
        mutableVerified.value = mutableVerified.value + (id to video.sourceRevision)
        migrate(project)
        project
    }
    private suspend fun migrate(p: VideoProject) {
        val settings = dao.settings(p.id) ?: VideoSettingsRecord(p.id)
        if(settings.migrated) return
        db.withTransaction {
            for(frame in p.frames) {
                val saved = container.videoLibrary.artifacts(p.id).current(frame.pageId) ?: continue
                dao.putFrame(VideoFrameIndex(p.id, p.sha256, frame.identity, frame.atMs, frame.atMs * 1000, frame.atMs * 1000,
                    frame.untilMs * 1000, frame.pageId, saved.revision, saved.regions.isEmpty(), saved.producerTaskId ?: "legacy"))
                saved.regions.forEach { region -> dao.addRegion(VideoRegionInterval(stableId("${p.id}:${frame.pageId}:${region.region.id}"),
                    p.id, p.sha256, frame.pageId, region.region.id, frame.atMs, frame.untilMs, revision = UUID.randomUUID().toString())) }
            }
            p.cues.forEach { cue -> dao.addCue(VideoCueRecord(p.id, cue.id, p.sha256, "external", cue.startMs, cue.endMs,
                cue.source, cue.translation, manuallyEdited = cue.manuallyEdited, revision = UUID.randomUUID().toString(), identity = p.subtitleIdentity)) }
            dao.putSettings(settings.copy(migrated = true))
        }
    }
    private suspend fun checkedProject(payload: JSONObject): VideoProject {
        validateSource(payload)
        return ensureProject(payload.getString("videoId"))
    }
    internal suspend fun validateSource(payload: JSONObject) {
        val video = container.videoCatalog.find(payload.getString("videoId")) ?: throw TaskWaiting("ACCESS", "视频不存在")
        if(!video.accessible) throw TaskWaiting("ACCESS", video.accessReason ?: "原视频暂不可访问")
        if(video.sourceRevision != payload.getString("sourceRevision") || video.uri != payload.getString("uri"))
            throw TaskWaiting("SOURCE_CHANGED", "视频引用或源版本已变化，请检查原文件并更新任务")
        if(JSONObject(dao.settings(video.videoId)?.json ?: "{}").optString("resultEpoch") != payload.optString("resultEpoch"))
            throw TaskWaiting("CONFIG_CHANGED", "成果已清理或恢复，请更新选项并重新排队")
    }
    internal suspend fun frames(execution: TaskExecution) = try { coroutineScope {
        val payload = JSONObject(execution.payload); val options = VideoWorkOptions.read(payload.getJSONObject("options"))
        val project = checkedProject(payload).copy(source = options.source, target = options.target)
        val engine = createFrameEngine(project, readWorkflow(payload.getJSONObject("frameWorkflow")),
            ApiProfileCodec.decode(payload.getJSONObject("apiProfiles").toString()),
            "${options.maxDimension}:${options.fpsNumerator}/${options.fpsDenominator}:${options.startMs}:${options.endMs}:${payload.optString("resultEpoch")}", options.segTextScope)
        val decoder = createFrameSource(project, options)
        val activeInputs = ConcurrentHashMap.newKeySet<VideoFrameInput>()
        try {
            options.frameSettings?.let { engine.configure(it) }
            try { engine.prepare() } catch(e: CancellationException) { throw e }
            catch(e: TaskWaiting) { throw e }
            catch(e: Exception) { throw TaskWaiting("CAPABILITY", e.message ?: "画面工作流尚未就绪") }
            val checkpoint = JSONObject(execution.initial.checkpoint)
            val previousIdentity = checkpoint.optString("identity")
            if(previousIdentity.isNotBlank() && previousIdentity != engine.identity)
                throw TaskWaiting("CONFIG_CHANGED", "模型或工作流语义已变化，已保留成果；请取消旧任务后从翻译入口重新开始")
            execution.publish { snapshot(project.id) {
                val row = dao.settings(project.id) ?: VideoSettingsRecord(project.id)
                dao.putSettings(row.copy(json = JSONObject(row.json).put("frameIdentity", engine.identity).toString()))
            } }
            val single = payload.has("frameAtUs")
            val plan = VideoSamplingPlan(project.durationMs, VideoSamplingRate(options.fpsNumerator, options.fpsDenominator),
                options.startMs, options.endMs.coerceAtMost(project.durationMs))
            val total = if(single) 1 else plan.sampleCount
            val similarity = (options.frameSettings ?: defaultFrameSettings()).similarityPercent
            var completed = execution.initial.completed
            val scaled = minOf(1.0, options.maxDimension.toDouble() / maxOf(project.width, project.height).coerceAtLeast(1))
            val inputReservation = (project.width.toDouble() * project.height * scaled * scaled * 12)
                .toLong().coerceAtLeast(1)
            data class UnitFrame(val index: Long, val targetUs: Long, val frame: DecodedVideoFrame?,
                val untilUs: Long = 0, val observations: List<VideoRegionObservation> = emptyList(),
                val trackByDetection: Map<String, String> = emptyMap(),
                val result: Deferred<Result<Unit>>? = null,
                val detected: Boolean = false, val input: VideoFrameInput? = null,
                val ptsUs: Long = frame?.ptsUs ?: -1L)
            suspend fun publishCaptured(work: UnitFrame, saved: com.lmreader.ui.reader.translation.ReaderPageTranslation) {
                val frame = requireNotNull(work.frame)
                val publicationContext = currentCoroutineContext()
            execution.publish { snapshot(project.id) {
                validateSource(payload)
                val artifacts = container.videoLibrary.artifacts(project.id)
                val stored = artifacts.current(saved.pageId)
                val published = stored?.takeIf { it.resultIdentity == engine.identity && (it.regions.isNotEmpty() || saved.regions.isEmpty()) } ?: artifacts.save(saved.pageId,
                    saved.sourceSha256, saved.source, saved.target, saved.width, saved.height, saved.regions, saved.modelPacks,
                    saved.elapsedMillis, saved.renderSettings, expectedRevision = stored?.revision, requireAbsent = stored == null,
                    resultIdentity = engine.identity, producerTaskId = execution.taskId,
                    checkCancelled = { publicationContext.ensureActive() })
                VideoFrameProof.capture(frame.bitmap, published).save(container.videoLibrary.proofs(project.id), published.pageId)
                db.withTransaction {
                    dao.putFrame(VideoFrameIndex(project.id, project.sha256, engine.identity, if(single) -work.targetUs - 1 else work.index,
                        work.targetUs, frame.ptsUs, work.untilUs, published.pageId, published.revision,
                        if(work.detected) work.observations.isEmpty() else published.regions.isEmpty(), execution.taskId))
                    published.regions.forEach { region ->
                        val detectedId = region.region.id.substringBeforeLast(":seg:")
                        val track = work.trackByDetection[detectedId]
                            ?: work.observations.firstOrNull { it.kind == region.region.kind.name && it.bounds() == region.region.bounds }?.trackId
                            ?: stableId("${project.id}:${published.pageId}:${region.region.id}")
                        val latest = dao.lastTrackObservation(project.id, project.sha256, engine.identity, track)
                        val end = ((latest?.untilUs ?: work.untilUs) / 1000).coerceAtLeast(frame.ptsUs / 1000 + 1)
                        val old = dao.region(track)
                        if(old == null) dao.addRegion(VideoRegionInterval(track, project.id, project.sha256, published.pageId,
                            region.region.id, frame.ptsUs / 1000, end, revision = UUID.randomUUID().toString(), trackId = track))
                        else if(!old.manualTiming && !old.deleted && end > old.endMs)
                            dao.saveRegion(old.copy(endMs = end, observedEndMs = end), old.revision)
                    }
                    execution.commitScope(published.pageId)
                }
            } }
            }
            // Pixel ownership is admitted before decode through the same byte budget
            // as manga preprocessing. Bound the lightweight ordered receipts as well.
            val window = Channel<UnitFrame>(64, onUndeliveredElement = { it.result?.cancel() })
            val producer = launch {
                val committedFrame = if(single) null else dao.latestFrame(project.id, project.sha256, engine.identity, checkpoint.optLong("committed", -1))
                var previous = committedFrame?.let { dao.sampleObservations(project.id, project.sha256, engine.identity, it.sampleIndex) }.orEmpty()
                var previousPts = committedFrame?.ptsUs ?: -1L
                var previousResult: Deferred<Result<Unit>>? = null
                try {
                    var index: Long? = if(single) 0 else plan.nextIndexAfter(checkpoint.optLong("committed", -1).takeIf { it >= 0 })
                    while(index != null) {
                        val current = index
                        val target = if(single) payload.getLong("frameAtUs") else plan.targetUs(current)
                        val sample = if(single) -target - 1 else current
                        var frame: DecodedVideoFrame? = null
                        var input: VideoFrameInput? = null
                        var lease: com.lmreader.ui.queue.TranslationCacheBudget.Lease? = null
                        var future: Deferred<Result<Unit>>? = null
                        var sent = false
                        try {
                            val old = dao.sample(project.id, project.sha256, engine.identity, sample)
                            if(old != null) {
                                previous = dao.sampleObservations(project.id, project.sha256, engine.identity, sample)
                                previousPts = old.ptsUs
                                previousResult = null
                                window.send(UnitFrame(current, target, null)); sent = true
                            } else {
                                lease = container.imageCacheBudget.acquirePage(inputReservation, "video:${execution.taskId}")
                                val decoded = decoder.frameAt(target) ?: error("此时间范围没有可解码画面")
                                frame = decoded
                                val admitted = VideoFrameInput(decoded, requireNotNull(lease))
                                input = admitted
                                activeInputs += admitted
                                lease = null
                                val detection = engine.detect(decoded.bitmap, decoded.ptsUs / 1000)
                                admitted.lease.resizeForPage(admitted.sourceBytes + videoSegmentationBytes(detection))
                                val fresh = detection?.let { com.lmreader.screen.screenVideoRegions(decoded.bitmap, it.regions, ScreenTransform()) }.orEmpty()
                                val matches = screenVideoRegionMatches(previous.map { it.screenRegion() }, fresh, similarity)
                                val tracks = fresh.associate { region -> region.id to (matches[region.id]?.id
                                    ?: stableId("${project.id}:${project.sha256}:${engine.identity}:$sample:${region.id}")) }
                                val nextUs = if(single) target + 350_000 else plan.nextIndexAfter(current)?.let(plan::targetUs) ?: options.endMs * 1000
                                val untilUs = maxOf(decoded.ptsUs + 1000, nextUs).coerceAtMost(project.durationMs * 1000)
                                val observations = detection?.regions.orEmpty().map { region ->
                                    videoObservation(project.id, project.sha256, engine.identity, sample, tracks.getValue(region.id),
                                        target, decoded.ptsUs, untilUs, decoded.bitmap, region)
                                }
                                // SEG evidence advances retained tracks immediately, independently of a new region's API latency.
                                execution.publish { snapshot(project.id) { db.withTransaction {
                                    validateSource(payload)
                                    if(!single && previous.isNotEmpty() && decoded.ptsUs > previous.first().ptsUs) {
                                        dao.putObservations(previous.map { it.copy(untilUs = decoded.ptsUs) })
                                        previous.filter { it.trackId !in tracks.values }.forEach { observed ->
                                            dao.trackRegions(project.id, observed.trackId).filter { !it.manualTiming && !it.deleted }.forEach { row ->
                                                val end = (decoded.ptsUs / 1000).coerceAtLeast(row.startMs + 1)
                                                dao.saveRegion(row.copy(endMs = end, observedEndMs = end), row.revision)
                                            }
                                        }
                                    }
                                    dao.deleteSampleObservations(project.id, project.sha256, engine.identity, sample)
                                    dao.putObservations(observations)
                                    observations.forEach { observed ->
                                        dao.trackRegions(project.id, observed.trackId).filter { !it.manualTiming && !it.deleted }.forEach { row ->
                                            val end = (untilUs / 1000).coerceAtLeast(row.startMs + 1)
                                            dao.saveRegion(row.copy(endMs = maxOf(row.endMs, end), observedEndMs = maxOf(row.observedEndMs, end)), row.revision)
                                        }
                                    }
                                } } }
                                previous = observations
                                val staged = UnitFrame(current, target, decoded, untilUs, observations, tracks,
                                    detected = detection != null, input = admitted)
                                val samePresentation = previousPts == decoded.ptsUs
                                val preceding = previousResult
                                future = async(admitted) {
                                    try {
                                        val translated = if(samePresentation) {
                                            preceding?.await()?.getOrThrow()
                                            container.videoLibrary.artifacts(project.id).current(engine.pageId(decoded.ptsUs / 1000))
                                                ?: error("相同视频帧尚无可靠成果")
                                        } else withContext(TaskPublicationScope(engine.pageId(decoded.ptsUs / 1000))) {
                                                engine.frame(decoded.bitmap, decoded.ptsUs / 1000, detection, matches.keys)
                                            }
                                        // Commit every completed API unit immediately, even while an earlier
                                        // sample is still running. Ordered checkpoints can safely lag these receipts.
                                        publishCaptured(staged, translated)
                                        Result.success(Unit)
                                    } catch(cancelled: CancellationException) { throw cancelled }
                                    catch(failure: Throwable) { Result.failure(failure) }
                                    finally { admitted.close(); activeInputs -= admitted }
                                }
                                previousPts = decoded.ptsUs
                                previousResult = future
                                window.send(staged.copy(frame = null, observations = emptyList(), trackByDetection = emptyMap(),
                                    result = future, input = null)); sent = true
                            }
                        } finally { if(!sent) { withContext(NonCancellable) {
                            try { future?.cancelAndJoin() }
                            finally {
                                val abandoned = input
                                if(abandoned != null) { abandoned.close(); activeInputs -= abandoned }
                                else frame?.close()
                                lease?.release()
                            }
                        } } }
                        index = if(single) null else plan.nextIndexAfter(current)
                    }
                } finally { window.close() }
            }
            try {
                for(work in window) {
                    try {
                        execution.checkActive()
                        work.result?.await()?.getOrThrow()
                        completed++
                        execution.progress(completed, total, "画面 $completed/$total",
                            JSONObject().put("committed", work.index).put("identity", engine.identity).toString())
                    } catch(conflict: com.lmreader.ui.reader.translation.PageTranslationRevisionConflict) {
                        val pts = work.ptsUs.takeIf { it >= 0 } ?: throw conflict
                        val pageId = engine.pageId(pts / 1000)
                        execution.publish { snapshot(project.id) {
                            validateSource(payload); container.videoLibrary.artifacts(project.id).delete(pageId)
                            db.withTransaction {
                                dao.deletePageFrames(project.id, pageId); dao.deletePageRegions(project.id, pageId)
                                dao.deleteSampleObservations(project.id, project.sha256, engine.identity, if(single) -work.targetUs - 1 else work.index)
                            }
                        } }
                        throw TaskRequeue(resultConflict = true)
                    } finally { withContext(NonCancellable) {
                        work.result?.cancelAndJoin()
                        work.input?.close()
                        work.input?.let { activeInputs -= it }
                    } }
                }
                producer.join()
            } finally { withContext(NonCancellable) {
                try { producer.cancelAndJoin() }
                finally {
                    window.cancel()
                    activeInputs.toList().forEach { it.close(); activeInputs -= it }
                }
            } }
        } finally { withContext(NonCancellable) { try { decoder.close() } finally { engine.close() } } }
    } } catch(memory: OutOfMemoryError) {
        android.util.Log.e("VideoFrames", "Frame analysis exhausted memory after releasing in-flight inputs", memory)
        throw TaskWaiting("MEMORY", "画面分析内存不足，已停止并保留完成成果。请降低分析分辨率或 SEG/OCR 并发后重新开始。")
    }
    internal suspend fun subtitles(execution: TaskExecution) = coroutineScope {
        val payload = JSONObject(execution.payload); val options = VideoWorkOptions.read(payload.getJSONObject("options"))
        val project = checkedProject(payload).copy(source = options.source, target = options.target)
        val workflow = readWorkflow(payload.getJSONObject("subtitleWorkflow"))
        val engine = VideoTranslationEngine(container, project, workflow,
            ApiProfileCodec.decode(payload.getJSONObject("apiProfiles").toString()), "subtitle:${payload.optString("resultEpoch")}")
        try {
            try { engine.prepare() } catch(e: CancellationException) { throw e }
            catch(e: TaskWaiting) { throw e }
            catch(e: Exception) { throw TaskWaiting("CAPABILITY", e.message ?: "字幕工作流尚未就绪") }
            val progressLock = Mutex()
            var sourceCheckpoint = execution.initial.checkpoint
            var sourceStage = "准备语音识别"
            var sourceDone = false
            var completed = 0L
            var discovered = 0L
            suspend fun report(stage: String? = null, checkpoint: String? = null, finishedCue: Boolean = false) = progressLock.withLock {
                if(checkpoint != null) sourceCheckpoint = checkpoint
                if(stage != null) sourceStage = stage
                if(finishedCue) completed++
                val status = if(sourceDone && discovered == 0L) "识别完成，所选范围没有可用语音"
                    else if(sourceDone) "字幕 $completed/$discovered" else "$sourceStage · 字幕已处理 $completed 句"
                execution.progress(completed, if(sourceDone) discovered else 0, status, sourceCheckpoint)
            }
            val queue = Channel<VideoCueRecord>(16)
            val resources = VideoSentenceResources(container.applicationContext, project.uri, options.audioTrack)
            val workers = List(workflow.parallelLimit.coerceIn(1, 8)) {
                launch {
                    for(row in queue) {
                        execution.checkActive()
                        val original = execution.publish { snapshot(project.id) {
                            validateSource(payload)
                            val current = dao.cue(project.id, row.cueId)?.takeIf { !it.deleted }
                            if(current != null && current.translation.isNotBlank() && current.identity != engine.identity) {
                                dao.saveCue(current.copy(translation = "", manuallyEdited = false, identity = null, producerTaskId = null), current.revision)
                                dao.cue(project.id, row.cueId)
                            } else current
                        } }
                        if(original != null) {
                            val cue = VideoSubtitleCue(original.cueId, original.startMs, original.endMs, original.source,
                                original.translation.takeIf { original.identity == engine.identity }.orEmpty(),
                                original.manuallyEdited && original.identity == engine.identity)
                            engine.subtitles(listOf(cue), resources) { translated, _, _ ->
                                var restart = false
                                execution.publish { snapshot(project.id) {
                                    validateSource(payload)
                                    val current = dao.cue(project.id, translated.id)
                                    if(current != null && !current.deleted) {
                                        if(current.revision != original.revision) {
                                            dao.saveCue(current.copy(translation = "", manuallyEdited = false, identity = null,
                                                producerTaskId = null), current.revision)
                                            restart = true
                                        } else if(!current.manuallyEdited && (current.translation != translated.translation || current.identity != engine.identity))
                                            dao.saveCue(current.copy(translation = translated.translation, identity = engine.identity,
                                                producerTaskId = execution.taskId), current.revision)
                                    }
                                    if(!restart) execution.commitScope("cue:${translated.id}")
                                } }
                                if(restart) throw TaskRequeue(resultConflict = true)
                            }
                        }
                        report(finishedCue = true)
                    }
                }
            }
            try {
            val seen = hashSetOf<String>()
            suspend fun offer(rows: List<VideoCueRecord>) {
                for(row in rows) {
                    if(row.deleted || row.sourceHash != project.sha256 || row.endMs <= options.startMs || row.startMs >= options.endMs || !seen.add(row.cueId)) continue
                    progressLock.withLock { discovered++ }
                    queue.send(row)
                }
            }
            try {
                val checkpoint = JSONObject(sourceCheckpoint)
                val sourceTrackId = checkpoint.optString("sourceTrackId").takeIf { it.isNotBlank() } ?: when(options.subtitleSource) {
                    "EMBEDDED" -> "embedded:${options.subtitleTrack}"
                    else -> if(checkpoint.optLong("asrCommitted", -1) >= 0) "speech:${options.audioTrack}"
                        else "speech:${options.audioTrack}:${options.asrModelId ?: container.asrModels.selectedId.value ?: "api:${options.audioApiProfileId}"}"
                }
                val existing = if(options.subtitleSource == "EXTERNAL") dao.cues(project.id).filter { it.trackId.startsWith("external") }
                    else dao.cuesInRange(project.id, project.sha256, sourceTrackId, options.startMs, options.endMs)
                offer(existing)
                if(options.subtitleSource != "EXTERNAL") prepareAudio(execution, project, options,
                    VideoSourceUpdates(onBatch = { offer(it) }, onProgress = { stage, cp -> report(stage, cp) }))
                progressLock.withLock { sourceDone = true }
                report()
            } finally { queue.close() }
            workers.joinAll()
            if(discovered == 0L && options.subtitleSource == "EXTERNAL") throw TaskWaiting("CAPABILITY", "请先导入可用的字幕")
            report(if(discovered == 0L) "识别完成，所选范围没有可用语音" else null)
            } finally {
                queue.cancel()
                withContext(NonCancellable) { workers.forEach { it.cancel() }; workers.joinAll() }
            }
        } finally { engine.close() }
    }
    private fun workflowSnapshot(id: String, apiProfileId: String?): JSONObject {
        val w = container.translationWorkflows.find(id) ?: error("工作流已删除，请重新选择")
        // Execution copy only: a per-video choice never edits the stored workflow definition.
        val program = w.program.withApiOverride(apiProfileId)
        return JSONObject().put("id", w.id).put("revision", w.revision).put("name", w.name).put("parallelLimit", w.parallelLimit)
            .put("retries", w.retries).put("program", JSONObject(WorkflowProgramCodec.encode(program)))
    }
    private fun readWorkflow(j: JSONObject) = TranslationWorkflow(j.getString("id"), j.getInt("revision"), j.getString("name"), "", TranslationPageMode.BUBBLE,
        j.getInt("parallelLimit"), j.getInt("retries"), program = WorkflowProgramCodec.decode(j.getJSONObject("program").toString()))
    companion object {
        fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun stableId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray()).toString()
    }
}
