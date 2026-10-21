package com.lmreader.ui.video

import android.content.Intent
import android.net.Uri
import androidx.room.withTransaction
import com.lmreader.core.database.media.*
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.di.AppContainer
import com.lmreader.tasks.TaskExecution
import com.lmreader.tasks.TaskWaiting
import com.lmreader.ui.queue.ExportFiles
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.ReaderPageTranslation
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/** Portable formal results only. Import never executes a workflow or rebinds source media. */
class VideoResultImports(private val container: AppContainer) {
    private val root = File(container.application.noBackupFilesDir, "video-result-imports")
    init { container.taskScheduler.register("VIDEO_IMPORT") { execute(it) } }

    suspend fun submit(id: String, uri: Uri): String {
        runCatching { container.application.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val video = requireNotNull(container.videoCatalog.find(id))
        val epoch = JSONObject(container.videoResults.results().settings(id)?.json ?: "{}").optString("resultEpoch")
        val payload = JSONObject().put("videoId", id).put("uri", video.uri).put("sourceRevision", video.sourceRevision)
            .put("resultEpoch", epoch).put("packageUri", uri.toString())
        return container.taskScheduler.submit("VIDEO_IMPORT", "导入成果包 · ${video.name}", "video:$id:import",
            "video-import:$id:${VideoTasks.hash(uri.toString())}", payload.toString()).taskId
    }

    private suspend fun execute(execution: TaskExecution) {
        if(JSONObject(execution.initial.checkpoint).optBoolean("imported")) return
        val payload = JSONObject(execution.payload)
        val id = payload.getString("videoId")
        container.videoTasks.validateSource(payload)
        val project = container.videoTasks.ensureProject(id)
        val staging = ExportFiles.ownedRoot(root, execution.taskId)
        val manifestFile = File(staging, "manifest.json")
        ResourceGovernor.shared.use("io") {
            if(!File(staging, "ready").isFile) {
                check(!staging.exists() || staging.deleteRecursively())
                check(staging.mkdirs())
                try {
                    container.application.contentResolver.openInputStream(Uri.parse(payload.getString("packageUri")))?.use { unpack(it, staging) }
                        ?: throw TaskWaiting("ACCESS", "成果包读取授权已失效，请重新选择成果包")
                    ExportFiles.commit(File(staging, "ready")) { it.write(1) }
                } catch(error: Throwable) { staging.deleteRecursively(); throw error }
            }
        }
        require(manifestFile.length() in 1..16_000_000) { "成果包清单缺失或过大" }
        val manifest = JSONObject(manifestFile.readText())
        require(manifest.getString("format") == "LMReaderVideoResults" && manifest.getInt("schema") == 1) { "不支持的成果包版本" }
        require(manifest.getString("sourceSha256") == project.sha256 && manifest.getLong("durationMs") == project.durationMs) {
            "成果包与原视频内容不匹配，请先在信息中定位对应原视频"
        }
        val oldId = UUID.fromString(manifest.getString("videoId")).toString()
        fun pageId(old: String): String {
            require(old.startsWith("video:$oldId:") && old.length <= 2000) { "成果包画面编号不匹配" }
            return "video:$id:" + old.removePrefix("video:$oldId:")
        }
        fun mapped(key: String) = if(oldId == id) key else VideoTasks.stableId("$id:import:$key")
        val frames = rows(manifest.getJSONArray("frames"))
        val regions = rows(manifest.getJSONArray("regions"))
        val cues = rows(manifest.getJSONArray("cues"))
        val sourceStore = ReaderPageArtifactStore(File(staging, "pages"))
        val oldPages = (frames.map { it.getString("pageId") } + regions.map { it.getString("pageId") }).distinct()
        val pages = oldPages.associateWith { old ->
            pageId(old)
            sourceStore.current(old) ?: error("成果包缺少正式画面数据")
        }
        // Validate the entire package before publishing anything.
        frames.forEach { f ->
            val saved = pages.getValue(f.getString("pageId"))
            require(f.getLong("ptsUs") >= 0 && f.getLong("ptsUs") < project.durationMs * 1000 &&
                f.getLong("untilUs") > f.getLong("ptsUs") && f.getLong("untilUs") <= project.durationMs * 1000)
            require(f.getLong("targetUs") in 0 until project.durationMs * 1000)
            require(saved.revision == f.getString("resultRevision")) { "画面成果版本与清单不匹配" }
            require(f.getString("identity").matches(SHA) && (saved.resultIdentity == null || saved.resultIdentity == f.getString("identity")))
        }
        regions.forEach { r ->
            val saved = pages.getValue(r.getString("pageId"))
            time(r, project.durationMs)
            require(r.getLong("observedStartMs") >= 0 && r.getLong("observedEndMs") > r.getLong("observedStartMs") && r.getLong("observedEndMs") <= project.durationMs)
            listOf("intervalId", "regionId", "revision", "trackId").forEach { require(r.getString(it).length in 1..500) }
            retargetVideoRegionId(r.getString("regionId"), r.getString("pageId"), pageId(r.getString("pageId")))
            require(r.getBoolean("deleted") || saved.regions.any { it.region.id == r.getString("regionId") })
        }
        cues.forEach { c ->
            time(c, project.durationMs)
            listOf("cueId", "trackId", "revision").forEach { require(c.getString(it).length in 1..500) }
            require(c.getString("source").length <= 20_000 && c.getString("translation").length <= 20_000)
            c.getBoolean("deleted"); c.getBoolean("manuallyEdited")
        }
        require(regions.map { it.getString("intervalId") }.distinct().size == regions.size)
        require(cues.map { it.getString("cueId") }.distinct().size == cues.size)
        val style = manifest.getJSONObject("style")
        require(style.toString().length <= 16_000)
        val dao = container.videoResults.results()
        container.videoTasks.withEditBarrier(id) {
            val published = mutableMapOf<String, ReaderPageTranslation>()
            val protectedPages = mutableSetOf<String>()
            val destination = container.videoLibrary.artifacts(id)
            ResourceGovernor.shared.use("io") {
            for((index, old) in oldPages.withIndex()) {
                val incoming = pages.getValue(old)
                val nextId = pageId(old)
                val nextRegions = retargetVideoRegions(incoming.regions, old, nextId)
                val publicationContext = currentCoroutineContext()
                execution.publish {
                    container.videoTasks.validateSource(payload)
                    // Local revisions, including deletions and edits, always win a merge.
                    val existing = destination.current(nextId)
                    val intervals = dao.pageRegions(nextId)
                    val timingsChanged = intervals.any { local -> (local.manualTiming || local.deleted) &&
                        regions.none { mapped(it.getString("intervalId")) == local.intervalId && it.getString("revision") == local.revision } }
                    if(existing != null && (timingsChanged || existing.manuallyEdited &&
                        (existing.producerTaskId != execution.taskId || existing.regions != nextRegions))) protectedPages += old
                    published[old] = existing ?: destination.save(nextId, incoming.sourceSha256, incoming.source, incoming.target,
                        incoming.width, incoming.height, nextRegions, incoming.modelPacks, incoming.elapsedMillis, incoming.renderSettings,
                        requireAbsent = true, resultIdentity = incoming.resultIdentity, manuallyEdited = incoming.manuallyEdited,
                        producerTaskId = execution.taskId, checkCancelled = { publicationContext.ensureActive() })
                    val proof = File(staging, "proofs/${VideoTasks.hash(old)}.json")
                    if(proof.isFile) {
                        val sourceProof = requireNotNull(VideoFrameProof.load(proof.parentFile!!, old))
                        val target = File(container.videoLibrary.proofs(id), "${VideoTasks.hash(nextId)}.json")
                        if(!target.exists()) sourceProof.retarget(old, nextId).save(container.videoLibrary.proofs(id), nextId)
                    }
                }
                execution.progress(index + 1L, oldPages.size + 1L, "正在合并画面成果")
            }
            }
            execution.publish { container.workDatabase.withTransaction {
                container.videoTasks.validateSource(payload)
                for(f in frames) {
                    if(f.getString("pageId") in protectedPages) continue
                    val saved = published.getValue(f.getString("pageId"))
                    val identity = f.getString("identity")
                    if(saved.resultIdentity != null && saved.resultIdentity != identity) continue
                    if(dao.sample(id, project.sha256, identity, f.getLong("sampleIndex")) == null)
                        dao.putFrame(VideoFrameIndex(id, project.sha256, identity, f.getLong("sampleIndex"), f.getLong("targetUs"), f.getLong("ptsUs"),
                            f.getLong("untilUs"), saved.pageId, saved.revision, f.getBoolean("noText"), execution.taskId))
                }
                for(r in regions) if(r.getString("pageId") !in protectedPages) dao.addRegion(VideoRegionInterval(mapped(r.getString("intervalId")), id, project.sha256, pageId(r.getString("pageId")),
                    retargetVideoRegionId(r.getString("regionId"), r.getString("pageId"), pageId(r.getString("pageId"))), r.getLong("startMs"), r.getLong("endMs"), r.getLong("observedStartMs"), r.getLong("observedEndMs"),
                    r.getBoolean("manualTiming"), r.getBoolean("deleted"), r.getString("revision"), mapped(r.getString("trackId"))))
                for(c in cues) dao.addCue(VideoCueRecord(id, c.getString("cueId"), project.sha256, c.getString("trackId"), c.getLong("startMs"), c.getLong("endMs"),
                    c.getString("source"), c.getString("translation"), c.getBoolean("deleted"), c.getBoolean("manuallyEdited"), c.getString("revision"),
                    c.optString("identity").takeUnless { it.isBlank() || it == "null" }, execution.taskId))
                val settings = dao.settings(id) ?: VideoSettingsRecord(id)
                if(JSONObject(settings.style).length() == 0) dao.putSettings(settings.copy(style = style.put("overrideStyle", true).toString(), revision = UUID.randomUUID().toString()))
                container.workDatabase.videoCatalogDao().addToShelf(id)
            } }
            execution.progress(oldPages.size + 1L, oldPages.size + 1L, "成果已合并；本机已有修订保留", JSONObject().put("imported", true).toString())
        }
        staging.deleteRecursively()
    }

    private suspend fun unpack(input: java.io.InputStream, staging: File) {
        val seen = mutableSetOf<String>(); var total = 0L
        ZipInputStream(input).use { zip ->
            while(true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && seen.add(entry.name) && seen.size <= 100_001 &&
                    (entry.name == "manifest.json" || Regex("(pages|proofs)/[a-f0-9]{64}\\.json").matches(entry.name))) { "成果包包含无效或重复文件" }
                val file = File(staging, entry.name)
                require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator))
                file.parentFile!!.mkdirs()
                file.outputStream().use { out ->
                    val buffer = ByteArray(65_536); var size = 0L
                    while(true) { currentCoroutineContext().ensureActive(); val n = zip.read(buffer); if(n < 0) break
                        size += n; total += n
                        require(size <= 64_000_000 && total <= 512_000_000) { "成果包展开后过大" }
                        out.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        require("manifest.json" in seen)
    }
    private fun rows(array: JSONArray): List<JSONObject> { require(array.length() <= 100_000); return List(array.length()) { array.getJSONObject(it) } }
    private fun time(row: JSONObject, duration: Long) { require(row.getLong("startMs") >= 0 && row.getLong("endMs") > row.getLong("startMs") && row.getLong("endMs") <= duration) }
    companion object { private val SHA = Regex("[a-f0-9]{64}") }
}
