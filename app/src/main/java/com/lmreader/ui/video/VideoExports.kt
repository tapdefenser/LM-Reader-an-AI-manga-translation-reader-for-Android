package com.lmreader.ui.video

import android.net.Uri
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.lmreader.core.model.*
import com.lmreader.di.AppContainer
import com.lmreader.tasks.*
import com.lmreader.ui.queue.ExportFiles
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Immutable revision snapshots are captured at the export click, then published by the task host. */
class VideoExports(private val container: AppContainer) {
    private val root = File(container.application.noBackupFilesDir, "video-export-staging")
    init { container.taskScheduler.register("VIDEO_EXPORT") { execute(it) } }
    suspend fun submit(id: String, kind: String, bilingual: Boolean = false): String {
        require(kind in setOf("SRT", "BUNDLE"))
        val project = container.videoTasks.existingProject(id) ?: error("这个视频尚无正式成果")
        val key = UUID.randomUUID().toString()
        val staging = ExportFiles.ownedRoot(root, key).apply { check(mkdirs() || isDirectory) }
        val globalStyle = container.preferences.videoSubtitleStyle.first()
        try {
            container.videoTasks.snapshot(id) { container.workDatabase.withTransaction {
                val dao = container.videoResults.results()
                val frames = dao.frames(id, project.sha256)
                val cues = dao.cues(id).filter { it.sourceHash == project.sha256 }
                if(kind == "SRT") require(cues.any { !it.deleted }) { "当前没有可导出的字幕" }
                val regions = dao.regions(id, project.sha256)
                val style = resolveVideoSubtitleStyle(globalStyle, dao.settings(id)?.style ?: "{}").put("overrideStyle", true)
                val pageIds = (frames.map { it.pageId } + regions.map { it.pageId }).distinct()
                val captured = if(kind == "BUNDLE") container.videoLibrary.artifacts(id).snapshot(pageIds, File(staging, "pages")) else emptyMap()
                if(kind == "BUNDLE") for(pageId in pageIds) {
                    val proof = File(container.videoLibrary.proofs(id), "${VideoTasks.hash(pageId)}.json")
                    if(proof.isFile) { val dest = File(staging, "proofs/${proof.name}"); dest.parentFile!!.mkdirs(); proof.copyTo(dest) }
                }
                val manifest = JSONObject().put("format", "LMReaderVideoResults").put("schema", 1).put("videoId", id)
                    .put("sourceSha256", project.sha256).put("sourceName", project.name).put("durationMs", project.durationMs)
                    .put("width", project.width).put("height", project.height).put("rotation", project.rotation)
                    .put("style", style).put("configuration", JSONObject(dao.settings(id)?.json ?: "{}").apply { remove("audioApiEndpoint"); remove("audioApiProfileId") })
                    .put("cues", JSONArray(cues.map { c -> JSONObject().put("cueId", c.cueId).put("trackId", c.trackId).put("startMs", c.startMs)
                        .put("endMs", c.endMs).put("source", c.source).put("translation", c.translation).put("deleted", c.deleted)
                        .put("manuallyEdited", c.manuallyEdited).put("revision", c.revision).put("identity", c.identity) }))
                    .put("frames", JSONArray(frames.map { f -> JSONObject().put("identity", f.identity).put("sampleIndex", f.sampleIndex)
                        .put("targetUs", f.targetUs).put("ptsUs", f.ptsUs).put("untilUs", f.untilUs).put("pageId", f.pageId)
                        .put("resultRevision", captured[f.pageId]?.revision ?: f.resultRevision).put("noText", f.noText) }))
                    .put("regions", JSONArray(regions.map { r -> JSONObject().put("intervalId", r.intervalId).put("pageId", r.pageId).put("regionId", r.regionId)
                        .put("startMs", r.startMs).put("endMs", r.endMs).put("observedStartMs", r.observedStartMs).put("observedEndMs", r.observedEndMs)
                        .put("manualTiming", r.manualTiming).put("deleted", r.deleted).put("revision", r.revision).put("trackId", r.trackId) }))
                ExportFiles.commit(File(staging, "manifest.json")) { it.write(manifest.toString().toByteArray()) }
            } }
            return container.taskScheduler.submit("VIDEO_EXPORT", "导出${if(kind == "SRT") "字幕" else "可恢复成果包"} · ${project.name}",
                "video-export:$id", "video-export:$key", JSONObject().put("snapshot", key).put("kind", kind).put("bilingual", bilingual).toString()).taskId
        } catch(e: Throwable) { staging.deleteRecursively(); throw e }
    }
    private suspend fun execute(execution: TaskExecution) {
        val payload = JSONObject(execution.payload); val staging = ExportFiles.ownedRoot(root, payload.getString("snapshot"))
        val manifestFile = File(staging, "manifest.json")
        require(manifestFile.isFile && manifestFile.length() <= 64_000_000) { "导出快照缺失或过大，请重新创建导出" }
        val manifest = JSONObject(manifestFile.readText())
        val srt = payload.getString("kind") == "SRT"
        val destination = File(container.application.cacheDir, "video-exports/${execution.taskId}.${if(srt) "srt" else "lmvideo.zip"}")
        check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
        ResourceGovernor.shared.use("io") { execution.publish {
            ExportFiles.commit(destination) { output ->
                if(srt) {
                    val items = manifest.getJSONArray("cues")
                    val track = manifest.getJSONObject("style").optString("trackId", "all")
                    val cues = (0 until items.length()).map { items.getJSONObject(it) }.filter { !it.getBoolean("deleted") && (track == "all" || it.getString("trackId") == track) }.map { c ->
                        VideoSubtitleCue(c.getString("cueId"), c.getLong("startMs"), c.getLong("endMs"), c.getString("source"), c.getString("translation"), c.getBoolean("manuallyEdited"))
                    }
                    output.write(VideoSubtitles.encode(cues, if(payload.optBoolean("bilingual")) VideoSubtitleDisplay.BILINGUAL else VideoSubtitleDisplay.TRANSLATED,
                        manifest.getJSONObject("style").optLong("offsetMs")).toByteArray())
                } else {
                    val shield = object : java.io.FilterOutputStream(output) { override fun close() { flush() } }
                    ZipOutputStream(shield).use { zip ->
                        for(file in staging.walkTopDown().filter { it.isFile }) {
                            val path = file.relativeTo(staging).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                        zip.finish()
                    }
                }
            }
        } }
        val uri = container.videoResultUri(destination)
        execution.progress(1, 1, "导出完成", JSONObject().put("outputUri", uri.toString()).put("mime", if(srt) "application/x-subrip" else "application/zip").toString())
    }
}
