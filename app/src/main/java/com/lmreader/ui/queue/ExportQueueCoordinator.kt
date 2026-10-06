package com.lmreader.ui.queue

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lmreader.core.storage.reader.PageSourceOpenResult
import com.lmreader.core.vision.BubbleMaskRenderer
import com.lmreader.core.vision.VisionImageDecoder
import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.reader.translation.decodePageAnalysisImage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class ExportTask(
    val id: String,
    val mangaId: String,
    val chapterId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val sourceTreeUri: String,
    val destinationTreeUri: String,
    val singleChapter: Boolean,
    val format: ExportFormat = ExportFormat.CBZ,
    val state: String = "PENDING",
    val completedPages: Int = 0,
    val totalPages: Int = 0,
    val outputUri: String? = null,
    val failure: String? = null,
    val temporaryUri: String? = null,
    val publicationName: String? = null,
    val publicationPhase: String? = null,
)

/** Durable snapshots and a publication journal make retries and process death recoverable. */
class ExportQueueCoordinator(private val container: AppContainer) {
    private val context = container.application
    private val scope = container.backgroundScope
    private val stateFile = File(context.filesDir, "export-queue.json")
    private val _storageFailure = MutableStateFlow<String?>(null)
    val storageFailure = _storageFailure.asStateFlow()
    private val staging = File(context.noBackupFilesDir, "export-staging").apply { mkdirs() }
    private val _tasks = MutableStateFlow(load())
    val tasks = _tasks.asStateFlow()
    private val _paused = MutableStateFlow(context.getSharedPreferences("export-settings", 0).getBoolean("queue-paused", false))
    val paused = _paused.asStateFlow()
    @Volatile private var runner: Job? = null

    @Volatile private var recovering = true
    private val recovery = scope.launch {
        try { container.startupReady.await() } catch (_: Exception) { return@launch }
        synchronized(this@ExportQueueCoordinator) {
            _paused.value = context.getSharedPreferences("export-settings", 0).getBoolean("queue-paused", false)
        }
        if (!update(_tasks.value)) return@launch
        for (task in _tasks.value) {
            runCatching { recoverPublication(task) }.onFailure { error ->
                change(task.id) { it.copy(state = if (it.state == "DONE") "DONE" else "INTERRUPTED",
                    failure = "临时文件恢复失败，请重新授权目标目录后重试：${error.message}") }
            }
            cleanCompleted(task.id)
        }
        recovering = false; start()
    }

    suspend fun enqueue(mangaId: String, chapterIds: List<String>): Int {
        check(_storageFailure.value == null) { _storageFailure.value!! }
        val target = container.mangaRepository.getBackfillTarget(mangaId) ?: error("漫画不存在")
        val destination = container.exportSettings.destination(target.manga.layoutMode)
            ?: error("请先在导出设置中选择${if (target.manga.layoutMode.name == "SINGLE_CHAPTER") "单章节" else "多章节"}路径")
        check(context.contentResolver.persistedUriPermissions.any { it.uri == destination && it.isWritePermission }) {
            "导出目录授权已失效，请在导出设置中重新选择"
        }
        val chapters = target.chapters.filter { it.chapterId in chapterIds }
        check(chapters.isNotEmpty()) { "没有可导出的章节" }
        val format = container.exportSettings.format.value
        val fresh = chapters.map { chapter ->
            ExportTask(UUID.randomUUID().toString(), mangaId, chapter.chapterId,
                target.manga.displayName, chapter.title, target.sourceTreeUri, destination.toString(),
                target.manga.layoutMode.name == "SINGLE_CHAPTER", format)
        }
        synchronized(this) {
            val existing = _tasks.value.filter { it.state in listOf("PENDING", "RUNNING", "PAUSED") }
                .map { it.chapterId }.toSet()
            val added = fresh.filterNot { it.chapterId in existing }
            val startNewBatch = added.isNotEmpty() && _tasks.value.isEmpty()
            check(update(_tasks.value + added.map { if (_paused.value && !startNewBatch) it.copy(state = "PAUSED") else it })) { _storageFailure.value!! }
            if (startNewBatch) {
                container.taskService.allowRetry()
                context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", false).commit()
                _paused.value = false
            }
            start()
            return added.size
        }
    }

    @Synchronized fun pauseAll() {
        _paused.value = true
        context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", true).commit()
        update(_tasks.value.map { if (it.state in listOf("PENDING", "RUNNING")) it.copy(state = "PAUSED") else it })
    }
    @Synchronized fun resumeAll() {
        container.taskService.allowRetry()
        if (!update(_tasks.value.map { if (it.state == "PAUSED") it.copy(state = "PENDING") else it })) return
        _paused.value = false
        context.getSharedPreferences("export-settings", 0).edit().putBoolean("queue-paused", false).commit()
        start()
    }
    @Synchronized fun pause(id: String) = change(id) { if (it.state in listOf("PENDING", "RUNNING")) it.copy(state = "PAUSED") else it }
    @Synchronized fun resume(id: String) { container.taskService.allowRetry(); change(id) { if (it.state == "PAUSED") it.copy(state = "PENDING") else it }; start() }
    @Synchronized fun retry(id: String) {
        container.taskService.allowRetry()
        change(id) { task -> if (task.state in listOf("FAILED", "INTERRUPTED")) {
            val rebound = if (task.publicationPhase == null) container.exportSettings.destination(
                if (task.singleChapter) com.lmreader.core.model.LayoutMode.SINGLE_CHAPTER else com.lmreader.core.model.LayoutMode.MULTI_CHAPTER)?.toString() else null
            task.copy(state = "PENDING", failure = null, destinationTreeUri = rebound ?: task.destinationTreeUri)
        } else task }; start()
    }
    @Synchronized fun cancel(id: String) {
        change(id) { if (it.state != "DONE") it.copy(state = "CANCELLED") else it }
        cleanCancelled(listOf(id))
    }
    @Synchronized fun pauseManga(mangaId: String) = update(_tasks.value.map {
        if (it.mangaId == mangaId && it.state in listOf("PENDING", "RUNNING")) it.copy(state = "PAUSED") else it
    })
    @Synchronized fun resumeManga(mangaId: String) {
        container.taskService.allowRetry()
        update(_tasks.value.map { if (it.mangaId == mangaId && it.state == "PAUSED") it.copy(state = "PENDING") else it })
        start()
    }
    @Synchronized fun cancelManga(mangaId: String) {
        val ids = _tasks.value.filter { it.mangaId == mangaId }.map { it.id }
        update(_tasks.value.map {
        if (it.mangaId == mangaId && it.state !in listOf("DONE", "CANCELLED")) it.copy(state = "CANCELLED") else it
        }); cleanCancelled(ids)
    }
    @Synchronized fun moveManga(mangaId: String, direction: Int) {
        val groups = _tasks.value.groupBy { it.mangaId }.toMutableMap()
        val ids = groups.keys.toMutableList()
        val from = ids.indexOf(mangaId)
        val to = from + direction
        if (from >= 0 && to in ids.indices) {
            ids.removeAt(from); ids.add(to, mangaId)
            update(ids.flatMap { groups.getValue(it) })
        }
    }
    @Synchronized fun move(id: String, direction: Int) {
        val rows = _tasks.value.toMutableList()
        val from = rows.indexOfFirst { it.id == id }
        val to = from + direction
        if (from >= 0 && to in rows.indices && rows[from].state != "RUNNING" && rows[from].mangaId == rows[to].mangaId) {
            val row = rows.removeAt(from); rows.add(to, row); update(rows)
        }
    }
    @Synchronized fun clearFinished() {
        val removed = _tasks.value.filter { it.state in listOf("DONE", "CANCELLED") && it.publicationPhase == null }
        update(_tasks.value - removed.toSet())
        scope.launch { runner?.join(); removed.forEach { ExportFiles.ownedRoot(staging, it.id).deleteRecursively() } }
    }
    private fun cleanCancelled(ids: List<String>) { scope.launch {
        runner?.join()
        ids.forEach { id -> live(id)?.takeIf { it.state == "CANCELLED" }?.let { task ->
            runCatching { recoverPublication(task) }.onFailure { error -> change(id) { it.copy(failure = "临时文件未清理：${error.message}") } }
            if (live(id)?.publicationPhase == null) ExportFiles.ownedRoot(staging, id).deleteRecursively()
        } }
    } }
    suspend fun pauseAndAwait() { pauseAll(); runner?.cancelAndJoin(); recovery.join() }
    suspend fun cleanTemporaryFiles() = withContext(Dispatchers.IO) {
        recovery.join()
        check(runner?.isActive != true) { "请先暂停导出队列，等待当前任务停止" }
        for (task in _tasks.value) {
            recoverPublication(task)
            cleanCompleted(task.id)
        }
    }

    @Synchronized private fun change(id: String, block: (ExportTask) -> ExportTask): Boolean =
        update(_tasks.value.map { if (it.id == id) block(it) else it })

    /** Keep the receipt until publication recovery and owned staging cleanup are complete. */
    @Synchronized private fun cleanCompleted(id: String) {
        val task = live(id) ?: return
        if (task.state !in listOf("DONE", "CANCELLED") || task.publicationPhase != null) return
        val root = ExportFiles.ownedRoot(staging, id)
        if (root.exists() && !root.deleteRecursively()) {
            change(id) { it.copy(failure = "导出临时快照未清理，请在导出设置中清理临时文件") }
            return
        }
        if (task.state == "DONE") update(_tasks.value.filterNot { it.id == id })
    }

    private fun requireChange(id: String, block: (ExportTask) -> ExportTask) {
        check(change(id, block)) { _storageFailure.value ?: "导出队列无法保存" }
    }
    @Synchronized private fun update(rows: List<ExportTask>): Boolean {
        if (_storageFailure.value != null) return false
        return try { save(rows); _tasks.value = rows; true }
        catch (error: Exception) {
            _storageFailure.value = "导出队列无法保存，请释放应用存储空间后重新打开：${error.message}"
            _paused.value = true
            false
        }
    }

    @Synchronized fun start() {
        if (recovering || _storageFailure.value != null || !container.taskService.canStart() || _paused.value || runner?.isActive == true || _tasks.value.none { it.state == "PENDING" }) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var lease: String? = null
            try {
            lease = container.taskService.acquire()
            while (!_paused.value) {
                val next = _tasks.value.firstOrNull { it.state == "PENDING" } ?: break
                process(next)
            }
            } catch (error: Exception) { pauseAll(); android.util.Log.e("ExportQueue", "后台任务停止", error) }
            finally { lease?.let(container.taskService::release) }
        }
        runner = job
        job.invokeOnCompletion { synchronized(this) { if (runner === job) runner = null; start() } }
        job.start()
    }

    private fun live(id: String) = _tasks.value.firstOrNull { it.id == id }
    private fun shouldContinue(id: String) = _storageFailure.value == null && !_paused.value && live(id)?.state == "RUNNING"

    internal suspend fun process(task: ExportTask) {
        synchronized(this) {
            if (_paused.value || live(task.id)?.state != "PENDING") return
            if (!change(task.id) { it.copy(state = "RUNNING", failure = null) }) return
        }
        val root = ExportFiles.ownedRoot(staging, task.id)
        try {
            recoverPublication(live(task.id)!!)
            if (live(task.id)?.state == "DONE" || !shouldContinue(task.id)) return
            val snapshot = prepareSnapshot(task, root)
            val originals = File(root, "originals")
            val rendered = File(root, "rendered").apply { mkdirs() }
            val artifacts = ReaderPageArtifactStore(File(root, "translations"))
            val pages = snapshot.getJSONArray("pages")
            requireChange(task.id) { it.copy(totalPages = pages.length()) }
            for (index in 0 until pages.length()) {
                checkRunning(task)
                val page = pages.getJSONObject(index)
                val original = File(originals, index.toString())
                check(ExportFiles.hash(original) == page.getString("hash")) { "原图快照损坏，请取消任务并重新入队" }
                val output = File(rendered, page.getString("output"))
                if (!ExportFiles.intact(output)) {
                    val saved = artifacts.load(page.getString("id"), page.getString("hash"))
                    check(!page.getBoolean("translated") || saved != null) { "译文快照损坏" }
                    ExportFiles.commit(output) { writePage(it, original, task.format, saved, page.getString("extension")) }
                    ExportFiles.receipt(output)
                }
                requireChange(task.id) { it.copy(completedPages = index + 1) }
                check(rendered.listFiles().orEmpty().sumOf { it.length() } <= 2_000_000_000L) { "章节导出超过 2 GB 上限" }
            }
            checkRunning(task)
            val output = if (task.format == ExportFormat.CBZ) File(root, "chapter.cbz").also { archive ->
                ExportFiles.commit(archive) { stream ->
                    // ZipOutputStream must finish before the owning stream is synced.
                    val shield = object : java.io.FilterOutputStream(stream) { override fun close() { flush() } }
                    ZipOutputStream(shield.buffered()).use { zip ->
                    for (index in 0 until pages.length()) {
                        checkRunning(task)
                        val filename = pages.getJSONObject(index).getString("output")
                        zip.putNextEntry(ZipEntry(filename))
                        File(rendered, filename).inputStream().use { copyLimited(it, zip, task) }
                        zip.closeEntry()
                    }
                    zip.finish(); zip.flush()
                    }
                }
                check(archive.length() <= 2_000_000_000L) { "章节导出文件超过 2 GB 上限" }
            } else rendered
            publish(task, output)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                change(task.id) { if (it.state == "RUNNING") it.copy(state = "INTERRUPTED", failure = "运行被中断，已保留快照，可手动重试") else it }
            }
            throw cancelled
        } catch (failure: Exception) {
            change(task.id) { if (it.state == "RUNNING") it.copy(state = "FAILED", failure = failure.message ?: "导出失败") else it }
        } finally {
            runCatching { recoverPublication(live(task.id) ?: task) }.onFailure { error ->
                change(task.id) { it.copy(failure = "临时文件未清理，请恢复目标目录授权后重试：${error.message}") }
            }
            cleanCompleted(task.id)
        }
    }

    private suspend fun prepareSnapshot(task: ExportTask, root: File): JSONObject {
        val marker = File(root, "snapshot.json")
        if (marker.isFile) return JSONObject(marker.readText()).also { require(it.getString("task") == task.id) }
        // An unfinished capture has never been used to publish output.
        root.deleteRecursively(); check(root.mkdirs()) { "无法创建导出快照" }
        val originals = File(root, "originals").apply { mkdirs() }
        val translations = File(root, "translations").apply { mkdirs() }
        val chapter = container.mangaRepository.getChapters(task.mangaId).firstOrNull { it.chapterId == task.chapterId }
            ?: error("章节已不存在")
        val opened = container.pageSourceFactory.open(task.sourceTreeUri, chapter)
        val source = (opened as? PageSourceOpenResult.Ready)?.source
            ?: error((opened as PageSourceOpenResult.Unsupported).reason)
        val pages = source.pages(); require(pages.isNotEmpty()) { "章节没有可导出的页面" }
        val captured = JSONArray()
        val capturedTranslations = container.localPageTranslator.pageWriteMutex.let { mutex ->
            mutex.lock()
            try { container.localPageTranslator.artifacts.snapshot(pages.map { it.pageId }, translations) }
            finally { mutex.unlock() }
        }
            var bytes = 0L
            for ((index, page) in pages.withIndex()) {
                checkRunning(task)
                val original = File(originals, index.toString())
                source.open(page).use { input -> ExportFiles.commit(original) { output -> copyLimited(input, output, task) } }
                bytes += original.length(); check(bytes <= 2_000_000_000L) { "章节原图快照超过 2 GB 上限" }
                val hash = ExportFiles.hash(original)
                val saved = capturedTranslations[page.pageId]
                check(saved == null || saved.sourceSha256 == hash) { "页面原图已变化：${page.displayName}" }
                val extension = page.displayName.substringAfterLast('.', "jpg").lowercase()
                    .takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "jpg"
                val outputExtension = if (task.format == ExportFormat.CBZ) { if (saved != null) "png" else extension } else task.format.extension
                captured.put(JSONObject().put("id", page.pageId).put("hash", hash).put("extension", extension)
                    .put("translated", saved != null).put("output", "%05d.%s".format(index + 1, outputExtension)))
            }
        val snapshot = JSONObject().put("task", task.id).put("pages", captured)
        ExportFiles.commit(marker) { it.write(snapshot.toString().toByteArray(Charsets.UTF_8)) }
        return snapshot
    }

    private fun writePage(output: java.io.OutputStream, original: File, format: ExportFormat,
        saved: com.lmreader.ui.reader.translation.ReaderPageTranslation?, extension: String) {
        val canCopyOriginal = format == ExportFormat.CBZ ||
            (format == ExportFormat.PNG && extension == "png") ||
            (format == ExportFormat.JPEG && extension in setOf("jpg", "jpeg"))
        if (canCopyOriginal && saved == null) { original.inputStream().use { it.copyTo(output) }; return }
        var bitmap = if (saved != null) decodePageAnalysisImage(context, original)
            else VisionImageDecoder.decode(context, Uri.fromFile(original), maxPixels = 16_000_000)
        try {
            if (saved != null && (bitmap.width != saved.width || bitmap.height != saved.height)) {
                val scaled = Bitmap.createScaledBitmap(bitmap, saved.width, saved.height, true)
                bitmap.recycle(); bitmap = scaled
            }
            val rendered = if (saved != null) BubbleMaskRenderer().render(bitmap, saved.regions, saved.renderSettings) else bitmap
            try {
                if (format == ExportFormat.JPEG) {
                    val opaque = Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
                    try {
                        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(rendered, 0f, 0f, null) }
                        check(opaque.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "JPEG 编码失败" }
                    } finally { opaque.recycle() }
                } else check(rendered.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            } finally { if (rendered !== bitmap) rendered.recycle() }
        } finally { bitmap.recycle() }
    }

    private fun checkRunning(task: ExportTask) {
        if (!shouldContinue(task.id) || Thread.currentThread().isInterrupted) throw IOException("导出已暂停或取消")
    }
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, task: ExportTask) {
        val buffer = ByteArray(64 * 1024); var total = 0L
        while (true) {
            checkRunning(task)
            val count = input.read(buffer); if (count < 0) break
            total += count; if (total > 64_000_000) throw IOException("单页超过 64 MB 导出上限")
            output.write(buffer, 0, count)
        }
    }

    private fun publish(task: ExportTask, temporary: File) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(task.destinationTreeUri)) ?: error("无法打开导出目录")
        check(tree.canWrite()) { "导出目录授权已失效，请重新选择目录" }
        val base = safeName(if (task.singleChapter) task.mangaTitle else "${task.mangaTitle} - ${task.chapterTitle}")
        var filename = if (task.format == ExportFormat.CBZ) "$base.cbz" else base
        var serial = 2
        while (tree.findFile(filename) != null) { filename = if (task.format == ExportFormat.CBZ) "$base ($serial).cbz" else "$base ($serial)"; serial++ }
        checkRunning(task)
        // Reserve the deterministic token BEFORE creating the remote file. Even a crash
        // between createDocument and its return can be recovered by this exact name.
        requireChange(task.id) { it.copy(publicationName = filename, publicationPhase = "COPYING") }
        val token = ".lmreader-${task.id}.part"
        val output = (if (task.format == ExportFormat.CBZ) tree.createFile("application/octet-stream", token)
            else tree.createDirectory(token)) ?: error("无法创建导出临时文件")
        requireChange(task.id) { it.copy(temporaryUri = output.uri.toString()) }
        if (task.format == ExportFormat.CBZ) copyPublished(task, temporary, output)
        else temporary.listFiles().orEmpty().filterNot { it.name.endsWith(".sha256") }.sortedBy { it.name }.forEach { page ->
            checkRunning(task)
            val child = output.createFile(if (task.format == ExportFormat.PNG) "image/png" else "image/jpeg", page.name)
                ?: error("无法创建导出图片")
            copyPublished(task, page, child)
        }
        check(verifyPublished(task, output)) { "目标文件校验失败，可能空间不足或提供方写入不完整" }
        requireChange(task.id) { it.copy(publicationPhase = "READY") }
        synchronized(this) {
            checkRunning(task)
            // No UI control may interleave rename and the durable DONE receipt.
            check(tree.findFile(filename) == null) { "导出目标名称已被占用，请重试" }
            check(output.renameTo(filename)) { "目标目录不支持完成后重命名，请更换导出目录" }
            requireChange(task.id) { it.copy(state = "DONE", outputUri = output.uri.toString(),
                temporaryUri = null, publicationPhase = null, publicationName = null, failure = null) }
        }
    }
    private fun copyPublished(task: ExportTask, sourceFile: File, targetFile: DocumentFile) {
        context.contentResolver.openOutputStream(targetFile.uri, "wt")?.use { target ->
            sourceFile.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) { checkRunning(task); val count = source.read(buffer); if (count < 0) break; target.write(buffer, 0, count) }
            }
            target.flush()
        } ?: error("无法写入导出文件")
    }
    private fun matches(file: DocumentFile, expected: File): Boolean {
        if (!expected.isFile) return false
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        context.contentResolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; size += count
                if (size > expected.length()) return false; digest.update(buffer, 0, count) }
        } ?: return false
        return size == expected.length() && digest.digest().joinToString("") { "%02x".format(it) } == ExportFiles.hash(expected)
    }
    private fun verifyPublished(task: ExportTask, output: DocumentFile): Boolean {
        val root = ExportFiles.ownedRoot(staging, task.id)
        if (task.format == ExportFormat.CBZ) return matches(output, File(root, "chapter.cbz"))
        val expected = File(root, "rendered").listFiles().orEmpty().filterNot { it.name.endsWith(".sha256") || it.name.endsWith(".part") }
        val actual = output.listFiles().associateBy { it.name }
        return expected.isNotEmpty() && actual.size == expected.size && expected.all { file -> actual[file.name]?.let { matches(it, file) } == true }
    }
    private fun recoverPublication(task: ExportTask) {
        if (task.publicationPhase == null) return
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(task.destinationTreeUri)) ?: error("无法打开目标目录")
        check(tree.canWrite()) { "导出目录授权已失效" }
        val candidates = tree.listFiles()
        val output = task.temporaryUri?.let { uri -> candidates.firstOrNull { it.uri.toString() == uri } }
            ?: candidates.firstOrNull { it.name == ".lmreader-${task.id}.part" }
        val final = task.publicationName?.let { name -> candidates.firstOrNull { it.name == name } }
        // READY is written before rename. Providers may change the URI on rename.
        if (task.publicationPhase == "READY" && final != null && verifyPublished(task, final) &&
            (output == null || output.uri == final.uri)) {
            requireChange(task.id) { it.copy(state = "DONE", outputUri = final.uri.toString(), temporaryUri = null,
                publicationPhase = null, publicationName = null, failure = null) }
            return
        }
        if (output != null) {
            // Never delete a conflicting user file under the reserved final name.
            check(output.name == ".lmreader-${task.id}.part") { "临时文件名称已变化，保留供人工检查" }
            check(output.delete()) { "无法清理临时文件：${output.uri}" }
        }
        change(task.id) { it.copy(temporaryUri = null, publicationPhase = null, publicationName = null) }
    }

    private fun safeName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim(' ', '.').take(100).ifBlank { "未命名" }

    private fun save(rows: List<ExportTask>) {
        val json = JSONArray().apply { rows.forEach { row -> put(JSONObject()
            .put("id", row.id).put("mangaId", row.mangaId).put("chapterId", row.chapterId)
            .put("mangaTitle", row.mangaTitle).put("chapterTitle", row.chapterTitle)
            .put("sourceTreeUri", row.sourceTreeUri).put("destinationTreeUri", row.destinationTreeUri)
            .put("singleChapter", row.singleChapter).put("format", row.format.name).put("state", row.state)
            .put("completedPages", row.completedPages).put("totalPages", row.totalPages)
            .put("outputUri", row.outputUri).put("failure", row.failure)
            .put("temporaryUri", row.temporaryUri).put("publicationName", row.publicationName).put("publicationPhase", row.publicationPhase)) } }
        // AtomicFile.finishWrite only logs some commit failures. A publication
        // receipt must report failure before the next external mutation begins.
        ExportFiles.commit(stateFile) { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun load(): List<ExportTask> = runCatching {
        if (!stateFile.exists() && !File(stateFile.path + ".bak").exists()) return emptyList()
        require(stateFile.length() <= 32_000_000) { "队列文件过大" }
        val file = android.util.AtomicFile(stateFile)
        val json = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        require(json.length() <= 10_000) { "队列任务过多" }
        (0 until json.length()).map { index -> json.getJSONObject(index).let { item ->
            ExportTask(item.getString("id"), item.getString("mangaId"), item.getString("chapterId"),
                item.getString("mangaTitle"), item.getString("chapterTitle"), item.getString("sourceTreeUri"),
                item.getString("destinationTreeUri"), item.getBoolean("singleChapter"),
                ExportFormat.valueOf(item.optString("format", "CBZ")),
                item.getString("state").let { if (it == "RUNNING") "INTERRUPTED" else it },
                item.getInt("completedPages"), item.getInt("totalPages"),
                item.optString("outputUri").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("failure").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("temporaryUri").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("publicationName").takeIf { it.isNotEmpty() && it != "null" },
                item.optString("publicationPhase").takeIf { it.isNotEmpty() && it != "null" }).also { task ->
                    require(task.id.matches(Regex("[a-fA-F0-9-]{36}")) && task.completedPages >= 0 && task.totalPages >= 0)
                    require(task.state in setOf("PENDING", "RUNNING", "PAUSED", "FAILED", "INTERRUPTED", "DONE", "CANCELLED"))
                    require(task.publicationPhase == null || task.publicationPhase in setOf("COPYING", "READY"))
                }
        } }
    }.getOrElse { error -> _storageFailure.value = "导出队列文件损坏，原文件已保留，请保留文件供排查：${error.message}"; emptyList() }
}
