package com.lmreader.core.translation.asr

import android.os.Build
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun defaultAsrHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

/**
 * Installs one whisper.cpp GGML file per descriptor.
 *
 * Layout inside `noBackupFilesDir/asr-models`:
 * ```
 * .staging/<id>/descriptor.json
 * .staging/<id>/model.bin.part        <- resumable download / import staging
 * .staging/<id>/model.bin             <- staged after the .part is fsynced
 * <id>/model.bin
 * <id>/descriptor.json
 * <id>/manifest.json
 * ```
 * Only the exact descriptor bytes plus SHA-256 can be published. The whole verified staging
 * directory is renamed to `<id>` in one filesystem operation.
 */
internal class AsrModelInstaller(
    private val root: File,
    private val client: OkHttpClient = defaultAsrHttpClient(),
) {
    private val stagingRoot = File(root, ".staging")
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,79}")

    init {
        check(root.mkdirs() || root.isDirectory) { "无法创建 ASR 模型目录" }
    }

    fun installedDirectory(model: AsrModelDescriptor): File = child(root, model.id)

    fun stagingDirectory(model: AsrModelDescriptor): File = child(stagingRoot, model.id)

    fun modelFile(model: AsrModelDescriptor): File = File(installedDirectory(model), AsrManifest.MODEL_FILE)

    fun partialBytes(model: AsrModelDescriptor): Long =
        partFile(model).takeIf { it.isFile }?.length() ?: 0L

    fun hasPartial(model: AsrModelDescriptor): Boolean = partFile(model).isFile

    fun isInstalled(model: AsrModelDescriptor): Boolean {
        val directory = installedDirectory(model)
        if (!directory.isDirectory) return false
        return runCatching {
            val manifest = AsrManifest.read(File(directory, AsrManifest.MANIFEST_FILE))
            require(manifest.descriptor.id == model.id)
            require(manifest.descriptor.version == model.version)
            require(manifest.descriptor.sha256 == model.sha256)
            require(manifest.descriptor.bytes == model.bytes)
            require(File(directory, manifest.modelFile).length() == model.bytes)
            true
        }.getOrDefault(false)
    }

    /** Manifests that survived a process restart; each is still size-checked before use. */
    fun installedDescriptors(): List<AsrModelDescriptor> = root.listFiles().orEmpty()
        .filter { it.isDirectory && it.name != ".staging" }
        .mapNotNull { directory ->
            runCatching {
                require(directory.canonicalFile.parentFile == root.canonicalFile)
                val manifest = AsrManifest.read(File(directory, AsrManifest.MANIFEST_FILE))
                require(directory.name == manifest.descriptor.id)
                require(File(directory, manifest.modelFile).length() == manifest.descriptor.bytes)
                manifest.descriptor
            }.getOrNull()
        }

    suspend fun download(
        model: AsrModelDescriptor,
        onProgress: (Long) -> Unit,
        onVerifying: () -> Unit,
    ) {
        if (isInstalled(model)) return
        prepareStage(model)
        val part = partFile(model)
        if (part.length() > model.bytes) part.delete()
        val directory = stagingDirectory(model)
        val remaining = (model.bytes - part.length()).coerceAtLeast(0L)
        require(directory.usableSpace > remaining + FREE_SPACE_MARGIN) {
            "剩余空间不足：至少还需要 ${remaining + FREE_SPACE_MARGIN} 字节"
        }
        try {
            onProgress(part.length())
            transfer(model, part, onProgress)
            onVerifying()
            verifyFile(part, model)
            commit(model, part)
        } catch (cancelled: CancellationException) {
            // The verified prefix is intentionally kept. It is safe to resume from it
            // because every completed byte is covered by the final SHA-256 check.
            throw cancelled
        } catch (failure: Exception) {
            if (part.isFile && part.length() >= model.bytes) runCatching { part.delete() }
            throw failure
        }
    }

    suspend fun import(
        model: AsrModelDescriptor,
        input: InputStream,
        onProgress: (Long) -> Unit,
        onVerifying: () -> Unit,
    ) {
        if (isInstalled(model)) return
        prepareStage(model)
        val directory = stagingDirectory(model)
        val part = partFile(model)
        part.delete()
        File(directory, AsrManifest.MODEL_FILE).delete()
        require(directory.usableSpace > model.bytes + FREE_SPACE_MARGIN) {
            "剩余空间不足：至少还需要 ${model.bytes + FREE_SPACE_MARGIN} 字节"
        }
        try {
            writeExact(input, part, model.bytes, onProgress)
            onVerifying()
            verifyFile(part, model)
            commit(model, part)
        } catch (cancelled: CancellationException) {
            cleanupImport(model)
            throw cancelled
        } catch (failure: Exception) {
            cleanupImport(model)
            throw failure
        }
    }

    suspend fun verifyInstalled(model: AsrModelDescriptor) {
        require(isInstalled(model)) { "ASR 模型未安装或安装清单不完整" }
        verifyFile(File(installedDirectory(model), AsrManifest.MODEL_FILE), model)
    }

    fun discardPartial(model: AsrModelDescriptor) {
        stagingDirectory(model).deleteRecursively()
        AsrFileOps.fsyncDirectory(root)
    }

    fun delete(model: AsrModelDescriptor) {
        installedDirectory(model).deleteRecursively()
        stagingDirectory(model).deleteRecursively()
        AsrFileOps.fsyncDirectory(root)
    }

    private fun partFile(model: AsrModelDescriptor): File = File(stagingDirectory(model), "model.bin.part")

    private fun prepareStage(model: AsrModelDescriptor) {
        val directory = stagingDirectory(model)
        check(directory.mkdirs() || directory.isDirectory) { "无法创建 ASR 暂存目录" }
        val descriptorFile = File(directory, AsrManifest.DESCRIPTOR_FILE)
        val previous = runCatching {
            AsrCatalogJson.parseDescriptor(Json.parseToJsonElement(descriptorFile.readText()).jsonObject)
        }.getOrNull()
        if (previous != model) {
            // A stale prefix belongs to a different descriptor; it must never be resumed.
            partFile(model).delete()
            File(directory, AsrManifest.MODEL_FILE).delete()
            AsrFileOps.atomicText(descriptorFile, AsrCatalogJson.descriptorJson(model).toString())
        }
        // A previous process may have failed between renaming the .part and publishing the
        // directory. It is cheap to re-download than to trust an unpublished file.
        File(directory, AsrManifest.MODEL_FILE).delete()
    }

    private suspend fun transfer(
        model: AsrModelDescriptor,
        part: File,
        onProgress: (Long) -> Unit,
    ) {
        var restartAttempts = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            var offset = part.takeIf { it.isFile }?.length() ?: 0L
            if (offset > model.bytes) {
                part.delete()
                offset = 0L
            }
            if (offset == model.bytes) {
                onProgress(offset)
                return
            }
            val builder = Request.Builder().url(model.url).header("Accept-Encoding", "identity")
            if (offset > 0L) builder.header("Range", "bytes=$offset-")
            val call = client.newCall(builder.build())
            var restart = false
            executeWithCancellation(call) { response ->
                val body = requireNotNull(response.body) { "下载响应没有内容" }
                when (response.code) {
                    200 -> {
                        require(body.contentLength() < 0L || body.contentLength() == model.bytes) {
                            "服务器返回的模型大小与目录不一致"
                        }
                        copyBody(body, part, append = false, expected = model.bytes, onProgress)
                    }
                    206 -> {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
                            .matchEntire(response.header("Content-Range") ?: "")
                        if (range == null) {
                            part.delete()
                            restart = true
                            return@executeWithCancellation
                        }
                        val (start, end, total) = range.groupValues.drop(1).map(String::toLong)
                        if (start != offset || end < start || end >= model.bytes || total != model.bytes) {
                            // The server did not honor the exact requested offset/total.
                            // Drop the prefix and retry from zero without a Range header.
                            part.delete()
                            restart = true
                            return@executeWithCancellation
                        }
                        require(body.contentLength() < 0L || body.contentLength() == end - start + 1) {
                            "续传长度与 Content-Range 不一致"
                        }
                        copyBody(body, part, append = true, expected = model.bytes, onProgress)
                    }
                    416 -> {
                        if (part.length() == model.bytes &&
                            response.header("Content-Range") == "bytes */${model.bytes}") {
                            onProgress(part.length())
                            return@executeWithCancellation
                        }
                        part.delete()
                        restart = true
                    }
                    else -> error("下载失败：HTTP ${response.code}")
                }
            }
            if (restart) {
                restartAttempts += 1
                require(restartAttempts <= 1) { "服务器未按请求的续传位置返回数据，请重试" }
                continue
            }
            return
        }
    }

    private suspend fun copyBody(
        body: ResponseBody,
        part: File,
        append: Boolean,
        expected: Long,
        onProgress: (Long) -> Unit,
    ) {
        var written = if (append) part.length() else 0L
        var lastReported = written
        FileOutputStream(part, append).use { output ->
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    require(written <= expected) { "模型下载超出目录声明的大小" }
                    output.write(buffer, 0, read)
                    if (written - lastReported >= PROGRESS_INTERVAL || written == expected) {
                        onProgress(written)
                        lastReported = written
                    }
                }
            }
            output.fd.sync()
        }
        require(written == expected) { "下载未完成：$written / $expected 字节" }
        onProgress(written)
    }

    private suspend fun writeExact(
        input: InputStream,
        part: File,
        expected: Long,
        onProgress: (Long) -> Unit,
    ) {
        var written = 0L
        var lastReported = 0L
        FileOutputStream(part, false).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                written += read
                require(written <= expected) { "导入文件大于所选模型的大小" }
                output.write(buffer, 0, read)
                if (written - lastReported >= PROGRESS_INTERVAL || written == expected) {
                    onProgress(written)
                    lastReported = written
                }
            }
            output.fd.sync()
        }
        require(written == expected) { "导入文件大小不匹配：需要 $expected 字节，实际 $written 字节" }
        onProgress(written)
    }

    private suspend fun verifyFile(file: File, model: AsrModelDescriptor) {
        require(file.isFile && file.length() == model.bytes) {
            "模型文件大小不匹配：需要 ${model.bytes} 字节，实际 ${if (file.isFile) file.length() else 0} 字节"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().hex()
        require(actual == model.sha256) { "模型 SHA-256 校验失败" }
    }

    private fun commit(model: AsrModelDescriptor, part: File) {
        val directory = stagingDirectory(model)
        val staged = File(directory, AsrManifest.MODEL_FILE)
        require(part.isFile && part.length() == model.bytes) { "模型暂存文件大小不匹配" }
        require(!staged.exists() || staged.delete()) { "无法替换 ASR 暂存模型文件" }
        check(part.renameTo(staged)) { "ASR 模型暂存文件重命名失败" }
        AsrFileOps.atomicText(File(directory, AsrManifest.DESCRIPTOR_FILE),
            AsrCatalogJson.descriptorJson(model).toString())
        AsrManifest.write(File(directory, AsrManifest.MANIFEST_FILE), model)
        AsrFileOps.fsyncDirectory(directory)
        val installed = installedDirectory(model)
        require(!installed.exists()) { "ASR 模型安装目录已存在，请删除损坏的模型后重试" }
        // Directory rename publishes the model file, descriptor and manifest together.
        check(directory.renameTo(installed)) { "ASR 模型原子安装失败" }
        AsrFileOps.fsyncDirectory(stagingRoot)
        AsrFileOps.fsyncDirectory(root)
    }

    private fun cleanupImport(model: AsrModelDescriptor) {
        runCatching { stagingDirectory(model).deleteRecursively() }
        runCatching { AsrFileOps.fsyncDirectory(root) }
    }

    private suspend fun executeWithCancellation(call: Call, block: suspend (Response) -> Unit) {
        val completed = AtomicBoolean(false)
        val context = currentCoroutineContext()
        val watcher = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    if (!completed.get()) runCatching { call.cancel() }
                }
            }
        }
        try {
            call.execute().use { response -> block(response) }
        } catch (failure: Exception) {
            context.ensureActive()
            throw failure
        } finally {
            completed.set(true)
            withContext(NonCancellable) { watcher.cancelAndJoin() }
        }
    }

    private fun child(parent: File, id: String): File {
        require(id.matches(idPattern)) { "非法 ASR 模型 ID" }
        val directory = File(parent, id)
        require(directory.canonicalFile.parentFile == parent.canonicalFile) { "ASR 模型目录越界" }
        return directory
    }

    companion object {
        private const val FREE_SPACE_MARGIN = 8L * 1_048_576
        private const val PROGRESS_INTERVAL = 262_144L
    }
}

internal object AsrFileOps {
    fun atomicText(file: File, value: String) {
        val parent = file.parentFile ?: error("文件没有父目录")
        check(parent.mkdirs() || parent.isDirectory) { "无法创建目录：${parent.name}" }
        val temp = File(parent, file.name + ".tmp")
        FileOutputStream(temp).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        fsyncDirectory(parent)
    }

    /** Best effort: Android does not expose a portable directory fd API above the JNI layer. */
    fun fsyncDirectory(directory: File) {
        if (Build.VERSION.SDK_INT < 21) return
        runCatching {
            val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
