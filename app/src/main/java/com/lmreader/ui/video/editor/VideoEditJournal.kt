package com.lmreader.ui.video.editor

import com.lmreader.di.AppContainer
import com.lmreader.ui.reader.translation.PageTranslationRevisionConflict
import com.lmreader.ui.reader.translation.ReaderPageArtifactStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Crash journal for the video bubble editor's two-part write (artifact file + Room rows).
 *
 * The journal lives under the private no-backup directory and stores the exact artifact
 * envelope that existed before `saveEdits`. Recovery only trusts paths derived from the
 * journal's validated video/page ids; no path from the journal is opened directly.
 */
class VideoEditJournal private constructor(
    private val container: AppContainer,
    val journalId: String,
    val videoId: String,
    val sourceHash: String,
    val pageId: String,
    val originalRevision: String,
    private val preimage: ByteArray?,
    private val artifactFile: File,
    private val journalDir: File,
    private val journalFile: File,
) {
    @Volatile private var writtenRevision: String? = null
    @Volatile private var aborted = false

    /**
     * The Room transaction committed. Fsync the new artifact and remove the journal.
     * The caller only invokes this after `withTransaction` returned successfully.
     */
    suspend fun commitAfterRoom() = withContext(NonCancellable + Dispatchers.IO) {
        validateJournalFiles()
        if (artifactFile.isFile) syncFile(artifactFile)
        preimageFile()?.takeIf { it.isFile }?.let(::syncFile)
        if (journalFile.isFile) syncFile(journalFile)
        clearJournal()
    }

    /**
     * Called after a failed save, or by startup recovery. Uses the durable frame index as
     * the commit witness: all frame rows at the new revision means the transaction committed;
     * old/absent rows means the artifact must be rolled back.
     */
    suspend fun resolveAfterFailure() = withContext(NonCancellable + Dispatchers.IO) {
        validateJournalFiles()
        if (aborted) {
            clearJournal()
            return@withContext
        }
        when (classifyWrite()) {
            WriteState.COMMITTED -> clearJournal()
            WriteState.ROLLBACK -> {
                restorePreimage()
                clearJournal()
            }
            WriteState.CONFLICT -> throw IllegalStateException(
                "视频编辑恢复日志与当前画面成果冲突，已保留日志且未覆盖文件",
            )
        }
    }

    private suspend fun classifyWrite(): WriteState {
        val currentBytes = if (artifactFile.isFile) readBounded(artifactFile, MAX_ARTIFACT_BYTES) else null
        val current = currentBytes?.let { readArtifactRevision(it, pageId) }
        val old = originalRevision.ifEmpty { null }
        val written = writtenRevision

        // A current revision that is neither our preimage nor the revision we wrote is
        // a third-party edit; never guess over it.
        if (written != null && current != null && current != old && current != written) {
            return WriteState.CONFLICT
        }

        val rows = container.videoResults.results().allFrames()
            .filter { it.pageId == pageId }
        val revisions = rows.map { it.resultRevision }.distinct()

        if (revisions.isEmpty()) {
            return if (current == old) WriteState.COMMITTED else WriteState.ROLLBACK
        }
        if (current != null && revisions.all { it == current }) return WriteState.COMMITTED
        if (old != null && revisions.all { it == old }) {
            return if (current == old) WriteState.COMMITTED else WriteState.ROLLBACK
        }
        return WriteState.CONFLICT
    }

    private fun restorePreimage() {
        validateArtifactPath()
        val bytes = preimage
        if (bytes == null) {
            if (artifactFile.exists() && !artifactFile.delete()) {
                throw IOException("无法移除尚未提交的视频画面成果")
            }
            syncDirectory(artifactFile.parentFile)
            return
        }
        val temporary = File(artifactFile.parentFile, artifactFile.name + ".journal-" + UUID.randomUUID())
        try {
            writeSynced(temporary, bytes)
            if (!temporary.renameTo(artifactFile)) {
                throw IOException("无法恢复视频画面成果")
            }
            syncDirectory(artifactFile.parentFile)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun clearJournal() {
        validateJournalFiles()
        deleteJournalFiles()
    }

    private fun deleteJournalFiles() {
        val root = journalRoot(container).canonicalFile
        require(journalDir.canonicalFile.parentFile == root) { "视频编辑恢复日志路径非法" }
        require(journalDir.isDirectory) { "视频编辑恢复日志已丢失" }
        val children = journalDir.listFiles().orEmpty()
        for (child in children) {
            require(child.canonicalFile.parentFile == journalDir.canonicalFile) {
                "视频编辑恢复日志包含非法路径"
            }
            require(!child.isDirectory) { "视频编辑恢复日志包含非法子目录" }
            if (!child.delete()) throw IOException("无法清理视频编辑恢复日志")
        }
        if (!journalDir.delete()) throw IOException("无法清理视频编辑恢复日志")
        syncDirectory(journalDir.parentFile)
    }

    private fun validateJournalFiles() {
        val root = journalRoot(container).canonicalFile
        require(journalDir.canonicalFile.parentFile == root) { "视频编辑恢复日志路径非法" }
        require(journalDir.isDirectory) { "视频编辑恢复日志已丢失" }
        val allowed = setOf(JOURNAL_FILE, PREIMAGE_FILE, JOURNAL_FILE + ".tmp")
        for (child in journalDir.listFiles().orEmpty()) {
            require(child.canonicalFile.parentFile == journalDir.canonicalFile &&
                child.name in allowed && !child.isDirectory) { "视频编辑恢复日志包含非法文件" }
        }
        val record = readRecord(journalDir)
        require(record.journalId == journalId && record.videoId == videoId &&
            record.sourceHash == sourceHash && record.pageId == pageId) {
            "视频编辑恢复日志身份不一致"
        }
        require(record.artifactName == artifactFile.name && record.originalRevision == originalRevision &&
            record.preimagePresent == (preimage != null) && record.aborted == aborted) {
            "视频编辑恢复日志身份不一致"
        }
        if (record.preimagePresent) {
            val actual = readBounded(preimageFile(), MAX_ARTIFACT_BYTES)
            require(sha256(actual) == record.preimageChecksum) { "视频编辑恢复日志校验失败" }
            require(readArtifactRevision(actual, pageId) == record.originalRevision) {
                "视频编辑恢复日志原始成果不一致"
            }
        }
        validateArtifactPath()
    }

    private fun validateArtifactPath() {
        val expected = artifactFile(container, videoId, pageId)
        require(expected.canonicalFile == artifactFile.canonicalFile) { "视频画面成果路径非法" }
    }

    private fun preimageFile() = File(journalDir, PREIMAGE_FILE)

    private fun record(): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("journalId", journalId)
        .put("videoId", videoId)
        .put("sourceHash", sourceHash)
        .put("pageId", pageId)
        .put("artifactName", artifactFile.name)
        .put("preimagePresent", preimage != null)
        .put("originalRevision", originalRevision)
        .put("preimageChecksum", preimage?.let(::sha256).orEmpty())
        .put("preimageBytes", preimage?.size ?: 0)
        .put("writtenRevision", writtenRevision ?: JSONObject.NULL)
        .put("aborted", aborted)

    /** Persist the revision produced by `saveEdits` before the Room commit decides rollback. */
    suspend fun recordWrittenRevision(revision: String) = withContext(NonCancellable + Dispatchers.IO) {
        require(revision.isNotBlank()) { "视频画面成果修订为空" }
        validateJournalFiles()
        writtenRevision = revision
        writeRecord(journalDir, journalFile, record())
    }

    /**
     * The failure happened before `saveEdits` could replace the artifact. Recovery must
     * discard this journal without ever rolling a third-party artifact back.
     */
    suspend fun abandonAfterAbortedWrite() = withContext(NonCancellable + Dispatchers.IO) {
        validateJournalFiles()
        aborted = true
        val marked = runCatching { writeRecord(journalDir, journalFile, record()) }
        if (marked.isFailure) {
            // Without the aborted marker recovery would see a preimage-only/incomplete
            // journal and discard it without touching the artifact.
            runCatching { journalFile.delete() }
        }
        if (marked.isSuccess) clearJournal() else deleteJournalFiles()
    }

    private enum class WriteState { COMMITTED, ROLLBACK, CONFLICT }

    companion object {
        private const val SCHEMA = 1
        private const val JOURNAL_DIR = "video-edit-journal"
        private const val JOURNAL_FILE = "journal.json"
        private const val PREIMAGE_FILE = "preimage.bin"
        private const val MAX_ARTIFACT_BYTES = 8_000_000L
        private const val MAX_JOURNAL_BYTES = 64_000L
        private val VIDEO_ID = Regex("[a-f0-9-]{36}")
        private val SOURCE_HASH = Regex("[a-f0-9]{64}")

        /**
         * Capture the current artifact envelope and persist it before the caller writes
         * `saveEdits`. Throws a revision conflict when the draft no longer matches.
         */
        suspend fun prepare(
            container: AppContainer,
            videoId: String,
            sourceHash: String,
            pageId: String,
            expectedRevision: String,
        ): VideoEditJournal = withContext(NonCancellable + Dispatchers.IO) {
            require(videoId.matches(VIDEO_ID)) { "视频标识非法" }
            require(sourceHash.matches(SOURCE_HASH)) { "视频源版本非法" }
            validatePageId(videoId, pageId)
            val artifact = artifactFile(container, videoId, pageId)
            val bytes = if (artifact.isFile) readBounded(artifact, MAX_ARTIFACT_BYTES) else null
            if (bytes == null) {
                if (expectedRevision.isNotEmpty()) throw PageTranslationRevisionConflict()
            } else {
                val actualRevision = readArtifactRevision(bytes, pageId)
                if (actualRevision != expectedRevision) throw PageTranslationRevisionConflict()
            }
            val original = bytes?.let { readArtifactRevision(it, pageId) }.orEmpty()

            val root = journalRoot(container)
            check(root.isDirectory || root.mkdirs()) { "无法建立视频编辑恢复目录" }
            val id = UUID.randomUUID().toString()
            val directory = File(root, id)
            check(directory.mkdir()) { "无法建立视频编辑恢复日志" }
            try {
                if (bytes != null) writeSynced(File(directory, PREIMAGE_FILE), bytes)
                val file = File(directory, JOURNAL_FILE)
                val initial = JSONObject()
                    .put("schema", SCHEMA)
                    .put("journalId", id)
                    .put("videoId", videoId)
                    .put("sourceHash", sourceHash)
                    .put("pageId", pageId)
                    .put("artifactName", artifact.name)
                    .put("preimagePresent", bytes != null)
                    .put("originalRevision", original)
                    .put("preimageChecksum", bytes?.let(::sha256).orEmpty())
                    .put("preimageBytes", bytes?.size ?: 0)
                    .put("writtenRevision", JSONObject.NULL)
                    .put("aborted", false)
                writeRecord(directory, file, initial)
                VideoEditJournal(
                    container = container,
                    journalId = id,
                    videoId = videoId,
                    sourceHash = sourceHash,
                    pageId = pageId,
                    originalRevision = original,
                    preimage = bytes,
                    artifactFile = artifact,
                    journalDir = directory,
                    journalFile = file,
                )
            } catch (failure: Throwable) {
                directory.deleteRecursively()
                throw failure
            }
        }

        /** Startup recovery. Never waits for `startupReady`; callers must have no writers. */
        suspend fun recover(container: AppContainer) {
            withContext(Dispatchers.IO) {
                val root = journalRoot(container)
                if (!root.exists()) return@withContext
                check(root.isDirectory) { "视频编辑恢复目录非法" }
                var firstFailure: Throwable? = null
                val entries = root.listFiles().orEmpty().sortedBy { it.name }
                for (entry in entries) {
                    try {
                        if (entry.isFile) throw IOException("视频编辑恢复目录包含非法文件")
                        require(entry.name.matches(VIDEO_ID)) { "视频编辑恢复目录名称非法" }
                        val journal = load(container, entry) ?: continue
                        journal.resolveAfterFailure()
                    } catch (failure: Throwable) {
                        if (firstFailure == null) firstFailure = failure
                    }
                }
                firstFailure?.let {
                    throw IllegalStateException("视频编辑恢复失败，日志已保留", it)
                }
            }
        }

        /** Removes an incomplete preimage-only directory; no artifact write can precede metadata. */
        private fun load(container: AppContainer, directory: File): VideoEditJournal? {
            validateJournalDir(container, directory)
            val metadata = File(directory, JOURNAL_FILE)
            for (child in directory.listFiles().orEmpty()) {
                require(child.canonicalFile.parentFile == directory.canonicalFile &&
                    child.name in setOf(JOURNAL_FILE, PREIMAGE_FILE, JOURNAL_FILE + ".tmp") &&
                    !child.isDirectory) { "视频编辑恢复目录包含非法文件" }
            }
            if (!metadata.isFile) {
                val children = directory.listFiles().orEmpty()
                for (child in children) {
                    require(child.canonicalFile.parentFile == directory.canonicalFile &&
                        child.name in setOf(PREIMAGE_FILE, JOURNAL_FILE + ".tmp") &&
                        !child.isDirectory) { "视频编辑恢复目录包含非法文件" }
                }
                for (child in children) if (!child.delete()) throw IOException("无法清理不完整的恢复日志")
                if (!directory.delete()) throw IOException("无法清理不完整的恢复日志")
                return null
            }
            val record = readRecord(directory)
            require(record.schema == SCHEMA) { "视频编辑恢复日志版本不支持" }
            require(record.journalId == directory.name && record.videoId.matches(VIDEO_ID) &&
                record.sourceHash.matches(SOURCE_HASH)) { "视频编辑恢复日志身份非法" }
            validatePageId(record.videoId, record.pageId)
            require(record.artifactName == artifactName(record.pageId)) { "视频编辑恢复日志路径非法" }
            require(record.originalRevision.length <= 128) { "视频编辑恢复日志修订非法" }
            require(record.writtenRevision == null || record.writtenRevision.length <= 128) {
                "视频编辑恢复日志修订非法"
            }

            val preimage = if (record.preimagePresent) {
                val file = File(directory, PREIMAGE_FILE)
                require(file.isFile && file.length() <= MAX_ARTIFACT_BYTES) { "视频编辑恢复日志备份缺失" }
                readBounded(file, MAX_ARTIFACT_BYTES).also { bytes ->
                    require(bytes.size.toLong() == record.preimageBytes) { "视频编辑恢复日志大小不一致" }
                    require(sha256(bytes) == record.preimageChecksum) { "视频编辑恢复日志校验失败" }
                    require(record.originalRevision.isNotEmpty() &&
                        readArtifactRevision(bytes, record.pageId) == record.originalRevision) {
                        "视频编辑恢复日志原始成果不一致"
                    }
                }
            } else {
                require(record.originalRevision.isEmpty()) { "视频编辑恢复日志缺少原始成果" }
                require(!File(directory, PREIMAGE_FILE).exists()) { "视频编辑恢复日志备份非法" }
                null
            }
            val artifact = artifactFile(container, record.videoId, record.pageId)
            require(artifact.name == record.artifactName) { "视频画面成果路径非法" }
            return VideoEditJournal(
                container = container,
                journalId = record.journalId,
                videoId = record.videoId,
                sourceHash = record.sourceHash,
                pageId = record.pageId,
                originalRevision = record.originalRevision,
                preimage = preimage,
                artifactFile = artifact,
                journalDir = directory,
                journalFile = metadata,
            ).also {
                it.writtenRevision = record.writtenRevision
                it.aborted = record.aborted
            }
        }

        private data class Record(
            val schema: Int,
            val journalId: String,
            val videoId: String,
            val sourceHash: String,
            val pageId: String,
            val artifactName: String,
            val preimagePresent: Boolean,
            val originalRevision: String,
            val preimageChecksum: String,
            val preimageBytes: Long,
            val writtenRevision: String?,
            val aborted: Boolean,
        )

        private fun readRecord(directory: File): Record {
            require(directory.isDirectory) { "视频编辑恢复目录已丢失" }
            val file = File(directory, JOURNAL_FILE)
            require(file.isFile && file.length() <= MAX_JOURNAL_BYTES) { "视频编辑恢复日志缺失" }
            val json = JSONObject(readBounded(file, MAX_JOURNAL_BYTES).toString(Charsets.UTF_8))
            return Record(
                schema = json.getInt("schema"),
                journalId = json.getString("journalId"),
                videoId = json.getString("videoId"),
                sourceHash = json.getString("sourceHash"),
                pageId = json.getString("pageId"),
                artifactName = json.getString("artifactName"),
                preimagePresent = json.getBoolean("preimagePresent"),
                originalRevision = json.optString("originalRevision"),
                preimageChecksum = json.optString("preimageChecksum"),
                preimageBytes = json.optLong("preimageBytes"),
                writtenRevision = json.optString("writtenRevision").takeIf { it.isNotBlank() && it != "null" },
                aborted = json.optBoolean("aborted", false),
            )
        }

        private fun writeRecord(directory: File, destination: File, json: JSONObject) {
            require(directory.isDirectory) { "视频编辑恢复目录已丢失" }
            val temporary = File(directory, JOURNAL_FILE + ".tmp")
            writeSynced(temporary, json.toString().toByteArray(Charsets.UTF_8))
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IOException("无法写入视频编辑恢复日志")
            }
            syncDirectory(directory)
        }

        private fun validateJournalDir(container: AppContainer, directory: File) {
            val root = journalRoot(container).canonicalFile
            require(directory.canonicalFile.parentFile == root) { "视频编辑恢复目录路径非法" }
            require(directory.isDirectory) { "视频编辑恢复目录已丢失" }
        }

        private fun journalRoot(container: AppContainer): File =
            File(container.applicationContext.noBackupFilesDir, JOURNAL_DIR)

        private fun artifactName(pageId: String): String =
            ReaderPageArtifactStore.sha256(pageId.toByteArray(Charsets.UTF_8)) + ".json"

        private fun artifactFile(container: AppContainer, videoId: String, pageId: String): File {
            require(videoId.matches(VIDEO_ID)) { "视频标识非法" }
            validatePageId(videoId, pageId)
            val libraryRoot = container.videoLibrary.root.canonicalFile
            val root = container.videoLibrary.artifacts(videoId).root.canonicalFile
            require(root == File(libraryRoot, "$videoId${File.separator}frames").canonicalFile) {
                "视频画面成果目录非法"
            }
            require(root.isDirectory || root.mkdirs()) { "视频画面成果目录不可用" }
            val file = File(root, artifactName(pageId)).canonicalFile
            require(file.parentFile == root) { "视频画面成果路径非法" }
            return file
        }

        private fun validatePageId(videoId: String, pageId: String) {
            require(pageId.startsWith("video:$videoId:") && pageId.length <= 512) { "画面标识非法" }
            require(pageId.none { it == '/' || it == '\\' || it.isISOControl() }) { "画面标识非法" }
        }

        private fun readArtifactRevision(bytes: ByteArray, pageId: String): String {
            require(bytes.size <= MAX_ARTIFACT_BYTES) { "视频画面成果过大" }
            val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
            val document = envelope.getString("document")
            val documentBytes = document.toByteArray(Charsets.UTF_8)
            require(documentBytes.size <= MAX_ARTIFACT_BYTES) { "视频画面成果过大" }
            require(envelope.getString("sha256") == sha256(documentBytes)) { "视频画面成果校验失败" }
            val json = JSONObject(document)
            require(json.getInt("schema") == 2 && json.getString("pageId") == pageId) {
                "视频画面成果身份不一致"
            }
            return json.getString("revision").also { require(it.isNotBlank()) { "视频画面成果修订为空" } }
        }

        private fun readBounded(file: File, max: Long): ByteArray {
            require(file.isFile) { "文件不存在" }
            require(file.length() <= max) { "文件过大" }
            val bytes = file.readBytes()
            require(bytes.size.toLong() <= max) { "文件过大" }
            return bytes
        }

        private fun writeSynced(file: File, bytes: ByteArray) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }

        private fun syncFile(file: File) {
            FileOutputStream(file, true).use { it.fd.sync() }
        }

        private fun syncDirectory(directory: File) {
            // Best effort: some test/JVM filesystems cannot open a directory descriptor.
            runCatching {
                val fd = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
                try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    init {
        require(journalId.matches(Regex("[a-f0-9-]{36}")))
        require(videoId.matches(Regex("[a-f0-9-]{36}")))
        require(sourceHash.matches(Regex("[a-f0-9]{64}")))
    }
}

/** Root startup hook; intended to run before `startupReady` is completed. */
suspend fun recoverVideoEditorWrites(container: AppContainer) = VideoEditJournal.recover(container)
