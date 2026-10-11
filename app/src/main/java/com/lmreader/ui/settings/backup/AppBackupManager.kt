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
            // Do not touch the destination until the entire package is validated.
            container.translationQueue.pause()
            container.exportQueue.pauseAll()
            container.taskService.cancelAndAwait()
            container.translationQueue.pauseAndAwait()
            container.exportQueue.pauseAndAwait()
            container.scanCoordinator.cancelAll().join()
            container.screenTranslation.stopAndAwait()
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
            validate(root)
            apply(root, restored = false)
            check(journal.delete()); root.deleteRecursively(); reload()
        } catch (error: Exception) { _failure.value = "本机恢复未完成：${error.message}"; throw error }
        finally { _recoveryRequired.value = journal.exists() }
    }

    private suspend fun <T> operation(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            container.startupReady.await()
            _busy.value = true; _failure.value = null
            try { block() } finally {
                // A failed rollback must also block the current session, before reopening.
                _recoveryRequired.value = journal.exists()
                _busy.value = false
            }
        }
    }
    private suspend fun snapshot(root: File, secrets: Boolean) {
        container.database.withTransaction { BackupDatabase.snapshot(container.database.openHelper.writableDatabase, root, secrets) }
        File(root, "preferences.json").writeText(prefs.snapshot().toString())
        val profiles = container.apiProfiles.profiles.first().map { if (secrets) it else it.copy(apiKey = "") }
        File(root, "api.json").writeText(ApiProfileCodec.encode(profiles))
        val source = container.translationSources.settings.first()
        val settings = JSONObject().put("general", JSONObject(context.getSharedPreferences("general-settings", 0).all))
            .put("export", JSONObject(context.getSharedPreferences("export-settings", 0).all))
            .put("source", JSONObject().put("source", source.source.name).put("base", source.customBaseUrl).put("wifi", source.wifiOnly))
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
        validate(root)
    }
    private fun source(json: JSONObject) = TranslationSourceSettings(
        TranslationDownloadSource.valueOf(json.getString("source")), json.getString("base"), json.getBoolean("wifi"))

    private fun validate(root: File) {
        BackupDatabase.validate(container.database.openHelper.writableDatabase, root)
        prefs.validate(JSONObject(File(root, "preferences.json").readText()))
        val api = ApiProfileCodec.decode(File(root, "api.json").readText())
        require(api.map { it.id }.distinct().size == api.size); api.forEach(ApiProtocol::validate)
        val settings = JSONObject(File(root, "settings.json").readText())
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
        val src = source(settings.getJSONObject("source"))
        if (src.source == TranslationDownloadSource.CUSTOM) com.lmreader.core.translation.TranslationModelCatalog.validateCustomBase(src.customBaseUrl)
        TranslationWorkflowStore(root) // Uses the same decoder as the live workflow store.
        val order = JSONObject(File(root, "queue-order.json").readText())
        order.getJSONArray("manga"); order.getJSONObject("chapters")
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
            container.translationSources.save(source(settings.getJSONObject("source")))
            val pages = container.localPageTranslator.artifacts.root
            pages.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.forEach { check(it.delete()) }
            File(root, "pages").listFiles().orEmpty().forEach { file -> ExportFiles.commit(File(pages, file.name)) { output -> file.inputStream().use { it.copyTo(output) } } }
            val workflow = File(context.filesDir, "translation-workflows/workflows.json"); workflow.parentFile!!.mkdirs()
            ExportFiles.commit(workflow) { it.write(File(root, "workflows.json").readBytes()) }
            val order = JSONObject(File(root, "queue-order.json").readText())
            if (restored) order.put("paused", true)
            ExportFiles.commit(File(context.filesDir, "translation-queue-order.json")) { it.write(order.toString().toByteArray(Charsets.UTF_8)) }
        }
    }
    private fun shared(name: String, json: JSONObject) {
        val editor = context.getSharedPreferences(name, 0).edit().clear()
        json.keys().forEach { key -> when (val value = json.get(key)) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            else -> error("无效的通用设置")
        } }
        check(editor.commit()) { "无法恢复设置" }
    }
    private suspend fun reload() {
        container.generalPreferences.reload(); container.exportSettings.reload()
        container.translationWorkflows.reload(); container.queueOrder.reload()
    }
}
