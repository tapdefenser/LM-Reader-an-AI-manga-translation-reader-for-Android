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
import kotlinx.coroutines.sync.Semaphore
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
    val maxDimension: Int = 1280, val frameApiProfileId: String? = null, val subtitleApiProfileId: String? = null) {
    fun json() = JSONObject().put("source", source).put("target", target).put("mode", mode)
        .put("frameWorkflowId", frameWorkflowId).put("subtitleWorkflowId", subtitleWorkflowId)
        .put("fpsNumerator", fpsNumerator).put("fpsDenominator", fpsDenominator).put("startMs", startMs).put("endMs", endMs)
        .put("subtitleSource", subtitleSource).put("audioTrack", audioTrack).put("subtitleTrack", subtitleTrack)
        .put("asrModelId", asrModelId).put("audioApiProfileId", audioApiProfileId).put("audioApiEndpoint", audioApiEndpoint).put("maxDimension", maxDimension)
        .put("frameApiProfileId", frameApiProfileId).put("subtitleApiProfileId", subtitleApiProfileId)
    companion object { fun read(j: JSONObject) = VideoWorkOptions(j.optString("source", "ja"), j.optString("target", "zh-Hans"), j.optString("mode", "BOTH"),
        j.optString("frameWorkflowId", TranslationWorkflow.LOCAL_MACHINE_ID), j.optString("subtitleWorkflowId", "builtin.subtitle-local"),
        j.optInt("fpsNumerator", 1), j.optInt("fpsDenominator", 1), j.optLong("startMs"), j.optLong("endMs"),
        j.optString("subtitleSource", "ASR_LOCAL"), j.optInt("audioTrack", -1), j.optInt("subtitleTrack", -1),
        j.optString("asrModelId").takeUnless { it.isBlank() || it == "null" }, j.optString("audioApiProfileId").takeUnless { it.isBlank() || it == "null" },
        j.optString("audioApiEndpoint"), j.optInt("maxDimension", 1280),
        j.optString("frameApiProfileId").takeUnless { it.isBlank() || it == "null" },
        j.optString("subtitleApiProfileId").takeUnless { it.isBlank() || it == "null" }) }
}

/** Application-owned offline executors. No player or ViewModel owns this work. */
class VideoTasks(private val container: AppContainer) {
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
    suspend fun options(id: String): VideoWorkOptions = dao.settings(id)?.let { VideoWorkOptions.read(JSONObject(it.json)) }
        ?: container.videoLibrary.projects.value.firstOrNull { it.id == id }?.let { VideoWorkOptions(it.source, it.target, frameWorkflowId = it.workflowId, subtitleWorkflowId = it.subtitleWorkflowId) }
        ?: VideoWorkOptions()
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
            subtitleTrack = -1, asrModelId = if(speech) container.asrModels.selectedId.value else null)
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
    private suspend fun frames(execution: TaskExecution) = coroutineScope {
        val payload = JSONObject(execution.payload); val options = VideoWorkOptions.read(payload.getJSONObject("options"))
        val project = checkedProject(payload).copy(source = options.source, target = options.target)
        val workflow = readWorkflow(payload.getJSONObject("frameWorkflow"))
        val engine = VideoTranslationEngine(container, project, workflow, ApiProfileCodec.decode(payload.getJSONObject("apiProfiles").toString()),
            "${options.maxDimension}:${payload.optString("resultEpoch")}")
        val decoder = VideoFrameDecoder(container.applicationContext, project.uri, options.maxDimension)
        try {
            try { engine.prepare() } catch(e: CancellationException) { throw e } catch(e: Exception) { throw TaskWaiting("CAPABILITY", e.message ?: "画面工作流尚未就绪") }
            execution.publish { snapshot(project.id) {
                val row = dao.settings(project.id) ?: VideoSettingsRecord(project.id)
                dao.putSettings(row.copy(json = JSONObject(row.json).put("frameIdentity", engine.identity).toString()))
            } }
            val checkpoint = JSONObject(execution.initial.checkpoint)
            val previousIdentity = checkpoint.optString("identity")
            if(previousIdentity.isNotBlank() && previousIdentity != engine.identity) throw TaskWaiting("CONFIG_CHANGED", "模型或工作流语义已变化，请更新并重新排队")
            val single = payload.has("frameAtUs")
            val plan = VideoSamplingPlan(project.durationMs, VideoSamplingRate(options.fpsNumerator, options.fpsDenominator), options.startMs,
                options.endMs.coerceAtMost(project.durationMs))
            val total = if(single) 1 else plan.sampleCount
            var completed = execution.initial.completed
            var previousPixels: String? = null
            var previousResult: com.lmreader.ui.reader.translation.ReaderPageTranslation? = null
            var previousTracks = emptyMap<String, String>()
            val permits = Semaphore(2)
            data class UnitFrame(val index: Long, val targetUs: Long, val frame: DecodedVideoFrame?)
            val window = Channel<UnitFrame>(1, onUndeliveredElement = { it.frame?.close(); permits.release() })
            val producer = launch {
                try {
                    var index: Long? = if(single) 0 else plan.nextIndexAfter(checkpoint.optLong("committed", -1).takeIf { it >= 0 })
                    while(index != null) {
                        val current = index
                        val target = if(single) payload.getLong("frameAtUs") else plan.targetUs(current)
                        permits.acquire()
                        var frame: DecodedVideoFrame? = null; var sent = false
                        try {
                            val old = dao.sample(project.id, project.sha256, engine.identity, if(single) -target - 1 else current)
                            if(old == null) frame = decoder.frameAt(target) ?: error("此时间范围没有可解码画面")
                            window.send(UnitFrame(current, target, frame)); sent = true
                        } finally { if(!sent) { frame?.close(); permits.release() } }
                        index = if(single) null else plan.nextIndexAfter(current)
                    }
                } finally { window.close() }
            }
            try {
                for(work in window) {
                    try {
                        execution.checkActive()
                        val publicationContext = currentCoroutineContext()
                        if(work.frame == null) {
                            // A reused persisted sample has no pixel evidence linking its neighbours.
                            previousPixels = null; previousResult = null; previousTracks = emptyMap()
                        }
                        work.frame?.let { frame ->
                            val pageId = "video:${project.id}:${project.sha256}:${frame.ptsUs / 1000}:${engine.identity}"
                            val stored = container.videoLibrary.artifacts(project.id).current(pageId)
                            val fingerprint = VideoTranslationEngine.bitmapHash(frame.bitmap)
                            val reusable = previousResult?.takeIf { !it.manuallyEdited && previousPixels == fingerprint &&
                                it.width == frame.bitmap.width && it.height == frame.bitmap.height && it.resultIdentity == engine.identity }
                            val saved = stored?.takeIf { it.resultIdentity == engine.identity } ?: reusable?.copy(pageId = pageId,
                                regions = retargetVideoRegions(reusable.regions, reusable.pageId, pageId))
                                ?: engine.frame(frame.bitmap, frame.ptsUs / 1000)
                            val nextUs = if(single) work.targetUs + 350_000 else plan.nextIndexAfter(work.index)?.let(plan::targetUs) ?: project.durationMs * 1000
                            val untilUs = maxOf(frame.ptsUs + 1000, nextUs).coerceAtMost(project.durationMs * 1000)
                            execution.publish { snapshot(project.id) {
                                validateSource(payload)
                                val artifacts = container.videoLibrary.artifacts(project.id)
                                val published = stored?.takeIf { it.resultIdentity == engine.identity } ?: artifacts.save(saved.pageId, saved.sourceSha256, saved.source, saved.target,
                                    saved.width, saved.height, saved.regions, saved.modelPacks, saved.elapsedMillis, saved.renderSettings,
                                    expectedRevision = stored?.revision, requireAbsent = stored == null, resultIdentity = engine.identity,
                                    producerTaskId = execution.taskId, checkCancelled = { publicationContext.ensureActive() })
                                VideoFrameProof.capture(frame.bitmap, published).save(container.videoLibrary.proofs(project.id), published.pageId)
                                db.withTransaction {
                                    dao.putFrame(VideoFrameIndex(project.id, project.sha256, engine.identity, if(single) -work.targetUs - 1 else work.index,
                                        work.targetUs, frame.ptsUs, untilUs, published.pageId, published.revision, published.regions.isEmpty(), execution.taskId))
                                    published.regions.forEach { region ->
                                        val intervalId = stableId("${project.id}:${published.pageId}:${region.region.id}")
                                        val endMs = (untilUs / 1000).coerceAtLeast(frame.ptsUs / 1000 + 1)
                                        val old = dao.region(intervalId)
                                        if(old == null) dao.addRegion(VideoRegionInterval(intervalId, project.id, project.sha256, published.pageId, region.region.id,
                                            frame.ptsUs / 1000, endMs, revision = UUID.randomUUID().toString(),
                                            trackId = if(reusable != null) previousTracks[region.region.id.removePrefix("${published.pageId}:")] ?: intervalId else intervalId))
                                        else if(!old.manualTiming && !old.deleted && endMs > old.endMs)
                                            dao.saveRegion(old.copy(endMs = endMs, observedEndMs = endMs), old.revision)
                                    }
                                    previousTracks = dao.pageRegions(published.pageId).associate { it.regionId.removePrefix("${published.pageId}:") to it.trackId }
                                    execution.commitScope(published.pageId)
                                }
                                previousResult = published; previousPixels = fingerprint
                            } }
                        }
                        completed++
                        execution.progress(completed, total, "画面 $completed/$total", JSONObject().put("committed", work.index).put("identity", engine.identity).toString())
                    } catch(conflict: com.lmreader.ui.reader.translation.PageTranslationRevisionConflict) {
                        val pts = work.frame?.ptsUs ?: throw conflict
                        val pageId = "video:${project.id}:${project.sha256}:${pts / 1000}:${engine.identity}"
                        execution.publish { snapshot(project.id) {
                            validateSource(payload)
                            container.videoLibrary.artifacts(project.id).delete(pageId)
                            db.withTransaction { dao.deletePageFrames(project.id, pageId); dao.deletePageRegions(project.id, pageId) }
                        } }
                        throw TaskRequeue(resultConflict = true)
                    } finally { work.frame?.close(); permits.release() }
                }
                producer.join()
            } finally { producer.cancelAndJoin(); window.cancel() }
        } finally { decoder.close(); engine.close() }
    }
    private suspend fun subtitles(execution: TaskExecution) {
        val payload = JSONObject(execution.payload); val options = VideoWorkOptions.read(payload.getJSONObject("options"))
        val project = checkedProject(payload).copy(source = options.source, target = options.target)
        val track = options.subtitleSource
        if(track != "EXTERNAL") container.videoAudio.prepareSource(execution, project, options)
        val sourceTrackId = JSONObject(container.taskScheduler.dao.get(execution.taskId)?.checkpoint ?: "{}")
            .optString("sourceTrackId").takeIf { it.isNotBlank() } ?: "speech:${options.audioTrack}"
        val initial = dao.cues(project.id).filter { !it.deleted && it.sourceHash == project.sha256 && it.endMs > options.startMs && it.startMs < options.endMs && when(track) {
            "EXTERNAL" -> it.trackId.startsWith("external")
            "EMBEDDED" -> it.trackId == "embedded:${options.subtitleTrack}"
            else -> it.trackId == sourceTrackId
        } }
        if(initial.isEmpty()) {
            if(track != "EXTERNAL") { execution.progress(0, 0, "识别完成，所选范围没有可用语音/文字字幕"); return }
            throw TaskWaiting("CAPABILITY", "没有可用字幕，请选择字幕轨、导入 SRT 或配置 ASR")
        }
        val engine = VideoTranslationEngine(container, project, readWorkflow(payload.getJSONObject("subtitleWorkflow")), ApiProfileCodec.decode(payload.getJSONObject("apiProfiles").toString()),
            "subtitle:${payload.optString("resultEpoch")}")
        try {
            try { engine.prepare() } catch(e: CancellationException) { throw e } catch(e: Exception) { throw TaskWaiting("CAPABILITY", e.message ?: "字幕工作流尚未就绪") }
            val currentRows = execution.publish { snapshot(project.id) {
                validateSource(payload)
                initial.mapNotNull { old ->
                    val current = dao.cue(project.id, old.cueId)?.takeIf { !it.deleted } ?: return@mapNotNull null
                    if(current.translation.isNotBlank() && current.identity != engine.identity) {
                        dao.saveCue(current.copy(translation = "", manuallyEdited = false, identity = null, producerTaskId = null), current.revision)
                        dao.cue(project.id, old.cueId)
                    } else current
                }
            } }
            val originals = currentRows.associateBy { it.cueId }
            val cues = currentRows.map { VideoSubtitleCue(it.cueId, it.startMs, it.endMs, it.source,
                it.translation.takeIf { _ -> it.identity == engine.identity }.orEmpty(), it.manuallyEdited && it.identity == engine.identity) }
            engine.subtitles(cues, VideoSentenceResources(container.applicationContext, project.uri, options.audioTrack)) { cue, done, total ->
                var restart = false
                execution.publish { snapshot(project.id) {
                    validateSource(payload)
                    val current = dao.cue(project.id, cue.id)
                    val original = originals[cue.id]
                    if(current != null && original != null && !current.deleted) {
                        if(current.revision != original.revision) {
                            dao.saveCue(current.copy(translation = "", manuallyEdited = false, identity = null, producerTaskId = null), current.revision)
                            restart = true
                        } else if(!current.manuallyEdited)
                            dao.saveCue(current.copy(translation = cue.translation, identity = engine.identity, producerTaskId = execution.taskId), current.revision)
                    }
                    if(!restart) execution.commitScope("cue:${cue.id}")
                } }
                if(restart) throw TaskRequeue(resultConflict = true)
                execution.progress(done.toLong(), total.toLong(), "字幕 $done/$total")
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
