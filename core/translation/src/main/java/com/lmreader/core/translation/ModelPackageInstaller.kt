package com.lmreader.core.translation

import com.lmreader.core.model.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Only complete, hash-verified files from validated catalog snapshots become installed models. */
class ModelPackageInstaller(val root: File, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()) {
    init { check(root.mkdirs() || root.isDirectory) }
    private fun directory(pack: TranslationModelPack, suffix: String): File {
        require(pack.id.matches(Regex("[A-Za-z0-9-]+")) && TranslationModelCatalog.safeVersion(pack.version)) { "非法语言包标识" }
        require(pack.files.isNotEmpty() && pack.files.all { it.name.matches(Regex("[A-Za-z0-9._-]+")) && it.name !in listOf(".","..") }) { "非法模型文件名" }
        val dir = File(root,"${pack.id}-${pack.version}$suffix")
        require(dir.canonicalFile.parentFile == root.canonicalFile) { "语言包目录越界" }
        return dir
    }
    fun installedDirectory(pack: TranslationModelPack) = directory(pack, "")
    private fun staging(pack: TranslationModelPack) = directory(pack, ".staging")
    private fun marker(pack: TranslationModelPack) = pack.files.joinToString("\n") { "${it.name}:${it.size}:${it.gitBlobSha1 ?: it.sha256}" }
    fun isInstalled(pack: TranslationModelPack): Boolean {
        val dir = installedDirectory(pack)
        return runCatching { File(dir, "verified.txt").readText() == marker(pack) && pack.files.all { File(dir, it.name).length() == it.size } }.getOrDefault(false)
    }
    fun hasPartial(pack: TranslationModelPack) = staging(pack).isDirectory
    /** Saved descriptors preserve installed versions when the source advertises a newer one. */
    fun knownPackages(fallback: List<TranslationModelPack> = emptyList()): List<TranslationModelPack> {
        val saved=root.listFiles().orEmpty().filter {it.isDirectory}.mapNotNull { directory -> runCatching {
            require(directory.canonicalFile.parentFile==root.canonicalFile)
            val file=File(directory,"package.json");require(file.length() in 1..131_072)
            val pack=TranslationModelCatalog.parsePack(Json.parseToJsonElement(file.readText()).jsonObject)
            TranslationModelCatalog.validatePack(pack)
            require(directory.canonicalFile == installedDirectory(pack).canonicalFile || directory.canonicalFile == staging(pack).canonicalFile)
            pack
        }.getOrNull() }
        return (saved+fallback.filter {isInstalled(it) || hasPartial(it)}).distinctBy {it.identity}
    }
    suspend fun verifyInstalled(pack: TranslationModelPack) = withContext(Dispatchers.IO) {
        require(isInstalled(pack)) { "语言包未安装" }
        pack.files.forEach { ensureActive(); verify(File(installedDirectory(pack), it.name), it) }
    }

    suspend fun download(pack: TranslationModelPack, url: (TranslationModelFile) -> String,
        progress: (TranslationPackState) -> Unit) = withContext(Dispatchers.IO) {
        if (isInstalled(pack)) return@withContext
        val dir = staging(pack).apply { mkdirs() }
        atomicText(File(dir,"package.json"),TranslationModelCatalog.packJson(pack).toString())
        require(dir.usableSpace > pack.installedBytes * 2 + pack.downloadBytes) { "剩余空间不足，至少需要 ${pack.installedBytes * 2 + pack.downloadBytes} 字节" }
        var completed = 0L
        pack.files.forEach { asset ->
            ensureActive()
            val raw = File(dir, asset.name)
            if (runCatching { verify(raw, asset) }.isSuccess) { completed += asset.downloadSize; return@forEach }
            raw.delete()
            val compressed = File(dir, "${asset.name}.part")
            val source = File(dir, "${asset.name}.source")
            val address = url(asset)
            if (!source.exists() || source.readText() != address) { compressed.delete(); File(dir, "${asset.name}.etag").delete(); atomicText(source, address) }
            downloadFile(address, compressed, asset.size * 2 + 1_048_576) { bytes ->
                progress(TranslationPackState(TranslationPackStatus.DOWNLOADING, completed + bytes, pack.downloadBytes))
            }
            ensureActive()
            progress(TranslationPackState(TranslationPackStatus.VERIFYING, completed, pack.downloadBytes))
            try {
                val input=compressed.inputStream().buffered()
                (if(asset.compression==TranslationFileCompression.GZIP) GZIPInputStream(input) else input).use { decoded -> writeVerified(decoded,raw,asset) }
            } catch (cancelled: CancellationException) {
                raw.delete(); throw cancelled
            } catch (failure: Exception) {
                raw.delete(); compressed.delete(); throw failure
            }
            compressed.delete(); File(dir, "${asset.name}.etag").delete()
            completed += asset.downloadSize
        }
        ensureActive()
        commit(pack, dir)
    }

    private suspend fun downloadFile(address: String, file: File, limit: Long, progress: (Long) -> Unit) {
        val offset = file.takeIf { it.exists() }?.length() ?: 0L
        val tag = File(file.parentFile, file.name.removeSuffix(".part") + ".etag")
        val builder = Request.Builder().url(address).header("Accept-Encoding", "identity")
        if (offset > 0) {
            builder.header("Range", "bytes=$offset-")
            if (tag.exists()) builder.header("If-Range", tag.readText())
        }
        val call = client.newCall(builder.build())
        val context = currentCoroutineContext()
        // Cancelling the caller closes blocked HTTP reads as well as checking the copy loop.
        val watcher = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                if (response.code == 416 && offset > 0) {
                    // A complete gzip may remain after a process death before verification.
                    require(response.header("Content-Range") == "bytes */$offset") { "续传位置不匹配，请取消下载后重试" }
                    progress(offset); return
                }
                require(response.isSuccessful && response.code in listOf(200, 206)) { "下载失败：HTTP ${response.code}" }
                val body = requireNotNull(response.body) { "下载响应没有内容" }
                val append = response.code == 206
                if (append) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range") ?: "")
                        ?: error("服务器返回无效的续传范围")
                    val (start, end, total) = range.groupValues.drop(1).map(String::toLong)
                    require(start == offset && end >= start && end < total && total <= limit) { "服务器返回错误的续传范围" }
                    require(body.contentLength() < 0 || body.contentLength() == end - start + 1) { "续传长度不匹配" }
                    if (tag.exists()) response.header("ETag")?.let { currentTag ->
                        require(currentTag == tag.readText()) { "续传文件版本已改变，请取消下载后重试" }
                    }
                }
                require(body.contentLength() <= limit) { "模型下载超出大小限制" }
                response.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }?.let { atomicText(tag, it) }
                var written = if (append) offset else 0L
                body.byteStream().use { input -> FileOutputStream(file, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        context.ensureActive()
                        val read = input.read(buffer); if (read < 0) break
                        written += read; require(written <= limit) { "模型下载超出大小限制" }
                        output.write(buffer, 0, read); progress(written)
                    }
                    output.fd.sync()
                } }
            }
        } catch (failure: Exception) { context.ensureActive(); throw failure }
        finally { withContext(NonCancellable) { watcher.cancelAndJoin() } }
    }

    suspend fun importZip(pack: TranslationModelPack, input: InputStream) = withContext(Dispatchers.IO) {
        if (isInstalled(pack)) return@withContext
        val dir = staging(pack).apply { mkdirs() }
        atomicText(File(dir,"package.json"),TranslationModelCatalog.packJson(pack).toString())
        val seen = mutableSetOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                ensureActive()
                val entry = zip.nextEntry ?: break
                val asset = pack.files.singleOrNull { it.name == entry.name }
                    ?: error("导入包包含未知文件或非法路径：${entry.name}")
                require(!entry.isDirectory && seen.add(entry.name)) { "导入包包含重复文件" }
                val raw = File(dir, asset.name)
                try { writeVerified(zip, raw, asset) } catch (failure: Exception) { raw.delete(); throw failure }
                zip.closeEntry()
            }
        }
        require(seen == pack.files.map { it.name }.toSet()) { "导入包缺少模型文件" }
        ensureActive(); commit(pack, dir)
    }

    private fun commit(pack: TranslationModelPack, dir: File) {
        pack.files.forEach { verify(File(dir, it.name), it) }
        atomicText(File(dir,"package.json"),TranslationModelCatalog.packJson(pack).toString())
        atomicText(File(dir, "verified.txt"), marker(pack))
        val installed = installedDirectory(pack)
        require(!installed.exists()) { "安装目录已存在，请删除损坏的语言包后重试" }
        // A directory rename on the same filesystem publishes all files together.
        check(dir.renameTo(installed)) { "语言包原子安装失败" }
    }
    fun discardPartial(pack: TranslationModelPack) { staging(pack).deleteRecursively() }
    fun remove(pack: TranslationModelPack) { installedDirectory(pack).deleteRecursively(); discardPartial(pack) }

    companion object {
        internal fun atomicText(file: File, value: String) {
            val temp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(temp).use { it.write(value.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            try { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING) }
        }
        internal fun verify(file: File, asset: TranslationModelFile) {
            require(file.isFile && file.length() == asset.size) { "模型文件大小不匹配：${asset.name}" }
            val hash = MessageDigest.getInstance(if (asset.gitBlobSha1 != null) "SHA-1" else "SHA-256")
            if (asset.gitBlobSha1 != null) hash.update("blob ${asset.size}\u0000".toByteArray(Charsets.UTF_8))
            val sha256 = asset.gitBlobSha1?.takeIf { asset.sha256.isNotEmpty() }?.let { MessageDigest.getInstance("SHA-256") }
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n); sha256?.update(buffer, 0, n) }
            }
            require(hash.digest().joinToString("") { "%02x".format(it) } == (asset.gitBlobSha1 ?: asset.sha256)) { "模型校验失败：${asset.name}" }
            if (sha256 != null) require(sha256.digest().joinToString("") { "%02x".format(it) } == asset.sha256) { "模型校验失败：${asset.name}" }
        }
        private suspend fun writeVerified(input: InputStream, file: File, asset: TranslationModelFile) {
            var size = 0L
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    size += n; require(size <= asset.size) { "解压后文件过大：${asset.name}" }
                    output.write(buffer, 0, n)
                }
                output.fd.sync()
            }
            verify(file, asset)
        }
    }
}
