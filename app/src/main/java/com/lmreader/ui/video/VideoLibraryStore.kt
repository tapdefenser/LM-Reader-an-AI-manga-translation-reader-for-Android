package com.lmreader.ui.video

import com.lmreader.core.model.*
import com.lmreader.ui.queue.ExportFiles
import com.lmreader.ui.reader.translation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class VideoFrameResult(val atMs: Long, val untilMs: Long, val pageId: String, val identity: String)
data class VideoProject(val id: String, val uri: String, val name: String, val sha256: String, val bytes: Long,
    val durationMs: Long, val width: Int, val height: Int, val rotation: Int, val positionMs: Long = 0,
    val source: String = "ja", val target: String = "zh-Hans", val workflowId: String = TranslationWorkflow.LOCAL_MACHINE_ID,
    val subtitleWorkflowId: String = "builtin.subtitle-local", val offsetMs: Long = 0,
    val cues: List<VideoSubtitleCue> = emptyList(), val frames: List<VideoFrameResult> = emptyList(),
    val subtitleIdentity: String? = null, val revision: String = UUID.randomUUID().toString(), val frameIdentity: String? = null)

data class VideoResource(val owner: String, val kind: String, val bytes: Long, val rebuildable: Boolean)

/** Owned metadata/results only. Original media always remains at the user's URI. */
class VideoLibraryStore(val root: File) {
    private val mutable = MutableStateFlow(readAll())
    val projects = mutable.asStateFlow()
    private fun file(id: String): File { require(id.matches(Regex("[a-f0-9-]{36}"))); return File(root, "$id.json") }
    fun artifacts(id: String) = ReaderPageArtifactStore(File(root, "$id/frames"))
    fun proofs(id: String): File { file(id); return File(root, "$id/proofs") }
    @Synchronized fun current(id: String) = mutable.value.firstOrNull { it.id == id } ?: error("视频条目已移除")
    @Synchronized fun reload() { mutable.value = readAll() }
    @Synchronized fun snapshot(target: File) {
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.forEach { source ->
            val destination = File(target, source.relativeTo(root).path)
            destination.parentFile!!.mkdirs(); source.copyTo(destination)
        }
        check(target.isDirectory || target.mkdirs())
    }
    @Synchronized fun validateResults() {
        for (p in projects.value) {
            for (f in p.frames) {
                val result = artifacts(p.id).current(f.pageId) ?: error("视频画面成果缺失")
                require(result.resultIdentity == f.identity)
                require(VideoFrameProof.load(proofs(p.id), f.pageId) != null)
            }
        }
    }
    @Synchronized fun add(value: VideoProject): VideoProject {
        val old = mutable.value.firstOrNull { it.sha256 == value.sha256 }
        if (old != null) return change(old.id) { it.copy(uri = value.uri) }
        write(value); mutable.value = mutable.value + value; return value
    }
    @Synchronized fun addOwned(value: VideoProject): VideoProject {
        mutable.value.firstOrNull { it.id == value.id }?.let { return it }
        write(value); mutable.value = mutable.value + value; return value
    }
    /** Explicit source-version switch. Historical headers and all formal artifacts remain owned. */
    @Synchronized fun replaceSource(value: VideoProject) {
        val previous = mutable.value.firstOrNull { it.id == value.id }
        if(previous != null && previous.sha256 != value.sha256) {
            val archive = File(root, "${previous.id}/sources/${previous.sha256}.json")
            check(archive.parentFile!!.mkdirs() || archive.parentFile!!.isDirectory)
            ExportFiles.commit(archive) { it.write(encode(previous).toString().toByteArray(Charsets.UTF_8)) }
        }
        write(value)
        mutable.value = mutable.value.filterNot { it.id == value.id } + value
    }
    @Synchronized fun sourceHeader(id: String, hash: String): VideoProject? {
        file(id); require(hash.matches(Regex("[a-f0-9]{64}")))
        mutable.value.firstOrNull { it.id == id && it.sha256 == hash }?.let { return it }
        val archived = File(root, "$id/sources/$hash.json")
        if(!archived.isFile) return null
        require(archived.length() <= MAX_BYTES)
        return decode(JSONObject(archived.readText())).also { require(it.id == id && it.sha256 == hash) }
    }
    @Synchronized fun change(id: String, expectedRevision: String? = null, change: (VideoProject) -> VideoProject): VideoProject {
        val old = current(id)
        check(expectedRevision == null || old.revision == expectedRevision) { "视频成果已更新，请重新载入后再试" }
        val next = change(old).copy(revision = UUID.randomUUID().toString())
        require(next.id == old.id && next.sha256 == old.sha256)
        write(next); mutable.value = mutable.value.map { if (it.id == id) next else it }; return next
    }
    @Synchronized fun publishFrame(id: String, sourceHash: String, atMs: Long, untilMs: Long, identity: String,
        saved: ReaderPageTranslation, checkActive: () -> Unit): VideoFrameResult {
        val project = current(id)
        check(project.sha256 == sourceHash)
        checkActive()
        // Same semantic frame identity can be reused. Human edits are never automatically replaced.
        val store = artifacts(id)
        val previous = store.current(saved.pageId)
        if (previous?.manuallyEdited != true) store.save(saved.pageId, saved.sourceSha256, saved.source, saved.target,
            saved.width, saved.height, saved.regions, saved.modelPacks, saved.elapsedMillis, saved.renderSettings,
            expectedRevision = previous?.revision, requireAbsent = previous == null, resultIdentity = identity, checkCancelled = checkActive)
        val result = VideoFrameResult(atMs, untilMs.coerceAtMost(project.durationMs), saved.pageId, identity)
        checkActive()
        change(id) { it.copy(frameIdentity = identity, frames = it.frames.filterNot { row -> row.pageId == result.pageId && row.atMs == result.atMs } + result) }
        return result
    }
    @Synchronized fun verifyInterval(id: String, result: VideoFrameResult, atMs: Long): VideoFrameResult {
        val p = current(id)
        val row = if (atMs >= result.atMs && atMs <= result.untilMs + 500) result.copy(untilMs = (atMs + 350).coerceAtMost(p.durationMs))
            else result.copy(atMs = atMs, untilMs = (atMs + 350).coerceAtMost(p.durationMs))
        if (row.untilMs <= row.atMs) return result
        change(id) { it.copy(frames = it.frames.filterNot { old -> old.pageId == row.pageId && old.atMs == row.atMs } + row) }
        return row
    }
    @Synchronized fun editCue(id: String, cueId: String, translation: String, expectedCue: VideoSubtitleCue) = change(id) { p ->
        require(translation.length <= 20000)
        check(p.cues.firstOrNull { it.id == cueId } == expectedCue) { "字幕已更新，请重新载入" }
        p.copy(cues = p.cues.map { if (it.id == cueId) it.copy(translation = translation, manuallyEdited = true) else it })
    }
    @Synchronized fun resources(id: String): List<VideoResource> {
        val p = current(id)
        return listOf(VideoResource(id, "原视频（外部引用）", p.bytes, false),
            VideoResource(id, "画面译文、字幕与观看进度", file(id).length() + File(root, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }, false),
            VideoResource(id, "可重建的识别分析缓存", File(root.parentFile, "video-analysis-cache/$id").walkTopDown().filter { it.isFile }.sumOf { it.length() }, true))
    }
    @Synchronized fun remove(id: String) {
        // Remove only explicitly owned files; never delete or release a potentially shared source URI.
        val owned = File(root, id).canonicalFile
        check(owned.parentFile == root.canonicalFile)
        check(!owned.exists() || owned.deleteRecursively()); check(!file(id).exists() || file(id).delete())
        mutable.value = mutable.value.filterNot { it.id == id }
    }
    private fun readAll(): List<VideoProject> = root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9-]{36}\\.json")) }
        .map { require(it.length() <= MAX_BYTES); decode(JSONObject(it.readText(Charsets.UTF_8))).also { p -> require(file(p.id).name == it.name) } }
    private fun write(p: VideoProject) {
        check(root.isDirectory || root.mkdirs())
        val text = encode(p).toString(); require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        decode(JSONObject(text))
        ExportFiles.commit(file(p.id)) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
    companion object {
        private const val MAX_BYTES = 16_000_000L
        fun encode(p: VideoProject) = JSONObject().put("schema", 1).put("id", p.id).put("uri", p.uri).put("name", p.name)
            .put("sha256", p.sha256).put("bytes", p.bytes).put("durationMs", p.durationMs).put("width", p.width).put("height", p.height)
            .put("rotation", p.rotation).put("positionMs", p.positionMs).put("source", p.source).put("target", p.target)
            .put("workflowId", p.workflowId).put("subtitleWorkflowId", p.subtitleWorkflowId).put("offsetMs", p.offsetMs)
            .put("revision", p.revision).put("subtitleIdentity", p.subtitleIdentity)
            .put("frameIdentity", p.frameIdentity)
            .put("cues", JSONArray(p.cues.map { c -> JSONObject().put("id", c.id).put("startMs", c.startMs).put("endMs", c.endMs)
                .put("source", c.source).put("translation", c.translation).put("manuallyEdited", c.manuallyEdited) }))
            .put("frames", JSONArray(p.frames.map { f -> JSONObject().put("atMs", f.atMs).put("untilMs", f.untilMs)
                .put("pageId", f.pageId).put("identity", f.identity) }))
        fun decode(j: JSONObject): VideoProject {
            require(j.getInt("schema") == 1)
            val cues = j.getJSONArray("cues"); val frames = j.getJSONArray("frames")
            require(cues.length() <= 20000 && frames.length() <= 10000)
            return VideoProject(j.getString("id"), j.getString("uri"), j.getString("name"), j.getString("sha256"), j.getLong("bytes"),
                j.getLong("durationMs"), j.getInt("width"), j.getInt("height"), j.getInt("rotation"), j.getLong("positionMs"),
                j.getString("source"), j.getString("target"), j.getString("workflowId"), j.getString("subtitleWorkflowId"), j.getLong("offsetMs"),
                (0 until cues.length()).map { i -> cues.getJSONObject(i).let { c -> VideoSubtitleCue(c.getString("id"), c.getLong("startMs"),
                    c.getLong("endMs"), c.getString("source"), c.getString("translation"), c.getBoolean("manuallyEdited")) } },
                (0 until frames.length()).map { i -> frames.getJSONObject(i).let { f -> VideoFrameResult(f.getLong("atMs"), f.getLong("untilMs"),
                    f.getString("pageId"), f.getString("identity")) } }, j.optString("subtitleIdentity").takeIf { it.isNotBlank() && it != "null" }, j.getString("revision"),
                j.optString("frameIdentity").takeIf { it.isNotBlank() && it != "null" }).also { p ->
                require(p.id.matches(Regex("[a-f0-9-]{36}")) && p.sha256.matches(Regex("[a-f0-9]{64}")))
                require(p.uri.startsWith("content://") || p.uri.startsWith("file://"))
                require(p.durationMs in 1..31536000000L && p.width in 1..16384 && p.height in 1..16384 && p.rotation in setOf(0, 90, 180, 270))
                require(p.positionMs in 0..p.durationMs && p.offsetMs in -3600000..3600000 && p.bytes >= 0 && p.name.length <= 500)
                LocalTranslationLanguage.fromTag(p.source); LocalTranslationLanguage.fromTag(p.target)
                require(p.cues.map { it.id }.distinct().size == p.cues.size && p.cues.all { it.endMs <= p.durationMs })
                require(p.frames.all { it.atMs >= 0 && it.untilMs > it.atMs && it.untilMs <= p.durationMs &&
                    it.pageId.startsWith("video:${p.id}:") && it.identity.matches(Regex("[a-f0-9]{64}")) })
            }
        }
    }
}
