package com.lmreader.ui.video.player

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import com.lmreader.core.model.ResourceGovernor
import com.lmreader.core.model.InferencePriority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Immutable playlist snapshots for sessions too large to put in an Intent or a
 * saved-state Bundle.
 *
 * Only an opaque UUID token travels through Binder/Bundle. The snapshot is a
 * versioned, hash-checked file under `noBackupFilesDir/video-playlists`, so a
 * process recreation can rebuild the exact frozen order without relying on any
 * in-memory global. The file is written by a dedicated IO thread.
 */
object VideoPlaylistSnapshots {
    /** At or below this size the historical inline ArrayList is kept. */
    const val MAX_INLINE_IDS = 250

    private const val DIRECTORY = "video-playlists"
    private const val FILE_SUFFIX = ".snapshot"
    private const val HEADER = "LMREADER-VIDEO-PLAYLIST"
    private const val VERSION = 1
    /** Bounds a corrupt/hostile file read; it is not a playlist count limit. */
    private const val MAX_FILE_BYTES = 16 * 1024 * 1024
    private const val MAX_ID_CHARS = 1_024
    private val TOKEN_PATTERN = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    )

    fun isValidToken(token: String?): Boolean = token != null && TOKEN_PATTERN.matches(token)

    fun playlistsDir(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, DIRECTORY)

    /** The only accepted path shape: a canonical UUID token inside the private dir. */
    fun snapshotFile(context: Context, token: String): File {
        require(isValidToken(token)) { "Invalid playlist snapshot token" }
        return File(playlistsDir(context), "$token$FILE_SUFFIX")
    }

    /** LinkedHashSet keeps the caller's frozen order and appends [currentId] once. */
    fun deduplicate(playlistIds: List<String>, currentId: String): List<String> {
        val ids = LinkedHashSet<String>(playlistIds.size + 1)
        playlistIds.forEach { candidate ->
            if (candidate.isNotBlank()) ids.add(candidate)
        }
        if (currentId.isNotBlank()) ids.add(currentId)
        return ids.toList()
    }

    /**
     * Persists a new immutable snapshot and returns its token. The write itself runs on
     * the snapshot IO thread; callers keep the existing synchronous Intent API.
     */
    fun persistBlocking(context: Context, ids: List<String>): String {
        require(ids.isNotEmpty()) { "Cannot persist an empty playlist" }
        require(ids.none { it.isBlank() || '\n' in it || '\r' in it || it.length > MAX_ID_CHARS }) {
            "Playlist contains an unsupported id"
        }
        val token = UUID.randomUUID().toString()
        val file = snapshotFile(context, token)
        runBlocking(Dispatchers.IO + InferencePriority(ResourceGovernor.INTERACTIVE)) {
            ResourceGovernor.shared.use("io") { write(file, ids) }
        }
        return token
    }

    /** Returns null for missing/corrupt/invalid snapshots; never a partial playlist. */
    fun load(context: Context, token: String?): List<String>? {
        if (!isValidToken(token)) return null
        val file = snapshotFile(context, token!!)
        if (!file.isFile) return null
        return try {
            read(file)
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, token: String): Boolean =
        isValidToken(token) && try {
            snapshotFile(context, token).delete()
        } catch (_: Exception) {
            false
        }

    private fun write(file: File, ids: List<String>) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val payload = ids.joinToString("\n")
        val text = buildString {
            append(HEADER).append('\n')
            append(VERSION).append('\n')
            append(ids.size).append('\n')
            append(sha256(payload)).append('\n')
            append(payload)
        }
        var stream: FileOutputStream? = null
        try {
            val output = atomic.startWrite()
            stream = output
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            stream?.let(atomic::failWrite)
            throw error
        }
    }

    private fun read(file: File): List<String>? {
        if (file.length() > MAX_FILE_BYTES) return null
        val bytes = AtomicFile(file).openRead().use { input ->
            val out = ByteArrayOutputStream(
                minOf(file.length().toInt().coerceAtLeast(0), 64 * 1024),
            )
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_FILE_BYTES) return null
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        val lines = bytes.toString(Charsets.UTF_8).split('\n')
        if (lines.size < 4 || lines[0] != HEADER || lines[1] != VERSION.toString()) return null
        val count = lines[2].toIntOrNull() ?: return null
        // Exact shape: header, version, count, hash, then exactly `count` ids.
        if (count <= 0 || lines.size != count + 4) return null
        val ids = lines.subList(4, lines.size)
        val seen = HashSet<String>(ids.size)
        for (id in ids) {
            if (id.isBlank() || id.length > MAX_ID_CHARS || !seen.add(id)) return null
        }
        if (lines[3] != sha256(ids.joinToString("\n"))) return null
        return ids
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
