package com.lmreader.ui.settings.backup

import android.net.Uri
import androidx.room.withTransaction
import com.lmreader.core.api.ApiProfileCodec
import com.lmreader.core.api.ApiProtocol
import com.lmreader.core.model.*
import com.lmreader.core.storage.settings.*
import com.lmreader.di.AppContainer
import com.lmreader.ui.queue.ExportFiles
import com.lmreader.ui.queue.ExportFormat
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import com.lmreader.ui.workflow.TranslationWorkflowStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AppBackupManager(private val container: AppContainer) {
    private val context = container.application
    private val mutex = Mutex()
    private val prefs = PreferenceBackup(context)
    private val journal = File(context.noBackupFilesDir, "restore-pending")
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failure = MutableStateFlow<String?>(null)
    val failure = _failure.asStateFlow()
    private val _recoveryRequired = MutableStateFlow(false)
    val recoveryRequired = _recoveryRequired.asStateFlow()
    private fun scratch() = File(context.noBackupFilesDir, "backup-${UUID.randomUUID()}").apply { mkdirs() }

    suspend fun create(uri: Uri) = operation {
        val root = scratch()
        try {
            container.localPageTranslator.pageWriteMutex.withLock { snapshot(root, secrets = false) }
            val archive = File(context.noBackupFilesDir, "backup-output-${UUID.randomUUID()}.zip")
            try {
                archive.outputStream().use { BackupArchive.write(root, it) }
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    archive.inputStream().use { it.copyTo(output) }; output.flush()
                } ?: error("无法写入备份文件")
            } finally { archive.delete() }
        } finally { root.deleteRecursively() }
    }

    suspend fun restore(uri: Uri) = operation {
        val incoming = scratch()
        try {
            context.contentResolver.openInputStream(uri)?.use { BackupArchive.read(it, incoming) } ?: error("无法读取备份")
            validate(incoming)
            container.taskScheduler.dao.all().filter { it.state !in com.lmreader.tasks.TaskScheduler.TERMINAL }
                .forEach { container.taskScheduler.pause(it.taskId, pauseReason = "RESTORE") }
            // Do not touch the destination until the entire package is validated.
            container.translationQueue.pause()
            container.exportQueue.pauseAll()
            container.taskService.cancelAndAwait()
            container.translationQueue.pauseAndAwait()
            container.exportQueue.pauseAndAwait()
            container.scanCoordinator.cancelAll().join()
            container.screenTranslation.stopAndAwait()
            container.videoSessions.stopAndAwait()
            container.localPageTranslator.pageWriteMutex.withLock {
                check(!journal.exists()) { "上次恢复尚未完成，请先重新打开应用恢复本机数据" }
                val rollback = scratch()
                try {
                    snapshot(rollback, secrets = true)
                    rollback.walkTopDown().filter { it.isFile }.forEach { file -> java.io.FileOutputStream(file, true).use { it.fd.sync() } }
                    // A synced pointer appears only after a complete rollback snapshot exists.
                    ExportFiles.commit(journal) { it.write(rollback.name.toByteArray(Charsets.UTF_8)) }
                    try { apply(incoming, restored = true) }
                    catch (error: Exception) {
                        withContext(NonCancellable) {
                            try { apply(rollback, restored = false); check(journal.delete()); rollback.deleteRecursively() }
                            catch (rollbackError: Exception) { error.addSuppressed(rollbackError); _failure.value = "恢复失败，本机回滚副本已保留，重新打开应用后继续恢复" }
                        }
                        throw error
                    }
                    check(journal.delete()) { "恢复已写入，但回滚日志未提交，请重新打开应用" }
                    rollback.deleteRecursively()
                } finally { if (!journal.exists()) rollback.deleteRecursively() }
            }
            reload()
        } finally { incoming.deleteRecursively() }
    }

    /** Called before startup maintenance and queue observers are allowed to run. */
    suspend fun recoverPendingRestore() = withContext(Dispatchers.IO) {
        if (!journal.isFile) return@withContext
        try {
            val name = journal.readText()
            require(name.matches(Regex("backup-[a-f0-9-]{36}")))
            val root = File(context.noBackupFilesDir, name)
            validate(root, requireArtifacts = false, strictMedia = false)
            apply(root, restored = false)
            check(journal.delete()); root.deleteRecursively(); reload()
        } catch (error: Exception) { _failure.value = "本机恢复未完成：${error.message}"; throw error }
        finally { _recoveryRequired.value = journal.exists() }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            container.startupReady.await()
            _busy.value = true; _failure.value = null
            try { container.taskScheduler.withBarrier { block() } } finally {
                // A failed rollback must also block the current session, before reopening.
                _recoveryRequired.value = journal.exists()
                _busy.value = false
            }
        }
    }
    private suspend fun snapshot(root: File, secrets: Boolean) {
        container.database.withTransaction { BackupDatabase.snapshot(container.database.openHelper.writableDatabase, root, secrets) }
        container.workDatabase.withTransaction {
            MediaTaskBackup.snapshot(container.workDatabase.openHelper.writableDatabase, root, secrets)
        }
        File(root, "preferences.json").writeText(prefs.snapshot().toString())
        val profiles = container.apiProfiles.profiles.first().map { if (secrets) it else it.copy(apiKey = "") }
        File(root, "api.json").writeText(ApiProfileCodec.encode(profiles))
        val source = container.translationSources.settings.first()
        val settings = JSONObject().put("general", JSONObject(context.getSharedPreferences("general-settings", 0).all))
            .put("export", JSONObject(context.getSharedPreferences("export-settings", 0).all))
            .put("source", JSONObject().put("source", source.source.name).put("base", source.customBaseUrl).put("wifi", source.wifiOnly))
            .put("local-asr-settings", asrSettingsSnapshot())
            .put("video-player", videoPlayerSettingsSnapshot())
            .put("unified-task-settings", unifiedTaskSettingsSnapshot())
        File(root, "settings.json").writeText(settings.toString())
        container.translationWorkflows // Ensure persisted data has been loaded and validated.
        val workflows = File(context.filesDir, "translation-workflows/workflows.json")
        File(root, "workflows.json").writeText(if (workflows.isFile) workflows.readText() else "{\"schema\":2,\"workflows\":[]}")
        val order = File(context.filesDir, "translation-queue-order.json")
        File(root, "queue-order.json").writeText(if (order.isFile) order.readText() else "{\"manga\":[],\"chapters\":{},\"paused\":true}")
        val store = container.localPageTranslator.artifacts
        store.migrateLegacy()
        val pages = File(root, "pages").apply { mkdirs() }
        store.root.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            .forEach { it.copyTo(File(pages, it.name)) }
        container.videoLibrary.snapshot(File(root, "videos"))
        File(root, "video-library.json").writeText("{\"schema\":1}")
        // A user-visible new backup must be restorable; the local rollback copy must be exact
        // even if the current session already has an orphaned index row or an odd local state.
        validate(root, requireArtifacts = !secrets, strictMedia = !secrets)
    }
    private fun source(json: JSONObject) = TranslationSourceSettings(
        TranslationDownloadSource.valueOf(json.getString("source")), json.getString("base"), json.getBoolean("wifi"))

    private fun validate(root: File, requireArtifacts: Boolean = true, strictMedia: Boolean = true) {
        BackupDatabase.validate(container.database.openHelper.writableDatabase, root)
        prefs.validate(JSONObject(File(root, "preferences.json").readText()))
        val api = ApiProfileCodec.decode(File(root, "api.json").readText())
        require(api.map { it.id }.distinct().size == api.size); api.forEach(ApiProtocol::validate)
        val settings = JSONObject(File(root, "settings.json").readText())
        require(settings.keys().asSequence().all { it in SETTINGS_SECTIONS }) { "备份包含未知设置区段" }
        val general = settings.getJSONObject("general")
        require(general.keys().asSequence().all { it in setOf("theme", "language") && general.get(it) is String })
        AppThemeMode.valueOf(general.optString("theme", "SYSTEM"))
        require(general.optString("language").let { it.isEmpty() || it in setOf("zh-Hans", "en") })
        val export = settings.getJSONObject("export")
        require(export.keys().asSequence().all { key ->
            if (key == "queue-paused") export.get(key) is Boolean
            else key in setOf("multi", "single", "format", "previous-multi", "previous-single") && export.get(key) is String
        })
        ExportFormat.valueOf(export.optString("format", "CBZ"))
        validateOptionalSettings(settings)
        val src = source(settings.getJSONObject("source"))
        if (src.source == TranslationDownloadSource.CUSTOM) com.lmreader.core.translation.TranslationModelCatalog.validateCustomBase(src.customBaseUrl)
        TranslationWorkflowStore(root) // Uses the same decoder as the live workflow store.
        val order = JSONObject(File(root, "queue-order.json").readText())
        order.getJSONArray("manga"); order.getJSONObject("chapters")
        if (File(root, "video-library.json").isFile) {
            require(JSONObject(File(root, "video-library.json").readText()).getInt("schema") == 1)
            if (requireArtifacts) MediaTaskBackup.validateVideoLibrary(File(root, "videos"))
        }
        if (File(root, MediaTaskBackup.FILE).isFile) {
            require(File(root, "video-library.json").isFile) { "媒体任务备份缺少视频清单" }
            MediaTaskBackup.validate(
                container.workDatabase.openHelper.writableDatabase,
                root,
                if (requireArtifacts) File(root, "videos") else null,
                semantic = strictMedia,
            )
        }
        val store = ReaderPageArtifactStore(File(root, "pages"))
        store.root.listFiles().orEmpty().forEach { file ->
            val envelope = JSONObject(file.readText()); val document = JSONObject(envelope.getString("document"))
            require(store.load(document.getString("pageId"), document.getString("sourceSha256"))?.dataFile?.name == file.name)
        }
    }

    private suspend fun apply(root: File, restored: Boolean) {
        container.database.withTransaction {
            BackupDatabase.restore(container.database.openHelper.writableDatabase, root, restored)
            prefs.restore(JSONObject(File(root, "preferences.json").readText()))
            container.apiProfiles.replaceAll(ApiProfileCodec.decode(File(root, "api.json").readText()))
            val settings = JSONObject(File(root, "settings.json").readText())
            shared("general-settings", settings.getJSONObject("general"))
            val export = settings.getJSONObject("export")
            if (restored) {
                for (key in listOf("multi", "single")) { if (export.has(key)) export.put("previous-$key", export.getString(key)); export.remove(key) }
                export.put("queue-paused", true)
            }
            shared("export-settings", export)
            // Optional sections: an old backup without them preserves the current local values.
            settings.optJSONObject("local-asr-settings")?.let { shared("local-asr-settings", it) }
            settings.optJSONObject("video-player")?.let { shared("video-player", it) }
            settings.optJSONObject("unified-task-settings")?.let { shared("unified-task-settings", it) }
            container.translationSources.save(source(settings.getJSONObject("source")))
            val pages = container.localPageTranslator.artifacts.root
            pages.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.forEach { check(it.delete()) }
            File(root, "pages").listFiles().orEmpty().forEach { file -> ExportFiles.commit(File(pages, file.name)) { output -> file.inputStream().use { it.copyTo(output) } } }
            val workflow = File(context.filesDir, "translation-workflows/workflows.json"); workflow.parentFile!!.mkdirs()
            ExportFiles.commit(workflow) { it.write(File(root, "workflows.json").readBytes()) }
            val order = JSONObject(File(root, "queue-order.json").readText())
            if (restored) order.put("paused", true)
            ExportFiles.commit(File(context.filesDir, "translation-queue-order.json")) { it.write(order.toString().toByteArray(Charsets.UTF_8)) }
            val videos = File(context.filesDir, "videos").canonicalFile
            check(videos.parentFile == context.filesDir.canonicalFile) { "视频目录归属无效" }
            if (File(root, "video-library.json").isFile) {
                val incoming = File(root, "videos")
                // New media restores merge; only the local rollback path may replace exactly.
                if (restored) MediaTaskBackup.mergeVideoLibrary(incoming, videos)
                else MediaTaskBackup.replaceVideoLibrary(incoming, videos)
            }
            if (File(root, MediaTaskBackup.FILE).isFile) {
                container.workDatabase.withTransaction {
                    MediaTaskBackup.restore(
                        container.workDatabase.openHelper.writableDatabase,
                        root,
                        restored,
                        videos,
                    )
                }
            }
        }
    }
    private fun shared(name: String, json: JSONObject) {
        val editor = context.getSharedPreferences(name, 0).edit().clear()
        json.keys().forEach { key -> when (val value = json.get(key)) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) editor.putInt(key, value.toInt())
                else editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Double -> editor.putFloat(key, value.toFloat())
            else -> error("无效的通用设置")
        } }
        check(editor.commit()) { "无法恢复设置" }
    }

    private fun asrSettingsSnapshot(): JSONObject {
        val values = context.getSharedPreferences("local-asr-settings", 0)
        return JSONObject().apply {
            put("wifi_only", values.getBoolean("wifi_only", true))
            put("threads", values.getInt("threads", Runtime.getRuntime().availableProcessors().coerceIn(1, 4)).coerceIn(1, 64))
            values.getString("selected_model_id", null)
                ?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")) }
                ?.let { put("selected_model_id", it) }
        }
    }

    private fun videoPlayerSettingsSnapshot(): JSONObject {
        val values = context.getSharedPreferences("video-player", 0)
        return JSONObject()
            .put("speed", values.getFloat("speed", 1f).coerceIn(0.25f, 4f).toDouble())
            .put("background_playback", values.getBoolean("background_playback", false))
    }

    private fun unifiedTaskSettingsSnapshot(): JSONObject {
        val values = context.getSharedPreferences("unified-task-settings", 0)
        return JSONObject().put("paused", values.getBoolean("paused", false))
            .apply { values.getString("order-mode", null)?.let { put("order-mode", it) } }
    }

    /** Strict whitelist for the optional prefs sections; absent sections preserve local values. */
    private fun validateOptionalSettings(settings: JSONObject) {
        if (settings.has("local-asr-settings")) {
            val json = settings.get("local-asr-settings") as? JSONObject ?: error("本地 ASR 设置类型无效")
            require(json.keys().asSequence().all { it in ASR_SETTINGS_KEYS }) { "本地 ASR 设置包含未知键" }
            require(json.keys().asSequence().none { json.get(it) == JSONObject.NULL }) { "本地 ASR 设置包含空值" }
            if (json.has("wifi_only")) require(json.get("wifi_only") is Boolean) { "本地 ASR 离线设置无效" }
            if (json.has("threads")) {
                val value = json.get("threads")
                // The live store reads an Int; do not accept a JSON floating point for this key.
                require((value is Int || value is Long) && integerSetting(value) in 1L..64L) { "本地 ASR 线程数无效" }
            }
            if (json.has("selected_model_id")) {
                val value = json.get("selected_model_id")
                require(value is String && value.length in 1..160 &&
                    value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*"))) { "本地 ASR 模型编号无效" }
            }
        }
        if (settings.has("video-player")) {
            val json = settings.get("video-player") as? JSONObject ?: error("视频播放器设置类型无效")
            require(json.keys().asSequence().all { it in VIDEO_PLAYER_KEYS }) { "视频播放器设置包含未知键" }
            require(json.keys().asSequence().none { json.get(it) == JSONObject.NULL }) { "视频播放器设置包含空值" }
            if (json.has("speed")) {
                val value = json.get("speed")
                // The player reads a Float; require the exact floating-point JSON shape we write.
                require(value is Double && value.isFinite() && value in 0.25..4.0) { "视频播放速度无效" }
            }
            if (json.has("background_playback")) require(json.get("background_playback") is Boolean) { "后台播放设置无效" }
        }
        if (settings.has("unified-task-settings")) {
            val json = settings.get("unified-task-settings") as? JSONObject ?: error("统一任务设置类型无效")
            require(json.keys().asSequence().all { it in UNIFIED_TASK_SETTINGS_KEYS }) { "统一任务设置包含未知键" }
            require(json.keys().asSequence().none { json.get(it) == JSONObject.NULL }) { "统一任务设置包含空值" }
            if (json.has("paused")) require(json.get("paused") is Boolean) { "统一任务暂停设置无效" }
            if (json.has("order-mode")) require(json.get("order-mode") in setOf("ORDER", "RESOURCES")) { "统一任务调度设置无效" }
        }
    }

    private fun integerSetting(value: Any): Long {
        val number = value as Number
        val double = number.toDouble()
        require(double.isFinite() && double == Math.floor(double)) { "设置整数无效" }
        return number.toLong()
    }

    private suspend fun reload() {
        container.generalPreferences.reload(); container.exportSettings.reload()
        container.translationWorkflows.reload(); container.queueOrder.reload()
        container.videoLibrary.reload()
        container.videoSessions.reload()
        container.taskScheduler.reloadPreferences()
        container.asrModels.reloadSettings()
    }

    private companion object {
        val SETTINGS_SECTIONS = setOf(
            "general", "export", "source", "local-asr-settings", "video-player", "unified-task-settings",
        )
        val ASR_SETTINGS_KEYS = setOf("wifi_only", "threads", "selected_model_id")
        val VIDEO_PLAYER_KEYS = setOf("speed", "background_playback")
        val UNIFIED_TASK_SETTINGS_KEYS = setOf("paused", "order-mode")
    }
}
