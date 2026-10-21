package com.lmreader.ui.video

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.room.withTransaction
import com.lmreader.core.database.media.VideoCueRecord
import com.lmreader.core.model.*
import com.lmreader.core.translation.asr.AsrSegment
import com.lmreader.di.AppContainer
import com.lmreader.tasks.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class VideoTrackDescription(val index: Int, val mime: String, val language: String, val type: String)

/** Text tracks and true audio transcription routes. A text-only API is never sent audio implicitly. */
class VideoAudioSources(private val container: AppContainer) {
    private val dao get() = container.videoResults.results()
    suspend fun tracks(uri: String): List<VideoTrackDescription> = withContext(Dispatchers.IO) {
        ResourceGovernor.shared.use("io") {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(container.applicationContext, Uri.parse(uri), null)
                (0 until extractor.trackCount).mapNotNull { index ->
                    val f = extractor.getTrackFormat(index); val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                    val type = when {
                        mime.startsWith("audio/") -> "AUDIO"
                        mime.contains("pgs") || mime.contains("dvbsub") -> "GRAPHIC"
                        mime.startsWith("text/") || mime in setOf("application/x-subrip", "application/ttml+xml", "application/mp4vtt", "application/webvtt") -> "TEXT"
                        else -> return@mapNotNull null
                    }
                    VideoTrackDescription(index, mime, if(f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE).orEmpty() else "und", type)
                }
            } finally { extractor.release() }
        }
    }
    suspend fun importSrt(id: String, uri: Uri) {
        val p = container.videoTasks.ensureProject(id)
        val bytes = ResourceGovernor.shared.use("io") {
            requireNotNull(container.applicationContext.contentResolver.openInputStream(uri)).use { input ->
                val buffer = java.io.ByteArrayOutputStream(); val block = ByteArray(8192)
                while(true) { currentCoroutineContext().ensureActive(); val n = input.read(block); if(n < 0) break
                    require(buffer.size() + n <= VideoSubtitles.MAX_INPUT_BYTES) { "字幕文件超过 4 MB" }; buffer.write(block, 0, n) }
                buffer.toByteArray()
            }
        }
        val cues = VideoSubtitles.parse(bytes, p.durationMs)
        container.videoTasks.withEditBarrier(id) { container.workDatabase.withTransaction {
            cues.forEach { c -> dao.addCue(VideoCueRecord(id, VideoTasks.stableId("${p.sha256}:${c.id}"), p.sha256,
                "external:${VideoTasks.hash(bytes.decodeToString())}", c.startMs, c.endMs, c.source, revision = UUID.randomUUID().toString())) }
        } }
    }
    suspend fun prepareSource(execution: TaskExecution, project: VideoProject, options: VideoWorkOptions) {
        container.videoTasks.validateSource(JSONObject(execution.payload))
        val checkpoint = JSONObject(execution.initial.checkpoint)
        if(checkpoint.optBoolean("sourcePrepared")) return
        when(options.subtitleSource) {
            "EMBEDDED" -> embedded(execution, project, options)
            "ASR_LOCAL", "ASR_API" -> speech(execution, project, options)
            else -> throw TaskWaiting("CAPABILITY", "请选择字幕或语音文字来源")
        }
    }
    private suspend fun embedded(execution: TaskExecution, p: VideoProject, options: VideoWorkOptions) {
        val metadata = tracks(p.uri)
        val selected = metadata.firstOrNull { it.index == options.subtitleTrack }
            ?: metadata.firstOrNull { options.subtitleTrack < 0 && it.type == "TEXT" }
            ?: throw TaskWaiting("CAPABILITY", "未找到所选文字字幕轨；图形字幕请改用画面翻译或 ASR")
        if(selected.type != "TEXT" || selected.mime !in setOf("application/x-subrip", "text/3gpp"))
            throw TaskWaiting("CAPABILITY", "暂不支持解析 ${selected.mime}；请选择外部 SRT 或 ASR，原轨没有被修改")
        val cues = withContext(Dispatchers.IO) { ResourceGovernor.shared.use("io") {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(container.applicationContext, Uri.parse(p.uri), null); extractor.selectTrack(selected.index)
                val buffer = ByteBuffer.allocate(1_048_576)
                val text = java.io.ByteArrayOutputStream()
                val entries = mutableListOf<Pair<Long, String>>()
                while(true) {
                    currentCoroutineContext().ensureActive(); buffer.clear(); val size = extractor.readSampleData(buffer, 0); if(size < 0) break
                    require(size <= buffer.capacity()) { "单条字幕过大" }; buffer.position(0); buffer.limit(size)
                    val bytes = ByteArray(size); buffer.get(bytes)
                    if(selected.mime == "application/x-subrip") { require(text.size() + size < VideoSubtitles.MAX_INPUT_BYTES); text.write(bytes); text.write("\n\n".toByteArray()) }
                    else {
                        require(size >= 2); val length = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                        require(length <= size - 2) { "容器字幕数据损坏" }
                        val source = bytes.copyOfRange(2, length + 2).toString(Charsets.UTF_8)
                        entries += extractor.sampleTime / 1000 to source
                        require(entries.size <= VideoSubtitles.MAX_CUES)
                    }
                    extractor.advance()
                }
                if(selected.mime == "application/x-subrip") VideoSubtitles.parse(text.toByteArray(), p.durationMs)
                else entries.mapIndexedNotNull { index, (start, source) ->
                    val end = entries.getOrNull(index + 1)?.first ?: p.durationMs
                    if(source.isBlank() || end <= start) null else VideoSubtitleCue(VideoTasks.stableId("${p.sha256}:${selected.index}:$start:$end:$source"), start, end, source)
                }
            } finally { extractor.release() }
        } }
        execution.publish { container.workDatabase.withTransaction {
            container.videoTasks.validateSource(JSONObject(execution.payload))
            cues.forEach { cue -> dao.addCue(VideoCueRecord(p.id, cue.id, p.sha256, "embedded:${selected.index}", cue.startMs, cue.endMs, cue.source, revision = UUID.randomUUID().toString(), producerTaskId = execution.taskId)) }
            execution.progress(cues.size.toLong(), 0, "已读取文字字幕", JSONObject().put("sourcePrepared", true).toString())
        } }
    }
    private suspend fun speech(execution: TaskExecution, p: VideoProject, options: VideoWorkOptions) {
        var checkpoint = JSONObject(execution.initial.checkpoint)
        val modelId = checkpoint.optString("asrModelId").takeIf { it.isNotBlank() }
            ?: options.asrModelId ?: container.asrModels.selectedId.value
        if(options.subtitleSource == "ASR_LOCAL" && (modelId == null || container.asrModels.installedModel(modelId) == null))
            throw TaskWaiting("CAPABILITY", "请在设置 → API 与翻译引擎 → 本地 ASR 模型下载模型并设为当前；视频会自动使用它")
        val decoder = VideoAudioDecoder(container.applicationContext, p.uri, options.audioTrack, options.startMs, options.endMs)
        val committed = checkpoint.optLong("asrCommitted", -1)
        val trackId = checkpoint.optString("sourceTrackId").takeIf { it.isNotBlank() }
            ?: if(committed >= 0) "speech:${options.audioTrack}" else "speech:${options.audioTrack}:${modelId ?: "api:${options.audioApiProfileId}"}"
        try {
            decoder.decode { chunk ->
                if(chunk.index <= committed) return@decode
                execution.checkActive()
                execution.progress(chunk.index, 0, "语音识别 · ${chunk.startMs / 1000} 秒")
                val language = options.source.substringBefore('-').substringBefore('_')
                val segments = if(options.subtitleSource == "ASR_LOCAL") container.localAsr.transcribe(chunk.samples, language, modelId)
                    else transcribeApi(execution, chunk.samples, language, options)
                val existing = dao.cues(p.id).filter { it.trackId == trackId && it.sourceHash == p.sha256 }
                execution.publish { container.workDatabase.withTransaction {
                    container.videoTasks.validateSource(JSONObject(execution.payload))
                    for(segment in segments) {
                        val start = chunk.startMs + segment.startMs; val end = minOf(chunk.startMs + segment.endMs, options.endMs, p.durationMs)
                        if(start < options.startMs || end <= start || segment.text.isBlank()) continue
                        val duplicate = existing.any { row -> row.source.trim() == segment.text.trim() && minOf(row.endMs, end) > maxOf(row.startMs, start) }
                        if(duplicate) continue
                        val cueId = VideoTasks.stableId("${p.sha256}:${options.audioTrack}:$modelId:$start:$end:${segment.text}")
                        dao.addCue(VideoCueRecord(p.id, cueId, p.sha256, trackId, start, end, segment.text,
                            revision = UUID.randomUUID().toString(), producerTaskId = execution.taskId))
                    }
                    checkpoint = JSONObject().put("asrCommitted", chunk.index).put("asrModelId", modelId).put("sourceTrackId", trackId)
                    execution.progress(chunk.index + 1, 0, "语音识别 · ${chunk.startMs / 1000} 秒", checkpoint.toString())
                    execution.remoteOutcome(false)
                } }
            }
            checkpoint.put("sourcePrepared", true)
            execution.progress(0, 0, "语音识别完成", checkpoint.toString())
        } finally { decoder.close(); if(options.subtitleSource == "ASR_LOCAL") container.localAsr.release() }
    }
    private suspend fun transcribeApi(execution: TaskExecution, samples: FloatArray, language: String, options: VideoWorkOptions): List<AsrSegment> {
        val liveProfiles = container.apiProfiles.profiles.first()
        val live = liveProfiles.firstOrNull { it.id == options.audioApiProfileId }
            ?: throw TaskWaiting("CAPABILITY", "请选择具有实际音频转写能力的 API 配置")
        val captured = com.lmreader.core.api.ApiProfileCodec.decode(JSONObject(execution.payload).getJSONObject("apiProfiles").toString())
            .firstOrNull { it.id == options.audioApiProfileId } ?: throw TaskWaiting("CAPABILITY", "音频 API 配置没有捕获到任务，请更新选项")
        val profile = captured.copy(apiKey = live.apiKey)
        container.apiClient.configureProfiles(liveProfiles)
        val endpoint = options.audioApiEndpoint.toHttpUrlOrNull() ?: throw TaskWaiting("CAPABILITY", "请明确填写音频转写端点，例如 /audio/transcriptions")
        require(endpoint.scheme == "https" || endpoint.host in setOf("localhost", "127.0.0.1")) { "音频 API 需要 HTTPS，或本机服务" }
        val audio = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        audio.put("RIFF".toByteArray()).putInt(36 + samples.size * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples.size * 2)
        samples.forEach { audio.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("model", profile.model)
            .addFormDataPart("language", language).addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("file", "segment.wav", audio.array().toRequestBody("audio/wav".toMediaType())).build()
        val request = Request.Builder().url(endpoint).post(body).apply { if(profile.apiKey.isNotBlank()) header("Authorization", "Bearer ${profile.apiKey}") }.build()
        return container.apiClient.withExternalPermit(profile, endpoint.toString()) {
            // A response lost after upload must never be automatically billed again.
            execution.remoteOutcome(true)
            execution.networkStage(true)
            try {
                val client = OkHttpClient.Builder().callTimeout(profile.timeoutSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS).build()
                val response = suspendCancellableCoroutine<Response> { continuation ->
                    val call = client.newCall(request); continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: java.io.IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
                        override fun onResponse(call: Call, response: Response) { if(continuation.isActive) continuation.resume(response) else response.close() }
                    })
                }
                response.use {
                    check(it.isSuccessful) { "音频 API 返回 HTTP ${it.code}" }
                    val bytes = it.body?.byteStream()?.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while(true) { val n = input.read(buffer); if(n < 0) break; require(out.size() + n <= 4_000_000); out.write(buffer, 0, n) }; out.toByteArray()
                    } ?: error("音频 API 未返回结果")
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    val segments = json.optJSONArray("segments") ?: error("该音频 API 没有返回逐段时间戳，无法生成对齐字幕")
                    (0 until segments.length()).map { index -> val segment = segments.getJSONObject(index)
                        val start = (segment.getDouble("start") * 1000).toLong(); val end = (segment.getDouble("end") * 1000).toLong()
                        require(start >= 0 && end > start && end <= samples.size * 1000L / 16000 + 1000) { "音频 API 时间戳不正确" }
                        AsrSegment(start, end, segment.getString("text"))
                    }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { throw TaskWaiting("REMOTE_UNKNOWN", "音频请求结果未知，可能已计费。请核对提供方后明确重试：${e.message}") }
            finally { execution.networkStage(false) }
        }
    }
}
